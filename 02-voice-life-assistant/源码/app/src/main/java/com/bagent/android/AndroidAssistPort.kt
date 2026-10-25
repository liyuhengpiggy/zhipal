package com.bagent.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import cn.seniorvoice.demo.assist.BAgentActionPolicy
import com.bagent.integration.*
import com.mobileagent.app.agent.*
import com.mobileagent.app.api.*
import com.mobileagent.app.controller.*
import com.mobileagent.app.util.JsonActionParser
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.UUID

/** Calls host screenshot provider first; no MediaProjection authorization and no new service. */
class AndroidAssistPort(
    private val service: AccessibilityService,
    private val capture: suspend () -> Bitmap?,
    private val api: VlmApiClient,
    private val scope: CoroutineScope,
    private val guard: () -> Unit = {}
) : SingleStepPort {
    private data class Frame(val image: Bitmap, val elements: List<UiElement>)
    private val frames = mutableMapOf<String, Frame>()
    private val actions = AccessibilityActions(service)
    private val manager = Manager()
    private val executor = Executor()
    override suspend fun observe(): Observation {
        assertMain()
        guard()
        val before = identity()
        val beforeTree = elementSignature(UiElementDetector.detectElements(service))
        val bitmap = capture() ?: error("screenshot_unavailable")
        try {
            guard()
            val elements = UiElementDetector.detectElements(service)
            val after = identity()
            require(before == after && beforeTree == elementSignature(elements)) { "page_changed_during_capture" }
            require(elements.isNotEmpty()) { "empty_accessibility_tree" }
            // Ignore status/navigation bars so a changing clock cannot invalidate a static app page.
            val crop = Bitmap.createBitmap(bitmap, 0, (bitmap.height * 0.08).toInt(), bitmap.width, (bitmap.height * 0.84).toInt().coerceAtLeast(1))
            val small = Bitmap.createScaledBitmap(crop, 32, 32, false)
            val pixels = IntArray(1024)
            try { small.getPixels(pixels, 0, 32, 0, 0, 32, 32) }
            finally { if (small !== crop) small.recycle(); if (crop !== bitmap) crop.recycle() }
            val fingerprint = sha(after.toString() + elementSignature(elements) + pixels.joinToString(","))
            val token = UUID.randomUUID().toString()
            frames[token] = Frame(bitmap, elements)
            return Observation(token, fingerprint, after.first, after.second)
        } catch (e: Throwable) { bitmap.recycle(); throw e }
    }
    override suspend fun suggest(goal: String, history: List<RecentEvent>, screen: Observation): Suggestion {
        guard()
        val frame = frames[screen.token] ?: error("released_frame")
        val info = InfoPool(instruction = goal)
        info.additionalKnowledgeManager = "Recent observed events (suspected difficulty is not a user goal): ${history.joinToString("\n")}. Plan only one next step on CURRENT screen."
        val elements = UiElementDetector.buildElementListText(frame.elements)
        val annotated = UiElementDetector.annotateScreenshot(frame.image, frame.elements)
        try {
            val plan = api.predictWithImages(null, manager.getPrompt(info) + "\n" + elements, listOf(annotated)).getOrThrow()
            BModelProtocol.sections(plan, listOf("Thought", "Plan"))
            manager.parseResponse(plan, info)
            require(!info.plan.trim().equals("Finished", true)) { "no_action_needed" }
            val allowed = frame.elements.filter { it.isClickable && it.enabled && !it.password &&
                BAgentActionPolicy.allowed(it.text, it.contentDescription, it.viewId) }.associateBy { it.index }
            val prompt = BModelProtocol.targetPrompt(goal, info.plan, allowed.mapValues { (_, element) -> element.text.ifBlank { element.contentDescription } })
            val response = api.predictWithImages(null, prompt, listOf(annotated)).getOrThrow()
            executor.parseResponse(response, info)
            val id = BModelProtocol.targetClick(response, allowed.keys)
            val selected = allowed.getValue(id)
            val action = AgentAction.Click(selected.bounds.centerX(), selected.bounds.centerY())
            val target = target(frame, action)
            require(BAgentActionPolicy.allowed(target.text, target.contentDescription, target.viewId)) { "sensitive_or_unlabelled_action" }
            require(info.lastSummary.isNotBlank()) { "missing_action_description" }
            val label = target.text.ifBlank { target.contentDescription }.ifBlank { target.viewId }
            require(label.isNotBlank()) { "unlabelled_target" }
            val description = "点击“$label”"
            return Suggestion(description, """{"action":"click","coordinate":[${action.x},${action.y}]}""", key(target))
        } finally { if (annotated !== frame.image) annotated.recycle() }
    }
    override fun dispatchOnce(screen: Observation, suggestion: Suggestion): Deferred<Boolean> {
        assertMain()
        guard()
        val frame = frames[screen.token] ?: error("released_frame")
        val action = JsonActionParser.parseAction(suggestion.actionJson)
        require(action is AgentAction.Click) { "only_one_click_supported" }
        val selected = target(frame, action)
        require(BAgentActionPolicy.allowed(selected.text, selected.contentDescription, selected.viewId)) { "sensitive_action_requires_manual_operation" }
        require(key(selected) == suggestion.targetKey) { "target_changed" }
        require(identity() == (screen.packageName to screen.windowId)) { "window_changed" }
        // Reread tree in the same Main turn immediately before upstream dispatchGesture.
        val current = UiElementDetector.detectElements(service)
        require(elementSignature(current) == elementSignature(frame.elements)) { "tree_changed" }
        return scope.async(start = CoroutineStart.UNDISPATCHED) { actions.performTap(action.x.toFloat(), action.y.toFloat()) }
    }
    override suspend fun reflect(before: Observation, after: Observation, suggestion: Suggestion, goal: String): String {
        val info = InfoPool(instruction = goal, lastAction = JsonActionParser.extractActionMap(suggestion.actionJson), lastSummary = suggestion.description)
        val reflector = ActionReflector()
        val images = listOf(frames.getValue(before.token).image, frames.getValue(after.token).image)
        val result = api.predictWithImages(null, reflector.getPrompt(info), images).getOrThrow()
        reflector.parseResponse(result, info)
        return "${reflector.lastOutcome}:${reflector.lastErrorDescription}"
    }
    override fun release(screen: Observation) { frames.remove(screen.token)?.image?.recycle() }
    fun pageSignature(): String = sha(identity().toString() + elementSignature(UiElementDetector.detectElements(service)))
    private fun target(frame: Frame, action: AgentAction.Click): UiElement {
        val candidates = frame.elements.filter { it.isClickable && it.enabled && !it.password &&
            it.bounds.centerX() == action.x && it.bounds.centerY() == action.y }
        require(candidates.size == 1) { "ambiguous_or_unlisted_target" }
        return candidates.single()
    }
    private fun identity(): Pair<String, Int> {
        val node = service.rootInActiveWindow ?: error("no_active_window")
        return try { node.packageName.toString() to node.windowId } finally { node.recycle() }
    }
    private fun key(el: UiElement) = "${el.viewId}|${el.text}|${el.contentDescription}|${el.className}|${el.bounds}"
    private fun elementSignature(elements: List<UiElement>) = elements.joinToString("\n") { "${key(it)}|${it.isClickable}|${it.isScrollable}|${it.enabled}|${it.password}" }
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun assertMain() { check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { "Call B on Android Main thread" } }
}
