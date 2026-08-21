package com.superqr.android.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.superqr.android.transfer.PreparedQrSendSession
import com.superqr.android.transfer.ProductionQrContract
import com.superqr.android.transfer.ProductionQrProfile
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

@Composable
fun SendScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var profileIndex by remember { mutableIntStateOf(0) }
    val profile = ProductionQrContract.profiles[profileIndex]
    var session by remember { mutableStateOf<PreparedQrSendSession?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var framePosition by remember { mutableIntStateOf(0) }
    var loopIndex by remember { mutableIntStateOf(0) }
    var presented by remember { mutableLongStateOf(0L) }
    var measuredFps by remember { mutableDoubleStateOf(0.0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun resetPresentation() {
        running = false
        framePosition = 0
        loopIndex = 0
        presented = 0
        measuredFps = 0.0
        bitmap = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            preparing = true
            error = null
            scope.launch {
                try {
                    val prepared = withContext(Dispatchers.IO) {
                        PreparedQrSendSession.fromUri(context, uri, profile)
                    }
                    session?.close()
                    session = prepared
                    resetPresentation()
                } catch (t: Throwable) {
                    error = t.message ?: t.javaClass.simpleName
                } finally {
                    preparing = false
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { session?.close() }
    }
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(running, session, profile) {
        val active = session ?: return@LaunchedEffect
        try {
            if (!running) {
                if (bitmap == null) {
                    bitmap = withContext(Dispatchers.Default) { renderProductionQr(active.frameBytes(0), active.profile) }
                }
                return@LaunchedEffect
            }
            val intervalNs = (1_000_000_000.0 / active.profile.senderFps).toLong()
            val measurementStart = System.nanoTime()
            var measurementFrames = 0L
            while (running && session === active) {
                val frameStarted = System.nanoTime()
                val actualFrame = carouselFrameId(framePosition, loopIndex, active.totalFrames, active.sessionId)
                bitmap = withContext(Dispatchers.Default) { renderProductionQr(active.frameBytes(actualFrame), active.profile) }
                presented++
                measurementFrames++
                val elapsed = (System.nanoTime() - measurementStart) / 1_000_000_000.0
                if (elapsed >= 0.5) measuredFps = measurementFrames / elapsed
                framePosition++
                if (framePosition >= active.totalFrames) {
                    framePosition = 0
                    loopIndex++
                }
                val remainingNs = intervalNs - (System.nanoTime() - frameStarted)
                if (remainingNs > 0) delay(max(1L, remainingNs / 1_000_000L))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            running = false
            bitmap = null
            error = "Could not render transfer QR: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    val total = session?.totalFrames ?: 0
    val firstPassProgress = if (total > 0) framePosition.toDouble() / total else 0.0
    val etaSeconds = if (running && measuredFps > 0 && loopIndex == 0) (total - framePosition) / measuredFps else 0.0
    val measuredPayload = session?.let { it.profile.payloadBytes * measuredFps / 1024.0 } ?: 0.0

    Column(
        modifier.fillMaxSize().background(Color(0xFF090B10)).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Send", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Offline V40 QR transfer", color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF11151D)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !running && !preparing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (preparing) "PREPARING…" else "SELECT ANY FILE") }
                OutlinedButton(
                    onClick = {
                        val next = (profileIndex + 1) % ProductionQrContract.profiles.size
                        profileIndex = next
                        session?.setProfile(ProductionQrContract.profiles[next])
                        resetPresentation()
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (profileIndex == 0) "AUTO / SAFE • ${profile.label}" else profile.label)
                }
                session?.let { active ->
                    Text(active.metadata.filename, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${formatBytes(active.metadata.fileSize)} • ${active.metadata.mimeType} • ${active.totalFrames} frames",
                        color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                    )
                    Text("SHA-256 ${active.metadata.sha256Hex}", color = Color.White.copy(alpha = 0.38f), fontSize = 9.sp)
                }
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))

        Card(
            Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(12.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "SuperQR transfer frame",
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                } ?: Text("Select a file to prepare frame 0", color = Color.Black.copy(alpha = 0.45f))
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SendMetric("Sender", if (measuredFps > 0) "%.1f fps".format(measuredFps) else "—")
            SendMetric("Measured", if (measuredPayload > 0) "%.1f KiB/s".format(measuredPayload) else "—")
            SendMetric("Progress", if (total > 0) "%.0f%%".format(firstPassProgress * 100) else "—")
            SendMetric("ETA", if (etaSeconds > 0) "%.1f s".format(etaSeconds) else "—")
        }
        Text(
            if (running) "Pass ${loopIndex + 1} • frame ${framePosition + 1} / $total • $presented presented" else "Prepared preview • press Start when the receiver is ready",
            color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { running = !running },
            enabled = session != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "STOP" else "START SENDING", fontWeight = FontWeight.Bold) }
    }
}

internal fun carouselFrameId(position: Int, loopIndex: Int, total: Int, sessionId: Int): Int {
    if (total <= 1 || loopIndex == 0) return position
    var step = 1 + ((sessionId * 17 + loopIndex * 29) % (total - 1))
    fun gcd(aStart: Int, bStart: Int): Int {
        var a = aStart; var b = bStart
        while (b != 0) { val next = a % b; a = b; b = next }
        return a
    }
    while (gcd(step, total) != 1) {
        step++
        if (step >= total) step = 1
    }
    val offset = ((sessionId.toLong() * 131L + loopIndex.toLong() * 977L) % total).toInt()
    return (offset + position * step) % total
}

private fun renderProductionQr(data: ByteArray, profile: ProductionQrProfile): Bitmap {
    val matrix = encodeProductionQr(data, profile)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        pixels[y * matrix.width + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

internal fun encodeProductionQr(data: ByteArray, profile: ProductionQrProfile): BitMatrix {
    // ZXing already defaults to ISO-8859-1; an explicit charset hint emits ECI and overflows max-size V40 frames.
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to if (profile.ecc == "M") ErrorCorrectionLevel.M else ErrorCorrectionLevel.L,
        EncodeHintType.QR_VERSION to ProductionQrContract.QR_VERSION,
        EncodeHintType.QR_MASK_PATTERN to 4,
        EncodeHintType.MARGIN to 4,
    )
    return QRCodeWriter().encode(
        data.toString(Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, 185, 185, hints
    )
}

@Composable
private fun SendMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.42f), fontSize = 9.sp)
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GiB", value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> String.format(Locale.US, "%.2f MiB", value / (1024.0 * 1024.0))
    value >= 1024L -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
    else -> "$value B"
}
