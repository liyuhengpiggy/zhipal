package cn.seniorvoice.demo.nextaction.domain

/** Unique stable-feature match only. Bounds are evidence, never a coordinate-click fallback. */
class TargetResolver {
    fun isSamePage(original: UiSnapshot, current: UiSnapshot): Boolean =
        original.packageName == current.packageName && original.windowId == current.windowId &&
            original.revision == current.revision && original.uiFingerprint.isNotBlank() &&
            original.uiFingerprint == current.uiFingerprint &&
            original.screenWidth == current.screenWidth && original.screenHeight == current.screenHeight

    fun resolve(candidate: Candidate, current: UiSnapshot): UiNodeSnapshot? {
        if (candidate.action !in setOf(ActionType.TAP, ActionType.SCROLL_UP, ActionType.SCROLL_DOWN)) return null
        val key = candidate.nodeKey ?: return null
        val bounds = candidate.bounds ?: return null
        val className = candidate.className ?: return null
        return current.nodes.filter { node ->
            node.nodeKey == key && node.className == className && node.viewId == candidate.viewId && node.bounds == bounds &&
                node.label.ifBlank { if (candidate.action == ActionType.TAP) "" else "可滚动区域" } == candidate.targetLabel &&
                node.visible && node.enabled && !node.password && !node.editable &&
                when (candidate.action) {
                    ActionType.TAP -> node.clickable
                    ActionType.SCROLL_UP, ActionType.SCROLL_DOWN -> node.scrollable
                    else -> false
                }
        }.singleOrNull()
    }
}
