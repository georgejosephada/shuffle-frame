package com.gjnet.shuffleframe

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** One-time permissions frame mode and background sync depend on, and how to grant each. */
object Setup {
    class Step(val title: Int, val intent: Intent)

    fun missing(ctx: Context): List<Step> {
        val pkg = Uri.parse("package:${ctx.packageName}")
        val steps = ArrayList<Step>()
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            steps += Step(
                R.string.setup_notifications,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
            )
        }
        if (Prefs(ctx).frameMode && Build.VERSION.SDK_INT >= 34 &&
            !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            steps += Step(R.string.setup_full_screen, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
        }
        if (BuildConfig.UPDATE_REPO.isNotEmpty() && !ctx.packageManager.canRequestPackageInstalls()) {
            steps += Step(R.string.setup_updates, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg))
        }
        if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) {
            @Suppress("BatteryLife")
            steps += Step(R.string.setup_battery, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
        }
        return steps
    }
}
