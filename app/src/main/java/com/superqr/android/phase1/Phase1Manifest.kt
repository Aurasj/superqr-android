package com.superqr.android.phase1

import android.content.Context
import com.superqr.android.vision.v7_capacity_lab.V7CarrierSpec
import com.superqr.android.vision.v7_capacity_lab.V7Phase1GridProfile
import org.json.JSONArray
import org.json.JSONObject

sealed interface Phase1Profile {
    val id: Int
    val name: String
    val usefulBytes: Int

    data class Grid(
        override val id: Int,
        override val name: String,
        val receiverProfile: V7Phase1GridProfile,
        override val usefulBytes: Int,
    ) : Phase1Profile

    data class Qr(
        override val id: Int,
        override val name: String,
        val version: Int,
        val frameBytes: Int,
        override val usefulBytes: Int,
        val targetFps: Double,
    ) : Phase1Profile
}

data class Phase1Manifest(
    val profiles: List<Phase1Profile>,
    val dwellEpochs: List<Int>,
    val referenceRefreshHz: Double,
    val seed: Int,
    val carrierSpec: V7CarrierSpec,
) {
    fun profile(id: Int): Phase1Profile? = profiles.firstOrNull { it.id == id }

    companion object {
        fun load(context: Context): Phase1Manifest {
            val text = context.assets.open("v7_phy_selection/phase1_manifest.json")
                .bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            require(json.getString("status") == "LAB_ONLY_NOT_A_V7_WIRE_CONTRACT")
            require(json.getInt("schema_version") >= 3)
            val acquisition = json.getJSONObject("acquisition_carrier")
            require(acquisition.getString("status") == "LAB_ONLY_NOT_PRODUCTION_V7_GEOMETRY")
            val border = acquisition.getJSONObject("outer_border")
            val finders = acquisition.getJSONArray("finder_patterns")
            val sync = json.getJSONObject("run_sync")
            val carrierSpec = V7CarrierSpec(
                canvasSize = json.getDouble("canvas_size"),
                borderBbox = border.getJSONArray("bbox").toDoubleArray(),
                borderThickness = border.getDouble("thickness"),
                syncTopBbox = sync.getJSONArray("top_bbox").toDoubleArray(),
                syncBottomBbox = sync.getJSONArray("bottom_bbox").toDoubleArray(),
                syncRows = sync.getInt("rows"),
                syncCols = sync.getInt("cols"),
                candidateContourEdges = acquisition.getJSONArray("candidate_contour_edges").toDoubleArray(),
                finderOuterBboxes = List(finders.length()) { index ->
                    finders.getJSONObject(index).getJSONArray("outer_bbox").toDoubleArray()
                },
            )

            val profiles = mutableListOf<Phase1Profile>()
            val grid = json.getJSONArray("grid_profiles")
            for (index in 0 until grid.length()) {
                val p = grid.getJSONObject(index)
                val rawBytes = p.getInt("raw_bytes_per_frame")
                val parityBytes = kotlin.math.ceil(rawBytes * 0.15).toInt()
                profiles += Phase1Profile.Grid(
                    id = profiles.size,
                    name = p.getString("name"),
                    receiverProfile = V7Phase1GridProfile(
                        name = p.getString("name"),
                        rows = p.getInt("rows"),
                        cols = p.getInt("cols"),
                        bitsPerCell = p.getInt("bits_per_cell"),
                        rawBytesPerFrame = rawBytes,
                    ),
                    usefulBytes = rawBytes - parityBytes,
                )
            }

            val qr = json.getJSONArray("qr_controls")
            for (index in 0 until qr.length()) {
                val p = qr.getJSONObject(index)
                profiles += Phase1Profile.Qr(
                    id = profiles.size,
                    name = p.getString("name"),
                    version = p.getInt("version"),
                    frameBytes = p.getInt("frame_bytes"),
                    usefulBytes = p.getInt("payload_bytes"),
                    targetFps = p.getDouble("target_fps"),
                )
            }

            val dwells = json.getJSONArray("dwell_epoch_candidates")
            return Phase1Manifest(
                profiles = profiles,
                dwellEpochs = List(dwells.length()) { dwells.getInt(it) },
                referenceRefreshHz = json.getDouble("reference_refresh_hz"),
                seed = json.getInt("seed"),
                carrierSpec = carrierSpec,
            )
        }

        private fun JSONArray.toDoubleArray(): DoubleArray =
            DoubleArray(length()) { index -> getDouble(index) }
    }
}
