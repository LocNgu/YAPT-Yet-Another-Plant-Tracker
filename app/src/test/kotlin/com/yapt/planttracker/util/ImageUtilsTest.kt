package com.yapt.planttracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUtilsTest {

    @Test
    fun calculateInSampleSize_smallImage_decodesAtFullSize() {
        assertEquals(1, ImageUtils.calculateInSampleSize(1280, 720, 1920))
    }

    @Test
    fun calculateInSampleSize_largeImage_usesLargestSafePowerOfTwo() {
        assertEquals(2, ImageUtils.calculateInSampleSize(6000, 4000, 1920))
        assertEquals(4, ImageUtils.calculateInSampleSize(8000, 6000, 1920))
    }
}
