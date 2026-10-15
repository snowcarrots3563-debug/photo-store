package dev.local.artistgallery.data.importing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

class ImportWorkInterruptedException(message: String) : CancellationException(message)

/** In-memory running-work leases. Callers own their jobs and durable recovery state. */
class ImportWorkController(
    private val startService: () -> Unit,
    private val foregroundTimeoutMillis: Long = 10_000,
) {
    private val lock = Any()
    private var nextId = 0L
    private var generation = 0L
    private var service: Host? = null
    private val leases = linkedMapOf<Long, Lease>()
    private val mutableActiveWork = MutableStateFlow<List<ImportWorkProgress>>(emptyList())
    val activeWork: StateFlow<List<ImportWorkProgress>> = mutableActiveWork.asStateFlow()

    fun begin(initial: ImportWorkProgress): Handle {
        val lease: Lease
        val ready: Boolean
        synchronized(lock) {
            lease = Lease(++nextId, initial)
            leases[lease.id] = lease
            publish()
            try {
                // Request protection synchronously, before the caller can launch actual IO.
                startService()
            } catch (error: RuntimeException) {
                leases.remove(lease.id)
                publish()
                throw IllegalStateException("无法启动后台导入，请返回应用后重试。", error)
            }
            ready = service?.ready == true && leases.containsKey(lease.id)
        }
        if (ready) lease.foreground.complete(Unit)
        return Handle(lease)
    }

    fun activeSnapshot(): List<ImportWorkProgress> = synchronized(lock) { leases.values.map { it.progress } }

    inner class Handle internal constructor(private val lease: Lease) : AutoCloseable {
        fun attach(job: Job) {
            val interruption = synchronized(lock) {
                if (!leases.containsKey(lease.id)) lease.interruption ?: CancellationException("导入任务已结束。")
                else {
                    check(lease.job == null || lease.job === job) { "An import lease already has a job" }
                    lease.job = job
                    null
                }
            }
            if (interruption != null) job.cancel(interruption)
        }

        suspend fun awaitForeground() {
            ensureActive()
            if (withTimeoutOrNull(foregroundTimeoutMillis) { lease.foreground.await(); true } != true) {
                val error = ImportWorkInterruptedException("后台导入服务未能及时就绪，请返回应用继续或重试。")
                interruptLease(lease, error)
                throw error
            }
            ensureActive()
        }

        private fun ensureActive() = synchronized(lock) {
            lease.interruption?.let { throw it }
            if (!leases.containsKey(lease.id)) throw CancellationException("导入任务已结束。")
        }

        fun update(progress: ImportWorkProgress) = synchronized(lock) {
            if (leases.containsKey(lease.id)) {
                lease.progress = progress
                publish()
            }
        }

        override fun close() {
            val removed = synchronized(lock) {
                if (leases.remove(lease.id) == null) false else { publish(); true }
            }
            if (removed) lease.foreground.completeExceptionally(CancellationException("导入任务已结束。"))
        }
    }

    internal fun serviceCreated(): Long = synchronized(lock) {
        (++generation).also { service = Host(it) }
    }

    internal fun isCurrentService(generation: Long): Boolean = synchronized(lock) { service?.generation == generation }

    internal fun serviceReady(generation: Long, protect: () -> Unit = {}) {
        val pending = synchronized(lock) {
            val host = service?.takeIf { it.generation == generation } ?: return
            if (leases.isEmpty()) return
            protect()
            host.ready = true
            leases.values.toList()
        }
        pending.forEach { it.foreground.complete(Unit) }
    }

    /** Stop issuance and a new begin cannot interleave, even across service generations. */
    internal fun stopIfIdle(generation: Long, stop: () -> Unit): Boolean = synchronized(lock) {
        if (service?.generation != generation || leases.isNotEmpty()) return false
        service = null
        stop()
        true
    }

    internal fun serviceDestroyed(generation: Long) = interruptService(generation,
        ImportWorkInterruptedException("后台导入服务已停止，请返回应用继续或重试。")) {}

    internal fun serviceTimedOut(generation: Long, stop: () -> Unit) = interruptService(generation,
        ImportWorkInterruptedException("后台导入已达到系统时限，请返回应用继续或重试。"), stop)

    internal fun serviceFailed(generation: Long, cause: RuntimeException, stop: () -> Unit) = interruptService(generation,
        ImportWorkInterruptedException("无法启动后台导入保护，请返回应用后重试。").apply { initCause(cause) }, stop)

    private fun interruptService(generation: Long, error: ImportWorkInterruptedException, stop: () -> Unit): Boolean {
        val interrupted = synchronized(lock) {
            if (service?.generation != generation) return false
            service = null
            val active = leases.values.toList()
            active.forEach { it.interruption = error }
            leases.clear()
            publish()
            stop()
            active
        }
        interrupted.forEach { it.foreground.completeExceptionally(error); it.job?.cancel(error) }
        return true
    }

    private fun interruptLease(lease: Lease, error: ImportWorkInterruptedException) {
        val removed = synchronized(lock) {
            if (leases.remove(lease.id) == null) false else {
                lease.interruption = error
                publish()
                true
            }
        }
        if (removed) { lease.foreground.completeExceptionally(error); lease.job?.cancel(error) }
    }

    private fun publish() { mutableActiveWork.value = leases.values.map { it.progress } }

    internal class Lease(val id: Long, var progress: ImportWorkProgress) {
        val foreground = CompletableDeferred<Unit>()
        var job: Job? = null
        var interruption: CancellationException? = null
    }
    private class Host(val generation: Long, var ready: Boolean = false)
}
