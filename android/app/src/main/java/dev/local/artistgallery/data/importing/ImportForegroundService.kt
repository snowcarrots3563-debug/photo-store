package dev.local.artistgallery.data.importing

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import dev.local.artistgallery.GalleryApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ImportForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as GalleryApplication).importWorkController
    private val notifications get() = (application as GalleryApplication).importNotifications
    private var generation = 0L
    private var startId = 0
    private var updates: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastUpdate = 0L
    private var promotionFailure: RuntimeException? = null

    override fun onCreate() {
        super.onCreate()
        promote()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        this.startId = startId
        promotionFailure?.let { error ->
            generation = controller.serviceCreated()
            controller.serviceFailed(generation, error) { stopIdle() }
            return START_NOT_STICKY
        }
        if (!controller.isCurrentService(generation) && !promote()) return START_NOT_STICKY
        updates?.cancel()
        updates = scope.launch {
            controller.activeWork.collectLatest { active ->
                if (!controller.isCurrentService(generation)) return@collectLatest
                if (active.isEmpty()) {
                    controller.stopIfIdle(generation) { stopIdle() }
                } else {
                    try {
                        controller.serviceReady(generation) { acquireWakeLock() }
                        delay((UPDATE_INTERVAL_MILLIS - (SystemClock.elapsedRealtime() - lastUpdate)).coerceAtLeast(0))
                        val latest = controller.activeSnapshot()
                        if (latest.isEmpty()) controller.stopIfIdle(generation) { stopIdle() }
                        else if (controller.isCurrentService(generation)) {
                            notifications.updateProgress(notifications.progress(latest.first(), latest.size))
                            lastUpdate = SystemClock.elapsedRealtime()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: RuntimeException) {
                        controller.serviceFailed(generation, error) { stopIdle() }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promote(): Boolean {
        generation = controller.serviceCreated()
        return try {
            val active = controller.activeSnapshot()
            val progress = active.firstOrNull() ?: ImportWorkProgress("正在准备导入", "准备中")
            val serviceType = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, ImportNotifications.FOREGROUND_ID, notifications.progress(progress, active.size), serviceType)
            lastUpdate = SystemClock.elapsedRealtime()
            controller.serviceReady(generation) { acquireWakeLock() }
            true
        } catch (error: RuntimeException) {
            promotionFailure = error
            controller.serviceFailed(generation, error) { stopIdle() }
            false
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val current = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArtistGallery:ImportWork").apply {
            setReferenceCounted(false)
        }.also { wakeLock = it }
        current.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopIdle() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (startId == 0) stopSelf() else stopSelf(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val stopped = controller.serviceTimedOut(generation) {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        if (!stopped) {
            releaseWakeLock()
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        updates?.cancel()
        scope.cancel()
        releaseWakeLock()
        controller.serviceDestroyed(generation)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 250L
        const val WAKE_LOCK_TIMEOUT_MILLIS = 6 * 60 * 60 * 1000L
    }
}
