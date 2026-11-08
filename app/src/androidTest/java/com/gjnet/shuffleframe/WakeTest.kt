package com.gjnet.shuffleframe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame mode on wake, on the device (frame mode on, tablet locked, not on the charger, notification
 * sound audible): a wake that comes with a notification sound leaves the frame off, a plain wake
 * brings it back. Leaves the slideshow showing.
 */
@RunWith(AndroidJUnit4::class)
class WakeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).close()
    private fun heldOff() = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("frame_held_off", false)
    private fun interactive() = ctx.getSystemService(PowerManager::class.java).isInteractive
    private fun onMain(block: () -> Unit) = inst.runOnMainSync(block)
    private fun visible(): Int { var v = 0; onMain { v = SlideshowActivity.visible }; return v }

    /** Screen off as if by the power button over the slideshow. */
    private fun switchOffOverFrame() {
        onMain { FrameService.setHeldOff(ctx, true) }
        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(3_000)
        assertTrue("screen should be off", !interactive())
        assertTrue("held off after power", heldOff())
    }

    @Test
    fun notificationWakeKeepsFrameOff() {
        Thread.sleep(3_000)  // FrameService starting with the app
        switchOffOverFrame()

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("wake_test", "Wake test", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(99, NotificationCompat.Builder(ctx, "wake_test")
            .setSmallIcon(R.drawable.ic_frame).setContentTitle("Wake test").build())
        Thread.sleep(300)
        shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(4_000)
        nm.cancel(99)
        nm.deleteNotificationChannel("wake_test")
        assertEquals("no slideshow after a notification wake", 0, visible())
        assertTrue("still held off after a notification wake", heldOff())

        // A plain wake (the power button) brings it back.
        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(6_000)  // past the alert-sound window
        assertTrue("no frame on the screen-off while held off", !interactive())
        shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(4_000)
        assertTrue("slideshow back after a user wake", visible() > 0)
        assertTrue("hold cleared by a user wake", !heldOff())
    }
}
