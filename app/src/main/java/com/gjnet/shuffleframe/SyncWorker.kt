package com.gjnet.shuffleframe

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private var promoted = false

    override suspend fun doWork(): Result {
        // Revives frame mode if the system stopped it (allowed while exempt from battery optimisation).
        FrameService.sync(applicationContext)
        try {
            Sync.run(applicationContext, inputData.getString(KEY_ALBUM)) { promote() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Store.status.value = SyncStatus(false, "Sync failed: ${e.message ?: e.javaClass.simpleName}")
        }
        // Failures are retried by the periodic sync; Result.retry() would hold up queued "sync now" requests.
        return Result.success()
    }

    /** Long downloads run as a foreground service so Android doesn't stop them after 10 minutes. */
    private suspend fun promote() {
        if (promoted) return
        promoted = true
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            // Not allowed from the background on some devices; the next run continues where this one stops.
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(applicationContext.getString(R.string.sync_notification))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val CHANNEL = "sync"
        const val KEY_ALBUM = "album"
        private const val NOTIFICATION_ID = 1
    }
}
