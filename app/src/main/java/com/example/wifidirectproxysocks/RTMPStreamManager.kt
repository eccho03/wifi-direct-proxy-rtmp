package com.example.wifidirectproxysocks

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader


class RTMPStreamManager {
    private val currentSession = AtomicReference<FFmpegSession?>(null)
    private var isStreaming = false

    fun startStream(context: Context) {
        // 0. 스트림키 받아오기
        val STREAM_KEY = BuildConfig.STREAMING_KEY

        // 1. 기존 스트리밍 강제 종료
        stopStreamForcefully()

        // 2. 잠시 대기 (YouTube 서버가 이전 연결을 정리할 시간)
        Thread.sleep(3000)

        val inputFile = File(context.filesDir, "paris.mp4")
        if (!inputFile.exists()) {
            try {
                copyRawToInternalStorage(context, R.raw.paris, "paris.mp4")
            }
            catch (e: Exception) {
                println("❌ 파일 복사 실패: ${e.message}")
                createAndStreamTestVideo(context)
            }
        }

        println("🚀 새로운 RTMP 스트리밍 시작...")
        println("📁 파일: ${inputFile.absolutePath} (${inputFile.length()} bytes)")

        // 3. 더 안정적인 RTMP 설정
        val command = listOf(
            "-re",
            "-i", inputFile.absolutePath,
            "-vcodec", "libx264",
            "-preset", "ultrafast",
            "-pix_fmt", "yuv420p",
            "-f", "flv",
            "rtmp://a.rtmp.youtube.com/live2/${STREAM_KEY}"
        ).joinToString(" ")

        println("🎬 RTMP 명령어: $command")

        // 4. 로그 콜백 설정
        var frameCount = 0
        var lastLogTime = System.currentTimeMillis()

        FFmpegKitConfig.enableLogCallback { log ->
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastLogTime > 5000) { // 5초마다만 로그 출력
                println("📋 FFmpeg: ${log.message}")
                lastLogTime = currentTime
            }

            // 연결 관련 중요 로그는 항상 출력
            val message = log.message
            if (message.contains("Connection") ||
                message.contains("error") ||
                message.contains("failed") ||
                message.contains("refused") ||
                message.contains("timeout")) {
                println("⚠️ 중요: $message")
            }
        }

        FFmpegKitConfig.enableStatisticsCallback { stats ->
            frameCount++
            if (frameCount % 150 == 0) { // 5초마다 (30fps 기준)
                println("📊 스트리밍 상태: frame=${stats.videoFrameNumber}, fps=${stats.videoFps}, bitrate=${stats.bitrate}kbps, time=${stats.time}")
            }
        }

        // 5. 비동기 실행
        val session = FFmpegKit.executeAsync(command) { completedSession ->
            currentSession.set(null)
            isStreaming = false

            val returnCode = completedSession.returnCode
            val duration = completedSession.duration

            println("🔚 스트리밍 종료:")
            println("  📊 실행 시간: ${duration}ms")
            println("  📊 리턴 코드: $returnCode")

            when {
                ReturnCode.isSuccess(returnCode) -> {
                    println("✅ 스트리밍이 정상적으로 완료되었습니다")
                }
                ReturnCode.isCancel(returnCode) -> {
                    println("🛑 스트리밍이 사용자에 의해 취소되었습니다")
                }
                else -> {
                    println("❌ 스트리밍 오류:")
                    analyzeStreamingError(completedSession.logsAsString)
                }
            }
        }

        currentSession.set(session)
        isStreaming = true

        println("✅ 스트리밍 세션 시작됨 (ID: ${session.sessionId})")

        // 6. 5초 후 연결 상태 확인
        Thread {
            Thread.sleep(5000)
            checkStreamingHealth()
        }.start()
    }

    fun stopStreamForcefully() {
        println("🛑 기존 스트리밍 강제 종료 중...")

        // 1. 현재 세션 취소
        currentSession.get()?.let { session ->
            try {
                session.cancel()
                FFmpegKit.cancel()
                println("🛑 세션 ${session.sessionId} 취소됨")
            } catch (e: Exception) {
                println("⚠️ 세션 취소 중 오류: ${e.message}")
            }
        }

        // 2. 모든 FFmpeg 세션 취소
        try {
            FFmpegKit.cancel()
            println("🛑 모든 FFmpeg 세션 취소됨")
        } catch (e: Exception) {
            println("⚠️ FFmpeg 취소 중 오류: ${e.message}")
        }

        currentSession.set(null)
        isStreaming = false

        println("✅ 스트리밍 정리 완료")
    }

    private fun checkStreamingHealth() {
        val session = currentSession.get()
        if (session != null && isStreaming) {
            println("💓 스트리밍 상태 체크:")
            println("  📊 세션 ID: ${session.sessionId}")
            println("  📊 세션 상태: ${session.state}")
            println("  📊 시작 시간: ${session.startTime}")

            // 로그에서 오류 찾기
            val recentLogs = session.logsAsString
            if (recentLogs.contains("error") || recentLogs.contains("failed")) {
                println("⚠️ 스트리밍 중 오류 감지됨")
                analyzeStreamingError(recentLogs)
            } else {
                println("✅ 스트리밍 정상 작동 중")
            }
        }
    }

    private fun analyzeStreamingError(logs: String) {
        println("🔍 스트리밍 오류 분석:")

        when {
            logs.contains("Connection refused") -> {
                println("  🌐 RTMP 서버 연결 거부")
                println("  💡 해결: 스트림 키 확인, 라이브 스트림이 활성화되어 있는지 확인")
            }
            logs.contains("403") || logs.contains("Forbidden") -> {
                println("  🔐 스트림 키 인증 실패")
                println("  💡 해결: YouTube Studio에서 새 스트림 키 생성")
            }
            logs.contains("already publishing") -> {
                println("  🔄 이미 스트리밍 중인 상태")
                println("  💡 해결: 기존 스트림 완전히 종료 후 재시도")
            }
            logs.contains("timeout") -> {
                println("  ⏰ 연결 타임아웃")
                println("  💡 해결: 네트워크 상태 확인, 방화벽 확인")
            }
            logs.contains("Broken pipe") -> {
                println("  🔌 연결이 예기치 않게 끊어짐")
                println("  💡 해결: 네트워크 안정성 확인")
            }
            else -> {
                println("  ❓ 알 수 없는 오류")
                println("  📋 최근 로그: ${logs.takeLast(500)}")
            }
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

    private fun createAndStreamTestVideo(context: Context) {
        println("🎬 테스트 비디오 생성 후 스트리밍...")
        val STREAM_KEY = BuildConfig.STREAMING_KEY

        // 간단한 테스트 패턴으로 직접 스트리밍
        val command = listOf(
            "-f", "lavfi",
            "-i", "testsrc=size=1920x1080:rate=30", // HD 테스트 패턴
            "-f", "lavfi",
            "-i", "sine=frequency=1000:sample_rate=44100", // 테스트 오디오
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-tune", "zerolatency",
            "-pix_fmt", "yuv420p",
            "-g", "30",
            "-b:v", "2500k",
            "-maxrate", "2500k",
            "-bufsize", "5000k",
            "-c:a", "aac",
            "-b:a", "128k",
            "-ar", "44100",
            "-ac", "2",
            "-f", "flv",
            "-rtmp_live", "live",
            "rtmp://a.rtmp.youtube.com/live2/$STREAM_KEY"
        ).joinToString(" ")

        println("🎬 테스트 스트림 명령어: $command")

        val session = FFmpegKit.executeAsync(command) { completedSession ->
            if (ReturnCode.isSuccess(completedSession.returnCode)) {
                println("✅ 테스트 스트리밍 성공!")
            } else {
                println("❌ 테스트 스트리밍 실패:")
                analyzeStreamingError(completedSession.logsAsString)
            }
        }

        currentSession.set(session)
        isStreaming = true
    }

    fun getStreamingStatus(): String {
        return if (isStreaming) {
            val session = currentSession.get()
            "🔴 스트리밍 중 (세션: ${session?.sessionId ?: "unknown"})"
        } else {
            "⚫ 스트리밍 중지됨"
        }
    }

    fun isCurrentlyStreaming(): Boolean = isStreaming
}