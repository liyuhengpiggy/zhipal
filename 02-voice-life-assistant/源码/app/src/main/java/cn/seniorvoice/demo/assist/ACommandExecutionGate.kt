package cn.seniorvoice.demo.assist

/** A confirmation can authorize one step, only while its original page is still current. */
class ACommandExecutionGate(private val expiresAt: Long, private val expectedPage: String? = null) {
    private var consumed = false

    @Synchronized
    fun claim(now: Long, currentPage: String? = null): Boolean {
        if (consumed || now >= expiresAt || (expectedPage != null && expectedPage != currentPage)) return false
        consumed = true
        return true
    }

    @Synchronized
    fun invalidate() { consumed = true }
}
