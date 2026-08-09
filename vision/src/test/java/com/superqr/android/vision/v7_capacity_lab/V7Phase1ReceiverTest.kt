package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.v6.contract.V6Contract
import com.superqr.android.vision.v6.detection.V6StaticDetector
import com.superqr.android.vision.opencv.OpenCvRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.util.zip.CRC32
import java.security.MessageDigest
import org.json.JSONObject

class V7Phase1ReceiverTest {
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun qrDecoderConstructionDoesNotTouchNativeCodeBeforeRuntimeLoad() {
        V7Phase1QrDecoder().close()
    }

    @Test
    fun packagedManifestIsCanonicalPhase1Artifact() {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("v7_phy_selection/phase1_manifest.json"))
            .use { it.readBytes() }
        val normalizedBytes = String(bytes, Charsets.UTF_8).replace("\r\n", "\n").toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(normalizedBytes).joinToString("") { "%02X".format(it) }
        val json = JSONObject(String(bytes, Charsets.UTF_8))

        assertEquals("E42920B89F498FC13D6EF47126F2405BF4F761D7C7426F9DEFEAC17734CABC6C", hash)
        assertEquals("LAB_ONLY_NOT_A_V7_WIRE_CONTRACT", json.getString("status"))
        assertEquals(5, json.getJSONArray("grid_profiles").length())
        assertEquals(2, json.getJSONArray("qr_controls").length())

        val trace = json.getJSONObject("run_sync").getJSONObject("conformance_trace")
        val packets = trace.getJSONArray("packets")
        for (index in 0 until packets.length()) {
            val packet = packets.getJSONObject(index)
            val state = V7LabRunState.valueOf(packet.getString("state"))
            val envelope = V7LabRunEnvelope(
                state, trace.getInt("profile_id"), trace.getInt("run_token"),
                packet.getInt("frame_index"), trace.getInt("frame_count"), trace.getInt("dwell_epochs"),
            )
            assertEquals(packet.getString("packet_hex"), envelope.encode().joinToString("") { "%02X".format(it) })
            assertEquals(envelope, V7LabRunEnvelope.decode(envelope.encode()))
        }
    }

    @Test
    fun rectangularMonochromeFrameDecodesAgainstContinuousTruth() {
        val profile = V7Phase1GridProfile("mono_64x50_matched", 50, 64, 1, 400)
        val receiver = V7Phase1Receiver(profile)
        val frameIndex = 37
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 1, 0xBEEF, frameIndex, 256, 3)
        val luma = render(profile, receiver.expectedSymbols(frameIndex), envelope)

        val sync = V7Phase1SyncDecoder().analyze(identity, luma, 1000, 1000)
        assertEquals(envelope, sync.envelope)
        val result = receiver.analyze(identity, luma, 1000, 1000, synchronizedFrameIndex = sync.envelope?.frameIndex)

        assertEquals(frameIndex, result.frameIndex)
        assertEquals(3200, result.observedBits)
        assertEquals(0, result.bitErrors)
        assertEquals(0, result.erasedBits)
        assertTrue(result.frameValid)
        assertTrue(result.postFecValid)
    }

    @Test
    fun duplicatedSyncRejectsRollingShutterTransition() {
        val profile = V7Phase1GridProfile("mono_64x50_matched", 50, 64, 1, 400)
        val receiver = V7Phase1Receiver(profile)
        val frameIndex = 173
        val expected = receiver.expectedSymbols(frameIndex)
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 1, 0x1234, frameIndex, 256, 3)
        val luma = render(profile, expected, envelope)
        drawSyncBand(luma, envelope.copy(frameIndex = frameIndex + 1), 825)
        val sync = V7Phase1SyncDecoder().analyze(identity, luma, 1000, 1000)
        assertEquals(null, sync.envelope)
        assertEquals("SYNC_TRANSITION_TOP_BOTTOM_MISMATCH", sync.status)
    }

    @Test
    fun physical720pWhiteSurroundAcquiresContractBorderAndOpticalSync() {
        try {
            OpenCvRuntime.ensureLoaded()
        } catch (failure: Throwable) {
            // The OpenCV Android artifact has no Linux-host JNI library. The
            // native regression runs on developer hosts that have one; CI still
            // compiles it and runs every pure receiver/sync assertion.
            Assume.assumeNoException("host OpenCV native library is unavailable", failure)
        }
        V6Contract.loadAndVerifyBytes(java.io.File("src/main/assets/visual_contract.json").readBytes())
        val profile = V7Phase1GridProfile("mono_64x50_matched", 50, 64, 1, 400)
        val receiver = V7Phase1Receiver(profile)
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 1, 0x5D84, 9, 32, 3)
        val canonical = renderPhysicalCarrier(profile, receiver.expectedSymbols(9), envelope)
        val frame = ByteArray(1280 * 720) { 235.toByte() }
        val markerSize = 600
        val offsetX = (1280 - markerSize) / 2
        val offsetY = (720 - markerSize) / 2
        for (y in 0 until markerSize) for (x in 0 until markerSize) {
            frame[(offsetY + y) * 1280 + offsetX + x] = canonical[(y * 1000 / markerSize) * 1000 + x * 1000 / markerSize]
        }

        V6StaticDetector().use { detector ->
            val geometry = detector.detectGeometry(frame, 1280, 720)
            assertTrue("contract border and anchors must acquire: ${geometry.failureReason}", geometry.finalInvHomography != null)
            val sync = V7Phase1SyncDecoder().analyze(geometry.finalInvHomography!!, frame, 1280, 720)
            assertEquals(sync.status, envelope, sync.envelope)
        }
    }

    @Test
    fun physicalMonitorBezelDoesNotHideNestedCarrierAndOpticalSync() {
        try {
            OpenCvRuntime.ensureLoaded()
        } catch (failure: Throwable) {
            Assume.assumeNoException("host OpenCV native library is unavailable", failure)
        }
        V6Contract.loadAndVerifyBytes(java.io.File("src/main/assets/visual_contract.json").readBytes())
        val profile = V7Phase1GridProfile("mono_64x50_matched", 50, 64, 1, 400)
        val receiver = V7Phase1Receiver(profile)
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 1, 0x6160, 17, 32, 3)
        val canonical = renderPhysicalCarrier(profile, receiver.expectedSymbols(17), envelope)

        // Real camera composition: room/background -> dark monitor bezel -> bright
        // display -> carrier. RETR_EXTERNAL sees only the bezel and suppresses the
        // nested SuperQR border; acquisition must search nested quads and identify
        // the carrier by its four strict corner identities.
        val frame = ByteArray(1280 * 720) { 170.toByte() }
        fillRect(frame, 1280, 50, 20, 1230, 700, 18)
        fillRect(frame, 1280, 90, 45, 1190, 675, 235)
        val markerSize = 600
        val offsetX = (1280 - markerSize) / 2
        val offsetY = (720 - markerSize) / 2
        for (y in 0 until markerSize) for (x in 0 until markerSize) {
            frame[(offsetY + y) * 1280 + offsetX + x] =
                canonical[(y * 1000 / markerSize) * 1000 + x * 1000 / markerSize]
        }

        V6StaticDetector().use { detector ->
            val geometry = detector.detectGeometry(frame, 1280, 720)
            assertTrue("nested carrier must acquire through monitor bezel: ${geometry.failureReason}", geometry.finalInvHomography != null)
            assertTrue("nested acquisition must examine multiple quads", geometry.quadsConsidered >= 2)
            val sync = V7Phase1SyncDecoder().analyze(geometry.finalInvHomography!!, frame, 1280, 720)
            assertEquals(sync.status, envelope, sync.envelope)
        }
    }

    @Test
    fun monitorRectangleAloneIsNotAcceptedAsGridCarrier() {
        try {
            OpenCvRuntime.ensureLoaded()
        } catch (failure: Throwable) {
            Assume.assumeNoException("host OpenCV native library is unavailable", failure)
        }
        V6Contract.loadAndVerifyBytes(java.io.File("src/main/assets/visual_contract.json").readBytes())
        val frame = ByteArray(1280 * 720) { 170.toByte() }
        fillRect(frame, 1280, 50, 20, 1230, 700, 18)
        fillRect(frame, 1280, 90, 45, 1190, 675, 235)

        V6StaticDetector().use { detector ->
            val geometry = detector.detectGeometry(frame, 1280, 720)
            assertTrue("screen and bezel quads should be observed", geometry.quadsConsidered >= 2)
            assertTrue("only the encoded V6 anchors may identify a grid carrier", !geometry.borderFound)
            assertTrue(geometry.finalInvHomography == null)
        }
    }

    @Test
    fun concentratedDamageUsesRealRs255BlockBoundary() {
        val profile = V7Phase1GridProfile("mono_64x50_matched", 50, 64, 1, 400)
        val receiver = V7Phase1Receiver(profile)
        val frameIndex = 11
        val expected = receiver.expectedSymbols(frameIndex)
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 1, 4, frameIndex, 256, 3)
        val luma = render(profile, expected, envelope)
        val cellW = 800.0 / profile.cols
        val cellH = 620.0 / profile.rows
        // 16 byte errors in the first 200-byte block cost 32 parity symbols;
        // the balanced 15% model gives that block 30, so the frame must fail.
        for (byte in 0 until 16) {
            val cell = byte * 8
            val row = cell / profile.cols
            val col = cell % profile.cols
            fillCell(luma, 1000, profile, row, col, cellW, cellH, if (expected[cell].toInt() == 0) 235 else 20)
        }

        val result = receiver.analyze(identity, luma, 1000, 1000, synchronizedFrameIndex = frameIndex)
        assertEquals(16, result.byteErrors)
        assertTrue(!result.postFecValid)
    }

    @Test
    fun qrPayloadValidationRequiresBinaryLengthHeaderAndCrc() {
        val payload = qrPayload(version = 27, frameIndex = 91, frameBytes = 1465)
        val good = V7Phase1QrDecoder.validatePayload(payload, 27, 1465)
        assertTrue(good.valid)
        assertEquals(91L, good.frameIndex)
        assertEquals(0xCAFE, good.envelope?.runToken)

        payload[100] = (payload[100].toInt() xor 1).toByte()
        val bad = V7Phase1QrDecoder.validatePayload(payload, 27, 1465)
        assertEquals("QR_CRC", bad.failure)
    }

    private fun render(profile: V7Phase1GridProfile, symbols: ByteArray, envelope: V7LabRunEnvelope): ByteArray {
        val image = ByteArray(1000 * 1000) { 235.toByte() }
        fillRect(image, 1000, 290, 110, 310, 130, 20)
        fillRect(image, 1000, 370, 110, 390, 130, 235)
        val cellW = 800.0 / profile.cols
        val cellH = 620.0 / profile.rows
        for (row in 0 until profile.rows) for (col in 0 until profile.cols) {
            val symbol = symbols[row * profile.cols + col].toInt()
            fillCell(image, 1000, profile, row, col, cellW, cellH, if (symbol == 0) 20 else 235)
        }
        drawSyncBand(image, envelope, 145)
        drawSyncBand(image, envelope, 825)
        return image
    }

    private fun renderPhysicalCarrier(
        profile: V7Phase1GridProfile, symbols: ByteArray, envelope: V7LabRunEnvelope,
    ): ByteArray {
        val image = ByteArray(1000 * 1000) { 235.toByte() }
        fillRect(image, 1000, 60, 60, 940, 940, 20)
        fillRect(image, 1000, 70, 70, 930, 930, 235)
        val anchors = listOf(
            intArrayOf(100, 100, 180, 180, 120, 120, 140, 140),
            intArrayOf(820, 100, 900, 180, 860, 120, 880, 140),
            intArrayOf(820, 820, 900, 900, 860, 860, 880, 880),
            intArrayOf(100, 820, 180, 900, 120, 860, 140, 880),
        )
        val cores = listOf(
            intArrayOf(120, 120, 160, 160), intArrayOf(840, 120, 880, 160),
            intArrayOf(840, 840, 880, 880), intArrayOf(120, 840, 160, 880),
        )
        for (index in anchors.indices) {
            val a = anchors[index]; val core = cores[index]
            fillRect(image, 1000, a[0], a[1], a[2], a[3], 20)
            fillRect(image, 1000, core[0], core[1], core[2], core[3], 235)
            fillRect(image, 1000, a[4], a[5], a[6], a[7], 20)
        }
        fillRect(image, 1000, 280, 100, 320, 140, 235)
        fillRect(image, 1000, 290, 110, 310, 130, 20)
        fillRect(image, 1000, 360, 100, 400, 140, 20)
        fillRect(image, 1000, 370, 110, 390, 130, 235)
        val cellW = 800.0 / profile.cols
        val cellH = 620.0 / profile.rows
        for (row in 0 until profile.rows) for (col in 0 until profile.cols) {
            val value = if (symbols[row * profile.cols + col].toInt() == 0) 20 else 235
            fillCell(image, 1000, profile, row, col, cellW, cellH, value)
        }
        drawSyncBand(image, envelope, 145)
        drawSyncBand(image, envelope, 825)
        return image
    }

    private fun drawSyncBand(image: ByteArray, envelope: V7LabRunEnvelope, top: Int) {
        val packet = envelope.encode()
        for (bit in 0 until 80) {
            val row = bit / 40; val col = bit % 40
            val value = if (((packet[bit / 8].toInt() ushr (7 - bit % 8)) and 1) == 0) 20 else 235
            fillRect(image, 1000, 200 + col * 15, top + row * 15, 200 + (col + 1) * 15, top + (row + 1) * 15, value)
        }
    }

    private fun fillCell(
        image: ByteArray, width: Int, profile: V7Phase1GridProfile,
        row: Int, col: Int, cellW: Double, cellH: Double, value: Int,
    ) = fillRect(
        image, width,
        (100.0 + col * cellW).toInt(), (190.0 + row * cellH).toInt(),
        (100.0 + (col + 1) * cellW).toInt(), (190.0 + (row + 1) * cellH).toInt(), value,
    )

    private fun fillRect(
        image: ByteArray, width: Int, left: Int, top: Int, right: Int, bottom: Int, value: Int,
    ) {
        for (y in top until bottom) for (x in left until right) image[y * width + x] = value.toByte()
    }

    private fun qrPayload(version: Int, frameIndex: Int, frameBytes: Int): ByteArray {
        val payload = ByteArray(frameBytes)
        payload[0] = 'S'.code.toByte(); payload[1] = 'Q'.code.toByte()
        payload[2] = 'P'.code.toByte(); payload[3] = '1'.code.toByte()
        payload[4] = version.toByte(); payload[5] = 1
        writeLe32(payload, 6, frameIndex)
        writeLe32(payload, 10, 42)
        payload[14] = (frameBytes and 0xFF).toByte()
        payload[15] = ((frameBytes ushr 8) and 0xFF).toByte()
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 5, 0xCAFE, frameIndex, 256, 3).encode()
        envelope.copyInto(payload, 16)
        val prng = V7LabPrng((42 xor version xor frameIndex).let { if (it == 0) 1 else it })
        for (index in 26 until frameBytes - 4) payload[index] = (prng.next() and 0xFF).toByte()
        writeLe32(payload, frameBytes - 4, CRC32().apply { update(payload, 0, frameBytes - 4) }.value.toInt())
        return payload
    }

    private fun writeLe32(bytes: ByteArray, offset: Int, value: Int) {
        for (shift in 0 until 4) bytes[offset + shift] = ((value ushr (8 * shift)) and 0xFF).toByte()
    }
}
