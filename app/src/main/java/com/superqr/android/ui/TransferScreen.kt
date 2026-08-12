package com.superqr.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.superqr.android.transfer.ProductionQrContract
import com.superqr.android.transfer.TransferCameraManager
import com.superqr.android.transfer.TransferReceiveStatus
import com.superqr.android.transfer.TransferReceiverSession
import java.util.Locale
import java.util.concurrent.ExecutorService

@Composable
fun TransferScreen(
    analysisExecutor: ExecutorService,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
    }
    val camera = remember { TransferCameraManager(context, analysisExecutor) }
    val receiver = remember { TransferReceiverSession(context) }
    val cameraStatus by camera.status.collectAsState()
    val cameraFps by camera.cameraFps.collectAsState()
    val decodeMs by camera.decodeMs.collectAsState()
    val resolution by camera.resolution.collectAsState()
    val state by receiver.state.collectAsState()

    DisposableEffect(camera) {
        camera.onQrDecoded = { sample -> receiver.onQrDecoded(sample.bytes, sample.decodeMs) }
        onDispose { camera.onQrDecoded = null }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) camera.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            camera.destroy()
            receiver.close()
        }
    }
    LaunchedEffect(permission, cameraStatus) {
        if (permission && cameraStatus == TransferCameraManager.Status.IDLE &&
            state.status !in setOf(TransferReceiveStatus.COMPLETE, TransferReceiveStatus.VERIFYING)) {
            camera.start(owner)
        }
    }
    LaunchedEffect(state.status) {
        if (state.status == TransferReceiveStatus.VERIFYING || state.status == TransferReceiveStatus.COMPLETE) {
            camera.stop()
        }
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SuperQR", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Receive any file offline", color = Color.White.copy(alpha = 0.58f), fontSize = 13.sp)
        Text(
            "V40 Auto • camera ${ProductionQrContract.CAMERA_TARGET_FPS} FPS • sender L/M at 15/20 FPS",
            color = Color(0xFF7CB7FF), fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))

        if (!permission) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("ALLOW CAMERA")
                }
            }
            return@Column
        }

        Card(
            Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
        ) {
            AndroidView(factory = { camera.previewView }, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(10.dp))

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF11151D)),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                val title = when (state.status) {
                    TransferReceiveStatus.WAITING -> "Point the camera at the SuperQR"
                    TransferReceiveStatus.RECEIVING -> "Receiving${if (state.filename.isNotEmpty()) " ${state.filename}" else ""}"
                    TransferReceiveStatus.VERIFYING -> "Verifying file…"
                    TransferReceiveStatus.COMPLETE -> "File received ✓"
                    TransferReceiveStatus.ERROR -> "Transfer error"
                }
                Text(
                    title,
                    color = if (state.status == TransferReceiveStatus.COMPLETE) Color(0xFF66BB6A) else Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.profileLabel.isNotBlank()) {
                    Text(
                        "Detected ${state.profileLabel}",
                        color = Color(0xFF7CB7FF),
                        fontSize = 11.sp,
                    )
                }
                if (state.totalFrames > 0) {
                    Spacer(Modifier.height(8.dp))
                    val progress = state.progress.coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(8.dp).background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(4.dp))) {
                        Box(Modifier.fillMaxWidth(progress).height(8.dp).background(Color(0xFF1976D2), RoundedCornerShape(4.dp)))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${state.uniqueFrames} / ${state.totalFrames} frames", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
                        Text(String.format(Locale.US, "%.1f%%", progress * 100), color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
                    }
                }
                if (state.fileSize > 0 || state.filename.isNotEmpty()) {
                    Text(
                        "${formatBytes(state.fileSize)}${if (state.mimeType.isNotBlank()) " • ${state.mimeType}" else ""}",
                        color = Color.White.copy(alpha = 0.50f), fontSize = 11.sp,
                    )
                }
                if (state.status == TransferReceiveStatus.RECEIVING) {
                    Text(
                        String.format(Locale.US, "%.1f KiB/s useful • duplicates %d", state.usefulKibPerSecond, state.duplicates),
                        color = Color.White.copy(alpha = 0.50f), fontSize = 11.sp,
                    )
                }
                if (state.status == TransferReceiveStatus.COMPLETE) {
                    Text("Saved to ${state.savedLocation}", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.savedUri?.let { uri ->
                            Button(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, state.mimeType.ifBlank { "application/octet-stream" })
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try { context.startActivity(intent) } catch (_: Throwable) {
                                    Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("OPEN") }
                        }
                        OutlinedButton(onClick = {
                            receiver.reset()
                            if (camera.status.value == TransferCameraManager.Status.IDLE) camera.start(owner)
                        }) { Text("RECEIVE ANOTHER") }
                    }
                }
                if (state.status == TransferReceiveStatus.ERROR) {
                    Text(state.error.orEmpty(), color = Color(0xFFFF8A80), fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        receiver.reset()
                        if (camera.status.value == TransferCameraManager.Status.IDLE) camera.start(owner)
                    }) { Text("TRY AGAIN") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Camera ${String.format(Locale.US, "%.1f", cameraFps)} FPS • decode ${String.format(Locale.US, "%.1f", decodeMs)} ms${if (resolution.isNotBlank()) " • $resolution" else ""}",
            color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp,
        )
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GiB", value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> String.format(Locale.US, "%.2f MiB", value / (1024.0 * 1024.0))
    value >= 1024L -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
    else -> "$value B"
}
