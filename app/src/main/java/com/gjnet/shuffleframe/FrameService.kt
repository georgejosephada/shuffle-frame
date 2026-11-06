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
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Frame mode: whenever the screen turns off and the user's conditions allow, turns it back on
 * with the slideshow (over the lock screen), like a photo frame. Android's own screen saver
 * can't do this here: OxygenOS hides its settings and never starts it on battery.
 *
 * Switching the screen off over the slideshow turns it off for now; waking the tablet brings
 * the slideshow back over the lock screen. Closing it with ✕ (or Pause in the notification)
 * keeps frame mode quiet until the next unlock. The ongoing notification, also on the lock
 * screen, starts the slideshow when tapped.
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
                    handler.removeCallbacks(launchOnWake)
                    // Power pressed while a slideshow was showing: the user wants the screen off.
                    val byUser = SlideshowActivity.visible > 0 ||
                        SystemClock.elapsedRealtime() - SlideshowActivity.turnedOffAt < 5_000
                    if (!byUser) {
                        waitedMs = 0
                        launchWhenLocked.run()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    handler.removeCallbacks(launchWhenLocked)
                    // Woken on the lock screen: back to the photos (unlocking closes them). After
                    // a moment, so whatever woke it (an alarm, a call, face unlock) shows first.
                    handler.removeCallbacks(launchOnWake)
                    handler.postDelayed(launchOnWake, WAKE_DELAY_MS)
                }
                Intent.ACTION_USER_PRESENT -> {
                    handler.removeCallbacks(launchOnWake)
                    getSystemService(NotificationManager::class.java).cancel(LAUNCH_ID)
                    setSnoozed(context, false)
                }
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

    private val launchOnWake = Runnable {
        val km = getSystemService(KeyguardManager::class.java)
        val locked = if (km.isDeviceSecure) km.isDeviceLocked else km.isKeyguardLocked
        if (locked && getSystemService(PowerManager::class.java).isInteractive && !somethingElseUp()) maybeLaunch()
    }

    /** A call, or an alarm or ringtone sounding: that app's screen is what the user needs. */
    private fun somethingElseUp(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        if (am.mode != AudioManager.MODE_NORMAL) return true
        val urgent = setOf(
            AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
        )
        return am.activePlaybackConfigurations.any { it.audioAttributes.usage in urgent }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        // The old minimal-importance channel hid this notification from the lock screen.
        nm.deleteNotificationChannel(OLD_CHANNEL_SERVICE)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, getString(R.string.frame_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setSound(null, null); enableVibration(false); setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LAUNCH, getString(R.string.frame_launch_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null); enableVibration(false) }
        )
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, statusNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
        running = true
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
        running = false
        handler.removeCallbacks(launchWhenLocked)
        handler.removeCallbacks(launchOnWake)
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
        private const val OLD_CHANNEL_SERVICE = "frame"
        private const val CHANNEL_SERVICE = "frame_status"
        private const val ACTION_PAUSE = "com.gjnet.shuffleframe.FRAME_PAUSE"
        private const val ACTION_RESUME = "com.gjnet.shuffleframe.FRAME_RESUME"
        /** Whether the service is up, so its notification may be updated (main thread). */
        @Volatile private var running = false
        private const val CHANNEL_LAUNCH = "frame_launch"
        private const val NOTIFICATION_ID = 2
        const val LAUNCH_ID = 3
        /** Set when an update replaced the app while a frame was showing. */
        @Volatile var resumeAfterUpdate = false
        /** Longest lock delay to wait for before giving up on this screen-off. */
        private const val MAX_LOCK_WAIT_MS = 30 * 60_000L
        /** Pause after a wake before the photos come back. */
        private const val WAKE_DELAY_MS = 1_000L
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
            val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
            if (sp.getBoolean(KEY_SNOOZED, false) == snoozed) return
            sp.edit().putBoolean(KEY_SNOOZED, snoozed).apply()
            if (running) runCatching {
                ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, statusNotification(ctx))
            }
        }

        fun isSnoozed(ctx: Context) =
            PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_SNOOZED, false)

        /**
         * The ongoing notification, on the lock screen too: tap to start the slideshow (it shows
         * over the lock screen without unlocking), with Pause or Resume for frame mode.
         */
        private fun statusNotification(ctx: Context): Notification {
            val play = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, FrameSlideshowActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(SlideshowActivity.EXTRA_MANUAL, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val snoozed = isSnoozed(ctx)
            val toggle = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, Actions::class.java).setAction(if (snoozed) ACTION_RESUME else ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_frame)
                .setContentTitle(ctx.getString(R.string.frame_notification))
                .setContentText(ctx.getString(if (snoozed) R.string.frame_notification_paused else R.string.frame_notification_on))
                .setContentIntent(play)
                .addAction(
                    if (snoozed) R.drawable.ic_play else R.drawable.ic_pause,
                    ctx.getString(if (snoozed) R.string.frame_resume else R.string.frame_pause),
                    toggle,
                )
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }

    /** Pause and Resume from the notification. */
    class Actions : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PAUSE -> setSnoozed(context, true)
                ACTION_RESUME -> setSnoozed(context, false)
            }
        }
    }
}
