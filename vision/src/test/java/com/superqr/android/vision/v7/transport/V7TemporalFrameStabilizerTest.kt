package com.superqr.android.vision.v7.transport

import com.superqr.android.vision.v7_capacity_lab.V7SoftClassifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V7TemporalFrameStabilizerTest {
    private val profile = V7OpticalProfiles.all.first()
    private val header = V7FrameHeader(7, 3, 9, 12, profile.id)

    @Test
    fun `fills erasure from prior observation of same frame`() {
        val stabilizer = V7TemporalFrameStabilizer()
        val first = ByteArray(profile.cellCount) { 1 }
        stabilizer.merge(header, first, 1_000L)

        val second = first.copyOf()
        second[100] = V7SoftClassifier.ERASURE_MARKER
        val merged = stabilizer.merge(header, second, 2_000L)

        assertEquals(1, merged.filledErasures)
        assertEquals(0, merged.remainingErasures)
        assertEquals(1, merged.fillOnlySymbols[100].toInt())
        assertEquals(1, merged.symbols[100].toInt())
        assertEquals(2, merged.observations)
    }

    @Test
    fun `never mixes different frame ids`() {
        val stabilizer = V7TemporalFrameStabilizer()
        val first = ByteArray(profile.cellCount) { 2 }
        stabilizer.merge(header, first, 1_000L)

        val otherHeader = header.copy(frameId = 4)
        val second = ByteArray(profile.cellCount) { V7SoftClassifier.ERASURE_MARKER }
        val merged = stabilizer.merge(otherHeader, second, 2_000L)

        assertEquals(profile.cellCount, merged.remainingErasures)
        assertEquals(0, merged.filledErasures)
    }

    @Test
    fun `strong consensus is separate from fill-only candidate`() {
        val stabilizer = V7TemporalFrameStabilizer()
        val stable = ByteArray(profile.cellCount) { 0 }
        repeat(4) { stabilizer.merge(header, stable, 1_000L + it) }
        val noisy = stable.copyOf().also { it[55] = 3 }
        val merged = stabilizer.merge(header, noisy, 2_000L)

        assertEquals(3, merged.fillOnlySymbols[55].toInt())
        assertEquals(0, merged.symbols[55].toInt())
        assertTrue(merged.overriddenConflicts >= 1)
    }

    @Test
    fun `reset clears temporal history`() {
        val stabilizer = V7TemporalFrameStabilizer()
        val symbols = ByteArray(profile.cellCount) { 1 }
        stabilizer.merge(header, symbols)
        assertEquals(1, stabilizer.trackedFrameCount())
        stabilizer.reset()
        assertEquals(0, stabilizer.trackedFrameCount())
    }

    @Test
    fun `header prefix packs independently of payload erasures`() {
        val frame = V7Transport.buildFrame(9, 2, 5, byteArrayOf(1, 2, 3), profile)
        val symbols = V7Transport.bytesToSymbols(frame, profile)
        symbols[symbols.lastIndex] = V7SoftClassifier.ERASURE_MARKER
        val prefix = V7Transport.symbolsToPrefixBytes(symbols, profile, V7Transport.HEADER_SIZE)
        val parsed = V7Transport.inspectHeader(prefix!!, profile)
        assertEquals(9, parsed.sessionId)
        assertEquals(2, parsed.frameId)
        assertEquals(5, parsed.totalFrames)
        assertArrayEquals(frame.copyOfRange(0, V7Transport.HEADER_SIZE), prefix)
    }
}
