package com.example.wifidirectproxysocks

import android.view.View
import androidx.appcompat.app.AppCompatActivity


class LiveActivity : AppCompatActivity() {
    private lateinit var nodePublisher: NodePublisher
    private lateinit var cameraView: NodeCameraView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)

        cameraView = findViewById(R.id.nodeCameraView)
        nodePublisher = NodePublisher(this, "demoPublisher", cameraView)
        nodePublisher.setOutputUrl("rtmp://live.example.com/live/streamkey")
        nodePublisher.setCameraPreview(0)
        nodePublisher.setVideoEnable(true)
        nodePublisher.setAudioEnable(true)
    }

    fun onStartPublish(view: View) {
        nodePublisher.start()
    }

    fun onStopPublish(view: View) {
        nodePublisher.stop()
    }
}
