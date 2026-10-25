package cn.seniorvoice.demo

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Runs on the audio storage worker. Recognition keeps using the original received file. */
object AudioRecordingFiles {
    fun retain(source: File, directory: File): File {
        // A broken new recording must not replace the last valid recording offered for retry.
        WavPcmReader.inspect(source)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建录音保存目录" }
        val retained = File(directory, "recording.wav")
        val staging = File.createTempFile("recording-", ".wav", directory)
        try {
            source.copyTo(staging, overwrite = true)
            try {
                Files.move(staging.toPath(), retained.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), retained.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return retained
        } finally { staging.delete() }
    }
}
