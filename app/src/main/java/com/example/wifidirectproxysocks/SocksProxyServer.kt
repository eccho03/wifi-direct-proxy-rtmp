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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SocksProxyServer(private val port: Int) {
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var isRunning = false
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeConnections = ConcurrentHashMap<String, Pair<Socket, Socket>>()
    private val udpAssociations = ConcurrentHashMap<String, UdpAssociation>()
    private val clientSockets = mutableSetOf<Socket>()
    private val connectionCounter = AtomicInteger(0)
    private val udpAssociationCounter = AtomicInteger(0)
    private val maxConnections = 100 // Limit concurrent connections
    private val maxUdpAssociations = 50 // Limit UDP associations

    data class UdpAssociation(
        val tcpClientSocket: Socket,
        val relaySocket: DatagramSocket,
        val id: String,
        @Volatile var clientUdpAddr: InetSocketAddress? = null // 최초 클라 UDP 패킷 도착 시 기록
    )

    private data class Socks5UdpHeader(
        val frag: Int,
        val atyp: Int,
        val dstHost: String,
        val dstPort: Int,
        val headerLen: Int
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
                serverSocket?.receiveBufferSize = 16384 // 버퍼 크기 증가
                isRunning = true
                println("SOCKS proxy server started on port $port")

                while (isRunning && serverScope.isActive) {
                    try {
                        // Check connection limit
                        if (activeConnections.size >= maxConnections) {
                            println("Maximum connections reached (${maxConnections}), waiting...")
                            kotlinx.coroutines.delay(100) // 대기 시간 단축
                            continue
                        }

                        val clientSocket = serverSocket?.accept()
                        clientSocket?.let { socket ->
                            // Set socket options for better performance
                            socket.keepAlive = false // keep-alive 비활성화로 성능 향상
                            socket.tcpNoDelay = true
                            socket.soTimeout = 10000 // 타임아웃 단축 (10초)
                            socket.receiveBufferSize = 16384 // 버퍼 크기 증가
                            socket.sendBufferSize = 16384

                            clientSockets.add(socket)
                            launch {
                                try {
                                    handleClient(socket)
                                } catch (e: Exception) {
                                    println("Error in client handler: ${e.message}")
                                    cleanup(socket)
                                }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // 정상적인 타임아웃, 계속 진행
                        continue
                    } catch (e: Exception) {
                        if (isRunning) {
                            println("Error accepting client: ${e.message}")
                            kotlinx.coroutines.delay(50) // 대기 시간 단축
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
                        0x02.toByte() -> { // BIND
                            handleBind(address, port, output, clientSocket, connectionId)
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
     * Handles the SOCKS5 CONNECT command and attempts to establish a TCP connection
     * to the requested remote address and port.
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
     * Handles the SOCKS5 BIND command. Creates a listening socket and waits for incoming connection.
     */
    private fun handleBind(host: String, port: Int, output: OutputStream, clientSocket: Socket, connectionId: String) {
        var bindSocket: ServerSocket? = null
        try {
            println("[$connectionId] Setting up BIND for $host:$port")

            // Create a server socket for incoming connections
            bindSocket = ServerSocket(0) // Use any available port
            bindSocket.reuseAddress = true
            bindSocket.soTimeout = 30000 // 30 second timeout

            val bindPort = bindSocket.localPort
            val bindAddress = InetAddress.getLocalHost().hostAddress

            println("[$connectionId] BIND listening on $bindAddress:$bindPort")

            // Send first reply with bound address
            val addressBytes = bindAddress.split(".").map { it.toInt().toByte() }.toByteArray()
            val portBytes = byteArrayOf(
                (bindPort shr 8).toByte(),
                (bindPort and 0xFF).toByte()
            )

            val firstResponse = byteArrayOf(0x05, 0x00, 0x00, 0x01) + addressBytes + portBytes
            output.write(firstResponse)
            output.flush()

            // Wait for incoming connection
            serverScope.launch {
                try {
                    val incomingSocket = bindSocket.accept()
                    println("[$connectionId] BIND received connection from ${incomingSocket.inetAddress}:${incomingSocket.port}")

                    // Send second reply with peer address
                    val peerAddress = incomingSocket.inetAddress.hostAddress
                    val peerPort = incomingSocket.port
                    val peerAddressBytes = peerAddress.split(".").map { it.toInt().toByte() }.toByteArray()
                    val peerPortBytes = byteArrayOf(
                        (peerPort shr 8).toByte(),
                        (peerPort and 0xFF).toByte()
                    )

                    val secondResponse = byteArrayOf(0x05, 0x00, 0x00, 0x01) + peerAddressBytes + peerPortBytes
                    output.write(secondResponse)
                    output.flush()

                    // Start relaying data between client and incoming socket
                    val connectionKey = "bind-$host:$port-${System.currentTimeMillis()}"
                    activeConnections[connectionKey] = Pair(clientSocket, incomingSocket)
                    startRelay(clientSocket, incomingSocket, connectionId, connectionKey)

                } catch (e: java.net.SocketTimeoutException) {
                    println("[$connectionId] BIND timeout waiting for connection")
                    sendErrorResponse(output, 0x04)
                } catch (e: Exception) {
                    println("[$connectionId] BIND error: ${e.message}")
                    sendErrorResponse(output, 0x05)
                } finally {
                    bindSocket?.close()
                }
            }

        } catch (e: Exception) {
            println("[$connectionId] BIND setup error: ${e.message}")
            sendErrorResponse(output, 0x05)
            bindSocket?.close()
        }
    }

    private fun pickBindAddressForReply(tcpClient: Socket, relay: DatagramSocket): InetAddress {
        // 우선순위: relay.localAddress(유효 IPv4) > tcpClient.localAddress(유효 IPv4) > 루프백 제외한 첫 번째 IPv4
        fun InetAddress.isGoodIPv4() =
            this is java.net.Inet4Address && !this.isLoopbackAddress && !this.isAnyLocalAddress

        val ra = relay.localAddress
        if (ra != null && ra.isGoodIPv4()) return ra

        val la = tcpClient.localAddress
        if (la != null && la.isGoodIPv4()) return la

        val niEnum = java.net.NetworkInterface.getNetworkInterfaces()
        for (ni in niEnum) {
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is java.net.Inet4Address && !a.isLoopbackAddress && !a.isAnyLocalAddress) {
                    return a
                }
            }
        }
        // 최후 수단: 0.0.0.0 (클라가 무시할 가능성 있으므로 가급적 위에서 잡히도록)
        return InetAddress.getByName("0.0.0.0")
    }


    /**
     * Handles the SOCKS5 UDP ASSOCIATE command. Sets up UDP relay functionality.
     */
    private fun handleUdpAssociate(host: String, port: Int, output: OutputStream, clientSocket: Socket, connectionId: String) {
        try {
            println("[$connectionId] Setting up UDP ASSOCIATE for $host:$port")

            if (udpAssociations.size >= maxUdpAssociations) {
                println("[$connectionId] Maximum UDP associations reached")
                sendErrorResponse(output, 0x05)
                return
            }

            // UDP 릴레이 소켓 바인드 (임의 포트)
            val relaySocket = DatagramSocket(0)
            relaySocket.soTimeout = 1000

            val bindAddr = pickBindAddressForReply(clientSocket, relaySocket)  // 중요
            val bindPort = relaySocket.localPort
            println("[$connectionId] UDP relay listening on ${bindAddr.hostAddress}:$bindPort")

            // SOCKS5 성공 응답 (IPv4만 우선 지원; 필요 시 IPv6 분기 추가)
            val addrBytes = bindAddr.address // IPv4 4바이트
            val portBytes = byteArrayOf((bindPort shr 8).toByte(), (bindPort and 0xFF).toByte())
            val resp = byteArrayOf(0x05, 0x00, 0x00, 0x01) + addrBytes + portBytes
            output.write(resp)
            output.flush()

            // Association 등록
            val associationId = "udp-${udpAssociationCounter.incrementAndGet()}"
            val assoc = UdpAssociation(
                tcpClientSocket = clientSocket,
                relaySocket = relaySocket,
                id = associationId
            )
            udpAssociations[associationId] = assoc
            println("[$connectionId] UDP association created: $associationId")

            // 릴레이 시작
            startUdpRelay(assoc, connectionId)

            // TCP 연결이 끊기면 UDP 정리
            serverScope.launch {
                try {
                    val buf = ByteArray(1)
                    val input = clientSocket.getInputStream()
                    while (!clientSocket.isClosed && clientSocket.isConnected) {
                        // 블로킹 read에서 EOF(-1)이면 끊김
                        if (input.read(buf) == -1) break
                    }
                } catch (_: Exception) {
                } finally {
                    println("[$connectionId] TCP closed → cleanup UDP association")
                    cleanupUdpAssociation(associationId)
                }
            }

        } catch (e: Exception) {
            println("[$connectionId] UDP ASSOCIATE error: ${e.message}")
            sendErrorResponse(output, 0x05)
        }
    }

    /**
     * Starts UDP relay for the given association.
     */
    private fun startUdpRelay(association: UdpAssociation, connectionId: String) {
        serverScope.launch {
            val sock = association.relaySocket
            val buf = ByteArray(65507)

            loop@ while (isRunning && !sock.isClosed && !association.tcpClientSocket.isClosed) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt) // from either client or remote server

                    val src = InetSocketAddress(pkt.address, pkt.port)
                    val data = pkt.data
                    val len = pkt.length

                    val knownClient = association.clientUdpAddr

                    // 아직 클라 UDP 주소를 모르면: 들어온 패킷이 SOCKS5 헤더를 가진 클라 패킷인지 확인
                    if (knownClient == null) {
                        val firstHeader = parseSocks5UdpHeader(data, len)
                        if (firstHeader != null) {
                            association.clientUdpAddr = src
                            println("[$connectionId] Learn client UDP addr: $src")
                        } else {
                            // 서버에서 온 선행 패킷은 무시
                            continue@loop
                        }
                    }

                    // 이후엔 항상 존재
                    val clientAddr = association.clientUdpAddr!!

                    if (src == clientAddr) {
                        // CLIENT -> PROXY
                        val header = parseSocks5UdpHeader(data, len)
                        if (header == null) {
                            println("[$connectionId] Bad SOCKS5 UDP header from client")
                            continue@loop
                        }
                        if (header.frag != 0) {
                            println("[$connectionId] FRAG!=0 not supported")
                            continue@loop
                        }

                        val payload = data.copyOfRange(header.headerLen, len)
                        val dstInet = InetAddress.getByName(header.dstHost)
                        val out = DatagramPacket(payload, payload.size, dstInet, header.dstPort)
                        sock.send(out)

                        println("[$connectionId] UDP C->S ${clientAddr.address.hostAddress}:${clientAddr.port} -> ${header.dstHost}:${header.dstPort} (${payload.size}B)")

                    } else {
                        // REMOTE SERVER -> PROXY -> CLIENT
                        if (pkt.address !is java.net.Inet4Address) {
                            // (간단화를 위해 IPv4만 지원)
                            continue@loop
                        }
                        val hdr = buildSocks5UdpHeaderIPv4(pkt.address, pkt.port)
                        val wrapped = ByteArray(hdr.size + len)
                        System.arraycopy(hdr, 0, wrapped, 0, hdr.size)
                        System.arraycopy(data, 0, wrapped, hdr.size, len)

                        val back = DatagramPacket(wrapped, wrapped.size, clientAddr.address, clientAddr.port)
                        sock.send(back)

                        println("[$connectionId] UDP S->C ${pkt.address.hostAddress}:${pkt.port} -> ${clientAddr.address.hostAddress}:${clientAddr.port} (${len}B wrapped)")
                    }

                } catch (e: java.net.SocketTimeoutException) {
                    // 타임아웃은 정상: 다음 루프
                    continue@loop
                } catch (e: Exception) {
                    println("[$connectionId] UDP relay error: ${e.message}")
                    break@loop
                }
            }

            println("[$connectionId] UDP relay loop ended")
        }
    }

    /**
     * Cleans up a UDP association.
     */
    private fun cleanupUdpAssociation(associationId: String) {
        val association = udpAssociations.remove(associationId)
        association?.let {
            try {
                println("Cleaned up UDP association: $associationId")
            } catch (e: Exception) {
                println("Error cleaning up UDP association $associationId: ${e.message}")
            }
        }
    }

    /**
     * Sends a SOCKS5 error response to the client.
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

        udpAssociations.values.forEach { assoc ->
            try { assoc.relaySocket.close() } catch (_: Exception) {}
        }
        udpAssociations.clear()

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
     * Returns a human-readable status of the SOCKS proxy server.
     * Includes port and connection info if running.
     * @return Status string.
     */
    fun getServerStatus(): String {
        return if (isRunning) {
            "Running on port $port (${activeConnections.size} active connections)"
        } else {
            "Stopped"
        }
    }

    private fun parseSocks5UdpHeader(pkt: ByteArray, length: Int): Socks5UdpHeader? {
        if (length < 10) return null
        var off = 0
        val rsv1 = pkt[off++].toInt() and 0xFF
        val rsv2 = pkt[off++].toInt() and 0xFF
        val frag = pkt[off++].toInt() and 0xFF
        if (rsv1 != 0x00 || rsv2 != 0x00) return null
        if (frag != 0) {
            // 조각화 미지원
            return null
        }
        val atyp = pkt[off++].toInt() and 0xFF
        val dstHost: String
        val dstPort: Int
        when (atyp) {
            0x01 -> { // IPv4
                if (length < off + 4 + 2) return null
                dstHost = "${pkt[off].toUByte()}.${pkt[off + 1].toUByte()}.${pkt[off + 2].toUByte()}.${pkt[off + 3].toUByte()}"
                off += 4
                dstPort = ((pkt[off].toInt() and 0xFF) shl 8) or (pkt[off + 1].toInt() and 0xFF)
                off += 2
            }
            0x03 -> { // DOMAIN
                if (length < off + 1) return null
                val dlen = pkt[off++].toInt() and 0xFF
                if (length < off + dlen + 2) return null
                dstHost = String(pkt, off, dlen)
                off += dlen
                dstPort = ((pkt[off].toInt() and 0xFF) shl 8) or (pkt[off + 1].toInt() and 0xFF)
                off += 2
            }
            0x04 -> { // IPv6 (필요 시 구현)
                return null
            }
            else -> return null
        }
        return Socks5UdpHeader(frag, atyp, dstHost, dstPort, off)
    }

    private fun buildSocks5UdpHeaderIPv4(srcAddr: InetAddress, srcPort: Int): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(byteArrayOf(0x00, 0x00, 0x00)) // RSV(2), FRAG=0
        b.write(byteArrayOf(0x01))             // ATYP=IPv4
        b.write(srcAddr.address)               // DST.ADDR
        b.write(byteArrayOf((srcPort shr 8).toByte(), (srcPort and 0xFF).toByte()))
        return b.toByteArray()
    }
}