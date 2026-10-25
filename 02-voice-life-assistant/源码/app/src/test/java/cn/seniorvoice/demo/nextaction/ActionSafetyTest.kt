package cn.seniorvoice.demo.nextaction

import cn.seniorvoice.demo.nextaction.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ActionSafetyTest {
    private fun node(key: String, label: String, clickable: Boolean = true, scrollable: Boolean = false) =
        UiNodeSnapshot(key, "android.widget.Button", "sample:id/$key", label, Bounds(20, 40, 240, 160),
            true, true, clickable, scrollable, false, false)
    private fun snapshot(nodes: List<UiNodeSnapshot>) = UiSnapshot("capture", "com.example.sample", 2, 1000,
        "fingerprint", 3, 1080, 1920, nodes)

    @Test fun sensitiveAndAmbiguousButtonsAreNotCandidatesDespiteHighModelConfidence() {
        val labels = listOf("发送", "发布", "提交", "确认", "删除", "拨打电话", "购买", "下一步", "继续", "确定", "登录", "允许", "分享", "保存")
        for (label in labels) {
            val nodes = listOf(node("risky", label), node("safe", "下一页"))
            val generated = CandidateGenerator().generate(snapshot(nodes))
            assertFalse("不能为 $label 生成点击候选", generated.any { it.action == ActionType.TAP && it.nodeKey == "risky" })
        }
    }

    @Test fun nodeMustBeVisibleEnabledActuallyClickableAndHaveSafeMeaning() {
        val valid = node("next", "下一页")
        val bad = listOf(valid.copy(visible = false), valid.copy(enabled = false), valid.copy(clickable = false),
            valid.copy(password = true), valid.copy(editable = true), valid.copy(label = ""), valid.copy(bounds = Bounds(0, 0, 0, 0)))
        assertTrue(ActionPolicy().allowsNode(valid))
        bad.forEach { assertFalse(ActionPolicy().allowsNode(it)) }
    }

    @Test fun candidatesAreBoundedAndScrollingRequiresRealScrollableNode() {
        val nodes = (0 until 160).map { node("node-$it", "下一页") }
        val generated = CandidateGenerator().generate(snapshot(nodes))
        assertTrue(generated.size <= AgentLimits.MAX_CANDIDATES)
        assertEquals(1, generated.count { it.action == ActionType.NO_OP })
        assertEquals(1, generated.count { it.action == ActionType.BACK })
        assertFalse(generated.any { it.action == ActionType.SCROLL_UP || it.action == ActionType.SCROLL_DOWN })
        val scroll = CandidateGenerator().generate(snapshot(listOf(node("list", "列表", false, true))))
        assertEquals(1, scroll.count { it.action == ActionType.SCROLL_UP })
        assertEquals(1, scroll.count { it.action == ActionType.SCROLL_DOWN })
    }

    @Test fun duplicateOrDisappearedNodesNeverResolveAndNoCoordinateFallbackExists() {
        val target = node("next", "下一页")
        val original = snapshot(listOf(target))
        val candidate = CandidateGenerator().generate(original).first { it.action == ActionType.TAP }
        val resolver = TargetResolver()
        assertEquals(target, resolver.resolve(candidate, original))
        assertNull(resolver.resolve(candidate, snapshot(emptyList())))
        assertNull(resolver.resolve(candidate, snapshot(listOf(target, target))))
        assertNull(resolver.resolve(candidate, snapshot(listOf(target.copy(nodeKey = "different-key")))))
        assertNull(resolver.resolve(candidate, snapshot(listOf(target.copy(label = "删除")))))
        assertNull(resolver.resolve(candidate, snapshot(listOf(target.copy(bounds = Bounds(21, 40, 241, 160))))))
        assertFalse(resolver.isSamePage(original, original.copy(revision = 4)))
    }

    @Test fun gateClaimIsAtomicAndCancellationOrDeadlineCannotBeBypassed() {
        val now = AtomicInteger(1000)
        val gate = ExecutionGate(AgentClock { now.get().toLong() })
        val permit = gate.createPermit("one-request", 2000)
        val start = CountDownLatch(1)
        val claimed = AtomicInteger()
        val threads = (1..24).map { Thread { start.await(); if (permit.claim()) claimed.incrementAndGet() }.apply { start() } }
        start.countDown()
        threads.forEach { it.join(1000) }
        assertEquals(1, claimed.get())
        assertTrue(permit.wasClaimed)
        assertFalse(permit.isValid)
        val cancelled = gate.createPermit("cancelled", 2000)
        cancelled.cancel()
        assertFalse(cancelled.claim())
        val expired = gate.createPermit("expired", 2000)
        now.set(2000)
        assertFalse(expired.claim())
    }
}
