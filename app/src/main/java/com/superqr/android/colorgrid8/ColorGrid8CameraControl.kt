package com.superqr.android.colorgrid8

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult

data class CameraControlState(
    val exposureTimeNs: Long,
    val iso: Int,
    val focusState: String,
    val awbState: String,
    val aeState: String,
    val focusDistanceDiopters: Float?
)

object ColorGrid8CameraControl {
    fun buildCalibrationRequest(builder: CaptureRequest.Builder, characteristics: CameraCharacteristics) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val thirty = ranges?.firstOrNull { it.lower == 30 && it.upper == 30 }
        if (thirty != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, thirty)
        // Optical cells are signal, not photographic chroma noise. Request OFF
        // only when the device advertises both the key and the supported mode.
        val noiseMode = opticalNoiseReductionMode(
            characteristics.availableCaptureRequestKeys.contains(CaptureRequest.NOISE_REDUCTION_MODE),
            characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
        )
        if (noiseMode != null) builder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseMode)
        android.util.Log.i("Camera2Manager", "ColorGrid noise reduction request=${noiseMode ?: "template default"}")
    }

    internal fun opticalNoiseReductionMode(keyAvailable: Boolean, modes: IntArray?): Int? =
        CaptureRequest.NOISE_REDUCTION_MODE_OFF.takeIf {
            keyAvailable && modes?.contains(it) == true
        }

    fun buildLockedRequest(builder: CaptureRequest.Builder, characteristics: CameraCharacteristics, captureResult: TotalCaptureResult?) {
        buildCalibrationRequest(builder, characteristics)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        if (characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        if (characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }

        if (captureResult != null) {
            val focusDistance = captureResult.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (focusDistance != null) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            }
        }
    }

    fun buildShortExposureRequest(builder: CaptureRequest.Builder, characteristics: CameraCharacteristics, targetExposureNs: Long) {
        if (isManualSensorSupported(characteristics)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExposureNs)
        }
    }

    fun inspectResult(result: CaptureResult): CameraControlState {
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val focusStateInt = result.get(CaptureResult.CONTROL_AF_STATE) ?: CaptureResult.CONTROL_AF_STATE_INACTIVE
        val awbStateInt = result.get(CaptureResult.CONTROL_AWB_STATE) ?: CaptureResult.CONTROL_AWB_STATE_INACTIVE
        val aeStateInt = result.get(CaptureResult.CONTROL_AE_STATE) ?: CaptureResult.CONTROL_AE_STATE_INACTIVE
        val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)

        val focusState = when (focusStateInt) {
            CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
            else -> "UNKNOWN"
        }
        val awbState = when (awbStateInt) {
            CaptureResult.CONTROL_AWB_STATE_INACTIVE -> "INACTIVE"
            CaptureResult.CONTROL_AWB_STATE_SEARCHING -> "SEARCHING"
            CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "CONVERGED"
            CaptureResult.CONTROL_AWB_STATE_LOCKED -> "LOCKED"
            else -> "UNKNOWN"
        }
        val aeState = when (aeStateInt) {
            CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
            CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
            CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
            CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
            CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
            else -> "UNKNOWN"
        }

        return CameraControlState(
            exposureTimeNs = exposureTime,
            iso = iso,
            focusState = focusState,
            awbState = awbState,
            aeState = aeState,
            focusDistanceDiopters = focusDistance
        )
    }

    fun isManualSensorSupported(characteristics: CameraCharacteristics): Boolean {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        return capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    }
}
