package com.superqr.android.vision.v6.diagnostic

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import com.superqr.android.vision.v6.normalization.FrameRotationHelper
import com.superqr.android.vision.v6.normalization.V6NormalizedFrameConverter
import com.superqr.android.vision.v6.replay.V6RawYuvFrame

object V6FullDiagnosticExporter {
    const val IS_SUPPORTED: Boolean = true

    fun exportToZip(bundle: V6CapturedFrameBundle, outputDir: File): File {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(bundle.timestamp))
        val zipFile = File(outputDir, "v6_full_diagnostic_$timestampStr.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // 1. summary.txt
            addZipEntry(zos, "summary.txt", generateSummaryTxt(bundle).toByteArray(Charsets.UTF_8))

            // 2. frame_metadata.json
            addZipEntry(zos, "frame_metadata.json", generateFrameMetadataJson(bundle).toByteArray(Charsets.UTF_8))

            // 3. Raw camera data planes
            addZipEntry(zos, "y_plane.bin", bundle.yPlaneBytes)
            addZipEntry(zos, "u_plane.bin", bundle.uPlaneBytes)
            addZipEntry(zos, "v_plane.bin", bundle.vPlaneBytes)

            // 4. pilots.csv
            addZipEntry(zos, "pilots.csv", generatePilotsCsv(bundle).toByteArray(Charsets.UTF_8))

            // 5. cells.csv
            addZipEntry(zos, "cells.csv", generateCellsCsv(bundle).toByteArray(Charsets.UTF_8))

            // 6. frame_trace.jsonl
            addZipEntry(zos, "frame_trace.jsonl", generateFrameTraceJsonl(bundle).toByteArray(Charsets.UTF_8))

            // 7. Diagnostic images
            generateAndAddImages(bundle, zos)
        }

        return zipFile
    }

    private fun addZipEntry(zos: ZipOutputStream, entryName: String, data: ByteArray) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    fun generateSummaryTxt(bundle: V6CapturedFrameBundle): String {
        val payload = bundle.payload
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateStr = dateFormat.format(Date(bundle.timestamp))

        val decCrcHex = payload.decodedCrc32?.let { String.format("%08X", it) } ?: "N/A"
        val expCrcHex = String.format("%08X", payload.expectedCrc32)
        val crcMatch = decCrcHex == expCrcHex
        val isTransportValid = (payload.transportSessionId != null && payload.transportError == null)

        val ySuccessTotal = payload.cellDetails.sumOf { it.yReadSuccessCount }
        val uSuccessTotal = payload.cellDetails.sumOf { it.uReadSuccessCount }
        val vSuccessTotal = payload.cellDetails.sumOf { it.vReadSuccessCount }
        val totalSamplePoints = payload.cellDetails.size * 5

        val highlights = mutableListOf<String>()
        if (ySuccessTotal < totalSamplePoints || uSuccessTotal < totalSamplePoints || vSuccessTotal < totalSamplePoints) {
            highlights.add("WARNING: Chroma/Luma read out of bounds detected (Y:$ySuccessTotal, U:$uSuccessTotal, V:$vSuccessTotal / $totalSamplePoints)")
        } else {
            highlights.add("[OK] Chroma reads: All $totalSamplePoints Y/U/V sample points read successfully within bounds.")
        }

        if (bundle.ransacInliers < 4) {
            highlights.add("WARNING: Low RANSAC inliers (${bundle.ransacInliers})")
        } else {
            highlights.add("[OK] Geometry: Valid border quad with ${bundle.ransacInliers} RANSAC inliers.")
        }

        val bwDist = payload.pairwisePilotDistances["DIST_BLACK_WHITE"] ?: 0.0
        if (bwDist < 5000.0) {
            highlights.add("WARNING: Low BLACK-WHITE pilot separation distance ($bwDist)")
        } else {
            highlights.add("[OK] Pilot Separation: Pilots show distinct YUV profiles (BLACK-WHITE dist: ${bwDist.roundToInt()}).")
        }

        if (bundle.uncertainCount > 20) {
            highlights.add("WARNING: High uncertain cell count (${bundle.uncertainCount}/400)")
        } else {
            highlights.add("[OK] Cell Confidence: Low uncertain cell count (${bundle.uncertainCount}/400).")
        }

        if (isTransportValid) {
            highlights.add("[OK] V6 Transport Frame: VALID (Session: ${payload.transportSessionId}, Frame: ${payload.transportFrameId}/${payload.transportTotalFrames})")
            highlights.add("[OK] CRC16: PASS (0x${payload.transportCrc16Hex})")
            highlights.add("[INFO] Static golden-pattern comparison not applicable to transport frame")
        } else {
            if (crcMatch) {
                highlights.add("[OK] Pattern CRC Match: Decoded CRC32 matches expected $expCrcHex.")
            } else {
                highlights.add("CRITICAL: Pattern CRC mismatch! Decoded=$decCrcHex, Expected=$expCrcHex")
            }
        }

        val quadStr = payload.detectedQuad?.joinToString(", ") { "[%.1f, %.1f]".format(Locale.US, it[0], it[1]) } ?: "N/A"

        val decodingSection = if (isTransportValid) {
            val prefix = "A506${"%02X".format(payload.transportSessionId)}${"%04X".format(payload.transportFrameId)}${"%04X".format(payload.transportTotalFrames)}"
            val pkgLenStr = if (payload.transportFrameId == 0 && payload.transportPayloadHex != null && payload.transportPayloadHex.length >= 8) {
                "Package Length (Frame 0): ${payload.transportPayloadHex.substring(0, 8).toLong(16)} bytes\n"
            } else ""
            """
--- DECODING & CLASSIFICATION ---
Transport Frame: VALID
Session ID: ${payload.transportSessionId}
Frame ID: ${payload.transportFrameId} / ${payload.transportTotalFrames}
CRC16: PASS (0x${payload.transportCrc16Hex})
Raw Header Prefix: $prefix
${pkgLenStr}Static Golden-Pattern Comparison: N/A (Transport Data Grid)
Classification Source: ${payload.classificationSource}
""".trimIndent()
        } else {
            """
--- DECODING & CLASSIFICATION ---
Expected CRC32: $expCrcHex
Decoded CRC32:  $decCrcHex ${if (crcMatch) "(MATCH)" else "(MISMATCH)"}
Cell Classification Results:
  Correct:   ${bundle.correctCount} / 400 (${"%.1f".format(Locale.US, bundle.correctCount * 100.0 / 400.0)}%)
  Incorrect: ${bundle.incorrectCount} / 400 (${"%.1f".format(Locale.US, bundle.incorrectCount * 100.0 / 400.0)}%)
  Uncertain: ${bundle.uncertainCount} / 400 (${"%.1f".format(Locale.US, bundle.uncertainCount * 100.0 / 400.0)}%)
Classification Source: ${payload.classificationSource}
${if (payload.transportError != null) "Transport Rejection Reason: ${payload.transportError}" else ""}
""".trimIndent()
        }

        val rawCropW = bundle.cropRect.right - bundle.cropRect.left
        val rawCropH = bundle.cropRect.bottom - bundle.cropRect.top
        val cropW = if (rawCropW > 0) rawCropW else bundle.imageWidth
        val cropH = if (rawCropH > 0) rawCropH else bundle.imageHeight
        val (normW, normH) = FrameRotationHelper.getNormalizedDimensions(cropW, cropH, bundle.rotationDegrees)

        return """
===================================================================
SUPERQR V6 FULL DIAGNOSTIC SUMMARY
===================================================================
Timestamp: $dateStr
Contract Hash: ${payload.contractHash}
Active Pattern: ${payload.patternName} (Seed: ${payload.seed})

$decodingSection

--- DETECTOR & TRACKER STATE ---
Tracking State: ${bundle.trackingState}
RANSAC Inliers: ${bundle.ransacInliers}
Detected Quad: [$quadStr]

--- CAMERA FRAME PARAMETERS ---
Raw Camera Dimensions: ${bundle.imageWidth} x ${bundle.imageHeight}
Rotation: ${bundle.rotationDegrees} degrees
Crop Rect: Rect(${bundle.cropRect.left}, ${bundle.cropRect.top} - ${bundle.cropRect.right}, ${bundle.cropRect.bottom}) (${cropW}x${cropH})
Normalized Detector Dimensions: $normW x $normH
Overlay Coordinate Space: NORMALIZED_DETECTOR
Y Plane: RowStride=${bundle.yRowStride}, PixelStride=${bundle.yPixelStride}
U Plane: RowStride=${bundle.uRowStride}, PixelStride=${bundle.uPixelStride}
V Plane: RowStride=${bundle.vRowStride}, PixelStride=${bundle.vPixelStride}

--- CHROMA & LUMA READ COUNTS ---
Y Read Success Count: $ySuccessTotal / $totalSamplePoints
U Read Success Count: $uSuccessTotal / $totalSamplePoints
V Read Success Count: $vSuccessTotal / $totalSamplePoints

--- PILOT MEDIAN YUV & SEPARATION ---
${payload.pilotDetails.joinToString("\n") { "${it.pilotName.padEnd(7)} Pilot: Y=${it.medianY}, U=${it.medianU}, V=${it.medianV}" }}

Pairwise Pilot Distances (Squared YUV):
${payload.pairwisePilotDistances.entries.joinToString("\n") { "  ${it.key}: ${"%.1f".format(Locale.US, it.value)}" }}

--- AUTOMATIC DIAGNOSTIC HIGHLIGHTS ---
${highlights.joinToString("\n")}
===================================================================
""".trimIndent()
    }

    fun generateFrameMetadataJson(bundle: V6CapturedFrameBundle): String {
        val payload = bundle.payload
        val root = JSONObject()
        root.put("imageWidth", bundle.imageWidth)
        root.put("imageHeight", bundle.imageHeight)
        root.put("rotationDegrees", bundle.rotationDegrees)

        val cropObj = JSONObject()
        cropObj.put("left", bundle.cropRect.left)
        cropObj.put("top", bundle.cropRect.top)
        cropObj.put("right", bundle.cropRect.right)
        cropObj.put("bottom", bundle.cropRect.bottom)
        root.put("cropRect", cropObj)

        root.put("timestamp", bundle.timestamp)

        root.put("yRowStride", bundle.yRowStride)
        root.put("yPixelStride", bundle.yPixelStride)
        root.put("uRowStride", bundle.uRowStride)
        root.put("uPixelStride", bundle.uPixelStride)
        root.put("vRowStride", bundle.vRowStride)
        root.put("vPixelStride", bundle.vPixelStride)

        val detQuadArr = JSONArray()
        payload.detectedQuad?.forEach { pt ->
            val arr = JSONArray()
            arr.put(pt[0])
            arr.put(pt[1])
            detQuadArr.put(arr)
        }
        root.put("detectedQuad", detQuadArr)

        val imgToCanonArr = JSONArray()
        payload.imageToCanonicalHomography?.forEach { imgToCanonArr.put(it) }
        root.put("imageToCanonicalHomography", imgToCanonArr)

        val canonToImgArr = JSONArray()
        payload.canonicalToImageHomography?.forEach { canonToImgArr.put(it) }
        root.put("canonicalToImageHomography", canonToImgArr)

        root.put("trackingState", bundle.trackingState)
        val trackedQuadArr = JSONArray()
        payload.trackedQuad?.forEach { pt ->
            val arr = JSONArray()
            arr.put(pt[0])
            arr.put(pt[1])
            trackedQuadArr.put(arr)
        }
        root.put("trackedQuad", trackedQuadArr)

        root.put("classificationSource", payload.classificationSource)
        root.put("contractHash", payload.contractHash)
        root.put("patternName", payload.patternName)
        root.put("seed", payload.seed)

        val expArr = JSONArray()
        payload.first20Expected.forEach { expArr.put(it) }
        root.put("first20ExpectedIndexes", expArr)

        val decArr = JSONArray()
        payload.first20Decoded.forEach { decArr.put(it) }
        root.put("first20DecodedIndexes", decArr)

        root.put("expectedCrc32", String.format("%08X", payload.expectedCrc32))
        root.put("decodedCrc32", payload.decodedCrc32?.let { String.format("%08X", it) } ?: "N/A")

        val isTransportValid = (payload.transportSessionId != null && payload.transportError == null)
        root.put("transportParseAttempted", payload.classificationSource == "FULL_DETECTION")
        root.put("transportValid", isTransportValid)
        root.put("transportSessionId", payload.transportSessionId ?: JSONObject.NULL)
        root.put("transportFrameId", payload.transportFrameId ?: JSONObject.NULL)
        root.put("transportTotalFrames", payload.transportTotalFrames ?: JSONObject.NULL)
        root.put("transportCrc16Hex", payload.transportCrc16Hex ?: JSONObject.NULL)
        root.put("transportError", payload.transportError ?: JSONObject.NULL)
        if (isTransportValid) {
            val prefix = "A506${"%02X".format(payload.transportSessionId)}${"%04X".format(payload.transportFrameId)}${"%04X".format(payload.transportTotalFrames)}"
            root.put("transportHeaderPrefix", prefix)
            if (payload.transportFrameId == 0 && payload.transportPayloadHex != null && payload.transportPayloadHex.length >= 8) {
                root.put("transportPackageLength", payload.transportPayloadHex.substring(0, 8).toLong(16))
            }
        }

        return root.toString(2)
    }

    fun generatePilotsCsv(bundle: V6CapturedFrameBundle): String {
        val sb = StringBuilder()
        sb.append("pilot_name,canonical_x,canonical_y,mapped_image_x,mapped_image_y,y_success,u_success,v_success,raw_y,raw_u,raw_v,median_y,median_u,median_v\n")

        for (p in bundle.payload.pilotDetails) {
            for ((idx, sample) in p.rawSamples.withIndex()) {
                val name = "${p.pilotName}_s$idx"
                sb.append("$name,${p.canonicalCenterX},${p.canonicalCenterY},${p.mappedCameraCenterX},${p.mappedCameraCenterY},${p.ySuccess},${p.uSuccess},${p.vSuccess},${sample[0]},${sample[1]},${sample[2]},${p.medianY},${p.medianU},${p.medianV}\n")
            }
            sb.append("${p.pilotName}_MEDIAN,${p.canonicalCenterX},${p.canonicalCenterY},${p.mappedCameraCenterX},${p.mappedCameraCenterY},${p.ySuccess},${p.uSuccess},${p.vSuccess},${p.medianY},${p.medianU},${p.medianV},${p.medianY},${p.medianU},${p.medianV}\n")
        }

        sb.append("\nPAIRWISE_PILOT_DISTANCES\n")
        for ((key, dist) in bundle.payload.pairwisePilotDistances) {
            sb.append("$key,${"%.2f".format(Locale.US, dist)}\n")
        }

        return sb.toString()
    }

    fun generateCellsCsv(bundle: V6CapturedFrameBundle): String {
        val sb = StringBuilder()
        sb.append("row,col,expected_idx,expected_color,decoded_idx,decoded_color,status,classification_source,canonical_center_x,canonical_center_y,")
        sb.append("sample0_cx,sample0_cy,sample1_cx,sample1_cy,sample2_cx,sample2_cy,sample3_cx,sample3_cy,sample4_cx,sample4_cy,")
        sb.append("sample0_ix,sample0_iy,sample1_ix,sample1_iy,sample2_ix,sample2_iy,sample3_ix,sample3_iy,sample4_ix,sample4_iy,")
        sb.append("y_read_success_count,u_read_success_count,v_read_success_count,")
        sb.append("s0_y,s0_u,s0_v,s1_y,s1_u,s1_v,s2_y,s2_u,s2_v,s3_y,s3_u,s3_v,s4_y,s4_u,s4_v,")
        sb.append("median_y,median_u,median_v,norm_y,norm_u,norm_v,")
        sb.append("dist_black,dist_white,dist_red,dist_blue,nearest_dist,second_best_dist,confidence_margin,uncertain_reason\n")

        for (c in bundle.payload.cellDetails) {
            sb.append("${c.row},${c.col},${c.expectedIdx},${c.expectedColor},${c.decodedIdx},${c.decodedColor},${c.status},${c.classificationSource},")
            sb.append("${c.canonicalCenterX},${c.canonicalCenterY},")
            val cs = c.canonicalSamples
            sb.append("${cs[0].first},${cs[0].second},${cs[1].first},${cs[1].second},${cs[2].first},${cs[2].second},${cs[3].first},${cs[3].second},${cs[4].first},${cs[4].second},")
            val ms = c.mappedCameraSamples
            sb.append("${ms[0].first},${ms[0].second},${ms[1].first},${ms[1].second},${ms[2].first},${ms[2].second},${ms[3].first},${ms[3].second},${ms[4].first},${ms[4].second},")
            sb.append("${c.yReadSuccessCount},${c.uReadSuccessCount},${c.vReadSuccessCount},")
            val rs = c.rawSamples
            sb.append("${rs[0][0]},${rs[0][1]},${rs[0][2]},${rs[1][0]},${rs[1][1]},${rs[1][2]},${rs[2][0]},${rs[2][1]},${rs[2][2]},${rs[3][0]},${rs[3][1]},${rs[3][2]},${rs[4][0]},${rs[4][1]},${rs[4][2]},")
            sb.append("${c.medianY},${c.medianU},${c.medianV},")
            sb.append("${"%.2f".format(Locale.US, c.normY)},${"%.2f".format(Locale.US, c.normU)},${"%.2f".format(Locale.US, c.normV)},")
            sb.append("${"%.1f".format(Locale.US, c.distBlack)},${"%.1f".format(Locale.US, c.distWhite)},${"%.1f".format(Locale.US, c.distRed)},${"%.1f".format(Locale.US, c.distBlue)},")
            sb.append("${"%.1f".format(Locale.US, c.nearestDist)},${"%.1f".format(Locale.US, c.secondBestDist)},${"%.4f".format(Locale.US, c.confidenceMargin)},${c.uncertainReason}\n")
        }

        return sb.toString()
    }

    fun generateFrameTraceJsonl(bundle: V6CapturedFrameBundle): String {
        val sb = StringBuilder()
        for (trace in bundle.frameTrace) {
            val obj = JSONObject()
            obj.put("timestamp", trace.timestamp)
            obj.put("state", trace.state)
            if (trace.quad != null) {
                val qArr = JSONArray()
                trace.quad.forEach { pt ->
                    val arr = JSONArray()
                    arr.put(pt[0])
                    arr.put(pt[1])
                    qArr.put(arr)
                }
                obj.put("quad", qArr)
            }
            obj.put("ransacInliers", trace.ransacInliers)
            obj.put("correctCount", trace.correctCount)
            obj.put("incorrectCount", trace.incorrectCount)
            obj.put("uncertainCount", trace.uncertainCount)
            obj.put("decodedCrc", trace.decodedCrc)

            val pilotsObj = JSONObject()
            for ((key, value) in trace.pilotMedians) {
                val pArr = JSONArray()
                value.forEach { pArr.put(it) }
                pilotsObj.put(key, pArr)
            }
            obj.put("pilotMedians", pilotsObj)

            val yuvReadsObj = JSONObject()
            for ((key, value) in trace.yuvReadSuccessCounts) {
                yuvReadsObj.put(key, value)
            }
            obj.put("yuvReadSuccessCounts", yuvReadsObj)

            sb.append(obj.toString()).append("\n")
        }
        return sb.toString()
    }
    private fun generateAndAddImages(bundle: V6CapturedFrameBundle, zos: ZipOutputStream) {
        val rawCropW = bundle.cropRect.right - bundle.cropRect.left
        val rawCropH = bundle.cropRect.bottom - bundle.cropRect.top
        val cropRight = if (rawCropW > 0) bundle.cropRect.right else bundle.imageWidth
        val cropBottom = if (rawCropH > 0) bundle.cropRect.bottom else bundle.imageHeight

        val rawFrame = V6RawYuvFrame(
            imageWidth = bundle.imageWidth,
            imageHeight = bundle.imageHeight,
            cropLeft = bundle.cropRect.left,
            cropTop = bundle.cropRect.top,
            cropRight = cropRight,
            cropBottom = cropBottom,
            rotationDegrees = bundle.rotationDegrees,
            yRowStride = bundle.yRowStride,
            yPixelStride = bundle.yPixelStride,
            uRowStride = bundle.uRowStride,
            uPixelStride = bundle.uPixelStride,
            vRowStride = bundle.vRowStride,
            vPixelStride = bundle.vPixelStride,
            yPlaneBytes = bundle.yPlaneBytes,
            uPlaneBytes = bundle.uPlaneBytes,
            vPlaneBytes = bundle.vPlaneBytes
        )

        // 1. camera_raw_rgb.png & camera_rgb.png (raw sensor orientation)
        val rawRes = V6NormalizedFrameConverter.convertRawYuvToRawBgr(rawFrame)
        val cameraRawMat = Mat(rawRes.height, rawRes.width, CvType.CV_8UC3)
        cameraRawMat.put(0, 0, rawRes.bgrBytes)
        val cameraRawPng = encodeMatToPng(cameraRawMat)
        addZipEntry(zos, "camera_raw_rgb.png", cameraRawPng)
        addZipEntry(zos, "camera_rgb.png", cameraRawPng)
        cameraRawMat.release()

        // 2. camera_normalized_rgb.png (EXACT image coordinate space used by V6StaticDetector)
        val normRes = V6NormalizedFrameConverter.convertRawYuvToNormalizedBgr(rawFrame)
        val cameraNormalizedMat = Mat(normRes.height, normRes.width, CvType.CV_8UC3)
        cameraNormalizedMat.put(0, 0, normRes.bgrBytes)
        val cameraNormPng = encodeMatToPng(cameraNormalizedMat)
        addZipEntry(zos, "camera_normalized_rgb.png", cameraNormPng)

        // 3. camera_normalized_overlay.png & camera_overlay.png
        val cameraOverlayMat = cameraNormalizedMat.clone()
        val quad = bundle.payload.detectedQuad
        if (quad != null && quad.size == 4) {
            for (i in 0 until 4) {
                val p1 = Point(quad[i][0], quad[i][1])
                val p2 = Point(quad[(i + 1) % 4][0], quad[(i + 1) % 4][1])
                Imgproc.line(cameraOverlayMat, p1, p2, Scalar(0.0, 255.0, 0.0), 3)
            }
        }

        // Draw Pilot sample points
        for (p in bundle.payload.pilotDetails) {
            val center = Point(p.mappedCameraCenterX, p.mappedCameraCenterY)
            Imgproc.circle(cameraOverlayMat, center, 5, Scalar(255.0, 0.0, 255.0), -1)
        }

        // Draw cell centers
        for (c in bundle.payload.cellDetails) {
            val sample0 = c.mappedCameraSamples[0]
            val pt = Point(sample0.first, sample0.second)
            val color = when (c.status) {
                "CORRECT" -> Scalar(0.0, 255.0, 0.0) // Green
                "WRONG" -> Scalar(0.0, 0.0, 255.0)   // Red
                else -> Scalar(0.0, 255.0, 255.0)    // Yellow
            }
            Imgproc.circle(cameraOverlayMat, pt, 2, color, -1)
        }
        val cameraOverlayPng = encodeMatToPng(cameraOverlayMat)
        addZipEntry(zos, "camera_normalized_overlay.png", cameraOverlayPng)
        addZipEntry(zos, "camera_overlay.png", cameraOverlayPng)
        cameraOverlayMat.release()

        // 4. canonical_rgb.png & canonical_luma.png
        val canonicalRgbMat = Mat(1000, 1000, CvType.CV_8UC3)
        val hMat = Mat(3, 3, CvType.CV_64F)
        val imgToCanon = bundle.payload.imageToCanonicalHomography ?: doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        hMat.put(0, 0, *imgToCanon)
        Imgproc.warpPerspective(cameraNormalizedMat, canonicalRgbMat, hMat, Size(1000.0, 1000.0), Imgproc.INTER_NEAREST)

        val canonicalRgbPng = encodeMatToPng(canonicalRgbMat)
        addZipEntry(zos, "canonical_rgb.png", canonicalRgbPng)

        val canonicalLumaMat = Mat(1000, 1000, CvType.CV_8UC1)
        if (bundle.warpedLumaBytes != null && bundle.warpedLumaBytes.size == 1000 * 1000) {
            canonicalLumaMat.put(0, 0, bundle.warpedLumaBytes)
        } else {
            Imgproc.cvtColor(canonicalRgbMat, canonicalLumaMat, Imgproc.COLOR_BGR2GRAY)
        }
        val canonicalLumaPng = encodeMatToPng(canonicalLumaMat)
        addZipEntry(zos, "canonical_luma.png", canonicalLumaPng)

        // 4. canonical_overlay.png
        val canonicalOverlayMat = canonicalRgbMat.clone()
        // Draw grid boundaries [200, 200, 800, 800]
        Imgproc.rectangle(canonicalOverlayMat, Point(200.0, 200.0), Point(800.0, 800.0), Scalar(255.0, 255.0, 255.0), 2)

        for (c in bundle.payload.cellDetails) {
            val cx = c.canonicalCenterX
            val cy = c.canonicalCenterY
            val x1 = cx - 15.0
            val y1 = cy - 15.0
            val x2 = cx + 15.0
            val y2 = cy + 15.0

            when (c.status) {
                "CORRECT" -> {
                    Imgproc.rectangle(canonicalOverlayMat, Point(x1, y1), Point(x2, y2), Scalar(0.0, 255.0, 0.0), 1)
                }
                "WRONG" -> {
                    Imgproc.rectangle(canonicalOverlayMat, Point(x1, y1), Point(x2, y2), Scalar(0.0, 0.0, 255.0), 2)
                    Imgproc.line(canonicalOverlayMat, Point(x1, y1), Point(x2, y2), Scalar(0.0, 0.0, 255.0), 1)
                }
                "UNCERTAIN" -> {
                    Imgproc.rectangle(canonicalOverlayMat, Point(x1, y1), Point(x2, y2), Scalar(0.0, 255.0, 255.0), 2)
                    Imgproc.line(canonicalOverlayMat, Point(x1, y2), Point(x2, y1), Scalar(0.0, 255.0, 255.0), 1)
                }
            }
        }
        val canonicalOverlayPng = encodeMatToPng(canonicalOverlayMat)
        addZipEntry(zos, "canonical_overlay.png", canonicalOverlayPng)
        canonicalOverlayMat.release()

        // 5. Pilot Images (pilot_BLACK.png, pilot_WHITE.png, pilot_RED.png, pilot_BLUE.png)
        val pilotBBoxes = mapOf(
            "BLACK" to Pair(270.0..330.0, 90.0..150.0),
            "WHITE" to Pair(350.0..410.0, 90.0..150.0),
            "RED" to Pair(590.0..650.0, 90.0..150.0),
            "BLUE" to Pair(670.0..730.0, 90.0..150.0)
        )

        for ((name, rects) in pilotBBoxes) {
            val rx = rects.first
            val ry = rects.second
            val rect = Rect(rx.start.toInt(), ry.start.toInt(), rx.endInclusive.toInt(), ry.endInclusive.toInt())
            val roiMat = Mat(canonicalRgbMat, org.opencv.core.Rect(rect.left, rect.top, rect.width(), rect.height()))
            val scaledRoi = Mat()
            Imgproc.resize(roiMat, scaledRoi, Size(240.0, 240.0), 0.0, 0.0, Imgproc.INTER_NEAREST)
            val pilotPng = encodeMatToPng(scaledRoi)
            addZipEntry(zos, "pilot_$name.png", pilotPng)
            scaledRoi.release()
            roiMat.release()
        }

        cameraNormalizedMat.release()
        canonicalRgbMat.release()
        canonicalLumaMat.release()
        hMat.release()
    }

    private fun encodeMatToPng(mat: Mat): ByteArray {
        val buf = MatOfByte()
        Imgcodecs.imencode(".png", mat, buf)
        val bytes = buf.toArray()
        buf.release()
        return bytes
    }
}
