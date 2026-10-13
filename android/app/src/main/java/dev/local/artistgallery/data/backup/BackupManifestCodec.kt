package dev.local.artistgallery.data.backup

import dev.local.artistgallery.data.ArtistEntity
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.CollectionEntity
import dev.local.artistgallery.data.CollectionMediaEntity
import dev.local.artistgallery.model.MediaSort
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

object BackupManifestCodec {
    fun encode(manifest: BackupManifest): ByteArray {
        BackupValidation.validate(manifest)
        val document = JSONObject().apply {
            put("format", "artist-gallery-backup")
            put("version", manifest.version); put("createdAt", manifest.createdAt)
            put("assets", array(manifest.assets) { a -> JSONObject().apply {
                put("id", a.id); put("originalName", a.originalName); put("mediaType", a.mediaType)
                put("byteSize", a.byteSize); put("width", a.width ?: JSONObject.NULL); put("height", a.height ?: JSONObject.NULL)
                put("sha256", a.sha256); put("createdAt", a.createdAt)
            } })
            put("artists", array(manifest.artists) { a -> JSONObject().apply {
                put("id", a.id); put("name", a.name); put("createdAt", a.createdAt); put("updatedAt", a.updatedAt); put("coverAssetId", a.coverAssetId ?: JSONObject.NULL)
            } })
            put("collections", array(manifest.collections) { c -> JSONObject().apply {
                put("id", c.id); put("artistId", c.artistId); put("name", c.name); put("isTemporary", c.isTemporary); put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
            } })
            put("references", array(manifest.references) { r -> JSONObject().apply {
                put("collectionId", r.collectionId); put("assetId", r.assetId); put("manualOrder", r.manualOrder)
                put("sourceName", r.sourceName); put("sourceRelativePath", r.sourceRelativePath ?: JSONObject.NULL)
            } })
            put("settings", settingsJson(manifest.settings))
        }
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > BackupValidation.MAX_MANIFEST_BYTES) throw IOException("备份清单过大。")
        return bytes
    }

    fun decode(bytes: ByteArray): BackupManifest {
        if (bytes.size > BackupValidation.MAX_MANIFEST_BYTES) throw IOException("备份清单过大。")
        try {
            val document = JSONObject(bytes.toString(Charsets.UTF_8))
            if (document.getString("format") != "artist-gallery-backup") throw IOException("不是本应用的完整备份。")
            if (document.getInt("version") != 1) throw IOException("备份版本不兼容，请使用支持此版本的应用。")
            val manifest = BackupManifest(
                version = 1, createdAt = document.getLong("createdAt"),
                assets = rows(document.getJSONArray("assets")) { a -> BackupAsset(a.getString("id"), a.getString("originalName"), a.getString("mediaType"), a.getLong("byteSize"), a.nullInt("width"), a.nullInt("height"), a.getString("sha256"), a.getLong("createdAt")) },
                artists = rows(document.getJSONArray("artists")) { a -> ArtistEntity(a.getString("id"), a.getString("name"), a.getLong("createdAt"), a.getLong("updatedAt"), a.nullString("coverAssetId")) },
                collections = rows(document.getJSONArray("collections")) { c -> CollectionEntity(c.getString("id"), c.getString("artistId"), c.getString("name"), c.getBoolean("isTemporary"), c.getLong("createdAt"), c.getLong("updatedAt")) },
                references = rows(document.getJSONArray("references")) { r -> CollectionMediaEntity(r.getString("collectionId"), r.getString("assetId"), r.getInt("manualOrder"), r.getString("sourceName"), r.nullString("sourceRelativePath")) },
                settings = readSettings(document.getJSONObject("settings")),
            )
            BackupValidation.validate(manifest)
            return manifest
        } catch (error: IOException) { throw error }
        catch (error: Exception) { throw IOException("备份清单无法读取或已损坏。", error) }
    }

    internal fun settingsJson(settings: BrowseOptions): JSONObject = JSONObject().apply {
        put("cardEffects", settings.cardEffects); put("playGifs", settings.playGifs); put("swipeDelete", settings.swipeDelete)
        put("imageLayout", settings.imageLayout.name); put("storageNoticeDismissed", settings.storageNoticeDismissed)
        put("artistOrder", JSONArray(settings.artistOrder))
        put("collectionOrders", JSONObject().apply { settings.collectionOrders.forEach { (id, order) -> put(id, JSONArray(order)) } })
        put("artistLayouts", JSONObject().apply { settings.artistLayouts.forEach { (id, layout) -> put(id, layout.name) } })
        put("artistImageSorts", JSONObject().apply { settings.artistImageSorts.forEach { (id, sort) -> put(id, sort.name) } })
        put("collectionMediaSorts", JSONObject().apply { settings.collectionMediaSorts.forEach { (id, sort) -> put(id, sort.name) } })
    }

    internal fun readSettings(json: JSONObject): BrowseOptions = BrowseOptions(
        cardEffects = json.getBoolean("cardEffects"), playGifs = json.getBoolean("playGifs"), swipeDelete = json.getBoolean("swipeDelete"),
        imageLayout = ArtistImageLayout.valueOf(json.getString("imageLayout")), storageNoticeDismissed = json.getBoolean("storageNoticeDismissed"),
        artistOrder = strings(json.getJSONArray("artistOrder")),
        collectionOrders = json.getJSONObject("collectionOrders").let { obj -> boundedKeys(obj).associateWith { strings(obj.getJSONArray(it)) } },
        artistLayouts = json.getJSONObject("artistLayouts").let { obj -> boundedKeys(obj).associateWith { ArtistImageLayout.valueOf(obj.getString(it)) } },
        artistImageSorts = if (!json.has("artistImageSorts")) emptyMap() else json.getJSONObject("artistImageSorts").let { obj ->
            boundedKeys(obj).associateWith { MediaSort.valueOf(obj.getString(it)) }
        },
        collectionMediaSorts = if (!json.has("collectionMediaSorts")) emptyMap() else json.getJSONObject("collectionMediaSorts").let { obj ->
            boundedKeys(obj).associateWith { MediaSort.valueOf(obj.getString(it)) }
        },
    )

    private fun JSONObject.nullInt(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun boundedKeys(json: JSONObject): List<String> {
        if (json.length() > BackupValidation.MAX_RECORDS) throw IOException("备份记录过多。")
        return json.keys().asSequence().toList()
    }
    private fun strings(array: JSONArray): List<String> {
        if (array.length() > BackupValidation.MAX_RECORDS) throw IOException("备份记录过多。")
        return (0 until array.length()).map { array.getString(it) }
    }
    private fun <T> rows(array: JSONArray, read: (JSONObject) -> T): List<T> {
        if (array.length() > BackupValidation.MAX_RECORDS) throw IOException("备份记录过多。")
        return (0 until array.length()).map { read(array.getJSONObject(it)) }
    }
    private fun <T> array(items: List<T>, write: (T) -> JSONObject): JSONArray = JSONArray().apply { items.forEach { put(write(it)) } }
}
