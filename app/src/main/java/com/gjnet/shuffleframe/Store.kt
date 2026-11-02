package com.gjnet.shuffleframe

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Process-wide state: the albums the user picked, the last listing of each album and the
 * media files downloaded for them. Persisted as one JSON file; media lives in files/media.
 */
object Store {
    /**
     * A sync that would remove more than half of an album (and more than this many items) is
     * treated as suspect: the items are hidden but only deleted if the next sync agrees.
     */
    private const val MASS_REMOVAL_MIN = 10
    /** Raised when the rules for how videos are stored change, so they're checked again. */
    private const val VIDEO_RULES = 2

    private lateinit var root: File
    private lateinit var mediaDir: File
    private lateinit var coverDir: File

    private val albums = LinkedHashMap<String, Album>()
    private val items = HashMap<String, LinkedHashMap<String, Item>>()
    /** Videos whose original failed to play; the next sync swaps in Google's H.264 transcode. */
    private val needsTranscode = HashSet<String>()
    /** Items already re-fetched once after a playback failure (transcoded video or re-downloaded photo). */
    private val transcoded = HashSet<String>()
    /** Items that failed even after transcoding; never shown. */
    private val broken = HashSet<String>()
    /** Items the user chose to hide on this tablet only. */
    private val hidden = HashSet<String>()
    /** How each stored video was made: [VideoVariant]. */
    private val videoVariants = HashMap<String, VideoVariant>()
    /** Videos already stored the way [videoPolicy] wants; anything else gets converted or re-fetched. */
    private val videoDone = HashSet<String>()
    private var videoPolicy = ""
    /** Photo format of the last complete conversion pass; new downloads already use the current one. */
    private var photoFormatDoneLoaded = ""
    var photoFormatDone: String
        @Synchronized get() = photoFormatDoneLoaded
        @Synchronized set(value) { photoFormatDoneLoaded = value; save() }
    /** Video the slideshow is playing right now; its file isn't replaced until it moves on. */
    @Volatile var nowPlaying: String? = null
    /** Failed conversion attempts per video (kept in memory: retried a few times per app run). */
    private val conversionFailures = HashMap<String, Int>()
    /** Legacy record (before variants): videos known to be stored as originals. */
    private val legacyOriginals = HashSet<String>()
    /** state.json couldn't be read this run: keep every file until the album list is restored. */
    private var loadFailed = false

    private val _albumsChanged = MutableStateFlow(0L)
    /** Bumped when the album list itself (links, titles, on/off) changes; drives the backup copy. */
    val albumsChanged: StateFlow<Long> = _albumsChanged

    private val _version = MutableStateFlow(0L)
    /** Bumped whenever albums, listings or downloaded media change. */
    val version: StateFlow<Long> = _version
    val status = MutableStateFlow(SyncStatus())

    @Synchronized
    fun init(ctx: Context) {
        if (::root.isInitialized) return
        root = ctx.filesDir
        mediaDir = File(root, "media").apply { mkdirs() }
        coverDir = File(root, "covers").apply { mkdirs() }
        mediaDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        load()
        // Classify videos stored before variants were recorded: the old "originals" list holds the
        // originals; every other stored video came from Google's smaller version.
        var migrated = false
        items.values.forEach { m ->
            m.values.filter { it.type == MediaType.VIDEO && it.key !in videoVariants && mediaFile(it.key, it.type).exists() }
                .forEach {
                    videoVariants[it.key] = if (it.key in legacyOriginals) VideoVariant.ORIGINAL else VideoVariant.GOOGLE
                    migrated = true
                }
        }
        if (migrated) save()
    }

    fun mediaFile(key: String, type: MediaType) =
        File(mediaDir, key + if (type == MediaType.VIDEO) ".mp4" else ".jpg")

    fun coverFile(albumKey: String) = File(coverDir, "$albumKey.jpg")

    fun bump() = _version.update { it + 1 }

    @Synchronized
    fun albums(): List<Album> = albums.values.toList()

    @Synchronized
    fun summaries(): List<AlbumSummary> = albums.values.map { a ->
        val its = items[a.key]?.values.orEmpty()
        AlbumSummary(
            a,
            photos = its.count { it.missCount == 0 && it.type == MediaType.PHOTO },
            videos = its.count { it.missCount == 0 && it.type == MediaType.VIDEO },
            downloaded = its.count { it.missCount == 0 && mediaFile(it.key, it.type).exists() },
            hidden = its.count { it.key in hidden },
        )
    }

    @Synchronized
    fun itemCount(albumKey: String) = items[albumKey]?.size ?: 0

    /** Enabled albums that haven't synced successfully for [maxAgeMs] (or since they were added). */
    @Synchronized
    fun staleAlbums(maxAgeMs: Long): List<Album> {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        return albums.values.filter { it.enabled && maxOf(it.lastSyncAt, it.addedAt) < cutoff }
    }

    @Synchronized
    fun addAlbum(album: Album): Boolean {
        if (albums.containsKey(album.key)) return false
        albums[album.key] = album
        save(); bump()
        _albumsChanged.update { it + 1 }
        return true
    }

    @Synchronized
    fun updateAlbum(key: String, change: (Album) -> Album) {
        val a = albums[key] ?: return
        val b = change(a)
        albums[key] = b
        save(); bump()
        if (a.title != b.title || a.enabled != b.enabled || a.shareUrl != b.shareUrl) _albumsChanged.update { it + 1 }
    }

    @Synchronized
    fun removeAlbum(key: String) {
        albums.remove(key) ?: return
        val gone = items.remove(key)?.values.orEmpty()
        for (it in gone) if (!isReferenced(it.key)) deleteMedia(it.key)
        coverFile(key).delete()
        save(); bump()
        _albumsChanged.update { it + 1 }
    }

    /**
     * Mirrors a complete listing of an album: items no longer in it are dropped and their files
     * deleted (unless another album has them). A suspiciously large removal (or an empty album)
     * is only hidden until the next sync confirms it. Returns the listed items whose media still needs downloading.
     */
    @Synchronized
    fun applyListing(albumKey: String, remote: List<Item>): List<Item> {
        if (!albums.containsKey(albumKey)) return emptyList()
        val old = items[albumKey].orEmpty()
        val fresh = LinkedHashMap<String, Item>()
        for (r in remote) fresh[r.key] = r
        val gone = old.values.filter { !fresh.containsKey(it.key) }
        // An album that suddenly lists as empty is treated like a mass removal: hidden now, deleted on the next empty sync.
        val suspect = remote.isEmpty() || (gone.size > MASS_REMOVAL_MIN && gone.size * 2 > old.size)
        val dropped = ArrayList<String>()
        for (it in gone) {
            if (suspect && it.missCount == 0) fresh[it.key] = it.copy(missCount = 1) else dropped += it.key
        }
        items[albumKey] = fresh
        for (k in dropped) if (!isReferenced(k)) deleteMedia(k)
        save(); bump()
        return fresh.values.filter { it.missCount == 0 && !mediaFile(it.key, it.type).exists() }
    }

    @Synchronized
    fun videoVariant(key: String): VideoVariant? = videoVariants[key]

    /**
     * Records how the video file now on disk was made, and that it suits [policy]. Returns false
     * (recording nothing) when no album lists the video any more; the caller then deletes the file.
     */
    @Synchronized
    fun videoStored(key: String, variant: VideoVariant, policy: String): Boolean {
        if (!isReferenced(key)) return false
        videoVariants[key] = variant
        if (policy == videoPolicy) videoDone.add(key)
        save(); bump()
        return true
    }

    /** Videos that only played after Google's H.264 version was fetched: leave them as they are. */
    @Synchronized
    fun wasRescued(key: String) = key in transcoded || key in needsTranscode

    /** Counts a failed conversion; returns how many there have been for this video. */
    @Synchronized
    fun conversionFailed(key: String): Int = (conversionFailures[key] ?: 0).plus(1).also { conversionFailures[key] = it }

    /**
     * Videos (listed, not removed) that aren't yet stored the way [policy] wants: missing ones
     * first so new videos appear soon, then the biggest stored files.
     */
    @Synchronized
    fun videosToProcess(policy: String): List<Item> {
        if (policy != videoPolicy) {
            videoPolicy = policy
            videoDone.clear()
            save()
        }
        val seen = HashSet<String>()
        return items.values.asSequence().flatMap { it.values.asSequence() }
            .filter {
                it.type == MediaType.VIDEO && it.missCount == 0 && it.key !in broken && seen.add(it.key) &&
                    // "Done" only counts while the file is there (it may have been removed and re-added).
                    !(it.key in videoDone && mediaFile(it.key, it.type).exists())
            }
            .sortedWith(compareBy<Item> { mediaFile(it.key, it.type).exists() }.thenByDescending { mediaFile(it.key, it.type).length() })
            .toList()
    }

    /** Stored photos of enabled albums (each once) for the format conversion pass. */
    @Synchronized
    fun storedPhotos(): List<Item> {
        val seen = HashSet<String>()
        return albums.values.asSequence().filter { it.enabled }.flatMap { items[it.key]?.values.orEmpty().asSequence() }
            .filter { it.type == MediaType.PHOTO && it.missCount == 0 && seen.add(it.key) && mediaFile(it.key, it.type).exists() }
            .toList()
    }

    @Synchronized
    fun transcodeQueue(): List<Item> = needsTranscode.mapNotNull { k ->
        items.values.firstNotNullOfOrNull { it[k] }
    }

    @Synchronized
    fun markTranscoded(key: String) {
        needsTranscode.remove(key)
        videoVariants[key] = VideoVariant.GOOGLE
        videoDone.add(key)
        transcoded.add(key)
        save(); bump()
    }

    /**
     * Called by the player when a file can't be shown. First failure: a video is queued for
     * Google's H.264 version and a photo is deleted so the next sync downloads it again.
     * A second failure hides the item for good.
     */
    @Synchronized
    fun reportPlaybackFailure(key: String, type: MediaType) {
        // Already handled (file deleted for re-download) by an earlier report.
        if (!mediaFile(key, type).exists()) return
        when {
            key in transcoded -> broken.add(key)
            type == MediaType.VIDEO -> needsTranscode.add(key)
            else -> {
                mediaFile(key, type).delete()
                transcoded.add(key)
            }
        }
        save(); bump()
    }

    @Synchronized
    fun hide(key: String) {
        hidden.add(key)
        save(); bump()
    }

    @Synchronized
    fun unhideAll(albumKey: String) {
        items[albumKey]?.keys?.let { hidden.removeAll(it) }
        save(); bump()
    }

    @Synchronized
    fun playable(): List<PlayItem> {
        val out = ArrayList<PlayItem>()
        for (a in albums.values) {
            if (!a.enabled) continue
            for (it in items[a.key]?.values.orEmpty()) {
                if (it.missCount > 0 || it.key in broken || it.key in needsTranscode || it.key in hidden) continue
                val f = mediaFile(it.key, it.type)
                if (f.exists()) out += PlayItem(it.key, a.key, a.title, it.type, f, it.takenAt, it.tzOffsetMs, a.authKey)
            }
        }
        return out
    }

    fun storageBytes(): Long = mediaDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun isReferenced(key: String) = items.values.any { it.containsKey(key) }

    private fun deleteMedia(key: String) {
        for (t in MediaType.values()) mediaFile(key, t).delete()
        needsTranscode.remove(key)
        transcoded.remove(key)
        broken.remove(key)
        hidden.remove(key)
        videoVariants.remove(key)
        videoDone.remove(key)
    }

    private fun stateFile() = AtomicFile(File(root, "state.json"))

    /** True while some album still lists [key]; downloads for untracked items are discarded. */
    @Synchronized
    fun isTracked(key: String) = isReferenced(key)

    /** Deletes media and covers nothing refers to any more (e.g. downloads that finished after their album was removed). */
    @Synchronized
    fun sweepOrphans() {
        // After a failed load the media are still valid (named by item key) and get reused once
        // the album list is restored, so never treat them as orphans then.
        if (loadFailed || items.values.all { it.isEmpty() }) return
        val keep = HashSet<String>()
        items.values.forEach { keep.addAll(it.keys) }
        mediaDir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(".part") && f.nameWithoutExtension !in keep) f.delete()
        }
        coverDir.listFiles()?.forEach { f -> if (f.nameWithoutExtension !in albums) f.delete() }
    }

    private fun save() {
        val o = JSONObject()
        o.put("albums", JSONArray().apply { albums.values.forEach { put(it.toJson()) } })
        o.put("items", JSONObject().apply {
            items.forEach { (k, m) -> put(k, JSONArray().apply { m.values.forEach { put(it.toJson()) } }) }
        })
        o.put("needsTranscode", JSONArray(needsTranscode.toList()))
        o.put("transcoded", JSONArray(transcoded.toList()))
        o.put("broken", JSONArray(broken.toList()))
        o.put("hidden", JSONArray(hidden.toList()))
        o.put("videoVariants", JSONObject().apply { videoVariants.forEach { (k, v) -> put(k, v.name) } })
        o.put("videoDone", JSONArray(videoDone.toList()))
        o.put("videoPolicy", videoPolicy)
        o.put("videoRules", VIDEO_RULES)
        o.put("photoFormatDone", photoFormatDoneLoaded)
        val file = stateFile()
        val out = file.startWrite()
        try {
            out.write(o.toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    /** Loads all-or-nothing; an unreadable file is kept aside as state.json.bad rather than overwritten. */
    private fun load() {
        val file = stateFile()
        if (!file.baseFile.exists()) return
        try {
            val o = JSONObject(String(file.readFully()))
            val a = LinkedHashMap<String, Album>()
            o.optJSONArray("albums")?.let { arr ->
                for (i in 0 until arr.length()) arr.getJSONObject(i).toAlbum().let { a[it.key] = it }
            }
            val its = HashMap<String, LinkedHashMap<String, Item>>()
            o.optJSONObject("items")?.let { io ->
                for (k in io.keys()) {
                    val arr = io.getJSONArray(k)
                    its[k] = LinkedHashMap<String, Item>().apply {
                        for (i in 0 until arr.length()) arr.getJSONObject(i).toItem().let { put(it.key, it) }
                    }
                }
            }
            albums.putAll(a)
            items.putAll(its)
            o.optJSONArray("needsTranscode")?.strings()?.let(needsTranscode::addAll)
            o.optJSONArray("transcoded")?.strings()?.let(transcoded::addAll)
            o.optJSONArray("broken")?.strings()?.let(broken::addAll)
            o.optJSONArray("hidden")?.strings()?.let(hidden::addAll)
            o.optJSONArray("originals")?.strings()?.let(legacyOriginals::addAll)
            o.optJSONObject("videoVariants")?.let { v ->
                for (k in v.keys()) runCatching { videoVariants[k] = VideoVariant.valueOf(v.getString(k)) }
            }
            videoPolicy = o.optString("videoPolicy")
            // Google mode used to keep the tablet's own 1080p conversions: check those videos again.
            val recheck = o.optInt("videoRules", 1) < VIDEO_RULES && videoPolicy == Prefs.VIDEO_GOOGLE
            if (!recheck) o.optJSONArray("videoDone")?.strings()?.let(videoDone::addAll)
            photoFormatDoneLoaded = o.optString("photoFormatDone")
        } catch (e: Exception) {
            android.util.Log.e("Store", "Could not read state; keeping it as state.json.bad", e)
            file.baseFile.renameTo(File(root, "state.json.bad"))
            loadFailed = true
        }
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun Album.toJson() = JSONObject()
        .put("key", key).put("authKey", authKey).put("shareUrl", shareUrl).put("title", title)
        .put("enabled", enabled).put("coverUrl", coverUrl).put("addedAt", addedAt)
        .put("lastSyncAt", lastSyncAt).put("lastError", lastError)

    private fun JSONObject.toAlbum() = Album(
        key = getString("key"),
        authKey = getString("authKey"),
        shareUrl = getString("shareUrl"),
        title = getString("title"),
        enabled = optBoolean("enabled", true),
        coverUrl = optString("coverUrl").ifEmpty { null },
        addedAt = optLong("addedAt"),
        lastSyncAt = optLong("lastSyncAt"),
        lastError = optString("lastError").ifEmpty { null },
    )

    private fun Item.toJson() = JSONObject()
        .put("key", key).put("type", type.name).put("baseUrl", baseUrl)
        .put("w", width).put("h", height).put("takenAt", takenAt).put("tz", tzOffsetMs)
        .put("dur", durationMs).put("miss", missCount)

    private fun JSONObject.toItem() = Item(
        key = getString("key"),
        type = MediaType.valueOf(getString("type")),
        baseUrl = getString("baseUrl"),
        width = optInt("w"),
        height = optInt("h"),
        takenAt = optLong("takenAt"),
        tzOffsetMs = optLong("tz"),
        durationMs = optLong("dur"),
        missCount = optInt("miss"),
    )
}
