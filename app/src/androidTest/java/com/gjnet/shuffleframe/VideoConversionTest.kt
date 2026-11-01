package com.gjnet.shuffleframe

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Converts a mix of stored original videos into the cache (the library is not touched) and checks
 * each result: HEVC, short side ≤ 1080, same orientation, smaller, and playable.
 * Run with: adb shell am instrument -w -e class com.gjnet.shuffleframe.VideoConversionTest ...
 */
@RunWith(AndroidJUnit4::class)
class VideoConversionTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun convertsSampleVideos() = runBlocking {
        val media = File(ctx.filesDir, "media")
        val originals = media.listFiles { f -> f.name.endsWith(".mp4") }.orEmpty()
            .filter { Store.videoVariant(it.nameWithoutExtension) == VideoVariant.ORIGINAL }
            .mapNotNull { f -> VideoConverter.probe(f)?.let { f to it } }
        // A mix: 4K portrait, 4K landscape, H.264, HDR, then the biggest.
        val picks = LinkedHashMap<String, Pair<File, VideoConverter.Probe>>()
        originals.firstOrNull { it.second.shortSide > 1080 && it.second.height > it.second.width && !it.second.hdr }?.let { picks["4k-portrait"] = it }
        originals.firstOrNull { it.second.shortSide > 1080 && it.second.width > it.second.height && !it.second.hdr }?.let { picks["4k-landscape"] = it }
        originals.firstOrNull { it.second.mime == "video/avc" }?.let { picks["h264"] = it }
        originals.firstOrNull { it.second.hdr }?.let { picks["hdr"] = it }
        originals.maxByOrNull { it.first.length() }?.let { picks["largest"] = it }
        Log.i(TAG, "originals available: ${originals.size}; picked ${picks.keys}")
        val failures = ArrayList<String>()
        for ((label, pick) in picks) {
            val (src, p) = pick
            val out = File(ctx.cacheDir, "test-$label.mp4").apply { delete() }
            val start = System.currentTimeMillis()
            val result = runCatching { VideoConverter.convert(ctx, src, out, p) }
            val secs = (System.currentTimeMillis() - start) / 1000.0
            val line = "$label ${p.mime} ${p.width}x${p.height} ${p.fps}fps hdr=${p.hdr} ${src.length() / 1_000_000}MB"
            result.exceptionOrNull()?.let {
                failures += "$label: ${it.javaClass.simpleName}: ${it.message}"
                Log.i(TAG, "$line -> FAILED after ${secs}s: $it")
                return@let
            }
            if (result.getOrNull() == false) {
                Log.i(TAG, "$line -> kept original (conversion not worth it) in ${secs}s")
                continue
            }
            val o = VideoConverter.probe(out)
            val playable = play(out)
            val ok = o != null && o.mime == "video/hevc" && o.shortSide <= 1080 &&
                (o.width > o.height) == (p.width > p.height) && out.length() < src.length() && playable == null
            Log.i(TAG, "$line -> ${o?.mime} ${o?.width}x${o?.height} hdr=${o?.hdr} ${out.length() / 1_000_000}MB " +
                "(${100 * out.length() / src.length()}%) in ${secs}s, plays=${playable ?: "yes"}, ok=$ok")
            if (!ok) failures += "$label: output check failed"
            if (label == "largest") Log.i(TAG, "kept for comparison: ${out.path} from ${src.path}") else out.delete()
        }
        assertTrue("Failures: $failures", failures.isEmpty())
    }

    private fun play(file: File): String? {
        val main = Handler(Looper.getMainLooper())
        val reader = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 3)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, main)
        val done = CountDownLatch(1)
        var result: String? = "timed out"
        var player: ExoPlayer? = null
        main.post {
            val p = ExoPlayer.Builder(ctx).build()
            player = p
            p.setVideoSurface(reader.surface)
            p.volume = 0f
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    main.postDelayed({ if (done.count > 0L) { result = null; done.countDown() } }, 1500)
                }
                override fun onPlayerError(error: PlaybackException) {
                    result = error.errorCodeName; done.countDown()
                }
            })
            p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            p.prepare(); p.play()
        }
        done.await(20, TimeUnit.SECONDS)
        val released = CountDownLatch(1)
        main.post { player?.release(); reader.close(); released.countDown() }
        released.await(5, TimeUnit.SECONDS)
        return result
    }

    private companion object {
        const val TAG = "VideoConversionTest"
    }
}
