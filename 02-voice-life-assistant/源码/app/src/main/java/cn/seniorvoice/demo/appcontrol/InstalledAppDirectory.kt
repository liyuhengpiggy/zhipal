package cn.seniorvoice.demo.appcontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/**
 * Discovers only apps with an enabled, exported MAIN/LAUNCHER activity.
 *
 * Android 11+ needs the matching MAIN/LAUNCHER intent in manifest <queries>. No broad
 * QUERY_ALL_PACKAGES permission or hand-maintained package-name table is necessary.
 */
class InstalledAppDirectory(context: Context) {
    private val packageManager = context.applicationContext.packageManager

    /** Query on demand, so newly installed or removed apps do not need an app restart. */
    fun listLaunchableApps(): List<InstalledApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return queryLauncherActivities(query).mapNotNull { resolved ->
            val activity = resolved.activityInfo ?: return@mapNotNull null
            if (!activity.enabled || !activity.exported || !activity.applicationInfo.enabled) {
                return@mapNotNull null
            }
            // This is the name visible in the system launcher. loadLabel already falls back
            // to the application label; assigning the app label to every activity could
            // accidentally open a secondary launcher (for example a scanner shortcut).
            val label = runCatching { resolved.loadLabel(packageManager)?.toString()?.trim() }
                .getOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            InstalledApp(activity.packageName, label, activity.name)
        }.distinctBy { Triple(it.packageName, InstalledAppMatcher.normalizeName(it.label), it.launcherActivity) }
            .sortedWith(compareBy<InstalledApp>({ it.label }, { it.packageName }, { it.launcherActivity }))
    }

    fun resolve(requestedName: String): InstalledAppMatcher.Result =
        InstalledAppMatcher.resolve(requestedName, listLaunchableApps())

    /**
     * Construct an explicit launcher intent for a Matched result. The caller handles launch
     * failure (for example, an app uninstalled between discovery and startActivity).
     */
    fun launchIntent(app: InstalledApp): Intent? {
        if (app.packageName.isBlank() || app.launcherActivity.isBlank()) return null
        val activity = if (app.launcherActivity.startsWith(".")) {
            app.packageName + app.launcherActivity
        } else {
            app.launcherActivity
        }
        return Intent.makeMainActivity(ComponentName(app.packageName, activity))
    }

    @Suppress("DEPRECATION")
    private fun queryLauncherActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
}
