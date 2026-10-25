package cn.seniorvoice.demo

import org.junit.Assert.*
import org.junit.Test

class TranscriptCollectorTest {
    @Test fun keepsAllCompletedSegmentsAndIgnoresEmptyFinalSegment() {
        val collector = TranscriptCollector()
        collector.append("""{"text":"打开 抖音","result":[{"conf":0.9},{"conf":0.8}]}""")
        collector.append("""{"text":"然后 给 女儿 打 电话","result":[{"conf":0.7}]}""")
        collector.append("""{"text":""}""")
        val result = collector.finish()
        assertEquals("打开抖音然后给女儿打电话", result.text)
        assertEquals(0.8, result.confidence!!, 0.00001)
        assertEquals(CommandParser.Command.Unknown, CommandParser.parse(result.text))
    }

    @Test fun partialHypothesesAndMissingOrInvalidConfidenceCannotExecute() {
        val collector = TranscriptCollector()
        collector.append("""{"partial":"给女儿打电话"}""")
        assertThrows(IllegalArgumentException::class.java) { collector.finish() }
        collector.append("""{"text":"打开 抖音","result":[{"conf":-1},{"conf":2},{}]}""")
        assertFalse(collector.finish().canExecute)
    }

    @Test fun uncertainSpeechIsStillRecordedButCannotLaunchCalls() {
        val collector = TranscriptCollector()
        collector.append("""{"text":"给 女儿 打 电话","result":[{"conf":0.4},{"conf":0.5}]}""")
        assertEquals("给女儿打电话", collector.finish().text)
        assertFalse(collector.finish().canExecute)
    }

    @Test fun completeClearCommandsExecuteWithoutMatchingSubstringsOrNegation() {
        assertEquals(CommandParser.Command.OpenApp("抖音"), CommandParser.parse("请帮我打开抖音。"))
        assertEquals(CommandParser.Command.Call("女儿"), CommandParser.parse("给女儿打个电话"))
        assertEquals(CommandParser.Command.Call("小水"), CommandParser.parse("拨打小水的电话"))
        for (text in listOf("不要打开抖音", "不要给女儿打电话", "能不能打开抖音", "打开抖音然后给女儿打电话")) {
            assertEquals(CommandParser.Command.Unknown, CommandParser.parse(text))
        }
        val collector = TranscriptCollector()
        collector.append("""{"text":"给 女儿 打 电话","result":[{"conf":0.95}]}""")
        assertTrue(collector.finish().canExecute)
        assertEquals(CommandParser.Command.Call("女儿"), CommandParser.parse(collector.finish().text))
    }

    @Test fun malformedAndOverlongResultsAreRejected() {
        assertThrows(Exception::class.java) { TranscriptCollector().append("not json") }
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptCollector().append("{\"text\":\"${"字".repeat(501)}\"}")
        }
    }
}
