package com.yapt.planttracker.domain.model

data class PlantPhoto(
    val id: Long = 0,
    val plantId: Long,
    val uri: String,
    val capturedAt: Long
)
