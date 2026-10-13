package dev.local.artistgallery.data.importing

object MediaImportPolicy {
    fun skipReason(byteSize: Long): String? =
        if (byteSize in 0 until 5L * 1024) "文件小于 5 KB，已自动跳过。" else null
}
