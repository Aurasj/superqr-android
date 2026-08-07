package com.superqr.android.vision.v6.replay

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class V6ReplayLoadException(message: String) : IllegalArgumentException(message)

object V6DiagnosticZipLoader {

    fun loadFromZipFile(file: File): V6RawYuvFrame {
        require(file.exists() && file.isFile) { "File does not exist or is not a regular file: ${file.absolutePath}" }
        return file.inputStream().use { loadFromZipStream(it) }
    }

    fun loadFromZipStream(inputStream: InputStream): V6RawYuvFrame {
        var metadataJsonStr: String? = null
        var yBytes: ByteArray? = null
        var uBytes: ByteArray? = null
        var vBytes: ByteArray? = null

        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name.substringAfterLast('/')
                when (entryName) {
                    "frame_metadata.json" -> metadataJsonStr = zis.readBytes().toString(Charsets.UTF_8)
                    "y_plane.bin" -> yBytes = zis.readBytes()
                    "u_plane.bin" -> uBytes = zis.readBytes()
                    "v_plane.bin" -> vBytes = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val jsonStr = metadataJsonStr ?: throw V6ReplayLoadException("Missing frame_metadata.json in ZIP archive")
        val y = yBytes ?: throw V6ReplayLoadException("Missing y_plane.bin in ZIP archive")
        val u = uBytes ?: throw V6ReplayLoadException("Missing u_plane.bin in ZIP archive")
        val v = vBytes ?: throw V6ReplayLoadException("Missing v_plane.bin in ZIP archive")

        val json = JSONObject(jsonStr)

        val imageWidth = json.optInt("imageWidth", -1)
        val imageHeight = json.optInt("imageHeight", -1)
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw V6ReplayLoadException("Invalid dimensions: imageWidth=$imageWidth, imageHeight=$imageHeight")
        }

        val cropObj = json.optJSONObject("cropRect")
            ?: throw V6ReplayLoadException("Missing cropRect in frame_metadata.json")

        val cropLeft = cropObj.optInt("left", -1)
        val cropTop = cropObj.optInt("top", -1)
        val cropRight = cropObj.optInt("right", -1)
        val cropBottom = cropObj.optInt("bottom", -1)

        if (cropLeft < 0 || cropTop < 0 || cropRight <= cropLeft || cropBottom <= cropTop ||
            cropRight > imageWidth || cropBottom > imageHeight) {
            throw V6ReplayLoadException("Invalid cropRect: left=$cropLeft, top=$cropTop, right=$cropRight, bottom=$cropBottom for image ${imageWidth}x${imageHeight}")
        }

        val rotationDegrees = json.optInt("rotationDegrees", -1)
        if (rotationDegrees !in setOf(0, 90, 180, 270)) {
            throw V6ReplayLoadException("Invalid rotationDegrees: $rotationDegrees (must be 0, 90, 180, or 270)")
        }

        val yRowStride = json.optInt("yRowStride", -1)
        val yPixelStride = json.optInt("yPixelStride", -1)
        val uRowStride = json.optInt("uRowStride", -1)
        val uPixelStride = json.optInt("uPixelStride", -1)
        val vRowStride = json.optInt("vRowStride", -1)
        val vPixelStride = json.optInt("vPixelStride", -1)

        if (yRowStride <= 0 || yPixelStride <= 0 ||
            uRowStride <= 0 || uPixelStride <= 0 ||
            vRowStride <= 0 || vPixelStride <= 0) {
            throw V6ReplayLoadException("Invalid stride metadata: Y($yRowStride, $yPixelStride), U($uRowStride, $uPixelStride), V($vRowStride, $vPixelStride)")
        }

        val minYBytes = (cropBottom - 1) * yRowStride + cropRight * yPixelStride
        if (y.size < minYBytes) {
            throw V6ReplayLoadException("y_plane.bin size (${y.size}) is smaller than required minimum ($minYBytes)")
        }

        val chromaMaxY = cropBottom / 2
        val chromaMaxX = cropRight / 2
        val minUBytes = if (chromaMaxY > 0 && chromaMaxX > 0) (chromaMaxY - 1) * uRowStride + chromaMaxX * uPixelStride else 1
        if (u.size < minUBytes) {
            throw V6ReplayLoadException("u_plane.bin size (${u.size}) is smaller than required minimum ($minUBytes)")
        }

        val minVBytes = if (chromaMaxY > 0 && chromaMaxX > 0) (chromaMaxY - 1) * vRowStride + chromaMaxX * vPixelStride else 1
        if (v.size < minVBytes) {
            throw V6ReplayLoadException("v_plane.bin size (${v.size}) is smaller than required minimum ($minVBytes)")
        }

        val timestamp = json.optLong("timestamp", 0L)
        val patternName = json.optString("patternName", null)
        val sessionId = if (json.has("transportSessionId") && !json.isNull("transportSessionId")) json.getInt("transportSessionId") else null
        val frameId = if (json.has("transportFrameId") && !json.isNull("transportFrameId")) json.getInt("transportFrameId") else null
        val totalFrames = if (json.has("transportTotalFrames") && !json.isNull("transportTotalFrames")) json.getInt("transportTotalFrames") else null
        val payloadHex = if (json.has("transportPayloadHex") && !json.isNull("transportPayloadHex")) json.getString("transportPayloadHex") else null

        return V6RawYuvFrame(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropRight = cropRight,
            cropBottom = cropBottom,
            rotationDegrees = rotationDegrees,
            yRowStride = yRowStride,
            yPixelStride = yPixelStride,
            uRowStride = uRowStride,
            uPixelStride = uPixelStride,
            vRowStride = vRowStride,
            vPixelStride = vPixelStride,
            yPlaneBytes = y,
            uPlaneBytes = u,
            vPlaneBytes = v,
            timestamp = timestamp,
            patternName = patternName,
            transportSessionId = sessionId,
            transportFrameId = frameId,
            transportTotalFrames = totalFrames,
            transportPayloadHex = payloadHex
        )
    }
}
