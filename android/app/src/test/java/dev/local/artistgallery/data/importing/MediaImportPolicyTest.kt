package dev.local.artistgallery.data.importing

import org.junit.Assert.*
import org.junit.Test

class MediaImportPolicyTest {
    @Test fun filtersOnlyKnownSizesStrictlyBelowFiveKilobytes() {
        listOf(0L, 1L, 5119L).forEach { assertNotNull(MediaImportPolicy.skipReason(it)) }
        listOf(-1L, 5120L, 5121L, Long.MAX_VALUE).forEach { assertNull(MediaImportPolicy.skipReason(it)) }
    }
}
