package com.simplexray.an.feature.dashboard

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.simplexray.an.core.geodata.GeodataProgress
import com.simplexray.an.feature.dashboard.model.ConnectionPreparation
import com.simplexray.an.feature.dashboard.ui.DashboardConnectionControl
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h900dp", application = Application::class)
class DashboardConnectionControlTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun geodataDownloadShowsReadableSizeAndProgressRing() {
        var preparation by
            mutableStateOf<ConnectionPreparation?>(
                ConnectionPreparation(
                    "Загрузка баз",
                    GeodataProgress("geosite.dat", 4_404_019L, 19_608_371L),
                )
            )
        compose.setContent {
            MaterialTheme { DashboardConnectionControl(false, false, preparation, {}, Modifier) }
        }
        val ring = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        compose.onNodeWithText("Загрузка баз").assertIsDisplayed()
        compose.onNodeWithText("geosite.dat · 4,2 из 18,7 МБ").assertIsDisplayed()
        compose.onNode(ring).assertExists()
        compose.onAllNodesWithText("байт", substring = true).assertCountEquals(0)

        preparation = ConnectionPreparation("Загрузка баз", GeodataProgress("geoip.dat", 0L, -1L))
        compose.onNodeWithText("geoip.dat").assertIsDisplayed()
        compose.onNode(ring).assertExists()

        preparation = ConnectionPreparation("Подключение…")
        compose.onNodeWithText("Подключение…").assertIsDisplayed()
        compose.onNodeWithText("geoip.dat", substring = true).assertDoesNotExist()
        compose.onNode(ring).assertDoesNotExist()
    }
}
