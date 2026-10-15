package dev.local.artistgallery.data.importing

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.local.artistgallery.MainActivity
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic progress only; never starts a service, imports media or changes permission grants. */
@RunWith(AndroidJUnit4::class)
class ImportForegroundContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun progressChannelIsSeparateAndLowImportance() {
        ImportNotifications(isolatedContext())
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = requireNotNull(manager.getNotificationChannel(ImportNotifications.PROGRESS_CHANNEL_ID))
        assertEquals("导入进度", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNotEquals(ImportNotifications.CHANNEL_ID, ImportNotifications.PROGRESS_CHANNEL_ID)
        assertNull(channel.sound)
    }

    @Test fun ongoingProgressIncludesCountsAndNoCancelActionEvenWhenPermissionIsDenied() {
        val notifications = ImportNotifications(isolatedContext())
        val notification = notifications.progress(ImportWorkProgress("正在导入", "校验中", 3, 7), 2)
        assertEquals(ImportNotifications.PROGRESS_CHANNEL_ID, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals(7, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(3, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("3 / 7"))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("2"))
        assertTrue(notification.actions.isNullOrEmpty())
    }

    @Test fun preparationHasIndeterminateProgressAndOpensOnlyTheExistingActivity() {
        val progress = ImportWorkProgress("正在准备", "读取目录")
        val notification = ImportNotifications(isolatedContext()).progress(progress)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        val intent = ImportNotifications.createProgressIntent(context, progress)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNull(ImportNotifications.parseTarget(intent))
        assertNull(intent.data)
    }

    @Test fun confirmedBatchUsesTheExistingReadOnlyResultTarget() {
        val target = ImportResultTarget(ImportResultKind.Archive, UUID.randomUUID().toString())
        val progress = ImportWorkProgress("正在导入", "处理中", target = target)
        assertEquals(target, ImportNotifications.parseTarget(ImportNotifications.createProgressIntent(context, progress)))
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun progressRequestsImmediateForegroundDisplay() {
        val notification = ImportNotifications(isolatedContext()).progress(ImportWorkProgress("正在导入", "处理中"))
        notification.contentIntent = null
        fun rebuilt(behavior: Int? = null) = Notification.Builder.recoverBuilder(context, notification).apply {
            behavior?.let { setForegroundServiceBehavior(it) }
        }.build()
        // The public SDK has no behavior getter; compare otherwise identical notification parcels.
        val preserved = parcelBytes(rebuilt())
        assertArrayEquals(preserved, parcelBytes(rebuilt(Notification.FOREGROUND_SERVICE_IMMEDIATE)))
        assertFalse(preserved.contentEquals(parcelBytes(rebuilt(Notification.FOREGROUND_SERVICE_DEFERRED))))
    }

    @SdkSuppress(minSdkVersion = 33)
    @Test fun deniedProgressUpdatesNeverAccessTheNotificationService() {
        var rejectManagerAccess = false
        val denied = object : ContextWrapper(isolatedContext()) {
            override fun getSystemService(name: String): Any? {
                if (rejectManagerAccess && name == Context.NOTIFICATION_SERVICE) throw AssertionError("Denied updates must be skipped")
                return super.getSystemService(name)
            }
        }
        val notifications = ImportNotifications(denied)
        val notification = notifications.progress(ImportWorkProgress("正在导入", "处理中"))
        rejectManagerAccess = true
        notifications.updateProgress(notification)
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun manifestDeclaresOnlyAnExplicitDataSyncServiceAndRequiredPermissions() {
        val component = ComponentName(context, ImportForegroundService::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(component, 0)
        assertFalse(info.exported)
        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        listOf(Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC, Manifest.permission.WAKE_LOCK).forEach {
            assertTrue(permissions.contains(it))
        }
        assertNull(ImportForegroundService().onBind(Intent()))
    }

    private fun isolatedContext(): Context {
        val prefix = "foreground-test-${UUID.randomUUID()}-"
        return object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
            override fun checkSelfPermission(permission: String) = if (permission == Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
    }

    private fun parcelBytes(notification: Notification): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            notification.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally { parcel.recycle() }
    }
}
