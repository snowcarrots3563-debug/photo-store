package dev.local.artistgallery.ui

internal fun albumReadPermissions(sdkInt: Int): Array<String> = when {
    sdkInt >= 34 -> arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    sdkInt >= 33 -> arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
    else -> arrayOf("android.permission.READ_EXTERNAL_STORAGE")
}
