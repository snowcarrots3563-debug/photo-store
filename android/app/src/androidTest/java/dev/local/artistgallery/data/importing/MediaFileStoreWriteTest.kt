package dev.local.artistgallery.data.importing

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic bytes in an isolated UUID directory only. */
@RunWith(AndroidJUnit4::class)
class MediaFileStoreWriteTest {
    @Test fun streamingWritesCountAndHashSingleWholeAndOffsetWritesExactlyOnce() = runBlocking {
        withFiles { files ->
            val key = UUID.randomUUID().toString()
            val bytes = ByteArray(128 * 1024 + 17) { (it % 251).toByte() }
            val staged = files.writeToStaging(key, hashWhileWriting = true) { output ->
                output.write(bytes[0].toInt())
                output.write(bytes, 1, 3)
                output.flush()
                output.write(bytes.copyOfRange(4, bytes.size))
                output.write(byteArrayOf())
                output.write(bytes, 0, 0)
            }
            assertEquals(bytes.size.toLong(), staged.byteSize)
            assertEquals(hash(bytes), staged.sha256)
            assertArrayEquals(bytes, files.stagingFile(key).readBytes())
            assertEquals(staged, files.inspectStaging(key))
        }
    }

    @Test fun failedOrCancelledStreamingWriterRemovesPartialFileAndReturnsNoResult() = runBlocking {
        withFiles { files ->
            listOf(IOException("fixture write failure"), CancellationException("fixture cancellation")).forEach { failure ->
                val key = UUID.randomUUID().toString()
                try {
                    files.writeToStaging(key, hashWhileWriting = true) { output ->
                        output.write(ByteArray(6 * 1024) { 7 })
                        output.flush()
                        throw failure
                    }
                    fail("Failed extraction must not return a staging result")
                } catch (error: Exception) { assertSame(failure, error) }
                assertFalse(files.hasStaging(key))
            }
        }
    }

    @Test fun defaultWriteHashesDiskWhileStreamingWriteReturnsTheWrittenHash() = runBlocking {
        withFiles { files ->
            val bytes = ByteArray(6 * 1024) { (it % 251).toByte() }
            val changed = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            listOf(false, true).forEach { streaming ->
                val key = UUID.randomUUID().toString()
                val writer: (java.io.OutputStream) -> Unit = { output ->
                    output.write(bytes)
                    output.flush()
                    files.stagingFile(key).writeBytes(changed)
                }
                val staged = if (streaming) files.writeToStaging(key, hashWhileWriting = true, writer = writer)
                else files.writeToStaging(key, writer = writer)
                assertEquals(bytes.size.toLong(), staged.byteSize)
                assertEquals(hash(if (streaming) bytes else changed), staged.sha256)
                assertArrayEquals(changed, files.stagingFile(key).readBytes())
            }
        }
    }

    private suspend fun withFiles(block: suspend (MediaFileStore) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val parent = requireNotNull(base.getExternalFilesDir(null))
        val directory = File(parent, "write-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val context = object : ContextWrapper(base) {
            override fun getExternalFilesDir(type: String?): File = directory
        }
        try { block(MediaFileStore(context)) }
        finally {
            check(directory.canonicalFile.parentFile == parent.canonicalFile)
            directory.deleteRecursively()
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
