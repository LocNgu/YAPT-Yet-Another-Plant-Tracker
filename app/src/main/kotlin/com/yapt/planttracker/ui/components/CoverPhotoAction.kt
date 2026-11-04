package com.yapt.planttracker.ui.components

/** The viewer's Set-as-cover action (#511): the plant's current cover and the callback for choosing another. */
data class CoverPhotoAction(val coverUri: String?, val onSetCover: (uri: String) -> Unit)
