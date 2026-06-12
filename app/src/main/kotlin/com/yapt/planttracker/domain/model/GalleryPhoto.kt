package com.yapt.planttracker.domain.model

sealed interface GalleryPhotoSource {
    data class FromPlant(val photo: PlantPhoto) : GalleryPhotoSource
    data class FromCareLog(val logId: Long) : GalleryPhotoSource
}

data class GalleryPhoto(
    val uri: String,
    val timestamp: Long,
    val source: GalleryPhotoSource
)
