/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.phone.PhoneCameraManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    // Pool size of 6 gives ~250ms of safety at 24fps for any consumer (UI/Gemini/WebRTC)
    // that may still be reading a previously published frame when the next decode finishes.
    // Capture flow MUST clone the bitmap before retaining it longer (see capturePhoto).
    private const val BITMAP_POOL_SIZE = 6
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var streamSession: StreamSession? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private val _videoFrame = MutableStateFlow<Bitmap?>(null)
  val videoFrame: StateFlow<Bitmap?> = _videoFrame.asStateFlow()

  private var bitmapPool: Array<Bitmap>? = null
  private var poolWidth = 0
  private var poolHeight = 0
  private var poolIndex = 0
  private var pixelsBuf: IntArray? = null

  private var videoJob: Job? = null
  private var stateJob: Job? = null

  // VisionClaw additions
  var geminiViewModel: GeminiSessionViewModel? = null
  var webrtcViewModel: WebRTCSessionViewModel? = null
  private var phoneCameraManager: PhoneCameraManager? = null
  var twitchRawFrameCallback: ((ByteBuffer, Int, Int, Long) -> Unit)? = null
  private var frameTimestampBase = 0L

  // Screen state: skip the expensive JPEG decode pipeline when screen is off
  // to free CPU for the H.264 encoder (main quality preservation measure)
  @Volatile private var isScreenOn = true
  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.action) {
        Intent.ACTION_SCREEN_OFF -> {
          isScreenOn = false
          Log.d(TAG, "Screen OFF — JPEG UI pipeline suspended")
        }
        Intent.ACTION_SCREEN_ON -> {
          isScreenOn = true
          Log.d(TAG, "Screen ON — JPEG UI pipeline resumed")
        }
      }
    }
  }
  private var screenReceiverRegistered = false

  private fun registerScreenReceiver() {
    if (screenReceiverRegistered) return
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_OFF)
      addAction(Intent.ACTION_SCREEN_ON)
    }
    getApplication<Application>().registerReceiver(screenReceiver, filter)
    screenReceiverRegistered = true
  }

  private fun unregisterScreenReceiver() {
    if (!screenReceiverRegistered) return
    try { getApplication<Application>().unregisterReceiver(screenReceiver) } catch (_: Exception) {}
    screenReceiverRegistered = false
  }

  fun startStream() {
    videoJob?.cancel()
    stateJob?.cancel()

    // Start foreground service to keep streaming alive in background / screen locked
    StreamingService.start(getApplication())
    registerScreenReceiver()
    isScreenOn = true

    val streamSession =
        Wearables.startStreamSession(
                getApplication(),
                deviceSelector,
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24),
            )
            .also { streamSession = it }
    _uiState.update { it.copy(streamingMode = StreamingMode.GLASSES) }
    videoJob = viewModelScope.launch(Dispatchers.Default) {
      streamSession.videoStream.conflate().collect { handleVideoFrame(it) }
    }
    stateJob =
        viewModelScope.launch {
          streamSession.state.collect { currentState ->
            val prevState = _uiState.value.streamSessionState
            _uiState.update { it.copy(streamSessionState = currentState) }

            // navigate back when state transitioned to STOPPED
            if (currentState != prevState && currentState == StreamSessionState.STOPPED) {
              stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            }
          }
        }
  }

  fun startPhoneCamera() {
    val manager = PhoneCameraManager(getApplication())
    phoneCameraManager = manager

    manager.onFrameCaptured = { bitmap ->
      _videoFrame.value = bitmap
      geminiViewModel?.sendVideoFrameIfThrottled(bitmap)
      webrtcViewModel?.pushVideoFrame(bitmap)
    }

    _uiState.update {
      it.copy(
        streamingMode = StreamingMode.PHONE,
        streamSessionState = StreamSessionState.STREAMING,
      )
    }
    manager.start()
    Log.d(TAG, "Phone camera mode started")
  }

  fun pausePhoneCamera() {
    phoneCameraManager?.stop()
    Log.d(TAG, "Phone camera paused")
  }

  fun resumePhoneCamera() {
    phoneCameraManager?.let {
      Log.d(TAG, "Phone camera resumed")
    }
  }

  fun startServerVideo() {
    _uiState.update { it.copy(isServerVideoActive = true) }
    Log.d(TAG, "Server video started")
  }

  fun stopServerVideo() {
    _uiState.update { it.copy(isServerVideoActive = false) }
    Log.d(TAG, "Server video stopped")
  }

  fun onServerVideoStoppedByServer() {
    _uiState.update { it.copy(isServerVideoActive = false) }
    Log.d(TAG, "Server video stopped by server")
  }

  fun stopStream() {
    // Stop foreground service
    StreamingService.stop(getApplication())
    unregisterScreenReceiver()

    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    streamSession?.close()
    streamSession = null
    phoneCameraManager?.stop()
    phoneCameraManager = null
    _videoFrame.value = null
    bitmapPool = null
    pixelsBuf = null
    poolWidth = 0
    poolHeight = 0
    poolIndex = 0
    _uiState.update { INITIAL_STATE }
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      // Phone mode: capture current video frame as photo
      if (uiState.value.streamingMode == StreamingMode.PHONE) {
        // Deep-copy the pooled frame so subsequent decodes don't overwrite the captured photo.
        _videoFrame.value?.let { frame ->
          val snapshot = frame.copy(Bitmap.Config.ARGB_8888, false)
          _uiState.update { it.copy(capturedPhoto = snapshot, isShareDialogVisible = true) }
        }
        return
      }

      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        streamSession
            ?.capturePhoto()
            ?.onSuccess { photoData ->
              Log.d(TAG, "Photo capture successful")
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false) }
            }
            ?.onFailure {
              Log.e(TAG, "Photo capture failed")
              _uiState.update { it.copy(isCapturing = false) }
            }
      }
    } else {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
      )
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    val buffer = videoFrame.buffer
    val dataSize = buffer.remaining()
    val byteArray = ByteArray(dataSize)

    val originalPosition = buffer.position()
    buffer.get(byteArray)
    buffer.position(originalPosition)

    val timestampUs = if (frameTimestampBase == 0L) {
      frameTimestampBase = System.nanoTime() / 1000
      0L
    } else {
      System.nanoTime() / 1000 - frameTimestampBase
    }

    // Twitch gets the raw I420 frame — always first, before any decode work.
    // Wrap the already-copied byteArray to avoid SDK buffer aliasing on Dispatchers.Default.
    val safeBuffer = ByteBuffer.wrap(byteArray)
    twitchRawFrameCallback?.invoke(safeBuffer, videoFrame.width, videoFrame.height, timestampUs)

    // Determine if anyone needs a decoded Bitmap:
    // - UI needs it only when the screen is on
    // - Gemini needs it only when the session is active
    // - WebRTC needs it only when the session is active
    val geminiActive = geminiViewModel?.uiState?.value?.isGeminiActive == true
    val webrtcActive = webrtcViewModel?.uiState?.value?.isActive == true
    val needsBitmap = isScreenOn || geminiActive || webrtcActive

    if (!needsBitmap) {
      // Screen is off and no AI/WebRTC session running — skip expensive decode pipeline
      // to free CPU for the H.264 encoder and prevent quality degradation
      _uiState.update { it.copy(rawFrameWidth = videoFrame.width, rawFrameHeight = videoFrame.height) }
      return
    }

    val w = videoFrame.width
    val h = videoFrame.height
    val bitmap = acquireBitmap(w, h)
    i420ToBitmap(byteArray, w, h, bitmap)

    if (isScreenOn) {
      _videoFrame.value = bitmap
      _uiState.update {
        it.copy(
          rawFrameWidth = w,
          rawFrameHeight = h,
        )
      }
    } else {
      // Screen off but Gemini/WebRTC still active — update dimensions only
      _uiState.update { it.copy(rawFrameWidth = w, rawFrameHeight = h) }
    }

    if (geminiActive) geminiViewModel?.sendVideoFrameIfThrottled(bitmap)
    if (webrtcActive) webrtcViewModel?.pushVideoFrame(bitmap)
  }

  private fun acquireBitmap(w: Int, h: Int): Bitmap {
    var pool = bitmapPool
    if (pool == null || poolWidth != w || poolHeight != h) {
      pool = Array(BITMAP_POOL_SIZE) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
      bitmapPool = pool
      poolWidth = w
      poolHeight = h
      poolIndex = 0
      pixelsBuf = IntArray(w * h)
    }
    val bmp = pool[poolIndex]
    poolIndex = (poolIndex + 1) % pool.size
    return bmp
  }

  // Convert I420 (YYYYYYYY:UU:VV) directly into ARGB bitmap using BT.601 fixed-point.
  private fun i420ToBitmap(yuv: ByteArray, width: Int, height: Int, dest: Bitmap) {
    val pixels = pixelsBuf ?: IntArray(width * height).also { pixelsBuf = it }
    val frameSize = width * height
    val uOffset = frameSize
    val vOffset = frameSize + frameSize / 4
    val halfW = width / 2

    var j = 0
    while (j < height) {
      val uvRow = j shr 1
      val yRow0 = j * width
      val yRow1 = yRow0 + width
      val uvBase = uvRow * halfW
      var i = 0
      while (i < width) {
        val uvCol = i shr 1
        val u = (yuv[uOffset + uvBase + uvCol].toInt() and 0xFF) - 128
        val v = (yuv[vOffset + uvBase + uvCol].toInt() and 0xFF) - 128

        val rTmp = 359 * v
        val gTmp = -88 * u - 183 * v
        val bTmp = 454 * u

        // pixel (i, j)
        var y0 = yuv[yRow0 + i].toInt() and 0xFF
        if (y0 < 16) y0 = 16
        val y0s = (y0 - 16) * 298
        var r0 = (y0s + rTmp + 128) shr 8
        var g0 = (y0s + gTmp + 128) shr 8
        var b0 = (y0s + bTmp + 128) shr 8
        if (r0 < 0) r0 = 0 else if (r0 > 255) r0 = 255
        if (g0 < 0) g0 = 0 else if (g0 > 255) g0 = 255
        if (b0 < 0) b0 = 0 else if (b0 > 255) b0 = 255
        pixels[yRow0 + i] = (0xFF shl 24) or (r0 shl 16) or (g0 shl 8) or b0

        // pixel (i+1, j)
        if (i + 1 < width) {
          var y1 = yuv[yRow0 + i + 1].toInt() and 0xFF
          if (y1 < 16) y1 = 16
          val y1s = (y1 - 16) * 298
          var r1 = (y1s + rTmp + 128) shr 8
          var g1 = (y1s + gTmp + 128) shr 8
          var b1 = (y1s + bTmp + 128) shr 8
          if (r1 < 0) r1 = 0 else if (r1 > 255) r1 = 255
          if (g1 < 0) g1 = 0 else if (g1 > 255) g1 = 255
          if (b1 < 0) b1 = 0 else if (b1 > 255) b1 = 255
          pixels[yRow0 + i + 1] = (0xFF shl 24) or (r1 shl 16) or (g1 shl 8) or b1
        }

        // pixel (i, j+1)
        if (j + 1 < height) {
          var y2 = yuv[yRow1 + i].toInt() and 0xFF
          if (y2 < 16) y2 = 16
          val y2s = (y2 - 16) * 298
          var r2 = (y2s + rTmp + 128) shr 8
          var g2 = (y2s + gTmp + 128) shr 8
          var b2 = (y2s + bTmp + 128) shr 8
          if (r2 < 0) r2 = 0 else if (r2 > 255) r2 = 255
          if (g2 < 0) g2 = 0 else if (g2 > 255) g2 = 255
          if (b2 < 0) b2 = 0 else if (b2 > 255) b2 = 255
          pixels[yRow1 + i] = (0xFF shl 24) or (r2 shl 16) or (g2 shl 8) or b2

          // pixel (i+1, j+1)
          if (i + 1 < width) {
            var y3 = yuv[yRow1 + i + 1].toInt() and 0xFF
            if (y3 < 16) y3 = 16
            val y3s = (y3 - 16) * 298
            var r3 = (y3s + rTmp + 128) shr 8
            var g3 = (y3s + gTmp + 128) shr 8
            var b3 = (y3s + bTmp + 128) shr 8
            if (r3 < 0) r3 = 0 else if (r3 > 255) r3 = 255
            if (g3 < 0) g3 = 0 else if (g3 > 255) g3 = 255
            if (b3 < 0) b3 = 0 else if (b3 > 255) b3 = 255
            pixels[yRow1 + i + 1] = (0xFF shl 24) or (r3 shl 16) or (g3 shl 8) or b3
          }
        }
        i += 2
      }
      j += 2
    }
    dest.setPixels(pixels, 0, width, 0, 0, width, height)
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    unregisterScreenReceiver()
    stateJob?.cancel()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}