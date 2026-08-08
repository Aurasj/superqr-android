package com.superqr.android.ui.scanner

import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.OutputTransform
import androidx.compose.ui.geometry.Offset
import com.superqr.android.vision.v6.model.V6StaticResult
import com.superqr.android.vision.v7.transport.V7DebugSnapshot
import com.superqr.android.vision.v7.transport.V7OpticalProfile
import com.superqr.android.vision.v7.transport.V7OpticalProfiles
import com.superqr.android.vision.v7.transport.V7Transport
import com.superqr.android.vision.v7_capacity_lab.V7HighDensitySampler
import com.superqr.android.vision.v7_capacity_lab.V7SoftClassifier
import kotlin.math.abs

/** Debug overlays shown on top of the real CameraX PreviewView. */
enum class V7DebugOverlayMode { LIVE, GRID, SAMPLES, CLASSIFY, TRANSPORT }

data class V7DebugOverlayGeometry(
    val gridSegments: List<Pair<Offset, Offset>> = emptyList(),
    val goodSamplePoints: List<Offset> = emptyList(),
    val lowSamplePoints: List<Offset> = emptyList(),
    val erasedSamplePoints: List<Offset> = emptyList(),
    val symbolPoints: List<List<Offset>> = emptyList(),
    val recoveredPoints: List<Offset> = emptyList(),
    val headerPoints: List<Offset> = emptyList(),
)

/**
 * Builds debug geometry from the exact production canonical payload bbox,
 * homography, rectangular grid pitch and CROSS_5 offsets. In SAMPLES mode each
 * individual physical probe is colored from its own validity bit.
 */
object V7DebugOverlayMapper {
    fun map(
        result: V6StaticResult,
        source: OutputTransform,
        target: OutputTransform,
        profile: V7OpticalProfile,
        snapshot: V7DebugSnapshot?,
    ): V7DebugOverlayGeometry? {
        val h = result.finalInvHomography ?: return null
        if (h.size != 9) return null
        val transform = try { CoordinateTransform(source, target) } catch (_: Throwable) { return null }

        val bbox = V7OpticalProfiles.payloadBbox
        val x0 = bbox[0]; val y0 = bbox[1]; val x1 = bbox[2]; val y1 = bbox[3]
        val cellW = (x1 - x0) / profile.grid
        val cellH = (y1 - y0) / profile.grid
        val grid = ArrayList<Pair<Offset, Offset>>(2 * (profile.grid + 1))

        for (i in 0..profile.grid) {
            val x = x0 + i * cellW
            mapCanonical(h, transform, x, y0)?.let { a ->
                mapCanonical(h, transform, x, y1)?.let { b -> grid.add(a to b) }
            }
            val y = y0 + i * cellH
            mapCanonical(h, transform, x0, y)?.let { a ->
                mapCanonical(h, transform, x1, y)?.let { b -> grid.add(a to b) }
            }
        }

        if (snapshot == null || snapshot.profileId != profile.id || snapshot.symbols.size != profile.cellCount) {
            return V7DebugOverlayGeometry(gridSegments = grid)
        }

        val good = ArrayList<Offset>()
        val low = ArrayList<Offset>()
        val erased = ArrayList<Offset>()
        val recovered = ArrayList<Offset>()
        val bySymbol = MutableList(profile.colorCount) { ArrayList<Offset>() }
        val header = ArrayList<Offset>()
        val headerCells = (V7Transport.HEADER_SIZE * 8 + profile.bitsPerCell - 1) / profile.bitsPerCell
        val ox = cellW * 0.15 / 2.0
        val oy = cellH * 0.15 / 2.0
        val stabilizedSymbols = snapshot.stabilizedSymbols

        for (idx in 0 until profile.cellCount) {
            val row = idx / profile.grid
            val col = idx % profile.grid
            val cx = x0 + (col + 0.5) * cellW
            val cy = y0 + (row + 0.5) * cellH
            val center = mapCanonical(h, transform, cx, cy) ?: continue

            val symbol = snapshot.symbols[idx].toInt()
            val cellValid = snapshot.validMask.getOrNull(idx)?.toInt() == 1
            val probeMask = snapshot.probeValidityMask.getOrNull(idx)?.toInt()?.and(0xFF) ?: 0
            val best = snapshot.bestDistances.getOrNull(idx) ?: Int.MAX_VALUE
            val second = snapshot.secondBestDistances.getOrNull(idx) ?: Int.MAX_VALUE
            val ratio = if (second in 1 until Int.MAX_VALUE) best.toDouble() / second.toDouble() else 1.0
            val cellErased = !cellValid || symbol == V7SoftClassifier.ERASURE_MARKER.toInt()
            val cellLow = !cellErased && (ratio > 0.72 || best > 28_000)

            if (idx < headerCells) header.add(center)
            if (symbol in 0 until profile.colorCount) bySymbol[symbol].add(center)
            if (stabilizedSymbols != null && symbol == V7SoftClassifier.ERASURE_MARKER.toInt()) {
                val stable = stabilizedSymbols.getOrNull(idx)?.toInt() ?: -1
                if (stable in 0 until profile.colorCount) recovered.add(center)
            }

            if (snapshot.probeMode == V7HighDensitySampler.ProbeMode.CENTER_1) {
                when {
                    (probeMask and 0x01) == 0 || cellErased -> erased.add(center)
                    cellLow -> low.add(center)
                    else -> good.add(center)
                }
            } else {
                val probes = arrayOf(
                    0.0 to 0.0,
                    -ox to -oy,
                    ox to -oy,
                    -ox to oy,
                    ox to oy,
                )
                probes.forEachIndexed { p, (dx, dy) ->
                    val point = mapCanonical(h, transform, cx + dx, cy + dy) ?: return@forEachIndexed
                    val physicalProbeValid = (probeMask and (1 shl p)) != 0
                    when {
                        !physicalProbeValid -> erased.add(point)
                        cellErased -> erased.add(point)
                        cellLow -> low.add(point)
                        else -> good.add(point)
                    }
                }
            }
        }

        return V7DebugOverlayGeometry(
            gridSegments = grid,
            goodSamplePoints = good,
            lowSamplePoints = low,
            erasedSamplePoints = erased,
            symbolPoints = bySymbol,
            recoveredPoints = recovered,
            headerPoints = header,
        )
    }

    private fun mapCanonical(
        h: DoubleArray,
        transform: CoordinateTransform,
        x: Double,
        y: Double,
    ): Offset? {
        val den = h[6] * x + h[7] * y + h[8]
        if (!den.isFinite() || abs(den) < 1e-9) return null
        val imageX = (h[0] * x + h[1] * y + h[2]) / den
        val imageY = (h[3] * x + h[4] * y + h[5]) / den
        if (!imageX.isFinite() || !imageY.isFinite()) return null
        val points = floatArrayOf(imageX.toFloat(), imageY.toFloat())
        return try {
            transform.mapPoints(points)
            if (points[0].isFinite() && points[1].isFinite()) Offset(points[0], points[1]) else null
        } catch (_: Throwable) {
            null
        }
    }
}
