package com.yapt.planttracker.ui.screens.plantlist

enum class SortOption { ALPHABETICAL, WATERING_DUE, FERTILIZING_DUE, RECENTLY_ADDED, BOTH_DUE, CARED_FOR_TODAY }

enum class SortDirection { ASC, DESC }

data class SortOrder(
    val option: SortOption = SortOption.ALPHABETICAL,
    val direction: SortDirection = SortDirection.ASC
)
