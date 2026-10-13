package dev.local.artistgallery.fixture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.local.artistgallery.ui.GalleryApp
import dev.local.artistgallery.ui.applyGalleryWindow

/** 调试专用，仓储只在内存中，不打开正常图库数据库。 */
class UiFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyGalleryWindow(window)
        val repository = FixtureGalleryRepository(intent.getStringExtra("scenario") ?: "baseline")
        setContent { GalleryApp(repository, fixtureCover = { SampleCover(it) }) }
    }
}
