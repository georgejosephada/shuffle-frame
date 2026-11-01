package com.gjnet.shuffleframe

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Converts videos on the tablet's hardware encoder to HEVC with the short side at most 1080 px,
 * at a bitrate high enough that only the resolution drops, not the visible quality.
 */
object VideoConverter {
    private const val MAX_SHORT_SIDE = 1080
    /**
     * HEVC bits per pixel per frame: ~12.5 Mbit/s for 1080p30. Measured on a detailed 90 Mbit/s 4K
     * clip: SSIM 0.957 against the original scaled to 1080p (Google's 1080p version: 0.930,
     * 0.12 bpp: 0.944, 0.3 bpp: 0.965 at 1.5x the size).
     */
    private const val BITS_PER_PIXEL = 0.2
    private const val MIN_BITRATE = 2_000_000
    /** Keep the original when the conversion would save less than this share. */
    private const val MIN_SAVING = 0.15

    /** Codecs already as efficient as HEVC: at 1080p or below these are kept as they are. */
    private val EFFICIENT = setOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_VP9)

    /** Display-oriented size and stream facts of a video file. */
    data class Probe(
        val mime: String, val width: Int, val height: Int, val fps: Float, val bitrate: Long, val hdr: Boolean,
    ) {
        val shortSide get() = min(width, height)
    }

    private val thread by lazy { HandlerThread("video-convert").apply { start() } }

    fun probe(file: File): Probe? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                var w = f.getInteger(MediaFormat.KEY_WIDTH)
                var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                val rotation = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rotation == 90 || rotation == 270) w = h.also { h = w }
                val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                    .getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE) } else 30f
                val durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                val bitrate = when {
                    f.containsKey(MediaFormat.KEY_BIT_RATE) -> f.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                    durationUs > 0 -> file.length() * 8 * 1_000_000 / durationUs
                    else -> 0L
                }
                val transfer = if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                val hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG ||
                    mime == MimeTypes.VIDEO_DOLBY_VISION
                return Probe(mime, w, h, fps, bitrate, hdr)
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            ex.release()
        }
    }

    /** True when the file is fine as it is: an efficient codec at 1080p or below. */
    fun keepAsIs(p: Probe) = p.mime in EFFICIENT && p.shortSide <= MAX_SHORT_SIDE

    /**
     * Converts [src] into [dest] (via a temporary file). Returns false, leaving [dest] untouched,
     * when the result wouldn't be meaningfully smaller; throws when the conversion fails.
     */
    suspend fun convert(ctx: Context, src: File, dest: File, p: Probe, bitsPerPixel: Double = BITS_PER_PIXEL): Boolean {
        val scale = min(1.0, MAX_SHORT_SIDE.toDouble() / p.shortSide)
        val outW = even(p.width * scale)
        val outH = even(p.height * scale)
        val fps = p.fps.coerceIn(24f, 60f)
        val byQuality = outW.toDouble() * outH * fps * bitsPerPixel
        // Never above what the source carries (scaled down with the resolution; H.264 needs ~40% more than HEVC).
        val efficiency = if (p.mime in EFFICIENT) 1.0 else 0.6
        val bySource = if (p.bitrate > 0) p.bitrate * (outW.toDouble() * outH / (p.width.toDouble() * p.height)).pow(0.75) * efficiency
        else byQuality
        val bitrate = max(MIN_BITRATE.toDouble(), min(byQuality, bySource)).roundToInt()

        val tmp = File(dest.path + ".conv.mp4")
        try {
            try {
                export(ctx, src, tmp, outW, outH, bitrate, Composition.HDR_MODE_KEEP_HDR)
            } catch (e: ExportException) {
                if (!p.hdr) throw e
                // This encoder can't write HDR: tone-map to SDR instead.
                tmp.delete()
                export(ctx, src, tmp, outW, outH, bitrate, Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            }
            val out = probe(tmp) ?: throw IllegalStateException("Converted file is unreadable")
            if (out.shortSide > MAX_SHORT_SIDE || tmp.length() == 0L) throw IllegalStateException("Unexpected conversion result")
            if (tmp.length() > src.length() * (1 - MIN_SAVING)) return false
            if (!tmp.renameTo(dest)) throw IllegalStateException("Could not save ${dest.name}")
            return true
        } finally {
            tmp.delete()
        }
    }

    private fun even(v: Double) = (v.roundToInt() / 2) * 2

    private suspend fun export(ctx: Context, src: File, out: File, w: Int, h: Int, bitrate: Int, hdrMode: Int): ExportResult =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(thread.looper)
            var transformer: Transformer? = null
            handler.post {
                val t = Transformer.Builder(ctx)
                    .setLooper(thread.looper)
                    .setVideoMimeType(MimeTypes.VIDEO_H265)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(ctx)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                            .setEnableFallback(true)
                            .build()
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(exportResult)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer = t
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                    .setEffects(Effects(listOf(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))))
                    .build()
                val composition = Composition.Builder(EditedMediaItemSequence(item)).setHdrMode(hdrMode).build()
                t.start(composition, out.path)
            }
            cont.invokeOnCancellation { handler.post { transformer?.cancel() } }
        }
}
