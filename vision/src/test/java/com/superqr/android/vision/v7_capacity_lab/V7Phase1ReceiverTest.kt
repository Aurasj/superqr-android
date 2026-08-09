package com.superqr.android.vision.v7_capacity_lab

import com.superqr.android.vision.opencv.OpenCvRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.security.MessageDigest
import org.json.JSONObject
import kotlin.math.abs

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

        assertEquals("A80DC3C99B90842A6F63F809F96035B81CC1EA81347E294014A90F723A866335", hash)
        assertEquals("LAB_ONLY_NOT_A_V7_WIRE_CONTRACT", json.getString("status"))
        assertEquals(3, json.getInt("schema_version"))
        assertEquals(
            "LAB_ONLY_NOT_PRODUCTION_V7_GEOMETRY",
            json.getJSONObject("acquisition_carrier").getString("status"),
        )
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
    fun physical720pWhiteSurroundAcquiresV7CarrierAndOpticalSync() {
        try {
            OpenCvRuntime.ensureLoaded()
        } catch (failure: Throwable) {
            // The OpenCV Android artifact has no Linux-host JNI library. The
            // native regression runs on developer hosts that have one; CI still
            // compiles it and runs every pure receiver/sync assertion.
            Assume.assumeNoException("host OpenCV native library is unavailable", failure)
        }
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

        V7CarrierAcquirer().use { acquirer ->
            val acquisition = acquirer.analyze(frame, 1280, 720)
            assertTrue("V7 carrier must acquire: ${acquisition.bestSyncStatus}", acquisition.acquired)
            assertEquals(acquisition.sync.status, envelope, acquisition.sync.envelope)
            assertEquals("all four physical finders must be visible", 4, acquisition.visibleFinderCount)

            val tracked = acquirer.analyze(frame, 1280, 720)
            assertEquals("V7_SYNC_TRACKED", tracked.source)
            assertEquals(envelope, tracked.sync.envelope)
            assertEquals(4, tracked.visibleFinderCount)
        }
    }

    @Test
    fun physicalMonitorBezelDoesNotHideNestedCarrierAndOpticalSync() {
        try {
            OpenCvRuntime.ensureLoaded()
        } catch (failure: Throwable) {
            Assume.assumeNoException("host OpenCV native library is unavailable", failure)
        }
        val profile = V7Phase1GridProfile("mono_64x50_matched", 50, 64, 1, 400)
        val receiver = V7Phase1Receiver(profile)
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 1, 0x6160, 17, 32, 3)
        val canonical = renderPhysicalCarrier(profile, receiver.expectedSymbols(17), envelope)

        // Real camera composition: room/background -> dark monitor bezel -> bright
        // display -> carrier. RETR_EXTERNAL sees only the bezel and suppresses the
        // nested SuperQR border; acquisition must search nested quads and validate
        // the correct homography through the protected duplicated sync envelope.
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

        V7CarrierAcquirer().use { acquirer ->
            val acquisition = acquirer.analyze(frame, 1280, 720)
            assertTrue("nested carrier must acquire through monitor bezel: ${acquisition.bestSyncStatus}", acquisition.acquired)
            assertTrue("nested acquisition must examine multiple quads", acquisition.candidateCount >= 2)
            assertEquals(acquisition.sync.status, envelope, acquisition.sync.envelope)
        }
    }

    @Test
    fun monitorRectangleAloneIsNotAcceptedAsGridCarrier() {
        try {
            OpenCvRuntime.ensureLoaded()
        } catch (failure: Throwable) {
            Assume.assumeNoException("host OpenCV native library is unavailable", failure)
        }
        val frame = ByteArray(1280 * 720) { 170.toByte() }
        fillRect(frame, 1280, 50, 20, 1230, 700, 18)
        fillRect(frame, 1280, 90, 45, 1190, 675, 235)

        V7CarrierAcquirer().use { acquirer ->
            val acquisition = acquirer.analyze(frame, 1280, 720)
            assertTrue("screen and bezel quads should be observed", acquisition.candidateCount >= 2)
            assertTrue("a quadrilateral without protected sync must not lock", !acquisition.acquired)
            assertTrue(acquisition.canonicalToImageHomography == null)
        }
    }

    @Test
    fun perspectiveExposureAndMoireStillAcquireProtectedCarrier() {
        try {
            OpenCvRuntime.ensureLoaded()
        } catch (failure: Throwable) {
            Assume.assumeNoException("host OpenCV native library is unavailable", failure)
        }
        val profile = V7Phase1GridProfile("mono_128x100_qrlike", 100, 128, 1, 1600)
        val receiver = V7Phase1Receiver(profile)
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 2, 0x7A31, 23, 64, 3)
        val canonical = renderPhysicalCarrier(profile, receiver.expectedSymbols(23), envelope)
        val cameraFrame = projectCarrier(
            canonical,
            1280,
            720,
            arrayOf(
                doubleArrayOf(270.0, 70.0), doubleArrayOf(1025.0, 105.0),
                doubleArrayOf(1080.0, 650.0), doubleArrayOf(215.0, 610.0),
            ),
        )

        V7CarrierAcquirer().use { acquirer ->
            val acquisitionStart = System.nanoTime()
            val acquisition = acquirer.analyze(cameraFrame, 1280, 720)
            val acquisitionMs = (System.nanoTime() - acquisitionStart) / 1_000_000.0
            assertTrue(
                "perspective carrier must acquire; source=${acquisition.source}, " +
                    "best=${acquisition.bestSyncStatus}, candidates=${acquisition.candidateCount}",
                acquisition.acquired,
            )
            assertEquals(envelope, acquisition.sync.envelope)
            assertTrue(acquisition.sync.topContrast >= 100)
            assertTrue(acquisition.sync.bottomContrast >= 100)

            val trackingStart = System.nanoTime()
            var opticalFlowUpdates = 0
            repeat(60) {
                val tracked = acquirer.analyze(cameraFrame, 1280, 720)
                assertTrue(tracked.source == "V7_SYNC_TRACKED" || tracked.source == "V7_OPTICAL_FLOW_TRACKED")
                if (tracked.source == "V7_OPTICAL_FLOW_TRACKED") opticalFlowUpdates++
                assertEquals(envelope, tracked.sync.envelope)
            }
            val trackedMeanMs = (System.nanoTime() - trackingStart) / 60.0 / 1_000_000.0
            println(
                "V7_ACQUISITION_BENCHMARK full_ms=%.3f tracked_mean_ms=%.3f candidates=%d attempts=%d flow_updates=%d"
                    .format(acquisitionMs, trackedMeanMs, acquisition.candidateCount, acquisition.syncAttempts, opticalFlowUpdates),
            )
            assertTrue("periodic optical-flow geometry updates must run", opticalFlowUpdates >= 10)
        }
    }

    @Test
    fun realGalaxyA53MonitorFrameAcquiresProtectedCarrier() {
        try {
            OpenCvRuntime.ensureLoaded()
        } catch (failure: Throwable) {
            Assume.assumeNoException("host OpenCV native library is unavailable", failure)
        }
        val (luma, dimensions) = loadPgm("v7_capacity_lab/physical/galaxy-a53-monitor-600.pgm.gz")

        V7CarrierAcquirer().use { acquirer ->
            var acquisition = acquirer.analyze(luma, dimensions.first, dimensions.second)
            var framesToLock = 1
            repeat(7) {
                if (!acquisition.acquired) {
                    acquisition = acquirer.analyze(luma, dimensions.first, dimensions.second)
                    framesToLock++
                }
            }
            println(
                "V7_PHYSICAL_ACQUISITION source=${acquisition.source} frames_to_lock=$framesToLock " +
                    "candidates=${acquisition.candidateCount} finder_hypotheses=${acquisition.finderHypothesisCount}",
            )
            assertTrue(
                "real carrier must acquire; source=${acquisition.source}, best=${acquisition.bestSyncStatus}, " +
                    "candidates=${acquisition.candidateCount}, finders=${acquisition.finderHypothesisCount}, " +
                    "summary=${acquisition.candidateSummary}",
                acquisition.acquired,
            )
            assertEquals(0xAC75, acquisition.sync.envelope?.runToken)
        }
    }

    @Test
    fun croppedRealCarrierDoesNotFalseLock() {
        try {
            OpenCvRuntime.ensureLoaded()
        } catch (failure: Throwable) {
            Assume.assumeNoException("host OpenCV native library is unavailable", failure)
        }
        val (luma, dimensions) = loadPgm("v7_capacity_lab/physical/galaxy-a53-monitor-800-cropped.pgm.gz")

        V7CarrierAcquirer().use { acquirer ->
            var acquisition = acquirer.analyze(luma, dimensions.first, dimensions.second)
            repeat(7) { acquisition = acquirer.analyze(luma, dimensions.first, dimensions.second) }
            assertTrue("a carrier clipped on both horizontal sides must not false-lock", !acquisition.acquired)
            assertEquals("V7_NO_COMPLETE_CARRIER_GEOMETRY", acquisition.bestSyncStatus)
            assertTrue(
                "clipped frame should expose carrier evidence for a useful diagnostic; " +
                    "best=${acquisition.bestSyncStatus}, finders=${acquisition.finderHypothesisCount}",
                acquisition.carrierLike,
            )
        }
    }

    @Test
    fun handheldMotionUpdatesHomographyWithoutColdReacquisition() {
        try {
            OpenCvRuntime.ensureLoaded()
        } catch (failure: Throwable) {
            Assume.assumeNoException("host OpenCV native library is unavailable", failure)
        }
        val profile = V7Phase1GridProfile("mono_64x50_matched", 50, 64, 1, 400)
        val receiver = V7Phase1Receiver(profile)
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 1, 0x319A, 7, 32, 3)
        val canonical = renderPhysicalCarrier(profile, receiver.expectedSymbols(7), envelope)
        val initialCorners = arrayOf(
            doubleArrayOf(270.0, 70.0), doubleArrayOf(1025.0, 105.0),
            doubleArrayOf(1080.0, 650.0), doubleArrayOf(215.0, 610.0),
        )
        val movedCorners = arrayOf(
            doubleArrayOf(279.0, 76.0), doubleArrayOf(1032.0, 109.0),
            doubleArrayOf(1085.0, 655.0), doubleArrayOf(223.0, 617.0),
        )
        val initial = projectCarrier(canonical, 1280, 720, initialCorners)
        val moved = projectCarrier(canonical, 1280, 720, movedCorners)

        V7CarrierAcquirer().use { acquirer ->
            assertTrue(acquirer.analyze(initial, 1280, 720).acquired)
            repeat(3) { assertTrue(acquirer.analyze(initial, 1280, 720).acquired) }
            val tracked = acquirer.analyze(moved, 1280, 720)
            assertEquals("motion must use optical flow instead of a contour reacquisition", "V7_OPTICAL_FLOW_TRACKED", tracked.source)
            assertEquals(envelope, tracked.sync.envelope)
            assertEquals(0, tracked.candidateCount)
        }
    }

    @Test
    fun nestedFindersAcquireWhenScanPatternFragmentsContinuousBorder() {
        try {
            OpenCvRuntime.ensureLoaded()
        } catch (failure: Throwable) {
            Assume.assumeNoException("host OpenCV native library is unavailable", failure)
        }
        val profile = V7Phase1GridProfile("mono_64x50_matched", 50, 64, 1, 400)
        val receiver = V7Phase1Receiver(profile)
        val envelope = V7LabRunEnvelope(V7LabRunState.RUNNING, 1, 0x513D, 12, 32, 3)
        val canonical = renderPhysicalCarrier(profile, receiver.expectedSymbols(12), envelope)
        // Remove every continuous outer-border edge while leaving the compact
        // nested finders and protected sync bands intact.
        fillRect(canonical, 1000, 45, 45, 955, 75, 235)
        fillRect(canonical, 1000, 45, 925, 955, 955, 235)
        fillRect(canonical, 1000, 45, 45, 75, 955, 235)
        fillRect(canonical, 1000, 925, 45, 955, 955, 235)
        val frame = ByteArray(1280 * 720) { 165.toByte() }
        val markerSize = 600
        val offsetX = (1280 - markerSize) / 2
        val offsetY = (720 - markerSize) / 2
        for (y in 0 until markerSize) for (x in 0 until markerSize) {
            frame[(offsetY + y) * 1280 + offsetX + x] =
                canonical[(y * 1000 / markerSize) * 1000 + x * 1000 / markerSize]
        }

        V7CarrierAcquirer().use { acquirer ->
            val acquisition = acquirer.analyze(frame, 1280, 720)
            assertEquals(
                "${acquisition.bestSyncStatus}; candidates=${acquisition.candidateCount}; " +
                    "finderHypotheses=${acquisition.finderHypothesisCount}; attempts=${acquisition.syncAttempts}; " +
                    "summary=${acquisition.candidateSummary}",
                "V7_FINDERS_ACQUIRED",
                acquisition.source,
            )
            assertEquals(envelope, acquisition.sync.envelope)
            assertEquals(4, acquisition.visibleFinderCount)
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
        fillRect(image, 1000, 50, 50, 950, 950, 20)
        fillRect(image, 1000, 70, 70, 930, 930, 235)
        val finders = listOf(
            intArrayOf(80, 80, 180, 180), intArrayOf(820, 80, 920, 180),
            intArrayOf(820, 820, 920, 920), intArrayOf(80, 820, 180, 920),
        )
        for (finder in finders) {
            fillRect(image, 1000, finder[0], finder[1], finder[2], finder[3], 20)
            fillRect(image, 1000, finder[0] + 14, finder[1] + 14, finder[2] - 14, finder[3] - 14, 235)
            fillRect(image, 1000, finder[0] + 29, finder[1] + 29, finder[2] - 29, finder[3] - 29, 20)
        }
        fillRect(image, 1000, 280, 90, 320, 130, 235)
        fillRect(image, 1000, 290, 100, 310, 120, 20)
        fillRect(image, 1000, 360, 90, 400, 130, 20)
        fillRect(image, 1000, 370, 100, 390, 120, 235)
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

    private fun loadPgm(resource: String): Pair<ByteArray, Pair<Int, Int>> {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream(resource))
        val bytes = (if (resource.endsWith(".gz")) GZIPInputStream(raw) else raw).use { it.readBytes() }
        var newlineCount = 0
        var headerEnd = -1
        for (index in bytes.indices) {
            if (bytes[index] == '\n'.code.toByte() && ++newlineCount == 3) {
                headerEnd = index + 1
                break
            }
        }
        require(headerEnd > 0)
        val lines = String(bytes, 0, headerEnd, Charsets.US_ASCII).trim().lines()
        require(lines[0] == "P5" && lines[2] == "255")
        val dimensions = lines[1].split(' ').map(String::toInt)
        val pixels = bytes.copyOfRange(headerEnd, bytes.size)
        require(pixels.size == dimensions[0] * dimensions[1])
        return pixels to (dimensions[0] to dimensions[1])
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

    /** Inverse-map a canonical carrier into a realistic camera quadrilateral. */
    private fun projectCarrier(
        canonical: ByteArray,
        width: Int,
        height: Int,
        imageCorners: Array<DoubleArray>,
    ): ByteArray {
        val imageToCanonical = solveHomographyForTest(
            imageCorners,
            arrayOf(
                doubleArrayOf(0.0, 0.0), doubleArrayOf(999.0, 0.0),
                doubleArrayOf(999.0, 999.0), doubleArrayOf(0.0, 999.0),
            ),
        )
        val frame = ByteArray(width * height) { 168.toByte() }
        // A dark monitor bezel surrounding the projected white display plane.
        fillRect(frame, width, 180, 30, 1120, 690, 16)
        for (y in 0 until height) for (x in 0 until width) {
            val den = imageToCanonical[6] * x + imageToCanonical[7] * y + imageToCanonical[8]
            if (abs(den) < 1e-9) continue
            val cx = (imageToCanonical[0] * x + imageToCanonical[1] * y + imageToCanonical[2]) / den
            val cy = (imageToCanonical[3] * x + imageToCanonical[4] * y + imageToCanonical[5]) / den
            if (cx < 0.0 || cy < 0.0 || cx >= 1000.0 || cy >= 1000.0) continue
            val source = canonical[cy.toInt() * 1000 + cx.toInt()].toInt() and 0xFF
            // Reduced exposure range plus deterministic high-frequency display/camera
            // interference. The acquisition threshold and sync levels must be local.
            val interference = ((x * 17 + y * 31) % 13) - 6
            val observed = (30.0 + source * 0.72).toInt() + interference
            frame[y * width + x] = observed.coerceIn(0, 255).toByte()
        }
        return frame
    }

    private fun solveHomographyForTest(src: Array<DoubleArray>, dst: Array<DoubleArray>): DoubleArray {
        val augmented = Array(8) { DoubleArray(9) }
        for (index in 0 until 4) {
            val sx = src[index][0]; val sy = src[index][1]
            val dx = dst[index][0]; val dy = dst[index][1]
            augmented[index * 2].also {
                it[0] = sx; it[1] = sy; it[2] = 1.0; it[6] = -dx * sx; it[7] = -dx * sy; it[8] = dx
            }
            augmented[index * 2 + 1].also {
                it[3] = sx; it[4] = sy; it[5] = 1.0; it[6] = -dy * sx; it[7] = -dy * sy; it[8] = dy
            }
        }
        for (column in 0 until 8) {
            var pivot = column
            for (row in column + 1 until 8) {
                if (abs(augmented[row][column]) > abs(augmented[pivot][column])) pivot = row
            }
            val swap = augmented[column]; augmented[column] = augmented[pivot]; augmented[pivot] = swap
            val divisor = augmented[column][column]
            for (entry in column until 9) augmented[column][entry] /= divisor
            for (row in 0 until 8) if (row != column) {
                val factor = augmented[row][column]
                for (entry in column until 9) augmented[row][entry] -= factor * augmented[column][entry]
            }
        }
        return doubleArrayOf(
            augmented[0][8], augmented[1][8], augmented[2][8],
            augmented[3][8], augmented[4][8], augmented[5][8],
            augmented[6][8], augmented[7][8], 1.0,
        )
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
