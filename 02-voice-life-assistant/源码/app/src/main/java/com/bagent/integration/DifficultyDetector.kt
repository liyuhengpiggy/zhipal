package com.bagent.integration

class DifficultyDetector(private val cooldownMs: Long = 60_000L) {
    private val events = ArrayDeque<RecentEvent>()
    private var lastTrigger = Long.MIN_VALUE / 2
    fun clear() { events.clear() }
    fun recent(): List<RecentEvent> = events.takeLast(5)
    fun add(event: RecentEvent): String? {
        events.addLast(event)
        while (events.isNotEmpty() && (event.atMs - events.first().atMs > 20_000 || events.size > 32)) events.removeFirst()
        if (event.atMs - lastTrigger < cooldownMs) return null
        val recent = events.filter { event.atMs - it.atMs <= 10_000 }
        // All observed page summaries must match; reading/waiting alone never triggers.
        val repeated = event.kind == RecentEvent.Kind.CLICK && event.control.isNotBlank() &&
            recent.all { it.page == event.page } &&
            recent.count { it.kind == RecentEvent.Kind.CLICK && it.control == event.control } >= 3
        val pages = events.filter { it.kind == RecentEvent.Kind.PAGE }.map { it.page }
            .fold(mutableListOf<String>()) { acc, p -> if (acc.lastOrNull() != p) acc.add(p); acc }.takeLast(6)
        val pingpong = event.kind == RecentEvent.Kind.PAGE && pages.size == 6 && pages[0] != pages[1] &&
            pages.indices.all { pages[it] == pages[it % 2] }
        val reason = when { repeated -> "repeated_click_without_progress"; pingpong -> "page_ping_pong"; else -> null }
        if (reason != null) lastTrigger = event.atMs
        return reason
    }
}
