package com.superqr.android.ui.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superqr.android.ui.v6.V6PreviewOverlayGeometry
import com.superqr.android.vision.v7.transport.V7OpticalProfile
import com.superqr.android.vision.v7.transport.V7OpticalProfiles
import com.superqr.android.vision.v7.transport.V7SessionAccumulator
import com.superqr.android.vision.v7.transport.V7TransportDiagnostics
import com.superqr.android.vision.v7_capacity_lab.V7HighDensitySampler

internal data class V7DebugUiState(
    val profile: V7OpticalProfile,
    val cameraState: String,
    val trackingState: String,
    val classificationSource: String,
    val borderFound: Boolean,
    val orientationResolved: Boolean,
    val detectorMs: Double,
    val calibrated: Int,
    val validSamples: Int,
    val confident: Int,
    val erasures: Int,
    val analyzed: Int,
    val skippedErasures: Int,
    val headerValid: Int,
    val headerInvalid: Int,
    val packAttempts: Int,
    val crcAttempts: Int,
    val crcPass: Int,
    val crcFail: Int,
    val parserRejects: Int,
    val temporalRecoveredFrames: Int,
    val cameraFps: Double,
    val analysisFps: Double,
    val analysisMs: Double,
    val unique: Int,
    val duplicates: Int,
    val conflicts: Int,
    val focusState: String,
    val forcedProfile: Int,
    val samplerMode: V7HighDensitySampler.ProbeMode,
    val latestTransport: V7TransportDiagnostics?,
)

@Composable
internal fun V7ScannerOverlay(
    carrier: V6PreviewOverlayGeometry?,
    debug: V7DebugOverlayGeometry?,
    mode: V7DebugOverlayMode,
    guidance: String,
    guidanceColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension * .76f
            val left = (size.width - side) / 2
            val top = (size.height - side) / 2
            val corner = side * .10f
            val sw = 2.dp.toPx()
            val guide = Color.White.copy(alpha = .25f)
            listOf(
                Offset(left, top) to Offset(left + corner, top), Offset(left, top) to Offset(left, top + corner),
                Offset(left + side, top) to Offset(left + side - corner, top), Offset(left + side, top) to Offset(left + side, top + corner),
                Offset(left, top + side) to Offset(left + corner, top + side), Offset(left, top + side) to Offset(left, top + side - corner),
                Offset(left + side, top + side) to Offset(left + side - corner, top + side), Offset(left + side, top + side) to Offset(left + side, top + side - corner),
            ).forEach { drawLine(guide, it.first, it.second, sw) }

            if (carrier != null && carrier.outerQuad.size == 4) {
                val exact = if (carrier.isFresh) Color(0xFF7EE787) else Color(0xFFF2CC60)
                for (i in 0..3) drawLine(exact, carrier.outerQuad[i], carrier.outerQuad[(i + 1) % 4], 3.dp.toPx())
            }

            if (debug != null && mode != V7DebugOverlayMode.LIVE) {
                val gridAlpha = if (mode == V7DebugOverlayMode.GRID) .62f else .28f
                debug.gridSegments.forEach { drawLine(Color(0xFF7CB7FF).copy(alpha = gridAlpha), it.first, it.second, 1.dp.toPx()) }
            }

            when (mode) {
                V7DebugOverlayMode.LIVE, V7DebugOverlayMode.GRID -> Unit
                V7DebugOverlayMode.SAMPLES -> {
                    if (debug != null) {
                        drawPoints(debug.goodSamplePoints, PointMode.Points, Color(0xFF39D353), 2.4.dp.toPx())
                        drawPoints(debug.lowSamplePoints, PointMode.Points, Color(0xFFF2CC60), 3.0.dp.toPx())
                        drawPoints(debug.erasedSamplePoints, PointMode.Points, Color(0xFFFF5C5C), 3.6.dp.toPx())
                    }
                }
                V7DebugOverlayMode.CLASSIFY -> {
                    if (debug != null) {
                        val colors = listOf(
                            Color(0xFF343A46), Color.White, Color.Red, Color(0xFF18D45B),
                            Color.Blue, Color.Yellow, Color.Cyan, Color.Magenta,
                        )
                        debug.symbolPoints.forEachIndexed { idx, points ->
                            if (idx < colors.size) drawPoints(points, PointMode.Points, colors[idx], 4.0.dp.toPx())
                        }
                        drawPoints(debug.erasedSamplePoints, PointMode.Points, Color(0xFFFF5C5C), 4.8.dp.toPx())
                        drawPoints(debug.recoveredPoints, PointMode.Points, Color(0xFF00E5FF), 5.5.dp.toPx())
                    }
                }
                V7DebugOverlayMode.TRANSPORT -> {
                    if (debug != null) {
                        drawPoints(debug.headerPoints, PointMode.Points, Color(0xFF00E5FF), 5.0.dp.toPx())
                        drawPoints(debug.recoveredPoints, PointMode.Points, Color(0xFF39D353), 5.0.dp.toPx())
                        drawPoints(debug.erasedSamplePoints, PointMode.Points, Color(0xFFFF5C5C), 3.2.dp.toPx())
                    }
                }
            }
        }

        Surface(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 58.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = .72f),
        ) {
            Text(
                guidance,
                color = guidanceColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
internal fun V7ReceiveCard(
    profile: V7OpticalProfile,
    acc: V7SessionAccumulator,
    calibrated: Int,
    confident: Int,
    erasures: Int,
    lastFrame: Int,
    error: String?,
    crcPass: Int,
    crcFail: Int,
    skippedErasures: Int,
    temporalRecovered: Int,
    cameraFps: Double,
    analysisFps: Double,
    modifier: Modifier,
) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xE6141821))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(profile.label, color = Color(0xFF7CB7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("CAL $calibrated/${profile.colorCount}", color = if (calibrated == profile.colorCount) Color(0xFF7EE787) else Color(0xFFF2CC60), fontSize = 10.sp)
            }
            val total = acc.getTotalFrames(); val unique = acc.getUniqueFrames()
            Text(if (total > 0) "$unique / $total frames • ${acc.getMissingFramesCount()} missing" else "Waiting for CRC-valid frame", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (total > 0) LinearProgressIndicator(progress = { acc.getProgress().toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("Cells $confident/${profile.cellCount} confident • $erasures raw erased", color = Color.White.copy(alpha = .74f), fontSize = 10.sp)
            Text("CRC $crcPass pass • $crcFail fail • skipped $skippedErasures • recovered $temporalRecovered", color = Color.White.copy(alpha = .66f), fontSize = 10.sp)
            Text("Last ${if (lastFrame >= 0) lastFrame else "—"}${error?.let { " • $it" } ?: ""}", color = if (error == null) Color.White.copy(alpha = .6f) else Color(0xFFFF7B72), fontSize = 9.sp)
            Text("Camera ${"%.1f".format(cameraFps)} fps • analysis ${"%.1f".format(analysisFps)} fps", color = Color.White.copy(alpha = .55f), fontSize = 10.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun V7DebugPanel(
    ui: V7DebugUiState,
    overlayMode: V7DebugOverlayMode,
    frozen: Boolean,
    onOverlayMode: (V7DebugOverlayMode) -> Unit,
    onForceProfile: (Int) -> Unit,
    onSampler: (V7HighDensitySampler.ProbeMode) -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onCapture: () -> Unit,
    onShare: () -> Unit,
    onFreeze: () -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xF5141821))) {
        Column(Modifier.padding(12.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("LIVE DEBUG", color = Color(0xFF7CB7FF), fontWeight = FontWeight.Bold)
                    Text(if (frozen) "FROZEN SNAPSHOT" else "real CameraX geometry + V7 decisions", color = if (frozen) Color(0xFFF2CC60) else Color.White.copy(alpha = .55f), fontSize = 9.sp)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(V7DebugOverlayMode.LIVE, V7DebugOverlayMode.GRID, V7DebugOverlayMode.SAMPLES).forEach { mode ->
                    FilterChip(selected = overlayMode == mode, onClick = { onOverlayMode(mode) }, label = { Text(mode.name, fontSize = 8.sp) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(V7DebugOverlayMode.CLASSIFY, V7DebugOverlayMode.TRANSPORT).forEach { mode ->
                    FilterChip(selected = overlayMode == mode, onClick = { onOverlayMode(mode) }, label = { Text(mode.name, fontSize = 8.sp) })
                }
            }

            Text("Profile ${ui.profile.key} • id ${ui.profile.id} • ${ui.profile.grid}×${ui.profile.grid} • ${ui.profile.colorCount} colors • ${ui.profile.frameSize} B/frame", color = Color.White, fontSize = 10.sp)
            Text("Camera ${ui.cameraState} • tracking ${ui.trackingState} • ${ui.classificationSource}", color = Color.White, fontSize = 10.sp)
            Text("Geometry border ${ui.borderFound} • orientation ${ui.orientationResolved} • detector ${"%.2f".format(ui.detectorMs)} ms", color = Color.White.copy(alpha = .78f), fontSize = 10.sp)
            Text("Cells sampled ${ui.validSamples}/${ui.profile.cellCount} • confident ${ui.confident} • raw erased ${ui.erasures} • CAL ${ui.calibrated}/${ui.profile.colorCount}", color = Color.White, fontSize = 10.sp)

            val t = ui.latestTransport
            Text(
                "Transport analyzed ${ui.analyzed} • skipped-erasure ${ui.skippedErasures} • header ${ui.headerValid} ok/${ui.headerInvalid} bad • pack ${ui.packAttempts}",
                color = Color.White,
                fontSize = 10.sp,
            )
            Text(
                "CRC attempts ${ui.crcAttempts} • pass ${ui.crcPass} • fail ${ui.crcFail} • parser rejects ${ui.parserRejects}",
                color = Color.White,
                fontSize = 10.sp,
            )
            Text(
                "Temporal recovered ${ui.temporalRecoveredFrames} frames • last obs ${t?.temporalObservations ?: 0} • filled ${t?.temporalFilledCells ?: 0} • remaining ${t?.remainingErasures ?: ui.erasures}",
                color = if ((t?.temporalFilledCells ?: 0) > 0) Color(0xFF7EE787) else Color.White.copy(alpha = .75f),
                fontSize = 10.sp,
            )
            t?.header?.let { h -> Text("Header session ${h.sessionId} • frame ${h.frameId}/${h.totalFrames} • payload ${h.payloadLen}", color = Color(0xFF7CB7FF), fontSize = 10.sp) }
            t?.rejectionReason?.let { Text("Last reject: $it", color = Color(0xFFFFA657), fontSize = 9.sp) }

            Text("Unique ${ui.unique} • dup ${ui.duplicates} • conflicts ${ui.conflicts}", color = Color.White.copy(alpha = .75f), fontSize = 10.sp)
            Text("Camera ${"%.1f".format(ui.cameraFps)} fps • analysis ${"%.1f".format(ui.analysisFps)} fps • V7 ${"%.2f".format(ui.analysisMs)} ms", color = Color.White, fontSize = 10.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = ui.samplerMode == V7HighDensitySampler.ProbeMode.CENTER_1, onClick = { onSampler(V7HighDensitySampler.ProbeMode.CENTER_1) }, label = { Text("CENTER_1", fontSize = 9.sp) })
                FilterChip(selected = ui.samplerMode == V7HighDensitySampler.ProbeMode.CROSS_5, onClick = { onSampler(V7HighDensitySampler.ProbeMode.CROSS_5) }, label = { Text("CROSS_5", fontSize = 9.sp) })
            }

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = if (ui.forcedProfile < 0) "AUTO from marker" else V7OpticalProfiles.byId(ui.forcedProfile)?.label ?: "AUTO",
                    onValueChange = {}, readOnly = true, label = { Text("Profile detection") },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("AUTO from marker") }, onClick = { onForceProfile(-1); expanded = false })
                    V7OpticalProfiles.all.forEach { p -> DropdownMenuItem(text = { Text(p.label) }, onClick = { onForceProfile(p.id); expanded = false }) }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onCapture, modifier = Modifier.weight(1f)) { Text("Capture", fontSize = 9.sp) }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Share", fontSize = 9.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onFreeze, modifier = Modifier.weight(1f)) { Text(if (frozen) "Resume" else "Freeze", fontSize = 9.sp) }
                Button(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Export ZIP", fontSize = 9.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onLock, modifier = Modifier.weight(1f)) { Text("Focus & lock", fontSize = 9.sp) }
                Button(onClick = onUnlock, modifier = Modifier.weight(1f)) { Text("Unlock", fontSize = 9.sp) }
            }
            Text("Focus ${ui.focusState}", color = Color.White.copy(alpha = .65f), fontSize = 9.sp)
        }
    }
}
