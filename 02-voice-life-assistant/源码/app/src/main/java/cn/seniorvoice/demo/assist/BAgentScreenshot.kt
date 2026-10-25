package cn.seniorvoice.demo.assist

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Original display coordinates must match accessibility bounds; do not scale this image. */
object BAgentScreenshot {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun capture(service: AccessibilityService, allowed: () -> Boolean): Bitmap =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT < 30 || !allowed()) {
                continuation.resumeWithException(IllegalStateException("当前不可截图，请解锁并开启本次 B 观察"))
            } else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        var hardware: Bitmap? = null
                        var image: Bitmap? = null
                        try {
                            if (!continuation.isActive || !allowed()) {
                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("B 已取消"))
                                return
                            }
                            hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("截图读取失败")
                            image = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("截图转换失败")
                            val owned = image
                            image = null
                            continuation.resume(owned) { owned.recycle() }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        } finally { image?.recycle(); hardware?.recycle(); buffer.close() }
                    }
                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("系统截图失败：$errorCode"))
                    }
                })
        }
}
