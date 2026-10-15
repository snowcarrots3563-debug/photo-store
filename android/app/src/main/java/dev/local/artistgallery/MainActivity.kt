package dev.local.artistgallery

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.pm.PackageInfoCompat
import dev.local.artistgallery.ui.DTheme
import dev.local.artistgallery.ui.GalleryApp
import dev.local.artistgallery.ui.UpdateNotesDialog
import dev.local.artistgallery.ui.applyGalleryWindow
import dev.local.artistgallery.data.importing.ImportNotifications
import dev.local.artistgallery.data.importing.ImportResultKind
import dev.local.artistgallery.data.importing.ImportResultTarget

class MainActivity : ComponentActivity() {
    private var importResultTarget by mutableStateOf<ImportResultTarget?>(null)
    private var notificationLaunch by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importResultTarget = ImportNotifications.parseTarget(intent) ?: savedInstanceState?.getString("importResultId")?.let { id ->
            ImportResultKind.entries.firstOrNull { it.name == savedInstanceState?.getString("importResultKind") }?.let { ImportResultTarget(it, id) }
        }
        notificationLaunch = importResultTarget != null || savedInstanceState?.getBoolean("notificationLaunch") == true
        enableEdgeToEdge()
        applyGalleryWindow(window)
        val app = application as GalleryApplication
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        val showUpdateNotes = app.updateHistory.shouldShow(versionCode, packageInfo.firstInstallTime != packageInfo.lastUpdateTime)
        val recentUpdates = app.updateHistory.recent(versionCode)
        setContent {
            var showUpdates by remember(versionCode) { mutableStateOf(showUpdateNotes) }
            GalleryApp(app.repository, mediaRepository = app.mediaRepository, mediaImporter = app.mediaImportCoordinator, previewDecoder = app.previewDecoder, assetReader = app.assetReader, archiveImporter = app.archiveImporter, browsePreferences = app.browsePreferences, localTransfer = app.localTransfer,
                importNotifications = app.importNotifications, importResultTarget = importResultTarget, mediaImportSession = app.mediaImportSession,
                onImportResultConsumed = { importResultTarget = null; intent?.data = null })
            if (showUpdates && !notificationLaunch) {
                DTheme {
                    UpdateNotesDialog(recentUpdates) {
                        app.updateHistory.acknowledge(versionCode)
                        showUpdates = false
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ImportNotifications.parseTarget(intent)?.let { target ->
            notificationLaunch = true
            importResultTarget = target
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        importResultTarget?.let { target ->
            outState.putString("importResultKind", target.kind.name)
            outState.putString("importResultId", target.batchId)
        }
        outState.putBoolean("notificationLaunch", notificationLaunch)
        super.onSaveInstanceState(outState)
    }
}
