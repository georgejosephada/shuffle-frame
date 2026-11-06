package com.gjnet.shuffleframe

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

open class SlideshowActivity : AppCompatActivity() {
    private lateinit var view: SlideshowView
    /** Started by frame mode: shown over the lock screen and stopped when conditions fail. */
    private val frame get() = this is FrameSlideshowActivity
    /** Started from the notification: shown whatever the frame conditions say. */
    private var manual = false
    /** Set while this screen itself asked to unlock (to open something), so it stays behind. */
    private var leaving = false
    private var receiverRegistered = false

    private val frameEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when {
                // Power button while the frame is up (it keeps the screen on, so not a timeout):
                // off for now; it comes back when the screen is next woken.
                intent.action == Intent.ACTION_SCREEN_OFF -> { turnedOffByUser(); finish() }
                // Unlocked (e.g. face unlock on wake): the user wants the tablet, not the frame.
                intent.action == Intent.ACTION_USER_PRESENT -> if (!leaving) finish()
                !manual && !FrameConditions.allowed(context) -> finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java).cancel(FrameService.LAUNCH_ID)
        readManual(intent, fresh = savedInstanceState == null)
        if (frame) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        view = SlideshowView(this)
        view.onExit = {
            // Closing it over the lock screen keeps frame mode quiet until the next unlock.
            if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) FrameService.setSnoozed(this, true)
            finish()
        }
        view.beforeLeaving = { proceed -> unlockThen(proceed) }
        setContentView(view)
        hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readManual(intent, fresh = true)
    }

    /** Started from the notification? Only a new request (not a recreation) lifts a pause. */
    private fun readManual(intent: Intent?, fresh: Boolean) {
        manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) == true
        if (manual && fresh) FrameService.setSnoozed(this, false)
    }

    override fun onResume() {
        super.onResume()
        // Turn the screen on only for the launch; otherwise Android re-applies it whenever the
        // frame is shown again and wakes the tablet right after the user switched it off.
        if (frame && getSystemService(PowerManager::class.java).isInteractive) setTurnScreenOn(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        visible++
        if (frame) {
            val km = getSystemService(KeyguardManager::class.java)
            // Unlocked before it got here (e.g. face unlock on wake): not wanted any more.
            val unlocked = km.isDeviceSecure && !km.isKeyguardLocked
            if (!manual && (unlocked || !FrameConditions.allowed(this))) {
                finish()
                return
            }
            val filter = FrameConditions.changes().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(this, frameEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        view.start()
    }

    override fun onStop() {
        visible--
        view.stop()
        if (receiverRegistered) {
            unregisterReceiver(frameEvents)
            receiverRegistered = false
        }
        // The screen can't time out while a slideshow is up, so it going off means the power
        // button: no frame on this screen-off. Android delivers this stop and FrameService's
        // screen-off broadcast in either order, so both record it.
        if (!getSystemService(PowerManager::class.java).isInteractive) {
            turnedOffByUser()
            if (frame) finish()
        }
        super.onStop()
    }

    override fun onDestroy() {
        view.release()
        super.onDestroy()
    }

    /** Opening other screens from over the lock screen needs the user to unlock first. */
    private fun unlockThen(proceed: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            proceed()
            return
        }
        leaving = true
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = proceed()
            override fun onDismissCancelled() { leaving = false }
            override fun onDismissError() { leaving = false }
        })
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        /** Number of slideshow screens currently started (main thread only). */
        var visible = 0
            private set
        /** When the user last switched the screen off over a slideshow (elapsed realtime). */
        var turnedOffAt = 0L
            private set
        const val EXTRA_MANUAL = "manual"

        private fun turnedOffByUser() { turnedOffAt = SystemClock.elapsedRealtime() }
    }
}

/** Frame-mode launch: its own task, so finishing returns to whatever was open before. */
class FrameSlideshowActivity : SlideshowActivity()
