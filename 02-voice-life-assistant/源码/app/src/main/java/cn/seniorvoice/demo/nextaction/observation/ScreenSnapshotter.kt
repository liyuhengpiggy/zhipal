package cn.seniorvoice.demo.nextaction.observation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import cn.seniorvoice.demo.nextaction.domain.ScreenImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Screen bytes exist only for the current request; no files, galleries or persistent logs. */
class ScreenSnapshotter(private val service: AccessibilityService) {
    fun capture(): ScreenImage {
        if (Build.VERSION.SDK_INT < 30) error("AI 截图需 Android 11 及以上；原有 WAV、电话和抖音功能仍可使用")
        val future = CompletableFuture<ScreenImage>()
        val expired = AtomicBoolean(false)
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                var hardware: Bitmap? = null
                var software: Bitmap? = null
                var scaled: Bitmap? = null
                try {
                    if (expired.get() || !NextActionRuntimeAccess.allowed(service)) return
                    hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("无法读取系统截图")
                    software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("截图转换失败")
                    val factor = minOf(1.0, 1280.0 / maxOf(software.width, software.height))
                    scaled = if (factor < 1) Bitmap.createScaledBitmap(software,
                        (software.width * factor).roundToInt().coerceAtLeast(1),
                        (software.height * factor).roundToInt().coerceAtLeast(1), true) else software
                    val output = ByteArrayOutputStream()
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 72, output)) { "JPEG 编码失败" }
                    val bytes = output.toByteArray()
                    check(bytes.size <= 1024 * 1024) { "当前截图超出 1 MiB 限制，本次不上传" }
                    if (!expired.get()) future.complete(ScreenImage(bytes, scaled.width, scaled.height))
                } catch (error: Exception) { future.completeExceptionally(error) }
                finally {
                    if (scaled !== software) scaled?.recycle()
                    software?.recycle(); hardware?.recycle(); buffer.close()
                }
            }
            override fun onFailure(errorCode: Int) {
                future.completeExceptionally(IllegalStateException("系统截图失败（$errorCode）；安全屏幕或设备限制不能绕过"))
            }
        })
        return try { future.get(3500, TimeUnit.MILLISECONDS) }
        finally { expired.set(true) }
    }
}

private object NextActionRuntimeAccess {
    fun allowed(service: AccessibilityService) = (service as? NextActionAccessibilityService)?.collectionAllowed() == true
}
