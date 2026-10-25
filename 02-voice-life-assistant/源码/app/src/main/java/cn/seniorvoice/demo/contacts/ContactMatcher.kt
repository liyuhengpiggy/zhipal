package cn.seniorvoice.demo.contacts

import java.text.Normalizer
import java.util.Locale

/**
 * Resolves only saved contacts. This class does not dial or parse a whole command.
 * A [Result.Homophone] is a suggestion for explicit user confirmation, not consent to call.
 */
class ContactMatcher(private val pronouncer: Pronouncer = Pronouncer { null }) {
    data class Contact(val id: String, val name: String, val phoneNumbers: List<String>)

    /** Return one unambiguous Mandarin syllable, or null to keep this Han character literal. */
    fun interface Pronouncer {
        fun syllable(hanCodePoint: Int): String?
    }

    enum class Ambiguity { MULTIPLE_CONTACTS, MULTIPLE_PHONE_NUMBERS }

    sealed class Result {
        data class Exact(val contact: Contact, val phoneNumber: String) : Result()
        data class Homophone(val contact: Contact, val phoneNumber: String) : Result()
        data class Ambiguous(val candidates: List<Contact>, val reason: Ambiguity) : Result()
        object NotFound : Result()
    }

    fun match(spokenName: String, contacts: List<Contact>): Result {
        val name = normalizeName(spokenName)
        if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_NAME_CODE_POINTS) return Result.NotFound

        val saved = contacts.mapNotNull { contact ->
            val key = normalizeName(contact.name)
            if (key.isEmpty() || key.codePointCount(0, key.length) > MAX_NAME_CODE_POINTS) null
            else PreparedContact(contact.copy(phoneNumbers = contact.phoneNumbers.filter { it.isNotBlank() }
                .map { normalizePhoneNumber(it) ?: it.trim() }.distinct().sorted()), key)
        }.distinct()

        // An exact name, even one with an invalid/missing number, must never fall through
        // to another person's similar-sounding name.
        val exact = saved.filter { it.name == name }
        if (exact.isNotEmpty()) return resolve(exact, exact = true)

        val spokenKey = phoneticKey(name)
        val candidates = saved.filter { phoneticKey(it.name) == spokenKey }
        return resolve(candidates, exact = false)
    }

    private fun resolve(candidates: List<PreparedContact>, exact: Boolean): Result {
        if (candidates.isEmpty()) return Result.NotFound
        if (candidates.size > 1) return Result.Ambiguous(candidates.map { it.contact }, Ambiguity.MULTIPLE_CONTACTS)
        val contact = candidates.single().contact
        if (contact.phoneNumbers.isEmpty()) return Result.NotFound
        if (contact.phoneNumbers.size > 1) return Result.Ambiguous(listOf(contact), Ambiguity.MULTIPLE_PHONE_NUMBERS)
        val number = normalizePhoneNumber(contact.phoneNumbers.single()) ?: return Result.NotFound
        return if (exact) Result.Exact(contact, number) else Result.Homophone(contact, number)
    }

    private fun phoneticKey(name: String): List<Token> {
        val tokens = ArrayList<Token>()
        var offset = 0
        while (offset < name.length) {
            val codePoint = name.codePointAt(offset)
            val syllable = if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                try { normalizePinyinSyllable(pronouncer.syllable(codePoint)) }
                catch (_: RuntimeException) { null }
            } else null
            // Token boundaries distinguish 西安 (xi/an) from 先 (xian). Literal tokens
            // also prevent Chinese syllables from matching Latin letters or digits.
            tokens.add(if (syllable == null) Token.Literal(codePoint) else Token.HanSyllable(syllable))
            offset += Character.charCount(codePoint)
        }
        return tokens
    }

    private data class PreparedContact(val contact: Contact, val name: String)
    private sealed class Token {
        data class Literal(val codePoint: Int) : Token()
        data class HanSyllable(val syllable: String) : Token()
    }

    companion object {
        private const val MAX_NAME_CODE_POINTS = 40
        private val phoneFormat = Regex("\\+?[0-9]{7,15}")
        private val phoneSeparators = Regex("[\\s\\p{Z}()（）-]")

        private fun normalizeName(raw: String): String = buildString {
            val text = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            var offset = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint) &&
                    codePoint != 0x200B && codePoint != 0xFEFF) appendCodePoint(codePoint)
                offset += Character.charCount(codePoint)
            }
        }

        internal fun normalizePhoneNumber(raw: String): String? {
            val number = Normalizer.normalize(raw, Normalizer.Form.NFKC).replace(phoneSeparators, "")
            return number.takeIf { phoneFormat.matches(it) }
        }

        /** Removes Mandarin tone marks, retaining ü as v so 吕 and 鲁 stay different. */
        internal fun normalizePinyinSyllable(raw: String?): String? {
            if (raw == null) return null
            val syllable = Normalizer.normalize(raw.trim().lowercase(Locale.ROOT).replace("u:", "v"), Normalizer.Form.NFD)
            val result = StringBuilder()
            syllable.forEachIndexed { index, character ->
                when {
                    character in 'a'..'z' -> result.append(character)
                    character == '\u0308' && result.isNotEmpty() && result.last() == 'u' -> result.setCharAt(result.lastIndex, 'v')
                    character == '\u0300' || character == '\u0301' || character == '\u0304' || character == '\u030C' -> Unit
                    character in '1'..'5' && index == syllable.lastIndex -> Unit
                    else -> return null
                }
            }
            return result.toString().takeIf { it.length in 1..6 }
        }
    }
}

/**
 * ICU Han-Latin gives one default reading; it does not report polyphonic ambiguity.
 * Only this conservative set of common name/kinship characters may be substituted.
 * Other characters remain literal, including 重/行/乐/曾/单/解/长/朝/柏/区/仇/会/绿.
 * This is intentionally not a complete Chinese pronunciation dictionary.
 */
class ConservativeHanPronouncer(private val transliterate: (String) -> String?) : ContactMatcher.Pronouncer {
    private val cache = HashMap<Int, String?>()

    @Synchronized
    override fun syllable(hanCodePoint: Int): String? {
        if (hanCodePoint !in safeCharacters) return null
        if (cache.containsKey(hanCodePoint)) return cache[hanCodePoint]
        val result = try {
            ContactMatcher.normalizePinyinSyllable(transliterate(String(Character.toChars(hanCodePoint))))
        } catch (_: RuntimeException) { null }
        cache[hanCodePoint] = result
        return result
    }

    companion object {
        private val safeCharacters = (
            "乔喬侨僑桥橋瞧荞蕎王汪旺张張章彰李里理礼禮莉丽麗力立利俐黎梨" +
            "刘劉留流柳陈陳晨辰臣宸林琳霖杨楊扬揚阳陽洋羊赵趙照兆昭钊釗黄黃皇凰煌" +
            "周州舟洲吴吳徐许許旭序绪緒孙孫荪蓀马馬玛瑪胡湖虎郭国國果高糕羔罗羅萝蘿洛骆駱珞" +
            "郑鄭正政梁良亮谢謝榭唐堂棠糖韩韓涵寒曹草魏卫衛伟偉维維薇威巍玮瑋袁元园園圆圓源缘緣远遠" +
            "邓鄧登灯燈肖萧蕭晓曉小筱笑田甜天添恬董冬东東栋棟潘攀盼蔡彩才材财財蒋蔣江姜疆" +
            "于宇羽雨余餘玉钰鈺遇杜丁叮姚摇搖吕呂旅履卢盧芦蘆鲁魯路璐苏蘇素肃肅范範凡帆樊" +
            "金今津锦錦夏霞傅付富廖料白百邹鄒熊雄孟梦夢秦琴勤阎閻严嚴颜顏薛雪学學段锻鍛" +
            "雷蕾贺賀荷顾顧古故毛茅茂郝浩皓昊豪龙龍隆邵绍紹少龚龔宫宮公赖賴来來易仪儀怡宜依义義毅亿億奕亦艺藝懿" +
            "邱丘秋尹音茵银銀仁人任侯候后後厚钟鍾锺鐘仲中众眾忠荣榮容蓉融向香湘祥翔安鞍岸全泉权權" +
            "程成城诚誠承丞婷庭廷亭妮倪尼静靜靖婧晶菁娟鹃鵑敏闵閔英莺鶯鹰鷹映颖穎影菲飞飛非" +
            "芳方房芬分萍平娥额額俄洁潔杰傑捷丹但兰蘭博波播鹏鵬朋彭斌彬滨濱宾賓锋鋒峰丰豐凤鳳" +
            "松嵩宋送俊竣君军軍骏駿铭銘明名鸣鳴志智芝之知双雙水" +
            "爸妈媽媽爹爷爺奶外婆哥姐弟妹儿兒女子叔姑姨舅父母丈妻老宝寶贝貝心欣新鑫星信馨斯丝絲恩美梅眉玫花华華慧惠"
        ).map { it.code }.toSet()
    }
}
