package net.jbglenn.telltype.platform.overlay

import android.animation.ValueAnimator
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class WindowFadeTest {
    private val view = View(ApplicationProvider.getApplicationContext())

    @Before
    fun playAnimations() {
        // Robolectric plays animators at duration scale 0 unless told otherwise; the device default is 1. The setter
        // is a hidden test API of the framework.
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, 1f)
    }

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun appearingFadesInFromTransparent() {
        val fade = WindowFade(view) { true }

        fade.appear()
        assertEquals("it starts transparent", 0f, view.alpha, 0f)
        advance(WindowFade.FADE_MILLIS + 20)
        assertEquals(1f, view.alpha, 0f)
    }

    @Test
    fun fadingOutEndsWithTheCallback() {
        val fade = WindowFade(view) { true }
        var faded = false

        fade.disappear { faded = true }
        assertTrue(fade.fadingOut)
        assertFalse("not before the fade ends", faded)
        advance(WindowFade.FADE_MILLIS + 20)
        assertTrue(faded)
        assertEquals(0f, view.alpha, 0f)
    }

    @Test
    fun aShowDuringTheFadeOutReversesItAndTheWindowIsNotRemoved() {
        val fade = WindowFade(view) { true }
        var faded = false
        fade.disappear { faded = true }

        fade.reverse()
        assertFalse(fade.fadingOut)
        advance(WindowFade.FADE_MILLIS + 20)

        assertFalse("the reversed fade-out never ends in a removal", faded)
        assertEquals(1f, view.alpha, 0f)
    }

    @Test
    fun withAnimationsRemovedNothingFades() {
        val fade = WindowFade(view) { false }
        var faded = false

        fade.appear()
        assertEquals(1f, view.alpha, 0f)
        fade.disappear { faded = true }
        assertTrue("at once", faded)
        assertEquals(0f, view.alpha, 0f)
    }

    @Test
    fun cancelLeavesTheContentOpaque() {
        val fade = WindowFade(view) { true }
        fade.disappear {}
        advance(WindowFade.FADE_MILLIS / 2)

        fade.cancel()

        assertFalse(fade.fadingOut)
        assertEquals(1f, view.alpha, 0f)
    }
}
