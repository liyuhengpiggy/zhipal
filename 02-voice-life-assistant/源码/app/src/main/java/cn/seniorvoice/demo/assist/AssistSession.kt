package cn.seniorvoice.demo.assist

/** Serialized on the Android main thread. Tokens invalidate every asynchronous B result. */
class AssistSession {
    enum class BState { OBSERVING, CHECKING, WAITING, RECORDING, THINKING, RESULT }
    var aTask: String? = null; private set
    var generation = 0L; private set
    var promptId: String? = null; private set
    var bState = BState.OBSERVING; private set
    var cooldownUntil = 0L; private set

    fun clearB(now: Long, cooldown: Long = 60000) {
        generation++; promptId = null; bState = BState.OBSERVING
        cooldownUntil = now + cooldown
    }
    fun beginA(task: String, now: Long) { clearB(now); aTask = task }
    fun finishA(task: String, now: Long) {
        if (aTask != task) return
        aTask = null; clearB(now, 5000)
    }
    fun mayObserve(now: Long) = aTask == null && bState == BState.OBSERVING && now >= cooldownUntil
    fun check(now: Long): Long? {
        if (!mayObserve(now)) return null
        bState = BState.CHECKING; return generation
    }
    fun current(token: Long) = aTask == null && generation == token
    fun offer(token: Long, prompt: String): Boolean {
        if (!current(token) || bState !in setOf(BState.CHECKING, BState.THINKING)) return false
        promptId = prompt; bState = BState.WAITING; return true
    }
    fun record(prompt: String): Long? {
        if (aTask != null || promptId != prompt || bState != BState.WAITING) return null
        bState = BState.RECORDING; return generation
    }
    fun think(token: Long, prompt: String): Boolean {
        if (!current(token) || promptId != prompt || bState != BState.RECORDING) return false
        bState = BState.THINKING; return true
    }
    fun result(token: Long): Boolean {
        if (!current(token) || bState != BState.THINKING) return false
        promptId = null; bState = BState.RESULT; return true
    }
}

/** Baseline AND adjacent-frame checks prevent a slowly scrolling page counting as stationary. */
class PageDwellDetector(private val dwellMs: Long = 25000, private val maxGapMs: Long = 6000,
    private val meanDifference: Double = 0.025) {
    private var key: String? = null
    private var baseline: IntArray? = null
    private var previous: IntArray? = null
    private var since = 0L
    private var last = 0L
    private var count = 0
    fun reset() { key = null; baseline = null; previous = null; count = 0 }
    fun sample(page: String, pixels: IntArray, now: Long): Boolean {
        require(pixels.isNotEmpty() && pixels.all { it in 0..255 })
        fun similar(other: IntArray?) = other != null && other.size == pixels.size &&
            pixels.indices.sumOf { kotlin.math.abs(pixels[it] - other[it]).toLong() }.toDouble() / (pixels.size * 255.0) <= meanDifference
        if (key != page || now < last || now - last > maxGapMs || !similar(baseline) || !similar(previous)) {
            key = page; baseline = pixels.copyOf(); since = now; count = 0
        }
        previous = pixels.copyOf(); last = now; count++
        return count >= 3 && now - since >= dwellMs
    }
}

enum class ReplyKind { DECLINE, ACCEPT, GOAL }
fun classifyReply(text: String): ReplyKind {
    val normalized = text.trim().replace(Regex("[\\s，。！？,.!?]"), "")
    return when {
        Regex("^(不用|不需要|不用了|不需要了|不要|不要了|取消|算了|没事|没事了|不用帮忙|不帮忙|不)$").matches(normalized) -> ReplyKind.DECLINE
        normalized in setOf("需要", "需要帮助", "需要帮忙", "帮帮我", "帮我", "是", "是的", "好", "好的", "可以") -> ReplyKind.ACCEPT
        else -> ReplyKind.GOAL
    }
}
