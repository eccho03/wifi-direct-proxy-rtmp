package com.example.wifidirectproxysocks

import android.util.Log
import java.net.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

const val TAG = "com.example.socksproxyserver.SocksProxy"

val executorService: ExecutorService = Executors.newCachedThreadPool()
var useSystemProxy = false

class SocksProxy(private val port: Int = 1081) : Runnable {

    var messageListener: ((String) -> Unit)? = null
    var started = false
    private var serverSocket: ServerSocket? = null

    override fun run() {
        started = true
        try {
            serverSocket = ServerSocket(port)
            sendMsg("SOCKS5 proxy is listening on port: $port")
        } catch (e: Exception) {
            Log.w(TAG, e)
            started = false
            sendMsg("failed to open SOCKS5 proxy on port: $port")
            return
        }

        try {
            while (true) {
                val socket = serverSocket!!.accept()
                executorService.submit(SocksRequestHandler(socket))
            }
        } catch (e: Exception) {
            Log.w(TAG, e)
        } finally {
            serverSocket?.close()
            serverSocket = null
            started = false
            sendMsg("SOCKS5 proxy is stopped")
        }
    }

    private fun sendMsg(msg: String) {
        Log.d(TAG, msg)
        messageListener?.invoke(msg)
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
    }

    companion object {
        fun start(port: Int): SocksProxy {
            val socksProxy = SocksProxy(port)
            executorService.submit(socksProxy)
            return socksProxy
        }
    }
}