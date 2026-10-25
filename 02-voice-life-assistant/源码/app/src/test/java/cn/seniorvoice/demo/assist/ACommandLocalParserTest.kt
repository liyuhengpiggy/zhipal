package cn.seniorvoice.demo.assist

import org.junit.Assert.*
import org.junit.Test

class ACommandLocalParserTest {
    @Test fun explicitSingleNavigationUsesLocalIntents() {
        for (text in listOf("返回", "请返回上一页。", "返回上一级", " Please go back! ", "ＢＡＣＫ"))
            assertEquals(text, ACommandIntent.Navigate(ACommandNavigation.BACK), ACommandLocalParser.parse(text))
        for (text in listOf("回桌面", "帮我回到桌面", "返回主屏幕", "go home"))
            assertEquals(text, ACommandIntent.Navigate(ACommandNavigation.HOME), ACommandLocalParser.parse(text))
        for (text in listOf("最近任务", "请显示最近应用", "open recents"))
            assertEquals(text, ACommandIntent.Navigate(ACommandNavigation.RECENTS), ACommandLocalParser.parse(text))
    }

    @Test fun exitOnlyBindsAnExplicitCurrentAppInstructionToItsRealPackage() {
        for (text in listOf("关闭当前应用", "请退出这个应用。", "close current app")) {
            assertEquals(ACommandIntent.ExitApp("com.example.front"), ACommandLocalParser.parse(text, "com.example.front"))
            assertNull(ACommandLocalParser.parse(text)); assertNull(ACommandLocalParser.parse(text, "not a package"))
        }
        for (text in listOf("关闭抖音", "退出微信", "退出", "关闭当前页面", "关闭所有应用"))
            assertNull(text, ACommandLocalParser.parse(text, "com.example.front"))
    }

    @Test fun ambiguousPagesNegationQuestionsAndCompoundCommandsRemainAiInput() {
        for (text in listOf("上一页", "下一页", "返回吗", "不要返回", "别回桌面", "返回然后打电话", "返回，打开应用", "返回。关闭当前应用。",
            "back and go home", "don't go back", "请问怎么回桌面", "返回\n", "返\u200b回", "打开任意一个应用", "向下翻页然后点击搜索"))
            assertNull(text, ACommandLocalParser.parse(text, "com.example.front"))
    }

    @Test fun explicitScrollingRequiresExactlyOneRealControlAdvertisingThatDirection() {
        val down = ACommandControl("list1", "列表", listOf(ACommandAction.SCROLL_FORWARD))
        val up = ACommandControl("list2", "列表", listOf(ACommandAction.SCROLL_BACKWARD))
        assertEquals(ACommandIntent.ScreenAction("list1", ACommandAction.SCROLL_FORWARD),
            ACommandLocalParser.parse("请向下翻页。", controls = listOf(down, up)))
        assertEquals(ACommandIntent.ScreenAction("list2", ACommandAction.SCROLL_BACKWARD),
            ACommandLocalParser.parse("scroll up", controls = listOf(down, up)))
        assertNull(ACommandLocalParser.parse("向下翻页"))
        assertNull(ACommandLocalParser.parse("向下翻页", controls = listOf(down, down.copy(id = "other"))))
        assertNull(ACommandLocalParser.parse("向下翻页", controls = listOf(up)))
        assertEquals(ACommandAction.SCROLL_FORWARD, ACommandLocalParser.scrollAction("往下滑动"))
        assertEquals(ACommandAction.SCROLL_BACKWARD, ACommandLocalParser.scrollAction("向上翻页"))
        assertNull(ACommandLocalParser.scrollAction("上一页"))
    }
}
