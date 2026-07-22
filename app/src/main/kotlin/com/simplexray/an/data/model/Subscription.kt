package com.simplexray.an.data.model

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long,
    val files: List<String>
)
