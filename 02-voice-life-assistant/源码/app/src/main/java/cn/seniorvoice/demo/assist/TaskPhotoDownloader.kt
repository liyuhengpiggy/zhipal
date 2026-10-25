package cn.seniorvoice.demo.assist

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException

/** Downloads the current hardware frame only from explicitly configured photo servers. */
class TaskPhotoDownloader {
    data class Source(val root: URI, val token: String = "")
    private val lock = Any()
    private var epoch = 0L
    private var active: HttpURLConnection? = null
    fun ticket(): Long = synchronized(lock) { epoch }
    fun cancel() {
        val old = synchronized(lock) { epoch++; active.also { active = null } }
        old?.disconnect()
    }
    private fun check(ticket: Long) { synchronized(lock) { if (ticket != epoch) throw CancellationException("本次照片已取消") } }
    fun download(location: String, sources: List<Source>, ticket: Long): ByteArray {
        check(ticket)
        require(sources.isNotEmpty() && location.length in 1..4096) { "需要本次照片的有效地址" }
        val uri = sources.first().root.resolve(location)
        val source = sources.firstOrNull { it.root.scheme == uri.scheme && it.root.host == uri.host && it.root.port == uri.port }
            ?: error("照片地址不属于已配置的服务器")
        require(uri.userInfo == null && uri.fragment == null && uri.normalize().path == uri.path &&
            uri.path.split('/').none { it.equals("latest", true) }) { "需要本次任务专属照片地址，不能读取最近照片缓存" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        synchronized(lock) { check(ticket); check(active == null) { "照片下载不能交错" }; active = connection }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            require(source.token.none { it.isISOControl() }) { "照片服务令牌无效" }
            if (source.token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${source.token}")
            val code = connection.responseCode
            IntegrationLog.event("MEDICINE_FRAME", "download", code = code, url = uri.toString())
            require(code in 200..299) { "本次照片下载失败 HTTP $code" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    check(ticket)
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 8 * 1024 * 1024) { "本次照片超过 8 MiB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(ticket)
            require(bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
                bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) { "本次照片 JPEG 不完整，请重新拍摄" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "本次照片不是有效 JPEG，或超过 1600 万像素" }
            return bytes
        } finally {
            synchronized(lock) { if (active === connection) active = null }
            connection.disconnect()
        }
    }
}
