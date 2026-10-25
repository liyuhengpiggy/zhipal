package cn.seniorvoice.demo.nextaction.observation

import cn.seniorvoice.demo.nextaction.domain.InteractionEvent

/** Session memory only. No raw AccessibilityEvent, screenshot or input text is retained. */
class InteractionHistory(private val capacity: Int = 20) {
    private val events = ArrayDeque<InteractionEvent>()
    private var sequence = 0L

    @Synchronized fun add(nowMs: Long, type: String, packageName: String, windowId: Int) {
        expire(nowMs)
        val previous = events.lastOrNull()
        if (previous != null && previous.eventType == type && previous.packageName == packageName &&
            previous.windowId == windowId && nowMs - previous.occurredAtMs < 400) return
        if (events.size >= capacity) events.removeFirst()
        // Labels are deliberately omitted: a click's text can itself be personal information.
        events.addLast(InteractionEvent(++sequence, nowMs, type, packageName.take(180), windowId, ""))
    }
    @Synchronized fun snapshot(): List<InteractionEvent> = events.toList()
    @Synchronized fun snapshot(nowMs: Long): List<InteractionEvent> { expire(nowMs); return events.toList() }
    private fun expire(nowMs: Long) { while (events.firstOrNull()?.let { nowMs - it.occurredAtMs > 60000 } == true) events.removeFirst() }
    @Synchronized fun clear() { events.clear() }
}
