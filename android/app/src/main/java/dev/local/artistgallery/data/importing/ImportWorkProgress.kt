package dev.local.artistgallery.data.importing

data class ImportWorkProgress(
    val title: String,
    val detail: String,
    val completed: Long = 0,
    val total: Long? = null,
    val target: ImportResultTarget? = null,
) {
    val processed: Long get() = completed.coerceAtLeast(0).let { count -> total?.let { count.coerceAtMost(it.coerceAtLeast(0)) } ?: count }
    val isIndeterminate: Boolean get() = total == null || total <= 0
    val barMaximum: Int get() = total?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
    val barValue: Int get() {
        val count = processed
        val size = total ?: return 0
        if (size <= 0) return 0
        if (size <= Int.MAX_VALUE) return count.toInt()
        if (count == size) return barMaximum
        return (count.toDouble() / size * barMaximum).toInt().coerceIn(0, barMaximum - 1)
    }
}
