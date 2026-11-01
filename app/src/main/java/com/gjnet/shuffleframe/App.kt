package com.gjnet.shuffleframe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class App : Application() {
    /** Work that should outlive any one screen (e.g. restoring an album list). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(FlowPreview::class)
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
        scope.launch {
            Store.albumsChanged.debounce(2000).collect { runCatching { AlbumBackup.saveAutoCopy(this@App) } }
        }
    }
}
