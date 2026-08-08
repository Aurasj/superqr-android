package com.superqr.android.vision.v7_capacity_lab

import org.json.JSONObject
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Parsed V7 Capacity Lab manifest from lab_manifest.json (schema version 2).
 *
 * This is a GENERATED/SYNCHRONIZED LAB ARTIFACT. The source of truth is
 * superqr-protocol/test-vectors/v7-capacity-lab/lab_manifest.json.
 *
 * Provides access to: canvas geometry, grid sizes, palettes, layouts,
 * PRNG specification, frame sequence semantics, reference profiles,
 * and sequence vectors with golden hashes for cross-language validation.
 */
class V7LabManifest private constructor(
    private val json: JSONObject
) {
    val schemaVersion: Int = json.getInt("lab_schema_version")
    val labVersion: String = json.getString("lab_version")

    // Canvas
    val canvasWidth: Double
    val canvasHeight: Double
    val payloadBbox: DoubleArray

    // Grid sizes
    val gridSizes: IntArray

    // Dwell candidates
    val dwellEpochCandidates: IntArray

    // Palette data
    data class PaletteDef(
        val name: String,
        val bitsPerCell: Int,
        val symbolCount: Int,
        /** sRGB hex strings, indexed by symbol index. e.g. colorMap[0] = "#000000" */
        val colorMap: Map<Int, String>,
        /** RGB byte triples, indexed by symbol index. e.g. rgbMap[0] = intArrayOf(0,0,0) */
        val rgbMap: Map<Int, IntArray>
    )
    val palettes: Map<String, PaletteDef>

    // Reference profiles
    data class ProfileDef(
        val name: String,
        val gridSize: Int,
        val paletteName: String,
        val layoutName: String,
        val seed: Int,
        val dwellEpochs: Int,
        val solidFrames: Boolean,
        val preambleFrames: Int,
        val referenceCellDensity: Int
    )
    val referenceProfiles: Map<String, ProfileDef>

    // Sequence vectors (golden hashes per reference profile)
    data class SequenceVectorDef(
        val numDataFrames: Int,
        val calibrationFrames: List<CalFrameDef>,
        val dataFrames: List<DataFrameDef>,
        val sequenceSymbolSha256: String
    )
    data class CalFrameDef(
        val frameIndex: Int,
        val frameType: String,
        val logicalEpoch: Int,
        val dwellEpochs: Int,
        val symbolSha256: String
    )
    data class DataFrameDef(
        val frameIndex: Int,
        val frameType: String,
        val logicalEpoch: Int,
        val dwellEpochs: Int,
        val symbolSha256: String
    )
    val sequenceVectors: Map<String, SequenceVectorDef>

    val erasureMarker: Int = -1

    // Cached sequence symbols for later hashing
    private data class CachedSequenceSymbols(
        val gridSize: Int,
        val bitsPerCell: Int,
        val symbolCount: Int,
        val numCalFrames: Int,
        val calFrames: List<ByteArray>,
        val dataFrames: List<ByteArray>
    )
    private val cachedSeqSymbols = mutableMapOf<String, CachedSequenceSymbols>()

    init {
        // Parse canvas
        val canvasObj = json.getJSONObject("canvas")
        canvasWidth = canvasObj.getDouble("width")
        canvasHeight = canvasObj.getDouble("height")
        val pbbox = canvasObj.getJSONArray("payload_bbox")
        payloadBbox = doubleArrayOf(
            pbbox.getDouble(0), pbbox.getDouble(1),
            pbbox.getDouble(2), pbbox.getDouble(3)
        )

        // Parse grid sizes
        val gridArr = json.getJSONArray("grid_sizes")
        gridSizes = IntArray(gridArr.length()) { gridArr.getInt(it) }
        gridSizes.sort()

        // Parse dwell candidates
        val dwellArr = json.getJSONArray("dwell_epoch_candidates")
        dwellEpochCandidates = IntArray(dwellArr.length()) { dwellArr.getInt(it) }

        // Parse palettes
        val palObj = json.getJSONObject("palettes")
        val palMap = mutableMapOf<String, PaletteDef>()
        for (palName in palObj.keys()) {
            val p = palObj.getJSONObject(palName)
            val cm = p.getJSONObject("color_map")
            val colorMap = mutableMapOf<Int, String>()
            val rgbMap = mutableMapOf<Int, IntArray>()
            for (idxStr in cm.keys()) {
                val idx = idxStr.toInt()
                val hex = cm.getString(idxStr)
                colorMap[idx] = hex
                rgbMap[idx] = hexToRgb(hex)
            }
            palMap[palName] = PaletteDef(
                name = p.getString("name"),
                bitsPerCell = p.getInt("bits_per_cell"),
                symbolCount = p.getInt("symbol_count"),
                colorMap = colorMap,
                rgbMap = rgbMap
            )
        }
        palettes = palMap

        // Parse reference profiles
        val rpObj = json.getJSONObject("reference_profiles")
        val rpMap = mutableMapOf<String, ProfileDef>()
        for (name in rpObj.keys()) {
            val rp = rpObj.getJSONObject(name)
            val cal = rp.getJSONObject("calibration")
            rpMap[name] = ProfileDef(
                name = name,
                gridSize = rp.getInt("grid_size"),
                paletteName = rp.getString("palette_name"),
                layoutName = rp.getString("layout_name"),
                seed = rp.getInt("seed"),
                dwellEpochs = rp.getInt("dwell_epochs"),
                solidFrames = cal.getBoolean("solid_frames"),
                preambleFrames = cal.getInt("preamble_frames"),
                referenceCellDensity = cal.getInt("reference_cell_density")
            )
        }
        referenceProfiles = rpMap

        // Parse sequence vectors
        val svObj = json.getJSONObject("sequence_vectors")
        val svMap = mutableMapOf<String, SequenceVectorDef>()
        for (name in svObj.keys()) {
            val sv = svObj.getJSONObject(name)
            val calFramesArr = sv.getJSONArray("calibration_frames")
            val calFrames = mutableListOf<CalFrameDef>()
            for (i in 0 until calFramesArr.length()) {
                val cf = calFramesArr.getJSONObject(i)
                calFrames.add(CalFrameDef(
                    frameIndex = cf.getInt("frame_index"),
                    frameType = cf.getString("frame_type"),
                    logicalEpoch = cf.getInt("logical_epoch"),
                    dwellEpochs = cf.getInt("dwell_epochs"),
                    symbolSha256 = cf.getString("symbol_sha256")
                ))
            }
            val dataFramesArr = sv.getJSONArray("data_frames")
            val dataFrames = mutableListOf<DataFrameDef>()
            for (i in 0 until dataFramesArr.length()) {
                val df = dataFramesArr.getJSONObject(i)
                dataFrames.add(DataFrameDef(
                    frameIndex = df.getInt("frame_index"),
                    frameType = df.getString("frame_type"),
                    logicalEpoch = df.getInt("logical_epoch"),
                    dwellEpochs = df.getInt("dwell_epochs"),
                    symbolSha256 = df.getString("symbol_sha256")
                ))
            }
            svMap[name] = SequenceVectorDef(
                numDataFrames = sv.getInt("num_data_frames_in_sequence"),
                calibrationFrames = calFrames,
                dataFrames = dataFrames,
                sequenceSymbolSha256 = sv.getJSONObject("sequence_symbol_sha256").getString("hash")
            )
        }
        sequenceVectors = svMap
    }

    // ---- Validation ---------------------------------------------------

    /**
     * Validate the PRNG golden check from the manifest.
     * Generates a 20×20 grid with 4-color palette at seed 42 and compares
     * first_20 and symbol CRC-32 against manifest golden values.
     * Returns a list of error messages (empty = pass).
     */
    fun validatePrngGolden(): List<String> {
        val errors = mutableListOf<String>()
        val prngObj = json.getJSONObject("prng")
        val goldenObj = prngObj.getJSONObject("v6_golden_check")
        val expectedFirst20 = goldenObj.getJSONArray("first_20_symbols")
        val expectedCrc32 = goldenObj.getString("symbol_crc32")

        // Generate 20x20, 2 bits/cell, seed=42
        val symbols = V7LabPrng.generateExpectedSymbols(
            gridSize = 20, seed = 42, bitsPerCell = 2
        )

        // Check first 20
        for (i in 0 until 20) {
            val expected = expectedFirst20.getInt(i)
            val actual = symbols[i].toInt() and 0xFF
            if (actual != expected) {
                errors.add("first_20[$i]: expected=$expected, got=$actual")
            }
        }

        // Check CRC-32
        val crc = CRC32()
        crc.update(symbols)
        val actualCrc = "%08X".format(crc.value)
        if (actualCrc != expectedCrc32) {
            errors.add("symbol_crc32: expected=$expectedCrc32, got=$actualCrc")
        }

        return errors
    }

    /**
     * Validate data frame hashes for a reference profile.
     * Generates the frame sequence, hashes each data frame, and compares
     * against manifest sequence_vectors.
     */
    fun validateDataFrameHashes(profileName: String): List<String> {
        val errors = mutableListOf<String>()
        val profile = referenceProfiles[profileName]
            ?: return listOf("Profile '$profileName' not found")
        val seqVec = sequenceVectors[profileName]
            ?: return listOf("Sequence vectors for '$profileName' not found")
        val palette = palettes[profile.paletteName]
            ?: return listOf("Palette '${profile.paletteName}' not found")

        val (calFrames, dataFrames) = V7LabPrng.generateSequenceSymbols(
            gridSize = profile.gridSize,
            seed = profile.seed,
            bitsPerCell = palette.bitsPerCell,
            symbolCount = palette.symbolCount,
            numCalibrationFrames = seqVec.calibrationFrames.size,
            numDataFrames = seqVec.dataFrames.size
        )

        // Validate each data frame hash
        for (i in seqVec.dataFrames.indices) {
            val expectedSha256 = seqVec.dataFrames[i].symbolSha256
            val actualSha256 = sha256(dataFrames[i])
            if (actualSha256 != expectedSha256) {
                errors.add("Data frame $i (frame_index=${seqVec.dataFrames[i].frameIndex}): " +
                    "expected SHA-256=$expectedSha256, got=$actualSha256")
            }
        }

        // Validate each calibration frame hash
        for (i in seqVec.calibrationFrames.indices) {
            val expectedSha256 = seqVec.calibrationFrames[i].symbolSha256
            val actualSha256 = sha256(calFrames[i])
            if (actualSha256 != expectedSha256) {
                errors.add("Calibration frame $i (frame_index=${seqVec.calibrationFrames[i].frameIndex}): " +
                    "expected SHA-256=$expectedSha256, got=$actualSha256")
            }
        }

        return errors
    }

    /**
     * Validate the full sequence SHA-256 for a reference profile.
     * Concatenates all calibration + data frame lab-serialized bytes and
     * compares against the manifest sequence_symbol_sha256.
     */
    fun validateSequenceHash(profileName: String): List<String> {
        val errors = mutableListOf<String>()
        val profile = referenceProfiles[profileName]
            ?: return listOf("Profile '$profileName' not found")
        val seqVec = sequenceVectors[profileName]
            ?: return listOf("Sequence vectors for '$profileName' not found")
        val palette = palettes[profile.paletteName]
            ?: return listOf("Palette '${profile.paletteName}' not found")

        val (calFrames, dataFrames) = getOrGenerateSequence(profileName, profile, seqVec, palette)

        // Concatenate all calibration + data frames
        val totalBytes = (calFrames.sumOf { it.size } + dataFrames.sumOf { it.size })
        val allBytes = ByteArray(totalBytes)
        var offset = 0
        for (f in calFrames) {
            System.arraycopy(f, 0, allBytes, offset, f.size)
            offset += f.size
        }
        for (f in dataFrames) {
            System.arraycopy(f, 0, allBytes, offset, f.size)
            offset += f.size
        }

        val actualSha256 = sha256(allBytes)
        if (actualSha256 != seqVec.sequenceSymbolSha256) {
            errors.add("sequence_symbol_sha256: expected=${seqVec.sequenceSymbolSha256}, got=$actualSha256")
        }

        return errors
    }

    /**
     * Run all validations and return aggregated errors.
     * Fails loudly if critical checks do not pass.
     */
    fun validateAll(): List<String> {
        val errors = mutableListOf<String>()

        if (schemaVersion != 2) {
            errors.add("Unsupported schema version: $schemaVersion (expected 2)")
        }

        // PRNG golden
        errors.addAll(validatePrngGolden())

        // Reference profile hashes and sequence hashes
        for (profileName in sequenceVectors.keys) {
            errors.addAll(validateDataFrameHashes(profileName))
            errors.addAll(validateSequenceHash(profileName))
        }

        return errors
    }

    /**
     * Regenerate expected symbols for a specific data frame in a profile's sequence.
     * This is what the receiver uses to know what symbols the camera should be seeing.
     *
     * @param profileName reference profile name from the manifest
     * @param dataFrameIndex index into the data-frames sub-sequence (0 = first data frame)
     * @return ByteArray of expected symbol indices, row-major
     */
    fun generateExpectedDataFrame(profileName: String, dataFrameIndex: Int): ByteArray? {
        val profile = referenceProfiles[profileName] ?: return null
        val seqVec = sequenceVectors[profileName] ?: return null
        val palette = palettes[profile.paletteName] ?: return null

        if (dataFrameIndex < 0 || dataFrameIndex >= seqVec.dataFrames.size) return null

        val (_, dataFrames) = getOrGenerateSequence(profileName, profile, seqVec, palette)
        return dataFrames[dataFrameIndex]
    }

    /** Return the number of calibration frames in a reference profile's sequence. */
    fun numCalibrationFrames(profileName: String): Int {
        return sequenceVectors[profileName]?.calibrationFrames?.size ?: 0
    }

    /** Return the number of data frames in a reference profile's sequence. */
    fun numDataFrames(profileName: String): Int {
        return sequenceVectors[profileName]?.dataFrames?.size ?: 0
    }

    /** Check if a frame index belongs to a calibration frame. */
    fun isCalibrationFrame(profileName: String, frameIndex: Int): Boolean {
        val sv = sequenceVectors[profileName] ?: return false
        return sv.calibrationFrames.any { it.frameIndex == frameIndex }
    }

    /** Get frame type ("calibration" or "data") for a frame index. */
    fun frameType(profileName: String, frameIndex: Int): String? {
        val sv = sequenceVectors[profileName] ?: return null
        for (cf in sv.calibrationFrames) {
            if (cf.frameIndex == frameIndex) return cf.frameType
        }
        for (df in sv.dataFrames) {
            if (df.frameIndex == frameIndex) return df.frameType
        }
        return null
    }

    // ---- Helpers ------------------------------------------------------

    private fun getOrGenerateSequence(
        profileName: String, profile: ProfileDef, seqVec: SequenceVectorDef, palette: PaletteDef
    ): Pair<List<ByteArray>, List<ByteArray>> {
        val cached = cachedSeqSymbols[profileName]
        if (cached != null && cached.gridSize == profile.gridSize) {
            return Pair(cached.calFrames, cached.dataFrames)
        }
        val (calFrames, dataFrames) = V7LabPrng.generateSequenceSymbols(
            gridSize = profile.gridSize, seed = profile.seed,
            bitsPerCell = palette.bitsPerCell, symbolCount = palette.symbolCount,
            numCalibrationFrames = seqVec.calibrationFrames.size,
            numDataFrames = seqVec.dataFrames.size
        )
        cachedSeqSymbols[profileName] = CachedSequenceSymbols(
            gridSize = profile.gridSize, bitsPerCell = palette.bitsPerCell,
            symbolCount = palette.symbolCount,
            numCalFrames = seqVec.calibrationFrames.size,
            calFrames = calFrames, dataFrames = dataFrames
        )
        return Pair(calFrames, dataFrames)
    }

    companion object {
        /** Load the manifest from a raw ByteArray. */
        fun loadFromBytes(bytes: ByteArray): V7LabManifest {
            val jsonStr = String(bytes, Charsets.UTF_8)
            return V7LabManifest(JSONObject(jsonStr))
        }

        fun hexToRgb(hex: String): IntArray {
            val h = hex.removePrefix("#")
            return intArrayOf(
                h.substring(0, 2).toInt(16),
                h.substring(2, 4).toInt(16),
                h.substring(4, 6).toInt(16)
            )
        }

        fun sha256(data: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(data).joinToString("") { "%02x".format(it) }
        }

        fun crc32Hex(data: ByteArray): String {
            val crc = CRC32()
            crc.update(data)
            return "%08X".format(crc.value)
        }
    }
}
