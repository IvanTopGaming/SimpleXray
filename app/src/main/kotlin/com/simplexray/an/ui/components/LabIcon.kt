package com.simplexray.an.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

@Composable
fun LabIcon(
    name: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val vector =
        remember(name) {
            val paths =
                when (name) {
                    "home" ->
                        listOf(
                            "M3 10 L12 3 L21 10 L21 20 Q21 21 20 21 L15 21 L15 14 L9 14 L9 21 L4 21 Q3 21 3 20 Z"
                        )
                    "servers" ->
                        listOf(
                            "M5 3 H19 Q21 3 21 5 V8 Q21 10 19 10 H5 Q3 10 3 8 V5 Q3 3 5 3 Z M5 14 H19 Q21 14 21 16 V19 Q21 21 19 21 H5 Q3 21 3 19 V16 Q3 14 5 14 Z",
                            "M7 6.5 H7.01 M7 17.5 H7.01 M12 6.5 H17 M12 17.5 H17",
                        )
                    "subs" ->
                        listOf(
                            "M9 15 A5 5 0 0 0 16 15 L20 11 A5 5 0 0 0 13 4 L11 6 M15 9 A5 5 0 0 0 8 9 L4 13 A5 5 0 0 0 11 20 L13 18"
                        )
                    "settings" ->
                        listOf(
                            "M4 6 H7 M11 6 H20 M4 12 H13 M17 12 H20 M4 18 H7 M11 18 H20 M11 6 A2 2 0 1 1 7 6 A2 2 0 1 1 11 6 M17 12 A2 2 0 1 1 13 12 A2 2 0 1 1 17 12 M11 18 A2 2 0 1 1 7 18 A2 2 0 1 1 11 18"
                        )
                    "arrow" -> listOf("M9 5 L16 12 L9 19")
                    "route" ->
                        listOf(
                            "M8 5 A2 2 0 1 1 4 5 A2 2 0 1 1 8 5 M20 19 A2 2 0 1 1 16 19 A2 2 0 1 1 20 19 M6 7 V15 A4 4 0 0 0 10 19 H16 M9 5 H15 A3 3 0 0 1 15 11 H9"
                        )
                    "power" -> listOf("M12 2 V12 M6 5 A9 9 0 1 0 18 5")
                    "info" ->
                        listOf("M21 12 A9 9 0 1 1 3 12 A9 9 0 1 1 21 12 M12 11 V17 M12 7 H12.01")
                    "edit" -> listOf("M15 4 L20 9 M4 20 L9 19 L21 7 A2 2 0 0 0 16 2 L4 14 Z")
                    "add" -> listOf("M12 5 V19 M5 12 H19")
                    "close" -> listOf("M6 6 L18 18 M18 6 L6 18")
                    "check" -> listOf("M5 12 L9 16 L19 6")
                    "save" ->
                        listOf(
                            "M5 3 H17 L21 7 V20 Q21 21 20 21 H4 Q3 21 3 20 V4 Q3 3 5 3 Z M7 3 V9 H16 V3 M7 21 V14 H17 V21"
                        )
                    "delete" ->
                        listOf("M3 6 H21 M9 6 V3 H15 V6 M5 6 L6 21 H18 L19 6 M10 10 V17 M14 10 V17")
                    "reset" -> listOf("M3 3 V9 H9 M3 9 A9 9 0 1 1 4 18 M12 7 V12 L15 14")
                    "refresh" ->
                        listOf(
                            "M3 3 V9 H9 M3 9 A9 9 0 0 1 18 5 M21 21 V15 H15 M21 15 A9 9 0 0 1 6 19"
                        )
                    "send" -> listOf("M22 2 L9 15 M22 2 L15 22 L9 15 L2 9 Z")
                    "activity" -> listOf("M3 12 H7 L10 4 L14 20 L17 12 H21")
                    "select-all" -> listOf("M4 4 H20 V20 H4 Z M7 12 L10 15 L17 8")
                    "shuffle" ->
                        listOf(
                            "M3 5 H6 L18 19 H21 M17 15 L21 19 L17 23 M3 19 H6 L18 5 H21 M17 1 L21 5 L17 9"
                        )
                    "apps" ->
                        listOf(
                            "M3 3 H10 V10 H3 Z M14 3 H21 V10 H14 Z M3 14 H10 V21 H3 Z M14 14 H21 V21 H14 Z"
                        )
                    "globe" ->
                        listOf(
                            "M21 12 A9 9 0 1 1 3 12 A9 9 0 1 1 21 12 M3 12 H21 M12 3 C6 8 6 16 12 21 C18 16 18 8 12 3"
                        )
                    "sort" -> listOf("M4 5 H20 M4 12 H15 M4 19 H10")
                    "qr" ->
                        listOf(
                            "M3 3 H10 V10 H3 Z M14 3 H21 V10 H14 Z M3 14 H10 V21 H3 Z M14 14 H17 V17 H21 V21 H14 Z M7 7 H7.01 M18 7 H18.01 M7 18 H7.01"
                        )
                    "sun" ->
                        listOf(
                            "M16 12 A4 4 0 1 1 8 12 A4 4 0 1 1 16 12 M12 2 V4 M12 20 V22 M2 12 H4 M20 12 H22 M5 5 L6 6 M18 18 L19 19 M5 19 L6 18 M18 6 L19 5"
                        )
                    "moon" -> listOf("M20 15 A9 9 0 1 1 9 4 A7 7 0 0 0 20 15 Z")
                    "download",
                    "import" -> listOf("M12 3 V15 M7 10 L12 15 L17 10 M4 16 V21 H20 V16")
                    "export" -> listOf("M12 15 V3 M7 8 L12 3 L17 8 M4 16 V21 H20 V16")
                    "clipboard" ->
                        listOf(
                            "M9 4 H6 Q4 4 4 6 V20 Q4 22 6 22 H18 Q20 22 20 20 V6 Q20 4 18 4 H15 M9 2 H15 V6 H9 Z M8 11 H16 M8 15 H16"
                        )
                    else -> listOf("M9 5 L16 12 L9 19")
                }
            ImageVector.Builder(name, 21.dp, 21.dp, 24f, 24f)
                .apply {
                    paths.forEach {
                        addPath(
                            PathParser().parsePathString(it).toNodes(),
                            stroke = SolidColor(Color.Black),
                            strokeLineWidth = 1.6f,
                            strokeLineCap = StrokeCap.Round,
                            strokeLineJoin = StrokeJoin.Round,
                        )
                    }
                }
                .build()
        }
    Icon(
        vector,
        contentDescription,
        modifier.size(21.dp),
        tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
    )
}
