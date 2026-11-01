package com.gjnet.shuffleframe

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val PERIODIC = "periodic-sync"
    private const val NOW = "sync-now"
    private const val ALBUM = "sync-album"
    private const val FOLLOW_UP = "sync-follow-up"

    /**
     * Syncs again shortly, queued behind any running sync so it lists albums afresh; used after
     * the user removes an item in Google Photos.
     */
    fun syncSoon(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(20, TimeUnit.SECONDS)
            .setConstraints(constraints(ctx, userInitiated = true))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(FOLLOW_UP, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Explicit user actions may use any network; automatic syncs respect "Wi-Fi only". */
    private fun constraints(ctx: Context, userInitiated: Boolean): Constraints {
        val network = if (!userInitiated && Prefs(ctx).wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        return Constraints.Builder().setRequiredNetworkType(network).build()
    }

    /** Schedules the background sync; [update] applies changed interval or Wi-Fi settings. */
    fun schedule(ctx: Context, update: Boolean) {
        val prefs = Prefs(ctx)
        val network = if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = PeriodicWorkRequestBuilder<SyncWorker>(prefs.syncMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC,
            if (update) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    /**
     * Runs a sync as soon as there is any network. A full sync is skipped if one is already
     * pending; a sync for one album (just added or re-enabled) queues behind a running one.
     */
    fun syncNow(ctx: Context, albumKey: String? = null, userInitiated: Boolean = true) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(ctx, userInitiated))
            .setInputData(workDataOf(SyncWorker.KEY_ALBUM to albumKey))
            .build()
        val wm = WorkManager.getInstance(ctx)
        if (albumKey != null) {
            wm.enqueueUniqueWork(ALBUM, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
            return
        }
        // A sync already running listed the albums when it started, so queue exactly one more run
        // behind it (things saved meanwhile are picked up). A run that is only waiting (e.g. one
        // rescheduled with a delay after the app was updated) is replaced so this one starts now.
        val infos = wm.getWorkInfosForUniqueWork(NOW)
        infos.addListener({
            val states = runCatching { infos.get() }.getOrDefault(emptyList()).map { it.state }
            val running = WorkInfo.State.RUNNING in states
            val waiting = states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }
            when {
                running && waiting -> Unit  // a follow-up is already queued behind the running sync
                running -> wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
                else -> wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
            }
        }, Executors.newSingleThreadExecutor())
    }
}
