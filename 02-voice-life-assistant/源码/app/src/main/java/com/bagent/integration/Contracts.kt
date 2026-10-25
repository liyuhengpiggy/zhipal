package com.bagent.integration

import kotlinx.coroutines.Deferred

/** Only immutable summaries cross this boundary; Android bitmaps stay in the adapter. */
data class Observation(val token: String, val fingerprint: String, val packageName: String, val windowId: Int)
data class Suggestion(val description: String, val actionJson: String, val targetKey: String)
data class RecentEvent(val atMs: Long, val kind: Kind, val page: String, val control: String = "", val summary: String = "") {
    enum class Kind { CLICK, PAGE, OTHER }
}
enum class PromptKind { GOAL, ACTION }
data class AssistPrompt(val taskId: String, val promptId: String, val kind: PromptKind, val text: String, val expiresAtMs: Long)
data class AssistEvent(val type: String, val taskId: String = "", val promptId: String = "", val detail: String = "")
interface AssistListener {
    fun onPrompt(prompt: AssistPrompt)
    fun onEvent(event: AssistEvent)
}
interface SingleStepPort {
    suspend fun observe(): Observation
    suspend fun suggest(goal: String, history: List<RecentEvent>, screen: Observation): Suggestion
    /** Revalidate and synchronously BEGIN exactly one platform action, with no suspension before dispatch.
     * Must be called on the same serialized dispatcher as A/B lifecycle entry points. */
    fun dispatchOnce(screen: Observation, suggestion: Suggestion): Deferred<Boolean>
    suspend fun reflect(before: Observation, after: Observation, suggestion: Suggestion, goal: String): String
    fun release(screen: Observation)
}
