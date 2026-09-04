package com.wentuyi.app

import android.os.Build
import android.view.View
import android.view.WindowInsets

/** Applies stable content padding plus system-bar insets for Android's edge-to-edge defaults. */
object SystemBarPadding {
    @JvmStatic
    fun apply(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
                insets
            }
            view.requestApplyInsets()
        } else {
            @Suppress("DEPRECATION")
            view.setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(
                    left + insets.systemWindowInsetLeft,
                    top + insets.systemWindowInsetTop,
                    right + insets.systemWindowInsetRight,
                    bottom + insets.systemWindowInsetBottom,
                )
                insets
            }
            view.requestApplyInsets()
        }
    }
}
