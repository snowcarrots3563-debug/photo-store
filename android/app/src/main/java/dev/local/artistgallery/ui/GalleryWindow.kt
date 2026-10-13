package dev.local.artistgallery.ui

import android.graphics.Color
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat

/** The app uses a light surface, including the area underneath the system gesture handle. */
fun applyGalleryWindow(window: Window) {
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) {
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = true
    }
}

@Composable
fun galleryDialogWindowInsets(includeIme: Boolean = true): WindowInsets {
    val view = LocalView.current
    val window = requireNotNull((view.parent as? DialogWindowProvider)?.window)
    var safeInsets by remember(window) { mutableStateOf(Insets.NONE) }
    DisposableEffect(window, includeIme) {
        val decor = window.decorView
        applyGalleryWindow(window)
        window.setDimAmount(0f)
        // Use the real window constraints instead of Compose 1.7's full-screen Dialog measure.
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fun update(insets: WindowInsetsCompat) {
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val imeBottom = if (includeIme && insets.isVisible(WindowInsetsCompat.Type.ime())) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            val gestureBottom = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            safeInsets = Insets.of(safe.left, safe.top, safe.right, maxOf(safe.bottom, gestureBottom, imeBottom))
        }
        // Read this Dialog's native window, independent of the Activity's consumed Compose insets.
        ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets -> update(insets); insets }
        ViewCompat.setWindowInsetsAnimationCallback(decor, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                update(insets)
                return insets
            }
        })
        ViewCompat.getRootWindowInsets(decor)?.let(::update)
        ViewCompat.requestApplyInsets(decor)
        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(decor, null)
            ViewCompat.setWindowInsetsAnimationCallback(decor, null)
        }
    }
    return WindowInsets(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom)
}
