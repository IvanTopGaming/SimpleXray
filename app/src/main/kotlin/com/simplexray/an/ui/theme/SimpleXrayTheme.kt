package com.simplexray.an.ui.theme

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplexray.an.R

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF417455),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE7EFE8),
        onPrimaryContainer = Color(0xFF294B35),
        secondaryContainer = Color(0xFFE7EFE8),
        onSecondaryContainer = Color(0xFF294B35),
        background = Color.White,
        surface = Color.White,
        onBackground = Color(0xFF292A2D),
        onSurface = Color(0xFF292A2D),
        onSurfaceVariant = Color(0xFF6F766E),
        outline = Color(0xFFADB5AA),
        outlineVariant = Color(0xFFE7E8E2),
        surfaceContainer = Color(0xFFF5F6F2),
        surfaceContainerHighest = Color(0xFFF5F6F2),
        surfaceContainerLow = Color(0xFFFAFAF7),
        surfaceContainerHigh = Color(0xFFF5F6F2),
        surfaceContainerLowest = Color.White,
        surfaceTint = Color(0xFF417455),
        error = Color(0xFFB65449),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF9AC9A8),
        onPrimary = Color(0xFF183B23),
        primaryContainer = Color(0xFF293F30),
        onPrimaryContainer = Color(0xFFC6E8CF),
        secondaryContainer = Color(0xFF293F30),
        onSecondaryContainer = Color(0xFFC6E8CF),
        background = Color(0xFF141517),
        surface = Color(0xFF141517),
        onBackground = Color(0xFFECEEE8),
        onSurface = Color(0xFFECEEE8),
        onSurfaceVariant = Color(0xFFA4ADA2),
        outline = Color(0xFF687163),
        outlineVariant = Color(0xFF30342E),
        surfaceContainer = Color(0xFF20241F),
        surfaceContainerHighest = Color(0xFF20241F),
        surfaceContainerLow = Color(0xFF191D19),
        surfaceContainerHigh = Color(0xFF20241F),
        surfaceContainerLowest = Color(0xFF141517),
        surfaceTint = Color(0xFF9AC9A8),
        error = Color(0xFFFFAEA3),
    )

@OptIn(ExperimentalTextApi::class)
private val ReferenceFont =
    FontFamily(
        listOf(400, 500, 550, 600, 700).map { weight ->
            Font(
                R.font.adwaita_sans,
                weight = FontWeight(weight),
                variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
            )
        }
    )

private val ReferenceText =
    TextStyle(
        fontFamily = ReferenceFont,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle =
            LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleXrayTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(12.dp),
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(18.dp),
                extraLarge = RoundedCornerShape(22.dp),
            ),
        typography =
            Typography(
                titleLarge =
                    ReferenceText.copy(
                        fontSize = 22.sp,
                        lineHeight = 27.5.sp,
                        letterSpacing = (-.7).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                titleMedium =
                    ReferenceText.copy(
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                titleSmall =
                    ReferenceText.copy(
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                bodyLarge = ReferenceText.copy(fontSize = 16.sp, lineHeight = 19.2.sp),
                bodyMedium = ReferenceText.copy(fontSize = 14.sp, lineHeight = 17.sp),
                bodySmall = ReferenceText.copy(fontSize = 12.sp, lineHeight = 14.4.sp),
                labelLarge = ReferenceText.copy(fontSize = 14.sp, lineHeight = 17.sp),
                labelMedium = ReferenceText.copy(fontSize = 12.sp, lineHeight = 16.sp),
                labelSmall = ReferenceText.copy(fontSize = 12.sp, lineHeight = 14.4.sp),
            ),
        content = {
            CompositionLocalProvider(
                LocalRippleConfiguration provides null,
                LocalOverscrollFactory provides null,
                content = content,
            )
        },
    )
}
