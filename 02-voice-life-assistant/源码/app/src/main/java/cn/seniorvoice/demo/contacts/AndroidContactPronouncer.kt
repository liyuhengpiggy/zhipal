package cn.seniorvoice.demo.contacts

import android.annotation.TargetApi
import android.os.Build

/** No model, network, hidden API, or third-party pinyin dependency is used. */
class AndroidContactPronouncer : ContactMatcher.Pronouncer {
    private val delegate: ContactMatcher.Pronouncer? by lazy {
        if (Build.VERSION.SDK_INT < 29) null
        else try { Api29.create() }
        catch (_: RuntimeException) { null }
        catch (_: LinkageError) { null }
    }

    /** Android 8/9, or a missing/broken system ICU implementation, keep exact matching. */
    override fun syllable(hanCodePoint: Int): String? = delegate?.syllable(hanCodePoint)

    @TargetApi(29)
    private object Api29 {
        fun create(): ContactMatcher.Pronouncer? {
            // Do not use Latin-ASCII: it would collapse ü into u and conflate 吕 / 鲁.
            val icu = android.icu.text.Transliterator.getInstance("Han-Latin")
            val pronouncer = ConservativeHanPronouncer { icu.transliterate(it) }
            // Fail closed on an unexpected vendor transliteration implementation.
            val probes = mapOf('乔' to "qiao", '瞧' to "qiao", '吕' to "lv", '鲁' to "lu")
            return pronouncer.takeIf { probes.all { (han, pinyin) -> it.syllable(han.code) == pinyin } }
        }
    }
}
