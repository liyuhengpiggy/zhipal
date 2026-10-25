package cn.seniorvoice.demo

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Bounded WAV parser and streaming PCM -> 16 kHz mono conversion, independent of Android/native ASR. */
object WavPcmReader {
    data class Info(val sampleRate: Int, val channels: Int, val bits: Int, val dataOffset: Long, val dataBytes: Long) {
        val frameBytes get() = channels * bits / 8
        val frames get() = dataBytes / frameBytes
        val seconds get() = frames.toDouble() / sampleRate
        val description get() = "$sampleRate Hz / $bits bit / ${channels}声道 / %.1f 秒".format(seconds)
    }

    fun inspect(file: File): Info = RandomAccessFile(file, "r").use { input ->
        require(input.length() in 44..10L * 1024 * 1024) { "WAV 为空、过短或超过 10 MiB" }
        fun tag(): String { val bytes = ByteArray(4); input.readFully(bytes); return bytes.toString(Charsets.US_ASCII) }
        fun u16() = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        fun u32(): Long = (input.readInt().let { Integer.reverseBytes(it) }).toLong() and 0xffffffffL
        require(tag() == "RIFF") { "录音不是 RIFF WAV；不能只改文件后缀" }
        require(u32() + 8 == input.length() && tag() == "WAVE") { "WAV 文件头长度与实际文件不一致" }
        var rate = 0; var channels = 0; var bits = 0
        var offset = -1L; var size = 0L
        while (input.filePointer + 8 <= input.length()) {
            val name = tag(); val length = u32(); val start = input.filePointer
            require(length <= input.length() - start) { "WAV $name 数据被截断" }
            when (name) {
                "fmt " -> {
                    require(rate == 0 && length >= 16) { "WAV fmt 块重复或不完整" }
                    val encoding = u16(); channels = u16(); rate = u32().toInt()
                    val byteRate = u32(); val blockAlign = u16(); bits = u16()
                    require(encoding == 1) { "暂只支持整数 PCM WAV；当前编码 $encoding，请硬件输出 PCM 16 bit WAV" }
                    require(channels in 1..2 && rate in 8000..48000 && bits in setOf(8, 16, 24, 32)) { "WAV 参数不支持：$rate Hz / $bits bit / $channels 声道" }
                    require(blockAlign == channels * bits / 8 && byteRate == rate.toLong() * blockAlign) { "WAV 采样参数与块对齐不一致" }
                }
                "data" -> {
                    require(offset < 0) { "WAV 含多个 data 块，请硬件输出一个完整录音块" }
                    offset = start; size = length
                }
            }
            val end = start + length + (length and 1)
            require(end <= input.length()) { "WAV 块缺少对齐字节" }
            input.seek(end)
        }
        require(input.filePointer == input.length()) { "WAV 文件尾不完整" }
        require(rate > 0 && offset >= 0 && size > 0 && size % (channels * bits / 8) == 0L) { "WAV 缺少格式、有效声音或完整采样帧" }
        Info(rate, channels, bits, offset, size).also {
            require(it.seconds <= 180) { "离线指令录音最长 180 秒；建议每条指令录制 2～8 秒" }
        }
    }

    fun stream16k(file: File, info: Info = inspect(file), onProgress: (Int) -> Unit = {}, onPcm: (ByteArray, Int) -> Unit) {
        file.inputStream().buffered().use { input ->
            var skip = info.dataOffset
            while (skip > 0) { val count = input.skip(skip); require(count > 0) { "无法读取 WAV 声音区域" }; skip -= count }
            onProgress(0)
            // When hardware already sends the recognition format, preserve every sample and avoid
            // per-sample reads/interpolation; bound reads to dataBytes so later RIFF metadata stays out.
            if (info.sampleRate == 16000 && info.channels == 1 && info.bits == 16) {
                val pcm = ByteArray(4096)
                var remaining = info.dataBytes
                while (remaining > 0) {
                    check(!Thread.currentThread().isInterrupted) { "录音识别已取消" }
                    val count = minOf(remaining, pcm.size.toLong()).toInt()
                    readFully(input, pcm, count)
                    onPcm(pcm, count)
                    remaining -= count
                    if (remaining > 0) onProgress(((info.dataBytes - remaining) * 100 / info.dataBytes).toInt())
                }
                onProgress(100)
                return
            }
            val frames = ByteArray(8192 / info.frameBytes * info.frameBytes)
            val output = ByteArray(4096)
            var used = 0; var emitted = 0L; var previous = 0
            var index = 0L; var remaining = info.dataBytes
            val width = info.bits / 8
            val targetSamples = (info.frames * 16000 + info.sampleRate - 1) / info.sampleRate
            fun emit(sample: Int) {
                val value = sample.coerceIn(-32768, 32767)
                output[used++] = value.toByte(); output[used++] = (value shr 8).toByte(); emitted++
                if (used == output.size) {
                    check(!Thread.currentThread().isInterrupted) { "录音识别已取消" }
                    onPcm(output, used); used = 0
                }
            }
            while (remaining > 0) {
                check(!Thread.currentThread().isInterrupted) { "录音识别已取消" }
                val count = minOf(remaining, frames.size.toLong()).toInt()
                readFully(input, frames, count)
                for (frameStart in 0 until count step info.frameBytes) {
                    var sum = 0
                    for (channel in 0 until info.channels) {
                        val start = frameStart + channel * width
                        val value = when (info.bits) {
                            8 -> ((frames[start].toInt() and 255) - 128) shl 8
                            16 -> (frames[start].toInt() and 255) or (frames[start + 1].toInt() shl 8)
                            24 -> (frames[start + 1].toInt() and 255) or (frames[start + 2].toInt() shl 8)
                            else -> (frames[start + 2].toInt() and 255) or (frames[start + 3].toInt() shl 8)
                        }
                        sum += value
                    }
                    val current = sum / info.channels
                    if (index == 0L) previous = current
                    while (emitted < targetSamples && emitted * info.sampleRate <= index * 16000) {
                        val fraction = if (index == 0L) 1.0 else (emitted * info.sampleRate - (index - 1) * 16000).toDouble() / 16000
                        emit((previous + (current - previous) * fraction).roundToInt())
                    }
                    previous = current
                    index++
                }
                remaining -= count
                if (remaining > 0) onProgress(((info.dataBytes - remaining) * 100 / info.dataBytes).toInt())
            }
            while (emitted < targetSamples) emit(previous)
            if (used > 0) onPcm(output, used)
            onProgress(100)
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, count: Int) {
        var read = 0
        while (read < count) {
            val received = input.read(buffer, read, count - read)
            require(received > 0) { "WAV 声音区域读取不完整" }
            read += received
        }
    }
}
