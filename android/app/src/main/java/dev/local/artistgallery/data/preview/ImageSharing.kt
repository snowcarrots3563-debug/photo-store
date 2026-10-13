package dev.local.artistgallery.data.preview

import android.content.ClipData
import android.content.Context
import android.content.Intent
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import java.io.IOException

internal enum class ShareImageFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), GIF("image/gif", "gif"),
}

internal fun detectShareImageFormat(header: ByteArray): ShareImageFormat {
    if (header.size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()) return ShareImageFormat.JPEG
    if (header.size >= 8 && header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return ShareImageFormat.PNG
    if (header.size >= 6 && header.copyOfRange(0, 6).toString(Charsets.US_ASCII) in listOf("GIF87a", "GIF89a")) return ShareImageFormat.GIF
    throw IOException("目前支持分享 JPEG、PNG 和 GIF 原图。")
}

internal fun imageShareDisplayName(name: String, format: ShareImageFormat): String {
    val safeName = name.replace('\\', '/').substringAfterLast('/').map { character ->
        if (character.isISOControl() || character in ":*?\"<>|") '_' else character
    }.joinToString("").trim().trimEnd('.', ' ').ifBlank { "图片" }
    val extension = safeName.substringAfterLast('.', "")
    if (extension.equals(format.extension, ignoreCase = true) ||
        (format == ShareImageFormat.JPEG && extension.equals("jpeg", ignoreCase = true))) return safeName
    val stem = if (safeName.lastIndexOf('.') > 0) safeName.substringBeforeLast('.') else safeName
    return "$stem.${format.extension}"
}

/** Call on an IO dispatcher; the original is read only for its header and is never copied or reencoded. */
fun buildImageShareChooser(context: Context, assetReader: AssetReader, item: MediaAssetSummary): Intent {
    if (item.type == MediaType.VIDEO) throw IOException("目前支持分享 JPEG、PNG 和 GIF 原图。")
    val sourceUri = assetReader.contentUri(item.storageKey)
    val header = ByteArray(16)
    val count = (context.contentResolver.openInputStream(sourceUri) ?: throw IOException("无法读取图库原图。")).use { input ->
        var readBytes = 0
        while (readBytes < header.size) {
            val read = input.read(header, readBytes, header.size - readBytes)
            if (read <= 0) break
            readBytes += read
        }
        readBytes
    }
    val format = detectShareImageFormat(header.copyOf(count))
    val displayName = imageShareDisplayName(item.name, format)
    val uri = assetReader.contentUri(item.storageKey, displayName).buildUpon().appendQueryParameter("shareMime", format.mimeType).build()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = format.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, displayName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "分享这张图片").apply {
        clipData = send.clipData
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
