package com.simplexray.an.feature.apps.state

import android.graphics.drawable.Drawable

data class Package(
    var selected: Boolean,
    val label: String,
    val icon: Drawable,
    val packageName: String,
    val isSystemApp: Boolean,
)
