package cn.seniorvoice.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors

/** Reads BLE WAV files directly. No microphone capture, HTTP upload or server inference. */
class OfflineSpeechClient(context: Context) {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lifecycle = Any()
    @Volatile private var closed = false
    private var model: Model? = null // confined to worker; native close must not race an active recognizer
    private val generation = java.util.concurrent.atomic.AtomicLong()

    fun cancel() { generation.incrementAndGet() }

    /** Start model preparation before a BLE recording arrives. Repeated calls share the cached model. */
    fun warmUp(onProgress: (String) -> Unit = {}, onReady: (Result<Unit>) -> Unit = {}) {
        enqueue {
            fun progress(message: String) { if (!closed) main.post { if (!closed) onProgress(message) } }
            val result = runCatching { readyModel(::progress) { !closed }; Unit }
            if (!closed) main.post { if (!closed) onReady(result) }
        }
    }

    fun recognize(file: File, onProgress: (String) -> Unit, deleteAfter: Boolean = false, onResult: (Result<LocalTranscript>) -> Unit) {
        val ticket = generation.get()
        val queued = enqueue {
            fun current() = !closed && ticket == generation.get()
            fun progress(message: String) { if (current()) main.post { if (current()) onProgress(message) } }
            val result = try { runCatching {
                check(current()) { "识别已取消" }
                val info = WavPcmReader.inspect(file)
                progress("WAV 已校验：${info.description}")
                val loaded = readyModel(::progress, ::current)
                check(current()) { "识别已取消" }
                val transcript = TranscriptCollector()
                Recognizer(loaded, 16000f).use { recognizer ->
                    recognizer.setWords(true)
                    var lastPercent = -10
                    WavPcmReader.stream16k(file, info, onProgress = { percent ->
                        if (percent == 100 || percent - lastPercent >= 10) { lastPercent = percent; progress("手机正在离线转文字：$percent%（不上传录音）") }
                    }) { pcm, count ->
                        check(current()) { "识别已取消" }
                        if (recognizer.acceptWaveForm(pcm, count)) transcript.append(recognizer.result)
                    }
                    transcript.append(recognizer.finalResult)
                }
                transcript.finish()
            } } finally { if (deleteAfter) file.delete() }
            if (current()) main.post { if (current()) onResult(result) }
        }
        if (!queued && deleteAfter) file.delete()
    }

    // Both preparation and recognition use one worker, so native Model construction/close never race.
    private fun readyModel(progress: (String) -> Unit, current: () -> Boolean): Model {
        check(current()) { "识别已取消" }
        model?.let { return it }
        progress("正在准备手机内置中文模型；首次使用需复制模型，后续无需重复准备…")
        val directory = prepareModel()
        check(current()) { "识别已取消" }
        progress("正在加载手机内置中文模型…")
        return Model(directory.absolutePath).also { model = it }
    }

    private fun enqueue(task: () -> Unit): Boolean = synchronized(lifecycle) {
        if (closed) false else {
            worker.execute(task)
            true
        }
    }

    private fun prepareModel(): File {
        val destination = File(app.noBackupFilesDir, "asr/vosk-model-small-cn-0.22")
        val marker = File(destination, ".complete-v1")
        if (marker.isFile) return destination
        destination.mkdirs()
        fun copy(asset: String, target: File) {
            check(!closed) { "模型准备已取消" }
            val children = app.assets.list(asset).orEmpty()
            if (children.isEmpty()) {
                target.parentFile?.mkdirs()
                app.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
            } else { target.mkdirs(); children.forEach { copy("$asset/$it", File(target, it)) } }
        }
        copy("asr/vosk-model-small-cn-0.22", destination)
        marker.writeText("vosk-model-small-cn-0.22")
        return destination
    }

    fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            main.removeCallbacksAndMessages(null)
            worker.execute { model?.close(); model = null }
            worker.shutdown()
        }
    }
}
