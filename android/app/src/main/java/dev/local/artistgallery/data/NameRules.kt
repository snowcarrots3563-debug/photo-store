package dev.local.artistgallery.data

object NameRules {
    fun normalize(name: String): String = name.trim()

    fun nextTemporaryName(existingNames: Collection<String>): String {
        val used = existingNames.toHashSet()
        var number = 1
        while ("未命名作品集 $number" in used) number++
        return "未命名作品集 $number"
    }

    fun isDuplicate(name: String, existingNames: Collection<String>): Boolean =
        normalize(name) in existingNames
}
