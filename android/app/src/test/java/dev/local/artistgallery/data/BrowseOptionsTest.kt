package dev.local.artistgallery.data

import org.junit.Assert.*
import org.junit.Test

class BrowseOptionsTest {
    @Test fun swipeDeleteIsEnabledByDefault() {
        assertTrue(BrowseOptions().swipeDelete)
    }

    @Test fun existingPositionalOptionsKeepTheirMeaning() {
        val options = BrowseOptions(true, false, ArtistImageLayout.Medium)

        assertTrue(options.cardEffects)
        assertFalse(options.playGifs)
        assertEquals(ArtistImageLayout.Medium, options.imageLayout)
        assertTrue(options.swipeDelete)
    }

    @Test fun disablingSwipeDeletePreservesOtherBrowseOptions() {
        val original = BrowseOptions(true, false, ArtistImageLayout.Large)
        val disabled = original.copy(swipeDelete = false)

        assertFalse(disabled.swipeDelete)
        assertEquals(original.cardEffects, disabled.cardEffects)
        assertEquals(original.playGifs, disabled.playGifs)
        assertEquals(original.imageLayout, disabled.imageLayout)
    }
}
