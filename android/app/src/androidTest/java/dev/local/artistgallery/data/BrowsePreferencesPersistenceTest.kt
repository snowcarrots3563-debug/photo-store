package dev.local.artistgallery.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Redirects only the browse preference file to a unique test file; no gallery data is changed. */
@RunWith(AndroidJUnit4::class)
class BrowsePreferencesPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferencesName = "browse-test-${UUID.randomUUID()}"
    private val isolatedContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name == "browse")
            return context.getSharedPreferences(preferencesName, mode)
        }
    }

    @After fun cleanup() {
        assertTrue(context.deleteSharedPreferences(preferencesName))
    }

    @Test fun oldPreferencesKeepTheirOptionsAndDefaultSwipeDeleteToEnabled() {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putBoolean("cardEffects", true).putBoolean("playGifs", false)
            .putString("imageLayout", ArtistImageLayout.Medium.name).commit())

        val loaded = LocalBrowsePreferences(isolatedContext).options.value

        assertEquals(BrowseOptions(true, false, ArtistImageLayout.Medium, true), loaded)
        assertFalse(preferences.contains("swipeDelete"))
    }

    @Test fun savedSwipeDeleteSurvivesPreferenceRecreationWithoutChangingOtherOptions() {
        val preferences = LocalBrowsePreferences(isolatedContext)
        val disabled = BrowseOptions(true, false, ArtistImageLayout.Large, false)
        preferences.save(disabled)

        assertEquals(disabled, preferences.options.value)
        val recreated = LocalBrowsePreferences(isolatedContext)
        assertEquals(disabled, recreated.options.value)
        recreated.save(disabled.copy(swipeDelete = true))
        assertEquals(disabled.copy(swipeDelete = true), LocalBrowsePreferences(isolatedContext).options.value)
    }
}
