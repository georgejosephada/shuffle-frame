package com.gjnet.shuffleframe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Heavy syncs (many downloads, video conversions) run much faster with the screen on: with it
 * off, Android throttles background work. While such a sync runs on the charger this keeps the
 * screen from turning off (dimmed), like the developer option "Stay awake while charging" but only
 * while there is work. With frame mode about to take over it holds nothing: the slideshow keeps
 * the screen on itself, and a held screen would stop it from ever starting. Off the charger it
 * asks to plug in, once per kind of work until the next plug-in (or app restart).
 */
object KeepAwake {
    enum class Work { DOWNLOAD, CONVERT }

    private const val CHANNEL = "plug_in"
    private const val NOTIFICATION_ID = 40
    private const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L

    private var lock: PowerManager.WakeLock? = null
    private var receiver: BroadcastReceiver? = null
    /** Kinds of work already asked about since the last plug-in. */
    private val asked = HashSet<Work>()

    /** Called when a heavy sync starts; [end] must follow. */
    @Synchronized
    fun begin(ctx: Context) {
        val app = ctx.applicationContext
        if (receiver == null) {
            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = update(
                    app,
                    // The sticky battery state can lag behind these two.
                    when (i.action) {
                        Intent.ACTION_POWER_CONNECTED -> true
                        Intent.ACTION_POWER_DISCONNECTED -> false
                        else -> FrameConditions.pluggedIn(app)
                    },
                )
            }.also {
                // Anything that changes whether the frame would show, or whether it's plugged in.
                val filter = FrameConditions.changes().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                ContextCompat.registerReceiver(app, it, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
        update(app, FrameConditions.pluggedIn(app))
    }

    @Synchronized
    fun end(ctx: Context) {
        receiver?.let { runCatching { ctx.applicationContext.unregisterReceiver(it) } }
        receiver = null
        release()
    }

    /** The tablet was plugged in: a new session for the prompts, and their notifications go. */
    @Synchronized
    fun onPluggedIn(ctx: Context) {
        asked.clear()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Work.entries.forEach { nm.cancel(NOTIFICATION_ID + it.ordinal) }
    }

    /** Frame mode will put the slideshow up when the screen times out (which keeps it on). */
    private fun frameTakesOver(ctx: Context) =
        Prefs(ctx).frameMode && !FrameService.isSnoozed(ctx) && FrameConditions.allowed(ctx)

    @Synchronized
    private fun update(ctx: Context, plugged: Boolean) {
        if (plugged) onPluggedIn(ctx)
        if (plugged && Prefs(ctx).keepAwakeCharging && !frameTakesOver(ctx)) {
            if (lock?.isHeld != true) {
                @Suppress("DEPRECATION")  // FLAG_KEEP_SCREEN_ON needs a window; a sync has none
                lock = ctx.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "ShuffleFrame:sync")
                    .apply { setReferenceCounted(false); acquire(MAX_HOLD_MS) }
            }
        } else release()
    }

    private fun release() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    /** Asks to plug in for [count] items of [work], unless on the charger or already asked. */
    fun askToPlugIn(ctx: Context, work: Work, count: Int) {
        val app = ctx.applicationContext
        if (count <= 0 || !Prefs(app).keepAwakeCharging || FrameConditions.pluggedIn(app)) return
        synchronized(this) { if (!asked.add(work)) return }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.plug_in_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val text = when (work) {
            Work.DOWNLOAD -> app.resources.getQuantityString(R.plurals.plug_in_download, count, count)
            Work.CONVERT -> app.resources.getQuantityString(
                if (Prefs(app).convertOnlyCharging) R.plurals.plug_in_convert_waiting else R.plurals.plug_in_convert, count, count
            )
        }
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(app.getString(R.string.plug_in_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID + work.ordinal, n) }  // notifications may be off
    }
}
