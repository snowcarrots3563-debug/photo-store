package dev.local.artistgallery.data.archive

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.RoomGalleryRepository
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic sources and a UUID storage root; no picker, user media or disk-full fixture. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ArchiveCopyVolumeTest {
    private val baseContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val uri = Uri.parse("content://copy.fixture/input.zip")
    private lateinit var root: File
    private lateinit var external: File
    private lateinit var source: File
    private lateinit var provider: CopyProvider
    private lateinit var database: GalleryDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var controller: ArchiveImportCoordinator
    private val target get() = File(external, ".archive-imports/input.zip")

    @Before fun prepare() {
        root = File(baseContext.cacheDir, "copy-volume-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        external = File(root, "external").also { assertTrue(it.mkdir()) }
        assertTrue(File(external, ".archive-imports").mkdir())
        source = File(root, "source.bin").also { it.writeBytes(byteArrayOf()) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        provider = CopyProvider(source, scope)
        provider.attachInfo(baseContext, ProviderInfo().apply { authority = "copy.fixture" })
        val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(baseContext) {
            override fun getContentResolver(): ContentResolver = resolver
            override fun getExternalFilesDir(type: String?): File = external
        }
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repository = RoomGalleryRepository(database)
        val files = MediaFileStore(context)
        controller = ArchiveImportCoordinator(context, database, repository, MediaImportCoordinator(context, repository, files), files, scope)
    }

    @After fun cleanUp() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        database.close()
        assertEquals(baseContext.cacheDir.canonicalFile, root.canonicalFile.parentFile)
        assertTrue(root.name.startsWith("copy-volume-"))
        assertEquals(root.canonicalFile, external.canonicalFile.parentFile)
        assertTrue(root.deleteRecursively())
    }

    @Test fun knownLengthCrossesSpaceIntervalAndKeepsShortTailAndFinalProgress() = runBlocking {
        writeSource(8 * 1024 * 1024 + 731)
        var initialProgress: String? = null
        provider.onMetadataOpen = { initialProgress = controller.state.value.progress }
        copy(target)
        assertEquals("input.zip · 0 MB", initialProgress)
        assertEquals(source.length(), provider.metadataLength)
        assertEquals(source.length(), target.length())
        assertArrayEquals(source.readBytes(), target.readBytes())
        assertEquals("input.zip · 8 MB", controller.state.value.progress)
        assertFalse(part(target).exists())
    }

    @Test fun unknownLengthReliablePipeCopiesThroughNormalEof() = runBlocking {
        writeSource(3 * 1024 * 1024 + 37)
        provider.declaredLength = AssetFileDescriptor.UNKNOWN_LENGTH
        provider.pipe = true
        copy(target)
        assertEquals(AssetFileDescriptor.UNKNOWN_LENGTH, provider.metadataLength)
        assertEquals(source.length(), target.length())
        assertArrayEquals(source.readBytes(), target.readBytes())
        assertEquals("input.zip · 3 MB", controller.state.value.progress)
        assertFalse(part(target).exists())
    }

    @Test fun declaredLengthLargerThanSourceRejectsShortCopy() = runBlocking {
        writeSource(128 * 1024 + 17)
        provider.declaredLength = source.length() + 1
        try { copy(target); fail("Short source was accepted") }
        catch (error: ArchiveReadException) {
            assertEquals(ArchiveError.IO, error.reason)
            assertEquals("所选分卷复制不完整，请重新选择。", error.message)
        }
        assertFalse(target.exists())
        assertFalse(part(target).exists())
    }

    @Test fun sourceIOExceptionAfterWrittenPrefixRemovesPartialCopy() = runBlocking {
        writeSource(2 * 1024 * 1024)
        provider.pipe = true
        provider.prefixBytes = 1024 * 1024
        provider.failPipe = true
        val copying = async(Dispatchers.IO) { runCatching { copy(target) } }
        withTimeout(10_000) { while (part(target).length() < provider.prefixBytes) delay(5) }
        provider.finishPipe.complete(Unit)
        assertTrue(copying.await().exceptionOrNull() is IOException)
        assertFalse(target.exists())
        assertFalse(part(target).exists())
        assertEquals("input.zip · 1 MB", controller.state.value.progress)
    }

    @Test fun publicCancelKeepsCancellationStateAndNeverPreparesThePartialFile() = runBlocking {
        writeSource(2 * 1024 * 1024)
        provider.pipe = true
        provider.prefixBytes = 1024 * 1024
        controller.recover()
        controller.open(UUID.randomUUID().toString())
        controller.pick(listOf(uri), false)
        val preparing = withTimeout(10_000) { controller.state.first { it.step == ArchiveStep.Preparing && it.taskId != null } }
        val taskRoot = File(external, ".archive-imports").canonicalFile
        val taskFolder = File(taskRoot, requireNotNull(preparing.taskId)).canonicalFile
        assertEquals(taskRoot, taskFolder.parentFile)
        val preparedFile = File(taskFolder, "input.zip")
        withTimeout(10_000) { while (part(preparedFile).length() < provider.prefixBytes) delay(5) }
        controller.cancel()
        assertTrue(controller.state.value.progress.startsWith("正在取消"))
        assertTrue(controller.state.value.files.isEmpty())
        provider.finishPipe.complete(Unit)
        val cancelled = withTimeout(10_000) { controller.state.first { !it.visible && it.taskId == null } }
        assertEquals(ArchiveStep.Source, cancelled.step)
        assertTrue(cancelled.files.isEmpty())
        assertFalse(taskFolder.exists())
        assertTrue(database.galleryDao().archiveTasks().isEmpty())
    }

    @Test fun renameFailureKeepsExistingTargetAndRemovesCopyingFile() = runBlocking {
        writeSource(6000)
        assertTrue(target.mkdir())
        val retained = File(target, "retained").also { it.writeText("fixture") }
        try { copy(target); fail("Rename over a directory was accepted") }
        catch (error: IOException) { assertEquals("无法保存所选分卷。", error.message) }
        assertTrue(target.isDirectory)
        assertEquals("fixture", retained.readText())
        assertFalse(part(target).exists())
        assertEquals("input.zip · 0 MB", controller.state.value.progress)
    }

    private fun writeSource(size: Int) {
        source.writeBytes(ByteArray(size) { (it % 251).toByte() })
        provider.declaredLength = size.toLong()
    }

    private fun part(file: File) = File(file.parentFile, file.name + ".copying")

    private suspend fun copy(file: File) = suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
        val method = ArchiveImportCoordinator::class.java.getDeclaredMethod("copyVolume", Uri::class.java, File::class.java, Continuation::class.java)
        method.isAccessible = true
        try { method.invoke(controller, uri, file, continuation) }
        catch (error: InvocationTargetException) { throw requireNotNull(error.cause) }
    }

    private class CopyProvider(private val source: File, private val scope: CoroutineScope) : ContentProvider() {
        var declaredLength = 0L
        var metadataLength: Long? = null
        var onMetadataOpen: () -> Unit = {}
        var pipe = false
        var prefixBytes = 0
        var failPipe = false
        val finishPipe = CompletableDeferred<Unit>()
        private var opens = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply {
                addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) "input.zip" else declaredLength })
            }
        }
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            if (opens++ == 0) {
                onMetadataOpen()
                val descriptor = if (declaredLength >= 0) {
                    AssetFileDescriptor(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY), 0, declaredLength)
                } else {
                    val ends = ParcelFileDescriptor.createReliablePipe()
                    ends[1].close()
                    AssetFileDescriptor(ends[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
                }
                metadataLength = descriptor.length
                return descriptor
            }
            if (!pipe) return AssetFileDescriptor(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            val ends = ParcelFileDescriptor.createReliablePipe()
            scope.launch {
                ends[1].use { writer ->
                    val output = ParcelFileDescriptor.AutoCloseOutputStream(writer)
                    if (prefixBytes == 0) source.inputStream().use { it.copyTo(output) }
                    else {
                        output.write(source.readBytes(), 0, prefixBytes)
                        finishPipe.await()
                        if (failPipe) writer.closeWithError("fixture source failure") else output.write(1)
                    }
                }
            }
            return AssetFileDescriptor(ends[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        override fun getType(uri: Uri) = "application/zip"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Read-only fixture")
    }
}
