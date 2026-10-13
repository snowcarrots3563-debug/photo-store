package dev.local.artistgallery.data.archive

import org.junit.Assert.*
import org.junit.Test

class ArchiveBundleTest {
    @Test fun detectsEachFormatIncludingOutOfOrderAndMissingFirstVolumes() {
        listOf(listOf("作品.ZIP"), listOf("作品.z02", "作品.zip", "作品.z01"), listOf("作品.zip.002")).forEach {
            assertEquals("ZIP", ArchiveBundle.detectFormat(it))
        }
        listOf(listOf("作品.RAR"), listOf("作品.part02.rar", "作品.part01.rar"), listOf("作品.r00")).forEach {
            assertEquals("RAR", ArchiveBundle.detectFormat(it))
        }
        listOf(listOf("作品.7Z"), listOf("作品.7z.002", "作品.7z.001"), listOf("作品.7z.003")).forEach {
            assertEquals("7z", ArchiveBundle.detectFormat(it))
        }
    }
    @Test fun automaticDetectionStillRejectsMixedUnsupportedAndUnsafeSelections() {
        error(ArchiveError.CORRUPT) { ArchiveBundle.detectFormat(listOf("a.zip", "b.rar")) }
        error(ArchiveError.CORRUPT) { ArchiveBundle.detectFormat(listOf("a.zip", "readme.txt")) }
        error(ArchiveError.CORRUPT) { ArchiveBundle.detectFormat(listOf("a.tar")) }
        error(ArchiveError.MISSING_VOLUME) { ArchiveBundle.detectFormat(emptyList()) }
        error(ArchiveError.UNSAFE_PATH) { ArchiveBundle.detectFormat(listOf("../a.zip")) }
        val missing = listOf("a.7z.002")
        error(ArchiveError.MISSING_VOLUME) { ArchiveBundle.mainName(ArchiveBundle.detectFormat(missing), missing) }
        val foreign = listOf("a.zip", "b.z01")
        error(ArchiveError.CORRUPT) { ArchiveBundle.mainName(ArchiveBundle.detectFormat(foreign), foreign) }
    }

    @Test fun findsMainFileAcrossOutOfOrderSupportedVolumeNames() {
        assertEquals("作品.zip", ArchiveBundle.mainName("ZIP", listOf("作品.z02", "作品.zip", "作品.z01")))
        assertEquals("作品.zip.001", ArchiveBundle.mainName("ZIP", listOf("作品.zip.002", "作品.zip.001")))
        assertEquals("作品.7z.001", ArchiveBundle.mainName("7z", listOf("作品.7z.003", "作品.7z.001", "作品.7z.002")))
        assertEquals("作品.part01.rar", ArchiveBundle.mainName("RAR", listOf("作品.part02.rar", "作品.part01.rar")))
        assertEquals("作品.rar", ArchiveBundle.mainName("RAR", listOf("作品.r01", "作品.rar", "作品.r00")))
    }
    @Test fun missingFirstOrMiddleVolumeIsReported() {
        error(ArchiveError.MISSING_VOLUME) { ArchiveBundle.mainName("7z", listOf("作品.7z.002")) }
        error(ArchiveError.MISSING_VOLUME) { ArchiveBundle.mainName("ZIP", listOf("作品.zip", "作品.z02")) }
        error(ArchiveError.MISSING_VOLUME) { ArchiveBundle.mainName("RAR", listOf("作品.part1.rar", "作品.part3.rar")) }
    }
    @Test fun foreignAndDuplicateSelectionsAreRejected() {
        error(ArchiveError.CORRUPT) { ArchiveBundle.mainName("ZIP", listOf("作品.zip", "其他.z01")) }
        error(ArchiveError.CORRUPT) { ArchiveBundle.mainName("7z", listOf("a.7z", "a.7z")) }
        error(ArchiveError.CORRUPT) { ArchiveBundle.mainName("RAR", listOf("a.rar", "A.RAR")) }
    }
    @Test fun providerNamesCannotEscapeBundleDirectory() {
        listOf("../a.zip", "/a.zip", "C:/a.zip", "目录/a.zip", "a\\b.zip").forEach { name -> error(ArchiveError.UNSAFE_PATH) { ArchiveBundle.safeName(name) } }
    }
    @Test fun rootCollectionTitleRemovesWholeVolumeSuffix() {
        assertEquals("作品.v2", ArchiveBundle.title("作品.v2.part001.rar"))
        assertEquals("作品", ArchiveBundle.title("作品.7z.001"))
    }
    private fun error(reason: ArchiveError, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (error: ArchiveReadException) { assertEquals(reason, error.reason) }
    }
}
