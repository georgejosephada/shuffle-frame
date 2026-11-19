package com.gjnet.shuffleframe

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import java.time.LocalTime

/** The user's rules for when the slideshow may start (and keep running) by itself. */
object FrameConditions {
    /** Whether the tablet is on a charger (even when full and no longer charging). */
    fun pluggedIn(ctx: Context): Boolean =
        ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false

    fun allowed(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        // The sticky battery broadcast is the system's own view of level and power source.
        val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (status != null) {
            val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val raw = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val level = if (raw >= 0 && scale > 0) raw * 100 / scale else -1
            if (!plugged && level in 0 until prefs.frameMinBattery) return false
        }
        if (prefs.frameSkipPowerSaver && ctx.getSystemService(PowerManager::class.java).isPowerSaveMode) return false
        if (!dayTime(prefs)) return false
        if (prefs.frameSkipAirplane &&
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        ) return false
        return true
    }

    /** Outside the night-off hours (if that's on). Hours may run past midnight; equal means all day. */
    fun dayTime(prefs: Prefs, now: LocalTime = LocalTime.now()): Boolean =
        !prefs.frameNightOff || dayTime(prefs.frameDayStart, prefs.frameDayEnd, now)

    /** Whether [now] is from [start] up to [end] (minutes into the day). */
    fun dayTime(start: Int, end: Int, now: LocalTime): Boolean {
        val m = now.hour * 60 + now.minute
        return when {
            start == end -> true
            start < end -> m in start until end
            else -> m >= start || m < end
        }
    }

    /** Broadcasts after which [allowed] may have changed. */
    fun changes() = IntentFilter().apply {
        addAction(Intent.ACTION_BATTERY_CHANGED)
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
        addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        // The night-off hours (each minute, and clock or time zone changes).
        addAction(Intent.ACTION_TIME_TICK)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }
}
