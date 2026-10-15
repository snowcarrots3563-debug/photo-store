package dev.local.artistgallery.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.RoomGalleryRepository
import dev.local.artistgallery.fixture.FixtureGalleryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** 专用真实数据库，禁止打开或清空普通应用的 gallery.db。 */
class GalleryFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "ui-test-${UUID.randomUUID()}.db"
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository
    @Before fun start() {
        database = Room.databaseBuilder(context, GalleryDatabase::class.java, databaseName).build()
        repository = RoomGalleryRepository(database)
        compose.setContent { GalleryApp(repository) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("还没有画师").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun close() { database.close(); context.deleteDatabase(databaseName) }
    private fun openArtistForm() {
        compose.onNodeWithContentDescription("添加").performClick()
        compose.onNode(hasText("添加画师") and hasAnyAncestor(isDialog())).performClick()
    }
    private fun submit(text: String) {
        compose.onNodeWithTag("nameInput").performTextReplacement(text)
        compose.onNode(hasText("添加") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    }
    @Test fun blankAndCancelDoNotWriteThenRealSaveSurvivesReopen() {
        openArtistForm()
        submit("   ")
        compose.onNodeWithText("请输入画师名称，不能只填写空格。").assertIsDisplayed()
        compose.onNodeWithTag("nameInput").performTextReplacement("尚未提交")
        compose.onNode(hasText("取消") and hasClickAction()).performClick()
        assertTrue(runBlocking { repository.observeArtists().first().isEmpty() })
        openArtistForm()
        submit("  同名画师  ")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("同名画师").fetchSemanticsNodes().isNotEmpty() }
        openArtistForm()
        submit("同名画师")
        compose.waitUntil(5_000) { runBlocking { repository.observeArtists().first().size == 2 } }
        val before = runBlocking { repository.observeArtists().first() }
        assertEquals(2, before.map { it.id }.toSet().size)
        val reopened = Room.databaseBuilder(context, GalleryDatabase::class.java, databaseName).build()
        try { assertEquals(before, runBlocking { RoomGalleryRepository(reopened).observeArtists().first() }) }
        finally { reopened.close() }
    }
    @Test fun emptyCollectionAndBlankRenameKeepIdentityAndParent() {
        openArtistForm(); submit("测试画师")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("测试画师").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测试画师").performClick()
        compose.onNodeWithText("作品集").performClick()
        compose.onNodeWithText("新建作品集").performClick()
        compose.onNodeWithText("创建作品集").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("未命名作品集 1").fetchSemanticsNodes().isNotEmpty() }
        val artist = runBlocking { repository.observeArtists().first().single() }
        val original = runBlocking { repository.observeArtist(artist.id).first()!!.collections.single { !it.isDefault } }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("作品集改名").performClick()
        compose.onNodeWithTag("nameInput").performTextReplacement("")
        compose.onNodeWithText("保存名称").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("未命名作品集 2").fetchSemanticsNodes().isNotEmpty() }
        val renamed = runBlocking { repository.observeCollection(original.id).first()!! }
        assertEquals(original.id, renamed.id)
        assertEquals(artist.id, renamed.artistId)
        assertEquals(0, renamed.imageCount)
        assertEquals(2, runBlocking { repository.observeArtists().first().single().collectionCount })
    }
    @Test fun fixtureCountsComeFromReferencesAndNeverTouchRealDatabase() = runBlocking {
        val fixture = FixtureGalleryRepository()
        val artists = fixture.observeArtists().first()
        assertEquals(listOf(5, 1, 0), artists.map { it.imageCount })
        assertEquals(listOf(2, 2, 0), artists.map { it.collectionCount })
        fixture.createArtist("仅样例")
        fixture.createCollection("a1", "仅内存作品集")
        assertTrue(repository.observeArtists().first().isEmpty())
        assertEquals(3, FixtureGalleryRepository().observeArtists().first().size)
    }
}
