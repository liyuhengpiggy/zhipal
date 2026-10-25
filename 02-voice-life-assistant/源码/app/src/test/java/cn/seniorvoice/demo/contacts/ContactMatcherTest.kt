package cn.seniorvoice.demo.contacts

import cn.seniorvoice.demo.CommandParser
import org.junit.Assert.*
import org.junit.Test

class ContactMatcherTest {
    private val readings = mapOf(
        '乔' to "qiáo", '瞧' to "qiáo", '桥' to "qiáo", '喬' to "qiáo", '侨' to "qiáo",
        '王' to "wáng", '汪' to "wāng", '张' to "zhāng", '章' to "zhāng",
        '李' to "lǐ", '里' to "lǐ", '莉' to "lì", '林' to "lín", '琳' to "lín",
        '小' to "xiǎo", '晓' to "xiǎo", '吕' to "lǚ", '鲁' to "lǔ",
        '陈' to "chén", '晨' to "chén", '二' to "èr", '西' to "xī", '安' to "ān", '先' to "xiān"
    )
    private val matcher = ContactMatcher(ContactMatcher.Pronouncer { readings[it.toChar()] })
    private fun contact(name: String, id: String = name, numbers: List<String> = listOf("13800000000")) =
        ContactMatcher.Contact(id, name, numbers)

    @Test fun parserHandsTheMisrecognizedNameToTheMatcherWithoutChangingTheButton() {
        val parsed = CommandParser.parse("给瞧瞧打电话") as CommandParser.Command.Call
        val saved = contact("乔乔")
        assertEquals(ContactMatcher.Result.Homophone(saved, "13800000000"), matcher.match(parsed.name, listOf(saved)))
    }

    @Test fun exactContactWinsOverAHomophone() {
        val exact = contact("瞧瞧", numbers = listOf("13900000000"))
        assertEquals(ContactMatcher.Result.Exact(exact, "13900000000"), matcher.match("瞧瞧", listOf(contact("乔乔"), exact)))
    }

    @Test fun tonesMayDifferButAllSyllablesMustMatch() {
        for ((heard, savedName) in listOf("汪莉" to "王李", "章晓" to "张小", "晨琳" to "陈林")) {
            assertTrue("$heard / $savedName", matcher.match(heard, listOf(contact(savedName))) is ContactMatcher.Result.Homophone)
        }
    }

    @Test fun multipleHomophonePeopleAreAmbiguousEvenIfTheirNumbersAreTheSame() {
        val saved = listOf(contact("乔乔"), contact("桥桥"))
        val result = matcher.match("瞧瞧", saved) as ContactMatcher.Result.Ambiguous
        assertEquals(ContactMatcher.Ambiguity.MULTIPLE_CONTACTS, result.reason)
        assertEquals(saved, result.candidates)
    }

    @Test fun multipleExactPeopleAreNotMergedBySharedNameOrPhone() {
        val saved = listOf(contact("乔乔", "person-one"), contact("乔乔", "person-two"))
        assertTrue(matcher.match("乔乔", saved) is ContactMatcher.Result.Ambiguous)
    }

    @Test fun onePersonWithDifferentPhoneNumbersRequiresAnExplicitNumberChoice() {
        val saved = contact("乔乔", numbers = listOf("13800000000", "13900000000"))
        for (name in listOf("乔乔", "瞧瞧")) {
            val result = matcher.match(name, listOf(saved)) as ContactMatcher.Result.Ambiguous
            assertEquals(ContactMatcher.Ambiguity.MULTIPLE_PHONE_NUMBERS, result.reason)
        }
    }

    @Test fun formattingDuplicatesOfOneNumberAreDeduplicatedWithoutDroppingCountryCodes() {
        val saved = contact("乔乔", numbers = listOf("138 0000 0000", "(138)0000-0000", "１３８００００００００"))
        val result = matcher.match("瞧瞧", listOf(saved)) as ContactMatcher.Result.Homophone
        assertEquals(listOf("13800000000"), result.contact.phoneNumbers)
        assertEquals("13800000000", result.phoneNumber)
        assertTrue(matcher.match("乔乔", listOf(saved.copy(phoneNumbers = listOf("13800000000", "+8613800000000")))) is ContactMatcher.Result.Ambiguous)
    }

    @Test fun exactNameWithInvalidNumberCannotFallThroughToAnotherPerson() {
        val invalid = contact("瞧瞧", numbers = listOf("13800000000;123"))
        assertEquals(ContactMatcher.Result.NotFound, matcher.match("瞧瞧", listOf(invalid, contact("乔乔"))))
    }

    @Test fun anInvalidStoredAlternativeDoesNotSilentlyChooseTheOtherNumber() {
        val saved = contact("乔乔", numbers = listOf("13800000000", "13900000000;123"))
        assertTrue(matcher.match("乔乔", listOf(saved)) is ContactMatcher.Result.Ambiguous)
        assertTrue(matcher.match("瞧瞧", listOf(saved)) is ContactMatcher.Result.Ambiguous)
    }

    @Test fun partialNamesAndArbitraryEditsDoNotSelectContacts() {
        for (heard in listOf("乔", "瞧瞧瞧", "瞧王", "乔乔姐", "给瞧瞧打电话", "乔瞧吗", "")) {
            assertEquals(heard, ContactMatcher.Result.NotFound, matcher.match(heard, listOf(contact("乔乔"))))
        }
    }

    @Test fun syllableBoundariesArePreserved() {
        assertEquals(ContactMatcher.Result.NotFound, matcher.match("西安", listOf(contact("先"))))
    }

    @Test fun latinLettersAndChineseSyllablesRemainDifferentTokenTypes() {
        val saved = contact("乔A2")
        assertTrue(matcher.match("瞧ａ２", listOf(saved)) is ContactMatcher.Result.Homophone)
        for (heard in listOf("qiaoA2", "瞧A二", "瞧B2", "瞧A3")) {
            assertEquals(heard, ContactMatcher.Result.NotFound, matcher.match(heard, listOf(saved)))
        }
        assertEquals(ContactMatcher.Result.NotFound, matcher.match("琳", listOf(contact("lin"))))
    }

    @Test fun umlautDoesNotCollapseIntoPlainU() {
        assertEquals(ContactMatcher.Result.NotFound, matcher.match("鲁乔", listOf(contact("吕乔"))))
        assertEquals("nv", ContactMatcher.normalizePinyinSyllable("nǚ"))
        assertEquals("lv", ContactMatcher.normalizePinyinSyllable("lǜ"))
        assertEquals("lv", ContactMatcher.normalizePinyinSyllable("lu:4"))
        assertEquals("lu", ContactMatcher.normalizePinyinSyllable("lǔ"))
    }

    @Test fun conservativePronouncerKeepsPolyphonicAndUnknownCharactersLiteral() {
        val defaultReadings = readings + mapOf('重' to "zhòng", '众' to "zhòng", '乐' to "lè", '勒' to "lè", '㐀' to "qiū")
        val pronouncer = ConservativeHanPronouncer { text -> defaultReadings[text.single()] }
        val safeMatcher = ContactMatcher(pronouncer)
        for (han in listOf('重', '行', '乐', '曾', '单', '解', '长', '朝', '柏', '区', '仇', '会', '绿', '㐀')) {
            assertNull(han.toString(), pronouncer.syllable(han.code))
        }
        assertEquals(ContactMatcher.Result.NotFound, safeMatcher.match("众乔", listOf(contact("重乔"))))
        assertEquals(ContactMatcher.Result.NotFound, safeMatcher.match("勒乔", listOf(contact("乐乔"))))
        assertTrue(safeMatcher.match("重瞧", listOf(contact("重乔"))) is ContactMatcher.Result.Homophone)
        assertTrue(safeMatcher.match("重乔", listOf(contact("重乔"))) is ContactMatcher.Result.Exact)
    }

    @Test fun traditionalVariantsRequireTheSameApprovedSyllableSequence() {
        assertTrue(matcher.match("喬喬", listOf(contact("乔乔"))) is ContactMatcher.Result.Homophone)
        val literalOnly = ContactMatcher()
        assertEquals(ContactMatcher.Result.NotFound, literalOnly.match("喬喬", listOf(contact("乔乔"))))
    }

    @Test fun unsupportedPlatformAndFailedTransliterationRetainExactMatching() {
        val literalOnly = ContactMatcher()
        val saved = contact("乔乔")
        assertTrue(literalOnly.match("乔乔", listOf(saved)) is ContactMatcher.Result.Exact)
        assertEquals(ContactMatcher.Result.NotFound, literalOnly.match("瞧瞧", listOf(saved)))
        val failing = ContactMatcher(ConservativeHanPronouncer { throw IllegalStateException("ICU unavailable") })
        assertEquals(ContactMatcher.Result.NotFound, failing.match("瞧瞧", listOf(saved)))
        assertTrue(failing.match("乔乔", listOf(saved)) is ContactMatcher.Result.Exact)
    }

    @Test fun transliterationMustReturnExactlyOneSyllable() {
        for (reading in listOf("", "qiao qiao", "qiao-qiao", "乔", "qiao7", "qi@o")) {
            assertNull(reading, ContactMatcher.normalizePinyinSyllable(reading))
        }
    }

    @Test fun contactNormalizationDoesNotEraseMeaningfulPunctuation() {
        val saved = contact("Anna")
        assertTrue(matcher.match(" Ａｎｎａ　", listOf(saved)) is ContactMatcher.Result.Exact)
        for (heard in listOf("An-na", "Ann", "Annab", "Anna?")) {
            assertEquals(ContactMatcher.Result.NotFound, matcher.match(heard, listOf(saved)))
        }
    }

    @Test fun repeatedIdenticalRecordDoesNotCreateFalseAmbiguity() {
        val saved = contact("乔乔")
        assertEquals(ContactMatcher.Result.Exact(saved, "13800000000"), matcher.match("乔乔", listOf(saved, saved)))
    }
}
