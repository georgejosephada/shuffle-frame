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
            "video_quality" to (R.array.video_quality_values to VIDEO_GOOGLE),
            "photo_format" to (R.array.photo_format_values to "webp"),
            "frame_touch_wait" to (R.array.touch_wait_values to "10"),
        )
        val edit = sp.edit()
        // The default became the lowest option; installs that relied on the old default keep it.
        if (!sp.contains("prefs_version") && sp.all.isNotEmpty() && !sp.contains("video_quality"))
            edit.putString("video_quality", VIDEO_CONVERT)
        edit.putInt("prefs_version", 2)
        // "Mute videos" became a volume level; keep what was chosen.
        if (!sp.contains("video_volume")) edit.putInt("video_volume", if (sp.getBoolean("mute_videos", true)) 0 else 100)
        edit.remove("mute_videos")
        // The refresh rate setting is gone (the lock screen keeps the display at its own rate).
        edit.remove("refresh_rate")
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
    val balanceAlbums get() = sp.getBoolean("balance_albums", true)
    /** A new order on every start or resume of the slideshow. */
    val trulyRandom get() = sp.getBoolean("truly_random", true)
    val includeVideos get() = sp.getBoolean("include_videos", true)
    /** Video sound as a share of the tablet's media volume, 0–100 (0 = muted). */
    var videoVolume: Int
        get() = sp.getInt("video_volume", 0).coerceIn(0, 100)
        set(v) = sp.edit().putInt("video_volume", v.coerceIn(0, 100)).apply()
    /** Convert mode: convert videos only while on the charger. */
    val convertOnlyCharging get() = sp.getBoolean("convert_only_charging", true)
    val maxVideoSeconds get() = int("max_video_seconds", 0)
    /** "google" (Google's compact 1080p), "convert" (1080p HEVC made on the tablet) or "original". */
    val videoQuality: String get() = sp.getString("video_quality", VIDEO_GOOGLE) ?: VIDEO_GOOGLE
    /** Photos as Google's WebP render (smaller) or JPEG. */
    val photoWebp get() = sp.getString("photo_format", "webp") == "webp"
    val syncMinutes get() = int("sync_interval", 30)
    /** Keep the screen on while a heavy sync runs on the charger (and ask to plug in otherwise). */
    val keepAwakeCharging get() = sp.getBoolean("keep_awake_charging", true)
    val wifiOnly get() = sp.getBoolean("wifi_only", true)
    val autoStart get() = sp.getBoolean("auto_start", false)
    val frameMode get() = sp.getBoolean("frame_mode", true)
    val frameMinBattery get() = int("frame_min_battery", 20)
    /** Frame mode only between [frameDayStart] and [frameDayEnd] (minutes into the day). */
    val frameNightOff get() = sp.getBoolean("frame_night_off", true)
    val frameDayStart get() = minutes("frame_day_start", 7 * 60)
    val frameDayEnd get() = minutes("frame_day_end", 22 * 60)
    /** At [frameDayStart] the frame turns the screen on by itself. */
    val frameMorningOn get() = sp.getBoolean("frame_morning_on", false)
    fun setMinutes(key: String, minutes: Int) =
        sp.edit().putString(key, "%02d:%02d".format(minutes / 60, minutes % 60)).apply()
    private fun minutes(key: String, default: Int): Int {
        val parts = sp.getString(key, null)?.split(":") ?: return default
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return default
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return default
        return if (h in 0..23 && m in 0..59) h * 60 + m else default
    }
    /**
     * How long the frame always waits before it comes up (after a wake or a screen-off), and
     * before it carries on after the notification shade was over it.
     */
    val frameTouchWaitMs get() = int("frame_touch_wait", 10) * 1000L
    val frameSkipPowerSaver get() = sp.getBoolean("frame_skip_power_saver", true)
    val frameSkipAirplane get() = sp.getBoolean("frame_skip_airplane", false)

    companion object {
        /** Player gain for a volume level; squared so the slider's middle sounds like half as loud. */
        fun gain(volume: Int): Float = (volume / 100f).let { it * it }

        const val VIDEO_CONVERT = "convert"
        const val VIDEO_GOOGLE = "google"
        const val VIDEO_ORIGINAL = "original"
    }
}
