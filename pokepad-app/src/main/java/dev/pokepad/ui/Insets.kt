package dev.pokepad.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15+ forces edge-to-edge, so content draws under the status/nav bars.
 * pad() keeps a view's original padding and adds the system-bar insets on top,
 * so buttons and content sit inside the safe area instead of under the bars.
 */
object Insets {
    fun pad(v: View) {
        val l = v.paddingLeft; val t = v.paddingTop; val r = v.paddingRight; val b = v.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(l + sb.left, t + sb.top, r + sb.right, b + sb.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(v)
    }

    /** bottom-only (+ sides): for a panel pinned to the bottom, so it clears the
     *  nav bar without opening a status-bar-sized gap at its top. */
    fun padBottom(v: View) {
        val l = v.paddingLeft; val t = v.paddingTop; val r = v.paddingRight; val b = v.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(v) { view, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(l + sb.left, t, r + sb.right, b + sb.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(v)
    }
}
