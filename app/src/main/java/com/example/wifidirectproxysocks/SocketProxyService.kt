package com.example.wifidirectproxysocks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

// Service 클래스도 업데이트
class SocksProxyService : Service() {

    private var port: Int = 1081
    private var socksProxy: SocksProxy? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        val notification = createForegroundNotification()
        startForeground(1, notification)
    }

    private fun createForegroundNotification(): Notification {
        val channelId = "$packageName.channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SOCKS5 proxy notification channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("SOCKS5 proxy server")
            .setContentText("Running...")
//            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSocksProxy(intent)
        return START_NOT_STICKY
    }

    private fun startSocksProxy(intent: Intent?) {
        val port = intent?.getIntExtra("port", 1081) ?: 1081
        if (socksProxy != null) {
            if (port != this.port) {
                socksProxy?.stop()
            } else if (socksProxy!!.started) {
                return
            }
        }
        this.port = port
        socksProxy = SocksProxy.Companion.start(port)
        socksProxy!!.messageListener = {
            handler.post {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
        }

        // 브로드캐스트 전송
        Log.d("SocksProxyService", "📢 Sending SERVER_STARTED with port = $port")
        val intent = Intent("SERVER_STARTED").apply {
            putExtra("port", port)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        socksProxy?.stop()
        socksProxy = null

        // 서버 종료 알림 브로드캐스트
        val intent = Intent("SERVER_STOPPED").apply {
            putExtra("port", port)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    companion object {
        fun newStartIntent(context: Context, port: Int): Intent {
            val intent = Intent(context, SocksProxyService::class.java)
            intent.putExtra("port", port)
            return intent
        }

        fun newStopIntent(context: Context): Intent {
            return Intent(context, SocksProxyService::class.java)
        }
    }
}