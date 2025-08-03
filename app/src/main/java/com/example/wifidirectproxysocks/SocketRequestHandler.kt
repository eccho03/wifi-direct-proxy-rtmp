package com.example.wifidirectproxysocks

import android.util.Log
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import kotlin.math.min

class SocksRequestHandler(private val clientSocket: Socket) : Runnable {

    companion object {
        // SOCKS5 Constants
        const val SOCKS_VERSION = 0x05.toByte()
        const val AUTH_NO_AUTH = 0x00.toByte()
        const val AUTH_FAIL = 0xFF.toByte()
        const val CMD_CONNECT = 0x01.toByte()
        const val CMD_BIND = 0x02.toByte()
        const val CMD_UDP_ASSOCIATE = 0x03.toByte()
        const val ATYP_IPV4 = 0x01.toByte()
        const val ATYP_DOMAIN = 0x03.toByte()
        const val ATYP_IPV6 = 0x04.toByte()
        const val REP_SUCCESS = 0x00.toByte()
        const val REP_GENERAL_FAILURE = 0x01.toByte()
        const val REP_CONNECTION_NOT_ALLOWED = 0x02.toByte()
        const val REP_NETWORK_UNREACHABLE = 0x03.toByte()
        const val REP_HOST_UNREACHABLE = 0x04.toByte()
        const val REP_CONNECTION_REFUSED = 0x05.toByte()
        const val REP_TTL_EXPIRED = 0x06.toByte()
        const val REP_COMMAND_NOT_SUPPORTED = 0x07.toByte()
        const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08.toByte()

        private const val TAG = "SocksRequestHandler"

        // UDP 연결 관리를 위한 전역 맵
        private val udpSessions = ConcurrentHashMap<String, UdpSession>()

        // RTMP 연결 관리를 위한 전역 맵
        private val rtmpSessions = ConcurrentHashMap<String, RtmpSession>()

        // UDP 패킷 모니터링을 위한 변수들
        private var udpMonitorStarted = false

        // RTMP 관련 상수들
        private val RTMP_PORTS = setOf(1935, 443, 80, 8080) // 일반적인 RTMP 포트들
        private val RTMP_HOSTS = setOf(
            "rtmp.youtube.com",
            "a.rtmp.youtube.com",
            "b.rtmp.youtube.com",
            "live-api-s.facebook.com",
            "ingest.twitch.tv",
            "rtmp-api.twitch.tv"
        )
    }

    // UDP 세션 정보를 담는 클래스
    private data class UdpSession(
        val clientAddress: InetAddress,
        var clientPort: Int,
        val relaySocket: DatagramSocket,
        val isActive: AtomicBoolean = AtomicBoolean(true),
        val lastActivity: AtomicBoolean = AtomicBoolean(false)
    )

    // RTMP 세션 정보를 담는 클래스
    private data class RtmpSession(
        val sessionId: String,
        val clientSocket: Socket,
        val serverSocket: Socket,
        val isActive: AtomicBoolean = AtomicBoolean(true),
        val startTime: Long = System.currentTimeMillis(),
        var bytesUploaded: Long = 0,
        var bytesDownloaded: Long = 0,
        val streamKey: String? = null
    )

    override fun run() {
        val startTime = System.nanoTime()

        try {
            // UDP 패킷 모니터링을 위한 별도 포트 리스너 시작 (한 번만)
            synchronized(udpSessions) {
                if (!udpMonitorStarted) {
                    startUdpMonitorListener()
                    udpMonitorStarted = true
                }
            }

            clientSocket.soTimeout = 0
            clientSocket.keepAlive = true
            clientSocket.tcpNoDelay = true

            if (!handleAuthentication()) {
                Log.w(TAG, "❌ Authentication failed")
                return
            }

            if (!handleRequest()) {
                Log.w(TAG, "❌ Request handling failed")
                return
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling SOCKS request", e)
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing client socket", e)
            }

            val totalElapsed = (System.nanoTime() - startTime) / 1_000_000
            Log.d(TAG, "✅ SOCKS5 연결 처리 완료: $totalElapsed ms")
        }
    }

    // RTMP 연결인지 확인하는 함수
    private fun isRtmpConnection(host: String, port: Int): Boolean {
        // 포트로 RTMP 연결 판단
        if (port in RTMP_PORTS) {
            Log.d(TAG, "🎥 RTMP 포트 감지: $port")
            return true
        }

        // 호스트명으로 RTMP 연결 판단
        val lowerHost = host.lowercase()
        for (rtmpHost in RTMP_HOSTS) {
            if (lowerHost.contains(rtmpHost.lowercase())) {
                Log.d(TAG, "🎥 RTMP 호스트 감지: $host")
                return true
            }
        }

        // URL 패턴으로 RTMP 연결 판단
        if (lowerHost.contains("rtmp") || lowerHost.contains("live") || lowerHost.contains("stream")) {
            Log.d(TAG, "🎥 RTMP 패턴 감지: $host")
            return true
        }

        return false
    }

    // RTMP Handshake 감지 함수
    private fun detectRtmpHandshake(data: ByteArray): Boolean {
        if (data.size < 1537) return false

        // RTMP handshake C0 패킷 확인 (첫 바이트가 0x03)
        if (data[0] == 0x03.toByte()) {
            Log.d(TAG, "🎥 RTMP C0 handshake 감지")
            return true
        }

        // RTMP handshake C1 패킷 확인 (1536 바이트)
        if (data.size == 1536) {
            Log.d(TAG, "🎥 RTMP C1 handshake 감지")
            return true
        }

        return false
    }

    // UDP 패킷 모니터링을 위한 별도 리스너
    private fun startUdpMonitorListener() {
        Thread {
            try {
                val monitorSocket = DatagramSocket(1082)
                val buffer = ByteArray(65536)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.d(TAG, "🎧 UDP 모니터링 리스너 시작: 포트 1082")

                while (true) {
                    try {
                        monitorSocket.soTimeout = 5000
                        monitorSocket.receive(packet)
                        val receivedData = packet.data.copyOfRange(0, packet.length)

                        Log.d(TAG, "📦 UDP 모니터링 포트로 패킷 수신: ${packet.length} bytes from ${packet.address}:${packet.port}")

                        handleMisdirectedUdpPacket(receivedData, packet.address, packet.port)

                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        Log.w(TAG, "UDP 모니터링 리스너 오류: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP 모니터링 리스너 시작 실패: ${e.message}")
            }
        }.start()
    }

    // 잘못된 경로로 전송된 UDP 패킷 처리
    private fun handleMisdirectedUdpPacket(data: ByteArray, clientAddr: InetAddress, clientPort: Int) {
        val udpRequest = parseSocks5UdpRequest(data)
        if (udpRequest != null) {
            Log.d(TAG, "✅ SOCKS5 UDP 요청 파싱 성공: ${udpRequest.destAddress}:${udpRequest.destPort}")

            val sessionKey = "${clientAddr.hostAddress}:$clientPort"
            var udpSession = udpSessions[sessionKey]

            if (udpSession == null) {
                Log.d(TAG, "🆕 새로운 UDP 세션 생성: $sessionKey")
                try {
                    val relaySocket = DatagramSocket()
                    udpSession = UdpSession(clientAddr, clientPort, relaySocket)
                    udpSessions[sessionKey] = udpSession
                    startUdpRelay(udpSession, sessionKey)
                } catch (e: Exception) {
                    Log.e(TAG, "UDP 세션 생성 실패", e)
                    return
                }
            }

            relayUdpToDestination(udpRequest, udpSession, ConcurrentHashMap())
        }
    }

    private fun handleAuthentication(): Boolean {
        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            val version = input.read()
            if (version != SOCKS_VERSION.toInt()) {
                Log.w(TAG, "❌ Unsupported SOCKS version: $version")
                return false
            }

            val nmethods = input.read()
            if (nmethods <= 0) {
                Log.w(TAG, "❌ Invalid number of methods: $nmethods")
                return false
            }

            val methods = ByteArray(nmethods)
            val bytesRead = input.read(methods)
            if (bytesRead != nmethods) {
                Log.w(TAG, "❌ Failed to read all authentication methods")
                return false
            }

            var noAuthSupported = false
            for (method in methods) {
                if (method == AUTH_NO_AUTH) {
                    noAuthSupported = true
                    break
                }
            }

            if (noAuthSupported) {
                output.write(byteArrayOf(SOCKS_VERSION, AUTH_NO_AUTH))
                Log.d(TAG, "✅ Authentication successful (no auth)")
                return true
            } else {
                output.write(byteArrayOf(SOCKS_VERSION, AUTH_FAIL))
                Log.w(TAG, "❌ No supported authentication method")
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Authentication error", e)
            return false
        }
    }

    private fun handleRequest(): Boolean {
        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            val version = input.read()
            val cmd = input.read()
            val rsv = input.read()
            val atyp = input.read()

            Log.d(TAG, "📋 SOCKS Request - Version: $version, Command: $cmd (${getCommandName(cmd)}), ATYP: $atyp")

            if (version != SOCKS_VERSION.toInt()) {
                Log.w(TAG, "❌ Unsupported SOCKS version: $version")
                sendErrorResponse(output, REP_GENERAL_FAILURE)
                return false
            }

            val (destHost, destPort) = when (atyp) {
                ATYP_IPV4.toInt() -> {
                    val addr = ByteArray(4)
                    input.read(addr)
                    val portBytes = ByteArray(2)
                    input.read(portBytes)
                    val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                    Pair(InetAddress.getByAddress(addr).hostAddress, port)
                }
                ATYP_DOMAIN.toInt() -> {
                    val domainLength = input.read()
                    val domain = ByteArray(domainLength)
                    input.read(domain)
                    val portBytes = ByteArray(2)
                    input.read(portBytes)
                    val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                    Pair(String(domain, Charsets.UTF_8), port)
                }
                ATYP_IPV6.toInt() -> {
                    val addr = ByteArray(16)
                    input.read(addr)
                    val portBytes = ByteArray(2)
                    input.read(portBytes)
                    val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
                    Pair(InetAddress.getByAddress(addr).hostAddress, port)
                }
                else -> {
                    Log.w(TAG, "❌ Unsupported address type: $atyp")
                    sendErrorResponse(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
                    return false
                }
            }

            return when (cmd) {
                CMD_CONNECT.toInt() -> {
                    if (isRtmpConnection(destHost, destPort)) {
                        Log.d(TAG, "🎥 Processing RTMP TCP CONNECT to $destHost:$destPort")
                        handleRtmpConnect(output, destHost, destPort)
                    } else {
                        Log.d(TAG, "📡 Processing TCP CONNECT to $destHost:$destPort")
                        handleTcpConnect(output, destHost, destPort)
                    }
                }
                CMD_UDP_ASSOCIATE.toInt() -> {
                    Log.d(TAG, "📡 Processing UDP ASSOCIATE for $destHost:$destPort")
                    handleUdpAssociate(output, destHost, destPort)
                }
                else -> {
                    Log.w(TAG, "❌ Unsupported command: $cmd (${getCommandName(cmd)})")
                    sendErrorResponse(output, REP_COMMAND_NOT_SUPPORTED)
                    false
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Request handling error", e)
            return false
        }
    }

    private fun getCommandName(cmd: Int): String {
        return when (cmd) {
            CMD_CONNECT.toInt() -> "CONNECT"
            CMD_BIND.toInt() -> "BIND"
            CMD_UDP_ASSOCIATE.toInt() -> "UDP_ASSOCIATE"
            else -> "UNKNOWN($cmd)"
        }
    }

    private fun handleRtmpConnect(output: java.io.OutputStream, destHost: String, destPort: Int): Boolean {
        Log.d(TAG, "🎥 RTMP Connecting to $destHost:$destPort")

        val destSocket = try {
            val socket = Socket()
            socket.keepAlive = true
            socket.tcpNoDelay = false // RTMP에서는 Nagle 알고리즘 사용할 수 있음
            socket.soTimeout = 0
            // RTMP 스트리밍을 위한 더 큰 버퍼 크기
            socket.receiveBufferSize = 128 * 1024 // 128KB
            socket.sendBufferSize = 128 * 1024    // 128KB
            socket.connect(InetSocketAddress(destHost, destPort), 15000) // 15초 연결 타임아웃
            socket
        } catch (e: ConnectException) {
            Log.w(TAG, "❌ RTMP Connection refused to $destHost:$destPort")
            sendErrorResponse(output, REP_CONNECTION_REFUSED)
            return false
        } catch (e: UnknownHostException) {
            Log.w(TAG, "❌ Unknown RTMP host: $destHost")
            sendErrorResponse(output, REP_HOST_UNREACHABLE)
            return false
        } catch (e: Exception) {
            Log.w(TAG, "❌ RTMP Connection failed to $destHost:$destPort", e)
            sendErrorResponse(output, REP_GENERAL_FAILURE)
            return false
        }

        sendSuccessResponse(output, destSocket.localAddress.address, destSocket.localPort)
        Log.d(TAG, "✅ RTMP connection established to $destHost:$destPort")

        // RTMP 세션 생성 및 관리
        val sessionId = "${System.currentTimeMillis()}-${destHost}:${destPort}"
        val rtmpSession = RtmpSession(sessionId, clientSocket, destSocket)
        rtmpSessions[sessionId] = rtmpSession

        startRtmpDataRelay(rtmpSession)
        return true
    }

    private fun handleTcpConnect(output: java.io.OutputStream, destHost: String, destPort: Int): Boolean {
        Log.d(TAG, "🔗 TCP Connecting to $destHost:$destPort")

        val destSocket = try {
            val socket = Socket()
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socket.soTimeout = 0
            socket.receiveBufferSize = 65536
            socket.sendBufferSize = 65536
            socket.connect(InetSocketAddress(destHost, destPort), 10000)
            socket
        } catch (e: ConnectException) {
            Log.w(TAG, "❌ Connection refused to $destHost:$destPort")
            sendErrorResponse(output, REP_CONNECTION_REFUSED)
            return false
        } catch (e: UnknownHostException) {
            Log.w(TAG, "❌ Unknown host: $destHost")
            sendErrorResponse(output, REP_HOST_UNREACHABLE)
            return false
        } catch (e: Exception) {
            Log.w(TAG, "❌ Connection failed to $destHost:$destPort", e)
            sendErrorResponse(output, REP_GENERAL_FAILURE)
            return false
        }

        sendSuccessResponse(output, destSocket.localAddress.address, destSocket.localPort)
        Log.d(TAG, "✅ TCP connection established to $destHost:$destPort")

        startTcpDataRelay(clientSocket, destSocket)
        return true
    }

    private fun startRtmpDataRelay(rtmpSession: RtmpSession) {
        val relayStartTime = System.nanoTime()
        Log.d(TAG, "🎥 RTMP 데이터 릴레이 시작: ${rtmpSession.sessionId}")

        val clientToServerThread = Thread {
            try {
                copyRtmpStream(
                    rtmpSession.clientSocket.getInputStream(),
                    rtmpSession.serverSocket.getOutputStream(),
                    "Client->Server(Upload)",
                    rtmpSession,
                    true
                )
            } catch (e: Exception) {
                Log.d(TAG, "🎥 RTMP Client->Server relay ended: ${e.javaClass.simpleName}")
            } finally {
                try {
                    rtmpSession.serverSocket.shutdownOutput()
                } catch (e: Exception) {}
            }
        }

        val serverToClientThread = Thread {
            try {
                copyRtmpStream(
                    rtmpSession.serverSocket.getInputStream(),
                    rtmpSession.clientSocket.getOutputStream(),
                    "Server->Client(Download)",
                    rtmpSession,
                    false
                )
            } catch (e: Exception) {
                Log.d(TAG, "🎥 RTMP Server->Client relay ended: ${e.javaClass.simpleName}")
            } finally {
                try {
                    rtmpSession.clientSocket.shutdownOutput()
                } catch (e: Exception) {}
            }
        }

        clientToServerThread.start()
        serverToClientThread.start()

        // RTMP 세션 모니터링 스레드
        Thread {
            try {
                clientToServerThread.join()
                serverToClientThread.join()
            } catch (e: InterruptedException) {
                Log.d(TAG, "🎥 RTMP 데이터 릴레이 중단됨")
            } finally {
                rtmpSession.isActive.set(false)
                rtmpSessions.remove(rtmpSession.sessionId)

                try {
                    rtmpSession.serverSocket.close()
                } catch (e: Exception) {}

                val relayElapsed = (System.nanoTime() - relayStartTime) / 1_000_000
                val totalUpload = rtmpSession.bytesUploaded / 1024 / 1024 // MB
                val totalDownload = rtmpSession.bytesDownloaded / 1024 / 1024 // MB
                val duration = (System.currentTimeMillis() - rtmpSession.startTime) / 1000 // seconds

                Log.d(TAG, "✅ RTMP 스트리밍 세션 완료:")
                Log.d(TAG, "   📊 세션 ID: ${rtmpSession.sessionId}")
                Log.d(TAG, "   ⏱️  지속 시간: ${duration}초")
                Log.d(TAG, "   📤 업로드: ${totalUpload}MB")
                Log.d(TAG, "   📥 다운로드: ${totalDownload}MB")
                Log.d(TAG, "   🚀 평균 업로드 속도: ${if (duration > 0) totalUpload / duration else 0} MB/s")
            }
        }.start()
    }

    private fun copyRtmpStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        direction: String,
        rtmpSession: RtmpSession,
        isUpload: Boolean
    ) {
        val bufferedInput = BufferedInputStream(input, 64 * 1024)
        val bufferedOutput = BufferedOutputStream(output, 64 * 1024)
        val buffer = ByteArray(32 * 1024) // 32KB 버퍼
        var totalBytes = 0L
        val startTime = System.nanoTime()
        var lastLogTime = startTime
        var isHandshakePhase = true
        var handshakeBytes = 0

        try {
            while (rtmpSession.isActive.get()) {
                val bytesRead = bufferedInput.read(buffer)
                if (bytesRead == -1) {
                    Log.d(TAG, "🎥 $direction: Stream ended normally (EOF)")
                    break
                }

                // RTMP handshake 감지 (처음 몇 KB)
                if (isHandshakePhase && handshakeBytes < 4096) {
                    if (detectRtmpHandshake(buffer.copyOfRange(0, bytesRead))) {
                        Log.d(TAG, "🎥 $direction: RTMP handshake 감지됨")
                    }
                    handshakeBytes += bytesRead
                    if (handshakeBytes >= 4096) {
                        isHandshakePhase = false
                        Log.d(TAG, "🎥 $direction: RTMP handshake 단계 완료")
                    }
                }

                bufferedOutput.write(buffer, 0, bytesRead)
                bufferedOutput.flush()
                totalBytes += bytesRead

                // 바이트 카운트 업데이트
                if (isUpload) {
                    rtmpSession.bytesUploaded += bytesRead
                } else {
                    rtmpSession.bytesDownloaded += bytesRead
                }

                val currentTime = System.nanoTime()

                // 로그 출력 (5초마다 또는 10MB마다)
                if (currentTime - lastLogTime > 5_000_000_000L || totalBytes % (10 * 1024 * 1024) == 0L) {
                    val elapsed = (currentTime - startTime) / 1_000_000
                    val mbps = if (elapsed > 0) (totalBytes * 8.0 / 1_000_000) / (elapsed / 1000.0) else 0.0
                    Log.d(TAG, "🎥 $direction: ${totalBytes / 1024 / 1024}MB, ${String.format("%.2f", mbps)} Mbps, ${elapsed}ms")
                    lastLogTime = currentTime
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "🎥 $direction: Socket timeout after ${totalBytes / 1024}KB")
        } catch (e: java.net.SocketException) {
            if (e.message?.contains("Connection reset") == true) {
                Log.d(TAG, "🎥 $direction: Connection reset by peer after ${totalBytes / 1024}KB")
            } else if (e.message?.contains("Broken pipe") == true) {
                Log.d(TAG, "🎥 $direction: Broken pipe after ${totalBytes / 1024}KB")
            } else if (e.message?.contains("Socket closed") == true) {
                Log.d(TAG, "🎥 $direction: Socket closed after ${totalBytes / 1024}KB")
            } else {
                Log.w(TAG, "🎥 $direction: Socket error after ${totalBytes / 1024}KB: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "🎥 $direction: ${e.javaClass.simpleName} after ${totalBytes / 1024}KB: ${e.message}")
        }

        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        val mbps = if (elapsed > 0) (totalBytes * 8.0 / 1_000_000) / (elapsed / 1000.0) else 0.0

        if (totalBytes > 0) {
            Log.d(TAG, "✅ 🎥 $direction: ${totalBytes / 1024 / 1024}MB total, ${elapsed}ms, ${String.format("%.2f", mbps)} Mbps avg")
        } else {
            Log.d(TAG, "✅ 🎥 $direction: No data transferred, ${elapsed}ms")
        }
    }

    private fun handleUdpAssociate(output: java.io.OutputStream, destHost: String, destPort: Int): Boolean {
        Log.d(TAG, "🔗 UDP ASSOCIATE request for $destHost:$destPort")

        try {
            val udpRelaySocket = DatagramSocket()
            val relayPort = udpRelaySocket.localPort
            val relayAddress = clientSocket.localAddress

            Log.d(TAG, "✅ UDP relay socket created on ${relayAddress.hostAddress}:$relayPort")

            val clientAddress = clientSocket.inetAddress
            val sessionKey = "${clientAddress.hostAddress}:${clientSocket.port}"

            val udpSession = UdpSession(clientAddress, clientSocket.port, udpRelaySocket)
            udpSessions[sessionKey] = udpSession

            startUdpRelay(udpSession, sessionKey)
            sendSuccessResponse(output, relayAddress.address, relayPort)

            Log.d(TAG, "✅ UDP ASSOCIATE established. Relay: ${relayAddress.hostAddress}:$relayPort")

            maintainTcpConnection(udpSession)
            return true

        } catch (e: Exception) {
            Log.e(TAG, "❌ UDP ASSOCIATE failed", e)
            sendErrorResponse(output, REP_GENERAL_FAILURE)
            return false
        }
    }

    private fun startUdpRelay(udpSession: UdpSession, sessionKey: String) {
        Thread {
            val buffer = ByteArray(65536)
            val packet = DatagramPacket(buffer, buffer.size)
            val remoteConnections = ConcurrentHashMap<String, DatagramSocket>()

            Log.d(TAG, "🔄 UDP relay started for session: $sessionKey")

            try {
                while (udpSession.isActive.get()) {
                    try {
                        udpSession.relaySocket.soTimeout = 1000
                        udpSession.relaySocket.receive(packet)
                        udpSession.lastActivity.set(true)

                        val receivedData = packet.data.copyOfRange(0, packet.length)

                        if (packet.address == udpSession.clientAddress) {
                            val udpRequest = parseSocks5UdpRequest(receivedData)
                            if (udpRequest != null) {
                                relayUdpToDestination(udpRequest, udpSession, remoteConnections)
                            }

                            if (udpSession.clientPort != packet.port) {
                                udpSession.clientPort = packet.port
                            }
                        } else {
                            sendUdpResponseToClient(receivedData, packet.address.hostAddress, packet.port, udpSession)
                        }

                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        if (udpSession.isActive.get()) {
                            Log.w(TAG, "UDP relay error: ${e.message}")
                        }
                        break
                    }
                }
            } finally {
                for (socket in remoteConnections.values) {
                    try { socket.close() } catch (e: Exception) {}
                }
                udpSessions.remove(sessionKey)
                try {
                    udpSession.relaySocket.close()
                } catch (e: Exception) {}
                Log.d(TAG, "✅ UDP relay ended for session: $sessionKey")
            }
        }.start()
    }

    private fun maintainTcpConnection(udpSession: UdpSession) {
        try {
            val buffer = ByteArray(1)

            while (udpSession.isActive.get()) {
                try {
                    clientSocket.soTimeout = 5000
                    val bytesRead = clientSocket.getInputStream().read(buffer)
                    if (bytesRead == -1) {
                        Log.d(TAG, "📴 TCP connection closed by client")
                        break
                    }
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    Log.d(TAG, "📴 TCP connection error: ${e.message}")
                    break
                }
            }
        } finally {
            udpSession.isActive.set(false)
        }
    }

    private data class Socks5UdpRequest(
        val fragment: Int,
        val addressType: Int,
        val destAddress: String,
        val destPort: Int,
        val data: ByteArray
    )

    private fun parseSocks5UdpRequest(data: ByteArray): Socks5UdpRequest? {
        if (data.size < 10) {
            Log.w(TAG, "UDP 패킷이 너무 작음: ${data.size} bytes")
            return null
        }

        try {
            var offset = 0

            val rsv = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2

            val fragment = data[offset].toInt() and 0xFF
            offset += 1

            val atyp = data[offset].toInt() and 0xFF
            offset += 1

            Log.d(TAG, "🔍 UDP 요청 파싱: RSV=$rsv, FRAG=$fragment, ATYP=$atyp")

            val (destAddress, addressLength) = when (atyp) {
                ATYP_IPV4.toInt() -> {
                    if (offset + 4 > data.size) {
                        Log.w(TAG, "IPv4 주소 데이터 부족")
                        return null
                    }
                    val addr = data.sliceArray(offset until offset + 4)
                    Pair(InetAddress.getByAddress(addr).hostAddress, 4)
                }
                ATYP_DOMAIN.toInt() -> {
                    if (offset >= data.size) {
                        Log.w(TAG, "도메인 길이 데이터 부족")
                        return null
                    }
                    val domainLength = data[offset].toInt() and 0xFF
                    offset += 1
                    if (offset + domainLength > data.size) {
                        Log.w(TAG, "도메인 데이터 부족")
                        return null
                    }
                    val domain = String(data.sliceArray(offset until offset + domainLength), Charsets.UTF_8)
                    Pair(domain, domainLength + 1)
                }
                ATYP_IPV6.toInt() -> {
                    if (offset + 16 > data.size) {
                        Log.w(TAG, "IPv6 주소 데이터 부족")
                        return null
                    }
                    val addr = data.sliceArray(offset until offset + 16)
                    Pair(InetAddress.getByAddress(addr).hostAddress, 16)
                }
                else -> {
                    Log.w(TAG, "지원하지 않는 주소 타입: $atyp")
                    return null
                }
            }

            offset += addressLength

            if (offset + 2 > data.size) {
                Log.w(TAG, "포트 데이터 부족")
                return null
            }

            val destPort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2

            val userData = data.sliceArray(offset until data.size)

            Log.d(TAG, "✅ UDP 요청 파싱 성공: $destAddress:$destPort, 데이터 ${userData.size}바이트")
            return Socks5UdpRequest(fragment, atyp, destAddress, destPort, userData)

        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SOCKS5 UDP request", e)
            return null
        }
    }

    private fun relayUdpToDestination(request: Socks5UdpRequest, udpSession: UdpSession, remoteConnections: ConcurrentHashMap<String, DatagramSocket>) {
        try {
            val destKey = "${request.destAddress}:${request.destPort}"

            val destSocket = remoteConnections.getOrPut(destKey) {
                val socket = DatagramSocket()
                socket.soTimeout = 30000

                Thread {
                    val responseBuffer = ByteArray(65536)
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)

                    try {
                        while (udpSession.isActive.get() && !socket.isClosed) {
                            socket.receive(responsePacket)
                            val responseData = responsePacket.data.copyOfRange(0, responsePacket.length)

                            Log.d(TAG, "📥 UDP response from ${responsePacket.address}:${responsePacket.port} (${responseData.size} bytes)")

                            sendUdpResponseToClient(
                                responseData,
                                responsePacket.address.hostAddress,
                                responsePacket.port,
                                udpSession
                            )
                        }
                    } catch (e: SocketTimeoutException) {
                        Log.d(TAG, "UDP response listener timeout for $destKey")
                    } catch (e: Exception) {
                        if (udpSession.isActive.get()) {
                            Log.d(TAG, "UDP response listener ended for $destKey: ${e.message}")
                        }
                    }
                }.start()

                socket
            }

            val destAddress = InetAddress.getByName(request.destAddress)
            val packet = DatagramPacket(request.data, request.data.size, destAddress, request.destPort)

            destSocket.send(packet)
            Log.d(TAG, "📤 UDP packet sent to ${request.destAddress}:${request.destPort} (${request.data.size} bytes)")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to relay UDP packet to ${request.destAddress}:${request.destPort}", e)
        }
    }

    private fun sendUdpResponseToClient(data: ByteArray, sourceAddress: String, sourcePort: Int, udpSession: UdpSession) {
        try {
            val response = mutableListOf<Byte>()
            response.add(0x00) // RSV
            response.add(0x00) // RSV
            response.add(0x00) // FRAG

            val addr = InetAddress.getByName(sourceAddress)
            if (addr.address.size == 4) {
                response.add(ATYP_IPV4)
                response.addAll(addr.address.toList())
            } else {
                response.add(ATYP_IPV6)
                response.addAll(addr.address.toList())
            }

            response.add((sourcePort shr 8).toByte())
            response.add(sourcePort.toByte())
            response.addAll(data.toList())

            val responsePacket = DatagramPacket(
                response.toByteArray(),
                response.size,
                udpSession.clientAddress,
                udpSession.clientPort
            )

            udpSession.relaySocket.send(responsePacket)
            Log.d(TAG, "📤 UDP response sent to client (${data.size} bytes)")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to send UDP response to client", e)
        }
    }

    private fun sendSuccessResponse(output: java.io.OutputStream, localAddr: ByteArray, localPort: Int) {
        val response = mutableListOf<Byte>()
        response.add(SOCKS_VERSION)
        response.add(REP_SUCCESS)
        response.add(0x00) // Reserved

        if (localAddr.size == 4) {
            response.add(ATYP_IPV4)
            response.addAll(localAddr.toList())
        } else {
            response.add(ATYP_IPV6)
            response.addAll(localAddr.toList())
        }

        response.add((localPort shr 8).toByte())
        response.add(localPort.toByte())

        output.write(response.toByteArray())
        output.flush()
    }

    private fun sendErrorResponse(output: java.io.OutputStream, errorCode: Byte) {
        try {
            val response = byteArrayOf(
                SOCKS_VERSION, errorCode, 0x00,
                ATYP_IPV4, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
            )
            output.write(response)
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send error response", e)
        }
    }

    private fun startTcpDataRelay(clientSocket: Socket, destSocket: Socket) {
        val relayStartTime = System.nanoTime()

        val clientToDestThread = Thread {
            try {
                Log.d(TAG, "▶▶ 시작: 클라이언트 → 목적지 데이터 릴레이")
                copyStream(clientSocket.getInputStream(), destSocket.getOutputStream(), "Client->Dest")
                Log.d(TAG, "◀◀ 종료: 클라이언트 → 목적지 데이터 릴레이")
            } catch (e: Exception) {
                Log.e(TAG, "클라이언트 → 목적지 릴레이 오류", e)
            } finally {
                try {
                    destSocket.shutdownOutput()
                } catch (e: Exception) {
                    Log.w(TAG, "destSocket.shutdownOutput() 실패", e)
                }
            }
        }

        val destToClientThread = Thread {
            try {
                Log.d(TAG, "▶▶ 시작: 목적지 → 클라이언트 데이터 릴레이")
                copyStream(destSocket.getInputStream(), clientSocket.getOutputStream(), "Dest->Client")
                Log.d(TAG, "◀◀ 종료: 목적지 → 클라이언트 데이터 릴레이")
            } catch (e: Exception) {
                Log.e(TAG, "목적지 → 클라이언트 릴레이 오류", e)
            } finally {
                try {
                    clientSocket.shutdownOutput()
                } catch (e: Exception) {
                    Log.w(TAG, "clientSocket.shutdownOutput() 실패", e)
                }
            }
        }

        clientToDestThread.start()
        destToClientThread.start()

        try {
            clientToDestThread.join()
            destToClientThread.join()
        } catch (e: InterruptedException) {
            Log.w(TAG, "데이터 릴레이 스레드 중단됨", e)
        }

        try {
            destSocket.close()
        } catch (e: Exception) {
            Log.w(TAG, "destSocket.close() 실패", e)
        }

        val relayElapsed = (System.nanoTime() - relayStartTime) / 1_000_000
        Log.d(TAG, "✅ TCP 데이터 릴레이 완료: $relayElapsed ms")
    }

    private fun copyStream(input: java.io.InputStream, output: java.io.OutputStream, direction: String) {
        val buffer = ByteArray(32768)
        var totalBytes = 0
        val startTime = System.nanoTime()
        var lastLogTime = startTime

        try {
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) {
                    Log.d(TAG, "$direction: 스트림 정상 종료 (EOF)")
                    break
                }

                output.write(buffer, 0, bytesRead)
                output.flush()
                totalBytes += bytesRead

                val currentTime = System.nanoTime()
                // 5초마다 또는 1MB 단위 전송시 로그 출력
                if (currentTime - lastLogTime > 5_000_000_000L || totalBytes % (1024 * 1024) < bytesRead) {
                    val elapsedMs = (currentTime - startTime) / 1_000_000
                    val kbps = if (elapsedMs > 0) (totalBytes * 8.0 / 1000) / (elapsedMs / 1000.0) else 0.0
                    Log.d(TAG, "📊 $direction: 전송 ${totalBytes / 1024}KB, 속도 ${String.format("%.1f", kbps)} kbps, 경과 ${elapsedMs}ms")
                    lastLogTime = currentTime
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "$direction: 소켓 타임아웃 발생. 전송 바이트 수=${totalBytes}", e)
        } catch (e: java.net.SocketException) {
            when {
                e.message?.contains("Connection reset") == true -> Log.d(TAG, "$direction: 연결 리셋됨, 전송 바이트 수=${totalBytes}")
                e.message?.contains("Broken pipe") == true -> Log.d(TAG, "$direction: Broken pipe, 전송 바이트 수=${totalBytes}")
                e.message?.contains("Socket closed") == true -> Log.d(TAG, "$direction: 소켓 닫힘, 전송 바이트 수=${totalBytes}")
                else -> Log.w(TAG, "$direction: 소켓 오류 발생, 전송 바이트 수=${totalBytes}, 오류 메시지=${e.message}", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$direction: 예상치 못한 오류, 전송 바이트 수=${totalBytes}, 오류: ${e.message}", e)
        }

        val totalElapsed = (System.nanoTime() - startTime) / 1_000_000
        val avgKbps = if (totalElapsed > 0) (totalBytes * 8.0 / 1000) / (totalElapsed / 1000.0) else 0.0

        if (totalBytes > 0) {
            Log.d(TAG, "✅ $direction: 총 전송 ${totalBytes / 1024}KB, 총 경과 ${totalElapsed}ms, 평균 속도 ${String.format("%.1f", avgKbps)} kbps")
        } else {
            Log.d(TAG, "✅ $direction: 데이터 전송 없음, 총 경과 ${totalElapsed}ms")
        }
    }

    // RTMP 세션 상태 모니터링 함수 (필요시 호출)
    fun getRtmpSessionsStatus(): String {
        val sb = StringBuilder()
        sb.append("🎥 활성 RTMP 세션: ${rtmpSessions.size}개\n")

        for ((sessionId, session) in rtmpSessions) {
            val duration = (System.currentTimeMillis() - session.startTime) / 1000
            val uploadMB = session.bytesUploaded / 1024 / 1024
            val downloadMB = session.bytesDownloaded / 1024 / 1024
            val uploadSpeed = if (duration > 0) uploadMB / duration else 0

            sb.append("📡 세션: $sessionId\n")
            sb.append("   ⏱️ 지속시간: ${duration}초\n")
            sb.append("   📤 업로드: ${uploadMB}MB (${uploadSpeed}MB/s)\n")
            sb.append("   📥 다운로드: ${downloadMB}MB\n")
            sb.append("   🔗 활성: ${session.isActive.get()}\n")
        }

        return sb.toString()
    }
}