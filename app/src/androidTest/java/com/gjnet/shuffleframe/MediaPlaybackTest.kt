package com.gjnet.shuffleframe

import android.graphics.ImageDecoder
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Checks every downloaded item the way the slideshow shows it: photos through ImageDecoder,
 * videos through ExoPlayer until frames have rendered for a couple of seconds.
 * Run with: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MediaPlaybackTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val media = File(ctx.filesDir, "media")

    @Test
    fun allPhotosDecode() {
        val bad = media.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().filter { f ->
            runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) }.isFailure
        }
        Log.i(TAG, "photos failing: ${bad.map { it.name }}")
        assertTrue("Photos that failed to decode: ${bad.map { it.name }}", bad.isEmpty())
    }

    @Test
    fun allVideosPlay() {
        val videos = media.listFiles { f -> f.name.endsWith(".mp4") }.orEmpty().sortedBy { it.name }
        val failures = videos.mapNotNull { f -> play(f)?.let { "${f.name}: $it" } }
        Log.i(TAG, "videos checked: ${videos.size}, failing: $failures")
        assertTrue("Videos that failed: $failures", failures.isEmpty())
    }

    /** Returns null when playback rendered frames for 2 s without error, else the reason. */
    private fun play(file: File): String? {
        val main = Handler(Looper.getMainLooper())
        val reader = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 3)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, main)
        val done = CountDownLatch(1)
        var result: String? = "timed out before the first frame"
        var player: ExoPlayer? = null
        main.post {
            val p = ExoPlayer.Builder(ctx).build()
            player = p
            p.setVideoSurface(reader.surface)
            p.volume = 0f
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    main.postDelayed({
                        if (done.count > 0L) { result = null; done.countDown() }
                    }, 2000)
                }
                override fun onPlayerError(error: PlaybackException) {
                    result = "${error.errorCodeName}: ${error.cause?.message ?: error.message}"
                    done.countDown()
                }
            })
            p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            p.prepare()
            p.play()
        }
        done.await(20, TimeUnit.SECONDS)
        val released = CountDownLatch(1)
        main.post { player?.release(); reader.close(); released.countDown() }
        released.await(5, TimeUnit.SECONDS)
        Log.i(TAG, "${file.name}: ${result ?: "ok"}")
        return result
    }

    private companion object {
        const val TAG = "MediaPlaybackTest"
    }
}
