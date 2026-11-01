package com.gjnet.shuffleframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.dreams.DreamService
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat

/**
 * Android screen saver entry point, for devices that offer one (OxygenOS hides it; frame mode
 * in [FrameService] covers that). Follows the same conditions as frame mode.
 */
class SlideshowDream : DreamService() {
    private var view: SlideshowView? = null
    private var receiverRegistered = false

    private val conditionsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!allowed()) finish()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        val v = SlideshowView(ContextThemeWrapper(this, R.style.Theme_ShuffleFrame_Slideshow))
        v.onExit = { finish() }
        v.closeToOpenApps = true
        setContentView(v)
        view = v
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        if (!allowed()) {
            finish()
            return
        }
        ContextCompat.registerReceiver(this, conditionsChanged, FrameConditions.changes(), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        view?.start()
    }

    override fun onDreamingStopped() {
        unregister()
        view?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        unregister()
        view?.release()
        view = null
        super.onDetachedFromWindow()
    }

    private fun unregister() {
        if (receiverRegistered) {
            unregisterReceiver(conditionsChanged)
            receiverRegistered = false
        }
    }

    private fun allowed() = FrameConditions.allowed(this)
}
