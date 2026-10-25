package com.simplexray.an.feature.dashboard.model

import com.simplexray.an.core.geodata.GeodataProgress

data class ConnectionPreparation(val message: String, val download: GeodataProgress? = null)
