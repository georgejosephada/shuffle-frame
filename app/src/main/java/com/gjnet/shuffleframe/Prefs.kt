package com.gjnet.shuffleframe

import android.content.Context
import androidx.preference.PreferenceManager

class Prefs(ctx: Context) {
    private val sp = PreferenceManager.getDefaultSharedPreferences(ctx)

    /** Moves list settings whose stored value is no longer offered (after an update) to the default. */
    fun dropRetiredValues(ctx: Context) {
        val lists = mapOf(
            "photo_seconds" to (R.array.photo_seconds_values to "10"),
            "sync_interval" to (R.array.sync_values to "30"),
            "video_quality" to (R.array.video_quality_values to VIDEO_CONVERT),
            "photo_format" to (R.array.photo_format_values to "webp"),
        )
        val edit = sp.edit()
        for ((key, spec) in lists) {
            val value = sp.getString(key, null) ?: continue
            if (value !in ctx.resources.getStringArray(spec.first)) edit.putString(key, spec.second)
        }
        edit.apply()
    }

    private fun int(key: String, default: Int) = sp.getString(key, null)?.toIntOrNull() ?: default

    val photoSeconds get() = int("photo_seconds", 10)
    val fill get() = sp.getString("fit_mode", "fit") == "fill"
    val blurBackground get() = sp.getBoolean("blur_background", true)
    val kenBurns get() = sp.getBoolean("ken_burns", true)
    val showCaption get() = sp.getBoolean("show_caption", true)
    val balanceAlbums get() = sp.getBoolean("balance_albums", false)
    val includeVideos get() = sp.getBoolean("include_videos", true)
    val muteVideos get() = sp.getBoolean("mute_videos", true)
    val maxVideoSeconds get() = int("max_video_seconds", 0)
    /** "convert" (1080p HEVC made on the tablet), "google" (Google's compact 1080p) or "original". */
    val videoQuality: String get() = sp.getString("video_quality", VIDEO_CONVERT) ?: VIDEO_CONVERT
    /** Photos as Google's WebP render (smaller) or JPEG. */
    val photoWebp get() = sp.getString("photo_format", "webp") == "webp"
    val syncMinutes get() = int("sync_interval", 30)
    val wifiOnly get() = sp.getBoolean("wifi_only", true)
    val autoStart get() = sp.getBoolean("auto_start", false)
    val frameMode get() = sp.getBoolean("frame_mode", true)
    val frameMinBattery get() = int("frame_min_battery", 20)
    val frameSkipPowerSaver get() = sp.getBoolean("frame_skip_power_saver", true)
    val frameSkipAirplane get() = sp.getBoolean("frame_skip_airplane", false)

    companion object {
        const val VIDEO_CONVERT = "convert"
        const val VIDEO_GOOGLE = "google"
        const val VIDEO_ORIGINAL = "original"
    }
}
