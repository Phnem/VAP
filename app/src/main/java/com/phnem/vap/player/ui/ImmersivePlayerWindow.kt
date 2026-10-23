package com.phnem.vap.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Полноэкранный режим плеера: прячем статус-бар и навигацию на всё время, пока экран плеера жив.
 * Свайп от края показывает их временно (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) и они сами уезжают.
 *
 * Вызывать из экрана с видео, а не из Activity: список эпизодов и PiP должны остаться обычными,
 * поэтому режим включается/снимается ровно на время показа кадра ([enabled]).
 */
@Composable
fun ImmersivePlayerWindow(enabled: Boolean = true) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(view, enabled, lifecycleOwner) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val originalCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 28)
                window.attributes.layoutInDisplayCutoutMode else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            if (enabled) {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            val observer = LifecycleEventObserver { _, event ->
                if (enabled && event == Lifecycle.Event.ON_RESUME) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                controller.show(WindowInsetsCompat.Type.systemBars())
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = originalCutoutMode }
                }
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
