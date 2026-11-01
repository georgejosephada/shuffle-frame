package com.gjnet.shuffleframe

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.StatFs
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
import java.text.NumberFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Mirrors the selected albums into local storage in three passes: list every album (a few at a
 * time) so counts and removals are current within a minute; download what's new, photos first,
 * several at a time; then, in Full HD mode, replace stored original videos with smaller copies.
 */
object Sync {
    private const val PARALLEL_LISTINGS = 4
    private const val PARALLEL_DOWNLOADS = 6
    /** Downloads stop when free space falls below this. */
    private const val MIN_FREE_BYTES = 5L shl 30
    /** Shrinking frees space as it goes, so it only needs room for one Full HD copy. */
    private const val SHRINK_MIN_FREE_BYTES = 1L shl 30
    private val mutex = Mutex()

    /**
     * Syncs every enabled album, or only [onlyAlbum]. [onHeavyWork] is called once before a
     * large batch of downloads so the worker can become a foreground service.
     */
    suspend fun run(ctx: Context, onlyAlbum: String? = null, onHeavyWork: suspend () -> Unit = {}) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val listingErrors = ConcurrentHashMap<String, String>()
                val failedDownloads = ConcurrentHashMap<String, AtomicInteger>()
                val outOfSpace = AtomicBoolean(false)
                var finished = false
                try {
                    val albums = Store.albums().filter { if (onlyAlbum != null) it.key == onlyAlbum else it.enabled }
                    val queue = listAll(albums, listingErrors)
                    val shrink = !Prefs(ctx).originalVideos && Store.shrinkQueue().isNotEmpty()
                    if (queue.size > 20 || shrink) onHeavyWork()
                    // Low on space: free some first by shrinking stored originals.
                    if (shrink && lowOnSpace(ctx)) shrinkVideos(ctx)
                    downloadAll(ctx, queue, failedDownloads, outOfSpace)
                    for (a in albums) {
                        if (listingErrors.containsKey(a.key)) continue
                        val failed = failedDownloads[a.key]?.get() ?: 0
                        Store.updateAlbum(a.key) {
                            it.copy(lastError = if (failed > 0) "$failed item(s) couldn't be downloaded yet; will retry" else null)
                        }
                    }
                    if (shrink) shrinkVideos(ctx)
                    transcodeFailedVideos()
                    Store.sweepOrphans()
                    finished = true
                } finally {
                    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                    val problems = listingErrors.size + failedDownloads.count { it.value.get() > 0 }
                    Store.status.value = SyncStatus(
                        running = false,
                        message = when {
                            !finished -> "Sync paused at $time; it continues next time"
                            outOfSpace.get() -> "Storage almost full: downloads paused at $time"
                            problems == 0 -> "Last synced at $time"
                            else -> "Synced at $time with problems in $problems album(s)"
                        },
                    )
                    Store.bump()
                }
            }
        }

    /** Lists the albums a few at a time; returns what needs downloading as (albumKey, item). */
    private suspend fun listAll(albums: List<Album>, errors: MutableMap<String, String>): List<Pair<String, Item>> {
        val pending = ConcurrentLinkedQueue<Pair<String, Item>>()
        val checked = AtomicInteger()
        val gate = Semaphore(PARALLEL_LISTINGS)
        Store.status.value = SyncStatus(true, "Checking ${albums.size} album(s) for changes…")
        coroutineScope {
            for (a in albums) launch {
                gate.withPermit {
                    coroutineContext.ensureActive()
                    try {
                        val listing = GooglePhotos.listAlbum(a.key, a.authKey)
                        val cover = Store.coverFile(a.key)
                        if (listing.coverUrl != null && (listing.coverUrl != a.coverUrl || !cover.exists())) {
                            runCatching { GooglePhotos.download(listing.coverUrl + "=w480-h480-c", cover) }
                        }
                        Store.applyListing(a.key, listing.items).forEach { pending.add(a.key to it) }
                        // Recorded now, so it sticks even if the downloads below get interrupted.
                        Store.updateAlbum(a.key) {
                            it.copy(
                                title = listing.title ?: it.title,
                                coverUrl = listing.coverUrl,
                                lastSyncAt = System.currentTimeMillis(),
                                lastError = null,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val message = e.message ?: e.javaClass.simpleName
                        errors[a.key] = message
                        Store.updateAlbum(a.key) { it.copy(lastError = message) }
                    }
                    Store.status.value = SyncStatus(true, "Checked ${checked.incrementAndGet()} of ${albums.size} albums…")
                }
            }
        }
        // Each item once (it may be in several albums); photos first so the frame fills quickly.
        return pending.distinctBy { it.second.key }.sortedBy { if (it.second.type == MediaType.PHOTO) 0 else 1 }
    }

    private suspend fun downloadAll(
        ctx: Context,
        queue: List<Pair<String, Item>>,
        failed: MutableMap<String, AtomicInteger>,
        outOfSpace: AtomicBoolean,
    ) {
        if (queue.isEmpty()) return
        val side = photoSide(ctx)
        val originalVideos = Prefs(ctx).originalVideos
        val done = AtomicInteger()
        val total = NumberFormat.getInstance().format(queue.size)
        val gate = Semaphore(PARALLEL_DOWNLOADS)
        coroutineScope {
            for ((albumKey, item) in queue) launch {
                gate.withPermit {
                    coroutineContext.ensureActive()
                    if (outOfSpace.get() || lowOnSpace(ctx)) {
                        outOfSpace.set(true)
                        return@withPermit
                    }
                    try {
                        if (Store.isTracked(item.key)) downloadItem(item, side, originalVideos)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed.getOrPut(albumKey) { AtomicInteger() }.incrementAndGet()
                    }
                    val n = done.incrementAndGet()
                    Store.status.value = SyncStatus(true, "Downloading ${NumberFormat.getInstance().format(n)} of $total new items…")
                    if (n % 20 == 0) Store.bump()
                }
            }
        }
    }

    private fun downloadItem(item: Item, side: Int, originalVideos: Boolean) {
        val dest = Store.mediaFile(item.key, item.type)
        when (item.type) {
            // Google renders HEIC and other formats to a correctly rotated JPEG at this size.
            MediaType.PHOTO -> GooglePhotos.download(item.baseUrl + "=s$side", dest)
            MediaType.VIDEO -> if (originalVideos) {
                GooglePhotos.download(item.baseUrl + "=dv", dest)
                Store.setOriginal(item.key, shrinkable = true)
            } else if (!downloadSmallerVideo(item, dest)) {
                // Short or low-resolution clips have no smaller version; their original is small.
                GooglePhotos.download(item.baseUrl + "=dv", dest)
                Store.setOriginal(item.key, shrinkable = false)
            }
        }
        // The album may have been removed while this was downloading.
        if (!Store.isTracked(item.key)) dest.delete()
    }

    /**
     * Fetches Google's 1080p (or 720p) H.264 version. Returns false when Google has none (404);
     * other failures throw so the item is retried next sync.
     */
    private fun downloadSmallerVideo(item: Item, dest: java.io.File): Boolean {
        for (suffix in listOf("=m37", "=m22")) {
            try {
                GooglePhotos.download(item.baseUrl + suffix, dest)
                return true
            } catch (e: GooglePhotos.HttpStatusException) {
                if (e.code != 404) throw e
            }
        }
        return false
    }

    /** Full HD mode: swaps stored original videos for Google's smaller version, biggest first. */
    private suspend fun shrinkVideos(ctx: Context) {
        val queue = Store.shrinkQueue()
        for ((i, item) in queue.withIndex()) {
            coroutineContext.ensureActive()
            if (!Store.isTracked(item.key)) continue
            if (StatFs(ctx.filesDir.path).availableBytes < SHRINK_MIN_FREE_BYTES) return
            Store.status.value = SyncStatus(true, "Shrinking stored videos to Full HD (${i + 1} of ${queue.size})…")
            try {
                val dest = Store.mediaFile(item.key, MediaType.VIDEO)
                val shrunk = downloadSmallerVideo(item, dest)
                Store.setOriginal(item.key, shrinkable = false)
                if (shrunk && !Store.isTracked(item.key)) dest.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Tried again next sync.
            }
            if (i % 10 == 9) Store.bump()
        }
    }

    /** Replaces videos the player couldn't decode with Google's 1080p (or 720p) H.264 version. */
    private suspend fun transcodeFailedVideos() {
        for (item in Store.transcodeQueue()) {
            coroutineContext.ensureActive()
            if (!Store.isTracked(item.key)) continue
            Store.status.value = SyncStatus(true, "Fetching a compatible version of a video…")
            val dest = Store.mediaFile(item.key, MediaType.VIDEO)
            val ok = runCatching { downloadSmallerVideo(item, dest) }.getOrDefault(false)
            if (ok) Store.markTranscoded(item.key)
        }
    }

    private fun lowOnSpace(ctx: Context) = StatFs(ctx.filesDir.path).availableBytes < MIN_FREE_BYTES

    /** Long edge of the panel in pixels, so photos fill it in either orientation. */
    private fun photoSide(ctx: Context): Int {
        val mode = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val side = if (mode != null) maxOf(mode.physicalWidth, mode.physicalHeight) else 2560
        return side.coerceIn(1920, 4096)
    }
}
