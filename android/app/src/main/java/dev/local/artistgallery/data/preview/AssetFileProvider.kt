package dev.local.artistgallery.data.preview

import android.net.Uri
import androidx.core.content.FileProvider

class AssetFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? = shareMime(uri) ?: super.getType(uri)

    override fun getTypeAnonymous(uri: Uri): String? = shareMime(uri) ?: super.getTypeAnonymous(uri)

    private fun shareMime(uri: Uri): String? = when (val mime = uri.getQueryParameter("shareMime")) {
        "image/jpeg", "image/png", "image/gif" -> mime
        else -> null
    }
}
