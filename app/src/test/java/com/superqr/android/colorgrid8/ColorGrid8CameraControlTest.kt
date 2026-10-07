package com.superqr.android.colorgrid8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorGrid8CameraControlTest {
    @Test fun disablesNoiseReductionOnlyWhenAdvertised() {
        assertEquals(0, ColorGrid8CameraControl.opticalNoiseReductionMode(true, intArrayOf(0, 1, 2)))
        assertNull(ColorGrid8CameraControl.opticalNoiseReductionMode(false, intArrayOf(0, 1)))
        assertNull(ColorGrid8CameraControl.opticalNoiseReductionMode(true, intArrayOf(1, 2)))
        assertNull(ColorGrid8CameraControl.opticalNoiseReductionMode(true, null))
        assertNull(ColorGrid8CameraControl.opticalNoiseReductionMode(true, intArrayOf()))
    }
}
