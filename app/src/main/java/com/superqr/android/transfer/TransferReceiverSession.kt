package com.superqr.android.transfer

import android.content.Context
import android.net.Uri
import com.superqr.android.vision.v7.transport.V7TransportError
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


enum class TransferReceiveStatus { WAITING, RECEIVING, VERIFYING, COMPLETE, ERROR }

data class TransferReceiveState(
    val status: TransferReceiveStatus = TransferReceiveStatus.WAITING,
    val sessionId: Int = -1,
    val profileLabel: String = "",
    val filename: String = "",
    val mimeType: String = "",
    val fileSize: Long = 0,
    val uniqueFrames: Int = 0,
    val totalFrames: Int = 0,
    val duplicates: Int = 0,
    val receivedBytes: Long = 0,
    val progress: Float = 0f,
    val usefulKibPerSecond: Double = 0.0,
    val decodeMs: Double = 0.0,
    val savedUri: Uri? = null,
    val savedLocation: String = "",
    val error: String? = null,
)

/** Production receiver state machine. It has no dependency on DiagnosticSession or Phase 0 lab code. */
class TransferReceiverSession(context: Context) {
    private val appContext = context.applicationContext
    private val accumulator = QrTransferAccumulator(appContext.cacheDir)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow(TransferReceiveState())
    val state: StateFlow<TransferReceiveState> = _state.asStateFlow()

    @Volatile private var finalizing = false
    private var activeSession = -1
    private var startedNs = 0L

    fun onQrDecoded(bytes: ByteArray, decodeMs: Double) {
        if (finalizing || _state.value.status == TransferReceiveStatus.COMPLETE) return
        if (!ProductionQrContract.looksLikeProductionFrame(bytes)) return
        try {
            val snap = accumulator.accept(bytes)
            if (snap.sessionId != activeSession) {
                activeSession = snap.sessionId
                startedNs = System.nanoTime()
            }
            val elapsed = ((System.nanoTime() - startedNs).coerceAtLeast(1L)) / 1_000_000_000.0
            val rate = snap.receivedPayloadBytes / elapsed / 1024.0
            val meta = snap.metadata
            _state.value = TransferReceiveState(
                status = if (snap.complete) TransferReceiveStatus.VERIFYING else TransferReceiveStatus.RECEIVING,
                sessionId = snap.sessionId,
                profileLabel = snap.profileLabel,
                filename = meta?.filename.orEmpty(),
                mimeType = meta?.mimeType.orEmpty(),
                fileSize = meta?.fileSize ?: 0,
                uniqueFrames = snap.uniqueFrames,
                totalFrames = snap.totalFrames,
                duplicates = snap.duplicateFrames,
                receivedBytes = snap.receivedPayloadBytes,
                progress = if (snap.totalFrames > 0) snap.uniqueFrames.toFloat() / snap.totalFrames else 0f,
                usefulKibPerSecond = rate,
                decodeMs = decodeMs,
            )
            if (snap.complete) finalizeAsync()
        } catch (_: V7TransportError) {
            // Ignore foreign/corrupt optical frames. The sender repeats every frame carousel.
        } catch (t: Throwable) {
            _state.value = _state.value.copy(status = TransferReceiveStatus.ERROR, error = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun finalizeAsync() {
        if (finalizing) return
        finalizing = true
        ioExecutor.execute {
            try {
                val completion = accumulator.prepareCompletion()
                accumulator.verifyFileCrc(completion)
                val saved = ReceivedFileStore.save(appContext, completion)
                accumulator.discardTemporaryFile()
                _state.value = _state.value.copy(
                    status = TransferReceiveStatus.COMPLETE,
                    progress = 1f,
                    savedUri = saved.uri,
                    savedLocation = saved.locationLabel,
                    filename = saved.displayName,
                    error = null,
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    status = TransferReceiveStatus.ERROR,
                    error = t.message ?: t.javaClass.simpleName,
                )
            } finally {
                finalizing = false
            }
        }
    }

    fun reset() {
        if (finalizing) return
        accumulator.reset()
        activeSession = -1
        startedNs = 0L
        _state.value = TransferReceiveState()
    }

    fun close() {
        accumulator.reset()
        ioExecutor.shutdownNow()
    }
}
