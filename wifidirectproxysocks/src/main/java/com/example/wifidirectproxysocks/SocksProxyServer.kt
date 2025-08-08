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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SocksProxyServer(private val port: Int) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeConnections = ConcurrentHashMap<String, Pair<Socket, Socket>>()
    private val clientSockets = mutableSetOf<Socket>()
    private val connectionCounter = AtomicInteger(0)
    private val maxConnections = 100 // Limit concurrent connections

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

                    if (cmd == 0x01.toByte()) { // CONNECT
                        handleConnectStrict(address, port, output, clientSocket, connectionId)
                    } else {
                        println("[$connectionId] Unsupported command: $cmd")
                        val response = byteArrayOf(0x05, 0x07, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                        output.write(response)
                        output.flush()
                        cleanup(clientSocket)
                    }
                }

            } catch (e: Exception) {
                println("[$connectionId] Client handling error: ${e.message}")
                cleanup(clientSocket)
            }
        }
    }

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

    private fun sendErrorResponse(output: OutputStream, errorCode: Byte) {
        try {
            val response = byteArrayOf(0x05, errorCode, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            output.write(response)
            output.flush()
        } catch (e: Exception) {
            println("Error writing failure response: ${e.message}")
        }
    }

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

    fun stop() {
        println("Stopping SOCKS proxy server...")
        isRunning = false

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

    fun getConnectionCount(): Int {
        return activeConnections.size
    }

    fun getClientSocketCount(): Int {
        return clientSockets.size
    }

    fun isServerRunning(): Boolean {
        return isRunning
    }

    fun getServerStatus(): String {
        return if (isRunning) {
            "Running on port $port (${activeConnections.size} active connections)"
        } else {
            "Stopped"
        }
    }
}