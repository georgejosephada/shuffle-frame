package com.gjnet.shuffleframe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The daily background step: a newer GitHub release is downloaded and announced, not installed. */
@RunWith(AndroidJUnit4::class)
class UpdateFlowTest {
    @Test
    fun downloadsWithoutInstalling() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val before = Updater.installedVersion(ctx)
        Updater.run(ctx, installNow = false)
        val pending = Updater.pendingVersion(ctx)
        Log.i("UpdateFlowTest", "installed=$before pending=$pending status=${Updater.status(ctx)}")
        assertTrue("expected a newer pending update", pending != null && pending > before)
        assertTrue("must not have installed", Updater.installedVersion(ctx) == before)
    }
}
