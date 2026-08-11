package com.superqr.android.phase1

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ShapeGridGeometryTest {
    private val payload = doubleArrayOf(70.0, 190.0, 930.0, 810.0)

    @Test
    fun `canonical bboxes stay sender independent`() {
        assertArrayEquals(
            doubleArrayOf(92.0, 260.0, 908.0, 740.0),
            ShapeGridManifest.canonicalGridBbox(136, 80, payload),
            1e-9,
        )
        assertArrayEquals(
            doubleArrayOf(92.0, 200.0, 908.0, 800.0),
            ShapeGridManifest.canonicalGridBbox(136, 100, payload),
            1e-9,
        )
        assertArrayEquals(
            doubleArrayOf(70.0, 194.4736842105263, 930.0, 805.5263157894738),
            ShapeGridManifest.canonicalGridBbox(152, 108, payload),
            1e-9,
        )
    }

    @Test
    fun `fast tiles are exactly six canonical units`() {
        val bbox = ShapeGridManifest.canonicalGridBbox(136, 100, payload)
        assertEquals(6.0, (bbox[2] - bbox[0]) / 136.0, 1e-12)
        assertEquals(6.0, (bbox[3] - bbox[1]) / 100.0, 1e-12)
    }
}
