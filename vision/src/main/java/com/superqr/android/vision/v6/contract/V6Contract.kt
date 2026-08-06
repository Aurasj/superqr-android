package com.superqr.android.vision.v6.contract

import android.content.Context
import com.superqr.android.vision.v6.model.V6BBox
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object V6Contract {
    lateinit var json: JSONObject
    var rawFileHash: String = ""
    var assetByteLength: Int = 0
    const val EXPECTED_HASH: String = "4b3e90a0a24106795066eabfbbddf6bdafa9b14584709c2ebb0614a12d07d757"

    fun loadAndVerify(context: Context) {
        val bytes = context.assets.open("visual_contract.json").use { it.readBytes() }
        loadAndVerifyBytes(bytes)
    }

    fun loadAndVerifyBytes(bytes: ByteArray) {
        assetByteLength = bytes.size
        val md = MessageDigest.getInstance("SHA-256")
        rawFileHash = md.digest(bytes).joinToString("") { "%02x".format(it) }
        
        try {
            android.util.Log.i("V6Contract", "Asset Byte Length: $assetByteLength, Raw Hash: $rawFileHash, Expected Hash: $EXPECTED_HASH")
        } catch (e: Throwable) {
            println("[V6Contract] Asset Byte Length: $assetByteLength, Raw Hash: $rawFileHash, Expected Hash: $EXPECTED_HASH")
        }
        
        if (rawFileHash != EXPECTED_HASH) {
            try {
                android.util.Log.e("V6Contract", "Hash mismatch! Expected $EXPECTED_HASH, got $rawFileHash")
            } catch (e: Throwable) {
                println("[V6Contract] Hash mismatch! Expected $EXPECTED_HASH, got $rawFileHash")
            }
        }
        
        val jsonString = String(bytes, Charsets.UTF_8)
        json = JSONObject(jsonString)
        require(json.optString("contract_version") == "v6") { "Invalid contract version" }
    }

    private fun parseBBox(array: JSONArray): V6BBox {
        fun getD(i: Int): Double = (array.get(i) as Number).toDouble()
        return V6BBox(getD(0), getD(1), getD(2), getD(3))
    }

    fun getAnchorBBox(id: String): V6BBox {
        val anchors = json.getJSONObject("anchors").getJSONObject("elements")
        return parseBBox(anchors.getJSONObject(id).getJSONArray("bbox"))
    }

    fun getAnchorCoreBBox(id: String): V6BBox {
        val anchors = json.getJSONObject("anchors").getJSONObject("elements")
        return parseBBox(anchors.getJSONObject(id).getJSONObject("identity_pattern").getJSONArray("core_bbox"))
    }

    fun getPilotCoreBBox(id: String): V6BBox {
        val pilots = json.getJSONObject("calibration_pilots").getJSONObject("elements")
        return parseBBox(pilots.getJSONObject(id).getJSONArray("core_bbox"))
    }

    fun getGridBBox(): V6BBox {
        return parseBBox(json.getJSONObject("data_grid").getJSONArray("bbox"))
    }

    fun getGridCols(): Int = json.getJSONObject("data_grid").getInt("cols")
    fun getGridRows(): Int = json.getJSONObject("data_grid").getInt("rows")
    fun getCellSize(): Double = (json.getJSONObject("data_grid").get("cell_size") as Number).toDouble()

    fun getCentralRegionRatio(): Double = 0.15

    fun getAnchorIdentityPattern(id: String): String = when (id) {
        "TL" -> "1000"
        "TR" -> "0100"
        "BR" -> "0010"
        "BL" -> "0001"
        else -> "0000"
    }
}
