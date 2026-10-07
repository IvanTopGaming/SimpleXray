package com.simplexray.an.ui.navigation

import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.ui.theme.SimpleXrayTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppMotionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun appContentFinishesMotionImmediatelyWithoutRipples() {
        val finished = CountDownLatch(1)
        var scale: Float? = null
        var rippleDisabled = false
        var overscrollDisabled = false
        var finalValue = 0f
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                SimpleXrayTheme(false) {
                    rippleDisabled = LocalRippleConfiguration.current == null
                    overscrollDisabled = LocalOverscrollFactory.current == null
                    LaunchedEffect(Unit) {
                        scale = coroutineContext[MotionDurationScale]?.scaleFactor
                        val value = Animatable(0f)
                        value.animateTo(1f, tween(60_000))
                        finalValue = value.value
                        finished.countDown()
                    }
                }
            }
        }
        assertTrue(
            "App animations must not wait for their duration",
            finished.await(5, TimeUnit.SECONDS),
        )
        assertEquals(0f, scale ?: -1f, 0f)
        assertEquals(1f, finalValue, 0f)
        assertTrue("App controls must not create animated ripples", rippleDisabled)
        assertTrue("Scrolling must not create animated overscroll effects", overscrollDisabled)
    }
}
