package dev.local.artistgallery.data.archive

/** Only read permissions newly persisted for this source-copy operation belong to its cleanup. */
internal class SourceReadPermissions(
    private val persistedReadUris: () -> Set<String>,
    private val takeRead: (String) -> Unit,
    private val releaseRead: (String) -> Unit,
) {
    fun acquire(uris: List<String>): AutoCloseable {
        val acquired = mutableListOf<String>()
        val held = object : AutoCloseable {
            private var closed = false
            @Synchronized override fun close() {
                if (closed) return
                closed = true
                acquired.forEach { uri ->
                    try { releaseRead(uri) }
                    catch (_: SecurityException) { }
                    catch (_: IllegalArgumentException) { }
                }
            }
        }
        try {
            val existing = try { persistedReadUris() }
            catch (_: SecurityException) { return held }
            catch (_: IllegalArgumentException) { return held }
            uris.distinct().filterNot { it in existing }.forEach { uri ->
                try { takeRead(uri); acquired.add(uri) }
                catch (_: SecurityException) { }
                catch (_: IllegalArgumentException) { }
            }
        } catch (error: RuntimeException) {
            held.close()
            throw error
        }
        return held
    }
}
