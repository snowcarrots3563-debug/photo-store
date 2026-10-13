package dev.local.artistgallery.data.archive

import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.data.importing.MediaImportPolicy

data class ArchivePreviewItem(val entry: ArchiveEntry, val mediaType: MediaType?, val skipReason: String?)
data class ArchivePreviewGroup(val relativeDirectory: String, val name: String, val items: List<ArchivePreviewItem>) {
    val canImport: Boolean get() = items.any { it.mediaType != null }
}

object ArchivePreview {
    fun build(entries: List<ArchiveEntry>, archiveName: String, existingNames: List<String>, stripCommonContainer: Boolean = true): List<ArchivePreviewGroup> {
        val normalized = entries.map { it.copy(path = ArchivePaths.normalize(it.path)) }
        val fileParts = normalized.filterNot { it.isDirectory }.map { it.path.split('/') }
        val container = fileParts.firstOrNull()?.first()?.takeIf { first -> stripCommonContainer &&
            fileParts.all { it.size >= 3 && it.first() == first } &&
                normalized.all { it.path == first || it.path.startsWith("$first/") }
        }
        val groups = linkedMapOf<String, MutableList<ArchivePreviewItem>>()
        normalized.forEach { entry ->
            val path = if (container == null) entry.path else entry.path.removePrefix("$container/")
            if (entry.isDirectory) {
                if (entry.path != container) groups.getOrPut(path) { mutableListOf() }
            } else {
                val directory = path.substringBeforeLast('/', "")
                val extension = path.substringAfterLast('.', "").lowercase()
                val type = when (extension) {
                    "gif" -> MediaType.GIF
                    "png", "jpg", "jpeg", "webp", "bmp", "heic", "heif", "avif" -> MediaType.IMAGE
                    "mp4", "m4v", "mov", "webm", "mkv", "3gp" -> MediaType.VIDEO
                    else -> null
                }
                val reason = when {
                    entry.skipReason != null -> entry.skipReason
                    extension == "pdf" -> "PDF 页面未能转换。"
                    extension == "txt" -> "TXT 暂不导入。"
                    type == null -> "不支持的媒体类型。"
                    else -> MediaImportPolicy.skipReason(entry.byteSize)
                }
                groups.getOrPut(directory) { mutableListOf() }.add(ArchivePreviewItem(entry, if (reason == null) type else null, reason))
            }
        }
        val reserved = existingNames.toMutableSet()
        return groups.map { (directory, items) ->
            val base = if (directory.isEmpty()) archiveName.substringBeforeLast('.').ifBlank { "压缩包" } else directory
            var name = base
            var suffix = 2
            while (name in reserved) name = "$base (${suffix++})"
            if (items.any { it.mediaType != null }) reserved.add(name)
            ArchivePreviewGroup(directory, name, items)
        }
    }
}
