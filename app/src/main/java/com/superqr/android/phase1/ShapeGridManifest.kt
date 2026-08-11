package com.superqr.android.phase1

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/** Lab-only runtime view of the canonical Chroma4 ShapeGrid artifact. */
data class ShapeGridProfile(
    val id: Int,
    val name: String,
    val role: String,
    val targetFps: Double,
    val gridCols: Int,
    val gridRows: Int,
    val blockCols: Int,
    val blockRows: Int,
    val blockCells: Int,
    val rsCodewordsPerBlock: Int,
    val encodedBytesPerBlock: Int,
    val activeSymbolsPerBlock: Int,
    val usefulBytesPerBlock: Int,
    val usefulBytesPerEpoch: Int,
    val theoreticalMbps: Double,
    val canonicalGridBbox: DoubleArray,
)

data class ShapeGridPilot(
    val name: String,
    val canonicalX: Double,
    val canonicalY: Double,
)

data class ShapeGridManifest(
    val profiles: List<ShapeGridProfile>,
    val glyphMasks: IntArray,
    /** Pilot order is ShapeGrid color bits: black, white, blue, red. */
    val pilotsByColorBits: List<ShapeGridPilot>,
    val payloadBbox: DoubleArray,
    val backgroundRgb: IntArray,
    val maxReceiverFps: Double,
    val designTargetFps: Double,
) {
    fun profile(id: Int): ShapeGridProfile? = profiles.firstOrNull { it.id == id }

    companion object {
        private const val MAX_CANONICAL_TILE_PITCH = 6.0

        fun load(context: Context): ShapeGridManifest {
            val text = context.assets.open("v7_phy_selection/chroma4_shapegrid.json")
                .bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            require(json.getString("artifact") == "v7_phase0_chroma4_shapegrid")
            require(json.getString("status") == "LAB_ONLY_NOT_A_V7_WIRE_CONTRACT")
            require(!json.getBoolean("production_phy_frozen"))
            require(json.getInt("schema_version") == 1)

            val receiver = json.getJSONObject("receiver_constraint")
            val designTarget = receiver.getDouble("design_target_fps")
            val maxReceiver = receiver.getDouble("max_fps")
            require(designTarget == 20.0)
            require(maxReceiver == 30.0)

            val acquisition = json.getJSONObject("acquisition")
            val payload = acquisition.getJSONArray("reference_payload_bbox").toDoubleArray()
            require(payload.contentEquals(doubleArrayOf(70.0, 190.0, 930.0, 810.0)))

            val modulation = json.getJSONObject("modulation")
            require(modulation.getInt("shape_bits") == 4)
            require(modulation.getInt("color_bits") == 2)
            require(modulation.getInt("symbol_bits") == 6)
            require(modulation.getJSONArray("glyph_subcells").toIntArray().contentEquals(intArrayOf(5, 5)))
            require(modulation.getJSONArray("tile_pitch_subcells").toIntArray().contentEquals(intArrayOf(6, 6)))
            require(modulation.getInt("inactive_tile_value") == 255)
            require(modulation.getString("background_color").uppercase() == "#808080")

            val palette = modulation.getJSONObject("data_palette")
            require(palette.getJSONArray("colors").toStringList() == listOf("#000000", "#FFFFFF", "#FF0000", "#0000FF"))
            require(palette.getJSONArray("bit_to_palette_index").toIntArray().contentEquals(intArrayOf(0, 1, 3, 2)))

            val glyphs = modulation.getJSONArray("glyphs")
            require(glyphs.length() == 16)
            val masks = IntArray(16)
            repeat(16) { index ->
                val glyph = glyphs.getJSONObject(index)
                require(glyph.getInt("shape_id") == index)
                require(glyph.getString("bits") == index.toString(2).padStart(4, '0'))
                val rows = glyph.getJSONArray("mask").toStringList()
                require(rows.size == 5 && rows.all { it.length == 5 })
                var mask = 0
                var ones = 0
                rows.forEach { row ->
                    row.forEach { char ->
                        mask = mask shl 1
                        when (char) {
                            '#' -> { mask = mask or 1; ones++ }
                            '.' -> Unit
                            else -> error("invalid ShapeGrid glyph char")
                        }
                    }
                }
                require(ones == 12)
                masks[index] = mask
            }
            for (a in 0 until 16) for (b in a + 1 until 16) {
                require(Integer.bitCount(masks[a] xor masks[b]) >= 10)
            }

            val profileArray = json.getJSONArray("profiles")
            require(profileArray.length() == 3)
            val profiles = List(profileArray.length()) { index ->
                val p = profileArray.getJSONObject(index)
                val id = p.getInt("profile_id")
                require(id == 18 + index)
                require(p.getDouble("target_fps") == 20.0)
                val cols = p.getInt("grid_cols")
                val rows = p.getInt("grid_rows")
                val blockCols = p.getInt("block_cols")
                val blockRows = p.getInt("block_rows")
                require(cols == blockCols * 4 && rows == blockRows * 2)
                val blockCells = blockCols * blockRows
                require(blockCells == p.getInt("block_cells"))
                val codewords = p.getInt("rs_codewords_per_block")
                require(p.getInt("encoded_bytes_per_block") == codewords * 255)
                require(p.getInt("active_symbols_per_block") == codewords * 255 * 8 / 6)
                require(p.getInt("useful_bytes_per_block") == codewords * 207 - 30)
                val bbox = canonicalGridBbox(cols, rows, payload)
                ShapeGridProfile(
                    id = id,
                    name = p.getString("name"),
                    role = p.getString("role"),
                    targetFps = p.getDouble("target_fps"),
                    gridCols = cols,
                    gridRows = rows,
                    blockCols = blockCols,
                    blockRows = blockRows,
                    blockCells = blockCells,
                    rsCodewordsPerBlock = codewords,
                    encodedBytesPerBlock = p.getInt("encoded_bytes_per_block"),
                    activeSymbolsPerBlock = p.getInt("active_symbols_per_block"),
                    usefulBytesPerBlock = p.getInt("useful_bytes_per_block"),
                    usefulBytesPerEpoch = p.getInt("useful_bytes_per_epoch"),
                    theoreticalMbps = p.getDouble("theoretical_net_mbps_at_20fps"),
                    canonicalGridBbox = bbox,
                )
            }
            require(profiles.map { it.role } == listOf("SAFE", "DEFAULT_FAST", "STRESS"))
            require(profiles.map { it.usefulBytesPerEpoch } == listOf(6384, 8040, 9696))

            val phase1Text = context.assets.open("v7_phy_selection/phase1_manifest.json")
                .bufferedReader().use { it.readText() }
            val phase1 = JSONObject(phase1Text)
            val pilotArray = phase1.getJSONObject("acquisition_carrier").getJSONArray("pilots")
            val byName = buildMap<String, ShapeGridPilot> {
                for (index in 0 until pilotArray.length()) {
                    val pilot = pilotArray.getJSONObject(index)
                    val bbox = pilot.getJSONArray("core_bbox").toDoubleArray()
                    put(
                        pilot.getString("name").uppercase(),
                        ShapeGridPilot(
                            pilot.getString("name").uppercase(),
                            (bbox[0] + bbox[2]) * 0.5,
                            (bbox[1] + bbox[3]) * 0.5,
                        ),
                    )
                }
            }
            val pilots = listOf("BLACK", "WHITE", "BLUE", "RED").map { requireNotNull(byName[it]) }

            return ShapeGridManifest(
                profiles = profiles,
                glyphMasks = masks,
                pilotsByColorBits = pilots,
                payloadBbox = payload,
                backgroundRgb = intArrayOf(128, 128, 128),
                maxReceiverFps = maxReceiver,
                designTargetFps = designTarget,
            )
        }

        fun canonicalGridBbox(cols: Int, rows: Int, payload: DoubleArray): DoubleArray {
            require(payload.size == 4 && cols > 0 && rows > 0)
            val pitch = min(
                MAX_CANONICAL_TILE_PITCH,
                min((payload[2] - payload[0]) / cols, (payload[3] - payload[1]) / rows),
            )
            val width = cols * pitch
            val height = rows * pitch
            val x0 = (payload[0] + payload[2] - width) * 0.5
            val y0 = (payload[1] + payload[3] - height) * 0.5
            return doubleArrayOf(x0, y0, x0 + width, y0 + height)
        }

        private fun JSONArray.toDoubleArray(): DoubleArray = DoubleArray(length()) { getDouble(it) }
        private fun JSONArray.toIntArray(): IntArray = IntArray(length()) { getInt(it) }
        private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }
    }
}
