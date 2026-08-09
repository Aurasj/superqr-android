package com.superqr.android.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LumaPlanePackerTest {
    private val raw = byteArrayOf(1, 2, 3, 4, 5, 6) // 3x2 rows

    private fun pack(rotation: Int): ByteArray {
        val width = LumaPlanePacker.normalizedWidth(3, 2, rotation)
        val height = LumaPlanePacker.normalizedHeight(3, 2, rotation)
        return ByteArray(width * height).also { output ->
            assertTrue(
                LumaPlanePacker.pack(
                    ByteBuffer.wrap(raw), 0, 0, 3, 2, 3, 1, rotation,
                    ByteArray(3), output,
                )
            )
        }
    }

    @Test fun `packs all rotations without coordinate objects`() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), pack(0))
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), pack(90))
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), pack(180))
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), pack(270))
    }

    @Test fun `honors pixel and row stride`() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 99, 2, 99, 0, 3, 99, 4, 99, 0))
        val output = ByteArray(4)
        assertTrue(LumaPlanePacker.pack(source, 0, 0, 2, 2, 5, 2, 0, ByteArray(4), output))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output)
    }
}
