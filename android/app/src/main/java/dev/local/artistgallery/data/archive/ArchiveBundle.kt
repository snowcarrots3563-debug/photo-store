package dev.local.artistgallery.data.archive

/** Names are provider metadata; they never become paths until checked here. */
object ArchiveBundle {
    fun detectFormat(names: List<String>): String {
        if (names.isEmpty()) throw ArchiveReadException(ArchiveError.MISSING_VOLUME, "请选择压缩包或分卷文件。")
        val formats = names.map { name ->
            safeName(name)
            when {
                Regex("(?i).*\\.(zip(?:\\.\\d{3,})?|z\\d+)").matches(name) -> "ZIP"
                Regex("(?i).*\\.(rar|r\\d+)").matches(name) -> "RAR"
                Regex("(?i).*\\.7z(?:\\.\\d{3,})?").matches(name) -> "7z"
                else -> throw ArchiveReadException(ArchiveError.CORRUPT, "请选择 ZIP、RAR 或 7z 压缩包及其分卷。")
            }
        }.distinct()
        if (formats.size != 1) wrongBundle()
        return formats.single()
    }

    fun safeName(name: String): String {
        if (name.isBlank() || name != ArchivePaths.normalize(name) || '/' in name || '\\' in name || name == "." || name == "..") {
            throw ArchiveReadException(ArchiveError.UNSAFE_PATH, "所选文件名称不安全。")
        }
        return name
    }

    fun mainName(format: String, names: List<String>): String {
        names.forEach(::safeName)
        if (names.isEmpty() || names.size != names.toSet().size) throw ArchiveReadException(ArchiveError.CORRUPT, "请选择同一包的文件，不能包含同名分卷。")
        val lower = names.associateBy { it.lowercase() }
        if (lower.size != names.size) throw ArchiveReadException(ArchiveError.CORRUPT, "分卷名称大小写冲突。")
        val main = when (format) {
            "ZIP" -> names.singleOrNull { it.endsWith(".zip", true) } ?: names.singleOrNull { it.endsWith(".zip.001", true) }
            "7z" -> names.singleOrNull { it.endsWith(".7z", true) }
                ?: names.singleOrNull { it.endsWith(".7z.001", true) }
            "RAR" -> names.singleOrNull { Regex("(?i).*\\.part0*1\\.rar").matches(it) }
                ?: names.singleOrNull { it.endsWith(".rar", true) && !Regex("(?i).*\\.part\\d+\\.rar").matches(it) }
            else -> null
        } ?: throw ArchiveReadException(ArchiveError.MISSING_VOLUME, "请选择主压缩包或首卷，并补齐同一包的全部分卷。")
        val key = main.lowercase()
        val numbered = when {
            key.endsWith(".7z.001") || key.endsWith(".zip.001") -> {
                val prefix = key.removeSuffix("001")
                names.map { name -> Regex("${Regex.escape(prefix)}(\\d{3,})").matchEntire(name.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: wrongBundle() }
            }
            format == "ZIP" -> {
                val prefix = key.removeSuffix(".zip")
                names.filter { it != main }.map { name ->
                    val match = Regex("${Regex.escape(prefix)}\\.z(\\d+)").matchEntire(name.lowercase()) ?: wrongBundle()
                    match.groupValues[1].toIntOrNull() ?: wrongBundle()
                }
            }
            Regex(".*\\.part0*1\\.rar").matches(key) -> {
                val prefix = key.substringBeforeLast(".part")
                names.map { name -> Regex("${Regex.escape(prefix)}\\.part(\\d+)\\.rar").matchEntire(name.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: wrongBundle() }
            }
            format == "RAR" -> {
                val prefix = key.removeSuffix(".rar")
                names.filter { it != main }.map { name -> (Regex("${Regex.escape(prefix)}\\.r(\\d+)").matchEntire(name.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: wrongBundle()) + 1 }
            }
            else -> { if (names.size != 1) wrongBundle(); emptyList() }
        }
        if (numbered.sorted() != (1..numbered.size).toList()) throw ArchiveReadException(ArchiveError.MISSING_VOLUME, "分卷编号不连续，请补齐缺少的分卷。")
        return main
    }

    fun title(main: String): String = main.replace(Regex("(?i)\\.part0*1\\.rar$|\\.(7z|zip)\\.001$|\\.(zip|rar|7z)$"), "")

    private fun wrongBundle(): Nothing = throw ArchiveReadException(ArchiveError.CORRUPT, "所选文件不属于同一个压缩包，请重新选择。")
}
