package com.gjnet.shuffleframe

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Pads a full-window view so content clears the status bar, navigation bar and cutouts. */
fun View.padForSystemBars() {
    val l = paddingLeft; val t = paddingTop; val r = paddingRight; val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        insets
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format("%.0f KB", bytes / 1024.0)
}
