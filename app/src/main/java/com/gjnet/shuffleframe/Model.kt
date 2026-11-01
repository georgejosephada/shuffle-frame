package com.gjnet.shuffleframe

import java.io.File

enum class MediaType { PHOTO, VIDEO }

data class Album(
    val key: String,
    val authKey: String,
    val shareUrl: String,
    val title: String,
    val enabled: Boolean = true,
    val coverUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
)

/** One media item as listed by Google Photos. [missCount] counts consecutive syncs it was absent. */
data class Item(
    val key: String,
    val type: MediaType,
    val baseUrl: String,
    val width: Int,
    val height: Int,
    val takenAt: Long,
    val tzOffsetMs: Long,
    val durationMs: Long,
    val missCount: Int = 0,
)

data class AlbumSummary(val album: Album, val photos: Int, val videos: Int, val downloaded: Int, val hidden: Int) {
    val total get() = photos + videos
}

data class PlayItem(
    val key: String,
    val albumKey: String,
    val albumTitle: String,
    val type: MediaType,
    val file: File,
    val takenAt: Long,
    val tzOffsetMs: Long,
    val authKey: String,
) {
    /** Opens this item inside the shared album, where Google Photos offers "Remove from album". */
    val photosUrl get() = "https://photos.google.com/share/$albumKey/photo/$key?key=$authKey"
}

data class SyncStatus(val running: Boolean = false, val message: String = "")
