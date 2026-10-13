package dev.local.artistgallery.data.importing

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.local.artistgallery.MainActivity
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No permission grants, activity launches or real notification delivery. */
@RunWith(AndroidJUnit4::class)
class ImportNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun channelHasTheExpectedNameAndDefaultImportance() {
        ImportNotifications(isolatedContext())
        val channel = requireNotNull(context.getSystemService(NotificationManager::class.java).getNotificationChannel(ImportNotifications.CHANNEL_ID))
        assertEquals("导入结果", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test fun targetIntentIsExplicitAndParsesBothKinds() {
        ImportResultKind.entries.forEach { kind ->
            val target = ImportResultTarget(kind, UUID.randomUUID().toString())
            val intent = ImportNotifications.createTargetIntent(context, target)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.flags)
            assertEquals(target, ImportNotifications.parseTarget(intent))
        }
    }

    @Test fun differentTargetsHaveDifferentPendingIntentIdentity() {
        val target = ImportResultTarget(ImportResultKind.Media, UUID.randomUUID().toString())
        val first = ImportNotifications.createTargetIntent(context, target)
        assertFalse(first.filterEquals(ImportNotifications.createTargetIntent(context, target.copy(kind = ImportResultKind.Archive))))
        assertFalse(first.filterEquals(ImportNotifications.createTargetIntent(context, target.copy(batchId = UUID.randomUUID().toString()))))
        assertTrue(first.filterEquals(ImportNotifications.createTargetIntent(context, target)))
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun resultPendingIntentIsImmutable() {
        val intent = ImportNotifications.createTargetIntent(context, ImportResultTarget(ImportResultKind.Media, UUID.randomUUID().toString()))
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { assertTrue(pending.isImmutable) } finally { pending.cancel() }
    }

    @Test fun malformedExternalIntentCannotOpenAResult() {
        val target = ImportResultTarget(ImportResultKind.Archive, UUID.randomUUID().toString())
        val valid = ImportNotifications.createTargetIntent(context, target)
        assertNull(ImportNotifications.parseTarget(null))
        assertNull(ImportNotifications.parseTarget(Intent(valid).setAction(Intent.ACTION_VIEW)))
        listOf("artistgallery://import-result/Archive/not-a-uuid", "artistgallery://import-result/Other/${target.batchId}",
            "artistgallery://import-result/Archive/${target.batchId}?retry=true", "artistgallery://import-result/Archive/${target.batchId}/extra",
            "artistgallery://import-result/Archive/1-1-1-1-1").forEach { uri ->
            assertNull(ImportNotifications.parseTarget(Intent(valid).setData(android.net.Uri.parse(uri))))
        }
    }

    @Test fun permissionAskedFlagDoesNotUseBrowsePreferences() {
        val isolated = isolatedContext()
        val notifications = ImportNotifications(isolated)
        notifications.markPermissionRequested()
        assertFalse(notifications.needsPermissionRequest())
        assertFalse(ImportNotifications(isolated).needsPermissionRequest())
    }

    @Test fun unavailableNotificationServiceCannotTurnCompletionIntoAnError() {
        val unavailable = object : ContextWrapper(isolatedContext()) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.NOTIFICATION_SERVICE) throw SecurityException("fixture unavailable")
                return super.getSystemService(name)
            }
        }
        ImportNotifications(unavailable).completed(ImportCompletion(ImportResultKind.Media, UUID.randomUUID().toString(), 1, 0, 0, 0))
    }

    private fun isolatedContext(): Context {
        val prefix = "notification-test-${UUID.randomUUID()}-"
        return object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
    }
}
