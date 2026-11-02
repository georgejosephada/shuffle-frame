package com.gjnet.shuffleframe

import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Holds the sync's screen wake lock for 15 s so `dumpsys power` can show it (run on the charger).
 * -e frame on|off sets frame mode for the run (restored afterwards); with it on, no lock is held.
 */
@RunWith(AndroidJUnit4::class)
class KeepAwakeTest {
    @Test
    fun holdsScreenOnWhilePlugged() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val before = sp.getBoolean("frame_mode", true)
        InstrumentationRegistry.getArguments().getString("frame")?.let { sp.edit().putBoolean("frame_mode", it == "on").commit() }
        try {
            KeepAwake.begin(ctx)
            try { Thread.sleep(15_000) } finally { KeepAwake.end(ctx) }
        } finally {
            sp.edit().putBoolean("frame_mode", before).commit()
        }
    }
}
