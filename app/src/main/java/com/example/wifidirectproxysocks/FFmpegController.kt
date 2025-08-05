package com.example.wifidirectproxysocks

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

fun startTestRTMPStream(context: Context) {
    val inputFile = File(context.filesDir, "output_rotated_metadata.mp4")

    // 파일 존재 여부 확인
    if (!inputFile.exists()) {
        println("❌ 입력 파일이 존재하지 않습니다: ${inputFile.absolutePath}")

        // Raw 리소스에서 파일 복사 시도 (예시)
        try {
            // R.raw.output_rotated_metadata가 있다면
            copyRawToInternalStorage(context, R.raw.output_rotated_metadata, "output_rotated_metadata.mp4")

            // 또는 assets 폴더에서 복사
            // copyAssetToInternalStorage(context, "output_rotated_metadata.mp4", "output_rotated_metadata.mp4")
        } catch (e: Exception) {
            println("❌ 파일 복사 실패: ${e.message}")
            return
        }
    }

    // 파일 정보 출력
    println("📁 파일 경로: ${inputFile.absolutePath}")
    println("📁 파일 존재: ${inputFile.exists()}")
    println("📁 파일 크기: ${inputFile.length()} bytes")
    println("📁 읽기 가능: ${inputFile.canRead()}")

    val command = listOf(
        "-re", // 실시간처럼 입력을 읽음
        "-i", inputFile.absolutePath, // 절대 경로 사용
        "-c", "copy", // 인코딩 없이 그대로 전송
        "-f", "flv", // 출력 포맷
        "rtmp://a.rtmp.youtube.com/live2/eh8y-tw7s-g62s-zt2x-1hmt"
    ).joinToString(" ")

    println("🎬 FFmpeg 명령어: $command")

    FFmpegKit.executeAsync(command) { session ->
        val returnCode = session.returnCode
        val logs = session.logsAsString

        if (ReturnCode.isSuccess(returnCode)) {
            println("✅ RTMP 스트리밍 성공")
        } else {
            println("❌ FFmpeg 오류 코드: $returnCode")
            println("❌ FFmpeg 로그: $logs")
            println("❌ FFmpeg 스택트레이스: ${session.failStackTrace}")
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

// 테스트용 간단한 비디오 파일 생성
fun createTestVideoFile(context: Context): File {
    val testFile = File(context.filesDir, "test_video.mp4")

    // FFmpeg로 간단한 테스트 비디오 생성
    val command = listOf(
        "-f", "lavfi",
        "-i", "testsrc=size=640x480:rate=1:duration=10", // 10초짜리 테스트 비디오
        "-c:v", "libx264",
        "-preset", "ultrafast",
        "-pix_fmt", "yuv420p",
        "-y", // 덮어쓰기
        testFile.absolutePath
    ).joinToString(" ")

    println("🎬 테스트 비디오 생성: $command")

    FFmpegKit.execute(command)

    return testFile
}