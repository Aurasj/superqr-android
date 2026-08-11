package com.superqr.android.phase1

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AdvancedPalette(
    val name: String,
    val bitsPerCell: Int,
    val colors: List<String>,
)

sealed interface AdvancedLane {
    val laneId: Int
    val kind: String
    val usefulBytes: Int

    data class Qr(
        override val laneId: Int,
        val bbox: DoubleArray,
        val version: Int,
        val errorCorrection: String,
        val eccId: Int,
        val frameBytes: Int,
        override val usefulBytes: Int,
    ) : AdvancedLane {
        override val kind: String = "qr"
    }

    data class Grid(
        override val laneId: Int,
        val palette: String,
        val dataBbox: DoubleArray,
        val pilotBbox: DoubleArray,
        val rows: Int,
        val cols: Int,
        val bitsPerCell: Int,
        val rawBytes: Int,
        val fecParityRatio: Double,
        override val usefulBytes: Int,
    ) : AdvancedLane {
        override val kind: String = "grid"
    }
}

data class AdvancedProfile(
    val id: Int,
    val name: String,
    val kind: String,
    val targetFps: Double,
    val requiresCarrier: Boolean,
    val laneCount: Int,
    val lanes: List<AdvancedLane>,
    val usefulBytesPerEpoch: Int,
    val theoreticalMbps: Double,
    val role: String,
)

data class AdvancedPhyManifest(
    val profiles: List<AdvancedProfile>,
    val palettes: Map<String, AdvancedPalette>,
    val maxReceiverFps: Double,
    val defaultProfile: String,
    val seed: Int,
) {
    fun profile(id: Int): AdvancedProfile? = profiles.firstOrNull { it.id == id }

    companion object {
        fun load(context: Context): AdvancedPhyManifest {
            val text = context.assets.open("v7_phy_selection/advanced_phy.json")
                .bufferedReader().use { it.readText() }
            return parse(text)
        }

        internal fun parse(text: String): AdvancedPhyManifest {
            val json = JSONObject(text)
            require(json.getString("status") == "LAB_ONLY_NOT_A_V7_WIRE_CONTRACT")
            require(json.getInt("schema_version") >= 1)
            require(json.getInt("base_profile_count") == 14)
            val maxFps = json.getJSONObject("receiver_constraint").getDouble("max_fps")
            require(maxFps <= 30.0) { "advanced PHY must not require receiver >30 FPS" }
            val design = json.getJSONObject("design")
            val palettesJson = json.getJSONObject("palettes")
            val palettes = linkedMapOf<String, AdvancedPalette>()
            for (name in listOf("chroma4", "chroma8", "chroma16")) {
                val p = palettesJson.getJSONObject(name)
                val colorsJson = p.getJSONArray("colors")
                val colors = List(colorsJson.length()) { colorsJson.getString(it) }
                val bits = p.getInt("bits_per_cell")
                require(colors.size == (1 shl bits))
                palettes[name] = AdvancedPalette(name, bits, colors)
            }

            val profilesJson = json.getJSONArray("profiles")
            val profiles = ArrayList<AdvancedProfile>(profilesJson.length())
            for (index in 0 until profilesJson.length()) {
                val p = profilesJson.getJSONObject(index)
                val id = p.getInt("profile_id")
                require(id == 14 + index)
                val targetFps = p.getDouble("target_fps")
                require(targetFps <= maxFps)
                val lanesJson = p.getJSONArray("lanes")
                val lanes = ArrayList<AdvancedLane>(lanesJson.length())
                for (laneIndex in 0 until lanesJson.length()) {
                    val lane = lanesJson.getJSONObject(laneIndex)
                    require(lane.getInt("lane_id") == laneIndex)
                    lanes += when (lane.getString("kind")) {
                        "qr" -> AdvancedLane.Qr(
                            laneId = laneIndex,
                            bbox = lane.getJSONArray("bbox").toDoubleArray(),
                            version = lane.getInt("version"),
                            errorCorrection = lane.getString("error_correction").uppercase(),
                            eccId = lane.getInt("ecc_id"),
                            frameBytes = lane.getInt("frame_bytes"),
                            usefulBytes = lane.getInt("useful_bytes"),
                        )
                        "grid" -> AdvancedLane.Grid(
                            laneId = laneIndex,
                            palette = lane.getString("palette"),
                            dataBbox = lane.getJSONArray("data_bbox").toDoubleArray(),
                            pilotBbox = lane.getJSONArray("pilot_bbox").toDoubleArray(),
                            rows = lane.getInt("rows"),
                            cols = lane.getInt("cols"),
                            bitsPerCell = lane.getInt("bits_per_cell"),
                            rawBytes = lane.getInt("raw_bytes"),
                            fecParityRatio = lane.getDouble("fec_parity_ratio"),
                            usefulBytes = lane.getInt("useful_bytes"),
                        )
                        else -> error("unsupported advanced lane kind")
                    }
                }
                require(lanes.size == p.getInt("lane_count"))
                require(lanes.sumOf { it.usefulBytes } == p.getInt("useful_bytes_per_epoch"))
                profiles += AdvancedProfile(
                    id = id,
                    name = p.getString("name"),
                    kind = p.getString("kind"),
                    targetFps = targetFps,
                    requiresCarrier = p.getBoolean("requires_carrier"),
                    laneCount = p.getInt("lane_count"),
                    lanes = lanes,
                    usefulBytesPerEpoch = p.getInt("useful_bytes_per_epoch"),
                    theoreticalMbps = p.getDouble("theoretical_mbps"),
                    role = p.getString("role"),
                )
            }
            val defaultProfile = design.getString("default_profile")
            require(profiles.any { it.name == defaultProfile })
            return AdvancedPhyManifest(
                profiles = profiles,
                palettes = palettes,
                maxReceiverFps = maxFps,
                defaultProfile = defaultProfile,
                seed = design.getInt("seed"),
            )
        }

        private fun JSONArray.toDoubleArray(): DoubleArray =
            DoubleArray(length()) { index -> getDouble(index) }
    }
}
