package com.superqr.android.phase1

import com.superqr.android.vision.v6.classification.ChromaPixelReader
import com.superqr.android.vision.v7_capacity_lab.ExternalQrDecodeResult
import com.superqr.android.vision.v7_capacity_lab.ExternalQrFrameDecoder
import com.superqr.android.vision.v7_capacity_lab.ExternalQrSymbol
import com.superqr.android.vision.v7_capacity_lab.V7CarrierSpec
import com.superqr.android.vision.v7_capacity_lab.V7LabRunEnvelope
import com.superqr.android.vision.v7_capacity_lab.V7LabRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

class AdvancedPhyTest {
    private fun manifest(): AdvancedPhyManifest {
        val paths = listOf(
            "src/main/assets/v7_phy_selection/advanced_phy.json",
            "app/src/main/assets/v7_phy_selection/advanced_phy.json",
        )
        val file = paths.map(::File).firstOrNull { it.exists() }
            ?: error("advanced_phy.json not found")
        return AdvancedPhyManifest.parse(file.readText())
    }

    @Test
    fun `advanced manifest freezes receiver at 30 fps and exposes color stress`() {
        val manifest = manifest()
        assertEquals(30.0, manifest.maxReceiverFps, 0.0)
        assertEquals("advanced_quad_qr_v27_l_fast20", manifest.defaultProfile)
        assertEquals(listOf(14, 15, 16, 17), manifest.profiles.map { it.id })
        assertTrue(manifest.profiles.all { it.targetFps <= 30.0 })
        val stress = manifest.profile(16)!!
        assertEquals(setOf(3, 4), stress.lanes.filterIsInstance<AdvancedLane.Grid>().map { it.bitsPerCell }.toSet())
    }

    @Test
    fun `quad qr receiver aggregates four independent lanes in one camera frame`() {
        val manifest = manifest()
        val profile = manifest.profile(14)!!
        val symbols = profile.lanes.filterIsInstance<AdvancedLane.Qr>().map { lane ->
            ExternalQrSymbol(payload = payload(profile, lane, frameIndex = 7, runToken = 0x1234, seed = manifest.seed))
        }
        val decoder = FakeFrame(symbols)
        val engine = AdvancedPhyEngine(manifest, V7CarrierSpec())
        try {
            val result = engine.analyze(
                luma = ByteArray(1280 * 960),
                width = 1280,
                height = 960,
                chromaReader = decoder,
                arrivalNs = System.nanoTime(),
            )
            assertNotNull(result)
            assertEquals(14, result!!.profile.id)
            assertEquals(4, result.decodedQrLanes)
            assertEquals(4, result.observations.size)
            assertEquals(setOf(0, 1, 2, 3), result.observations.map { it.laneId }.toSet())
            assertTrue(result.observations.all { it.postFecValid && it.usefulBytes == 1431 })
        } finally {
            engine.close()
        }
    }

    @Test
    fun `rolling transition never aggregates different frame indexes`() {
        val manifest = manifest()
        val profile = manifest.profile(14)!!
        val qr = profile.lanes.filterIsInstance<AdvancedLane.Qr>()
        val symbols = listOf(
            ExternalQrSymbol(payload(profile, qr[0], 7, 0x2222, manifest.seed)),
            ExternalQrSymbol(payload(profile, qr[1], 7, 0x2222, manifest.seed)),
            ExternalQrSymbol(payload(profile, qr[2], 7, 0x2222, manifest.seed)),
            ExternalQrSymbol(payload(profile, qr[3], 8, 0x2222, manifest.seed)),
        )
        val engine = AdvancedPhyEngine(manifest, V7CarrierSpec())
        try {
            val result = engine.analyze(
                ByteArray(1280 * 960), 1280, 960, FakeFrame(symbols), System.nanoTime()
            )!!
            assertEquals(7, result.envelope.frameIndex)
            assertEquals(3, result.decodedQrLanes)
            assertEquals(3, result.observations.size)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `advanced recorder counts innovation by lane and frame`() {
        val manifest = manifest()
        val profile = manifest.profile(14)!!
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 14, 0x4567, 5, 256, 3)
        val observations = (0 until 4).map { lane ->
            AdvancedLaneObservation(
                laneId = lane,
                kind = "qr",
                frameIndex = 5,
                usefulBytes = 1431,
                observedBits = 1465 * 8,
                bitErrors = 0,
                erasedBits = 0,
                frameValid = true,
                postFecValid = true,
            )
        }
        val result = AdvancedPhyResult(profile, envelope, observations, 4, null, 5.0)
        val recorder = AdvancedObservationRecorder()
        val first = recorder.record("campaign", result, 1_000_000_000L, 30.0, 1280, 960)
        assertEquals(4, first.uniqueLaneFrames)
        assertEquals(5724L, first.innovativeBytes)
        val duplicate = recorder.record("campaign", result, 1_100_000_000L, 30.0, 1280, 960)
        assertEquals(4, duplicate.uniqueLaneFrames)
        assertEquals(5724L, duplicate.innovativeBytes)
    }

    private class FakeFrame(private val symbols: List<ExternalQrSymbol>) :
        ChromaPixelReader, ExternalQrFrameDecoder {
        override fun read(imageX: Double, imageY: Double, destination: IntArray): Boolean = false
        override fun decodeQr(): ExternalQrDecodeResult = ExternalQrDecodeResult(
            payload = symbols.firstOrNull()?.payload ?: ByteArray(0),
            elapsedMs = 1.0,
            resultCount = symbols.size,
            source = "TEST",
            symbols = symbols,
        )
    }

    private fun payload(
        profile: AdvancedProfile,
        lane: AdvancedLane.Qr,
        frameIndex: Int,
        runToken: Int,
        seed: Int,
    ): ByteArray {
        val body = ByteArray(lane.frameBytes - 4)
        body[0] = 'S'.code.toByte(); body[1] = 'Q'.code.toByte(); body[2] = 'A'.code.toByte(); body[3] = '1'.code.toByte()
        body[4] = lane.version.toByte()
        body[5] = lane.eccId.toByte()
        body[6] = lane.laneId.toByte()
        body[7] = profile.laneCount.toByte()
        put32(body, 8, frameIndex)
        put32(body, 12, seed)
        put16(body, 16, lane.frameBytes)
        val envelope = V7LabRunEnvelope(
            state = V7LabRunState.RUNNING,
            profileId = profile.id,
            runToken = runToken,
            frameIndex = frameIndex,
            frameCount = 256,
            dwellEpochs = 3,
        ).encode()
        envelope.copyInto(body, 18)
        body[28] = 1
        body[29] = 0
        val crc = CRC32().apply { update(body) }.value
        return body + byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            ((crc ushr 16) and 0xFF).toByte(),
            ((crc ushr 24) and 0xFF).toByte(),
        )
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
