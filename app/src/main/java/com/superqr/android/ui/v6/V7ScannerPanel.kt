package com.superqr.android.ui.v6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superqr.android.vision.v6.model.V6StaticResult
import com.superqr.android.vision.v7_capacity_lab.V7CapacityLabReceiver
import com.superqr.android.vision.v7_capacity_lab.V7ChannelMetrics
import com.superqr.android.vision.v7_capacity_lab.V7HighDensitySampler

@Composable
fun V7ScannerPanel(
    result: V6StaticResult?,
    v7LastMetrics: V7ChannelMetrics.FrameMetrics?,
    v7ProfileName: String,
    v7ExpectedFrame: Int,
    v7CalibrationLabel: String,
    v7ConfidenceSummary: String,
    v7SamplerMode: V7HighDensitySampler.ProbeMode,
    frameCount: Int,
    v7AnalysisMs: Double,
    v7CameraFps: Double,
    v7AnalysisFps: Double,
    v7Receiver: V7CapacityLabReceiver?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Row 1: profile + state
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "V7 Lab: ${v7ProfileName.takeLast(30)}",
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                val state = result?.trackingState ?: "—"
                val stateColor = when (state) {
                    "TRACKING", "LOCKED" -> Color(0xFF4CAF50)
                    "ACQUIRING", "REACQUIRING" -> Color(0xFFFFB74D)
                    else -> Color.White.copy(alpha = 0.6f)
                }
                Text(state, color = stateColor, fontSize = 10.sp)
            }

            // Row 2: cal + sampler
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cal: $v7CalibrationLabel | $v7SamplerMode", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
                Text("Exp frame: $v7ExpectedFrame | #$frameCount", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
            }

            // Row 3: real camera FPS + analysis FPS + analysis ms
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cam: ${"%.1f".format(v7CameraFps)} fps", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
                Text("Ana: ${"%.1f".format(v7AnalysisFps)} fps", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
                Text("${"%.1f".format(v7AnalysisMs)} ms", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
            }

            // Row 4: Error metrics
            val m = v7LastMetrics
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "SER: ${if (m != null) "%.1f%%".format(m.serAll * 100) else "—"}",
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    "C-SER: ${if (m != null && !m.conditionalSer.isNaN()) "%.1f%%".format(m.conditionalSer * 100) else "—"}",
                    color = Color.White, fontSize = 10.sp
                )
                Text(
                    "BER: ${if (m != null && !m.berAccepted.isNaN()) "%.3f".format(m.berAccepted) else "—"}",
                    color = Color.White, fontSize = 10.sp
                )
            }

            // Row 5: erasure + confidence + accuracy
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Era: ${if (m != null) "%.1f%%".format(m.erasureRate * 100) else "—"}",
                    color = if (m != null && m.erasureRate > 0.3) Color(0xFFFFB74D) else Color.White,
                    fontSize = 10.sp
                )
                Text("Conf: $v7ConfidenceSummary", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
                Text(
                    "Acc: ${if (m != null) "${m.correctSymbols}/${m.totalCells}" else "—"}",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp
                )
            }

            // Buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                OutlinedButton(
                    onClick = { v7Receiver?.retreatExpectedFrame() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                ) { Text("◀", fontSize = 10.sp) }
                OutlinedButton(
                    onClick = { v7Receiver?.advanceExpectedFrame() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                ) { Text("▶", fontSize = 10.sp) }
                OutlinedButton(
                    onClick = { v7Receiver?.resetExpectedFrame() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                ) { Text("RstIdx", fontSize = 8.sp) }
                OutlinedButton(
                    onClick = { v7Receiver?.resetCalibration() },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                ) { Text("RstCal", fontSize = 8.sp) }
            }
        }
    }
}
