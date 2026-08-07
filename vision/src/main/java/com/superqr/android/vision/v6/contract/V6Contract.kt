package com.superqr.android.vision.v6.contract

import android.content.Context
import com.superqr.android.vision.v6.model.V6BBox
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object V6Contract {
    lateinit var json: JSONObject
    var rawFileHash: String = ""
    var canonicalHash: String = ""
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

        val jsonString = String(bytes, Charsets.UTF_8)
        val parsedJson = JSONObject(jsonString)
        require(parsedJson.optString("contract_version") == "v6") { "Invalid contract version" }

        json = parsedJson
        canonicalHash = computeCanonicalHash(parsedJson)

        try {
            android.util.Log.i(
                "V6Contract",
                "Asset Byte Length: $assetByteLength, Raw Hash: $rawFileHash, Canonical Hash: $canonicalHash, Expected Hash: $EXPECTED_HASH"
            )
        } catch (e: Throwable) {
            println("[V6Contract] Asset Byte Length: $assetByteLength, Raw Hash: $rawFileHash, Canonical Hash: $canonicalHash, Expected Hash: $EXPECTED_HASH")
        }

        require(canonicalHash == EXPECTED_HASH) {
            "Contract canonical hash mismatch! Expected $EXPECTED_HASH, got $canonicalHash"
        }
    }

    fun computeCanonicalHash(jsonString: String): String {
        return computeCanonicalHash(JSONObject(jsonString))
    }

    fun computeCanonicalHash(jsonObject: JSONObject): String {
        val canonicalStr = canonicalizeJson(jsonObject)
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(canonicalStr.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun quoteString(s: String): String = buildString {
        append('"')
        for (i in 0 until s.length) {
            val ch = s[i]
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code in 0x00..0x1F) {
                        append(String.format(java.util.Locale.US, "\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    fun canonicalizeJson(obj: Any?): String = when (obj) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> {
            val keys = mutableListOf<String>()
            val iterator = obj.keys()
            while (iterator.hasNext()) {
                keys.add(iterator.next())
            }
            keys.sort()
            keys.joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
                quoteString(key) + ":" + canonicalizeJson(obj.get(key))
            }
        }
        is JSONArray -> {
            val list = mutableListOf<String>()
            for (i in 0 until obj.length()) {
                list.add(canonicalizeJson(obj.get(i)))
            }
            list.joinToString(separator = ",", prefix = "[", postfix = "]")
        }
        is String -> quoteString(obj)
        is Boolean -> obj.toString()
        is Number -> obj.toString()
        else -> obj.toString()
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
