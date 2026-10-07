package com.simplexray.an.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private object LoadingMotion : MotionDurationScale {
    override val scaleFactor = 1f
}

@Composable
fun LoadingSpinner(modifier: Modifier = Modifier) {
    val rotation = remember { Animatable(0f) }
    val color = MaterialTheme.colorScheme.primary
    LaunchedEffect(Unit) {
        withContext(LoadingMotion) {
            while (isActive) {
                rotation.animateTo(360f, tween(900, easing = LinearEasing))
                rotation.snapTo(0f)
            }
        }
    }
    Canvas(
        modifier.size(24.dp).progressSemantics().testTag("loading-spinner").semantics {
            contentDescription = "Загрузка приложений"
        }
    ) {
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        drawArc(color.copy(alpha = .18f), 0f, 360f, false, style = stroke)
        drawArc(color, rotation.value - 90f, 270f, false, style = stroke)
    }
}
