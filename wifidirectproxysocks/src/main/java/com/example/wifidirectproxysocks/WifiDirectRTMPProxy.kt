package com.example.wifidirectproxysocks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*

class WiFiDirectRTMPProxy(private val context: Context) {

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var socksProxyServer: SocksProxyServer? = null
    private var isProxyRunning = false

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    //private var streamer: FFmpegRTMPStreamer? = null

    // WiFi P2P 상태 리스너
    private val wifiP2pReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    println("WiFi P2P state changed: $state")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    println("WiFi P2P peers changed")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    println("WiFi P2P connection changed")
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    println("WiFi P2P this device changed")
                }
            }
        }
    }

    init {
        initWiFiDirect()
        //streamer = FFmpegRTMPStreamer()
    }

    private fun initWiFiDirect() {
        try {
            wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            channel = wifiP2pManager?.initialize(context, Looper.getMainLooper()) {
                println("WiFi Direct channel disconnected")
            }

            // BroadcastReceiver 등록
            val intentFilter = android.content.IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            context.registerReceiver(wifiP2pReceiver, intentFilter)
        } catch (e: Exception) {
            println("Error initializing WiFi Direct: ${e.message}")
        }
    }

    fun startProxy(port: Int = 1081, rtmpUrl: String) {
        if (isProxyRunning) {
            println("Proxy is already running")
            return
        }

        scope.launch {
            try {
                // 권한 확인
                if (!checkPermissions()) {
                    println("Required permissions not granted")
                    return@launch
                }

                // WiFi Direct 그룹 생성
                createWiFiDirectGroup()

                // SOCKS 프록시 서버 시작
                socksProxyServer = SocksProxyServer(port)
                socksProxyServer?.start()
                isProxyRunning = true

                // 네이티브 RTMP 스트리밍 시작
                //streamer?.startStream("rtmp://a.rtmp.youtube.com/live2/eh8y-tw7s-g62s-zt2x-1hmt")

                println("WiFi Direct RTMP Proxy started on port $port")

            } catch (e: Exception) {
                println("Failed to start proxy: ${e.message}")
                e.printStackTrace()
                isProxyRunning = false
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        return permissions.all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun createWiFiDirectGroup() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            println("Location permission not granted for WiFi Direct")
            return
        }

        try {
            wifiP2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    println("WiFi Direct group created successfully")
                    getGroupInfo()
                }

                override fun onFailure(reason: Int) {
                    println("Failed to create WiFi Direct group: $reason")
                }
            })
        } catch (e: Exception) {
            println("Error creating WiFi Direct group: ${e.message}")
        }
    }

    private fun getGroupInfo() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            wifiP2pManager?.requestGroupInfo(channel) { group ->
                group?.let {
                    println("Group Owner: ${it.owner}")
                    println("Group Network Name: ${it.networkName}")
                    println("Group Passphrase: ${it.passphrase}")
                }
            }
        } catch (e: Exception) {
            println("Error getting group info: ${e.message}")
        }
    }

    fun stopProxy() {
        if (!isProxyRunning) {
            println("Proxy is not running")
            return
        }

        try {
            println("Stopping WiFi Direct RTMP Proxy...")

            // Stop SOCKS proxy server
            socksProxyServer?.let { server ->
                server.stop()
                socksProxyServer = null
            }
            isProxyRunning = false

            // Stop RTMP streamer
            //streamer?.stopStream()

            // WiFi Direct 그룹 제거
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    println("WiFi Direct group removed")
                }

                override fun onFailure(reason: Int) {
                    println("Failed to remove WiFi Direct group: $reason")
                }
            })

            try {
                context.unregisterReceiver(wifiP2pReceiver)
            } catch (e: Exception) {
                println("Error unregistering receiver: ${e.message}")
            }

            scope.cancel()
            println("WiFi Direct RTMP Proxy stopped")
        } catch (e: Exception) {
            println("Error stopping proxy: ${e.message}")
            e.printStackTrace()
        }
    }

    fun isRunning(): Boolean {
        return isProxyRunning
    }

    /**
     * Returns the UDP relay port number used by the SOCKS proxy server.
     * @return UDP relay port, or -1 if not available.
     */
    fun getUdpRelayPort(): Int {
        return socksProxyServer?.getUdpRelayPort() ?: -1
    }

    /**
     * Returns the number of active TCP connections.
     * @return Number of active TCP connections.
     */
    fun getConnectionCount(): Int {
        return socksProxyServer?.getConnectionCount() ?: 0
    }

    /**
     * Returns the number of active UDP associations.
     * @return Number of active UDP associations.
     */
    fun getUdpAssociationCount(): Int {
        return socksProxyServer?.getUdpAssociationCount() ?: 0
    }

    /**
     * Returns a detailed status string of the proxy server.
     * @return Status string including TCP and UDP information.
     */
    fun getProxyStatus(): String {
        return socksProxyServer?.getServerStatus() ?: "Not running"
    }
}