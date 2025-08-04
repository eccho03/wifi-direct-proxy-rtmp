package com.example.wifidirectproxysocks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
                serverSocket?.soTimeout = 0 // Non-blocking accept
                serverSocket?.receiveBufferSize = 8192
                //serverSocket?.sendBufferSize = 8192
                isRunning = true
                println("SOCKS proxy server started on port $port")

                while (isRunning) {
                    try {
                        // Check connection limit
                        if (activeConnections.size >= maxConnections) {
                            println("Maximum connections reached (${maxConnections}), waiting...")
                            kotlinx.coroutines.delay(1000)
                            continue
                        }

                        val clientSocket = serverSocket?.accept()
                        clientSocket?.let { socket ->
                            // Set socket options for better stability
                            socket.keepAlive = true
                            socket.tcpNoDelay = true
                            socket.soTimeout = 30000 // 30 second read timeout
                            socket.receiveBufferSize = 8192
                            socket.sendBufferSize = 8192

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
                    } catch (e: Exception) {
                        if (isRunning) {
                            println("Error accepting client: ${e.message}")
                            // Brief pause to prevent tight loop
                            kotlinx.coroutines.delay(100)
                        }
                    }
                }
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

                // 1. SOCKS5 handshake
                val handshakeHeader = ByteArray(2)
                if (input.read(handshakeHeader) != 2 || handshakeHeader[0] != 0x05.toByte()) {
                    println("[$connectionId] Not a SOCKS5 client or handshake too short")
                    cleanup(clientSocket)
                    return@withContext
                }
                val nMethods = handshakeHeader[1].toInt() and 0xFF
                val methods = ByteArray(nMethods)
                if (input.read(methods) != nMethods) {
                    println("[$connectionId] Could not read all methods")
                    cleanup(clientSocket)
                    return@withContext
                }
                // Reply: no authentication
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()

                // 2. SOCKS5 request
                val reqHeader = ByteArray(4)
                if (input.read(reqHeader) != 4) {
                    println("[$connectionId] Could not read request header")
                    cleanup(clientSocket)
                    return@withContext
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
                            return@withContext
                        }
                        address = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    }
                    0x03 -> { // Domain
                        val len = input.read()
                        if (len <= 0) {
                            println("[$connectionId] Invalid domain length")
                            cleanup(clientSocket)
                            return@withContext
                        }
                        val addr = ByteArray(len)
                        if (input.read(addr) != len) {
                            println("[$connectionId] Could not read domain name")
                            cleanup(clientSocket)
                            return@withContext
                        }
                        address = String(addr)
                    }
                    0x04 -> { // IPv6
                        val addr = ByteArray(16)
                        if (input.read(addr) != 16) {
                            println("[$connectionId] Could not read IPv6 address")
                            cleanup(clientSocket)
                            return@withContext
                        }
                        address = java.net.InetAddress.getByAddress(addr).hostAddress
                    }
                    else -> {
                        println("[$connectionId] Unsupported address type: $atyp")
                        cleanup(clientSocket)
                        return@withContext
                    }
                }
                val portBytes = ByteArray(2)
                if (input.read(portBytes) != 2) {
                    println("[$connectionId] Could not read port")
                    cleanup(clientSocket)
                    return@withContext
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
            targetSocket.keepAlive = true
            targetSocket.tcpNoDelay = true
            targetSocket.soTimeout = 30000
            targetSocket.receiveBufferSize = 8192
            targetSocket.sendBufferSize = 8192
            targetSocket.connect(java.net.InetSocketAddress(host, port), 10000)
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
                val buffer = ByteArray(8192)
                var totalBytes = 0L
                var lastActivityTime = System.currentTimeMillis()

                while (isRunning &&
                    !clientSocket.isClosed &&
                    !targetSocket.isClosed &&
                    clientSocket.isConnected &&
                    targetSocket.isConnected) {
                    try {
                        // Check for timeout (5 minutes of inactivity)
                        if (System.currentTimeMillis() - lastActivityTime > 300000) {
                            println("[$connectionId] $direction: Connection timeout due to inactivity")
                            break
                        }

                        val bytesRead = from.read(buffer)
                        if (bytesRead == -1) {
                            println("[$connectionId] $direction: EOF received, closing connection gracefully")
                            break
                        }

                        if (bytesRead > 0) {
                            to.write(buffer, 0, bytesRead)
                            to.flush()
                            totalBytes += bytesRead
                            lastActivityTime = System.currentTimeMillis()

                            // 주기적으로 연결 상태 확인
                            if (totalBytes % 10240 == 0L) {
                                if (clientSocket.isClosed || targetSocket.isClosed) {
                                    println("[$connectionId] $direction: Socket closed during transfer")
                                    break
                                }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // 타임아웃은 연결이 유지되고 있다면 정상 - 연결 상태 재확인
                        if (clientSocket.isClosed || targetSocket.isClosed) {
                            println("[$connectionId] $direction: Socket closed during timeout check")
                            break
                        }
                        continue
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

        // 소켓들을 우아하게 종료
        try {
            if (!clientSocket.isClosed && clientSocket.isConnected) {
                try {
                    clientSocket.shutdownOutput()
                } catch (e: Exception) {
                    // 이미 닫힌 경우 무시
                }
                try {
                    clientSocket.shutdownInput()
                } catch (e: Exception) {
                    // 이미 닫힌 경우 무시
                }
                clientSocket.close()
                println("[$connectionId] Client socket closed")
            }
        } catch (e: Exception) {
            println("[$connectionId] Error closing client socket: ${e.message}")
        }

        try {
            if (!targetSocket.isClosed && targetSocket.isConnected) {
                try {
                    targetSocket.shutdownOutput()
                } catch (e: Exception) {
                    // 이미 닫힌 경우 무시
                }
                try {
                    targetSocket.shutdownInput()
                } catch (e: Exception) {
                    // 이미 닫힌 경우 무시
                }
                targetSocket.close()
                println("[$connectionId] Target socket closed")
            }
        } catch (e: Exception) {
            println("[$connectionId] Error closing target socket: ${e.message}")
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
            println("Error closing client socket during cleanup: ${e.message}")
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
                println("Error closing active connection: ${e.message}")
            }
        }
        activeConnections.clear()

        // 클라이언트 소켓들 정리
        clientSockets.forEach { socket ->
            try {
                socket.close()
            } catch (e: Exception) {
                println("Error closing client socket: ${e.message}")
            }
        }
        clientSockets.clear()

        // 서버 소켓 닫기
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            println("Error closing server socket: ${e.message}")
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
}