package cn.seniorvoice.demo.appcontrol

import java.text.Normalizer
import java.util.Locale

/** A label and its actual launcher activity, discovered from Android rather than a command list. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val launcherActivity: String = ""
)

/** Exact label resolution; independent of Android so ambiguity and normalization can be tested. */
object InstalledAppMatcher {
    sealed class Result {
        data class Matched(val app: InstalledApp, val usedAlias: Boolean = false) : Result()
        data class Ambiguous(val candidates: List<InstalledApp>) : Result()
        object NotInstalled : Result()
    }

    // Preserve meaningful punctuation in real names: "C++" must not become "C".
    private val ignoredCharacters = Regex("[\\s\\p{Z}\\p{Cf}]+")
    private val qqNames = setOf("qq", "腾讯qq")
    private val qqSpokenNames = qqNames + setOf("扣扣", "腾讯扣扣")
    private val courtesyEndings = listOf("一下", "吧", "呀", "啊", "呗", "哦")

    fun normalizeName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(ignoredCharacters, "")

    fun resolve(requestedName: String, installedApps: List<InstalledApp>): Result {
        val requested = normalizeName(requestedName)
        if (requested.isBlank()) return Result.NotInstalled
        val apps = installedApps.map { it.copy(packageName = it.packageName.trim()) }
            .filter { it.packageName.isNotEmpty() && normalizeName(it.label).isNotEmpty() }
            .sortedWith(compareBy<InstalledApp>({ it.packageName }, { it.label }, { it.launcherActivity }))

        fun resultFor(matches: List<InstalledApp>, alias: Boolean = false): Result? {
            // Several launcher activities for the same package are still only one app. Keep all
            // reported labels available for matching and deduplicate only the matched candidates.
            val uniquePackages = matches.distinctBy { it.packageName }
            return when (uniquePackages.size) {
                0 -> null
                1 -> Result.Matched(uniquePackages.single(), alias)
                else -> Result.Ambiguous(uniquePackages)
            }
        }

        val names = mutableListOf(requested)
        var politeName = requested
        repeat(4) {
            val ending = courtesyEndings.firstOrNull { politeName.endsWith(it) }
            val shorter = ending?.let { politeName.dropLast(it.length) }.orEmpty()
            // Do not turn a short proper name such as “贴吧” into a different one-character
            // app “贴”. One-character app labels still match when spoken without a suffix.
            if (shorter.codePointCount(0, shorter.length) >= 2) {
                names += shorter
                politeName = shorter
            }
        }

        // Prefer the complete real app name to all courtesy endings and spoken aliases.
        // In particular “贴吧” and a genuinely installed app named “扣扣” retain their own names.
        for (name in names) {
            resultFor(apps.filter { normalizeName(it.label) == name })?.let { return it }
        }
        if (names.any { it in qqSpokenNames }) {
            resultFor(apps.filter { normalizeName(it.label) in qqNames }, alias = true)?.let { return it }
        }
        return Result.NotInstalled
    }
}
