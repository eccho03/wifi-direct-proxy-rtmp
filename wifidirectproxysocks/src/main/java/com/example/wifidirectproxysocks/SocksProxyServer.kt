package com.example.wifidirectproxysocks

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

class EnhancedSocksProxyServer(private val port: Int, private val enableDebugLog: Boolean = false) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeConnections = ConcurrentHashMap<String, ConnectionInfo>()
    private val clientSockets = mutableSetOf<Socket>()
    private val connectionCounter = AtomicInteger(0)
    private val maxConnections = 100
    private val totalBytesTransferred = AtomicLong(0)
    private val dateFormatter = SimpleDateFormat("HH:mm:ss.SSS")

    // Web application specific patterns
    private val webAppDomains = setOf(
        "youtube.com", "googlevideo.com", "googleapis.com", "gstatic.com",
        "doubleclick.net", "googleadservices.com", "googletagmanager.com",
        "google-analytics.com", "googletagservices.com"
    )

    data class ConnectionInfo(
        val clientSocket: Socket,
        val targetSocket: Socket,
        val startTime: Long,
        val bytesTransferred: AtomicLong = AtomicLong(0),
        val host: String,
        val port: Int,
        val isWebApp: Boolean = false
    )

    data class ConnectionStats(
        var totalConnections: Int = 0,
        var activeConnections: Int = 0,
        var failedConnections: Int = 0,
        var webAppConnections: Int = 0,
        var bytesTransferred: Long = 0
    )

    fun start() {
        if (isRunning) {
            println("Server is already running")
            return
        }

        serverScope.launch {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                    soTimeout = 1000
                    receiveBufferSize = 65536
                }
                isRunning = true
                println("Enhanced SOCKS5 proxy server started on port $port")
                if (enableDebugLog) {
                    println("Debug logging enabled")
                }

                // Start monitoring coroutine
                launch { monitorConnections() }

                while (isRunning && serverScope.isActive) {
                    try {
                        if (activeConnections.size >= maxConnections) {
                            debugLog("Maximum connections reached ($maxConnections), waiting...")
                            delay(100)
                            continue
                        }

                        val clientSocket = serverSocket?.accept()
                        clientSocket?.let { socket ->
                            configureSocket(socket, isClient = true)
                            clientSockets.add(socket)

                            launch(Dispatchers.IO + SupervisorJob()) {
                                try {
                                    handleClient(socket)
                                } catch (e: Exception) {
                                    debugLog("Error in client handler: ${e.message}")
                                    cleanup(socket)
                                }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        if (isRunning) {
                            debugLog("Error accepting client: ${e.message}")
                            delay(50)
                        }
                    }
                }
                println("Enhanced SOCKS proxy server stopped")
            } catch (e: Exception) {
                println("SOCKS proxy server error: ${e.message}")
            }
        }
    }

    private fun configureSocket(socket: Socket, isClient: Boolean = false) {
        socket.apply {
            keepAlive = true
            tcpNoDelay = true

            // Different timeouts for client vs target connections
            soTimeout = if (isClient) 60000 else 30000

            // Larger buffers for better web app performance
            receiveBufferSize = 131072 // 128KB
            sendBufferSize = 131072

            try {
                // Prioritize low latency for web apps
                setPerformancePreferences(0, 1, 2)
            } catch (e: Exception) {
                // Ignore if not supported
            }
        }
    }

    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        val connectionId = "conn-${connectionCounter.incrementAndGet()}"
        debugLog("[$connectionId] New client connection from ${clientSocket.inetAddress}")

        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            if (!performHandshake(input, output, connectionId)) {
                cleanup(clientSocket)
                return@withContext
            }

            handleSocksRequest(input, output, clientSocket, connectionId)

        } catch (e: Exception) {
            debugLog("[$connectionId] Client handling error: ${e.message}")
            cleanup(clientSocket)
        }
    }

    private suspend fun performHandshake(input: InputStream, output: OutputStream, connectionId: String): Boolean {
        return try {
            withTimeout(10000) { // Longer timeout for web apps
                val handshakeHeader = ByteArray(2)
                if (input.read(handshakeHeader) != 2 || handshakeHeader[0] != 0x05.toByte()) {
                    debugLog("[$connectionId] Invalid SOCKS5 handshake")
                    return@withTimeout false
                }

                val nMethods = handshakeHeader[1].toInt() and 0xFF
                if (nMethods <= 0) {
                    debugLog("[$connectionId] No authentication methods provided")
                    return@withTimeout false
                }

                val methods = ByteArray(nMethods)
                if (input.read(methods) != nMethods) {
                    debugLog("[$connectionId] Could not read all authentication methods")
                    return@withTimeout false
                }

                // Reply: no authentication required
                output.write(byteArrayOf(0x05, 0x00))
                output.flush()
                debugLog("[$connectionId] Handshake completed successfully")
                true
            }
        } catch (e: Exception) {
            debugLog("[$connectionId] Handshake error: ${e.message}")
            false
        }
    }

    private suspend fun handleSocksRequest(
        input: InputStream,
        output: OutputStream,
        clientSocket: Socket,
        connectionId: String
    ) {
        try {
            withTimeout(15000) { // Extended timeout for complex requests
                val reqHeader = ByteArray(4)
                if (input.read(reqHeader) != 4) {
                    debugLog("[$connectionId] Could not read request header")
                    sendErrorResponse(output, 0x01)
                    return@withTimeout
                }

                val version = reqHeader[0]
                val cmd = reqHeader[1]
                val atyp = reqHeader[3]

                if (version != 0x05.toByte()) {
                    debugLog("[$connectionId] Invalid SOCKS version: $version")
                    sendErrorResponse(output, 0x01)
                    return@withTimeout
                }

                if (cmd != 0x01.toByte()) {
                    debugLog("[$connectionId] Unsupported command: $cmd")
                    sendErrorResponse(output, 0x07)
                    return@withTimeout
                }

                val (address, port) = parseAddressAndPort(input, atyp, connectionId)
                    ?: run {
                        sendErrorResponse(output, 0x01)
                        return@withTimeout
                    }

                debugLog("[$connectionId] CONNECT request to $address:$port")
                handleConnect(address, port, output, clientSocket, connectionId)
            }
        } catch (e: Exception) {
            debugLog("[$connectionId] Request handling error: ${e.message}")
            sendErrorResponse(output, 0x01)
        }
    }

    private suspend fun parseAddressAndPort(
        input: InputStream,
        atyp: Byte,
        connectionId: String
    ): Pair<String, Int>? {
        return try {
            val address = when (atyp.toInt()) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4)
                    if (input.read(addr) != 4) {
                        debugLog("[$connectionId] Could not read IPv4 address")
                        return null
                    }
                    addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> { // Domain
                    val len = input.read()
                    if (len <= 0 || len > 255) {
                        debugLog("[$connectionId] Invalid domain length: $len")
                        return null
                    }
                    val addr = ByteArray(len)
                    if (input.read(addr) != len) {
                        debugLog("[$connectionId] Could not read domain name")
                        return null
                    }
                    String(addr, Charsets.UTF_8)
                }
                0x04 -> { // IPv6
                    val addr = ByteArray(16)
                    if (input.read(addr) != 16) {
                        debugLog("[$connectionId] Could not read IPv6 address")
                        return null
                    }
                    java.net.InetAddress.getByAddress(addr).hostAddress
                }
                else -> {
                    debugLog("[$connectionId] Unsupported address type: $atyp")
                    return null
                }
            }

            val portBytes = ByteArray(2)
            if (input.read(portBytes) != 2) {
                debugLog("[$connectionId] Could not read port")
                return null
            }
            val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            Pair(address, port)
        } catch (e: Exception) {
            debugLog("[$connectionId] Error parsing address: ${e.message}")
            null
        }
    }

    private suspend fun handleConnect(
        host: String,
        port: Int,
        output: OutputStream,
        clientSocket: Socket,
        connectionId: String
    ) {
        var targetSocket: Socket? = null
        val isWebApp = isWebAppDomain(host)

        try {
            debugLog("[$connectionId] Connecting to $host:$port (WebApp: $isWebApp)")

            val connectTimeout = when {
                isWebApp -> 25000 // Extra time for web apps
                port == 443 -> 20000 // HTTPS
                port == 80 -> 15000  // HTTP
                else -> 10000
            }

            targetSocket = Socket().apply {
                configureSocket(this, isClient = false)

                val address = withContext(Dispatchers.IO) {
                    try {
                        java.net.InetAddress.getByName(host)
                    } catch (e: java.net.UnknownHostException) {
                        debugLog("[$connectionId] DNS resolution failed for $host: ${e.message}")
                        throw e
                    }
                }

                debugLog("[$connectionId] Resolved $host to ${address.hostAddress}")
                connect(java.net.InetSocketAddress(address, port), connectTimeout)
            }

            if (targetSocket.isConnected) {
                debugLog("[$connectionId] Successfully connected to $host:$port")

                // Send success response
                val response = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                output.write(response)
                output.flush()

                val connectionKey = "$connectionId-${System.currentTimeMillis()}"
                val connectionInfo = ConnectionInfo(
                    clientSocket, targetSocket, System.currentTimeMillis(),
                    AtomicLong(0), host, port, isWebApp
                )
                activeConnections[connectionKey] = connectionInfo

                startRelay(connectionInfo, connectionId, connectionKey)
            } else {
                throw Exception("Connection failed - not connected")
            }
        } catch (e: java.net.ConnectException) {
            debugLog("[$connectionId] Connection refused: ${e.message}")
            sendErrorResponse(output, 0x05)
            targetSocket?.close()
        } catch (e: java.net.SocketTimeoutException) {
            debugLog("[$connectionId] Connection timeout: ${e.message}")
            sendErrorResponse(output, 0x04)
            targetSocket?.close()
        } catch (e: java.net.UnknownHostException) {
            debugLog("[$connectionId] Unknown host: ${e.message}")
            sendErrorResponse(output, 0x04)
            targetSocket?.close()
        } catch (e: Exception) {
            debugLog("[$connectionId] Connection failed: ${e.message}")
            sendErrorResponse(output, 0x01)
            targetSocket?.close()
        }
    }

    private fun isWebAppDomain(host: String): Boolean {
        return webAppDomains.any { domain ->
            host.equals(domain, ignoreCase = true) ||
                    host.endsWith(".$domain", ignoreCase = true)
        }
    }

    private fun sendErrorResponse(output: OutputStream, errorCode: Byte) {
        try {
            val response = byteArrayOf(0x05, errorCode, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            output.write(response)
            output.flush()
        } catch (e: Exception) {
            debugLog("Error sending error response: ${e.message}")
        }
    }

    private fun startRelay(connectionInfo: ConnectionInfo, connectionId: String, connectionKey: String) {
        val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val clientToServerJob = relayScope.launch {
            relayData(
                from = connectionInfo.clientSocket.getInputStream(),
                to = connectionInfo.targetSocket.getOutputStream(),
                direction = "Client->Server",
                connectionId = connectionId,
                connectionInfo = connectionInfo
            )
        }

        val serverToClientJob = relayScope.launch {
            relayData(
                from = connectionInfo.targetSocket.getInputStream(),
                to = connectionInfo.clientSocket.getOutputStream(),
                direction = "Server->Client",
                connectionId = connectionId,
                connectionInfo = connectionInfo
            )
        }

        serverScope.launch {
            try {
                select {
                    clientToServerJob.onJoin { }
                    serverToClientJob.onJoin { }
                }
            } finally {
                relayScope.cancel()
                cleanupConnection(connectionInfo, connectionId, connectionKey)
            }
        }
    }

    private suspend fun relayData(
        from: InputStream,
        to: OutputStream,
        direction: String,
        connectionId: String,
        connectionInfo: ConnectionInfo
    ) = withContext(Dispatchers.IO) {
        try {
            // Adaptive buffer size based on connection type
            val bufferSize = if (connectionInfo.isWebApp) 131072 else 65536 // 128KB for web apps
            val buffer = ByteArray(bufferSize)
            var totalBytes = 0L
            var lastActivityTime = System.currentTimeMillis()

            // Longer idle timeout for web apps
            val maxIdleTime = if (connectionInfo.isWebApp) 600000 else 300000 // 10min vs 5min

            while (isRunning &&
                !connectionInfo.clientSocket.isClosed &&
                !connectionInfo.targetSocket.isClosed &&
                connectionInfo.clientSocket.isConnected &&
                connectionInfo.targetSocket.isConnected) {

                try {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastActivityTime > maxIdleTime) {
                        debugLog("[$connectionId] $direction: Idle timeout after ${maxIdleTime/1000}s")
                        break
                    }

                    val bytesRead = from.read(buffer)
                    if (bytesRead == -1) {
                        debugLog("[$connectionId] $direction: EOF received")
                        break
                    }

                    if (bytesRead > 0) {
                        to.write(buffer, 0, bytesRead)
                        to.flush()
                        totalBytes += bytesRead
                        connectionInfo.bytesTransferred.addAndGet(bytesRead.toLong())
                        totalBytesTransferred.addAndGet(bytesRead.toLong())
                        lastActivityTime = currentTime

                        // More frequent logging for web apps
                        val logThreshold = if (connectionInfo.isWebApp) 512 * 1024 else 1024 * 1024 // 512KB vs 1MB
                        if (totalBytes % logThreshold == 0L) {
                            debugLog("[$connectionId] $direction: ${totalBytes / (1024 * 1024)}MB transferred")
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // For web apps, continue on timeout instead of breaking
                    if (connectionInfo.isWebApp) {
                        continue
                    } else {
                        debugLog("[$connectionId] $direction: Socket timeout")
                        break
                    }
                } catch (e: java.net.SocketException) {
                    when {
                        e.message?.contains("closed") == true ->
                            debugLog("[$connectionId] $direction: Connection closed gracefully")
                        e.message?.contains("reset") == true ->
                            debugLog("[$connectionId] $direction: Connection reset by peer")
                        e.message?.contains("Broken pipe") == true ->
                            debugLog("[$connectionId] $direction: Broken pipe")
                        else ->
                            debugLog("[$connectionId] $direction: Socket error - ${e.message}")
                    }
                    break
                } catch (e: Exception) {
                    debugLog("[$connectionId] $direction: Unexpected error - ${e.message}")
                    break
                }
            }

            debugLog("[$connectionId] $direction relay finished. Total bytes: $totalBytes")
        } catch (e: Exception) {
            debugLog("[$connectionId] $direction relay error: ${e.message}")
        }
    }

    private fun cleanupConnection(connectionInfo: ConnectionInfo, connectionId: String, connectionKey: String) {
        debugLog("[$connectionId] Cleaning up connection...")

        activeConnections.remove(connectionKey)
        clientSockets.remove(connectionInfo.clientSocket)

        val duration = System.currentTimeMillis() - connectionInfo.startTime
        val bytesTransferred = connectionInfo.bytesTransferred.get()

        debugLog("[$connectionId] Connection to ${connectionInfo.host}:${connectionInfo.port} closed. " +
                "Duration: ${duration}ms, Bytes: $bytesTransferred, WebApp: ${connectionInfo.isWebApp}")

        listOf(connectionInfo.clientSocket, connectionInfo.targetSocket).forEach { socket ->
            try {
                if (!socket.isClosed) {
                    socket.shutdownInput()
                    socket.shutdownOutput()
                    socket.close()
                }
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    private fun cleanup(clientSocket: Socket) {
        clientSockets.remove(clientSocket)
        try {
            if (!clientSocket.isClosed) {
                clientSocket.close()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun monitorConnections() {
        serverScope.launch {
            while (isRunning) {
                delay(60000) // Every minute
                val stats = getConnectionStats()
                if (enableDebugLog || stats.activeConnections > 0) {
                    println("=== Connection Monitor ===")
                    println("Active: ${stats.activeConnections}, Total: ${stats.totalConnections}")
                    println("WebApp connections: ${stats.webAppConnections}")
                    println("Total bytes: ${stats.bytesTransferred / (1024 * 1024)}MB")
                    println("Failed connections: ${stats.failedConnections}")

                    if (activeConnections.isNotEmpty()) {
                        println("Active connections:")
                        activeConnections.forEach { (key, info) ->
                            val duration = (System.currentTimeMillis() - info.startTime) / 1000
                            val bytes = info.bytesTransferred.get()
                            println("  - ${info.host}:${info.port} (${duration}s, ${bytes}B, WebApp:${info.isWebApp})")
                        }
                    }
                    println("========================")
                }
            }
        }
    }

    private fun debugLog(message: String) {
        if (enableDebugLog) {
            println("[${dateFormatter.format(Date())}] $message")
        }
    }

    fun stop() {
        println("Stopping Enhanced SOCKS proxy server...")
        isRunning = false

        activeConnections.values.forEach { connectionInfo ->
            try {
                connectionInfo.clientSocket.close()
                connectionInfo.targetSocket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        activeConnections.clear()

        clientSockets.forEach { socket ->
            try { socket.close() } catch (e: Exception) { /* ignore */ }
        }
        clientSockets.clear()

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }

        serverScope.cancel()
        println("Enhanced SOCKS proxy server stopped")
    }

    // Public status methods
    fun getConnectionCount(): Int = activeConnections.size
    fun getClientSocketCount(): Int = clientSockets.size
    fun isServerRunning(): Boolean = isRunning
    fun getTotalBytesTransferred(): Long = totalBytesTransferred.get()

    private fun getConnectionStats(): ConnectionStats {
        val webAppCount = activeConnections.values.count { it.isWebApp }
        return ConnectionStats(
            totalConnections = connectionCounter.get(),
            activeConnections = activeConnections.size,
            webAppConnections = webAppCount,
            bytesTransferred = totalBytesTransferred.get()
        )
    }

    fun getServerStatus(): String {
        val stats = getConnectionStats()
        return if (isRunning) {
            "Running on port $port (${stats.activeConnections} active, ${stats.webAppConnections} web apps, ${stats.bytesTransferred / (1024 * 1024)}MB transferred)"
        } else {
            "Stopped"
        }
    }

    fun getDetailedStatus(): String {
        val stats = getConnectionStats()
        return buildString {
            appendLine("Enhanced SOCKS5 Proxy Server Status:")
            appendLine("- Running: $isRunning")
            appendLine("- Port: $port")
            appendLine("- Debug logging: $enableDebugLog")
            appendLine("- Active connections: ${stats.activeConnections}/$maxConnections")
            appendLine("- Web app connections: ${stats.webAppConnections}")
            appendLine("- Total connections: ${stats.totalConnections}")
            appendLine("- Client sockets: ${clientSockets.size}")
            appendLine("- Total bytes transferred: ${stats.bytesTransferred / (1024 * 1024)}MB")

            if (activeConnections.isNotEmpty()) {
                appendLine("\nActive Connections:")
                activeConnections.forEach { (key, info) ->
                    val duration = System.currentTimeMillis() - info.startTime
                    val bytes = info.bytesTransferred.get()
                    appendLine("  - $key: ${info.host}:${info.port}")
                    appendLine("    Duration: ${duration}ms, Bytes: $bytes, WebApp: ${info.isWebApp}")
                }
            }
        }
    }
}