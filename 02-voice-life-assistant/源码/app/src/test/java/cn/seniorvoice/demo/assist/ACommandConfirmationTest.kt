package cn.seniorvoice.demo.assist

import org.junit.Assert.assertEquals
import org.junit.Test

class ACommandConfirmationTest {
    @Test fun missingOrDifferentPromptCannotConfirmAnOutstandingAction() {
        assertEquals(false, ACommandConfirmation.matchesPrompt("a-current", null))
        assertEquals(false, ACommandConfirmation.matchesPrompt("a-current", "a-old"))
        assertEquals(false, ACommandConfirmation.matchesPrompt("", ""))
        assertEquals(true, ACommandConfirmation.matchesPrompt("a-current", "a-current"))
    }
    @Test fun completeApprovalAndRejectionAreRecognized() {
        listOf("确认", "确认执行。", " 好的！", "confirm").forEach {
            assertEquals(ACommandConfirmation.Reply.ACCEPT, ACommandConfirmation.reply(it))
        }
        listOf("取消", "不对", "不用", "cancel").forEach {
            assertEquals(ACommandConfirmation.Reply.DECLINE, ACommandConfirmation.reply(it))
        }
    }
    @Test fun newInstructionsAndMixedRepliesDoNotApproveOldAction() {
        listOf("确认后打开微信", "好的，但是不要退出", "取消，然后打开抖音", "打开确认页面", "确认\n打开微信").forEach {
            assertEquals(ACommandConfirmation.Reply.OTHER, ACommandConfirmation.reply(it))
        }
    }
}
