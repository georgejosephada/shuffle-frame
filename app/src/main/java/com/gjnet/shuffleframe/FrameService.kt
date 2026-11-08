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
import android.media.AudioPlaybackConfiguration
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
 * the slideshow back over the lock screen. Wakes the user didn't ask for (plugging in or
 * unplugging the charger, an alarm, a call, a notification that sounds) leave things as they
 * were: nothing starts, and the frame stays off until the user wakes the tablet themselves,
 * taps the notification or unlocks. Closing it with ✕ (or Pause in the notification)
 * keeps frame mode quiet until the next unlock. The ongoing notification, also on the lock
 * screen, starts the slideshow when tapped.
 */
class FrameService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var waitedMs = 0L
    /** When the charger was last plugged in or unplugged (elapsed realtime). */
    private var plugChangedAt = 0L
    /** When a notification, alarm or ringtone sound last started playing (elapsed realtime). */
    private var alertSoundAt = 0L
    /** Whether an alarm, ringtone or call is sounding, and when the last one stopped. */
    private var urgentSounding = false
    private var urgentEndedAt = 0L

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val now = SystemClock.elapsedRealtime()
            if (configs.any { it.audioAttributes.usage in ALERT_USAGES }) alertSoundAt = now
            val urgent = configs.any { it.audioAttributes.usage in URGENT_USAGES }
            if (urgentSounding && !urgent) urgentEndedAt = now
            urgentSounding = urgent
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(launchWhenLocked)
                    handler.removeCallbacks(launchOnWake)
                    // Power pressed while a slideshow was showing: the user wants the screen off,
                    // and it stays off (even after a charger or alarm wake) until they wake it.
                    // Likewise power pressed to silence an alarm or end a call (which may have
                    // covered the slideshow): it doesn't come straight back.
                    val now = SystemClock.elapsedRealtime()
                    val byUser = SlideshowActivity.visible > 0 || now - SlideshowActivity.turnedOffAt < 5_000 ||
                        urgentSounding || somethingElseUp() || now - urgentEndedAt < URGENT_OFF_WINDOW_MS
                    if (byUser) setHeldOff(context, true)
                    if (!isHeldOff(context)) {
                        waitedMs = 0
                        launchWhenLocked.run()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    handler.removeCallbacks(launchWhenLocked)
                    // Woken on the lock screen: back to the photos (unlocking closes them). After
                    // a moment, so whatever woke it (the charger, an alarm, a call, face unlock)
                    // is known by then.
                    handler.removeCallbacks(launchOnWake)
                    handler.postDelayed(launchOnWake, WAKE_DELAY_MS)
                }
                Intent.ACTION_USER_PRESENT -> {
                    handler.removeCallbacks(launchOnWake)
                    setHeldOff(context, false)
                    getSystemService(NotificationManager::class.java).cancel(LAUNCH_ID)
                    setSnoozed(context, false)
                }
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                    plugChangedAt = SystemClock.elapsedRealtime()
                    if (intent.action == Intent.ACTION_POWER_CONNECTED) KeepAwake.onPluggedIn(context)
                }
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
        if (!getSystemService(PowerManager::class.java).isInteractive) return@Runnable
        val km = getSystemService(KeyguardManager::class.java)
        val locked = if (km.isDeviceSecure) km.isDeviceLocked else km.isKeyguardLocked
        // Still unlocked (the lock delay): the user is at the tablet, so the next timeout counts.
        if (!locked) {
            setHeldOff(this, false)
            return@Runnable
        }
        // Not woken by the user: keep things as they were. The screen was off with no frame up,
        // so the timeout that ends this wake must not start one either.
        if (wokenByCharger() || somethingElseUp() || SystemClock.elapsedRealtime() - alertSoundAt < ALERT_WAKE_WINDOW_MS) {
            setHeldOff(this, true)
            return@Runnable
        }
        setHeldOff(this, false)
        maybeLaunch()
    }

    /** Plugging in or unplugging turns the screen on: the plug broadcast comes around the wake. */
    private fun wokenByCharger() = SystemClock.elapsedRealtime() - plugChangedAt < CHARGER_WAKE_WINDOW_MS

    /** A call, or an alarm or ringtone sounding: that app's screen is what the user needs. */
    private fun somethingElseUp(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        if (am.mode != AudioManager.MODE_NORMAL) return true
        return am.activePlaybackConfigurations.any { it.audioAttributes.usage in URGENT_USAGES }
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
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        running = true
        getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(playback, handler)
        // Restarted while unlocked: an unlock may have been missed, so don't keep the frame off.
        val km = getSystemService(KeyguardManager::class.java)
        if (!(if (km.isDeviceSecure) km.isDeviceLocked else km.isKeyguardLocked)) setHeldOff(this, false)
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
        if (registered) {
            unregisterReceiver(receiver)
            getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(playback)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun maybeLaunch() {
        if (!Prefs(this).frameMode || isSnoozed(this) || !FrameConditions.allowed(this)) return
        if (SlideshowActivity.visible > 0) return
        // Never over a ringing alarm or call.
        if (somethingElseUp()) return
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
        // Always a new notification: Android only starts the full-screen intent of a new one.
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(LAUNCH_ID)
        nm.notify(LAUNCH_ID, n)
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
        /** A wake this close to a charger change (either side) is the charger's doing. */
        private const val CHARGER_WAKE_WINDOW_MS = 4_000L
        /** A wake this soon after an alert sound started is that alert's doing. */
        private const val ALERT_WAKE_WINDOW_MS = 4_000L
        /** A screen-off this soon after an alarm, ringtone or call stopped is what stopped it. */
        private const val URGENT_OFF_WINDOW_MS = 3_000L
        private const val KEY_HELD_OFF = "frame_held_off"
        /** Sounds whose app's screen is what the user needs right now. */
        private val URGENT_USAGES = setOf(
            AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
        )
        /** Sounds that come with something else waking the screen (a message, a reminder, ...). */
        @Suppress("DEPRECATION")
        private val ALERT_USAGES = URGENT_USAGES + setOf(
            AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_NOTIFICATION_EVENT,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
        )
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
         * The user switched the screen off over the slideshow, and hasn't since woken the tablet,
         * tapped the notification or unlocked: screen-offs don't bring the frame back. Stored, so
         * it survives the service being restarted.
         */
        fun setHeldOff(ctx: Context, held: Boolean) {
            val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
            if (sp.getBoolean(KEY_HELD_OFF, false) == held) return
            sp.edit().putBoolean(KEY_HELD_OFF, held).apply()
            // Whether the frame will keep the screen on during a sync has changed.
            KeepAwake.refresh(ctx)
        }

        fun isHeldOff(ctx: Context) =
            PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_HELD_OFF, false)

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
