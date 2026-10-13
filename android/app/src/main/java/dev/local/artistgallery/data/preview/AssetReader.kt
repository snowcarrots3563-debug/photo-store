package dev.local.artistgallery.data.preview

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.local.artistgallery.data.importing.MediaFileStore
import java.io.FileNotFoundException
import java.util.UUID

class AssetReader(
    private val context: Context,
    private val mediaFiles: MediaFileStore,
) {
    fun contentUri(storageKey: String): Uri {
        val normalizedKey = try {
            UUID.fromString(storageKey).toString()
        } catch (_: IllegalArgumentException) {
            throw FileNotFoundException("图库媒体标识无效")
        }
        val file = mediaFiles.originalFile(normalizedKey)
        if (!file.isFile) throw FileNotFoundException("图库原图不存在")
        return FileProvider.getUriForFile(context, "${context.packageName}.assetfiles", file)
    }
}
