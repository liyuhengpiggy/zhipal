package cn.seniorvoice.demo

import cn.seniorvoice.demo.appcontrol.InstalledApp
import cn.seniorvoice.demo.appcontrol.InstalledAppMatcher
import org.junit.Assert.*
import org.junit.Test

class InstalledAppMatcherTest {
    private fun matchedPackage(request: String, apps: List<InstalledApp>): String {
        val result = InstalledAppMatcher.resolve(request, apps)
        assertTrue("Expected a unique app for '$request', got $result", result is InstalledAppMatcher.Result.Matched)
        return (result as InstalledAppMatcher.Result.Matched).app.packageName
    }

    @Test fun discoversAnyInstalledNameWithoutPerAppRules() {
        val apps = listOf(
            InstalledApp("example.reader", "邻里读报"),
            InstalledApp("example.music", "云上音乐"),
            InstalledApp("example.qq", "QQ")
        )
        for (app in apps) assertEquals(app.packageName, matchedPackage(app.label, apps))
        assertEquals(InstalledAppMatcher.Result.NotInstalled, InstalledAppMatcher.resolve("微信", apps))
    }

    @Test fun fullWidthCaseAndSpeechWhitespaceResolveActualLabel() {
        val apps = listOf(InstalledApp("example.qq", "QQ"), InstalledApp("example.cafe", "Café Radio"))
        assertEquals("example.qq", matchedPackage("　ｑ Ｑ\u200B", apps))
        assertEquals("example.cafe", matchedPackage("CAFE\u0301\u00A0RADIO", apps))
    }

    @Test fun meaningfulPunctuationDoesNotChangeTheTarget() {
        val apps = listOf(InstalledApp("example.c", "C"), InstalledApp("example.cpp", "C++"))
        assertEquals("example.cpp", matchedPackage("C＋＋", apps))
        assertEquals("example.c", matchedPackage("c", apps))
        assertEquals(InstalledAppMatcher.Result.NotInstalled, InstalledAppMatcher.resolve("C+", apps))
    }

    @Test fun courtesyEndingsPreserveTiebaAndPreferTheActualCompleteName() {
        val apps = listOf(InstalledApp("example.tieba", "贴吧"), InstalledApp("example.qq", "QQ"))
        for (name in listOf("贴吧", "贴吧吧", "贴吧一下吧", "贴吧呀")) {
            assertEquals("example.tieba", matchedPackage(name, apps))
        }
        assertEquals("example.qq", matchedPackage("QQ一下吧", apps))
        val additionalApp = apps + InstalledApp("example.literal", "QQ吧")
        assertEquals("example.literal", matchedPackage("QQ吧", additionalApp))
        assertEquals(InstalledAppMatcher.Result.NotInstalled,
            InstalledAppMatcher.resolve("贴吧", listOf(InstalledApp("example.short", "贴"))))
    }

    @Test fun spokenQqAliasRequiresAnActuallyInstalledAppAndPrefersLiteralLabel() {
        val qq = InstalledApp("example.qq", "QQ")
        val result = InstalledAppMatcher.resolve("扣扣吧", listOf(qq))
        assertEquals(InstalledAppMatcher.Result.Matched(qq, usedAlias = true), result)
        assertEquals(InstalledAppMatcher.Result.NotInstalled, InstalledAppMatcher.resolve("扣扣", emptyList()))
        assertEquals("example.literal", matchedPackage("扣扣", listOf(qq, InstalledApp("example.literal", "扣扣"))))
    }

    @Test fun duplicateLabelsAcrossPackagesNeedAnExplicitUserChoice() {
        val apps = listOf(InstalledApp("example.music.two", "音乐"), InstalledApp("example.music.one", "音乐"))
        val result = InstalledAppMatcher.resolve("音乐", apps)
        assertTrue(result is InstalledAppMatcher.Result.Ambiguous)
        assertEquals(setOf("example.music.one", "example.music.two"),
            (result as InstalledAppMatcher.Result.Ambiguous).candidates.map { it.packageName }.toSet())
        val qqApps = listOf(InstalledApp("example.qq.one", "QQ"), InstalledApp("example.qq.two", "腾讯QQ"))
        assertTrue(InstalledAppMatcher.resolve("扣扣", qqApps) is InstalledAppMatcher.Result.Ambiguous)
    }

    @Test fun multipleLaunchersInOnePackageRetainTheMatchingComponent() {
        val apps = listOf(
            InstalledApp("example.reader", "读报", "example.reader.Main"),
            InstalledApp("example.reader", "听报", "example.reader.Listen"),
            InstalledApp("example.reader", "读报", "example.reader.Other")
        )
        val result = InstalledAppMatcher.resolve("听报", apps) as InstalledAppMatcher.Result.Matched
        assertEquals("example.reader.Listen", result.app.launcherActivity)
        assertTrue(InstalledAppMatcher.resolve("读报", apps) is InstalledAppMatcher.Result.Matched)
    }

    @Test fun emptyAndPartialNamesCannotLaunchAnUnrelatedApp() {
        val apps = listOf(InstalledApp("example.qq", "QQ音乐"), InstalledApp("", "QQ"))
        for (name in listOf("", "　", "QQ", "音乐", "打开QQ音乐")) {
            assertEquals(InstalledAppMatcher.Result.NotInstalled, InstalledAppMatcher.resolve(name, apps))
        }
    }

    @Test fun parsedVoiceInstructionsReachInstalledAppsWithoutEatingNameSuffixes() {
        val apps = listOf(InstalledApp("example.tieba", "贴吧"), InstalledApp("example.qq", "QQ"))
        val cases = mapOf("请帮我打开贴吧吧。" to "example.tieba", "打开 Ｑ Ｑ 一下吧" to "example.qq")
        for ((speech, expected) in cases) {
            val command = CommandParser.parse(speech)
            assertTrue(command is CommandParser.Command.OpenApp)
            assertEquals(expected, matchedPackage((command as CommandParser.Command.OpenApp).name, apps))
        }
    }

    @Test fun actionWordsInsideRealAppNamesStillResolveToTheFullInstalledLabel() {
        val apps = listOf(
            InstalledApp("example.search", "搜狗搜索"),
            InstalledApp("example.tap", "点击助手"),
            InstalledApp("example.launcher", "启动器"),
            InstalledApp("example.transfer", "转账助手")
        )
        for (app in apps) {
            val command = CommandParser.parse("打开${app.label}")
            assertEquals(CommandParser.Command.OpenApp(app.label), command)
            assertEquals(app.packageName,
                matchedPackage((command as CommandParser.Command.OpenApp).name, apps))
        }
    }

    @Test fun trailingInAppActionNeverFallsBackToOpeningThePrefixApp() {
        val apps = listOf(InstalledApp("example.qq", "QQ"), InstalledApp("example.weixin", "微信"))
        for (speech in listOf("打开QQ搜索水水", "打开QQ点击联系人", "打开QQ启动微信")) {
            val command = CommandParser.parse(speech)
            assertTrue(command is CommandParser.Command.OpenApp)
            assertEquals(InstalledAppMatcher.Result.NotInstalled,
                InstalledAppMatcher.resolve((command as CommandParser.Command.OpenApp).name, apps))
        }
    }

    @Test fun compoundQuestionsAndNegationNeverBecomeAnOpenAppCommand() {
        for (speech in listOf(
            "打开QQ然后给女儿打电话", "打开QQ，接着打开抖音", "打开QQ并发消息给水水",
            "打开QQ发消息", "打开QQ发送一条消息", "打开QQ再启动微信", "不要打开QQ",
            "打开QQ吗", "打开QQ？", "怎么打开QQ", "打开所有软件"
        )) {
            assertEquals(speech, CommandParser.Command.Unknown, CommandParser.parse(speech))
        }
        assertEquals(CommandParser.Command.Call("水水"), CommandParser.parse("给水水打电话"))
    }
}
