package cn.seniorvoice.demo

import java.io.BufferedOutputStream
import java.io.File
import java.util.Base64

/** Receives an explicitly framed JSON/base64 audio transfer over BLE notifications. */
class BleAudioTransfer(private val cacheDirectory: File) {
    private var transferId: String? = null
    private var format: String? = null
    private var expectedSize = 0
    private var outputSize = 0
    private var receivedChunks = 0
    private var receivedBytes = 0
    private var file: File? = null
    private var output: BufferedOutputStream? = null
    private val firstBytes = ByteArray(8)
    private var firstBytesSize = 0
    val isReceiving: Boolean get() = transferId != null

    /** Counts decoded payload bytes; Base64/JSON overhead and a generated WAV header are excluded. */
    fun progress(elapsedMs: Long): BleReceiveProgress {
        check(isReceiving) { "没有活动录音" }
        val riff = firstBytesSize == 8 && firstBytes.take(4).toByteArray().contentEquals("RIFF".toByteArray(Charsets.US_ASCII))
        val header = if (riff) firstBytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) } else ""
        return BleReceiveProgress(receivedBytes.toLong(), expectedSize.toLong(), elapsedMs.coerceAtLeast(0), header, false)
    }

    fun start(event: DeviceEvent) {
        require(event.type == "audio_start")
        require(transferId == null) { "已有录音正在传输" }
        val newFormat = event.data.optString("format").lowercase()
        require(newFormat in setOf("wav", "pcm_s16le")) { "手机离线识别仅支持 PCM WAV 或带参数的 pcm_s16le，收到：$newFormat" }
        val size = event.data.optInt("size", -1)
        val header = if (newFormat == "pcm_s16le") pcmHeader(event, size) else byteArrayOf()
        require(size in 1..(MAX_AUDIO_BYTES - header.size)) { "录音大小无效或超过 10 MB" }
        val temporary = File.createTempFile("ble-audio-", ".wav", cacheDirectory)
        val stream = try { temporary.outputStream().buffered() }
        catch (error: Exception) { temporary.delete(); throw error }
        try { stream.write(header) } catch (error: Exception) { stream.close(); temporary.delete(); throw error }
        transferId = event.id
        format = newFormat
        expectedSize = size
        outputSize = size + header.size
        receivedChunks = 0
        receivedBytes = 0
        firstBytesSize = minOf(header.size, firstBytes.size)
        header.copyInto(firstBytes, 0, 0, firstBytesSize)
        file = temporary
        output = stream
    }

    fun append(event: DeviceEvent) {
        require(event.type == "audio_chunk" && event.id == transferId) { "录音分包 ID 不匹配" }
        val index = event.data.optInt("index", -1)
        require(index == receivedChunks) { "录音分包缺失或顺序错误，期待第 $receivedChunks 包" }
        val encoded = event.data.optString("base64")
        require(encoded.isNotBlank() && encoded.length <= MAX_ENCODED_CHUNK) { "录音分包内容无效" }
        val chunk = try {
            val clean = if (encoded.any { it.isWhitespace() }) encoded.filterNot { it.isWhitespace() } else encoded
            Base64.getDecoder().decode(clean)
        }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("录音分包不是有效 Base64") }
        require(chunk.isNotEmpty() && receivedBytes + chunk.size <= expectedSize) { "录音数据超过声明大小" }
        checkNotNull(output) { "录音文件尚未打开" }.write(chunk)
        if (firstBytesSize < firstBytes.size) {
            val count = minOf(firstBytes.size - firstBytesSize, chunk.size)
            chunk.copyInto(firstBytes, firstBytesSize, 0, count)
            firstBytesSize += count
        }
        receivedBytes += chunk.size
        receivedChunks++
    }

    fun finish(event: DeviceEvent): File {
        require(event.type == "audio_end" && event.id == transferId) { "录音结束标记 ID 不匹配" }
        require(receivedChunks == event.data.optInt("chunks", -1)) { "录音分包数量不完整" }
        require(receivedBytes == expectedSize) { "录音文件大小不匹配：收到 $receivedBytes / $expectedSize 字节" }
        checkNotNull(format) { "没有录音格式" }
        checkNotNull(transferId) { "没有活动录音" }
        output?.close()
        output = null
        val completed = checkNotNull(file) { "没有录音文件" }
        check(completed.length() == outputSize.toLong()) { "录音文件写入不完整" }
        file = null
        clear()
        return completed
    }

    fun abort() { clear() }

    private fun clear() {
        runCatching { output?.close() }
        output = null
        file?.delete()
        file = null
        transferId = null
        format = null
        expectedSize = 0
        outputSize = 0
        receivedChunks = 0
        receivedBytes = 0
        firstBytes.fill(0)
        firstBytesSize = 0
    }

    companion object {
        private fun pcmHeader(event: DeviceEvent, size: Int): ByteArray {
            val rate = event.data.optInt("sample_rate", -1)
            val channels = event.data.optInt("channels", -1)
            require(rate in 8000..48000 && channels in 1..2 && event.data.optInt("bits_per_sample", -1) == 16) {
                "PCM 必须明确 sample_rate(8000～48000)、channels(1/2)、bits_per_sample:16"
            }
            require(size > 0 && size % (channels * 2) == 0 && size.toLong() <= rate.toLong() * channels * 2 * 180) { "PCM 长度未对齐或超过 180 秒" }
            return java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(size + 36)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1)
                putShort(channels.toShort()); putInt(rate); putInt(rate * channels * 2)
                putShort((channels * 2).toShort()); putShort(16)
                put("data".toByteArray(Charsets.US_ASCII)); putInt(size)
            }.array()
        }
        const val MAX_AUDIO_BYTES = 10 * 1024 * 1024
        const val MAX_ENCODED_CHUNK = 3 * 1024 * 1024
    }
}
