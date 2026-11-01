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
 * Self-update from the GitHub releases of [BuildConfig.UPDATE_REPO]. Once a day a newer release's
 * APK is downloaded in the background and kept ready; it is installed when the user is at the
 * tablet (opening the app, the "update ready" notification, or "Check for updates now"), because
 * Play Protect may ask about each new version and that prompt shouldn't pop up over the frame.
 * Android only accepts an APK signed with the same key, so a tampered download can't replace the app.
 */
object Updater {
    private const val ASSET = "ShuffleFrame.apk"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 4
    private const val KEY_STATUS = "update_status"
    const val KEY_RESUME_FRAME = "resume_frame_after_update"
    private const val KEY_LAST_AUTO_INSTALL = "update_last_auto_install"
    private const val KEY_LAST_AUTO_VERSION = "update_last_auto_version"
    /** Opening the app retries a dismissed install at most this often. */
    private const val AUTO_INSTALL_INTERVAL_MS = 12 * 60 * 60 * 1000L
    const val EXTRA_INSTALL = "install_update"
    private val lock = Mutex()

    class Release(val versionCode: Long, val versionName: String, val apkUrl: String)

    fun schedule(ctx: Context) {
        if (BuildConfig.UPDATE_REPO.isEmpty()) return
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** "Check for updates now": the user is at the tablet, so a newer version installs right away. */
    fun checkNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.workDataOf(UpdateWorker.KEY_INSTALL to System.currentTimeMillis()))
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

    /** The downloaded update waiting to be installed. */
    private fun pendingFile(ctx: Context) = File(ctx.filesDir, "update/$ASSET")

    /** Version code of the waiting update, or null if there is none newer than what's installed. */
    fun pendingVersion(ctx: Context): Long? {
        val f = pendingFile(ctx)
        if (!f.exists()) return null
        val code = ctx.packageManager.getPackageArchiveInfo(f.path, 0)?.longVersionCode
        if (code == null || code <= installedVersion(ctx)) {
            f.delete()  // already installed (or unreadable)
            return null
        }
        return code
    }

    /** Checks GitHub and downloads a newer version; installs it straight away only if [installNow]. */
    suspend fun run(ctx: Context, installNow: Boolean) = lock.withLock { runLocked(ctx, installNow) }

    private fun runLocked(ctx: Context, installNow: Boolean) {
        val release = latest()
        if (release.versionCode <= installedVersion(ctx)) {
            pendingFile(ctx).delete()
            setStatus(ctx, ctx.getString(R.string.update_none, java.text.DateFormat.getDateTimeInstance().format(java.util.Date())))
            return
        }
        if (pendingVersion(ctx) != release.versionCode) {
            val tmp = File.createTempFile("update", ".apk", ctx.cacheDir)
            try {
                GooglePhotos.download(release.apkUrl, tmp)
                val info = ctx.packageManager.getPackageArchiveInfo(tmp.path, 0)
                if (info?.packageName != ctx.packageName || info.longVersionCode != release.versionCode) {
                    throw IOException("Downloaded file isn't the expected Shuffle Frame build")
                }
                val dest = pendingFile(ctx).apply { parentFile?.mkdirs() }
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true) }
            } finally {
                tmp.delete()
            }
        }
        if (installNow) installPending(ctx)
        else {
            setStatus(ctx, ctx.getString(R.string.update_downloaded, release.versionName))
            notifyReady(ctx, release.versionName)
        }
    }

    /**
     * Installs the waiting update, if any. [automatic] (the app was simply opened) retries a
     * dismissed install at most every 12 hours; the notification and Settings always try.
     */
    fun installPending(ctx: Context, automatic: Boolean = false): Boolean {
        val version = pendingVersion(ctx) ?: return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val now = System.currentTimeMillis()
        // Only a retry of the same version waits; a newer download installs on the next open.
        if (automatic && prefs.getLong(KEY_LAST_AUTO_VERSION, 0) == version &&
            now - prefs.getLong(KEY_LAST_AUTO_INSTALL, 0) < AUTO_INSTALL_INTERVAL_MS
        ) return false
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            setStatus(ctx, ctx.getString(R.string.update_needs_permission, ""))
            return false
        }
        prefs.edit().putLong(KEY_LAST_AUTO_INSTALL, now).putLong(KEY_LAST_AUTO_VERSION, version)
            .putBoolean(KEY_RESUME_FRAME, SlideshowActivity.visible > 0).commit()
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        setStatus(ctx, ctx.getString(R.string.update_installing, ""))
        return try {
            install(ctx, pendingFile(ctx))
            true
        } catch (e: Exception) {
            prefs.edit().remove(KEY_RESUME_FRAME).apply()
            setStatus(ctx, ctx.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
            false
        }
    }

    /** "Update ready" notification; tapping it opens the app and installs. */
    private fun notifyReady(ctx: Context, versionName: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_INSTALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_frame)
                .setContentTitle(ctx.getString(R.string.update_ready_named, versionName))
                .setContentText(ctx.getString(R.string.update_tap))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
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
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
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
    companion object {
        const val KEY_INSTALL = "install"
    }

    override suspend fun doWork(): Result {
        try {
            // "Check now" installs at once only if it runs promptly while the user is still there
            // (if the tablet was offline, it may run much later): otherwise it just downloads.
            val requestedAt = inputData.getLong(KEY_INSTALL, 0)
            val ctx = applicationContext
            val present = ctx.getSystemService(android.os.PowerManager::class.java).isInteractive &&
                !ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            val prompt = requestedAt > 0 && System.currentTimeMillis() - requestedAt < 2 * 60_000 && present
            Updater.run(ctx, installNow = prompt)
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
            if (confirm != null) {
                // Always offer it as a notification (a blocked launch fails silently), and show it
                // at once when the user is looking at the app.
                Updater.notifyConfirm(context, confirm)
                if (MainActivity.resumed || SlideshowActivity.visible > 0) {
                    runCatching { context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
        Updater.onResult(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
