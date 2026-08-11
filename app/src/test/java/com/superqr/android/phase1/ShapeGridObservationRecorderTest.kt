package com.superqr.android.phase1

import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeGridObservationRecorderTest {
    private val profile = ShapeGridProfile(
        id = 19,
        name = "shapegrid_c4_s16_136x100_fast20",
        role = "DEFAULT_FAST",
        targetFps = 20.0,
        gridCols = 136,
        gridRows = 100,
        blockCols = 34,
        blockRows = 50,
        blockCells = 1700,
        rsCodewordsPerBlock = 5,
        encodedBytesPerBlock = 1275,
        activeSymbolsPerBlock = 1700,
        usefulBytesPerBlock = 1005,
        usefulBytesPerEpoch = 8040,
        theoreticalMbps = 1.2864,
        canonicalGridBbox = doubleArrayOf(92.0, 200.0, 908.0, 800.0),
    )

    @Test
    fun `innovation is counted once per frame and block`() {
        val recorder = ShapeGridObservationRecorder()
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 19, 0x4321, 4, 32, 3)
        val result = result(envelope, validBlocks = 0 until 8)

        val first = recorder.record("campaign", result, 1_000_000_000L, 24.0, 1280, 960)
        assertEquals(8, first.uniqueBlocks)
        assertEquals(8040L, first.innovativeBytes)

        val duplicate = recorder.record("campaign", result, 1_100_000_000L, 24.0, 1280, 960)
        assertEquals(8, duplicate.uniqueBlocks)
        assertEquals(8040L, duplicate.innovativeBytes)

        val next = recorder.record(
            "campaign",
            result(envelope.copy(frameIndex = 5), validBlocks = 0 until 4),
            1_200_000_000L,
            24.0,
            1280,
            960,
        )
        assertEquals(12, next.uniqueBlocks)
        assertEquals(8040L + 4L * 1005L, next.innovativeBytes)
    }

    @Test
    fun `ready blocks never contribute goodput`() {
        val recorder = ShapeGridObservationRecorder()
        val ready = V7LabRunEnvelope(V7LabRunState.READY, 19, 0x2222, 0, 16, 3)
        val snapshot = recorder.record(
            "campaign", result(ready, 0 until 8), 1_000_000_000L, 20.0, 1280, 960
        )
        assertEquals(0, snapshot.uniqueBlocks)
        assertEquals(0L, snapshot.innovativeBytes)
        assertEquals(0.0, snapshot.goodputMbps, 0.0)
    }

    @Test
    fun `partial epoch contributes only recovered blocks`() {
        val recorder = ShapeGridObservationRecorder()
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 19, 0xAAAA, 0, 1, 3)
        val snapshot = recorder.record(
            "campaign", result(envelope, listOf(0, 2, 4, 6, 7)), 1_000_000_000L, 20.0, 1280, 960
        )
        assertEquals(5, snapshot.uniqueBlocks)
        assertEquals(5L * 1005L, snapshot.innovativeBytes)
        assertEquals(5.0 / 8.0, snapshot.blockYield, 1e-12)
        assertTrue(snapshot.symbolErasures > 0)
    }

    private fun result(
        envelope: V7LabRunEnvelope,
        validBlocks: Iterable<Int>,
    ): ShapeGridResult {
        val valid = validBlocks.toSet()
        val observations = (0 until 8).map { blockId ->
            val ok = blockId in valid
            ShapeGridBlockObservation(
                blockId = blockId,
                frameIndex = envelope.frameIndex,
                usefulBytes = if (ok) 1005 else 0,
                symbolErasures = if (ok) blockId else 25,
                shapeSymbolErrors = if (ok) blockId / 2 else 0,
                colorSymbolErrors = if (ok) blockId / 3 else 0,
                rsErrors = if (ok) blockId / 2 else 0,
                rsErasures = if (ok) blockId else 0,
                postFecValid = ok,
                failure = if (ok) null else "TEST_BLOCK_LOST",
            )
        }
        return ShapeGridResult(
            profile = profile,
            envelope = envelope,
            observations = observations,
            recoveredBlocks = valid.size,
            projectedTilePitchPx = 5.5,
            shapegridTotalMs = 22.0,
            carrierSource = "TEST",
            failure = if (valid.isEmpty()) "TEST_NONE" else null,
        )
    }
}
