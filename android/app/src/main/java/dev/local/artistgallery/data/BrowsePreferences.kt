package dev.local.artistgallery.data

import android.content.Context
import dev.local.artistgallery.model.MediaSort
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ArtistImageLayout(val label: String, val columns: Int) {
    List("列表", 1), Large("大图标", 3), Medium("小图标", 5)
}

data class BrowseOptions(
    val cardEffects: Boolean = false,
    val playGifs: Boolean = true,
    val imageLayout: ArtistImageLayout = ArtistImageLayout.List,
    val swipeDelete: Boolean = true,
    val artistOrder: List<String> = emptyList(),
    val collectionOrders: Map<String, List<String>> = emptyMap(),
    val artistLayouts: Map<String, ArtistImageLayout> = emptyMap(),
    val storageNoticeDismissed: Boolean = false,
    val artistImageSorts: Map<String, MediaSort> = emptyMap(),
    val collectionMediaSorts: Map<String, MediaSort> = emptyMap(),
)

interface BrowsePreferences {
    val options: StateFlow<BrowseOptions>
    fun save(options: BrowseOptions)
    fun saveDurably(options: BrowseOptions) { save(options) }
}

class LocalBrowsePreferences(context: Context) : BrowsePreferences {
    private val preferences = context.getSharedPreferences("browse", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(BrowseOptions(
        preferences.getBoolean("cardEffects", false), preferences.getBoolean("playGifs", true),
        ArtistImageLayout.entries.firstOrNull { it.name == preferences.getString("imageLayout", null) } ?: ArtistImageLayout.List,
        preferences.getBoolean("swipeDelete", true),
        readArray(preferences.getString("artistOrder", null)),
        readObject(preferences.getString("collectionOrders", null)).let { obj ->
            obj.keys().asSequence().associateWith { readArray(obj.optJSONArray(it)?.toString()) }
        },
        readObject(preferences.getString("artistLayouts", null)).let { obj ->
            obj.keys().asSequence().mapNotNull { id ->
                ArtistImageLayout.entries.firstOrNull { it.name == obj.optString(id) }?.let { id to it }
            }.toMap()
        },
        preferences.getBoolean("storageNoticeDismissed", false),
        readObject(preferences.getString("artistImageSorts", null)).let { obj ->
            obj.keys().asSequence().mapNotNull { id ->
                MediaSort.entries.firstOrNull { it.name == obj.optString(id) }?.let { id to it }
            }.toMap()
        },
        readObject(preferences.getString("collectionMediaSorts", null)).let { obj ->
            obj.keys().asSequence().mapNotNull { id ->
                MediaSort.entries.firstOrNull { it.name == obj.optString(id) }?.let { id to it }
            }.toMap()
        },
    ))
    private fun readObject(value: String?): JSONObject = runCatching { JSONObject(value ?: "{}") }.getOrDefault(JSONObject())
    private fun readArray(value: String?): List<String> = runCatching {
        val array = JSONArray(value ?: "[]")
        (0 until array.length()).map { array.getString(it) }.distinct()
    }.getOrDefault(emptyList())
    override val options = current.asStateFlow()
    override fun save(options: BrowseOptions) { write(options, durable = false) }
    override fun saveDurably(options: BrowseOptions) { write(options, durable = true) }
    private fun write(options: BrowseOptions, durable: Boolean) {
        val editor = preferences.edit().apply {
            putBoolean("cardEffects", options.cardEffects); putBoolean("playGifs", options.playGifs)
            putString("imageLayout", options.imageLayout.name)
            putBoolean("swipeDelete", options.swipeDelete)
            putString("artistOrder", JSONArray(options.artistOrder).toString())
            putString("collectionOrders", JSONObject().apply {
                options.collectionOrders.forEach { (id, order) -> put(id, JSONArray(order)) }
            }.toString())
            putString("artistLayouts", JSONObject().apply {
                options.artistLayouts.forEach { (id, layout) -> put(id, layout.name) }
            }.toString())
            putBoolean("storageNoticeDismissed", options.storageNoticeDismissed)
            putString("artistImageSorts", JSONObject().apply {
                options.artistImageSorts.forEach { (id, sort) -> put(id, sort.name) }
            }.toString())
            putString("collectionMediaSorts", JSONObject().apply {
                options.collectionMediaSorts.forEach { (id, sort) -> put(id, sort.name) }
            }.toString())
        }
        if (durable) {
            if (!editor.commit()) throw IOException("恢复的浏览设置保存失败，将在下次启动重试。")
        } else editor.apply()
        current.value = options
    }
}

internal fun <T> orderedEntities(items: List<T>, order: List<String>, id: (T) -> String): List<T> {
    val ranks = order.withIndex().associate { it.value to it.index }
    return items.sortedBy { ranks[id(it)] ?: Int.MAX_VALUE }
}

internal fun reorderVisibleIds(current: List<String>, requested: List<String>): List<String> {
    val known = current.toSet()
    val reordered = requested.filter { it in known }.distinct()
    val visible = reordered.toSet()
    val iterator = reordered.iterator()
    return current.map { if (it in visible) iterator.next() else it }
}
