package com.example.wifidirectproxysocks

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

fun startTestRTMPStream(context: Context) {
//    val command = listOf(
//        "-f", "lavfi",
//        "-i", "testsrc=size=1280x720:rate=30",
//        "-vcodec", "libx264",
//        "-preset", "ultrafast",
//        "-tune", "zerolatency",
//        "-pix_fmt", "yuv420p",
//        "-f", "flv",
//        "rtmp://a.rtmp.youtube.com/live2/eh8y-tw7s-g62s-zt2x-1hmt"
//    ).joinToString(" ")

    val inputFile = File(context.filesDir, "output_rotated_metadata.mp4").absolutePath

    val command = listOf(
        "-re", // 실시간처럼 입력을 읽음
        "-i", inputFile, // 입력 파일
        "-c", "copy", // 인코딩 없이 그대로 전송
        "-f", "flv", // 출력 포맷
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

fun copyRawToInternalStorage(context: Context, rawId: Int, fileName: String): File {
    val inputStream = context.resources.openRawResource(rawId)
    val outFile = File(context.filesDir, fileName)
    val outputStream = FileOutputStream(outFile)

    inputStream.copyTo(outputStream)
    inputStream.close()
    outputStream.close()

    println("✅ 복사 완료: ${outFile.absolutePath}, 존재 여부: ${outFile.exists()}")
    return outFile
}


//fun startTestRTMPStream() {
//    val inputPath = "/Users/Eunchae/input.mp4"
//    val outputPath = "rtmp://a.rtmp.youtube.com/live2/eh8y-tw7s-g62s-zt2x-1hmt"
//
//    // FFmpeg 실행 명령어 리스트
//    val commandList = listOf(
//        "ffmpeg", // FFmpeg 실행 파일 이름
//        "-re",
//        "-i", inputPath,
//        "-c", "copy",
//        "-f", "flv",
//        outputPath
//    )
//
//    // ProcessBuilder를 사용하여 명령어 실행
//    val processBuilder = ProcessBuilder(commandList)
//    processBuilder.redirectErrorStream(true) // 에러와 출력을 함께 읽기 위해 설정
//
//    try {
//        val process = processBuilder.start()
//
//        // 프로세스의 출력 스트림 읽기 (FFmpeg 로그 확인용)
//        val reader = BufferedReader(InputStreamReader(process.inputStream))
//        var line: String?
//        while (reader.readLine().also { line = it } != null) {
//            println(line)
//        }
//
//        // 프로세스 종료를 기다림
//        val exitCode = process.waitFor()
//        println("FFmpeg process finished with exit code $exitCode")
//
//    } catch (e: Exception) {
//        e.printStackTrace()
//    }
//}