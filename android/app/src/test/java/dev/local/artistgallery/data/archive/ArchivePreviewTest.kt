package dev.local.artistgallery.data.archive

import dev.local.artistgallery.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class ArchivePreviewTest {
    private fun entry(index: Int, path: String, directory: Boolean = false) =
        ArchiveEntry(index, path, if (directory) 0 else 10 * 1024, directory, false)

    @Test fun filtersSmallMediaAndDoesNotCreateAnImportableEmptyGroup() {
        val groups = ArchivePreview.build(listOf(
            ArchiveEntry(0, "小文件/tiny.png", 5119, false, false),
            ArchiveEntry(1, "作品/exact.gif", 5120, false, false),
            ArchiveEntry(2, "作品/empty.mp4", 0, false, false),
            ArchiveEntry(3, "作品/unknown.jpg", -1, false, false),
        ), "包.zip", emptyList())
        assertFalse(groups.first().canImport)
        assertNull(groups.first().items.single().mediaType)
        assertTrue(groups.first().items.single().skipReason!!.contains("小于 5 KB"))
        assertEquals(MediaType.GIF, groups[1].items[0].mediaType)
        assertNull(groups[1].items[1].mediaType)
        assertEquals(MediaType.IMAGE, groups[1].items[2].mediaType)
    }

    @Test fun stripsPublicContainerAndKeepsDeepRelativeDirectories() {
        val preview = ArchivePreview.build(listOf(entry(0, "容器/作品/1.png"), entry(1, "容器/作品/附录/2.gif")), "包.zip", emptyList())
        assertEquals(listOf("作品", "作品/附录"), preview.map { it.name })
        assertEquals(MediaType.GIF, preview[1].items.single().mediaType)
    }

    @Test fun rootMediaUsesArchiveNameAndSingleWorkFolderKeepsItsName() {
        assertEquals("包", ArchivePreview.build(listOf(entry(0, "1.png")), "包.zip", emptyList()).single().name)
        assertEquals("作品", ArchivePreview.build(listOf(entry(0, "作品/1.png")), "包.zip", emptyList()).single().name)
    }

    @Test fun folderKeepsDeepDirectoriesWhileZipStillStripsItsContainer() {
        val entries = listOf(entry(0, "系列/第一章/1.png"), entry(1, "系列/第一章/附录/2.gif"))
        assertEquals(listOf("第一章", "第一章/附录"), ArchivePreview.build(entries, "包.zip", emptyList()).map { it.name })
        assertEquals(listOf("系列/第一章", "系列/第一章/附录"),
            ArchivePreview.build(entries, "收藏.folder", emptyList(), stripCommonContainer = false).map { it.name })
    }

    @Test fun folderRootTitlePreservesDotsAndNumbersNameConflicts() {
        val groups = ArchivePreview.build(listOf(entry(0, "a.png"), entry(1, "子目录/b.mp4")),
            "收藏.v2.zip.folder", listOf("收藏.v2.zip", "子目录"), stripCommonContainer = false)
        assertEquals(listOf("收藏.v2.zip (2)", "子目录 (2)"), groups.map { it.name })
        assertEquals(listOf("", "子目录"), groups.map { it.relativeDirectory })
    }

    @Test fun includesPdfTxtAndEmptyDirectoriesInPreviewWithoutMakingThemImportable() {
        val groups = ArchivePreview.build(listOf(entry(0, "作品/readme.txt"), entry(1, "作品/a.pdf"), entry(2, "空目录/", true)), "包.zip", emptyList())
        assertEquals(2, groups.size)
        assertTrue(groups.all { !it.canImport })
        assertTrue(groups.first().items.all { it.skipReason != null })
    }

    @Test fun mp4OnlyGroupIsImportableAndNameCollisionsReserveNewNames() {
        val groups = ArchivePreview.build(listOf(entry(0, "视频/a.mp4"), entry(1, "视频 (2)/a.png")), "包.zip", listOf("视频"))
        assertEquals(listOf("视频 (2)", "视频 (2) (2)"), groups.map { it.name })
        assertEquals(MediaType.VIDEO, groups.first().items.single().mediaType)
        assertTrue(groups.all { it.canImport })
    }

    @Test fun duplicateFileNamesRemainSeparateEntries() {
        val groups = ArchivePreview.build(listOf(entry(0, "作品/a.png"), entry(1, "作品/a.png")), "包.zip", emptyList())
        assertEquals(listOf(0, 1), groups.single().items.map { it.entry.index })
    }

    @Test fun pdfConversionFailureReasonTakesPriorityAndPagePngIsImportable() {
        val groups = ArchivePreview.build(listOf(
            ArchiveEntry(0, "作品/a.pdf", 100, false, false, "PDF 已加密，无法转换页面。"),
            ArchiveEntry(1, "作品/readme.txt", 100, false, false),
            ArchiveEntry(2, "作品/b.pdf", 100, false, false),
            ArchiveEntry(3, "作品/a/第0001页.png", 5120, false, false),
        ), "包.zip", emptyList())
        assertEquals("PDF 已加密，无法转换页面。", groups.first().items[0].skipReason)
        assertEquals("TXT 暂不导入。", groups.first().items[1].skipReason)
        assertEquals("PDF 页面未能转换。", groups.first().items[2].skipReason)
        assertEquals(MediaType.IMAGE, groups.last().items.single().mediaType)
    }

    @Test fun unsafePathsAreRejected() {
        listOf("../a.png", "作品/../a.png", "/a.png", "C:/a.png", "\\\\server\\a.png", "a\u0000.png").forEach { path ->
            try {
                ArchivePaths.normalize(path)
                fail(path)
            } catch (error: ArchiveReadException) {
                assertEquals(ArchiveError.UNSAFE_PATH, error.reason)
            }
        }
        assertEquals("作品/a.png", ArchivePaths.normalize("作品\\a.png"))
    }
}
