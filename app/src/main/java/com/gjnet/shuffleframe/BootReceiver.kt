package com.gjnet.shuffleframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts frame mode after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            FrameService.setSnoozed(context, false)
            val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            if (sp.getBoolean(Updater.KEY_RESUME_FRAME, false)) {
                sp.edit().remove(Updater.KEY_RESUME_FRAME).apply()
                // Only an update that just replaced the app brings the frame back, not a reboot.
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) FrameService.resumeAfterUpdate = true
            }
            FrameService.sync(context)
        }
    }
}
