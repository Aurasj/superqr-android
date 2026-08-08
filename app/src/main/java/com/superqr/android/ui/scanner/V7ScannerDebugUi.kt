package com.superqr.android.ui.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val detectorMs: Number,
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
    val crcCandidateAttempts: Int,
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
                val gridAlpha = if (mode == V7DebugOverlayMode.GRID) .68f else .24f
                debug.gridSegments.forEach {
                    drawLine(Color(0xFF7CB7FF).copy(alpha = gridAlpha), it.first, it.second, 1.dp.toPx())
                }
            }

            when (mode) {
                V7DebugOverlayMode.LIVE, V7DebugOverlayMode.GRID -> Unit
                V7DebugOverlayMode.SAMPLES -> if (debug != null) {
                    drawPoints(debug.goodSamplePoints, PointMode.Points, Color(0xFF39D353), 2.4.dp.toPx())
                    drawPoints(debug.lowSamplePoints, PointMode.Points, Color(0xFFF2CC60), 3.0.dp.toPx())
                    drawPoints(debug.erasedSamplePoints, PointMode.Points, Color(0xFFFF5C5C), 3.6.dp.toPx())
                }
                V7DebugOverlayMode.CLASSIFY -> if (debug != null) {
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
                V7DebugOverlayMode.TRANSPORT -> if (debug != null) {
                    drawPoints(debug.headerPoints, PointMode.Points, Color(0xFF00E5FF), 5.0.dp.toPx())
                    drawPoints(debug.recoveredPoints, PointMode.Points, Color(0xFF39D353), 5.0.dp.toPx())
                    drawPoints(debug.erasedSamplePoints, PointMode.Points, Color(0xFFFF5C5C), 3.2.dp.toPx())
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
            Text("Last ${if (lastFrame >= 0) lastFrame else "—"}${error?.let { " • $it" } ?: ""}", color = if (error == null) Color.White.copy(alpha = .6f) else Color(0xFFFF7B72), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Camera ${"%.1f".format(cameraFps)} fps • analysis ${"%.1f".format(analysisFps)} fps", color = Color.White.copy(alpha = .55f), fontSize = 10.sp)
        }
    }
}

@Composable
internal fun V7OverlayStatusBar(
    mode: V7DebugOverlayMode,
    frozen: Boolean,
    confident: Int,
    total: Int,
    crcPass: Int,
    crcFail: Int,
    onOpenDebug: () -> Unit,
    onLive: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xE8141821),
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${if (frozen) "FROZEN • " else ""}${mode.name} • $confident/$total • CRC $crcPass/$crcFail",
                color = Color.White,
                fontSize = 9.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onOpenDebug, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Debug", fontSize = 9.sp) }
            TextButton(onClick = onLive, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Live", fontSize = 9.sp) }
        }
    }
}

private enum class DebugPage { OPTICAL, TRANSPORT, TOOLS }

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
    var page by remember { mutableStateOf(DebugPage.OPTICAL) }

    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xF5141821))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("LIVE DEBUG", color = Color(0xFF7CB7FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        if (frozen) "FROZEN SNAPSHOT" else "overlay stays active after Close",
                        color = if (frozen) Color(0xFFF2CC60) else Color.White.copy(alpha = .55f),
                        fontSize = 8.sp,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = onClose) { Text("Close", fontSize = 10.sp) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                V7DebugOverlayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = overlayMode == mode,
                        onClick = { onOverlayMode(mode) },
                        label = { Text(mode.name, fontSize = 7.sp) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DebugPage.entries.forEach { p ->
                    FilterChip(
                        selected = page == p,
                        onClick = { page = p },
                        label = { Text(p.name, fontSize = 8.sp) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = .10f))

            when (page) {
                DebugPage.OPTICAL -> OpticalPage(ui, onSampler)
                DebugPage.TRANSPORT -> TransportPage(ui)
                DebugPage.TOOLS -> ToolsPage(ui, frozen, onForceProfile, onLock, onUnlock, onCapture, onShare, onFreeze, onExport)
            }
        }
    }
}

@Composable
private fun OpticalPage(ui: V7DebugUiState, onSampler: (V7HighDensitySampler.ProbeMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetricRow("Profile", "${ui.profile.grid}×${ui.profile.grid} • ${ui.profile.colorCount}c • id ${ui.profile.id}")
        MetricRow("Camera", ui.cameraState)
        MetricRow("Tracking", "${ui.trackingState} • ${ui.classificationSource}")
        MetricRow("Geometry", "border ${yn(ui.borderFound)} • orient ${yn(ui.orientationResolved)} • ${"%.1f".format(ui.detectorMs.toDouble())} ms")
        MetricRow("Samples", "${ui.validSamples}/${ui.profile.cellCount} • confident ${ui.confident} • erased ${ui.erasures}")
        MetricRow("Calibration", "${ui.calibrated}/${ui.profile.colorCount}")
        MetricRow("Performance", "cam ${"%.1f".format(ui.cameraFps)} • analysis ${"%.1f".format(ui.analysisFps)} • V7 ${"%.1f".format(ui.analysisMs)} ms")
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = ui.samplerMode == V7HighDensitySampler.ProbeMode.CENTER_1, onClick = { onSampler(V7HighDensitySampler.ProbeMode.CENTER_1) }, label = { Text("CENTER_1", fontSize = 8.sp) })
            FilterChip(selected = ui.samplerMode == V7HighDensitySampler.ProbeMode.CROSS_5, onClick = { onSampler(V7HighDensitySampler.ProbeMode.CROSS_5) }, label = { Text("CROSS_5", fontSize = 8.sp) })
        }
    }
}

@Composable
private fun TransportPage(ui: V7DebugUiState) {
    val t = ui.latestTransport
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetricRow("Analyzed", "${ui.analyzed} • skipped ${ui.skippedErasures}")
        MetricRow("Header", "${ui.headerValid} ok • ${ui.headerInvalid} bad")
        MetricRow("Packing", "${ui.packAttempts} frames")
        MetricRow("CRC", "${ui.crcAttempts} frames • ${ui.crcCandidateAttempts} candidates")
        MetricRow("Result", "${ui.crcPass} pass • ${ui.crcFail} fail • ${ui.parserRejects} parser")
        MetricRow("Temporal", "${ui.temporalRecoveredFrames} pass • obs ${t?.temporalObservations ?: 0} • fill ${t?.temporalFilledCells ?: 0} • override ${t?.temporalOverriddenCells ?: 0}")
        MetricRow("Remaining", "${t?.remainingErasures ?: ui.erasures} erasures")
        t?.header?.let { h -> MetricRow("Current", "s${h.sessionId} • f${h.frameId}/${h.totalFrames} • ${h.payloadLen} B") }
        MetricRow("Candidate", t?.candidatePassed ?: "—", if (t?.crcPassed == true) Color(0xFF7EE787) else Color.White.copy(alpha = .75f))
        MetricRow("CRC pair", crcPair(t))
        MetricRow("Session", "unique ${ui.unique} • dup ${ui.duplicates} • conflicts ${ui.conflicts}")
        Text(
            "Reject: ${t?.rejectionReason ?: "—"}",
            color = if (t?.rejectionReason == null) Color.White.copy(alpha = .55f) else Color(0xFFFFA657),
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsPage(
    ui: V7DebugUiState,
    frozen: Boolean,
    onForceProfile: (Int) -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onCapture: () -> Unit,
    onShare: () -> Unit,
    onFreeze: () -> Unit,
    onExport: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = if (ui.forcedProfile < 0) "AUTO from marker" else V7OpticalProfiles.byId(ui.forcedProfile)?.label ?: "AUTO",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Profile detection", fontSize = 8.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("AUTO from marker") }, onClick = { onForceProfile(-1); expanded = false })
                V7OpticalProfiles.all.forEach { p ->
                    DropdownMenuItem(text = { Text(p.label) }, onClick = { onForceProfile(p.id); expanded = false })
                }
            }
        }

        MetricRow("Focus", ui.focusState)
        TwoButtons("Focus & lock", onLock, "Unlock", onUnlock)
        TwoButtons("Capture", onCapture, "Share", onShare, outlined = true)
        TwoButtons(if (frozen) "Resume" else "Freeze", onFreeze, "Export ZIP", onExport, outlined = false)
        Text(
            "ZIP now includes events.csv, frame-summary.csv, calibration.csv and richer CRC diagnostics.",
            color = Color.White.copy(alpha = .55f),
            fontSize = 8.sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth().height(21.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(alpha = .52f), fontSize = 8.sp, modifier = Modifier.width(78.dp), maxLines = 1)
        Text(
            value,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TwoButtons(
    left: String,
    onLeft: () -> Unit,
    right: String,
    onRight: () -> Unit,
    outlined: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (outlined) {
            OutlinedButton(onClick = onLeft, modifier = Modifier.weight(1f)) { Text(left, fontSize = 9.sp) }
            OutlinedButton(onClick = onRight, modifier = Modifier.weight(1f)) { Text(right, fontSize = 9.sp) }
        } else {
            Button(onClick = onLeft, modifier = Modifier.weight(1f)) { Text(left, fontSize = 9.sp) }
            Button(onClick = onRight, modifier = Modifier.weight(1f)) { Text(right, fontSize = 9.sp) }
        }
    }
}

private fun yn(value: Boolean): String = if (value) "yes" else "no"

private fun crcPair(t: V7TransportDiagnostics?): String {
    val received = t?.receivedCrc32 ?: return "—"
    val computed = t.computedCrc32 ?: return "0x%08X / —".format(received)
    return "0x%08X / 0x%08X".format(received, computed)
}
