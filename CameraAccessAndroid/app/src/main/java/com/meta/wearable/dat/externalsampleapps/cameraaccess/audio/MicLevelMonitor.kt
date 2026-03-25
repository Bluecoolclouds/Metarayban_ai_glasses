package com.meta.wearable.dat.externalsampleapps.cameraaccess.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class MicLevelMonitor {

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, preferredDevice: AudioDeviceInfo? = null) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(2048)

            val audioSource = if (preferredDevice != null) MediaRecorder.AudioSource.MIC
                              else MediaRecorder.AudioSource.VOICE_RECOGNITION

            val ar = try {
                AudioRecord(
                    audioSource,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf,
                )
            } catch (e: Exception) {
                Log.w(TAG, "MicLevelMonitor: AudioRecord create failed: ${e.message}")
                return@launch
            }

            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "MicLevelMonitor: AudioRecord not initialized")
                ar.release()
                return@launch
            }

            if (preferredDevice != null) {
                val ok = ar.setPreferredDevice(preferredDevice)
                Log.d(TAG, "MicLevelMonitor setPreferredDevice '${preferredDevice.productName}': $ok")
            }

            ar.startRecording()
            Log.d(TAG, "MicLevelMonitor started (device=${preferredDevice?.productName ?: "default"})")

            val buf = ShortArray(minBuf / 2)
            try {
                while (isActive) {
                    val read = ar.read(buf, 0, buf.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) sum += buf[i].toDouble() * buf[i]
                        val rms = sqrt(sum / read)
                        val normalized = (rms / 8000.0).coerceIn(0.0, 1.0).toFloat()
                        _level.value = normalized
                    }
                }
            } finally {
                try { ar.stop(); ar.release() } catch (_: Exception) {}
                _level.value = 0f
                Log.d(TAG, "MicLevelMonitor stopped")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "MicLevelMonitor"
    }
}
