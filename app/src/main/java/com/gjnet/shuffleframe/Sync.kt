package com.gjnet.shuffleframe

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Mirrors the selected albums into local storage. */
object Sync {
    private const val PARALLEL_DOWNLOADS = 3
    private val mutex = Mutex()

    /**
     * Syncs every enabled album, or only [onlyAlbum]. [onHeavyWork] is called once before a
     * large batch of downloads so the worker can become a foreground service.
     */
    suspend fun run(ctx: Context, onlyAlbum: String? = null, onHeavyWork: suspend () -> Unit = {}) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                var errors = 0
                try {
                    val side = photoSide(ctx)
                    for (a in Store.albums()) {
                        if (onlyAlbum != null && a.key != onlyAlbum) continue
                        if (onlyAlbum == null && !a.enabled) continue
                        coroutineContext.ensureActive()
                        if (!syncAlbum(a, side, onHeavyWork)) errors++
                    }
                    transcodeFailedVideos()
                    Store.sweepOrphans()
                } finally {
                    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                    Store.status.value = SyncStatus(
                        running = false,
                        message = if (errors == 0) "Last synced at $time" else "Synced at $time with problems in $errors album(s)",
                    )
                    Store.bump()
                }
            }
        }

    private suspend fun syncAlbum(a: Album, side: Int, onHeavyWork: suspend () -> Unit): Boolean {
        Store.status.value = SyncStatus(true, "Checking “${a.title}”…")
        try {
            val listing = GooglePhotos.listAlbum(a.key, a.authKey)
            val title = listing.title ?: a.title
            val cover = Store.coverFile(a.key)
            if (listing.coverUrl != null && (listing.coverUrl != a.coverUrl || !cover.exists())) {
                runCatching { GooglePhotos.download(listing.coverUrl + "=w480-h480-c", cover) }
            }
            Store.updateAlbum(a.key) { it.copy(title = title, coverUrl = listing.coverUrl) }

            val missing = Store.applyListing(a.key, listing.items)
            if (missing.size > 20) onHeavyWork()
            val done = AtomicInteger()
            val failed = AtomicInteger()
            val gate = Semaphore(PARALLEL_DOWNLOADS)
            coroutineScope {
                for (item in missing) launch {
                    gate.withPermit {
                        coroutineContext.ensureActive()
                        try {
                            if (Store.isTracked(item.key)) downloadItem(item, side)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                        }
                        val n = done.incrementAndGet()
                        Store.status.value = SyncStatus(true, "“$title”: downloading $n of ${missing.size}")
                        if (n % 10 == 0) Store.bump()
                    }
                }
            }
            val f = failed.get()
            Store.updateAlbum(a.key) {
                it.copy(
                    lastSyncAt = System.currentTimeMillis(),
                    lastError = if (f > 0) "$f item(s) couldn't be downloaded yet; will retry" else null,
                )
            }
            return f == 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Store.updateAlbum(a.key) { it.copy(lastError = e.message ?: e.javaClass.simpleName) } }
            return false
        }
    }

    private fun downloadItem(item: Item, side: Int) {
        val dest = Store.mediaFile(item.key, item.type)
        val url = when (item.type) {
            // Google renders HEIC and other formats to a correctly rotated JPEG at this size.
            MediaType.PHOTO -> item.baseUrl + "=s$side"
            // The original video file (HEVC stays HEVC; the tablet decodes it in hardware).
            MediaType.VIDEO -> item.baseUrl + "=dv"
        }
        GooglePhotos.download(url, dest)
        // The album may have been removed while this was downloading.
        if (!Store.isTracked(item.key)) dest.delete()
    }

    /** Replaces videos the player couldn't decode with Google's 1080p (or 720p) H.264 version. */
    private suspend fun transcodeFailedVideos() {
        for (item in Store.transcodeQueue()) {
            coroutineContext.ensureActive()
            if (!Store.isTracked(item.key)) continue
            Store.status.value = SyncStatus(true, "Fetching a compatible version of a video…")
            val dest = Store.mediaFile(item.key, MediaType.VIDEO)
            val ok = listOf("=m37", "=m22").any { suffix ->
                runCatching { GooglePhotos.download(item.baseUrl + suffix, dest) }.isSuccess
            }
            if (ok) Store.markTranscoded(item.key)
        }
    }

    /** Long edge of the panel in pixels, so photos fill it in either orientation. */
    private fun photoSide(ctx: Context): Int {
        val mode = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val side = if (mode != null) maxOf(mode.physicalWidth, mode.physicalHeight) else 2560
        return side.coerceIn(1920, 4096)
    }
}
