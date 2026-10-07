package com.superqr.android.colorgrid8

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.superqr.android.camera.AnalysisRateAccumulator
import com.superqr.android.vision.lab.colorgrid8.gl.ColorGrid8GlThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

class ColorGrid8Camera2Manager(
    private val context: Context,
    private val glThread: ColorGrid8GlThread
) {
    enum class Status { IDLE, STARTING, RUNNING, STOPPING, ERROR }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _cameraFps = MutableStateFlow(0.0)
    val cameraFps: StateFlow<Double> = _cameraFps.asStateFlow()
    private val _resolution = MutableStateFlow("")
    val resolution: StateFlow<String> = _resolution.asStateFlow()

    private val _exposureTime = MutableStateFlow(0L)
    val exposureTime: StateFlow<Long> = _exposureTime.asStateFlow()
    private val _iso = MutableStateFlow(0)
    val iso: StateFlow<Int> = _iso.asStateFlow()
    private val _focusState = MutableStateFlow("")
    val focusState: StateFlow<String> = _focusState.asStateFlow()
    private val _awbState = MutableStateFlow("")
    val awbState: StateFlow<String> = _awbState.asStateFlow()
    private val _aeState = MutableStateFlow("")
    val aeState: StateFlow<String> = _aeState.asStateFlow()

    val cameraPath = MutableStateFlow("CAMERA2_GL")

    private val generation = AtomicInteger(0)
    private val deliveredRate = AnalysisRateAccumulator(64)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var lastCaptureResult: TotalCaptureResult? = null
    private var characteristics: CameraCharacteristics? = null
    var onResolutionChanged: ((Int, Int) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (_status.value == Status.STARTING || _status.value == Status.RUNNING) return
        _status.value = Status.STARTING

        val requestedGeneration = generation.incrementAndGet()

        cameraThread = HandlerThread("ColorGrid8Camera2").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val backCameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return

            val chars = manager.getCameraCharacteristics(backCameraId)
            characteristics = chars
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

            val outputSizes = map.getOutputSizes(SurfaceTexture::class.java)
            if (outputSizes == null) return

            for (size in outputSizes) {
                val minFrameDuration = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                val maxFps = if (minFrameDuration > 0) 1_000_000_000.0 / minFrameDuration else 0.0
                android.util.Log.d("Camera2Manager", "Size: ${size.width}x${size.height}, MinFrameDuration: $minFrameDuration, MaxFPS: $maxFps")
            }

            val targetSizes = listOf(Size(3840, 2160), Size(1920, 1440), Size(1920, 1080))
            var selectedSize: Size? = null
            for (target in targetSizes) {
                if (outputSizes.contains(target) && map.getOutputMinFrameDuration(SurfaceTexture::class.java, target) <= 1_000_000_000L / 30) {
                    selectedSize = target
                    break
                }
            }
            if (selectedSize == null) {
                selectedSize = outputSizes.filter { it.width >= 1920 || it.height >= 1080 }.maxByOrNull { it.width * it.height } ?: outputSizes.first()
            }

            _resolution.value = "${selectedSize.width}x${selectedSize.height}"
            onResolutionChanged?.invoke(selectedSize.width, selectedSize.height)

            manager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (generation.get() != requestedGeneration) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    glThread.post {
                        if (generation.get() == requestedGeneration) {
                            startSession(camera, selectedSize, requestedGeneration, chars)
                        }
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (generation.get() == requestedGeneration) _status.value = Status.ERROR
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (generation.get() == requestedGeneration) _status.value = Status.ERROR
                }
            }, cameraHandler)

        } catch (e: Exception) {
            _status.value = Status.ERROR
        }
    }

    private fun startSession(camera: CameraDevice, size: Size, requestedGeneration: Int, chars: CameraCharacteristics) {
        try {
            val surfaceTexture = glThread.surfaceTexture ?: return
            surfaceTexture.setDefaultBufferSize(size.width, size.height)
            val surface = glThread.surface ?: return

            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (generation.get() != requestedGeneration) {
                        session.close()
                        return
                    }
                    captureSession = session
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    builder.addTarget(surface)
                    ColorGrid8CameraControl.buildCalibrationRequest(builder, chars)

                    session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                        private var reportedProcessing = false
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            if (generation.get() != requestedGeneration) return
                            if (!reportedProcessing) {
                                reportedProcessing = true
                                android.util.Log.i("Camera2Manager", "ColorGrid actual NR=${result.get(CaptureResult.NOISE_REDUCTION_MODE)} edge=${result.get(CaptureResult.EDGE_MODE)}")
                            }
                            lastCaptureResult = result
                            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: System.nanoTime()
                            deliveredRate.recordCompletion(timestamp)
                            _cameraFps.value = deliveredRate.computeFps(timestamp)

                            val controlState = ColorGrid8CameraControl.inspectResult(result)
                            _exposureTime.value = controlState.exposureTimeNs
                            _iso.value = controlState.iso
                            _focusState.value = controlState.focusState
                            _awbState.value = controlState.awbState
                            _aeState.value = controlState.aeState
                        }
                    }, cameraHandler)
                    _status.value = Status.RUNNING
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (generation.get() == requestedGeneration) _status.value = Status.ERROR
                }
            }, cameraHandler)
        } catch (e: Exception) {
            _status.value = Status.ERROR
        }
    }

    fun lockExposure() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val chars = characteristics ?: return
        val surface = glThread.surface ?: return

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            ColorGrid8CameraControl.buildLockedRequest(builder, chars, lastCaptureResult)
            session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: System.nanoTime()
                    deliveredRate.recordCompletion(timestamp)
                    _cameraFps.value = deliveredRate.computeFps(timestamp)

                    val controlState = ColorGrid8CameraControl.inspectResult(result)
                    _exposureTime.value = controlState.exposureTimeNs
                    _iso.value = controlState.iso
                    _focusState.value = controlState.focusState
                    _awbState.value = controlState.awbState
                    _aeState.value = controlState.aeState
                }
            }, cameraHandler)
        } catch (e: Exception) {
            // handle
        }
    }

    fun stop() {
        if (_status.value == Status.IDLE) return
        _status.value = Status.STOPPING
        generation.incrementAndGet()
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(1000)
        } catch (e: Exception) {}
        cameraThread = null
        cameraHandler = null
        deliveredRate.reset()
        _status.value = Status.IDLE
    }

    fun destroy() {
        stop()
    }
}
