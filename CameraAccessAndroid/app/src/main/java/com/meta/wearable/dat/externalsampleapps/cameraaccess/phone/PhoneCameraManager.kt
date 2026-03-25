package com.meta.wearable.dat.externalsampleapps.cameraaccess.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.ByteArrayOutputStream

class PhoneCameraManager(private val context: Context) {
    companion object {
        private const val TAG = "PhoneCameraManager"
        private const val DESIRED_WIDTH = 1280
        private const val DESIRED_HEIGHT = 720
    }

    var onFrameCaptured: ((Bitmap) -> Unit)? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var sensorOrientation: Int = 0
    @Volatile
    private var isStopped = false
    @Volatile
    private var isProcessingFrame = false
    @Volatile
    private var encoderSurface: Surface? = null

    @SuppressLint("MissingPermission")
    fun start() {
        isStopped = false
        startBackgroundThread()

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findBackCamera(cameraManager)
        if (cameraId == null) {
            Log.e(TAG, "No back camera found")
            return
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        Log.d(TAG, "Camera sensor orientation: $sensorOrientation")

        val captureSize = chooseCaptureSize(characteristics)
        Log.d(TAG, "Selected capture size: ${captureSize.width}x${captureSize.height}")

        imageReader = ImageReader.newInstance(
            captureSize.width, captureSize.height,
            ImageFormat.YUV_420_888, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                if (isStopped) return@setOnImageAvailableListener
                if (isProcessingFrame) {
                    val dropped = reader.acquireLatestImage()
                    dropped?.close()
                    return@setOnImageAvailableListener
                }
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                isProcessingFrame = true
                try {
                    val bitmap = yuvImageToBitmap(image)
                    if (bitmap != null) {
                        onFrameCaptured?.invoke(bitmap)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Frame conversion error: ${e.message}")
                } finally {
                    image.close()
                    isProcessingFrame = false
                }
            }, backgroundHandler)
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (isStopped) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    Log.d(TAG, "Camera opened (Camera2 API)")
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
        }
    }

    fun stop() {
        isStopped = true
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing capture session: ${e.message}")
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera: ${e.message}")
        }
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing image reader: ${e.message}")
        }
        imageReader = null

        stopBackgroundThread()
        Log.d(TAG, "Phone camera stopped (Camera2)")
    }

    private fun chooseCaptureSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(DESIRED_WIDTH, DESIRED_HEIGHT)

        val exact = outputSizes.find { it.width == DESIRED_WIDTH && it.height == DESIRED_HEIGHT }
        if (exact != null) return exact

        val candidates = outputSizes
            .filter { it.width <= 1920 && it.height <= 1080 }
            .sortedByDescending { it.width * it.height }

        return candidates.firstOrNull() ?: outputSizes.last()
    }

    @SuppressLint("MissingPermission")
    fun setEncoderSurface(surface: Surface?) {
        encoderSurface = surface
        if (cameraDevice != null && !isStopped) {
            // Restart the capture session with the new surface configuration
            try {
                captureSession?.stopRepeating()
                captureSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing session for surface update: ${e.message}")
            }
            captureSession = null
            createCaptureSession()
        }
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return
        val readerSurface = reader.surface
        val surfaces = listOfNotNull(readerSurface, encoderSurface)

        try {
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (isStopped) {
                            session.close()
                            return
                        }
                        captureSession = session
                        startRepeatingCapture(session, surfaces)
                        Log.d(TAG, "Capture session configured (surfaces: ${surfaces.size})")
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session: ${e.message}")
        }
    }

    private fun startRepeatingCapture(session: CameraCaptureSession, surfaces: List<Surface>) {
        val camera = cameraDevice ?: return
        try {
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                for (surface in surfaces) addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()

            session.setRepeatingRequest(captureRequest, null, backgroundHandler)
            Log.d(TAG, "Repeating capture started (${surfaces.size} targets)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start repeating capture: ${e.message}")
        }
    }

    private fun findBackCamera(cameraManager: CameraManager): String? {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return null
    }

    private fun yuvImageToBitmap(image: android.media.Image): Bitmap? {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        val yBuffer = yPlane.buffer
        for (row in 0 until height) {
            val yOffset = row * yRowStride
            val destOffset = row * width
            yBuffer.position(yOffset)
            val rowLength = minOf(width, yBuffer.remaining())
            yBuffer.get(nv21, destOffset, rowLength)
        }

        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        val uvHeight = height / 2
        val uvWidth = width / 2
        val vCapacity = vBuffer.capacity()
        val uCapacity = uBuffer.capacity()
        var uvDestOffset = width * height

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvOffset = row * uvRowStride + col * uvPixelStride
                if (uvOffset < vCapacity && uvOffset < uCapacity) {
                    nv21[uvDestOffset++] = vBuffer.get(uvOffset)
                    nv21[uvDestOffset++] = uBuffer.get(uvOffset)
                } else {
                    nv21[uvDestOffset++] = 128.toByte()
                    nv21[uvDestOffset++] = 128.toByte()
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
        val jpegBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null

        return if (sensorOrientation != 0) {
            val matrix = Matrix()
            matrix.postRotate(sensorOrientation.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Background thread join interrupted")
        }
        backgroundThread = null
        backgroundHandler = null
    }
}
