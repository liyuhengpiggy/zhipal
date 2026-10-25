package cn.seniorvoice.demo.assist

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ACommandProtocolTest {
    private val request = ACommandRequest("a_test", "帮我看一下这个页面", listOf(ACommandApp("com.example.real", "真实应用")),
        listOf(ACommandContact("contact-7", "张同学")), ACommandScreen("com.example.front", listOf(
            ACommandControl("button1", "搜索", listOf(ACommandAction.CLICK)),
            ACommandControl("list2", "列表", listOf(ACommandAction.SCROLL_FORWARD, ACommandAction.SCROLL_BACKWARD)))))
    private fun response(intent: String) = """{"version":1,"task_id":"a_test","intent":$intent}"""
    private fun parse(intent: String) = ACommandProtocol.parseResponse(response(intent), request).intent
    private fun reject(body: String) = assertThrows(IllegalArgumentException::class.java) { ACommandProtocol.parseResponse(body, request) }

    @Test fun requestUsesOnlyTheDocumentedSchemaAndPreservesTheOriginalUtterance() {
        val json = JSONObject(ACommandProtocol.encode(request))
        assertEquals(setOf("version", "task_id", "utterance", "apps", "contacts", "screen"), json.keySet())
        assertEquals(1, json.getInt("version")); assertEquals(request.utterance, json.getString("utterance"))
        assertEquals("com.example.real", json.getJSONArray("apps").getJSONObject(0).getString("id"))
        assertEquals("张同学", json.getJSONArray("contacts").getJSONObject(0).getString("name"))
        assertEquals("com.example.front", json.getJSONObject("screen").getString("package_name"))
        assertEquals("click", json.getJSONObject("screen").getJSONArray("controls").getJSONObject(0).getJSONArray("actions").getString(0))
        assertTrue(JSONObject(ACommandProtocol.encode(request.copy(screen = null))).isNull("screen"))
    }

    @Test fun everyIntentUsesRealSnapshotTargetsAndExactlyOneTypedAction() {
        assertEquals(ACommandIntent.OpenApp("com.example.real"), parse("""{"type":"open_app","target":"com.example.real"}"""))
        assertEquals(ACommandIntent.CallContact("contact-7"), parse("""{"type":"call_contact","target":"contact-7"}"""))
        for (nav in ACommandNavigation.entries) assertEquals(ACommandIntent.Navigate(nav), parse("""{"type":"navigate","target":"${nav.wire}"}"""))
        assertEquals(ACommandIntent.ExitApp("com.example.front"), parse("""{"type":"exit_app","target":"com.example.front"}"""))
        assertEquals(ACommandIntent.ScreenAction("button1", ACommandAction.CLICK), parse("""{"type":"screen_action","target":"button1","action":"click"}"""))
        for (action in listOf(ACommandAction.SCROLL_FORWARD, ACommandAction.SCROLL_BACKWARD))
            assertEquals(ACommandIntent.ScreenAction("list2", action), parse("""{"type":"screen_action","target":"list2","action":"${action.wire}"}"""))
        for (mode in ACommandVisionMode.entries) assertEquals(ACommandIntent.Vision(mode), parse("""{"type":"vision","mode":"$mode"}"""))
        assertEquals(ACommandIntent.Clarify("你想打开哪个应用？"), parse("""{"type":"clarify","message":"你想打开哪个应用？"}"""))
        assertEquals(ACommandIntent.Unsupported("该操作暂不支持"), parse("""{"type":"unsupported","message":"该操作暂不支持"}"""))
    }

    @Test fun versionAndTaskMustMatchWithoutStringOrDecimalCoercion() {
        val valid = response("""{"type":"navigate","target":"back"}""")
        for (version in listOf("\"1\"", "1.0", "1e0", "true", "null", "2", "01")) reject(valid.replace("\"version\":1", "\"version\":$version"))
        reject(valid.replace("a_test", "a_old"))
        reject(valid.replace("\"task_id\":\"a_test\"", "\"task_id\":1"))
    }

    @Test fun unknownMissingExtraAndMultipleActionFieldsAreRejected() {
        val intents = listOf(
            """{"type":"launch_app","target":"com.example.real"}""",
            """{"type":"open_app","target":"com.example.real","message":"open it"}""",
            """{"type":"open_app"}""",
            """{"type":"open_app","target":null}""",
            """{"type":"navigate","target":"back","next":{"type":"navigate","target":"home"}}""",
            """[{"type":"navigate","target":"back"},{"type":"navigate","target":"home"}]""",
            """{"type":"screen_action","target":"button1","actions":["click","click"]}""",
            """{"type":"screen_action","target":"button1","action":["click"]}""",
            """{"type":"vision","mode":"ocr"}""",
            """{"type":"clarify","message":" "}""",
            """{"type":"clarify","message":"a\nb"}"""
        )
        intents.forEach { reject(response(it)) }
        reject(response("""{"type":"navigate","target":"back"}""").dropLast(1) + ",\"debug\":true}")
        reject("""{"version":1,"task_id":"a_test"}""")
    }

    @Test fun inventedOrCrossKindTargetsAndUnadvertisedActionsAreRejected() {
        for (intent in listOf(
            """{"type":"open_app","target":"com.example.fake"}""",
            """{"type":"open_app","target":"真实应用"}""",
            """{"type":"call_contact","target":"13800000000"}""",
            """{"type":"call_contact","target":"com.example.real"}""",
            """{"type":"exit_app","target":"com.example.real"}""",
            """{"type":"screen_action","target":"old-button","action":"click"}""",
            """{"type":"screen_action","target":"button1","action":"scroll_forward"}""",
            """{"type":"screen_action","target":"button1","action":"long_click"}""",
            """{"type":"navigate","target":"close_all"}"""
        )) reject(response(intent))
        for (intent in listOf("""{"type":"exit_app","target":"com.example.front"}""",
            """{"type":"screen_action","target":"button1","action":"click"}"""))
            assertThrows(IllegalArgumentException::class.java) { ACommandProtocol.parseResponse(response(intent), request.copy(screen = null)) }
    }

    @Test fun duplicateKeysIncludingEscapedKeysAreNeverSilentlyOverwritten() {
        for (body in listOf(
            """{"version":2,"version":1,"task_id":"a_test","intent":{"type":"navigate","target":"back"}}""",
            """{"version":1,"task_id":"a_old","task_id":"a_test","intent":{"type":"navigate","target":"back"}}""",
            response("""{"type":"open_app","type":"navigate","target":"back"}"""),
            response("""{"type":"navigate","target":"home","\u0074arget":"back"}""")
        )) reject(body)
    }

    @Test fun truncatedLenientOrSurroundedJsonCannotBecomeAnAction() {
        val valid = response("""{"type":"navigate","target":"back"}""")
        for (body in listOf(valid.dropLast(1), valid.dropLast(5), "```json\n$valid\n```", "$valid $valid", "$valid trailing",
            valid.replace("\"version\"", "version"), valid.replace('"', '\''), valid.replace(",\"task_id\"", ",/*note*/\"task_id\""),
            valid.dropLast(1) + ",}", valid.replace("\"back\"", "back"), "\uFEFF$valid",
            response("""{"type":"clarify","message":"\uD800"}"""), response("""{"type":"clarify","message":"\uDC00"}"""))) reject(body)
        assertEquals(ACommandIntent.Clarify("看这里😀"), parse("""{"type":"clarify","message":"看这里\uD83D\uDE00"}"""))
    }

    @Test fun requestLimitsRejectDuplicateTargetsAndInvalidSnapshotsButAllowFiveHundredApps() {
        val large = request.copy(apps = (1..500).map { ACommandApp("com.example.app$it", "应用$it") })
        ACommandProtocol.encode(large)
        val invalid = listOf(request.copy(apps = large.apps + ACommandApp("com.example.extra", "额外应用")),
            request.copy(apps = request.apps + request.apps), request.copy(contacts = request.contacts + request.contacts),
            request.copy(contacts = (1..101).map { ACommandContact("c$it", "联系人$it") }),
            request.copy(screen = ACommandScreen("com.example.front", (1..81).map { ACommandControl("c$it", "按钮", listOf(ACommandAction.CLICK)) })),
            request.copy(screen = request.screen!!.copy(controls = listOf(request.screen.controls[0], request.screen.controls[0]))),
            request.copy(screen = request.screen.copy(controls = listOf(ACommandControl("c1", "按钮", emptyList())))),
            request.copy(utterance = ""), request.copy(utterance = "回桌面\n"), request.copy(taskId = "../task"), request.copy(version = 2))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { ACommandProtocol.encode(it) } }
        reject(" ".repeat(ACommandProtocol.MAX_RESPONSE_BYTES + 1))
        reject(response("""{"type":"clarify","message":"${"字".repeat(601)}"}"""))
    }

    @Test fun snapshotCopiesMutableCollectionsBeforeTheRequestStarts() {
        val apps = request.apps.toMutableList(); val actions = mutableListOf(ACommandAction.CLICK)
        val controls = mutableListOf(ACommandControl("x", "按钮", actions))
        val frozen = ACommandProtocol.snapshot(request.copy(apps = apps, screen = ACommandScreen("com.example.front", controls)))
        apps.clear(); actions.clear(); controls.clear()
        assertEquals(1, frozen.apps.size); assertEquals(listOf(ACommandAction.CLICK), frozen.screen!!.controls.single().actions)
    }
    @Test fun largeRealCatalogFitsProxyWithoutChangingQuestionContactsOrPage() {
        val large = request.copy(apps = (1..500).map { ACommandApp("com.example.app$it", "字".repeat(200)) })
        assertThrows(IllegalArgumentException::class.java) { ACommandProtocol.encode(large) }
        val fitted = ACommandProtocol.fitForProxy(large)
        assertTrue(fitted.apps.size < large.apps.size)
        assertTrue(large.apps.containsAll(fitted.apps))
        assertEquals(large.utterance, fitted.utterance)
        assertEquals(large.contacts, fitted.contacts)
        assertEquals(large.screen, fitted.screen)
        assertTrue(ACommandProtocol.encode(fitted).toByteArray(Charsets.UTF_8).size <= 120 * 1024)
        assertThrows(IllegalArgumentException::class.java) { ACommandProtocol.fitForProxy(request.copy(utterance = "字".repeat(2001))) }
    }

    @Test fun endpointNormalizesRootAndBProxyRoutesWithoutAcceptingQueriesOrUnrelatedPaths() {
        for (path in listOf("", "/", "/v1", "/v1/", "/v1/chat/completions", "/chat/completions/", "/v1/commands/interpret"))
            assertEquals("https://example.test:8443/v1/commands/interpret", ACommandProtocol.endpoint("https://example.test:8443$path").toString())
        assertEquals("http://192.168.1.17:8001/v1/commands/interpret", ACommandProtocol.endpoint("http://192.168.1.17:8001", true, true).toString())
        for (url in listOf("https://example.test/docs", "https://example.test/upload", "https://example.test/path/../v1", "https://example.test/%76%31",
            "https://user:pass@example.test", "https://example.test?token=secret", "https://example.test#x", "https://example.test:0", "http://8.8.8.8", "http://example.test", "bad-url"))
            assertThrows(IllegalArgumentException::class.java) { ACommandProtocol.endpoint(url, true, true) }
        assertThrows(IllegalArgumentException::class.java) { ACommandProtocol.endpoint("http://192.168.1.17", true, false) }
        assertThrows(IllegalArgumentException::class.java) { ACommandProtocol.endpoint("http://192.168.1.17", false, true) }
    }

    @Test fun serverTokenRejectsProviderKeysAndUnsafeHeadersWithoutIncludingTheirValues() {
        for (token in listOf("", " ", "sk-private-example", "SK-private-example", "cloud token", "cloud\r\nvalue", "密钥", "x\u200by")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { ACommandProtocol.validateToken(token) }
            if (token.isNotBlank()) assertFalse(failure.message.orEmpty().contains(token))
        }
        ACommandProtocol.validateToken("own-server_opaque.token-123")
    }
}
