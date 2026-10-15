package dev.local.artistgallery.data.importing

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import dev.local.artistgallery.MainActivity
import dev.local.artistgallery.R
import java.util.UUID

class ImportNotifications(private val context: Context) : Application.ActivityLifecycleCallbacks {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("import-notification-permission", Context.MODE_PRIVATE)
    private val visibility = ImportAppVisibility()

    init {
        try {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "导入结果", NotificationManager.IMPORTANCE_DEFAULT))
            manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL_ID, "导入进度", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            })
        } catch (_: RuntimeException) { }
    }

    fun needsPermissionRequest(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        !preferences.getBoolean("requested", false)

    fun markPermissionRequested() {
        preferences.edit().putBoolean("requested", true).apply()
    }

    fun completed(result: ImportCompletion) {
        if (!result.shouldNotify(visibility.isVisible)) return
        try {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return
            val pending = PendingIntent.getActivity(context, 0, createTargetIntent(context, result.target), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_import_complete)
                .setContentTitle(result.title)
                .setContentText(result.message)
                .setStyle(Notification.BigTextStyle().bigText(result.message))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            if (!visibility.isVisible) manager.notify(result.notificationTag, 0, notification)
        } catch (_: RuntimeException) {
            // Permission/channel changes and unavailable system services cannot fail an import.
        }
    }

    fun progress(work: ImportWorkProgress, activeCount: Int = 1): Notification {
        val pending = PendingIntent.getActivity(context, 0, createProgressIntent(context, work), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val counts = work.total?.takeIf { it > 0 }?.let { "${work.processed} / $it" }
        val message = listOfNotNull(work.detail.takeIf { it.isNotBlank() }, counts).joinToString(" · ")
        return Notification.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_import_complete)
            .setContentTitle(if (activeCount > 1) "${work.title}（共 $activeCount 项任务）" else work.title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setProgress(work.barMaximum, work.barValue, work.isIndeterminate)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    fun updateProgress(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            if (!manager.areNotificationsEnabled()) return
            manager.notify(FOREGROUND_ID, notification)
        } catch (_: RuntimeException) { }
    }

    override fun onActivityStarted(activity: Activity) { visibility.started() }
    override fun onActivityStopped(activity: Activity) { visibility.stopped(activity.isChangingConfigurations) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        internal const val CHANNEL_ID = "import-results"
        internal const val PROGRESS_CHANNEL_ID = "import-progress"
        internal const val FOREGROUND_ID = 203
        private const val ACTION_RESULT = "dev.local.artistgallery.OPEN_IMPORT_RESULT"
        private const val RESULT_SCHEME = "artistgallery"

        fun createTargetIntent(context: Context, target: ImportResultTarget): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_RESULT
                data = Uri.Builder().scheme(RESULT_SCHEME).authority("import-result").appendPath(target.kind.name).appendPath(target.batchId).build()
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        internal fun createProgressIntent(context: Context, work: ImportWorkProgress): Intent =
            work.target?.let { createTargetIntent(context, it) } ?: Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        fun parseTarget(intent: Intent?): ImportResultTarget? {
            if (intent?.action != ACTION_RESULT) return null
            val uri = intent.data ?: return null
            if (uri.scheme != RESULT_SCHEME || uri.authority != "import-result" || uri.pathSegments.size != 2 || uri.query != null || uri.fragment != null) return null
            val kind = runCatching { ImportResultKind.valueOf(uri.pathSegments[0]) }.getOrNull() ?: return null
            val id = uri.pathSegments[1]
            val normalized = runCatching { UUID.fromString(id).toString() }.getOrNull() ?: return null
            if (!id.equals(normalized, ignoreCase = true)) return null
            return ImportResultTarget(kind, normalized)
        }
    }
}
