package com.example.wifidirectproxysocks

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

fun startTestRTMPStream() {
    val command = listOf(
        "-f", "lavfi",
        "-i", "testsrc=size=1280x720:rate=30",
        "-vcodec", "libx264",
        "-preset", "ultrafast",
        "-tune", "zerolatency",
        "-pix_fmt", "yuv420p",
        "-f", "flv",
        "rtmp://a.rtmp.youtube.com/live2/eh8y-tw7s-g62s-zt2x-1hmt"
    ).joinToString(" ")

    FFmpegKit.executeAsync(command) { session ->
        val returnCode = session.returnCode
        if (ReturnCode.isSuccess(returnCode)) {
            println("✅ RTMP 스트리밍 성공")
        } else {
            println("❌ FFmpeg 오류: ${session.failStackTrace}")
        }
    }
}
