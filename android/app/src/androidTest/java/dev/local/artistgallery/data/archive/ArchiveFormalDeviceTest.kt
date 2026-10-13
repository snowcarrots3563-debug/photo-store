package dev.local.artistgallery.data.archive

import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.artistgallery.data.*
import dev.local.artistgallery.data.importing.*
import dev.local.artistgallery.data.preview.*
import dev.local.artistgallery.model.SaveResult
import dev.local.artistgallery.ui.GalleryApp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production controller and UI, with a separate database and UUID assets. */
@RunWith(AndroidJUnit4::class)
class ArchiveFormalDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Test(timeout = 900_000) fun encryptedRealZipUsesFormalControllerAndResultUi() = runBlocking {
        assertEquals("dev.local.artistgallery.phase2test", context.packageName)
        val external = requireNotNull(context.getExternalFilesDir(null))
        val folder = File(external, requireNotNull(arguments.getString("archiveFolder"))).canonicalFile
        require(folder.parentFile == external.canonicalFile)
        val source = File(folder, "input.zip")
        val reference = JSONObject(File(folder, "reference-manifest.json").readText())
        val summary = reference.getJSONObject("summary")
        val expected = reference.getJSONArray("entries").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("path") }
        }
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = RoomGalleryRepository(db)
        val files = MediaFileStore(context)
        val importer = MediaImportCoordinator(context, repo, files)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = ArchiveImportCoordinator(context, db, repo, importer, files, scope)
        val reader = AssetReader(context, files)
        val decoder = PreviewDecoder(context.contentResolver, reader)
        val started = android.os.SystemClock.elapsedRealtime()
        val report = JSONObject().put("normalGalleryTouched", false).put("controller", "ArchiveImportCoordinator")
            .put("sourceSelection", "real file URI; SAF round trip tested separately").put("inputSha256", summary.getString("archiveSha256"))
        var taskId: String? = null
        try {
            controller.recover()
            val artist = (repo.createArtist("压缩导入实机验收") as SaveResult.Saved).id
            compose.setContent { GalleryApp(repo, mediaRepository = repo, mediaImporter = importer, previewDecoder = decoder, assetReader = reader, archiveImporter = controller) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("压缩导入实机验收").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("添加").performClick()
            compose.onNodeWithText("ZIP 压缩包").performClick()
            compose.onNodeWithText("ZIP 压缩包导入").assertIsDisplayed()
            compose.onNodeWithText("选择画师").performClick()
            compose.onNode(hasText("压缩导入实机验收") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            assertEquals(artist, controller.state.value.artistId)
            screenshot(folder, "formal-target")
            assertEquals(ArchiveStep.Source, controller.state.value.step)
            controller.pick(listOf(Uri.fromFile(source)), false)
            await(controller, ArchiveStep.Password)
            taskId = requireNotNull(controller.state.value.taskId)
            screenshot(folder, "formal-password")
            compose.onNodeWithContentDescription("压缩包密码").performTextInput(UUID.randomUUID().toString())
            compose.onNodeWithText("解锁预览").performClick()
            withTimeout(120_000) { controller.state.first { it.step == ArchiveStep.Password && it.message != null } }
            compose.onNodeWithText("解锁预览").assertIsNotEnabled()
            screenshot(folder, "formal-wrong-password")
            // The password arrives only as an instrumentation argument, never as a fixture or report field.
            compose.onNodeWithContentDescription("压缩包密码").performTextInput(requireNotNull(arguments.getString("archivePassword")))
            compose.onNodeWithText("解锁预览").performClick()
            await(controller, ArchiveStep.Preview)
            val preview = controller.state.value
            assertEquals(summary.getInt("groups"), preview.groups.size)
            assertEquals(summary.getInt("importableGroups"), preview.groups.count { it.canImport })
            assertEquals(summary.getInt("supportedMedia"), preview.groups.sumOf { group -> group.items.count { it.mediaType != null } })
            assertEquals(summary.getInt("skippedFiles"), preview.groups.sumOf { group -> group.items.count { it.skipReason != null } })
            assertTrue(preview.groups.flatMap { it.items }.filter { it.mediaType == null }.all { !it.skipReason.isNullOrBlank() })
            compose.onNodeWithText("开始导入").assertIsEnabled()
            screenshot(folder, "formal-preview")
            compose.onNodeWithText("开始导入").performClick()
            await(controller, ArchiveStep.Importing)
            screenshot(folder, "formal-progress")
            compose.onNodeWithText("后台继续").performClick()
            assertFalse(controller.state.value.visible)
            controller.showCurrent()
            await(controller, ArchiveStep.Result)
            compose.onNodeWithText("导入结果").assertIsDisplayed()
            screenshot(folder, "formal-result")
            val items = repo.importItems(requireNotNull(taskId))
            assertEquals(summary.getInt("files"), items.size)
            assertEquals(summary.getInt("supportedMedia"), items.count { it.status == "COMPLETE" })
            assertEquals(summary.getInt("skippedFiles"), items.count { it.status == "SKIPPED" })
            assertTrue(items.all { it.status in setOf("COMPLETE", "SKIPPED") })
            assertEquals(summary.getInt("importableGroups"), controller.state.value.collectionNames.size)
            val groups = db.galleryDao().archiveGroups(requireNotNull(taskId))
            val collections = db.galleryDao().getCollections(artist)
            assertEquals(summary.getInt("importableGroups"), collections.size)
            val results = JSONArray()
            items.forEach { item ->
                val expectedItem = expected.getValue(requireNotNull(item.sourceRelativePath))
                val result = JSONObject().put("path", item.sourceRelativePath).put("status", item.status)
                if (item.status == "COMPLETE") {
                    val asset = requireNotNull(repo.getAsset(item.id))
                    assertEquals(expectedItem.getString("sha256"), asset.sha256)
                    assertEquals(expectedItem.getLong("byteSize"), asset.byteSize)
                    assertEquals(asset.sha256, files.inspectOriginal(asset.storageKey).sha256)
                    assertEquals(1, repo.referenceCount(asset.id))
                    val collection = requireNotNull(db.galleryDao().getCollection(requireNotNull(item.archiveGroupId)))
                    val group = groups.single { it.id == collection.id }
                    assertEquals(preview.groups.single { it.relativeDirectory == group.relativeDirectory }.name, collection.name)
                    assertEquals(collection.id, item.targetCollectionId)
                    when (asset.mediaType) {
                        "IMAGE" -> { val bitmap = requireNotNull(decoder.decodeBitmap(asset.storageKey, 256, 256)); assertTrue(bitmap.width > 0); bitmap.recycle() }
                        "GIF" -> { val drawable = decoder.decodeAnimated(asset.storageKey, 256, 256); assertTrue(drawable is AnimatedImageDrawable); (drawable as AnimatedImageDrawable).stop() }
                        "VIDEO" -> MediaMetadataRetriever().use { metadata ->
                            metadata.setDataSource(context, reader.contentUri(asset.storageKey))
                            assertTrue((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0)
                        }
                    }
                    result.put("hashMatched", true).put("previewDecoded", true).put("collection", collection.name)
                } else result.put("reason", item.errorMessage)
                results.put(result)
            }
            collections.forEach { collection ->
                val group = groups.single { it.id == collection.id }
                assertEquals(preview.groups.single { it.relativeDirectory == group.relativeDirectory }.items.count { it.mediaType != null }, repo.observeCollectionMedia(collection.id).first().size)
            }
            withTimeout(5_000) { while (File(external, ".archive-imports/$taskId").exists()) delay(50) }
            controller.done()
            withTimeout(5_000) { controller.state.first { !it.visible && it.taskId == null } }
            delay(100)
            controller.history()
            await(controller, ArchiveStep.History)
            assertEquals(1, controller.state.value.history.size)
            delay(100)
            controller.resume(requireNotNull(taskId))
            await(controller, ArchiveStep.Result)
            assertEquals(summary.getInt("supportedMedia"), repo.importItems(requireNotNull(taskId)).count { it.status == "COMPLETE" })
            assertEquals(summary.getString("archiveSha256"), sha256(source))
            report.put("status", "PASSED").put("createdCollections", collections.size).put("newAssets", items.count { it.status == "COMPLETE" })
                .put("skippedFiles", items.count { it.status == "SKIPPED" }).put("wrongPasswordRetry", true).put("closeContinues", true)
                .put("historyResumeIdempotent", true).put("cacheCleaned", true).put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started).put("items", results)
            File(folder, "formal-device-results.json").writeText(report.toString(2))
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            taskId?.let { id -> repo.importItems(id).forEach { files.deleteStaging(it.stagingKey); files.deleteOriginal(it.storageKey) }
                val ownFolder = File(external, ".archive-imports/$id").canonicalFile
                require(ownFolder.parentFile == File(external, ".archive-imports").canonicalFile && ownFolder.name == UUID.fromString(id).toString())
                ownFolder.deleteRecursively()
            }
            db.close()
        }
    }

    private suspend fun await(controller: ArchiveImportCoordinator, step: ArchiveStep) {
        withTimeout(600_000) { controller.state.first { it.step == step || it.step == ArchiveStep.Error } }
        assertEquals(controller.state.value.message, step, controller.state.value.step)
        delay(100)
    }

    private fun screenshot(folder: File, name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
