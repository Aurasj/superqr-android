package com.superqr.android.colorgrid8

import android.content.Context
import com.superqr.android.transfer.IncomingPackageMetadata
import com.superqr.android.transfer.ReceivedFileStore
import com.superqr.android.transfer.ReceiverWorkQueue
import com.superqr.android.transfer.StagedTransfer
import com.superqr.android.transfer.TransferCompletion
import com.superqr.android.transfer.TransferReceiveState
import com.superqr.android.transfer.TransferReceiveStatus
import com.superqr.android.transfer.sanitizeReceivedFilename
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8AnalysisResult
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Profile
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8Spec
import com.superqr.android.vision.lab.colorgrid8.ColorGrid8TransferCodec
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ColorGrid8TransferDiagnostics(
    val parityFrames: Int = 0,
    val recoveredFrames: Int = 0,
    val rejectedFrames: Int = 0,
    val observedErasures: Long = 0,
)

private data class Grid8AccumulatorSnapshot(
    val sessionId: Int,
    val profileLabel: String,
    val uniqueFrames: Int,
    val totalFrames: Int,
    val duplicates: Int,
    val receivedBytes: Long,
    val metadata: IncomingPackageMetadata?,
    val parityFrames: Int,
    val recoveredFrames: Int,
    val complete: Boolean,
)

/** Disk-backed 8+1 XOR accumulator for the isolated ColorGrid8 v2 LAB. */
private class ColorGrid8TransferAccumulator(private val cacheDir: File) {
    companion object {
        private val PACKAGE_MAGIC = byteArrayOf('S'.code.toByte(), 'Q'.code.toByte(), 'P'.code.toByte(), '7'.code.toByte())
        private const val PACKAGE_HEADER_SIZE = 20
        private const val MAX_FILENAME_BYTES = 1024
        private const val MAX_MIME_BYTES = 255
    }

    private var sessionId = 0
    private var profile: ColorGrid8Profile? = null
    private var totalFrames = 0
    private var chunkCapacity = 0
    private var seen = BooleanArray(0)
    private var payloadLengths = IntArray(0)
    private var dataMaskLow = LongArray(0)
    private var dataMaskHigh = LongArray(0)
    private var paritySeen = BooleanArray(0)
    private var parityMaskLow = LongArray(0)
    private var parityMaskHigh = LongArray(0)
    private var uniqueFrames = 0
    private var duplicates = 0
    private var parityFrames = 0
    private var recoveredFrames = 0
    private var receivedBytes = 0L
    private var metadata: IncomingPackageMetadata? = null
    private var packageFile: File? = null
    private var parityFile: File? = null
    private var packageRaf: RandomAccessFile? = null
    private var parityRaf: RandomAccessFile? = null

    @Synchronized
    fun accept(profile: ColorGrid8Profile, frame: ColorGrid8TransferCodec.Frame): Grid8AccumulatorSnapshot {
        if (sessionId != frame.sessionId) startSession(profile, frame)
        if (
            this.profile?.profileId != profile.profileId ||
            this.profile?.fps != profile.fps ||
            frame.totalDataFrames != totalFrames ||
            frame.chunkCapacity != chunkCapacity
        ) throw IllegalArgumentException("ColorGrid8 transfer parameters changed inside session")

        val groupStart = frame.frameId - frame.frameId % ColorGrid8TransferCodec.XOR_GROUP_SIZE
        if (frame.kind == ColorGrid8TransferCodec.KIND_DATA) {
            acceptData(frame)
        } else {
            val group = groupStart / ColorGrid8TransferCodec.XOR_GROUP_SIZE
            if (paritySeen[group]) {
                duplicates++
            } else {
                val added = mergeValidBlocks(
                    target = parityRaf ?: throw IllegalStateException("parity file is closed"),
                    baseOffset = group.toLong() * chunkCapacity,
                    payload = frame.payload,
                    validBlocks = frame.validBlocks,
                    currentLow = parityMaskLow[group],
                    currentHigh = parityMaskHigh[group],
                )
                parityMaskLow[group] = added.first
                parityMaskHigh[group] = added.second
                if (!added.third) duplicates++
                if (hasAllBlocks(added.first, added.second, blockCount(chunkCapacity))) {
                    paritySeen[group] = true
                    parityFrames++
                }
            }
        }
        tryRecover(groupStart)
        return snapshot()
    }

    @Synchronized
    fun prepareCompletion(): TransferCompletion {
        if (uniqueFrames != totalFrames || totalFrames == 0) throw IllegalStateException("transfer is not complete")
        val meta = metadata ?: throw IllegalArgumentException("missing ColorGrid8 package metadata")
        val expectedFrames = ((meta.packageBytes + chunkCapacity - 1) / chunkCapacity).coerceAtLeast(1)
        if (expectedFrames != totalFrames.toLong()) throw IllegalArgumentException("package size does not match frame count")
        packageRaf?.setLength(meta.packageBytes)
        packageRaf?.fd?.sync()
        packageRaf?.close()
        packageRaf = null
        parityRaf?.close()
        parityRaf = null
        parityFile?.delete()
        parityFile = null
        return TransferCompletion(sessionId, meta, packageFile ?: throw IllegalStateException("missing package file"))
    }

    fun verify(completion: TransferCompletion): String {
        val crc = CRC32()
        val sha256 = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(completion.tempFile, "r").use { source ->
            source.seek(completion.metadata.dataOffset)
            var remaining = completion.metadata.fileSize
            val buffer = ByteArray(1024 * 1024)
            while (remaining > 0) {
                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) throw IllegalArgumentException("temporary package ended early")
                crc.update(buffer, 0, count)
                sha256.update(buffer, 0, count)
                remaining -= count
            }
        }
        if ((crc.value and 0xFFFFFFFFL) != completion.metadata.fileCrc32) {
            throw IllegalArgumentException("file CRC32 mismatch")
        }
        return sha256.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun detachTemporaryFile(): File {
        packageRaf?.close()
        packageRaf = null
        val file = packageFile ?: throw IllegalStateException("missing package file")
        packageFile = null
        return file
    }

    @Synchronized
    fun reset() {
        try { packageRaf?.close() } catch (_: Throwable) {}
        try { parityRaf?.close() } catch (_: Throwable) {}
        packageRaf = null
        parityRaf = null
        packageFile?.delete()
        parityFile?.delete()
        packageFile = null
        parityFile = null
        sessionId = 0
        profile = null
        totalFrames = 0
        chunkCapacity = 0
        seen = BooleanArray(0)
        payloadLengths = IntArray(0)
        dataMaskLow = LongArray(0)
        dataMaskHigh = LongArray(0)
        paritySeen = BooleanArray(0)
        parityMaskLow = LongArray(0)
        parityMaskHigh = LongArray(0)
        uniqueFrames = 0
        duplicates = 0
        parityFrames = 0
        recoveredFrames = 0
        receivedBytes = 0L
        metadata = null
    }

    private fun startSession(profile: ColorGrid8Profile, frame: ColorGrid8TransferCodec.Frame) {
        reset()
        sessionId = frame.sessionId
        this.profile = profile
        totalFrames = frame.totalDataFrames
        chunkCapacity = frame.chunkCapacity
        seen = BooleanArray(totalFrames)
        payloadLengths = IntArray(totalFrames)
        dataMaskLow = LongArray(totalFrames)
        dataMaskHigh = LongArray(totalFrames)
        val groups = (totalFrames + ColorGrid8TransferCodec.XOR_GROUP_SIZE - 1) / ColorGrid8TransferCodec.XOR_GROUP_SIZE
        paritySeen = BooleanArray(groups)
        parityMaskLow = LongArray(groups)
        parityMaskHigh = LongArray(groups)
        val dir = File(cacheDir, "superqr-transfer").apply { mkdirs() }
        val sessionHex = Integer.toUnsignedString(frame.sessionId, 16)
        packageFile = File(dir, "grid8_${sessionHex}.part")
        parityFile = File(dir, "grid8_${sessionHex}.xor")
        packageRaf = RandomAccessFile(packageFile, "rw")
        parityRaf = RandomAccessFile(parityFile, "rw")
    }

    private fun acceptData(frame: ColorGrid8TransferCodec.Frame) {
        val frameId = frame.frameId
        if (seen[frameId]) {
            duplicates++
            return
        }
        if (frameId < totalFrames - 1 && frame.payload.size != chunkCapacity) {
            throw IllegalArgumentException("short non-final ColorGrid8 data frame")
        }
        if (frame.payload.isEmpty() || frame.payload.size > chunkCapacity) {
            throw IllegalArgumentException("invalid ColorGrid8 payload length")
        }
        val previousLength = payloadLengths[frameId]
        if (previousLength != 0 && previousLength != frame.payload.size) {
            throw IllegalArgumentException("ColorGrid8 payload length changed across carousel passes")
        }
        payloadLengths[frameId] = frame.payload.size
        val merged = mergeValidBlocks(
            target = packageRaf ?: throw IllegalStateException("package file is closed"),
            baseOffset = frameId.toLong() * chunkCapacity,
            payload = frame.payload,
            validBlocks = frame.validBlocks,
            currentLow = dataMaskLow[frameId],
            currentHigh = dataMaskHigh[frameId],
        )
        dataMaskLow[frameId] = merged.first
        dataMaskHigh[frameId] = merged.second
        if (!merged.third) duplicates++
        if (hasAllBlocks(merged.first, merged.second, blockCount(frame.payload.size))) {
            seen[frameId] = true
            uniqueFrames++
            receivedBytes += frame.payload.size
            if (frameId == 0) metadata = readMetadataFrame()
        }
    }

    private fun tryRecover(groupStart: Int) {
        val group = groupStart / ColorGrid8TransferCodec.XOR_GROUP_SIZE
        if (!paritySeen[group]) return
        val end = minOf(groupStart + ColorGrid8TransferCodec.XOR_GROUP_SIZE, totalFrames)
        var missing = -1
        for (frameId in groupStart until end) {
            if (!seen[frameId]) {
                if (missing >= 0) return
                missing = frameId
            }
        }
        if (missing < 0) return

        val recovered = ByteArray(chunkCapacity)
        parityRaf?.seek(group.toLong() * chunkCapacity)
        parityRaf?.readFully(recovered)
        val scratch = ByteArray(chunkCapacity)
        for (frameId in groupStart until end) {
            if (frameId == missing) continue
            scratch.fill(0)
            packageRaf?.seek(frameId.toLong() * chunkCapacity)
            packageRaf?.readFully(scratch, 0, payloadLengths[frameId])
            for (index in scratch.indices) recovered[index] = (recovered[index].toInt() xor scratch[index].toInt()).toByte()
        }
        val recoveredLength = if (missing == totalFrames - 1 && metadata != null) {
            (metadata!!.packageBytes - missing.toLong() * chunkCapacity).toInt()
        } else {
            chunkCapacity
        }
        acceptRecoveredData(missing, recovered.copyOf(recoveredLength))
    }

    private fun acceptRecoveredData(frameId: Int, payload: ByteArray) {
        if (seen[frameId]) return
        packageRaf?.seek(frameId.toLong() * chunkCapacity)
        packageRaf?.write(payload)
        payloadLengths[frameId] = payload.size
        val masks = fullBlockMasks(blockCount(payload.size))
        dataMaskLow[frameId] = masks.first
        dataMaskHigh[frameId] = masks.second
        seen[frameId] = true
        uniqueFrames++
        recoveredFrames++
        receivedBytes += payload.size
        if (frameId == 0) metadata = parseMetadata(payload)
    }

    private fun mergeValidBlocks(
        target: RandomAccessFile,
        baseOffset: Long,
        payload: ByteArray,
        validBlocks: BooleanArray,
        currentLow: Long,
        currentHigh: Long,
    ): Triple<Long, Long, Boolean> {
        val expectedBlocks = blockCount(payload.size)
        if (validBlocks.size != expectedBlocks) throw IllegalArgumentException("invalid ColorGrid8 block map")
        var low = currentLow
        var high = currentHigh
        var added = false
        for (block in 0 until expectedBlocks) {
            if (!validBlocks[block] || blockIsSet(low, high, block)) continue
            val offset = block * ColorGrid8TransferCodec.BLOCK_DATA_BYTES
            val length = minOf(ColorGrid8TransferCodec.BLOCK_DATA_BYTES, payload.size - offset)
            target.seek(baseOffset + offset)
            target.write(payload, offset, length)
            if (block < 64) low = low or (1L shl block) else high = high or (1L shl (block - 64))
            added = true
        }
        return Triple(low, high, added)
    }

    private fun readMetadataFrame(): IncomingPackageMetadata {
        val length = payloadLengths[0]
        val payload = ByteArray(length)
        packageRaf?.seek(0)
        packageRaf?.readFully(payload)
        return parseMetadata(payload)
    }

    private fun blockCount(payloadLength: Int): Int =
        (payloadLength + ColorGrid8TransferCodec.BLOCK_DATA_BYTES - 1) / ColorGrid8TransferCodec.BLOCK_DATA_BYTES

    private fun blockIsSet(low: Long, high: Long, block: Int): Boolean =
        if (block < 64) (low and (1L shl block)) != 0L else (high and (1L shl (block - 64))) != 0L

    private fun fullBlockMasks(count: Int): Pair<Long, Long> {
        val lowCount = minOf(64, count)
        val highCount = (count - 64).coerceAtLeast(0)
        val low = if (lowCount == 64) -1L else (1L shl lowCount) - 1L
        val high = if (highCount == 0) 0L else (1L shl highCount) - 1L
        return low to high
    }

    private fun hasAllBlocks(low: Long, high: Long, count: Int): Boolean {
        val expected = fullBlockMasks(count)
        return (low and expected.first) == expected.first && (high and expected.second) == expected.second
    }

    private fun parseMetadata(payload: ByteArray): IncomingPackageMetadata {
        if (payload.size < PACKAGE_HEADER_SIZE) throw IllegalArgumentException("truncated SQP7 metadata")
        for (index in PACKAGE_MAGIC.indices) if (payload[index] != PACKAGE_MAGIC[index]) {
            throw IllegalArgumentException("invalid SQP7 package magic")
        }
        val buffer = ByteBuffer.wrap(payload)
        buffer.position(4)
        val filenameLength = buffer.short.toInt() and 0xFFFF
        val mimeLength = buffer.short.toInt() and 0xFFFF
        val fileSize = buffer.long
        val fileCrc32 = buffer.int.toLong() and 0xFFFFFFFFL
        if (filenameLength !in 1..MAX_FILENAME_BYTES || mimeLength !in 0..MAX_MIME_BYTES || fileSize < 0) {
            throw IllegalArgumentException("invalid SQP7 metadata")
        }
        val dataOffset = PACKAGE_HEADER_SIZE.toLong() + filenameLength + mimeLength
        if (dataOffset > payload.size || fileSize > Long.MAX_VALUE - dataOffset) {
            throw IllegalArgumentException("SQP7 metadata does not fit the first ColorGrid8 frame")
        }
        val packageBytes = dataOffset + fileSize
        val expectedFrames = ((packageBytes + chunkCapacity - 1) / chunkCapacity).coerceAtLeast(1)
        if (expectedFrames != totalFrames.toLong()) throw IllegalArgumentException("SQP7 size does not match ColorGrid8 frame count")
        val filename = sanitizeReceivedFilename(String(payload, PACKAGE_HEADER_SIZE, filenameLength, Charsets.UTF_8))
        val mime = String(payload, PACKAGE_HEADER_SIZE + filenameLength, mimeLength, Charsets.UTF_8)
        return IncomingPackageMetadata(
            filename = filename,
            mimeType = mime.ifBlank { "application/octet-stream" },
            fileSize = fileSize,
            fileCrc32 = fileCrc32,
            dataOffset = dataOffset,
            packageBytes = packageBytes,
        )
    }

    private fun snapshot(): Grid8AccumulatorSnapshot {
        val activeProfile = profile
        return Grid8AccumulatorSnapshot(
            sessionId = sessionId,
            profileLabel = activeProfile?.let { "ColorGrid8 ${it.cols}×${it.rows} @${it.fps}" }.orEmpty(),
            uniqueFrames = uniqueFrames,
            totalFrames = totalFrames,
            duplicates = duplicates,
            receivedBytes = receivedBytes,
            metadata = metadata,
            parityFrames = parityFrames,
            recoveredFrames = recoveredFrames,
            complete = totalFrames > 0 && uniqueFrames == totalFrames,
        )
    }
}

/** Receiver lifecycle and preview-before-save bridge for ColorGrid8 v2. */
class ColorGrid8TransferReceiverSession(context: Context) {
    private val appContext = context.applicationContext
    private val accumulator = ColorGrid8TransferAccumulator(appContext.cacheDir)
    private val workQueue = ReceiverWorkQueue()
    private val _state = MutableStateFlow(TransferReceiveState())
    val state: StateFlow<TransferReceiveState> = _state.asStateFlow()
    private val _diagnostics = MutableStateFlow(ColorGrid8TransferDiagnostics())
    val diagnostics: StateFlow<ColorGrid8TransferDiagnostics> = _diagnostics.asStateFlow()

    @Volatile private var finalizing = false
    @Volatile private var closed = false
    private var activeSession = 0
    private var startedNs = 0L
    private var stagedTransfer: StagedTransfer? = null

    @Synchronized
    fun onAnalysis(profile: ColorGrid8Profile, analysis: ColorGrid8AnalysisResult, decodeMs: Double) {
        if (closed || finalizing || analysis.header.version != ColorGrid8Spec.TRANSFER_HEADER_VERSION) return
        if (_state.value.status in setOf(TransferReceiveStatus.PREVIEW, TransferReceiveStatus.SAVING, TransferReceiveStatus.SAVED)) return
        val symbols = analysis.payloadSymbols ?: return
        val frame = ColorGrid8TransferCodec.parse(profile, symbols)
        if (frame == null) {
            _diagnostics.value = _diagnostics.value.copy(
                rejectedFrames = _diagnostics.value.rejectedFrames + 1,
                observedErasures = _diagnostics.value.observedErasures + analysis.erasures,
            )
            return
        }
        try {
            val snapshot = accumulator.accept(profile, frame)
            if (snapshot.sessionId != activeSession) {
                activeSession = snapshot.sessionId
                startedNs = System.nanoTime()
            }
            val elapsed = (System.nanoTime() - startedNs).coerceAtLeast(1L) / 1_000_000_000.0
            val metadata = snapshot.metadata
            _diagnostics.value = _diagnostics.value.copy(
                parityFrames = snapshot.parityFrames,
                recoveredFrames = snapshot.recoveredFrames,
                observedErasures = _diagnostics.value.observedErasures + analysis.erasures,
            )
            _state.value = TransferReceiveState(
                status = if (snapshot.complete) TransferReceiveStatus.VERIFYING else TransferReceiveStatus.RECEIVING,
                sessionId = snapshot.sessionId,
                profileLabel = snapshot.profileLabel,
                filename = metadata?.filename.orEmpty(),
                mimeType = metadata?.mimeType.orEmpty(),
                fileSize = metadata?.fileSize ?: 0,
                uniqueFrames = snapshot.uniqueFrames,
                totalFrames = snapshot.totalFrames,
                duplicates = snapshot.duplicates,
                receivedBytes = snapshot.receivedBytes,
                progress = snapshot.uniqueFrames.toFloat() / snapshot.totalFrames,
                usefulKibPerSecond = snapshot.receivedBytes / elapsed / 1024.0,
                decodeMs = decodeMs,
            )
            if (snapshot.complete) finalizeAsync()
        } catch (failure: Throwable) {
            _state.value = _state.value.copy(status = TransferReceiveStatus.ERROR, error = failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun finalizeAsync() {
        if (finalizing) return
        finalizing = true
        if (!workQueue.execute {
            var detached: File? = null
            try {
                val completion = accumulator.prepareCompletion()
                val sha256 = accumulator.verify(completion)
                detached = accumulator.detachTemporaryFile()
                val staged = ReceivedFileStore.stageVerified(appContext, completion, detached, sha256)
                detached = null
                stagedTransfer = staged
                _state.value = _state.value.copy(
                    status = TransferReceiveStatus.PREVIEW,
                    progress = 1f,
                    sha256Hex = sha256,
                    previewUri = staged.uri,
                    error = null,
                )
            } catch (failure: Throwable) {
                detached?.delete()
                _state.value = _state.value.copy(
                    status = TransferReceiveStatus.ERROR,
                    error = failure.message ?: failure.javaClass.simpleName,
                )
            } finally {
                finalizing = false
            }
        }) finalizing = false
    }

    fun save() {
        if (closed || finalizing) return
        val staged = stagedTransfer ?: return
        finalizing = true
        _state.value = _state.value.copy(status = TransferReceiveStatus.SAVING)
        if (!workQueue.execute {
            try {
                val saved = ReceivedFileStore.save(appContext, staged)
                ReceivedFileStore.discard(staged)
                stagedTransfer = null
                _state.value = _state.value.copy(
                    status = TransferReceiveStatus.SAVED,
                    previewUri = null,
                    savedUri = saved.uri,
                    savedLocation = saved.locationLabel,
                    filename = saved.displayName,
                    error = null,
                )
            } catch (failure: Throwable) {
                _state.value = _state.value.copy(status = TransferReceiveStatus.ERROR, error = failure.message ?: failure.javaClass.simpleName)
            } finally {
                finalizing = false
            }
        }) finalizing = false
    }

    fun discard() {
        if (closed || finalizing) return
        ReceivedFileStore.discard(stagedTransfer)
        stagedTransfer = null
        reset()
    }

    fun reset() {
        if (closed || finalizing) return
        ReceivedFileStore.discard(stagedTransfer)
        stagedTransfer = null
        accumulator.reset()
        activeSession = 0
        startedNs = 0L
        _state.value = TransferReceiveState()
        _diagnostics.value = ColorGrid8TransferDiagnostics()
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        workQueue.closeAfterPendingWork {
            ReceivedFileStore.discard(stagedTransfer)
            stagedTransfer = null
            accumulator.reset()
        }
    }
}
