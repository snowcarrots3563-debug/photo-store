package dev.local.artistgallery.data

import android.content.Context
import androidx.core.content.edit
import dev.local.artistgallery.model.UpdateRules
import dev.local.artistgallery.model.VersionUpdate
import org.json.JSONArray

class LocalUpdateHistory(context: Context) {
    private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val updates = context.assets.open("updates.json").bufferedReader().use { reader ->
        val entries = JSONArray(reader.readText())
        List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val changes = entry.getJSONArray("changes")
            VersionUpdate(entry.getLong("versionCode"), entry.getString("versionName"), entry.getString("date"),
                List(changes.length()) { changes.getString(it) })
        }
    }

    fun shouldShow(currentVersionCode: Long, hasBeenUpdated: Boolean): Boolean {
        val acknowledged = if (preferences.contains("lastAcknowledgedVersionCode")) {
            preferences.getLong("lastAcknowledgedVersionCode", 0L)
        } else null
        if (acknowledged == null && !hasBeenUpdated) acknowledge(currentVersionCode)
        return UpdateRules.shouldShow(currentVersionCode, acknowledged, hasBeenUpdated)
    }

    fun recent(currentVersionCode: Long): List<VersionUpdate> = UpdateRules.recent(updates, currentVersionCode)

    fun all(): List<VersionUpdate> = updates.sortedByDescending { it.versionCode }

    fun acknowledge(currentVersionCode: Long) {
        preferences.edit { putLong("lastAcknowledgedVersionCode", currentVersionCode) }
    }
}
