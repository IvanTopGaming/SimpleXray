package com.simplexray.an.ui.navigation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.simplexray.an.activity.MainActivity
import com.simplexray.an.ui.components.LoadingSpinner
import com.simplexray.an.ui.theme.SimpleXrayTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppLoadingIndicatorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun loadingSpinnerRemainsVisibleAndMovesWithAppTransitionsDisabled() {
        compose.mainClock.autoAdvance = false
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                SimpleXrayTheme(false) {
                    Box(Modifier.size(48.dp)) { LoadingSpinner() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(32)
        val node = compose.onNodeWithTag("loading-spinner").assertExists()
        val before = node.captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(320)
        val after = node.captureToImage().toPixelMap()
        assertTrue(
            (0 until before.width).any { x ->
                (0 until before.height).any { y -> before[x, y] != after[x, y] }
            }
        )
    }
}
