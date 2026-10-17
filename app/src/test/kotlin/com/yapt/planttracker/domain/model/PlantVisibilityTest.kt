package com.yapt.planttracker.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantVisibilityTest {

    private fun plant(archivedAt: Long? = null, hiddenAt: Long? = null) =
        Plant(name = "Fern", archivedAt = archivedAt, hiddenAt = hiddenAt)

    @Test
    fun `a plant that is neither archived nor hidden is visible`() {
        assertTrue(plant().isVisible)
    }

    @Test
    fun `a hidden plant is not visible`() {
        assertFalse(plant(hiddenAt = 1L).isVisible)
    }

    @Test
    fun `an archived plant is not visible`() {
        assertFalse(plant(archivedAt = 1L).isVisible)
    }

    @Test
    fun `a plant that is both archived and hidden is not visible`() {
        assertFalse(plant(archivedAt = 1L, hiddenAt = 2L).isVisible)
    }
}
