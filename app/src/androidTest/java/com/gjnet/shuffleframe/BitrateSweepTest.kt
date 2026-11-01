package com.gjnet.shuffleframe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Converts one stored video (-e key <itemKey>) at several bitrates into the cache for comparison. */
@RunWith(AndroidJUnit4::class)
class BitrateSweepTest {
    @Test
    fun sweep() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val key = InstrumentationRegistry.getArguments().getString("key")!!
        val src = File(ctx.filesDir, "media/$key.mp4")
        val p = VideoConverter.probe(src)!!
        for (bpp in listOf(0.12, 0.2, 0.3)) {
            val out = File(ctx.cacheDir, "sweep-$bpp.mp4").apply { delete() }
            val t = System.currentTimeMillis()
            VideoConverter.convert(ctx, src, out, p, bpp)
            Log.i("BitrateSweep", "bpp=$bpp ${out.length() / 1_000_000}MB in ${(System.currentTimeMillis() - t) / 1000}s -> ${out.path}")
        }
    }
}
