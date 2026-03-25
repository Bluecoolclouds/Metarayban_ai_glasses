package com.meta.wearable.dat.externalsampleapps.cameraaccess.twitch

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

data class TwitchStreamState(
    val isStreaming: Boolean = false,
    val isConnecting: Boolean = false,
    val error: String? = null,
    val reconnectAttempt: Int = 0,
    val isDirectEncoding: Boolean = false,
)

class TwitchStreamManager(
    private val context: Context,
) : ConnectChecker {

    companion object {
        private const val TAG = "TwitchStream"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
        private const val VIDEO_BITRATE = 2500 * 1024
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128 * 1024
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val DIRECT_ENCODE_BITRATE = 2_000_000
    }

    private var rtmpCamera: RtmpCamera2? = null
    private var startTime: Long = 0
    private var currentUrl: String? = null
    private var currentView: OpenGlView? = null
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private val handler = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var bitrateCollectJob: Job? = null

    private val _state = MutableStateFlow(TwitchStreamState())
    val state: StateFlow<TwitchStreamState> = _state.asStateFlow()

    private val _measuredBitrateKbps = MutableStateFlow(0)
    val measuredBitrateKbps: StateFlow<Int> = _measuredBitrateKbps.asStateFlow()

    val isStreaming: Boolean
        get() = _state.value.isStreaming

    private var directEncoder: DirectRtmpEncoder? = null

    /** Apply a new bitrate to the running encoder without stopping the stream. */
    fun updateBitrate(bitrateBps: Int) {
        directEncoder?.updateBitrate(bitrateBps)
    }

    fun start(rtmpUrl: String, openGlView: OpenGlView) {
        if (_state.value.isStreaming || _state.value.isConnecting) {
            Log.w(TAG, "Already streaming or connecting")
            return
        }

        Log.d(TAG, "Starting direct RTMP stream to: ${rtmpUrl.take(40)}...")
        _state.value = TwitchStreamState(isConnecting = true)
        currentUrl = rtmpUrl
        currentView = openGlView
        shouldReconnect = true
        reconnectAttempts = 0

        startInternal(rtmpUrl, openGlView)
    }

    fun startDirectEncoding(
        rtmpUrl: String,
        width: Int,
        height: Int,
        preferredMicDevice: AudioDeviceInfo? = null,
    ) {
        if (_state.value.isStreaming || _state.value.isConnecting) {
            Log.w(TAG, "Already streaming or connecting")
            return
        }

        Log.d(TAG, "Starting direct H.264 encoding → RTMP (${width}x${height})")
        _state.value = TwitchStreamState(isConnecting = true, isDirectEncoding = true)
        currentUrl = rtmpUrl
        startTime = System.currentTimeMillis()

        directEncoder = DirectRtmpEncoder(
            onConnected = {
                Log.d(TAG, "Direct encoder connected to RTMP")
                _state.value = TwitchStreamState(isStreaming = true, isDirectEncoding = true)
            },
            onDisconnected = {
                Log.d(TAG, "Direct encoder disconnected")
                _state.value = TwitchStreamState()
                directEncoder = null
            },
            onError = { reason ->
                Log.e(TAG, "Direct encoder error: $reason")
                _state.value = TwitchStreamState(error = reason)
                directEncoder?.stop()
                directEncoder = null
            },
        )

        directEncoder?.setMicDevice(context, preferredMicDevice)
        directEncoder?.start(rtmpUrl, width, height, SettingsManager.effectiveBitrateBps())

        // Collect measured bitrate from encoder into our public StateFlow
        val enc = directEncoder ?: return
        bitrateCollectJob?.cancel()
        bitrateCollectJob = scope.launch {
            enc.measuredBitrateKbps.collect { _measuredBitrateKbps.value = it }
        }
    }

    fun feedRawFrame(buffer: ByteBuffer, width: Int, height: Int, timestampUs: Long) {
        directEncoder?.feedFrame(buffer, width, height, timestampUs)
    }

    private fun startInternal(rtmpUrl: String, openGlView: OpenGlView) {
        try {
            releaseCamera()

            val camera = RtmpCamera2(openGlView, this)

            val (resW, resH) = SettingsManager.resolutionFor(SettingsManager.streamResolution)
            val videoBitrate = SettingsManager.effectiveBitrateBps()
            val videoPrepared = camera.prepareVideo(resW, resH, videoBitrate)
            val audioPrepared = camera.prepareAudio(
                AUDIO_BITRATE, AUDIO_SAMPLE_RATE, true
            )

            if (videoPrepared == false) {
                Log.e(TAG, "Failed to prepare video encoder")
                _state.value = TwitchStreamState(error = "Video encoder init failed")
                return
            }
            if (audioPrepared == false) {
                Log.w(TAG, "Audio not available, streaming video only")
            }

            camera.startStream(rtmpUrl)
            rtmpCamera = camera
            startTime = System.currentTimeMillis()

            Log.d(TAG, "RTMP stream initiated (video=$videoPrepared, audio=$audioPrepared)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTMP stream", e)
            _state.value = TwitchStreamState(error = "Start failed: ${e.message}")
            releaseCamera()
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping stream (directEncoding=${_state.value.isDirectEncoding})")
        bitrateCollectJob?.cancel()
        bitrateCollectJob = null
        _measuredBitrateKbps.value = 0

        if (_state.value.isDirectEncoding || directEncoder != null) {
            directEncoder?.stop()
            directEncoder = null
            _state.value = TwitchStreamState()
            return
        }

        shouldReconnect = false
        reconnectAttempts = 0
        handler.removeCallbacksAndMessages(null)
        release()
        _state.value = TwitchStreamState()
    }

    private fun releaseCamera() {
        try {
            rtmpCamera?.let { camera ->
                if (camera.isStreaming) camera.stopStream()
                if (camera.isOnPreview) camera.stopPreview()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during camera release", e)
        }
        rtmpCamera = null
    }

    fun release() {
        directEncoder?.release()
        directEncoder = null
        releaseCamera()
        currentUrl = null
        currentView = null
        startTime = 0
    }

    fun getDurationSeconds(): Int {
        if (startTime == 0L) return 0
        return ((System.currentTimeMillis() - startTime) / 1000).toInt()
    }

    private fun attemptReconnect() {
        val url = currentUrl ?: return
        val view = currentView ?: return
        if (!shouldReconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached")
            _state.value = TwitchStreamState(error = "Reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts")
            shouldReconnect = false
            return
        }

        reconnectAttempts++
        Log.d(TAG, "Reconnecting in ${RECONNECT_DELAY_MS}ms (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
        _state.value = TwitchStreamState(isConnecting = true, reconnectAttempt = reconnectAttempts)

        handler.postDelayed({
            if (shouldReconnect && rtmpCamera?.isStreaming != true) {
                startInternal(url, view)
            }
        }, RECONNECT_DELAY_MS)
    }

    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "RTMP connection started")
    }

    override fun onConnectionSuccess() {
        Log.d(TAG, "RTMP connected successfully")
        reconnectAttempts = 0
        _state.value = TwitchStreamState(isStreaming = true)
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTMP connection failed: $reason")
        _state.value = TwitchStreamState(error = reason)
        releaseCamera()
        attemptReconnect()
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        Log.d(TAG, "RTMP disconnected")
        releaseCamera()
        attemptReconnect()
    }

    override fun onAuthError() {
        Log.e(TAG, "RTMP auth error (bad stream key?)")
        _state.value = TwitchStreamState(error = "Auth failed — check stream key")
        shouldReconnect = false
        releaseCamera()
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "RTMP auth OK")
    }
}
