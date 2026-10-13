package dev.local.artistgallery.data.exporting

import dev.local.artistgallery.data.backup.BackupValidation
import dev.local.artistgallery.data.orderedEntities
import java.text.Normalizer
import java.util.Locale

object ExportPlanBuilder {
    fun build(snapshot: GallerySnapshot, scope: ExportScope): ExportPlan {
        val manifest = snapshot.manifest
        val assets = manifest.assets.associateBy { it.id }
        val entries = mutableListOf<ExportEntry>()
        val names = PathNames()
        fun file(parent: String, name: String, id: String) {
            require(id in assets) { "导出图片不存在，请重新选择。" }
            entries += ExportEntry(names.take(parent, name, false), id)
        }
        fun directory(parent: String, name: String): String = names.take(parent, name, true).also {
            entries += ExportEntry("$it/")
        }
        fun selected(ids: List<String>, known: Set<String>): Set<String> {
            require(ids.isNotEmpty() && ids.all { it in known }) { "导出项目不存在或尚未选择。" }
            return ids.toSet()
        }
        val label = when (scope) {
            is ExportScope.Assets -> {
                selected(scope.ids, assets.keys)
                scope.ids.distinct().forEach { id -> file("", assets.getValue(id).originalName, id) }
                "图片导出"
            }
            else -> {
                val artistIds = when (scope) {
                    is ExportScope.Artists -> selected(scope.ids, manifest.artists.map { it.id }.toSet())
                    else -> null
                }
                val collectionIds = when (scope) {
                    is ExportScope.Collections -> selected(scope.ids, manifest.collections.map { it.id }.toSet())
                    else -> null
                }
                val collections = manifest.collections.filter {
                    (artistIds == null || it.artistId in artistIds) && (collectionIds == null || it.id in collectionIds)
                }
                val includedArtists = collections.map { it.artistId }.toSet()
                val collectionsByArtist = collections.groupBy { it.artistId }
                val references = manifest.references.groupBy { it.collectionId }
                orderedEntities(manifest.artists, manifest.settings.artistOrder) { it.id }.filter {
                    when (scope) {
                        is ExportScope.Artists -> it.id in artistIds!!
                        is ExportScope.Collections -> it.id in includedArtists
                        else -> true
                    }
                }.forEach { artist ->
                    val artistPath = directory("", artist.name)
                    orderedEntities(collectionsByArtist[artist.id].orEmpty(), manifest.settings.collectionOrders[artist.id].orEmpty()) { it.id }
                        .forEach { collection ->
                            val collectionPath = directory(artistPath, collection.name)
                            references[collection.id].orEmpty().sortedBy { it.manualOrder }.forEach {
                                file(collectionPath, it.sourceName, it.assetId)
                            }
                        }
                }
                if (scope == ExportScope.Library) {
                    val assigned = manifest.references.map { it.assetId }.toSet()
                    val unassigned = manifest.assets.filter { it.id !in assigned }
                    if (unassigned.isNotEmpty()) {
                        val path = directory("", "未归组媒体")
                        unassigned.forEach { file(path, it.originalName, it.id) }
                    }
                }
                when (scope) {
                    is ExportScope.Collections -> "作品集导出"
                    is ExportScope.Artists -> "画师导出"
                    else -> "图库导出"
                }
            }
        }
        val included = entries.mapNotNull { it.assetId }.toSet()
        require(included.all { it in snapshot.storageKeys }) { "导出原图信息不完整。" }
        return ExportPlan(label, entries, manifest.assets.filter { it.id in included }, snapshot.storageKeys.filterKeys { it in included })
    }

    fun backup(snapshot: GallerySnapshot): ExportPlan {
        BackupValidation.validate(snapshot.manifest)
        require(snapshot.manifest.assets.all { it.id in snapshot.storageKeys }) { "备份原图信息不完整。" }
        return ExportPlan(
            "完整备份", listOf(ExportEntry("media/")) + snapshot.manifest.assets.map { ExportEntry(it.entryPath, it.id) },
            snapshot.manifest.assets, snapshot.storageKeys, snapshot.manifest,
        )
    }

    private class PathNames {
        private val used = HashSet<String>()
        private val nextNumbers = HashMap<String, Int>()
        fun take(parent: String, name: String, directory: Boolean): String {
            val cleaned = Normalizer.normalize(name, Normalizer.Form.NFC)
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_").trim().trimEnd('.', ' ').ifEmpty { "未命名" }
            val reserved = cleaned.substringBefore('.').matches(Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"))
            val safe = if (reserved) "_$cleaned" else cleaned
            val dot = if (directory) -1 else safe.lastIndexOf('.').takeIf { it > 0 } ?: -1
            val extension = if (dot >= 0) clip(safe.substring(dot), 40).trimEnd('.', ' ') else ""
            val stem = clip(if (dot >= 0) safe.substring(0, dot) else safe, 220 - extension.toByteArray(Charsets.UTF_8).size)
                .trimEnd('.', ' ').ifEmpty { "未命名" }
            val prefix = if (parent.isEmpty()) "" else "$parent/"
            val key = (prefix + stem + extension).lowercase(Locale.ROOT)
            var number = nextNumbers[key] ?: 1
            while (true) {
                val suffix = if (number == 1) "" else " ($number)"
                val path = prefix + stem + suffix + extension
                if (used.add(path.lowercase(Locale.ROOT))) {
                    nextNumbers[key] = number + 1
                    return path
                }
                number++
            }
        }
        private fun clip(value: String, maxBytes: Int): String {
            var end = 0
            var size = 0
            while (end < value.length) {
                val point = value.codePointAt(end)
                val bytes = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
                if (size + bytes > maxBytes) break
                size += bytes
                end += Character.charCount(point)
            }
            return value.substring(0, end)
        }
    }
}
