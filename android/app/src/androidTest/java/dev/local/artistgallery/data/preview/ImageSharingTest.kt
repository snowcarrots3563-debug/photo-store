package dev.local.artistgallery.data.preview

import android.content.Context
import android.content.Intent
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only uniquely owned synthetic originals are created; no gallery database or existing media is touched. */
@RunWith(AndroidJUnit4::class)
class ImageSharingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = MediaFileStore(context)
    private val reader = AssetReader(context, files)
    private val ownedKeys = mutableSetOf<String>()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(32)
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + ByteArray(32)
    private val gif = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()) +
        byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0) +
        byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4c, 1, 0, 0x3b)

    @After fun cleanup() {
        val root = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        ownedKeys.forEach { key ->
            val original = files.originalFile(key)
            check(original.canonicalFile == File(root, "originals/$key"))
            assertTrue(!original.exists() || original.delete())
        }
    }

    @Test fun actualBytesDetermineMimeAndProviderDisplayName() {
        listOf(Triple(jpeg, "错误.png", "image/jpeg"), Triple(png, "无扩展名", "image/png"), Triple(gif, "动画.JPG", "image/gif")).forEach { (bytes, name, mime) ->
            val send = send(buildImageShareChooser(context, reader, item(bytes, name)))
            val uri = requireNotNull(send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
            assertEquals(mime, send.type)
            assertEquals(mime, context.contentResolver.getType(uri))
            val expectedName = when (mime) { "image/jpeg" -> "错误.jpg"; "image/png" -> "无扩展名.png"; else -> "动画.gif" }
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(expectedName, cursor.getString(0))
                assertEquals(bytes.size.toLong(), cursor.getLong(1))
            }
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun chooserAndSendKeepOnlyTemporaryReadGrantAndTheSameClipUri() {
        val chooser = buildImageShareChooser(context, reader, item(png, "原图.png"))
        val send = send(chooser)
        val uri = requireNotNull(send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.assetfiles", uri.authority)
        val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        listOf(send, chooser).forEach { intent ->
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and grantFlags)
            assertEquals(1, intent.clipData!!.itemCount)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.clipData!!.description.hasMimeType("image/png"))
        }
    }

    @Test fun gifOriginalHashAndAllFramesStayUntouched() {
        val asset = item(gif, "两帧动画.gif", MediaType.GIF)
        val before = hash(files.originalFile(asset.storageKey).readBytes())
        val uri = requireNotNull(send(buildImageShareChooser(context, reader, asset)).getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        val shared = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(gif, shared)
        assertEquals(before, hash(shared))
        assertEquals(before, hash(files.originalFile(asset.storageKey).readBytes()))
        assertTrue(files.originalFile(asset.storageKey).isFile)
    }

    @Test fun unsupportedBytesAndVideoAreRejectedWithoutChangingOriginals() {
        val unsupported = item("RIFFxxxxWEBPxxxx".toByteArray(), "伪装.jpg")
        expectIo { buildImageShareChooser(context, reader, unsupported) }
        assertArrayEquals("RIFFxxxxWEBPxxxx".toByteArray(), files.originalFile(unsupported.storageKey).readBytes())
        val video = item(png, "video.mp4", MediaType.VIDEO)
        expectIo { buildImageShareChooser(context, reader, video) }
        assertArrayEquals(png, files.originalFile(video.storageKey).readBytes())
    }

    @Test fun missingAndInvalidStorageKeysCannotProduceAChooser() {
        val missing = MediaAssetSummary("missing", "missing.png", MediaType.IMAGE, 1, 0, "missing.png", UUID.randomUUID().toString())
        expectIo { buildImageShareChooser(context, reader, missing) }
        listOf("../originals/anything", "not-a-uuid", "").forEach { key -> expectIo { buildImageShareChooser(context, reader, missing.copy(storageKey = key)) } }
    }

    @Test fun existingAssetUriWithoutShareParametersKeepsItsBehavior() {
        val asset = item(png, "原图.png")
        val originalUri = reader.contentUri(asset.storageKey)
        assertNull(originalUri.getQueryParameter("displayName"))
        assertNull(originalUri.getQueryParameter("shareMime"))
        assertEquals("application/octet-stream", context.contentResolver.getType(originalUri))
        context.contentResolver.query(originalUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(asset.storageKey, cursor.getString(0))
        }
    }

    @Test fun anonymousMimeIsWhitelistedWithoutOpeningTheOriginal() {
        val provider = AssetFileProvider()
        val missing = android.net.Uri.parse("content://${context.packageName}.assetfiles/originals/${UUID.randomUUID()}")
        listOf("image/jpeg", "image/png", "image/gif").forEach { mime ->
            val uri = missing.buildUpon().appendQueryParameter("shareMime", mime).build()
            assertEquals(mime, provider.getType(uri))
            assertEquals(mime, provider.getTypeAnonymous(uri))
        }
        listOf(null, "image/svg+xml", "video/mp4", "image/*", "IMAGE/PNG").forEach { mime ->
            val uri = missing.buildUpon().apply { if (mime != null) appendQueryParameter("shareMime", mime) }.build()
            assertEquals("application/octet-stream", provider.getTypeAnonymous(uri))
        }
    }

    private fun item(bytes: ByteArray, name: String, type: MediaType = MediaType.IMAGE): MediaAssetSummary {
        val key = UUID.randomUUID().toString()
        ownedKeys.add(key)
        files.originalFile(key).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        return MediaAssetSummary(key, name, type, bytes.size.toLong(), 0, name, key)
    }

    private fun send(chooser: Intent) = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun expectIo(block: () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { }
    }
}
