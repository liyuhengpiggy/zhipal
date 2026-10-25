package cn.seniorvoice.demo.assist

import org.junit.Assert.*
import org.junit.Test

class ContactCallConfirmationTest {
    @Test fun singleCandidateNeedsACompleteConfirmation() {
        assertEquals(ContactCallConfirmation.Reply.Select(0), ContactCallConfirmation.reply("是的。", listOf("乔乔")))
        assertEquals(ContactCallConfirmation.Reply.Select(0), ContactCallConfirmation.reply("乔乔", listOf("乔乔")))
        for (text in listOf("不要确认", "确认然后给其他人打电话", "不是乔乔", "可以吗", "不是的", "随便"))
            assertSame(text, ContactCallConfirmation.Reply.Unclear, ContactCallConfirmation.reply(text, listOf("乔乔")))
    }
    @Test fun severalCandidatesRequireAnExactChoice() {
        assertSame(ContactCallConfirmation.Reply.Unclear, ContactCallConfirmation.reply("好的", listOf("乔乔", "巧巧")))
        assertEquals(ContactCallConfirmation.Reply.Select(1), ContactCallConfirmation.reply("第二个", listOf("乔乔", "巧巧")))
        assertEquals(ContactCallConfirmation.Reply.Select(0), ContactCallConfirmation.reply("乔乔", listOf("乔乔", "巧巧")))
        assertSame(ContactCallConfirmation.Reply.Unclear, ContactCallConfirmation.reply("第三个", listOf("乔乔", "巧巧")))
    }
    @Test fun refusalAlwaysStopsAndRepeatedNamesAreNotAChoice() {
        assertSame(ContactCallConfirmation.Reply.Decline, ContactCallConfirmation.reply("不要打电话", listOf("乔乔")))
        assertSame(ContactCallConfirmation.Reply.Unclear, ContactCallConfirmation.reply("乔乔", listOf("乔乔", "乔乔")))
    }
}
