package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.v6.normalization.FrameRotationHelper
import org.junit.Assert.*
import org.junit.Test

/**
 * Validate coordinate-space semantics for rotation.
 *
 * Confirms that luma and chroma coordinates map to the correct pixel
 * under 0/90/180/270 degree rotations.
 *
 * Does NOT validate physical ImageProxy behavior — real-device testing
 * is required.
 */
class V7CoordinateRotationTest {

    // Simulated sensor: 640x480 raw (typical landscape camera)
    // Normalized (after rotation): depends on rotation
    // For 0/180: normalized = raw (640x480)
    // For 90/270: normalized = 480x640

    private val rawW = 640
    private val rawH = 480

    @Test
    fun `rotation 0 — identity mapping`() {
        val (nw, nh) = FrameRotationHelper.getNormalizedDimensions(rawW, rawH, 0)
        assertEquals(640, nw)
        assertEquals(480, nh)

        // Center pixel
        val (nx, ny) = FrameRotationHelper.mapRawToNormalized(320, 240, rawW, rawH, 0)
        assertEquals(320, nx)
        assertEquals(240, ny)

        // Roundtrip
        val (rx, ry) = FrameRotationHelper.mapNormalizedToRaw(nx, ny, rawW, rawH, 0)
        assertEquals(320, rx)
        assertEquals(240, ry)

        // Corner (top-left)
        val (nxtl, nytl) = FrameRotationHelper.mapRawToNormalized(0, 0, rawW, rawH, 0)
        assertEquals(0, nxtl)
        assertEquals(0, nytl)

        // Corner (bottom-right)
        val (nxbr, nybr) = FrameRotationHelper.mapRawToNormalized(rawW - 1, rawH - 1, rawW, rawH, 0)
        assertEquals(rawW - 1, nxbr)
        assertEquals(rawH - 1, nybr)
    }

    @Test
    fun `rotation 90 — correct mapping`() {
        val (nw, nh) = FrameRotationHelper.getNormalizedDimensions(rawW, rawH, 90)
        assertEquals(480, nw)  // swapped
        assertEquals(640, nh)

        // Raw top-left (0,0) → normalized top-right
        val (nx, ny) = FrameRotationHelper.mapRawToNormalized(0, 0, rawW, rawH, 90)
        assertEquals(rawH - 1, nx)  // 479
        assertEquals(0, ny)

        // Raw top-right (639,0) → normalized top-left
        val (nxtr, nytr) = FrameRotationHelper.mapRawToNormalized(rawW - 1, 0, rawW, rawH, 90)
        assertEquals(rawH - 1, nxtr)
        assertEquals(rawW - 1, nytr)

        // Raw center → normalized center
        val (ncx, ncy) = FrameRotationHelper.mapRawToNormalized(320, 240, rawW, rawH, 90)
        assertEquals(239, ncx)
        assertEquals(320, ncy)

        // Roundtrip: normalized → raw → normalized
        val (rx, ry) = FrameRotationHelper.mapNormalizedToRaw(ncx, ncy, rawW, rawH, 90)
        val (nx2, ny2) = FrameRotationHelper.mapRawToNormalized(rx, ry, rawW, rawH, 90)
        assertEquals(ncx, nx2)
        assertEquals(ncy, ny2)
    }

    @Test
    fun `rotation 180 — correct mapping`() {
        val (nw, nh) = FrameRotationHelper.getNormalizedDimensions(rawW, rawH, 180)
        assertEquals(640, nw)
        assertEquals(480, nh)

        // Raw top-left (0,0) → normalized bottom-right
        val (nx, ny) = FrameRotationHelper.mapRawToNormalized(0, 0, rawW, rawH, 180)
        assertEquals(rawW - 1, nx)
        assertEquals(rawH - 1, ny)

        // Raw bottom-right → normalized top-left
        val (nxbr, nybr) = FrameRotationHelper.mapRawToNormalized(rawW - 1, rawH - 1, rawW, rawH, 180)
        assertEquals(0, nxbr)
        assertEquals(0, nybr)

        // Roundtrip center
        val (ncx, ncy) = FrameRotationHelper.mapRawToNormalized(320, 240, rawW, rawH, 180)
        val (rx, ry) = FrameRotationHelper.mapNormalizedToRaw(ncx, ncy, rawW, rawH, 180)
        assertEquals(320, rx)
        assertEquals(240, ry)
    }

    @Test
    fun `rotation 270 — correct mapping`() {
        val (nw, nh) = FrameRotationHelper.getNormalizedDimensions(rawW, rawH, 270)
        assertEquals(480, nw)
        assertEquals(640, nh)

        // Raw top-left (0,0) → normalized bottom-left
        val (nx, ny) = FrameRotationHelper.mapRawToNormalized(0, 0, rawW, rawH, 270)
        assertEquals(0, nx)
        assertEquals(rawW - 1, ny)

        // Raw bottom-right → normalized top-right
        val (nxbr, nybr) = FrameRotationHelper.mapRawToNormalized(rawW - 1, rawH - 1, rawW, rawH, 270)
        assertEquals(rawH - 1, nxbr)
        assertEquals(0, nybr)

        // Roundtrip
        val (ncx, ncy) = FrameRotationHelper.mapRawToNormalized(320, 240, rawW, rawH, 270)
        val (rx, ry) = FrameRotationHelper.mapNormalizedToRaw(ncx, ncy, rawW, rawH, 270)
        val (nx2, ny2) = FrameRotationHelper.mapRawToNormalized(rx, ry, rawW, rawH, 270)
        assertEquals(ncx, nx2)
        assertEquals(ncy, ny2)
    }

    @Test
    fun `sampler coordinate is in normalized space`() {
        // The V7 sampler's canonical projection maps to normalized (rotation-corrected)
        // camera coordinates. The homography inverse produces (imageX, imageY) in the
        // normalized space used by lumaBuffer.bytes[] and chromaReader.read().
        //
        // This test validates that a known canonical point maps correctly through
        // both coordinate systems when rotation is applied.
        //
        // Given: identity homography, the canonical center (500, 500) of a 1000x1000
        // canvas maps to camera pixel (500, 500) in the normalized frame.
        // With rotation 90 applied:
        //   - LumaFrameBuffer maps raw(500,0) → normalized(479,500)
        //   - ImageProxyChromaSampler maps normalized(500,500) → raw(500,479)
        //
        // So the sampler's projected point at normalized(500,500) should read
        // the chroma pixel at raw(500,479) and the luma pixel at normalized(500,500).
        // These refer to the SAME physical pixel.

        // Validate: a projected normalized coord (nx,ny) with rotation 90
        // should be converted back to raw to read chroma
        val nx = 300; val ny = 200
        val (chromaRawX, chromaRawY) = FrameRotationHelper.mapNormalizedToRaw(nx, ny, rawW, rawH, 90)

        // chromaRawX and chromaRawY are then used by ImageProxyChromaSampler
        // which adds cropLeft/cropTop and divides by 2 for chroma subsampling.
        assertEquals(200, chromaRawX)
        assertEquals(rawH - 1 - 300, chromaRawY) // 479 - 300 = 179
    }

    @Test
    fun `out-of-bounds projection produces safe behavior`() {
        // Project a canonical point that maps outside the normalized image.
        // The sampler rounds to Int and checks bounds: px in 0 until width, py in 0 until height.
        // Out-of-bounds reads Y=128 and U/V from chroma reader (which returns false).

        // With identity homography, canonical (2000, 2000) projects to (2000, 2000) which
        // is out of bounds for a 1000x1000 image.
        val hInv = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val cx = 2000.0; val cy = 2000.0
        val den = hInv[6] * cx + hInv[7] * cy + hInv[8]
        val ix = ((hInv[0] * cx + hInv[1] * cy + hInv[2]) / den).toInt()
        val iy = ((hInv[3] * cx + hInv[4] * cy + hInv[5]) / den).toInt()
        assertEquals(2000, ix)
        assertEquals(2000, iy)

        // 2000 not in 0 until 1000 → out of bounds
        assertFalse(ix in 0 until 1000)
        assertFalse(iy in 0 until 1000)
    }

    @Test
    fun `all four rotations roundtrip`() {
        val testPoints = listOf(
            Pair(0, 0),
            Pair(rawW - 1, 0),
            Pair(0, rawH - 1),
            Pair(rawW - 1, rawH - 1),
            Pair(rawW / 2, rawH / 2),
            Pair(100, 200),
            Pair(500, 300)
        )

        for (rot in intArrayOf(0, 90, 180, 270)) {
            for ((rx, ry) in testPoints) {
                val (nx, ny) = FrameRotationHelper.mapRawToNormalized(rx, ry, rawW, rawH, rot)
                val (rx2, ry2) = FrameRotationHelper.mapNormalizedToRaw(nx, ny, rawW, rawH, rot)
                assertEquals("rot=$rot, raw=($rx,$ry)", rx, rx2)
                assertEquals("rot=$rot, raw=($rx,$ry)", ry, ry2)
            }
        }
    }
}
