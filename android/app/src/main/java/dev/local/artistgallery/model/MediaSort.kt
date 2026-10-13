package dev.local.artistgallery.model

enum class MediaSort(val label: String) {
    Default("默认顺序"),
    SizeAscending("大小从小到大"), SizeDescending("大小从大到小"),
    AddedAscending("加入时间从早到晚"), AddedDescending("加入时间从晚到早"),
    NameAscending("名称升序"), NameDescending("名称降序"),
}

fun sortArtistImages(items: List<MediaAssetSummary>, sort: MediaSort): List<MediaAssetSummary> =
    items.sortedWith { left, right ->
        val primary = when (sort) {
            MediaSort.Default, MediaSort.AddedAscending -> left.createdAt.compareTo(right.createdAt)
            MediaSort.AddedDescending -> right.createdAt.compareTo(left.createdAt)
            MediaSort.SizeAscending -> left.byteSize.compareTo(right.byteSize)
            MediaSort.SizeDescending -> right.byteSize.compareTo(left.byteSize)
            MediaSort.NameAscending -> naturalCompare(left.name, right.name)
            MediaSort.NameDescending -> naturalCompare(right.name, left.name)
        }
        if (primary != 0) primary else left.id.compareTo(right.id)
    }

fun sortCollectionMedia(items: List<MediaAssetSummary>, sort: MediaSort): List<MediaAssetSummary> =
    if (sort == MediaSort.Default) items.sortedWith(compareBy(MediaAssetSummary::manualOrder, MediaAssetSummary::id))
    else sortArtistImages(items, sort)

internal fun naturalCompare(left: String, right: String): Int {
    var i = 0
    var j = 0
    while (i < left.length && j < right.length) {
        val leftDigit = left[i] in '0'..'9'
        val rightDigit = right[j] in '0'..'9'
        if (leftDigit && rightDigit) {
            val leftEnd = (i until left.length).firstOrNull { left[it] !in '0'..'9' } ?: left.length
            val rightEnd = (j until right.length).firstOrNull { right[it] !in '0'..'9' } ?: right.length
            val leftNumber = left.substring(i, leftEnd).trimStart('0').ifEmpty { "0" }
            val rightNumber = right.substring(j, rightEnd).trimStart('0').ifEmpty { "0" }
            val byDigits = leftNumber.length.compareTo(rightNumber.length)
            if (byDigits != 0) return byDigits
            val byValue = leftNumber.compareTo(rightNumber)
            if (byValue != 0) return byValue
            i = leftEnd
            j = rightEnd
        } else {
            val byChar = left[i].lowercaseChar().compareTo(right[j].lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    val byLength = (left.length - i).compareTo(right.length - j)
    return if (byLength != 0) byLength else left.compareTo(right, ignoreCase = true)
}
