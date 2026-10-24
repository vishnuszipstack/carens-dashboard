package dev.carens.dashboard.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable?)

/** Installed apps for the launcher grid, plus common car shortcuts. */
class AppsRepository(private val context: Context) {

    suspend fun installed(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        list.map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { pkg -> app(pkg) }
            .sortedBy { it.label.lowercase() }
    }

    fun app(pkg: String): LaunchableApp {
        val pm = context.packageManager
        val label = runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
        return LaunchableApp(pkg, label, runCatching { pm.getApplicationIcon(pkg) }.getOrNull())
    }

    fun isInstalled(pkg: String): Boolean = context.packageManager.getLaunchIntentForPackage(pkg) != null

    fun launch(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Google Assistant / the default voice assistant. */
    fun assistant(): Boolean {
        val intents = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND),
            Intent("android.intent.action.ASSIST"),
            Intent(Intent.ACTION_ASSIST),
        )
        return intents.any { i ->
            runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }

    fun dialer(): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    companion object {
        /** Sensible first-run dock; only the ones that are installed are shown. */
        val DEFAULT_DOCK = listOf(
            "com.google.android.apps.maps",
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.google.android.youtube",
            "com.whatsapp",
            "com.google.android.dialer",
        )
    }
}
