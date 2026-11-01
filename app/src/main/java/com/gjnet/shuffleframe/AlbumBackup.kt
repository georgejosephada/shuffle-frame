package com.gjnet.shuffleframe

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The album list (titles and share links) as a small JSON file: kept up to date automatically in
 * Download/Shuffle Frame on the tablet, and saved or restored anywhere by the user (e.g. Drive).
 * Anyone holding the file can view the albums, so it's never sent anywhere automatically.
 */
object AlbumBackup {
    private const val FORMAT = 1
    private const val FILE_NAME = "shuffle-frame-albums.json"
    private const val KEY_URI = "backup_copy_uri"
    const val FOLDER = "Download/Shuffle Frame"

    class Entry(val title: String, val shareUrl: String, val enabled: Boolean)
    class RestoreResult(val added: Int, val alreadyThere: Int, val failed: List<String>)

    fun toJson(albums: List<Album>): String = JSONObject()
        .put("app", "shuffle-frame")
        .put("format", FORMAT)
        .put("exportedAt", System.currentTimeMillis())
        .put("albums", JSONArray().apply {
            albums.forEach { put(JSONObject().put("title", it.title).put("shareUrl", it.shareUrl).put("enabled", it.enabled)) }
        })
        .toString(1)

    fun parse(text: String): List<Entry> {
        val arr = JSONObject(text).optJSONArray("albums") ?: throw IllegalArgumentException("Not a Shuffle Frame album list")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.optString("title"), o.getString("shareUrl"), o.optBoolean("enabled", true))
        }
    }

    /** Rewrites the automatic copy in Download/Shuffle Frame (recreating it if it was deleted). */
    fun saveAutoCopy(ctx: Context) {
        val albums = Store.albums()
        if (albums.isEmpty()) return  // never overwrite a good copy with an empty list
        val bytes = toJson(albums).toByteArray()
        val resolver = ctx.contentResolver
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefs.getString(KEY_URI, null)?.let { saved ->
            try {
                resolver.openOutputStream(Uri.parse(saved), "wt")?.use { it.write(bytes); return }
            } catch (e: Exception) {
                // Deleted or no longer ours (e.g. after a reinstall): create a new one below.
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Shuffle Frame")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
        prefs.edit().putString(KEY_URI, uri.toString()).apply()
    }

    /** Adds every album from the backup that isn't in the list yet; existing ones are left alone. */
    fun restore(ctx: Context, entries: List<Entry>): RestoreResult {
        val known = Store.albums().map { it.shareUrl }.toSet()
        var added = 0
        var already = 0
        val failed = ArrayList<String>()
        for (e in entries) {
            if (e.shareUrl in known) { already++; continue }
            try {
                val r = GooglePhotos.resolve(e.shareUrl)
                val title = e.title.ifBlank { r.title }
                if (Store.addAlbum(Album(r.albumKey, r.authKey, e.shareUrl, title, enabled = e.enabled))) added++ else already++
            } catch (ex: Exception) {
                failed += e.title.ifBlank { e.shareUrl }
            }
        }
        if (added > 0) SyncScheduler.syncNow(ctx)
        return RestoreResult(added, already, failed)
    }
}
