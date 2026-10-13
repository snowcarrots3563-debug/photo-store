package dev.local.artistgallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AlbumPermissionsTest {
    @Test fun supportedLegacyVersionsRequestOnlyReadStorage() {
        for (sdk in listOf(26, 29, 32)) {
            assertEquals(setOf("android.permission.READ_EXTERNAL_STORAGE"), albumReadPermissions(sdk).toSet())
        }
    }

    @Test fun android13RequestsImagesAndVideosWithoutLegacyStorage() {
        assertEquals(setOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"), albumReadPermissions(33).toSet())
    }

    @Test fun android14AndLaterSupportSelectedPhotosInTheSameRequest() {
        for (sdk in listOf(34, 35, 36)) {
            assertEquals(setOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"), albumReadPermissions(sdk).toSet())
        }
    }

    @Test fun albumImportNeverRequestsWriteOrAllFilesAccess() {
        for (sdk in 26..36) {
            val permissions = albumReadPermissions(sdk)
            assertFalse(permissions.any { it.contains("WRITE") || it.contains("MANAGE") })
            assertEquals(permissions.size, permissions.toSet().size)
        }
    }
}
