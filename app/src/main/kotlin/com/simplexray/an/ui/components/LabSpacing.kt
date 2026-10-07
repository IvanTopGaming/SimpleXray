package com.simplexray.an.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun labHorizontalPadding(): Dp =
    if (LocalConfiguration.current.screenWidthDp <= 370) 24.dp else 28.dp

@Composable fun labGap(): Dp = if (LocalConfiguration.current.screenWidthDp <= 370) 16.dp else 20.dp
