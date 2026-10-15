package dev.local.artistgallery.ui

import dev.local.artistgallery.data.archive.ArchiveImportState
import dev.local.artistgallery.data.archive.ArchiveStep
import org.junit.Assert.*
import org.junit.Test

class ArchiveSourcePermissionActionTest {
    private val source = ArchiveImportState(visible = true, artistId = "artist", step = ArchiveStep.Source)

    @Test fun sourcePickerPermissionContinuationRequiresTheSameVisibleTargetAndState() {
        val key = archiveSourceActionKey(source)
        assertTrue(isArchiveNotificationActionCurrent(NotificationImportAction.ArchivePick, key, source))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchivePick, key, source.copy(visible = false)))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchivePick, key, source.copy(artistId = "other")))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchivePick, key, source.copy(step = ArchiveStep.Preparing)))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchivePick, key, source.copy(taskId = "new")))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchivePick, key, source.copy(format = "FOLDER")))
    }

    @Test fun folderPickerCannotLaunchAnArchivePickerOrContinueAnOldTarget() {
        val folder = source.copy(format = "FOLDER")
        val key = archiveSourceActionKey(folder)
        assertTrue(isArchiveNotificationActionCurrent(NotificationImportAction.FolderPick, key, folder))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchivePick, key, folder))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.FolderPick, key, folder.copy(artistId = null)))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.FolderPick, key, ArchiveImportState()))
    }

    @Test fun supplementRequiresTheExistingErrorSelectionAndCannotConfirmAnImport() {
        val error = source.copy(step = ArchiveStep.Error, format = "ZIP", taskId = "selected", files = listOf("fixture.zip"))
        val key = archiveSourceActionKey(error)
        assertTrue(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveSupplementPick, key, error))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveSupplementPick, key, error.copy(files = emptyList())))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveSupplementPick, key, error.copy(step = ArchiveStep.Preview)))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveConfirm, key, error))
    }
}
