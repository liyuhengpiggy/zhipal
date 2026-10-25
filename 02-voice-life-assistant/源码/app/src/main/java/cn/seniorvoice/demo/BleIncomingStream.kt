package cn.seniorvoice.demo

import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Demultiplexes firmware v1 text frames, WAV and legacy JSON on an ordered BLE stream. */
class BleIncomingStream(
    private val cacheDirectory: File,
    onJson: (String) -> Unit,
    private val onWav: (File) -> Unit,
    private val onError: (String) -> Unit,
    private val onWavStarted: () -> Unit = {},
    onTextFrame: (BleTextFrame) -> Unit = {},
    private val onWavProgress: (receivedBytes: Long, totalBytes: Long, complete: Boolean) -> Unit = { _, _, _ -> }
) {
    private val json = BleJsonFramer(cacheDirectory, onJson, onError)
    private val textFrames = BleTextFrameDecoder({ unknownReported = false; onTextFrame(it) }, onError)
    private val header = ByteArray(12)
    private var headerSize = 0
    private var expectedSize = 0L
    private var receivedSize = 0L
    private var file: File? = null
    private var output: BufferedOutputStream? = null
    private var unknownReported = false
    /** Available as soon as a valid RIFF/WAVE header has been received; contains no audio samples. */
    var wavFirstBytesHex: String = ""
        private set
    val hasPendingFrame: Boolean get() = headerSize > 0 || file != null || json.hasPendingFrame || textFrames.hasPendingFrame

    fun accept(packet: ByteArray) {
        var offset = 0
        while (offset < packet.size) {
            val stream = output
            if (stream != null) {
                val count = minOf(packet.size - offset, (expectedSize - receivedSize).toInt())
                try {
                    stream.write(packet, offset, count)
                    receivedSize += count
                    offset += count
                    if (receivedSize == expectedSize) finishWav()
                    else onWavProgress(receivedSize, expectedSize, false)
                } catch (error: Exception) {
                    reset()
                    onError("WAV 接收失败：${error.message ?: "无法写入临时文件"}")
                    return
                }
                continue
            }
            val byte = packet[offset++]
            if (json.hasPendingFrame) {
                json.acceptByte(byte)
                continue
            }
            if (textFrames.hasPendingFrame) {
                textFrames.acceptByte(byte)
                continue
            }
            if (headerSize > 0) {
                val required = when (headerSize) {
                    1 -> 'I'; 2, 3 -> 'F'; 8 -> 'W'; 9 -> 'A'; 10 -> 'V'; 11 -> 'E'
                    else -> null
                }
                if (required != null && byte != required.code.toByte()) {
                    headerSize = 0
                    unknown()
                    if (byte == 'R'.code.toByte()) header[headerSize++] = byte
                    if (byte == 0xa5.toByte()) textFrames.acceptByte(byte)
                    continue
                }
                header[headerSize++] = byte
                if (headerSize == header.size) startWav()
                continue
            }
            when (byte.toInt() and 0xff) {
                9, 10, 13, 32 -> Unit
                '{'.code -> { unknownReported = false; json.acceptByte(byte) }
                'R'.code -> header[headerSize++] = byte
                0xa5 -> textFrames.acceptByte(byte)
                else -> unknown()
            }
        }
    }

    private fun unknown() {
        if (!unknownReported) {
            unknownReported = true
            onError("未识别到消息头：A 文字请发送 A5 5A v1 完整帧，录音请从 RIFF…WAVE 开始发送")
        }
    }

    private fun startWav() {
        expectedSize = (4..7).foldIndexed(0L) { index, value, i ->
            value or ((header[i].toLong() and 0xff) shl (index * 8))
        } + 8L
        if (expectedSize !in 44L..BleAudioTransfer.MAX_AUDIO_BYTES.toLong()) {
            reset()
            onError("WAV 文件头中的长度无效或超过 10 MiB；请在录音结束后写入实际文件长度再发送")
            return
        }
        try {
            file = File.createTempFile("ble-raw-wav-", ".wav", cacheDirectory)
            output = file!!.outputStream().buffered()
            output!!.write(header)
            receivedSize = header.size.toLong()
            wavFirstBytesHex = header.take(8).joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
            headerSize = 0
            unknownReported = false
            onWavStarted()
            onWavProgress(receivedSize, expectedSize, false)
        } catch (error: Exception) {
            reset()
            onError("无法保存 WAV 录音：${error.message}")
        }
    }

    private fun finishWav() {
        output?.close()
        output = null
        val completed = checkNotNull(file)
        require(completed.length() == expectedSize) { "WAV 文件长度不一致" }
        validateWav(completed)
        // Completion means both the RIFF file length and its internal chunks were validated.
        onWavProgress(receivedSize, expectedSize, true)
        file = null
        expectedSize = 0
        receivedSize = 0
        onWav(completed)
    }

    private fun validateWav(completed: File) {
        RandomAccessFile(completed, "r").use { input ->
            var position = 12L
            var hasFormat = false
            var hasAudio = false
            val chunkHeader = ByteArray(8)
            while (position + 8 <= input.length()) {
                input.seek(position)
                input.readFully(chunkHeader)
                val name = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val length = (4..7).foldIndexed(0L) { index, value, i ->
                    value or ((chunkHeader[i].toLong() and 0xff) shl (index * 8))
                }
                val end = position + 8 + length
                require(end <= input.length()) { "WAV 内部数据长度错误，可能缺包" }
                if (name == "fmt ") { require(length >= 16) { "WAV 格式信息不完整" }; hasFormat = true }
                if (name == "data" && length > 0) hasAudio = true
                position = end + (length and 1)
            }
            require(position == input.length()) { "WAV 数据块或填充字节不完整" }
            require(hasFormat && hasAudio) { "WAV 缺少格式信息或录音内容" }
        }
    }

    fun reset() {
        runCatching { output?.close() }
        output = null
        file?.delete()
        file = null
        expectedSize = 0
        receivedSize = 0
        headerSize = 0
        unknownReported = false
        wavFirstBytesHex = ""
        json.reset()
        textFrames.reset()
    }
}
