package cn.seniorvoice.demo.assist

import java.net.URI

/** One URL policy for newly integrated task clients. Legacy module addresses remain separately configurable. */
data class ServerConfig(val base: URI) {
    companion object {
        const val DEFAULT_BASE = "https://ai-glasses-backend-7bj3.onrender.com"
        fun parse(value: String, allowLanHttp: Boolean = false, debugBuild: Boolean = false): ServerConfig {
            val uri = OcrProtocol.endpoint(value, allowLanHttp, debugBuild)
            require(uri.path.trim('/') !in setOf("upload", "analyze", "vision/analyze")) { "请填写服务器根地址，不包含 /upload 或分析接口路径" }
            return ServerConfig(uri)
        }
    }
}
