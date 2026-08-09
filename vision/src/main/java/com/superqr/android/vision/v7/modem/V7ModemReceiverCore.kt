package com.superqr.android.vision.v7.modem

/**
 * PHY-agnostic Android receive core. Camera/optical code supplies one exact
 * channel frame plus optional byte erasures; this layer owns FEC, packet
 * integrity, generation fountain state, and bounded-memory outcomes.
 */
class V7ModemReceiverCore(
    private val parityRatio: Double = V7ModemContract.DEFAULT_PARITY_RATIO,
    maxActiveGenerations: Int = 4,
) {
    enum class Status {
        INNER_REJECTED,
        DUPLICATE,
        INNOVATIVE,
        GENERATION_COMPLETE,
        WINDOW_FULL,
        SESSION_REJECTED,
    }

    data class Result(
        val status: Status,
        val packet: V7ModemPacket? = null,
        val rank: Int = 0,
        val completedGeneration: ByteArray? = null,
        val reason: String? = null,
    )

    private val generations = V7GenerationReceiver(maxActiveGenerations)
    var physicalFrames: Long = 0
        private set
    var innerRejectedFrames: Long = 0
        private set
    var innovativeFrames: Long = 0
        private set
    var duplicateFrames: Long = 0
        private set
    var windowDroppedFrames: Long = 0
        private set

    fun offerPhysicalFrame(physicalFrame: ByteArray, erasedPhysicalBytes: IntArray = IntArray(0)): Result {
        physicalFrames++
        val packet = try {
            V7InnerFec.decodePacket(physicalFrame, parityRatio, erasedPhysicalBytes)
        } catch (failure: V7ModemException) {
            innerRejectedFrames++
            return Result(Status.INNER_REJECTED, reason = failure.message)
        }
        val generation = try {
            generations.offer(packet)
        } catch (failure: V7ModemException) {
            return Result(Status.SESSION_REJECTED, packet = packet, reason = failure.message)
        }
        return when (generation.status) {
            V7GenerationReceiver.Status.DUPLICATE -> {
                duplicateFrames++
                Result(Status.DUPLICATE, packet, generation.rank)
            }
            V7GenerationReceiver.Status.INNOVATIVE -> {
                innovativeFrames++
                Result(Status.INNOVATIVE, packet, generation.rank)
            }
            V7GenerationReceiver.Status.GENERATION_COMPLETE -> {
                innovativeFrames++
                Result(Status.GENERATION_COMPLETE, packet, generation.rank, generation.completedBytes)
            }
            V7GenerationReceiver.Status.WINDOW_FULL -> {
                windowDroppedFrames++
                Result(Status.WINDOW_FULL, packet, generation.rank)
            }
        }
    }

    fun reset() {
        generations.reset()
        physicalFrames = 0
        innerRejectedFrames = 0
        innovativeFrames = 0
        duplicateFrames = 0
        windowDroppedFrames = 0
    }

    val activeGenerationCount: Int get() = generations.activeGenerationCount
    val completedGenerationCount: Int get() = generations.completedGenerationCount
    val completionBitmapBytes: Int get() = generations.completionBitmapBytes
}
