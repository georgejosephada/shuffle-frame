package com.gjnet.shuffleframe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Self-update from the GitHub releases of [BuildConfig.UPDATE_REPO]: once a day, if the latest
 * release carries a higher version code, download its APK and install it. Android only accepts
 * it when it is signed with the same key, so a tampered download can't replace the app.
 */
object Updater {
    private const val ASSET = "ShuffleFrame.apk"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 4
    private const val KEY_STATUS = "update_status"
    const val KEY_RESUME_FRAME = "resume_frame_after_update"
    private val lock = Mutex()

    class Release(val versionCode: Long, val versionName: String, val apkUrl: String)

    fun schedule(ctx: Context) {
        if (BuildConfig.UPDATE_REPO.isEmpty()) return
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun checkNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("update-now", ExistingWorkPolicy.KEEP, req)
    }

    fun status(ctx: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(KEY_STATUS, null)

    fun setStatus(ctx: Context, text: String) =
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(KEY_STATUS, text).apply()

    fun installedVersion(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** Latest release on GitHub. Tags look like "build-12". Throws if there is no usable release. */
    fun latest(): Release {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val json = GooglePhotos.http.newCall(req).execute().use { r ->
            if (r.code == 404) throw IOException("no release found at ${BuildConfig.UPDATE_REPO}")
            if (!r.isSuccessful) throw IOException("GitHub returned HTTP ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
        val tag = json.optString("tag_name")
        val code = tag.removePrefix("build-").toLongOrNull() ?: throw IOException("unexpected release tag \"$tag\"")
        val assets = json.optJSONArray("assets") ?: throw IOException("release $tag has no files")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == ASSET) {
                return Release(code, json.optString("name"), a.getString("browser_download_url"))
            }
        }
        throw IOException("release $tag has no $ASSET")
    }

    suspend fun run(ctx: Context) = lock.withLock { runLocked(ctx) }

    private fun runLocked(ctx: Context) {
        val release = latest()
        if (release.versionCode <= installedVersion(ctx)) {
            setStatus(ctx, ctx.getString(R.string.update_none, java.text.DateFormat.getDateTimeInstance().format(java.util.Date())))
            return
        }
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            setStatus(ctx, ctx.getString(R.string.update_needs_permission, release.versionName))
            return
        }
        val apk = File.createTempFile("update", ".apk", ctx.cacheDir)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        try {
            GooglePhotos.download(release.apkUrl, apk)
            val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
            if (info?.packageName != ctx.packageName || info.longVersionCode != release.versionCode) {
                throw IOException("Downloaded file isn't the expected Shuffle Frame build")
            }
            setStatus(ctx, ctx.getString(R.string.update_installing, release.versionName))
            // Bring the frame back after the update if it was on screen.
            prefs.edit().putBoolean(KEY_RESUME_FRAME, SlideshowActivity.visible > 0).commit()
            install(ctx, apk)
        } catch (e: Exception) {
            prefs.edit().remove(KEY_RESUME_FRAME).apply()
            throw e
        } finally {
            apk.delete()
        }
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        // Drop sessions left by earlier attempts (e.g. a confirmation that was never given).
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    /** When Android insists on a confirmation, a notification lets the user give it. */
    fun notifyConfirm(ctx: Context, confirm: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pi = PendingIntent.getActivity(
            ctx, 0, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_frame)
                .setContentTitle(ctx.getString(R.string.update_ready))
                .setContentText(ctx.getString(R.string.update_tap))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun onResult(ctx: Context, status: Int, message: String?) {
        when (status) {
            // Normally the old process is gone by now; the new one reports its version in Settings.
            PackageInstaller.STATUS_SUCCESS -> PreferenceManager.getDefaultSharedPreferences(ctx).edit().remove(KEY_STATUS).apply()
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                setStatus(ctx, ctx.getString(R.string.update_waiting))
                PreferenceManager.getDefaultSharedPreferences(ctx).edit().remove(KEY_RESUME_FRAME).apply()
            }
            else -> {
                setStatus(ctx, ctx.getString(R.string.update_failed, message ?: "error $status"))
                PreferenceManager.getDefaultSharedPreferences(ctx).edit().remove(KEY_RESUME_FRAME).apply()
            }
        }
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try {
            Updater.run(applicationContext)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Shown in Settings; tried again at the next daily check.
            Updater.setStatus(applicationContext, applicationContext.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
        }
        return Result.success()
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) Updater.notifyConfirm(context, confirm)
        }
        Updater.onResult(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
