package com.gjnet.shuffleframe

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.StatFs
import android.view.Display
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import androidx.media3.transformer.ExportException
import java.io.File
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
 * Mirrors the selected albums into local storage: list every album (a few at a time) so counts
 * and removals are current within a minute; download new photos (and, unless videos are converted
 * on the tablet, new videos) several at a time; bring stored photos to the chosen format; then
 * store every video the way the "Video quality" setting asks, converting on the tablet if chosen.
 */
object Sync {
    private const val PARALLEL_LISTINGS = 4
    private const val PARALLEL_DOWNLOADS = 6
    /** Downloads stop when free space falls below this. */
    private const val MIN_FREE_BYTES = 5L shl 30
    /** Extra room kept free while fetching originals to convert, on top of [MIN_FREE_BYTES]. */
    private const val VIDEO_MIN_FREE_BYTES = 2L shl 30
    /** Conversion errors that retrying won't fix. */
    private val PERMANENT_EXPORT_ERRORS = setOf(
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
    )
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
                    clearLeftovers(ctx)
                    val albums = Store.albums().filter { if (onlyAlbum != null) it.key == onlyAlbum else it.enabled }
                    val prefs = Prefs(ctx)
                    val policy = prefs.videoQuality
                    // Videos converted on the tablet are fetched one at a time by processVideos instead.
                    val queue = listAll(albums, listingErrors)
                        .filter { policy != Prefs.VIDEO_CONVERT || it.second.type == MediaType.PHOTO }
                    val videos = Store.videosToProcess(policy)
                    val photoFormat = if (prefs.photoWebp) "webp" else "jpeg"
                    if (queue.size > 20 || videos.isNotEmpty() || Store.photoFormatDone != photoFormat) onHeavyWork()
                    // Low on space: converting stored videos frees space, so do that first.
                    if (lowOnSpace(ctx) && policy == Prefs.VIDEO_CONVERT) {
                        processVideos(ctx, Store.videosToProcess(policy).filter { Store.videoVariant(it.key) == VideoVariant.ORIGINAL &&
                            Store.mediaFile(it.key, it.type).exists() }, policy)
                    }
                    downloadAll(ctx, queue, failedDownloads, outOfSpace)
                    for (a in albums) {
                        if (listingErrors.containsKey(a.key)) continue
                        val failed = failedDownloads[a.key]?.get() ?: 0
                        Store.updateAlbum(a.key) {
                            it.copy(lastError = if (failed > 0) "$failed item(s) couldn't be downloaded yet; will retry" else null)
                        }
                    }
                    convertPhotos(ctx, photoFormat)
                    processVideos(ctx, Store.videosToProcess(policy), policy)
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
        val prefs = Prefs(ctx)
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
                        if (Store.isTracked(item.key)) downloadItem(item, side, prefs)
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

    private fun downloadItem(item: Item, side: Int, prefs: Prefs) {
        val dest = Store.mediaFile(item.key, item.type)
        when (item.type) {
            // Google renders HEIC and other formats at this size; "-rw" asks for WebP instead of JPEG.
            MediaType.PHOTO -> GooglePhotos.download(photoUrl(item, side, prefs.photoWebp), dest)
            MediaType.VIDEO -> if (prefs.videoQuality == Prefs.VIDEO_ORIGINAL) {
                GooglePhotos.download(item.baseUrl + "=dv", dest)
                Store.videoStored(item.key, VideoVariant.ORIGINAL, Prefs.VIDEO_ORIGINAL)
            } else if (downloadSmallerVideo(item, dest)) {
                Store.videoStored(item.key, VideoVariant.GOOGLE, Prefs.VIDEO_GOOGLE)
            } else {
                // Short or low-resolution clips have no smaller version; their original is small.
                GooglePhotos.download(item.baseUrl + "=dv", dest)
                Store.videoStored(item.key, VideoVariant.ORIGINAL, Prefs.VIDEO_GOOGLE)
            }
        }
        // The album may have been removed while this was downloading.
        if (!Store.isTracked(item.key)) dest.delete()
    }

    private fun photoUrl(item: Item, side: Int, webp: Boolean) = item.baseUrl + "=s$side" + if (webp) "-rw" else ""

    /** Re-fetches stored photos that aren't in the chosen format (Google renders both from the original). */
    private suspend fun convertPhotos(ctx: Context, format: String) {
        if (Store.photoFormatDone == format) return
        val webp = format == "webp"
        val side = photoSide(ctx)
        val todo = Store.storedPhotos().filter { isWebp(Store.mediaFile(it.key, it.type)) != webp }
        val failed = AtomicInteger()
        val done = AtomicInteger()
        val gate = Semaphore(PARALLEL_DOWNLOADS)
        coroutineScope {
            for (item in todo) launch {
                gate.withPermit {
                    coroutineContext.ensureActive()
                    try {
                        if (Store.isTracked(item.key)) {
                            val dest = Store.mediaFile(item.key, item.type)
                            GooglePhotos.download(photoUrl(item, side, webp), dest)
                            if (!Store.isTracked(item.key)) dest.delete()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: GooglePhotos.HttpStatusException) {
                        // Gone from Google (403/404): the next listing removes it; don't hold up completion.
                        if (e.code != 403 && e.code != 404) failed.incrementAndGet()
                    } catch (e: Exception) {
                        failed.incrementAndGet()
                    }
                    val n = done.incrementAndGet()
                    Store.status.value = SyncStatus(true, "Converting photos to ${format.uppercase()}: $n of ${todo.size}…")
                    if (n % 50 == 0) Store.bump()
                }
            }
        }
        if (failed.get() == 0) Store.photoFormatDone = format
    }

    private fun isWebp(f: File): Boolean {
        val head = ByteArray(12)
        val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(0)
        return n == 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
    }

    /** An original ready for [processVideos]; [temporary] when it was downloaded just for this. */
    private class Prepared(val item: Item, val file: File, val temporary: Boolean)

    /**
     * Stores each video the way [policy] wants. In "convert" mode the original is fetched (unless
     * it's already stored) and converted on the tablet, while at most one more original downloads.
     */
    private suspend fun processVideos(ctx: Context, queue: List<Item>, policy: String) {
        if (queue.isEmpty()) return
        val done = AtomicInteger()
        val label = when (policy) {
            Prefs.VIDEO_CONVERT -> "Converting videos to 1080p HEVC"
            Prefs.VIDEO_GOOGLE -> "Fetching compact videos"
            else -> "Fetching original videos"
        }
        coroutineScope {
            // Rendezvous: the next original downloads only while the current one converts.
            val ready = Channel<Prepared>(Channel.RENDEZVOUS)
            launch {
                try {
                    for (item in queue) {
                        coroutineContext.ensureActive()
                        if (!Store.isTracked(item.key)) continue
                        val p = try {
                            prepare(ctx, item, policy)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null  // left for the next sync
                        } ?: continue
                        ready.send(p)
                    }
                } finally {
                    ready.close()
                }
            }
            for (p in ready) {
                try {
                    Store.status.value = SyncStatus(true, "$label: ${done.get() + 1} of ${queue.size}…")
                    finish(ctx, p, policy)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Left for the next sync.
                } finally {
                    if (p.temporary) p.file.delete()
                    done.incrementAndGet()
                }
            }
        }
    }

    private fun freeBytes(ctx: Context) = StatFs(ctx.filesDir.path).availableBytes

    /**
     * Deletes temporary originals, conversions and downloads left by a sync that was cut short
     * (app update, reboot, the system stopping it). Only one sync runs at a time, so none is in use.
     */
    private fun clearLeftovers(ctx: Context) {
        val prefixes = listOf("original-", "converted-", "compact-")
        ctx.cacheDir.listFiles()?.forEach { f -> if (prefixes.any { f.name.startsWith(it) }) f.delete() }
    }

    /** Generous guess at an original's size (iPhone 4K60 HEVC is ~6 MB/s) before downloading it. */
    private fun originalEstimate(item: Item) = (item.durationMs / 1000 * (12L shl 20)).coerceIn(200L shl 20, 8L shl 30)

    /** Makes sure the source this video needs is on disk; null when nothing (more) is needed now. */
    private suspend fun prepare(ctx: Context, item: Item, policy: String): Prepared? {
        val stored = Store.mediaFile(item.key, MediaType.VIDEO)
        val variant = if (stored.exists()) Store.videoVariant(item.key) else null
        // Downloads must leave room for new photos and for the conversion itself.
        fun roomFor(bytes: Long) = freeBytes(ctx) > MIN_FREE_BYTES + VIDEO_MIN_FREE_BYTES + bytes
        return when (policy) {
            Prefs.VIDEO_CONVERT -> when {
                variant == VideoVariant.CONVERTED -> { Store.videoStored(item.key, variant, policy); null }
                // Only Google's version played on this tablet: keep it.
                variant != null && Store.wasRescued(item.key) -> { Store.videoStored(item.key, variant, policy); null }
                variant == VideoVariant.ORIGINAL ->
                    // Converting a stored original frees space; it needs room only for the smaller output.
                    if (freeBytes(ctx) > (1L shl 30) + stored.length() / 2) Prepared(item, stored, temporary = false) else null
                roomFor(originalEstimate(item)) -> {
                    val tmp = File(ctx.cacheDir, "original-${item.key}.mp4")
                    GooglePhotos.download(item.baseUrl + "=dv", tmp)
                    Prepared(item, tmp, temporary = true)
                }
                else -> null
            }
            Prefs.VIDEO_ORIGINAL -> {
                if (variant == VideoVariant.ORIGINAL) Store.videoStored(item.key, variant, policy)
                else if (roomFor(originalEstimate(item))) {
                    val tmp = File(ctx.cacheDir, "original-${item.key}.mp4")
                    GooglePhotos.download(item.baseUrl + "=dv", tmp)
                    place(tmp, stored, item.key)
                    if (!Store.videoStored(item.key, VideoVariant.ORIGINAL, policy)) stored.delete()
                }
                null
            }
            else -> {  // Google's compact version, unless what's stored is already 1080p or smaller
                val small = variant == VideoVariant.GOOGLE || variant == VideoVariant.CONVERTED ||
                    (variant == VideoVariant.ORIGINAL && (VideoConverter.probe(stored)?.shortSide ?: Int.MAX_VALUE) <= 1080)
                if (small) { Store.videoStored(item.key, variant!!, policy); return null }
                val tmp = File(ctx.cacheDir, "compact-${item.key}.mp4")
                try {
                    val made = if (downloadSmallerVideo(item, tmp)) VideoVariant.GOOGLE
                    else { GooglePhotos.download(item.baseUrl + "=dv", tmp); VideoVariant.ORIGINAL }
                    place(tmp, stored, item.key)
                    if (!Store.videoStored(item.key, made, policy)) stored.delete()
                } finally {
                    tmp.delete()
                }
                null
            }
        }
    }

    /** Convert mode: keep an efficient ≤1080p original as is, otherwise convert it on the tablet. */
    private suspend fun finish(ctx: Context, p: Prepared, policy: String) {
        val key = p.item.key
        val dest = Store.mediaFile(key, MediaType.VIDEO)
        val staging = File(ctx.cacheDir, "converted-$key.mp4")
        try {
            val probe = VideoConverter.probe(p.file)
            val converted = try {
                probe != null && !VideoConverter.keepAsIs(probe) && VideoConverter.convert(ctx, p.file, staging, probe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Retry transient failures on later syncs; give up only on unsupported formats or repeats.
                val permanent = e is ExportException && e.errorCode in PERMANENT_EXPORT_ERRORS
                if (!permanent && Store.conversionFailed(key) < 3) throw e
                fallBack(ctx, p, policy)
                return
            }
            when {
                converted -> {
                    place(staging, dest, key)
                    if (!Store.videoStored(key, VideoVariant.CONVERTED, policy)) dest.delete()
                }
                p.temporary -> {
                    // Keeping the original (already efficient, or conversion wouldn't help).
                    place(p.file, dest, key)
                    if (!Store.videoStored(key, VideoVariant.ORIGINAL, policy)) dest.delete()
                }
                else -> Store.videoStored(key, VideoVariant.ORIGINAL, policy)
            }
        } finally {
            staging.delete()
        }
    }

    /** The tablet can't convert this video: keep what's stored, or else use Google's 1080p version. */
    private fun fallBack(ctx: Context, p: Prepared, policy: String) {
        val key = p.item.key
        val dest = Store.mediaFile(key, MediaType.VIDEO)
        val existing = if (dest.exists()) Store.videoVariant(key) else null
        if (existing != null) {
            Store.videoStored(key, existing, policy)
            return
        }
        val tmp = File(ctx.cacheDir, "compact-$key.mp4")
        try {
            if (downloadSmallerVideo(p.item, tmp)) {
                place(tmp, dest, key)
                if (!Store.videoStored(key, VideoVariant.GOOGLE, policy)) dest.delete()
            } else {
                place(p.file, dest, key)
                if (!Store.videoStored(key, VideoVariant.ORIGINAL, policy)) dest.delete()
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Moves a finished file over the stored one, waiting (up to 30 minutes) while the slideshow is
     * playing that video, so its player never sees the file change underneath it.
     */
    private fun place(src: File, dest: File, key: String) {
        var waited = 0
        while (Store.nowPlaying == key && waited < 1800) {
            Thread.sleep(1000)
            waited++
        }
        if (!src.renameTo(dest)) {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
    }

    /**
     * Fetches Google's 1080p (or 720p) H.264 version. Returns false when Google has none (404);
     * other failures throw so the item is retried next sync.
     */
    private fun downloadSmallerVideo(item: Item, dest: File): Boolean {
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
