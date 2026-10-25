package cn.seniorvoice.demo.assist

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/** One phone TTS channel. Stopping also drops speech queued before engine initialization. */
class AssistantSpeaker(context: Context, private val unavailable: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var failed = false
    private var pending: String? = null
    private var serial = 0L
    private val engine = TextToSpeech(context.applicationContext) { status ->
        main.post { initialized(status) }
    }
    private fun initialized(status: Int) {
        val language = if (status == TextToSpeech.SUCCESS) engine.setLanguage(Locale.SIMPLIFIED_CHINESE) else TextToSpeech.LANG_NOT_SUPPORTED
        ready = language >= TextToSpeech.LANG_AVAILABLE
        failed = !ready
        if (ready) { val text = pending; pending = null; if (text != null) speak(text) }
        else { val requested = pending != null; pending = null; if (requested) unavailable() }
    }
    fun speak(text: String) {
        if (text.isBlank()) return
        if (failed) { unavailable(); return }
        if (!ready) { pending = text.take(3500); return }
        if (engine.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "assist-${++serial}") == TextToSpeech.ERROR) unavailable()
    }
    fun stop() { pending = null; serial++; engine.stop() }
}
