package dev.local.artistgallery.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.fixture.UiFixtureActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Canvas pixels and controlled clock; no gallery, decoder, preferences or phone settings. */
class ArtistLayoutMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()

    @Test fun samePictureMovesAndResizesInsteadOfFadingTheWholeRegion() {
        compose.mainClock.autoAdvance = false
        val from = Rect(20f, 40f, 80f, 100f)
        val to = Rect(180f, 200f, 300f, 320f)
        val motion = show(from)
        compose.runOnIdle { motion.begin(ArtistImageLayout.Large); destination(motion, to) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.advanceTimeBy(160)
        val progress = compose.onNodeWithTag("artistLayoutOverlay").fetchSemanticsNode().config[DMotionFraction]
        assertTrue(progress > 0f && progress < .8f)
        val center = androidx.compose.ui.geometry.lerp(from, to, progress).center
        val overlay = compose.onNodeWithTag("artistLayoutOverlay")
        val origin = overlay.fetchSemanticsNode().boundsInRoot.topLeft
        val pixels = overlay.captureToImage().toPixelMap()
        assertTrue("The same red picture must exist at its intermediate position", pixels[(center.x - origin.x).toInt(), (center.y - origin.y).toInt()].red > .9f)
        assertTrue(center.x > from.center.x && center.x < to.center.x)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("artistLayoutOverlay").assertDoesNotExist()
    }

    @Test fun rapidSwitchStartsFromTheCurrentlyDrawnBoundsAndOldCompletionCannotClearIt() {
        compose.mainClock.autoAdvance = false
        val motion = show(Rect(20f, 40f, 80f, 100f))
        compose.runOnIdle { motion.begin(ArtistImageLayout.Large); destination(motion, Rect(180f, 200f, 300f, 320f)) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.advanceTimeBy(120)
        var before = Rect.Zero
        compose.runOnIdle {
            before = motion.drawnFrames().getValue("sample").bounds
            motion.begin(ArtistImageLayout.Medium)
            assertEquals(before, motion.drawnFrames().getValue("sample").bounds)
            destination(motion, Rect(100f, 40f, 140f, 80f), ArtistImageLayout.Medium)
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("artistLayoutOverlay").assertExists()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("artistLayoutOverlay").assertDoesNotExist()
    }

    @Test fun destinationMustActuallyBindBeforeMotionBeginsAndCancelReleasesPresentation() {
        compose.mainClock.autoAdvance = false
        val motion = show(Rect(20f, 40f, 80f, 100f))
        compose.runOnIdle { motion.begin(ArtistImageLayout.Large) }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("artistLayoutOverlay").assert(SemanticsMatcher.expectValue(DMotionFraction, 0f))
        compose.runOnIdle { motion.cancel() }
        compose.onNodeWithTag("artistLayoutOverlay").assertDoesNotExist()
        compose.runOnIdle { assertTrue(motion.drawnFrames().isEmpty()) }
    }

    @Test fun partiallyClippedSourceKeepsItsFullGeometry() {
        val motion = show(Rect(20f, -20f, 80f, 40f))
        compose.runOnIdle {
            motion.begin(ArtistImageLayout.Large)
            assertEquals(60f, motion.drawnFrames().getValue("sample").bounds.height)
            motion.cancel()
        }
    }

    @Test fun skippedDestinationCannotStartTheNewerRequestedLayout() {
        compose.mainClock.autoAdvance = false
        val motion = show(Rect(20f, 40f, 80f, 100f))
        compose.runOnIdle {
            motion.begin(ArtistImageLayout.Large)
            motion.begin(ArtistImageLayout.Medium)
            destination(motion, Rect(180f, 200f, 300f, 320f))
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("artistLayoutOverlay").assert(SemanticsMatcher.expectValue(DMotionFraction, 0f))
        compose.runOnIdle { destination(motion, Rect(100f, 40f, 140f, 80f), ArtistImageLayout.Medium) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("artistLayoutOverlay").assertDoesNotExist()
    }

    @Test fun disposingOldLayoutDoesNotRemoveTheNewThumbnailRegistration() {
        val motion = show(Rect(20f, 40f, 80f, 100f))
        compose.runOnIdle {
            val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()
            val old = ArtistLayoutMotionEntry(ArtistImageLayout.List, Rect(20f, 40f, 80f, 100f), image, 18f, false)
            val replacement = ArtistLayoutMotionEntry(ArtistImageLayout.Large, Rect(180f, 200f, 300f, 320f), image, 0f, false)
            motion.register("sample", old)
            motion.register("sample", replacement)
            motion.remove("sample", old)
            motion.begin(ArtistImageLayout.Medium)
            assertEquals(replacement.bounds, motion.drawnFrames().getValue("sample").bounds)
            motion.cancel()
        }
    }

    @Test fun darkPictureDoesNotFlashGrayAtTheTailHandoff() {
        compose.mainClock.autoAdvance = false
        val target = Rect(180f, 200f, 300f, 320f)
        val motion = show(Rect(20f, 40f, 80f, 100f), android.graphics.Color.BLACK)
        compose.runOnIdle {
            motion.begin(ArtistImageLayout.Large)
            val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }.asImageBitmap()
            val entry = ArtistLayoutMotionEntry(ArtistImageLayout.Large, target, image, 0f, false)
            motion.register("sample", entry)
            motion.position("sample", entry)
            motion.bind(ArtistImageLayout.Large)
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.advanceTimeBy(280)
        val overlay = compose.onNodeWithTag("artistLayoutOverlay")
        val fraction = overlay.fetchSemanticsNode().config[DMotionFraction]
        assertTrue(fraction > .8f && fraction < 1f)
        val center = androidx.compose.ui.geometry.lerp(Rect(20f, 40f, 80f, 100f), target, fraction).center - overlay.fetchSemanticsNode().boundsInRoot.topLeft
        val pixel = overlay.captureToImage().toPixelMap()[center.x.toInt(), center.y.toInt()]
        assertTrue("An opaque black image stays black through handoff", pixel.red < .03f && pixel.green < .03f && pixel.blue < .03f && pixel.alpha > .98f)
        compose.mainClock.advanceTimeBy(600)
    }

    @Test fun lateRapidSwitchKeepsTheCarriedPictureInsteadOfRevertingDetail() {
        compose.mainClock.autoAdvance = false
        val motion = show(Rect(20f, 40f, 80f, 100f))
        var carried: androidx.compose.ui.graphics.ImageBitmap? = null
        compose.runOnIdle {
            motion.begin(ArtistImageLayout.Large)
            carried = motion.drawnFrames().getValue("sample").image
            val decoded = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }.asImageBitmap()
            val target = ArtistLayoutMotionEntry(ArtistImageLayout.Large, Rect(180f, 200f, 300f, 320f), decoded, 0f, false)
            motion.register("sample", target)
            motion.position("sample", target)
            motion.bind(ArtistImageLayout.Large)
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.advanceTimeBy(280)
        val fraction = compose.onNodeWithTag("artistLayoutOverlay").fetchSemanticsNode().config[DMotionFraction]
        assertTrue(fraction > .8f && fraction < 1f)
        compose.runOnIdle {
            val frame = motion.drawnFrames().getValue("sample")
            assertSame(carried, frame.image)
            motion.begin(ArtistImageLayout.Medium)
            assertSame(carried, motion.drawnFrames().getValue("sample").image)
            assertEquals(frame.bounds, motion.drawnFrames().getValue("sample").bounds)
            motion.cancel()
        }
    }

    @Test fun thirdRapidSwitchPreservesTheOpacityOfAnImageReturningFromOffscreen() {
        compose.mainClock.autoAdvance = false
        val motion = show(Rect(20f, 40f, 80f, 100f))
        compose.runOnIdle {
            motion.begin(ArtistImageLayout.Large)
            motion.position("other", ArtistLayoutMotionEntry(ArtistImageLayout.Large, Rect(180f, 200f, 300f, 320f), radius = 0f, video = false))
            motion.bind(ArtistImageLayout.Large)
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.advanceTimeBy(120)
        compose.runOnIdle {
            motion.begin(ArtistImageLayout.Medium)
            destination(motion, Rect(100f, 40f, 140f, 80f), ArtistImageLayout.Medium)
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.mainClock.advanceTimeBy(120)
        compose.runOnIdle {
            val before = motion.drawnFrames().getValue("sample").alpha
            assertTrue(before > 0f && before < 1f)
            motion.begin(ArtistImageLayout.List)
            assertEquals(before, motion.drawnFrames().getValue("sample").alpha, .001f)
            motion.cancel()
        }
    }

    private fun show(bounds: Rect, color: Int = android.graphics.Color.RED): ArtistLayoutMotionState {
        val motion = ArtistLayoutMotionState()
        val bitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        motion.register("sample", ArtistLayoutMotionEntry(ArtistImageLayout.List, bounds, bitmap.asImageBitmap(), 18f, false))
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            Box(Modifier.fillMaxSize()) { ArtistLayoutMotionOverlay(motion, Modifier.fillMaxSize()) }
        } }
        compose.runOnIdle { motion.viewport = Rect(Offset.Zero, androidx.compose.ui.geometry.Size(1000f, 1000f)) }
        return motion
    }

    private fun destination(motion: ArtistLayoutMotionState, bounds: Rect, layout: ArtistImageLayout = ArtistImageLayout.Large) {
        motion.position("sample", ArtistLayoutMotionEntry(layout, bounds, null, 0f, false))
        motion.bind(layout)
    }
}
