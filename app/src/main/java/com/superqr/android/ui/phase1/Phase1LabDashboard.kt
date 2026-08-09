package com.superqr.android.ui.phase1

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LabBackground = Color(0xFF080B11)
private val LabSurface = Color(0xFF121823)
private val LabSurfaceHigh = Color(0xFF1A2230)
private val LabBorder = Color(0xFF2A3547)
private val LabText = Color(0xFFF1F5FB)
private val LabMuted = Color(0xFF9BA9BC)
private val LabAccent = Color(0xFF67C7FF)
private val LabGood = Color(0xFF65D88A)
private val LabWarn = Color(0xFFFFC857)
private val LabBad = Color(0xFFFF7070)

private data class Metric(
    val label: String,
    val value: String,
    val detail: String,
    val tint: Color = LabText,
)

@Composable
fun Phase1LabDashboard(
    alignmentPreview: Phase1AnalysisPreview,
    status: Phase1RunSnapshot,
    cameraState: String,
    running: Boolean,
    cameraPermission: Boolean,
    onToggleCamera: () -> Unit,
    onNewCampaign: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val synchronized = status.syncStatus.contains("LOCKED")
    val stateTint = when {
        status.senderState == "RUNNING" && synchronized -> LabGood
        status.senderState == "READY" || status.senderState == "DONE" -> LabWarn
        status.lastFailure != null || cameraState.startsWith("ERROR") -> LabBad
        else -> LabMuted
    }
    Scaffold(
        modifier = modifier,
        containerColor = LabBackground,
        topBar = {
            Surface(modifier = Modifier.statusBarsPadding(), color = LabSurface, tonalElevation = 0.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Physical PHY Lab", color = LabText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("V7 optical benchmark • lab-only", color = LabMuted, fontSize = 12.sp)
                    }
                    StatusPill(status.senderState, stateTint)
                }
            }
        },
        bottomBar = {
            Surface(color = LabSurface, shadowElevation = 12.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onToggleCamera,
                        enabled = cameraPermission,
                        modifier = Modifier.weight(1.25f).heightIn(min = 48.dp),
                    ) { Text(if (running) "Stop camera" else "Start camera") }
                    OutlinedButton(
                        onClick = onNewCampaign,
                        enabled = !running,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("New run") }
                    OutlinedButton(
                        onClick = onShare,
                        enabled = status.analyzedFrames > 0,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("Share") }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 760.dp || maxWidth > maxHeight
            if (wide) {
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PreviewPanel(alignmentPreview, cameraState, Modifier.weight(1.18f).fillMaxHeight())
                    LabDetails(status, stateTint, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PreviewPanel(
                        alignmentPreview, cameraState,
                        Modifier.fillMaxWidth().heightIn(min = 220.dp).weight(0.43f),
                    )
                    LabDetails(status, stateTint, Modifier.fillMaxWidth().weight(0.57f))
                }
            }
        }
    }
}

@Composable
private fun PreviewPanel(
    preview: Phase1AnalysisPreview,
    cameraState: String,
    modifier: Modifier,
) {
    val geometry = preview.geometry
    val tint = framingTint(geometry.status)
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.clip(shape).background(Color.Black).border(1.dp, tint.copy(alpha = 0.65f), shape),
    ) {
        preview.bitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Exact ImageAnalysis luma frame",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        AnalysisGeometryOverlay(geometry, Modifier.fillMaxSize())
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
            color = tint.copy(alpha = 0.94f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    geometry.status.label,
                    color = Color(0xFF061019),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                )
                Text(
                    if (preview.measuring) "MEASURING • PREVIEW FROZEN" else geometry.detail,
                    color = Color(0xFF061019).copy(alpha = 0.78f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (geometry.frameWidth > 0 && geometry.frameHeight > 0) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 82.dp),
                color = Color.Black.copy(alpha = 0.76f),
                shape = RoundedCornerShape(7.dp),
            ) {
                Text(
                    "ANALYSIS ${geometry.frameWidth}×${geometry.frameHeight} • FULL FRAME • NO CROP",
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 82.dp),
                color = Color.Black.copy(alpha = 0.76f),
                shape = RoundedCornerShape(7.dp),
            ) {
                val margin = geometry.minimumMarginPx?.let { " • ${it.toInt()} px margin" } ?: ""
                Text(
                    "${geometry.visibleFinders} / 4 FINDERS$margin",
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = tint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            color = Color.Black.copy(alpha = 0.68f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                cameraState,
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                color = if (cameraState.startsWith("ERROR")) LabBad else Color.White,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AnalysisGeometryOverlay(geometry: Phase1FramingGeometry, modifier: Modifier = Modifier) {
    if (geometry.frameWidth <= 0 || geometry.frameHeight <= 0) return
    Canvas(modifier) {
        val fit = Phase1FrameFit.calculate(geometry.frameWidth, geometry.frameHeight, size.width, size.height)
        val scale = fit.scale
        val imageWidth = fit.renderedWidth
        val imageHeight = fit.renderedHeight
        val offsetX = fit.offsetX
        val offsetY = fit.offsetY
        fun map(point: Phase1FramePoint): androidx.compose.ui.geometry.Offset {
            val mapped = fit.map(point)
            return androidx.compose.ui.geometry.Offset(mapped.x, mapped.y)
        }
        fun path(points: List<Phase1FramePoint>): Path? {
            if (points.size != 4) return null
            return Path().apply {
                val first = map(points.first())
                moveTo(first.x, first.y)
                points.drop(1).forEach { point ->
                    val mapped = map(point)
                    lineTo(mapped.x, mapped.y)
                }
                close()
            }
        }

        val safe = geometry.safeInsetPx * scale
        if (safe > 0f && imageWidth > safe * 2f && imageHeight > safe * 2f) {
            drawRect(
                color = Color.White.copy(alpha = 0.52f),
                topLeft = androidx.compose.ui.geometry.Offset(offsetX + safe, offsetY + safe),
                size = androidx.compose.ui.geometry.Size(imageWidth - safe * 2f, imageHeight - safe * 2f),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
        }
        geometry.candidateQuad?.let { candidate ->
            path(candidate)?.let {
                drawPath(
                    it, LabWarn.copy(alpha = 0.9f),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
                )
            }
        }
        geometry.carrierQuad?.let { carrier ->
            path(carrier)?.let { drawPath(it, LabGood, style = Stroke(width = 2.5.dp.toPx())) }
        }
        geometry.finderQuads.forEach { finder ->
            path(finder)?.let {
                drawPath(it, LabGood.copy(alpha = 0.18f))
                drawPath(it, LabGood, style = Stroke(width = 2.5.dp.toPx()))
            }
        }
        geometry.finderCenters.forEach { center ->
            drawCircle(
                color = if (geometry.status == Phase1FramingStatus.GOOD) LabGood else LabWarn,
                radius = 5.dp.toPx(),
                center = map(center),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        drawRect(
            Color.White.copy(alpha = 0.35f),
            topLeft = androidx.compose.ui.geometry.Offset(offsetX, offsetY),
            size = androidx.compose.ui.geometry.Size(imageWidth, imageHeight),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

private fun framingTint(status: Phase1FramingStatus): Color = when (status) {
    Phase1FramingStatus.GOOD -> LabGood
    Phase1FramingStatus.MOVE_BACK -> LabWarn
    Phase1FramingStatus.NOT_FOUND -> LabBad
    Phase1FramingStatus.ALIGNING -> LabAccent
}

@Composable
private fun LabDetails(status: Phase1RunSnapshot, stateTint: Color, modifier: Modifier) {
    val metrics = listOf(
        Metric("Analyzed / scored", "${status.analyzedFrames} / ${status.observations}", "camera frames / PHY frames"),
        Metric("Unique frames", "${status.uniqueFrames} / ${status.expectedFrames}", "%.0f%% complete".format(status.progress * 100.0), LabAccent),
        Metric("BER", "%.3f%%".format(status.bitErrorRate * 100.0), "non-erased bits", if (status.bitErrorRate == 0.0) LabGood else LabWarn),
        Metric("Erasure rate", "%.3f%%".format(status.erasureRate * 100.0), "all observed bits", if (status.erasureRate == 0.0) LabGood else LabWarn),
        Metric("Raw valid", "%.1f%%".format(status.rawValidYield * 100.0), "exact frames", LabAccent),
        Metric("Inner-FEC valid", "%.1f%%".format(status.innerFecYield * 100.0), "simulated RS yield", LabAccent),
        Metric("Pipeline", "%.1f ms".format(status.p95PipelineMs), "p95 • mean %.1f ms".format(status.meanPipelineMs)),
        Metric("Camera rate", "%.1f fps".format(status.cameraFps), "${status.captureWidth}×${status.captureHeight}"),
        Metric("Goodput", "%.2f KiB/s".format(status.goodputKibS), "unique inner-FEC-valid data", LabGood),
        Metric("Run token", status.runId, "campaign ${status.campaignId.take(8)}", stateTint),
    )
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { RunCard(status, stateTint) }
        items(metrics.chunked(2)) { rowMetrics ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowMetrics.forEach { metric -> MetricCard(metric, Modifier.weight(1f)) }
                if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { DiagnosticsCard(status) }
        item {
            Text(
                "AUTO follows the optical profile and run state. Measurements are recorded only while synchronized RUNNING frames are visible.",
                color = LabMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun RunCard(status: Phase1RunSnapshot, tint: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = LabSurfaceHigh),
        border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status.profileName, color = LabText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("RUN ${status.runId}", color = tint, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Text("${status.senderState} • ${displaySyncStatus(status.syncStatus)}", color = tint, fontWeight = FontWeight.Medium)
            LinearProgressIndicator(
                progress = { status.progress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(7.dp),
                color = tint,
                trackColor = LabBorder,
            )
        }
    }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = LabSurface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(metric.label.uppercase(), color = LabMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
            Text(metric.value, color = metric.tint, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(metric.detail, color = LabMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DiagnosticsCard(status: Phase1RunSnapshot) {
    val failure = status.lastFailure
    Card(colors = CardDefaults.cardColors(containerColor = if (failure == null) LabSurface else LabBad.copy(alpha = 0.10f))) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ACQUISITION", color = LabMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
            Text("Geometry  ${status.geometryState}", color = LabText, fontSize = 13.sp)
            Text("Sync  ${status.syncStatus}", color = LabText, fontSize = 13.sp)
            if (failure != null) Text("Last failure  $failure", color = LabBad, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (failure == "V7_NO_COMPLETE_CARRIER_GEOMETRY") {
                Text(
                    "Frame all four corner squares. Move the phone back or select the Desktop 600 px marker.",
                    color = LabWarn,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            if (status.failureSummary.isNotBlank()) Text(status.failureSummary, color = LabMuted, fontSize = 11.sp)
        }
    }
}

private fun displaySyncStatus(status: String): String = when (status) {
    "V7_NO_COMPLETE_CARRIER_GEOMETRY" -> "FRAME ALL 4 CORNER MARKERS"
    else -> status
}

@Composable
private fun StatusPill(label: String, tint: Color) {
    Surface(color = tint.copy(alpha = 0.14f), shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.55f))) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
