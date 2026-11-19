package com.gjnet.shuffleframe

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import android.view.Window
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The slideshow's refresh rate: the system's own choice, or a cap to save battery. The slow pan
 * and zoom looks the same at a lower rate, and the display and graphics do less work. Only rates
 * the display offers at its current resolution are used; nothing is hard-coded.
 */
object DisplayRate {
    const val NATIVE = "native"
    const val SIXTY = "60"
    const val LOWEST = "lowest"
    /** Nothing below this (29.97 Hz counts). */
    private const val MIN_HZ = 29.5f

    private fun display(ctx: Context): Display? =
        ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    /**
     * Every rate the display can run at its current resolution, lowest first: its modes' rates
     * and, on Android 12+, the rates each mode can switch to (newer Android lists one mode with
     * the rest as alternatives).
     */
    private fun rates(ctx: Context): List<Float> {
        val d = display(ctx) ?: return emptyList()
        val now = d.mode
        val all = mutableListOf<Float>()
        for (m in d.supportedModes) {
            if (m.physicalWidth != now.physicalWidth || m.physicalHeight != now.physicalHeight) continue
            all += m.refreshRate
            if (Build.VERSION.SDK_INT >= 31) all += m.alternativeRefreshRates.toList()
        }
        return all.sorted().fold(mutableListOf()) { out, r -> if (out.isEmpty() || r - out.last() > 0.5f) out += r; out }
    }

    private fun sixty(rates: List<Float>) = rates.firstOrNull { abs(it - 60f) < 1f }
    private fun lowest(rates: List<Float>) = rates.firstOrNull { it >= MIN_HZ }

    /** The choices this display offers, as (value, label): always the system's, then 60 Hz and the lowest when they're lower. */
    fun options(ctx: Context): List<Pair<String, String>> {
        val rates = rates(ctx)
        val top = rates.lastOrNull() ?: 0f
        val out = mutableListOf(NATIVE to ctx.getString(R.string.refresh_native, hz(top)))
        val sixty = sixty(rates)
        if (sixty != null && sixty < top - 1f) out += SIXTY to ctx.getString(R.string.refresh_hz, hz(sixty))
        val low = lowest(rates)
        if (low != null && low < (sixty ?: top) - 1f) out += LOWEST to ctx.getString(R.string.refresh_lowest, hz(low))
        return out
    }

    /** Asks for the chosen rate on the slideshow's window (nothing for the system's choice). */
    fun apply(window: Window, ctx: Context) {
        val rates = rates(ctx)
        val rate = when (Prefs(ctx).refreshRate) {
            SIXTY -> sixty(rates)
            LOWEST -> lowest(rates)
            else -> null
        } ?: 0f
        if (window.attributes.preferredRefreshRate != rate) window.attributes = window.attributes.apply { preferredRefreshRate = rate }
        // Android 15+ also lets each animating view vote for a rate (the pan and zoom would vote
        // high), so the views ask for the same.
        if (Build.VERSION.SDK_INT >= 35) requestOnViews(window.decorView, if (rate > 0f) rate else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
    }

    @RequiresApi(35)
    private fun requestOnViews(v: View, rate: Float) {
        v.requestedFrameRate = rate
        if (v is ViewGroup) for (i in 0 until v.childCount) requestOnViews(v.getChildAt(i), rate)
    }

    private fun hz(rate: Float): String {
        val r = rate.roundToInt()
        return if (abs(rate - r) < 0.05f) "$r" else "%.2f".format(rate)
    }
}
