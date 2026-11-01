package com.gjnet.shuffleframe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.preference.PreferenceManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PreferenceManager.setDefaultValues(this, R.xml.prefs, false)
        Prefs(this).dropRetiredValues(this)
        Store.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(SyncWorker.CHANNEL, getString(R.string.sync_channel), NotificationManager.IMPORTANCE_LOW)
        )
        // UPDATE is a no-op when nothing changed, and picks up new defaults after an upgrade.
        SyncScheduler.schedule(this, update = true)
        FrameService.sync(this)
        Updater.schedule(this)
    }
}
