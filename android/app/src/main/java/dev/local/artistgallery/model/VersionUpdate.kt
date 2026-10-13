package dev.local.artistgallery.model

data class VersionUpdate(val versionCode: Long, val versionName: String, val date: String, val changes: List<String>)

object UpdateRules {
    fun shouldShow(currentVersionCode: Long, acknowledgedVersionCode: Long?, hasBeenUpdated: Boolean): Boolean =
        if (acknowledgedVersionCode == null) hasBeenUpdated else currentVersionCode > acknowledgedVersionCode

    fun recent(updates: List<VersionUpdate>, currentVersionCode: Long): List<VersionUpdate> =
        updates.filter { it.versionCode <= currentVersionCode }.sortedByDescending { it.versionCode }.take(2)
}
