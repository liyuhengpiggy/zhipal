package cn.seniorvoice.demo

/** Decoded audio transfer metadata only; no Base64 overhead. firstBytesHex is RIFF metadata, never speech. */
data class BleReceiveProgress(
    val receivedBytes: Long,
    val totalBytes: Long?,
    val elapsedMs: Long,
    val firstBytesHex: String,
    val complete: Boolean
)
