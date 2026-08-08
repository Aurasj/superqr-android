package com.superqr.android.vision.v7_capacity_lab

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class V7LabManifestTest {

    companion object {
        private lateinit var manifest: V7LabManifest

        @BeforeClass
        @JvmStatic
        fun loadManifest() {
            // Load from the project assets (tests run from vision module)
            val assetsDir = File("src/test/resources/v7_capacity_lab")
            if (assetsDir.exists()) {
                val file = File(assetsDir, "lab_manifest.json")
                manifest = V7LabManifest.loadFromBytes(file.readBytes())
            } else {
                // Try app assets path
                val appAssets = File("../app/src/main/assets/v7_capacity_lab/lab_manifest.json")
                if (appAssets.exists()) {
                    manifest = V7LabManifest.loadFromBytes(appAssets.readBytes())
                } else {
                    // Try protocol repo path
                    val protoPath = File("../../superqr-protocol/test-vectors/v7-capacity-lab/lab_manifest.json")
                    manifest = V7LabManifest.loadFromBytes(protoPath.readBytes())
                }
            }
        }
    }

    @Test
    fun `schema version is 2`() {
        assertEquals(2, manifest.schemaVersion)
    }

    @Test
    fun `canvas geometry`() {
        assertEquals(1000.0, manifest.canvasWidth, 1e-9)
        assertEquals(1000.0, manifest.canvasHeight, 1e-9)
        assertArrayEquals(
            doubleArrayOf(200.0, 200.0, 800.0, 800.0),
            manifest.payloadBbox, 1e-9
        )
    }

    @Test
    fun `grid sizes present`() {
        assertTrue(manifest.gridSizes.size >= 7)
        assertTrue(manifest.gridSizes.contains(40))
        assertTrue(manifest.gridSizes.contains(96))
    }

    @Test
    fun `dwell candidates present`() {
        assertTrue(manifest.dwellEpochCandidates.size >= 3)
        assertTrue(manifest.dwellEpochCandidates.contains(2))
        assertTrue(manifest.dwellEpochCandidates.contains(3))
        assertTrue(manifest.dwellEpochCandidates.contains(4))
    }

    @Test
    fun `palettes loaded`() {
        val pal4 = manifest.palettes["v6_reference_4"]
        assertNotNull(pal4)
        assertEquals(2, pal4!!.bitsPerCell)
        assertEquals(4, pal4.symbolCount)
        assertEquals("#000000", pal4.colorMap[0])
        assertEquals("#FFFFFF", pal4.colorMap[1])
        assertEquals("#FF0000", pal4.colorMap[2])
        assertEquals("#0000FF", pal4.colorMap[3])

        val pal8 = manifest.palettes["candidate_8_a"]
        assertNotNull(pal8)
        assertEquals(3, pal8!!.bitsPerCell)
        assertEquals(8, pal8.symbolCount)
        assertEquals("#00FF00", pal8.colorMap[3]) // GREEN
        assertEquals("#FFFF00", pal8.colorMap[5]) // YELLOW
        assertEquals("#00FFFF", pal8.colorMap[6]) // CYAN
        assertEquals("#FF00FF", pal8.colorMap[7]) // MAGENTA
    }

    @Test
    fun `reference profiles loaded`() {
        assertTrue(manifest.referenceProfiles.size >= 14)
        val profile = manifest.referenceProfiles["ref_40x40_v6_reference_4_seed42"]
        assertNotNull(profile)
        assertEquals(40, profile!!.gridSize)
        assertEquals("v6_reference_4", profile.paletteName)
        assertEquals("single", profile.layoutName)
        assertEquals(42, profile.seed)
        assertEquals(2, profile.dwellEpochs)
        assertTrue(profile.solidFrames)
        assertEquals(0, profile.preambleFrames)
        assertEquals(0, profile.referenceCellDensity)
    }

    @Test
    fun `sequence vectors loaded`() {
        assertTrue(manifest.sequenceVectors.size >= 14)
        val sv = manifest.sequenceVectors["ref_40x40_v6_reference_4_seed42"]
        assertNotNull(sv)
        assertEquals(4, sv!!.numDataFrames)
        assertEquals(4, sv.calibrationFrames.size)
        assertEquals(4, sv.dataFrames.size)
    }

    @Test
    fun `erasure marker is -1`() {
        assertEquals(-1, manifest.erasureMarker)
    }

    // ---- Validation ----

    @Test
    fun `validate prng golden passes`() {
        val errors = manifest.validatePrngGolden()
        assertTrue("PRNG golden validation errors: $errors", errors.isEmpty())
    }

    @Test
    fun `validate data frame hashes for first profile`() {
        val profileName = "ref_40x40_v6_reference_4_seed42"
        val errors = manifest.validateDataFrameHashes(profileName)
        assertTrue("Data frame hash errors for $profileName: $errors", errors.isEmpty())
    }

    @Test
    fun `validate data frame hashes for candidate_8_a profile`() {
        val profileName = "ref_40x40_candidate_8_a_seed42"
        val errors = manifest.validateDataFrameHashes(profileName)
        assertTrue("Data frame hash errors for $profileName: $errors", errors.isEmpty())
    }

    @Test
    fun `validate sequence hash for first profile`() {
        val profileName = "ref_40x40_v6_reference_4_seed42"
        val errors = manifest.validateSequenceHash(profileName)
        assertTrue("Sequence hash errors for $profileName: $errors", errors.isEmpty())
    }

    @Test
    fun `validate sequence hash for candidate_8_a profile`() {
        val profileName = "ref_40x40_candidate_8_a_seed42"
        val errors = manifest.validateSequenceHash(profileName)
        assertTrue("Sequence hash errors for $profileName: $errors", errors.isEmpty())
    }

    @Test
    fun `validate all passes`() {
        val errors = manifest.validateAll()
        assertTrue("validateAll errors: $errors", errors.isEmpty())
    }

    // ---- Expected frame generation ----

    @Test
    fun `generate expected data frame 0 matches reference`() {
        val expected = manifest.generateExpectedDataFrame("ref_40x40_v6_reference_4_seed42", 0)
        assertNotNull(expected)
        assertEquals(1600, expected!!.size)
        assertEquals(1, expected[0].toInt() and 0xFF)
        assertEquals(2, expected[1].toInt() and 0xFF)
        assertEquals(3, expected[2].toInt() and 0xFF)
        assertEquals(0, expected[3].toInt() and 0xFF)
    }

    @Test
    fun `generate expected data frame 1 is different from frame 0`() {
        val frame0 = manifest.generateExpectedDataFrame("ref_40x40_v6_reference_4_seed42", 0)
        val frame1 = manifest.generateExpectedDataFrame("ref_40x40_v6_reference_4_seed42", 1)
        assertNotNull(frame0)
        assertNotNull(frame1)
        // Frames should be different (continuous PRNG state)
        assertFalse(frame0!!.contentEquals(frame1!!))
    }

    // ---- RGB conversion ----

    @Test
    fun `hex to rgb conversion`() {
        val rgb = V7LabManifest.hexToRgb("#FF0000")
        assertArrayEquals(intArrayOf(255, 0, 0), rgb)
    }

    @Test
    fun `hex to rgb black`() {
        val rgb = V7LabManifest.hexToRgb("#000000")
        assertArrayEquals(intArrayOf(0, 0, 0), rgb)
    }

    // ---- Frame type queries ----

    @Test
    fun `is calibration frame and frame type`() {
        val profileName = "ref_40x40_v6_reference_4_seed42"
        // First 4 frames are calibration (frame indices 0-3)
        assertTrue(manifest.isCalibrationFrame(profileName, 0))
        assertTrue(manifest.isCalibrationFrame(profileName, 3))
        // Frame 4 is data frame 0
        assertFalse(manifest.isCalibrationFrame(profileName, 4))

        assertEquals("calibration", manifest.frameType(profileName, 0))
        assertEquals("data", manifest.frameType(profileName, 4))

        // 8-color has 8 calibration frames
        val profileName8 = "ref_40x40_candidate_8_a_seed42"
        assertTrue(manifest.isCalibrationFrame(profileName8, 0))
        assertTrue(manifest.isCalibrationFrame(profileName8, 7))
        assertFalse(manifest.isCalibrationFrame(profileName8, 8))
    }

    @Test
    fun `calibration frame count and data frame count`() {
        assertEquals(4, manifest.numCalibrationFrames("ref_40x40_v6_reference_4_seed42"))
        assertEquals(4, manifest.numDataFrames("ref_40x40_v6_reference_4_seed42"))
        assertEquals(8, manifest.numCalibrationFrames("ref_40x40_candidate_8_a_seed42"))
        assertEquals(4, manifest.numDataFrames("ref_40x40_candidate_8_a_seed42"))
    }
}
