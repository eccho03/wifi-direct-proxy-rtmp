package com.example.wifidirectproxysocks

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.wifidirectproxysocks.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiP2pManager
    private lateinit var streamManager: RTMPStreamManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var binding: ActivityMainBinding

    private var isProxyRunning = false
    private var isStreamRunning = false
    private val TAG = "WiFiDirect"
    private lateinit var wifiDirectRTMPProxy: WiFiDirectRTMPProxy
    private val STREAM_KEY = BuildConfig.STREAMING_KEY
    private val youtubeRTMPUrl = "rtmp://a.rtmp.youtube.com/live2/$STREAM_KEY"

    // Video selection data
    private val videoList = listOf<Pair<String, Int>>(
        "Select video..." to 0,
        "paris" to R.raw.paris,
        "game" to R.raw.game,
        "festival" to R.raw.festival,
        "millan" to R.raw.millan,
        "mt" to R.raw.mt,
        "workout" to R.raw.workout
    )

    private var selectedVideoIndex = 0

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.all { it }
        if (granted) {
            Log.d(TAG, "✅ 권한 모두 허용됨")
            startWiFiDirectFlow()
            initializeProxy()
        } else {
            Log.e(TAG, "❌ 권한 거부됨")
            Toast.makeText(this, "필요한 권한이 거부되었습니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 초기 상태 설정
        updateProxyStatus(false, 0)
        updateStreamStatus(false)
        setupVideoSpinner()
        setupClickListeners()

        // WiFi Direct 초기화
        wifiManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiManager.initialize(this, mainLooper, null)

        // 권한 요청
        requestPermissions()

        // 브로드캐스트 리시버 등록
        registerReceivers()
    }

    private fun setupVideoSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, videoList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerVideo.adapter = adapter

        binding.spinnerVideo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedVideoIndex = position
                updateVideoInfo(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedVideoIndex = 0
                updateVideoInfo(0)
            }
        }
    }

    private fun updateVideoInfo(position: Int) {
        val infoText = when (position) {
            0 -> "비디오를 선택해주세요"
            1 -> "해상도: 1280x720, 길이: 10분"
            2 -> "해상도: 1920x1080, 길이: 15분"
            3 -> "해상도: 3840x2160, 길이: 5분"
            4 -> "테스트 패턴, 해상도: 1920x1080"
            else -> "알 수 없는 비디오"
        }
        binding.tvVideoInfo.text = infoText
    }

    private fun setupClickListeners() {
        binding.btnStart.setOnClickListener {
            try {
                if (selectedVideoIndex == 0) {
                    Toast.makeText(this, "먼저 비디오를 선택해주세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val portText = binding.etPort.text.toString()
                if (portText.isEmpty()) {
                    binding.etPort.setText("1081")
                }

                val port = binding.etPort.text.toString().toIntOrNull() ?: 1081

                if (port < 1024) {
                    Toast.makeText(this, "포트는 1024 이상이어야 합니다", Toast.LENGTH_SHORT).show()
                    binding.etPort.setText("1081")
                    return@setOnClickListener
                }

                if (!isProxyRunning) {
                    startProxy(port)
                } else {
                    Log.w(TAG, "Proxy is already running")
                    Toast.makeText(this, "프록시가 이미 실행 중입니다", Toast.LENGTH_SHORT).show()
                }

//                if (!isStreamRunning) {
//                    startStreaming()
//                } else {
//                    Log.w(TAG, "Stream is already running")
//                    Toast.makeText(this, "스트리밍이 이미 실행 중입니다", Toast.LENGTH_SHORT).show()
//                }

            } catch (e: Exception) {
                Log.e(TAG, "Error starting services", e)
                Toast.makeText(this, "서비스 시작 중 오류가 발생했습니다", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnStop.setOnClickListener {
            try {
                var stopCount = 0

                if (isProxyRunning) {
                    stopProxy()
                    stopCount++
                }

                if (isStreamRunning) {
                    stopStreaming()
                    stopCount++
                }

                if (stopCount == 0) {
                    Toast.makeText(this, "실행 중인 서비스가 없습니다", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error stopping services", e)
                Toast.makeText(this, "서비스 중지 중 오류가 발생했습니다", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.INTERNET,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        // Android 13+ 추가 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        permissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun registerReceivers() {
        // 시스템 브로드캐스트 등록
        val systemFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiDirectReceiver, systemFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(wifiDirectReceiver, systemFilter)
        }

        // 로컬 브로드캐스트 등록
        val localFilter = IntentFilter().apply {
            addAction("PROXY_STARTED")
            addAction("PROXY_STOPPED")
            addAction("STREAM_STARTED")
            addAction("STREAM_STOPPED")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(localBroadcastReceiver, localFilter)
    }

    private fun startProxy(port: Int) {
        try {
            if (!::wifiDirectRTMPProxy.isInitialized) {
                Log.e(TAG, "WiFiDirectRTMPProxy not initialized")
                Toast.makeText(this, "프록시가 초기화되지 않았습니다", Toast.LENGTH_SHORT).show()
                return
            }

            wifiDirectRTMPProxy.startProxy(port = port, rtmpUrl = youtubeRTMPUrl)
            updateProxyStatus(true, port)
            Log.d(TAG, "Proxy started on port $port")
            Toast.makeText(this, "프록시가 포트 $port 에서 시작되었습니다", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            updateProxyStatus(false, 0)
            Toast.makeText(this, "프록시 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopProxy() {
        try {
            if (::wifiDirectRTMPProxy.isInitialized) {
                wifiDirectRTMPProxy.stopProxy()
                updateProxyStatus(false, 0)
                Log.d(TAG, "Proxy stopped")
                Toast.makeText(this, "프록시가 중지되었습니다", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "WiFiDirectRTMPProxy not initialized")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop proxy", e)
            Toast.makeText(this, "프록시 중지 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateProxyStatus(isRunning: Boolean, port: Int) {
        isProxyRunning = isRunning

        if (isRunning) {
            binding.tvProxyStatus.text = "Proxy: Running on port $port"
            binding.proxyStatusIndicator.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_active)
            )
        } else {
            binding.tvProxyStatus.text = "Proxy: Stopped"
            binding.proxyStatusIndicator.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_inactive)
            )
        }
    }

    private fun updateStreamStatus(isRunning: Boolean) {
        isStreamRunning = isRunning

        if (isRunning) {
            binding.tvStreamStatus.text = "Stream: Broadcasting"
            binding.streamStatusIndicator.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_active)
            )
        } else {
            binding.tvStreamStatus.text = "Stream: Stopped"
            binding.streamStatusIndicator.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_inactive)
            )
        }
    }

    private fun startWiFiDirectFlow() {
        resetWifiState {
            clearPersistentGroups {
                removeCurrentGroup {
                    Handler(Looper.getMainLooper()).postDelayed({
                        createWifiDirectGroup()
                    }, 1000)
                }
            }
        }
    }

    private fun resetWifiState(onComplete: () -> Unit) {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = false
                Handler(Looper.getMainLooper()).postDelayed({
                    wifiManager.isWifiEnabled = true
                    Log.d(TAG, "📶 Wi-Fi 리셋 완료")
                    onComplete()
                }, 1500)
            } else {
                wifiManager.isWifiEnabled = true
                Handler(Looper.getMainLooper()).postDelayed(onComplete, 1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting WiFi state", e)
            onComplete()
        }
    }

    private fun clearPersistentGroups(onComplete: () -> Unit) {
        try {
            val method = wifiManager.javaClass.getMethod(
                "deletePersistentGroup",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java
            )
            var pending = 32
            for (i in 0 until 32) {
                method.invoke(wifiManager, channel, i, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "🧹 Persistent group $i deleted")
                        if (--pending == 0) onComplete()
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "⚠️ Failed to delete group $i: $reason")
                        if (--pending == 0) onComplete()
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Persistent group 제거 실패", e)
            onComplete()
        }
    }

    private fun removeCurrentGroup(onComplete: () -> Unit) {
        try {
            wifiManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "✅ 기존 그룹 제거됨")
                    onComplete()
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "⚠️ 기존 그룹 제거 실패: $reason → 그래도 진행")
                    onComplete()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error removing current group", e)
            onComplete()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private fun createWifiDirectGroup() {
        try {
            wifiManager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "✅ Wi-Fi Direct 그룹 생성됨")
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ Wi-Fi Direct 그룹 생성 실패: $reason")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error creating WiFi Direct group", e)
        }
    }

    private val wifiDirectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val group = intent.getParcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                        if (group != null && group.isGroupOwner) {
                            val ssid = group.networkName
                            val pass = group.passphrase
                            Log.d(TAG, "📡 Wi-Fi Direct 그룹 정보 - SSID: $ssid, PASSWORD: $pass")

                            // 사용자에게 알림
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Wi-Fi Direct 그룹 생성됨\nSSID: $ssid",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtraCompatibility(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        Log.d(TAG, "Wi-Fi P2P 상태 변경: $state")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in wifiDirectReceiver", e)
            }
        }
    }

    private val localBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    "PROXY_STARTED" -> {
                        val port = intent.getIntExtraCompatibility("port", 1081)
                        Log.d(TAG, "📥 PROXY_STARTED received, port=$port")
                        updateProxyStatus(true, port)
                    }
                    "PROXY_STOPPED" -> {
                        Log.d(TAG, "📥 PROXY_STOPPED received")
                        updateProxyStatus(false, 0)
                    }
                    "STREAM_STARTED" -> {
                        Log.d(TAG, "📥 STREAM_STARTED received")
                        updateStreamStatus(true)
                    }
                    "STREAM_STOPPED" -> {
                        Log.d(TAG, "📥 STREAM_STOPPED received")
                        updateStreamStatus(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in localBroadcastReceiver", e)
            }
        }
    }

    private fun Intent.getIntExtraCompatibility(key: String, defaultValue: Int): Int {
        return try {
            getIntExtra(key, defaultValue)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get int extra for key: $key", e)
            defaultValue
        }
    }

    private fun initializeProxy() {
        try {
            wifiDirectRTMPProxy = WiFiDirectRTMPProxy(this)
            streamManager = RTMPStreamManager()
            Log.d(TAG, "WiFiDirectRTMPProxy 초기화 완료")

        } catch (e: Exception) {
            Log.e(TAG, "프록시 초기화 실패", e)
            Toast.makeText(this, "프록시 초기화에 실패했습니다", Toast.LENGTH_LONG).show()
        }
    }

    private fun startStreaming() {
        try {
            if (!::streamManager.isInitialized) {
                Log.e(TAG, "StreamManager not initialized")
                Toast.makeText(this, "스트림 매니저가 초기화되지 않았습니다", Toast.LENGTH_SHORT).show()
                return
            }

            if (streamManager.isCurrentlyStreaming()) {
                Log.w(TAG, "⚠️ 이미 스트리밍 중입니다")
                Toast.makeText(this, "이미 스트리밍 중입니다", Toast.LENGTH_SHORT).show()
                return
            }

            val selectedIndex = binding.spinnerVideo.selectedItemPosition
            val selectedResId = videoList.getOrNull(selectedIndex)?.second ?: 0

            if (selectedResId != 0) {
                val cachedFile = copyRawToInternalStorage(this, selectedResId, "${videoList[selectedIndex].first}.mp4")
                streamManager.startStream(this, cachedFile)
                updateStreamStatus(true)
                Toast.makeText(this, "스트리밍을 시작합니다", Toast.LENGTH_SHORT).show()
            } else {
                // 선택 안 됨 혹은 기본값
                Toast.makeText(this, "비디오를 선택해주세요", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "스트리밍 시작 실패", e)
            updateStreamStatus(false)
            Toast.makeText(this, "스트리밍 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun copyRawToInternalStorage(context: Context, rawId: Int, fileName: String): File {
        val outFile = File(context.filesDir, fileName)

        // 이미 파일이 존재하면 삭제
        if (outFile.exists()) {
            outFile.delete()
        }

        try {
            val inputStream = context.resources.openRawResource(rawId)
            val outputStream = FileOutputStream(outFile)

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            println("✅ Raw 파일 복사 완료: ${outFile.absolutePath}")
            println("📁 파일 크기: ${outFile.length()} bytes")
            println("📁 파일 존재: ${outFile.exists()}")

            return outFile
        } catch (e: Exception) {
            println("❌ Raw 파일 복사 실패: ${e.message}")
            throw e
        }
    }

    private fun stopStreaming() {
        try {
            if (::streamManager.isInitialized) {
                Log.d(TAG, "🛑 스트리밍 중지...")
                streamManager.stopStreamForcefully()
                updateStreamStatus(false)
                Toast.makeText(this, "스트리밍이 중지되었습니다", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "스트리밍 중지 실패", e)
            Toast.makeText(this, "스트리밍 중지 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkStatus() {
        try {
            if (::streamManager.isInitialized) {
                val status = streamManager.getStreamingStatus()
                Log.d(TAG, "📊 현재 스트리밍 상태: $status")
                Toast.makeText(this, "스트리밍 상태: $status", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "상태 확인 실패", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            // 실행 중인 서비스들 정리
            if (isProxyRunning && ::wifiDirectRTMPProxy.isInitialized) {
                wifiDirectRTMPProxy.stopProxy()
                Log.d(TAG, "WiFiDirectRTMPProxy stopped")
            }

            if (isStreamRunning && ::streamManager.isInitialized) {
                streamManager.stopStreamForcefully()
                Log.d(TAG, "StreamManager stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping services", e)
        }

        try {
            // 브로드캐스트 리시버 해제
            unregisterReceiver(wifiDirectReceiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(localBroadcastReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }
    }
}