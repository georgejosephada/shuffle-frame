package com.gjnet.shuffleframe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Frame mode: whenever the screen turns off and the user's conditions allow, turns it back on
 * with the slideshow (over the lock screen), like a photo frame. Android's own screen saver
 * can't do this here: OxygenOS hides its settings and never starts it on battery.
 *
 * Stays quiet after the user closes the slideshow or switches the screen off while it's
 * showing, until the tablet is next unlocked.
 */
class FrameService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var waitedMs = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(launchWhenLocked)
                    // Power pressed while a slideshow was showing: the user wants the screen off.
                    if (SlideshowActivity.visible > 0) setSnoozed(context, true)
                    else {
                        waitedMs = 0
                        launchWhenLocked.run()
                    }
                }
                Intent.ACTION_SCREEN_ON -> handler.removeCallbacks(launchWhenLocked)
                Intent.ACTION_USER_PRESENT -> setSnoozed(context, false)
                Intent.ACTION_POWER_CONNECTED -> KeepAwake.onPluggedIn(context)
            }
        }
    }

    /**
     * Shows the frame only once a secure tablet has actually locked, so the slideshow (and its
     * buttons) never sits on top of an unlocked device during the lock delay.
     */
    private val launchWhenLocked = object : Runnable {
        override fun run() {
            val km = getSystemService(KeyguardManager::class.java)
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            if (interactive) return
            if (km.isDeviceSecure && !km.isDeviceLocked) {
                if (waitedMs < MAX_LOCK_WAIT_MS) {
                    waitedMs += 1000
                    handler.postDelayed(this, 1000)
                }
                return
            }
            maybeLaunch()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, getString(R.string.frame_channel), NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LAUNCH, getString(R.string.frame_launch_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null); enableVibration(false) }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_frame)
            .setContentTitle(getString(R.string.frame_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            // Restarted from the background without the battery exemption; the next app start,
            // sync or boot starts it again.
            stopSelf()
            return
        }
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        if (resumeAfterUpdate) {
            resumeAfterUpdate = false
            maybeLaunch()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!registered || !Prefs(this).frameMode) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(launchWhenLocked)
        if (registered) unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun maybeLaunch() {
        if (!Prefs(this).frameMode || isSnoozed(this) || !FrameConditions.allowed(this)) return
        if (SlideshowActivity.visible > 0) return
        if (Store.playable().isEmpty()) return
        // A full-screen notification is how Android lets a background app turn the screen on
        // with an activity (the same mechanism alarm clocks use).
        val show = PendingIntent.getActivity(
            this, 1,
            Intent(this, FrameSlideshowActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_LAUNCH)
            .setSmallIcon(R.drawable.ic_frame)
            .setContentTitle(getString(R.string.frame_launch_notification))
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(show, true)
            .setTimeoutAfter(10_000)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(LAUNCH_ID, n)
    }

    companion object {
        private const val CHANNEL_SERVICE = "frame"
        private const val CHANNEL_LAUNCH = "frame_launch"
        private const val NOTIFICATION_ID = 2
        const val LAUNCH_ID = 3
        /** Set when an update replaced the app while a frame was showing. */
        @Volatile var resumeAfterUpdate = false
        /** Longest lock delay to wait for before giving up on this screen-off. */
        private const val MAX_LOCK_WAIT_MS = 30 * 60_000L
        private const val KEY_SNOOZED = "frame_snoozed"

        /** Starts or stops the service to match the "frame mode" setting. Safe to call often. */
        fun sync(ctx: Context) {
            val intent = Intent(ctx, FrameService::class.java)
            if (Prefs(ctx).frameMode) {
                try {
                    ContextCompat.startForegroundService(ctx, intent)
                } catch (e: Exception) {
                    // Background start not allowed right now; the next app launch or boot retries.
                }
            } else ctx.stopService(intent)
        }

        fun setSnoozed(ctx: Context, snoozed: Boolean) {
            PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(KEY_SNOOZED, snoozed).apply()
        }

        fun isSnoozed(ctx: Context) =
            PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_SNOOZED, false)
    }
}
