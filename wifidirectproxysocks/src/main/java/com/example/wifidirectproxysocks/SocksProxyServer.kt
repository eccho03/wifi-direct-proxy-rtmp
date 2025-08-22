package com.example.wifidirectproxysocks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SocksProxyServer(private val port: Int) {
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var isRunning = false
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeConnections = ConcurrentHashMap<String, Pair<Socket, Socket>>()
    private val udpAssociations = ConcurrentHashMap<String, UdpAssociation>()
    private val udpTargetMappings = ConcurrentHashMap<String, String>() // target -> client mapping
    private val clientSockets = mutableSetOf<Socket>()
    private val connectionCounter = AtomicInteger(0)
    private val maxConnections = 100 // Limit concurrent connections

    data class UdpAssociation(
        val clientAddress: InetAddress,
        val clientPort: Int,
        val udpRelaySocket: DatagramSocket,
        val associationId: String,
        var targetAddress: InetAddress? = null,
        var targetPort: Int? = null
    )

    /**
     * Starts the SOCKS5 proxy server on the specified port.
     * Accepts client connections, performs handshake, and relays traffic.
     */
    fun start() {
        serverScope.launch {
            try {
                serverSocket = ServerSocket(port)
                serverSocket?.reuseAddress = true
                serverSocket?.soTimeout = 1000 // 1초 타임아웃으로 주기적 체크
                serverSocket?.receiveBufferSize = 32768 // 버퍼 크기 증가

                // UDP 릴레이용 소켓 초기화
                udpSocket = DatagramSocket()
                udpSocket?.reuseAddress = true
                udpSocket?.soTimeout = 1000
                udpSocket?.receiveBufferSize = 65536 // UDP 버퍼 크기 증가
                udpSocket?.sendBufferSize = 65536

                isRunning = true
                println("SOCKS proxy server started on port $port")
                println("UDP relay socket bound to port ${udpSocket?.localPort}")

                // UDP 릴레이 처리 시작
                launch { handleUdpRelay() }

                // TCP 클라이언트 연결 처리
                while (isRunning && serverScope.isActive) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        if (clientSocket != null) {
                            clientSockets.add(clientSocket)
                            launch { handleClient(clientSocket) }
                        }
                    } catch (e: SocketTimeoutException) {
                        // 정상적인 타임아웃
                        continue
                    } catch (e: Exception) {
                        if (isRunning) {
                            println("Accept error: ${e.message}")
                        }
                    }
                }

                println("SOCKS proxy server stopped")
            } catch (e: Exception) {
                println("SOCKS proxy server error: ${e.message}")
            }
        }
    }

    /**
     * Handles UDP relay for SOCKS5 UDP ASSOCIATE
     */
    private suspend fun handleUdpRelay() {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(65507) // Maximum UDP packet size

            while (isRunning && serverScope.isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)

                    launch {
                        try {
                            processUdpPacket(packet)
                        } catch (e: Exception) {
                            println("Error processing UDP packet: ${e.message}")
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // 정상적인 타임아웃
                    continue
                } catch (e: Exception) {
                    if (isRunning) {
                        println("UDP relay error: ${e.message}")
                        kotlinx.coroutines.delay(100)
                    }
                }
            }
        }
    }

    /**
     * Processes incoming UDP packet and forwards it accordingly
     */
    private suspend fun processUdpPacket(packet: DatagramPacket) {
        withContext(Dispatchers.IO) {
            val sourceAddress = packet.address
            val sourcePort = packet.port
            val data = packet.data.copyOfRange(0, packet.length)

            // Check if this is from a known client
            val associationKey = "$sourceAddress:$sourcePort"
            val association = udpAssociations[associationKey]

            if (association != null) {
                // Parse SOCKS5 UDP request format
                if (data.size < 10) {
                    println("UDP packet too small: ${data.size} bytes")
                    return@withContext
                }

                try {
                    val parsedRequest = parseUdpRequest(data)
                    if (parsedRequest != null) {
                        // Forward to target
                        forwardUdpToTarget(parsedRequest, association, sourceAddress, sourcePort)
                    }
                } catch (e: Exception) {
                    println("Error parsing UDP request: ${e.message}")
                }
            } else {
                // Check if this is a response from a target server
                handleUdpResponse(packet)
            }
        }
    }

    data class UdpRequest(
        val targetAddress: String,
        val targetPort: Int,
        val payload: ByteArray
    )

    /**
     * Parses SOCKS5 UDP request format
     */
    private fun parseUdpRequest(data: ByteArray): UdpRequest? {
        if (data.size < 10) return null

        // SOCKS5 UDP request format:
        // +----+------+------+----------+----------+----------+
        // |RSV | FRAG | ATYP | DST.ADDR | DST.PORT |   DATA   |
        // +----+------+------+----------+----------+----------+
        // | 2  |  1   |  1   | Variable |    2     | Variable |
        // +----+------+------+----------+----------+----------+

        val rsv = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val frag = data[2].toInt() and 0xFF
        val atyp = data[3].toInt() and 0xFF

        if (rsv != 0 || frag != 0) {
            println("Invalid UDP request: RSV=$rsv, FRAG=$frag")
            return null
        }

        var offset = 4
        val targetAddress: String
        val targetPort: Int

        when (atyp) {
            0x01 -> { // IPv4
                if (data.size < offset + 6) return null
                targetAddress = "${data[offset].toUByte()}.${data[offset+1].toUByte()}.${data[offset+2].toUByte()}.${data[offset+3].toUByte()}"
                offset += 4
            }
            0x03 -> { // Domain
                if (data.size < offset + 1) return null
                val domainLen = data[offset].toUByte().toInt()
                offset += 1
                if (data.size < offset + domainLen + 2) return null
                targetAddress = String(data.sliceArray(offset until offset + domainLen))
                offset += domainLen
            }
            0x04 -> { // IPv6
                if (data.size < offset + 18) return null
                val addr = data.sliceArray(offset until offset + 16)
                targetAddress = InetAddress.getByAddress(addr).hostAddress
                offset += 16
            }
            else -> {
                println("Unsupported address type: $atyp")
                return null
            }
        }

        if (data.size < offset + 2) return null
        targetPort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset+1].toInt() and 0xFF)
        offset += 2

        val payload = if (offset < data.size) {
            data.sliceArray(offset until data.size)
        } else {
            ByteArray(0)
        }

        return UdpRequest(targetAddress, targetPort, payload)
    }

    /**
     * Forwards UDP packet to target server
     */
    private suspend fun forwardUdpToTarget(request: UdpRequest, association: UdpAssociation, clientAddress: InetAddress, clientPort: Int) {
        withContext(Dispatchers.IO) {
            try {
                val targetAddress = InetAddress.getByName(request.targetAddress)
                
                // Update association with target info
                association.targetAddress = targetAddress
                association.targetPort = request.targetPort
                
                // Create mapping for responses
                val targetKey = "$targetAddress:${request.targetPort}"
                val clientKey = "$clientAddress:$clientPort"
                udpTargetMappings[targetKey] = clientKey

                val targetPacket = DatagramPacket(
                    request.payload,
                    request.payload.size,
                    targetAddress,
                    request.targetPort
                )

                println("[${association.associationId}] Forwarding UDP to ${request.targetAddress}:${request.targetPort} (${request.payload.size} bytes)")
                udpSocket?.send(targetPacket)

            } catch (e: Exception) {
                println("Error forwarding UDP to target: ${e.message}")
            }
        }
    }

    /**
     * Handles UDP response from target server
     */
    private fun handleUdpResponse(packet: DatagramPacket) {
        try {
            val sourceAddress = packet.address
            val sourcePort = packet.port
            val responseData = packet.data.copyOfRange(0, packet.length)

            // Find the client that should receive this response
            val targetKey = "$sourceAddress:$sourcePort"
            val clientKey = udpTargetMappings[targetKey]

            if (clientKey != null) {
                // Parse client address and port
                val parts = clientKey.split(":")
                if (parts.size == 2) {
                    val clientAddress = InetAddress.getByName(parts[0])
                    val clientPort = parts[1].toInt()

                    // Create SOCKS5 UDP response format
                    val socksResponse = createUdpResponse(sourceAddress.hostAddress, sourcePort, responseData)

                    val clientPacket = DatagramPacket(
                        socksResponse,
                        socksResponse.size,
                        clientAddress,
                        clientPort
                    )

                    println("Forwarding UDP response from $sourceAddress:$sourcePort to client $clientKey (${responseData.size} bytes)")
                    udpSocket?.send(clientPacket)
                }
            }
        } catch (e: Exception) {
            println("Error handling UDP response: ${e.message}")
        }
    }

    /**
     * Creates SOCKS5 UDP response format
     */
    private fun createUdpResponse(targetAddress: String, targetPort: Int, payload: ByteArray): ByteArray {
        val addressBytes = try {
            val addr = InetAddress.getByName(targetAddress)
            if (addr is Inet4Address) {
                byteArrayOf(0x01) + addr.address // IPv4
            } else {
                byteArrayOf(0x04) + addr.address // IPv6
            }
        } catch (e: Exception) {
            // Use domain format
            val domainBytes = targetAddress.toByteArray()
            byteArrayOf(0x03, domainBytes.size.toByte()) + domainBytes
        }

        val portBytes = byteArrayOf(
            (targetPort shr 8).toByte(),
            (targetPort and 0xFF).toByte()
        )

        // RSV (2 bytes) + FRAG (1 byte) + ATYP + ADDRESS + PORT + DATA
        return byteArrayOf(0x00, 0x00, 0x00) + addressBytes + portBytes + payload
    }

    /**
     * Handles a single client connection including SOCKS5 handshake and request parsing.
     * @param clientSocket The incoming client socket connection.
     */
    private suspend fun handleClient(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            val connectionId = "conn-${connectionCounter.incrementAndGet()}"
            println("[$connectionId] New client connection from ${clientSocket.inetAddress}")
            try {
                val input = clientSocket.getInputStream()
                val output = clientSocket.getOutputStream()

                // 1. SOCKS5 handshake - 타임아웃 추가
                withTimeout(5000) { // 5초 타임아웃
                    val handshakeHeader = ByteArray(2)
                    if (input.read(handshakeHeader) != 2 || handshakeHeader[0] != 0x05.toByte()) {
                        println("[$connectionId] Not a SOCKS5 client or handshake too short")
                        cleanup(clientSocket)
                        return@withTimeout
                    }
                    val nMethods = handshakeHeader[1].toInt() and 0xFF
                    val methods = ByteArray(nMethods)
                    if (input.read(methods) != nMethods) {
                        println("[$connectionId] Could not read all methods")
                        cleanup(clientSocket)
                        return@withTimeout
                    }
                    // Reply: no authentication
                    output.write(byteArrayOf(0x05, 0x00))
                    output.flush()
                }

                // 2. SOCKS5 request - 타임아웃 추가
                withTimeout(5000) { // 5초 타임아웃
                    val reqHeader = ByteArray(4)
                    if (input.read(reqHeader) != 4) {
                        println("[$connectionId] Could not read request header")
                        cleanup(clientSocket)
                        return@withTimeout
                    }
                    val cmd = reqHeader[1]
                    val atyp = reqHeader[3]
                    var address = ""
                    var port = 0
                    when (atyp.toInt()) {
                        0x01 -> { // IPv4
                            val addr = ByteArray(4)
                            if (input.read(addr) != 4) {
                                println("[$connectionId] Could not read IPv4 address")
                                cleanup(clientSocket)
                                return@withTimeout
                            }
                            address = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                        }
                        0x03 -> { // Domain
                            val len = input.read()
                            if (len <= 0) {
                                println("[$connectionId] Invalid domain length")
                                cleanup(clientSocket)
                                return@withTimeout
                            }
                            val addr = ByteArray(len)
                            if (input.read(addr) != len) {
                                println("[$connectionId] Could not read domain name")
                                cleanup(clientSocket)
                                return@withTimeout
                            }
                            address = String(addr)
                        }
                        0x04 -> { // IPv6
                            val addr = ByteArray(16)
                            if (input.read(addr) != 16) {
                                println("[$connectionId] Could not read IPv6 address")
                                cleanup(clientSocket)
                                return@withTimeout
                            }
                            address = java.net.InetAddress.getByAddress(addr).hostAddress
                        }
                        else -> {
                            println("[$connectionId] Unsupported address type: $atyp")
                            cleanup(clientSocket)
                            return@withTimeout
                        }
                    }
                    val portBytes = ByteArray(2)
                    if (input.read(portBytes) != 2) {
                        println("[$connectionId] Could not read port")
                        cleanup(clientSocket)
                        return@withTimeout
                    }
                    port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

                    when (cmd) {
                        0x01.toByte() -> { // CONNECT
                            handleConnectStrict(address, port, output, clientSocket, connectionId)
                        }
                        0x03.toByte() -> { // UDP ASSOCIATE
                            handleUdpAssociate(address, port, output, clientSocket, connectionId)
                        }
                        else -> {
                            println("[$connectionId] Unsupported command: $cmd")
                            val response = byteArrayOf(0x05, 0x07, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                            output.write(response)
                            output.flush()
                            cleanup(clientSocket)
                        }
                    }
                }

            } catch (e: Exception) {
                println("[$connectionId] Client handling error: ${e.message}")
                cleanup(clientSocket)
            }
        }
    }

    /**
     * Handles SOCKS5 UDP ASSOCIATE command
     */
    private suspend fun handleUdpAssociate(host: String, port: Int, output: OutputStream, clientSocket: Socket, connectionId: String) {
        withContext(Dispatchers.IO) {
            try {
                println("[$connectionId] UDP ASSOCIATE request for $host:$port")

                // Create UDP association
                val clientAddress = clientSocket.inetAddress
                val udpRelayPort = udpSocket?.localPort ?: 0
                val udpRelayAddress = serverSocket?.inetAddress ?: InetAddress.getLocalHost()

                val association = UdpAssociation(
                    clientAddress = clientAddress,
                    clientPort = port, // Client specified port for UDP
                    udpRelaySocket = udpSocket!!,
                    associationId = connectionId
                )

                val associationKey = "$clientAddress:$port"
                udpAssociations[associationKey] = association

                println("[$connectionId] Created UDP association: $associationKey -> UDP relay port $udpRelayPort")

                // Send success response with UDP relay address and port
                val response = ByteArray(10)
                response[0] = 0x05 // Version
                response[1] = 0x00 // Success
                response[2] = 0x00 // Reserved
                response[3] = 0x01 // IPv4 address type

                // Use localhost for UDP relay
                val relayAddr = InetAddress.getLoopbackAddress().address
                System.arraycopy(relayAddr, 0, response, 4, 4)

                // UDP relay port
                response[8] = (udpRelayPort shr 8).toByte()
                response[9] = (udpRelayPort and 0xFF).toByte()

                output.write(response)
                output.flush()

                println("[$connectionId] UDP ASSOCIATE success response sent. UDP relay on ${InetAddress.getLoopbackAddress().hostAddress}:$udpRelayPort")

                // Keep the TCP connection alive to maintain the UDP association
                launch {
                    try {
                        val buffer = ByteArray(1024)
                        while (isRunning && !clientSocket.isClosed && clientSocket.isConnected) {
                            val bytesRead = clientSocket.getInputStream().read(buffer)
                            if (bytesRead == -1) {
                                println("[$connectionId] TCP connection closed, removing UDP association")
                                break
                            }
                            // Just read and discard to keep connection alive
                        }
                    } catch (e: Exception) {
                        println("[$connectionId] TCP keep-alive error: ${e.message}")
                    } finally {
                        udpAssociations.remove(associationKey)
                        // Clean up target mappings for this client
                        udpTargetMappings.entries.removeIf { (_, clientKey) -> clientKey == associationKey }
                        println("[$connectionId] UDP association removed")
                    }
                }

            } catch (e: Exception) {
                println("[$connectionId] UDP ASSOCIATE error: ${e.message}")
                sendErrorResponse(output, 0x05)
                cleanup(clientSocket)
            }
        }
    }

    /**
     * Handles the SOCKS5 CONNECT command and attempts to establish a TCP connection
     * to the requested remote address and port.
     * @param host The target hostname or IP address.
     * @param port The target port number.
     * @param output Output stream to send SOCKS5 response.
     * @param clientSocket The client's socket.
     * @param connectionId A unique connection identifier for logging.
     */
    private fun handleConnectStrict(host: String, port: Int, output: OutputStream, clientSocket: Socket, connectionId: String) {
        var targetSocket: Socket? = null
        try {
            println("[$connectionId] Connecting to $host:$port")
            targetSocket = Socket()
            targetSocket.keepAlive = false // keep-alive 비활성화
            targetSocket.tcpNoDelay = true
            targetSocket.soTimeout = 10000 // 타임아웃 단축
            targetSocket.receiveBufferSize = 16384 // 버퍼 크기 증가
            targetSocket.sendBufferSize = 16384
            targetSocket.connect(java.net.InetSocketAddress(host, port), 5000) // 연결 타임아웃 단축

            if (targetSocket.isConnected) {
                println("[$connectionId] Successfully connected to $host:$port")
                val response = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                output.write(response)
                output.flush()
                val connectionKey = "$host:$port-${System.currentTimeMillis()}"
                activeConnections[connectionKey] = Pair(clientSocket, targetSocket)
                startRelay(clientSocket, targetSocket, connectionId, connectionKey)
            } else {
                throw Exception("Connection failed")
            }
        } catch (e: java.net.ConnectException) {
            println("[$connectionId] Connection refused to target: ${e.message}")
            sendErrorResponse(output, 0x05)
            targetSocket?.close()
        } catch (e: java.net.SocketTimeoutException) {
            println("[$connectionId] Connection timeout to target: ${e.message}")
            sendErrorResponse(output, 0x04)
            targetSocket?.close()
        } catch (e: java.net.UnknownHostException) {
            println("[$connectionId] Unknown host: ${e.message}")
            sendErrorResponse(output, 0x04)
            targetSocket?.close()
        } catch (e: Exception) {
            println("[$connectionId] Connection failed to target: ${e.message}")
            sendErrorResponse(output, 0x05)
            targetSocket?.close()
        }
    }

    /**
     * Sends a SOCKS5 error response to the client.
     * @param output Output stream to write the error response to.
     * @param errorCode SOCKS5 error code to return.
     */
    private fun sendErrorResponse(output: OutputStream, errorCode: Byte) {
        try {
            val response = byteArrayOf(0x05, errorCode, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            output.write(response)
            output.flush()
        } catch (e: Exception) {
            println("Error writing failure response: ${e.message}")
        }
    }

    /**
     * Parses a SOCKS5 address field from a request.
     * @param buffer The buffer containing the SOCKS request.
     * @param addressType The address type byte (IPv4, domain, etc.).
     * @return A pair of the parsed address and port.
     */
    private fun parseAddress(buffer: ByteArray, addressType: Byte): Pair<String, Int> {
        when (addressType) {
            0x01.toByte() -> { // IPv4
                val ip = "${buffer[4].toUByte()}.${buffer[5].toUByte()}.${buffer[6].toUByte()}.${buffer[7].toUByte()}"
                val port = ((buffer[8].toUByte().toInt() shl 8) or buffer[9].toUByte().toInt())
                return Pair(ip, port)
            }
            0x03.toByte() -> { // Domain name
                val domainLength = buffer[4].toUByte().toInt()
                val domain = String(buffer.sliceArray(5 until 5 + domainLength))
                val port = ((buffer[5 + domainLength].toUByte().toInt() shl 8) or
                        buffer[6 + domainLength].toUByte().toInt())
                return Pair(domain, port)
            }
            0x04.toByte() -> { // IPv6
                throw IllegalArgumentException("IPv6 not supported yet")
            }
            else -> throw IllegalArgumentException("Unsupported address type: $addressType")
        }
    }

    /**
     * Starts bidirectional data relay between the client and the target server.
     * @param clientSocket The client socket.
     * @param targetSocket The target socket.
     * @param connectionId A unique ID for this connection.
     * @param connectionKey A key used to track the active connection.
     */
    private fun startRelay(clientSocket: Socket, targetSocket: Socket, connectionId: String, connectionKey: String) {
        // 개별 스코프로 릴레이 작업 격리
        val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val clientToServerJob = relayScope.launch {
            try {
                relayData(
                    from = clientSocket.getInputStream(),
                    to = targetSocket.getOutputStream(),
                    direction = "Client->Server",
                    connectionId = connectionId,
                    clientSocket = clientSocket,
                    targetSocket = targetSocket
                )
            } catch (e: Exception) {
                println("[$connectionId] Client->Server relay error: ${e.message}")
            }
        }

        val serverToClientJob = relayScope.launch {
            try {
                relayData(
                    from = targetSocket.getInputStream(),
                    to = clientSocket.getOutputStream(),
                    direction = "Server->Client",
                    connectionId = connectionId,
                    clientSocket = clientSocket,
                    targetSocket = targetSocket
                )
            } catch (e: Exception) {
                println("[$connectionId] Server->Client relay error: ${e.message}")
            }
        }

        // 정리 작업을 처리하는 코루틴
        serverScope.launch {
            try {
                // 둘 중 하나라도 완료되면 연결 종료
                clientToServerJob.join()
                serverToClientJob.join()
            } catch (e: Exception) {
                println("[$connectionId] Relay monitoring error: ${e.message}")
            } finally {
                println("[$connectionId] Relay completed, cleaning up...")
                relayScope.cancel() // 릴레이 스코프 취소
                cleanupConnection(clientSocket, targetSocket, connectionId, connectionKey)
            }
        }
    }

    /**
     * Performs data relay from one socket to another.
     * Reads data from input and writes to output until sockets close or an error occurs.
     * @param from Input stream to read from.
     * @param to Output stream to write to.
     * @param direction Description of the data direction (for logging).
     * @param connectionId Connection ID for debugging/logging.
     * @param clientSocket The client socket.
     * @param targetSocket The target socket.
     */
    private suspend fun relayData(
        from: java.io.InputStream,
        to: java.io.OutputStream,
        direction: String,
        connectionId: String,
        clientSocket: Socket,
        targetSocket: Socket
    ) {
        withContext(Dispatchers.IO) {
            try {
                val buffer = ByteArray(16384) // 버퍼 크기 증가
                var totalBytes = 0L

                while (isRunning && serverScope.isActive &&
                    !clientSocket.isClosed &&
                    !targetSocket.isClosed &&
                    clientSocket.isConnected &&
                    targetSocket.isConnected) {
                    try {
                        val bytesRead = from.read(buffer)
                        if (bytesRead == -1) {
                            println("[$connectionId] $direction: EOF received, closing connection")
                            break // EOF 받으면 즉시 연결 종료
                        }

                        if (bytesRead > 0) {
                            to.write(buffer, 0, bytesRead)
                            to.flush()
                            totalBytes += bytesRead

                            // 연결 상태 확인 빈도 줄임
                            if (totalBytes % 32768 == 0L) {
                                if (clientSocket.isClosed || targetSocket.isClosed) {
                                    println("[$connectionId] $direction: Socket closed during transfer")
                                    break
                                }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // 타임아웃 시 연결 종료
                        println("[$connectionId] $direction: Read timeout, closing connection")
                        break
                    } catch (e: java.net.SocketException) {
                        if (e.message?.contains("closed") == true ||
                            e.message?.contains("reset") == true ||
                            e.message?.contains("Broken pipe") == true) {
                            println("[$connectionId] $direction: Connection terminated (${e.message})")
                        } else {
                            println("[$connectionId] $direction: Network error - ${e.message}")
                        }
                        break
                    } catch (e: java.io.IOException) {
                        println("[$connectionId] $direction: IO error - ${e.message}")
                        break
                    } catch (e: Exception) {
                        println("[$connectionId] $direction: Unexpected error - ${e.message}")
                        break
                    }
                }

                println("[$connectionId] $direction relay finished. Total bytes: $totalBytes")

            } catch (e: Exception) {
                println("[$connectionId] $direction relay fatal error: ${e.message}")
            }
        }
    }

    /**
     * Cleans up a finished or failed connection by closing sockets and removing tracking info.
     * @param clientSocket The client socket.
     * @param targetSocket The target server socket.
     * @param connectionId A unique connection ID.
     * @param connectionKey Key used to remove from active connections.
     */
    private fun cleanupConnection(clientSocket: Socket, targetSocket: Socket, connectionId: String, connectionKey: String) {
        println("[$connectionId] Starting connection cleanup...")

        activeConnections.remove(connectionKey)

        // 소켓들을 빠르게 종료
        try {
            if (!clientSocket.isClosed) {
                try {
                    clientSocket.shutdownOutput()
                    clientSocket.shutdownInput()
                } catch (e: Exception) {
                    // 무시
                }
                clientSocket.close()
            }
        } catch (e: Exception) {
            // 무시
        }

        try {
            if (!targetSocket.isClosed) {
                try {
                    targetSocket.shutdownOutput()
                    targetSocket.shutdownInput()
                } catch (e: Exception) {
                    // 무시
                }
                targetSocket.close()
            }
        } catch (e: Exception) {
            // 무시
        }

        clientSockets.remove(clientSocket)
        println("[$connectionId] Connection cleanup completed")
    }

    /**
     * Cleans up a single client socket, usually after failed handshake or errors.
     * @param clientSocket The client socket to close and remove.
     */
    private fun cleanup(clientSocket: Socket) {
        clientSockets.remove(clientSocket)
        try {
            if (!clientSocket.isClosed) {
                clientSocket.close()
            }
        } catch (e: Exception) {
            // 무시
        }
    }

    /**
     * Stops the SOCKS proxy server and releases all resources.
     * Closes all sockets, cancels coroutines, and clears active connections.
     */
    fun stop() {
        println("Stopping SOCKS proxy server...")
        isRunning = false

        // UDP associations 정리
        udpAssociations.clear()
        udpTargetMappings.clear() // 매핑도 함께 정리

        // 활성 연결들 정리
        activeConnections.values.forEach { (client, target) ->
            try {
                client.close()
                target.close()
            } catch (e: Exception) {
                // 무시
            }
        }
        activeConnections.clear()

        // 클라이언트 소켓들 정리
        clientSockets.forEach { socket ->
            try {
                socket.close()
            } catch (e: Exception) {
                // 무시
            }
        }
        clientSockets.clear()

        // 서버 소켓 닫기
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // 무시
        }

        // UDP 소켓 닫기
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            // 무시
        }

        // 스코프 취소
        serverScope.cancel()
        println("SOCKS proxy server stopped")
    }

    /**
     * Returns the current number of active SOCKS connections.
     * @return Active connection count.
     */
    fun getConnectionCount(): Int {
        return activeConnections.size
    }

    /**
     * Returns the number of UDP associations currently active.
     * @return Number of UDP associations.
     */
    fun getUdpAssociationCount(): Int {
        return udpAssociations.size
    }

    /**
     * Returns the number of client sockets currently managed by the server.
     * @return Number of client sockets.
     */
    fun getClientSocketCount(): Int {
        return clientSockets.size
    }

    /**
     * Returns whether the server is currently running.
     * @return True if the server is running, false otherwise.
     */
    fun isServerRunning(): Boolean {
        return isRunning
    }

    /**
     * Returns the UDP relay port number.
     * @return UDP relay port, or -1 if not available.
     */
    fun getUdpRelayPort(): Int {
        return udpSocket?.localPort ?: -1
    }

    /**
     * Returns a human-readable status of the SOCKS proxy server.
     * Includes port and connection info if running.
     * @return Status string.
     */
    fun getServerStatus(): String {
        return if (isRunning) {
            val udpPort = getUdpRelayPort()
            "Running on port $port (${activeConnections.size} TCP connections, ${udpAssociations.size} UDP associations)" +
                    if (udpPort != -1) ", UDP relay on port $udpPort" else ""
        } else {
            "Stopped"
        }
    }
}