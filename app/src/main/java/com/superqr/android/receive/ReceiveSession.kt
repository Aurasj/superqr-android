package com.superqr.android.receive

import com.superqr.android.vision.v7.transport.V7SessionAccumulator
import com.superqr.android.vision.v7.transport.V7TransferPackage
import com.superqr.android.vision.v7.transport.V7Transport
import com.superqr.android.vision.v7.transport.V7TransportError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class ReceiveState(
    val uniqueFrames: Int = 0,
    val totalFrames: Int = 0,
    val progress: Float = 0f,
    val filename: String = "",
    val mimeType: String = "",
    val fileSize: Long = 0,
    val receivedFile: File? = null,
    val error: String? = null,
) {
    val isComplete: Boolean get() = receivedFile != null
}

class ReceiveSession(private val cacheDir: File) {
    private val accumulator = V7SessionAccumulator()
    private val _state = MutableStateFlow(ReceiveState())
    val state: StateFlow<ReceiveState> = _state.asStateFlow()
    private var lastSessionId = -1

    fun processQrBytes(bytes: ByteArray) {
        try {
            if (bytes.size < 20) return
            val frame = V7Transport.parseQrFrame(bytes)
            if (frame.sessionId != lastSessionId) {
                accumulator.reset()
                lastSessionId = frame.sessionId
            }
            val pkg = accumulator.addFrame(frame)
            if (pkg != null) {
                val file = writePackage(pkg)
                _state.value = ReceiveState(
                    uniqueFrames = accumulator.getUniqueFrames(),
                    totalFrames = accumulator.getTotalFrames(),
                    progress = 1f,
                    filename = pkg.filename,
                    mimeType = pkg.mimeType,
                    fileSize = pkg.fileSize.toLong(),
                    receivedFile = file,
                )
            } else {
                _state.value = ReceiveState(
                    uniqueFrames = accumulator.getUniqueFrames(),
                    totalFrames = accumulator.getTotalFrames(),
                    progress = accumulator.getProgress().toFloat(),
                )
            }
        } catch (_: V7TransportError) {
            // Frame failed CRC or header validation — skip
        }
    }

    private fun writePackage(pkg: V7TransferPackage): File {
        val ext = when {
            pkg.mimeType.startsWith("image/") -> ".img"
            pkg.mimeType.startsWith("video/") -> ".mp4"
            pkg.mimeType.startsWith("audio/") -> ".audio"
            pkg.mimeType == "text/plain" -> ".txt"
            else -> ".bin"
        }
        val file = File(cacheDir, "received_${pkg.filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")}$ext")
        file.outputStream().use { it.write(pkg.fileData) }
        return file
    }

    fun reset() {
        accumulator.reset()
        lastSessionId = -1
        _state.value = ReceiveState()
    }
}
