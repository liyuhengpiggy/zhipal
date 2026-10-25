package cn.seniorvoice.demo.nextaction.observation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cn.seniorvoice.demo.nextaction.domain.*
import java.security.MessageDigest
import java.util.UUID

/** Must be called on the accessibility main thread. Bounds remain in device pixels. */
class UiTreeParser {
    fun parse(root: AccessibilityNodeInfo, revision: Long, nowMs: Long, width: Int, height: Int): UiSnapshot {
        val nodes = mutableListOf<UiNodeSnapshot>()
        var incomplete = false
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            if (depth > MAX_DEPTH || nodes.size >= MAX_NODES) { incomplete = true; return }
            val bounds = Rect().also(node::getBoundsInScreen)
            val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
            val label = if (node.isPassword || editable) "" else
                (node.contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: node.text?.toString().orEmpty()).take(160)
            nodes.add(UiNodeSnapshot(path, node.className?.toString().orEmpty().take(180),
                node.viewIdResourceName?.take(200), label,
                Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom), node.isVisibleToUser,
                node.isEnabled, node.isClickable, node.isScrollable, node.isPassword, editable))
            for (index in 0 until node.childCount) {
                if (nodes.size >= MAX_NODES) { incomplete = true; break }
                val child = node.getChild(index) ?: continue
                try { visit(child, "$path.$index", depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        visit(root, "0", 0)
        check(!incomplete) { "页面控件过多或层级过深，无法完成隐私检查，本次不采集" }
        val packageName = root.packageName?.toString().orEmpty()
        val fingerprintText = buildString {
            append(packageName).append('|').append(root.windowId).append('|').append(width).append('x').append(height)
            nodes.forEach { append('\n').append(it) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(fingerprintText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return UiSnapshot(UUID.randomUUID().toString(), packageName, root.windowId, nowMs,
            digest, revision, width, height, nodes)
    }

    /** No coordinate fallback. Keep only this freshly fetched node for the duration of one action. */
    fun find(root: AccessibilityNodeInfo, nodeKey: String): AccessibilityNodeInfo? {
        val path = nodeKey.split('.')
        if (path.firstOrNull() != "0" || path.size > MAX_DEPTH + 1) return null
        @Suppress("DEPRECATION")
        var current: AccessibilityNodeInfo = AccessibilityNodeInfo.obtain(root)
        for (part in path.drop(1)) {
            val index = part.toIntOrNull()
            if (index == null || index !in 0 until current.childCount) { @Suppress("DEPRECATION") current.recycle(); return null }
            val next = current.getChild(index)
            @Suppress("DEPRECATION") current.recycle()
            if (next == null) return null
            current = next
        }
        return current
    }
    companion object { const val MAX_DEPTH = 20; const val MAX_NODES = 160 }
}
