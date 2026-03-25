package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager as SystemAudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class AudioManager {
    companion object {
        private const val TAG = "AudioManager"
        private const val MIN_SEND_BYTES = 3200 // 100ms at 16kHz mono Int16 = 1600 frames * 2 bytes
        private val BT_SCO_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
        private val BT_ALL_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )
    }

    var onAudioCaptured: ((ByteArray) -> Unit)? = null

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureThread: Thread? = null
    @Volatile
    private var isCapturing = false
    private val accumulatedData = ByteArrayOutputStream()
    private val accumulateLock = Any()

    private var sysAudioManager: SystemAudioManager? = null
    private var scoStarted = false
    private var commDeviceSet = false
    private var modeChanged = false

    @SuppressLint("MissingPermission")
    fun startCapture(context: Context? = null, preferredDevice: AudioDeviceInfo? = null) {
        if (isCapturing) return

        val isBtSco = preferredDevice != null && preferredDevice.type in BT_SCO_TYPES
        val isAnyBt = preferredDevice != null && preferredDevice.type in BT_ALL_TYPES

        if (context != null && preferredDevice != null) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as SystemAudioManager
            sysAudioManager = am

            // All BT types need MODE_IN_COMMUNICATION for proper bidirectional audio routing
            if (isAnyBt) {
                am.mode = SystemAudioManager.MODE_IN_COMMUNICATION
                modeChanged = true
                Log.d(TAG, "Set MODE_IN_COMMUNICATION for BT device type=${preferredDevice.type}")
            }

            if (isBtSco) {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                scoStarted = true
                Log.d(TAG, "BT SCO started, waiting...")
                val deadline = System.currentTimeMillis() + 2000
                while (!am.isBluetoothScoOn && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                }
                Log.d(TAG, "BT SCO isOn=${am.isBluetoothScoOn}")
            }

            val ok = am.setCommunicationDevice(preferredDevice)
            commDeviceSet = ok
            Log.d(TAG, "setCommunicationDevice '${preferredDevice.productName}' (type=${preferredDevice.type}): $ok")
        } else {
            Log.d(TAG, "No preferred device — using system default mic")
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            GeminiConfig.INPUT_AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioSource = if (isAnyBt) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                          else MediaRecorder.AudioSource.VOICE_RECOGNITION

        audioRecord = AudioRecord(
            audioSource,
            GeminiConfig.INPUT_AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (preferredDevice != null) {
            val routed = audioRecord?.setPreferredDevice(preferredDevice)
            Log.d(TAG, "AudioRecord.setPreferredDevice '${preferredDevice.productName}' (type=${preferredDevice.type}): $routed")
        }

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(GeminiConfig.OUTPUT_AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    GeminiConfig.OUTPUT_AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ) * 4
            )
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize — mic may be held by another process")
            audioRecord?.release()
            audioRecord = null
            return
        }

        audioRecord?.startRecording()
        audioTrack?.play()
        isCapturing = true

        synchronized(accumulateLock) {
            accumulatedData.reset()
        }

        captureThread = Thread({
            val buffer = ByteArray(bufferSize)
            var tapCount = 0
            while (isCapturing) {
                try {
                    val record = audioRecord ?: break
                    val read = record.read(buffer, 0, buffer.size)
                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        Log.w(TAG, "AudioRecord dead object, stopping capture")
                        break
                    }
                    if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                        Log.w(TAG, "AudioRecord invalid operation, stopping capture")
                        break
                    }
                    if (read > 0) {
                        tapCount++
                        // Compute RMS for mic level indicator (PCM16 LE samples)
                        val sampleCount = read / 2
                        if (sampleCount > 0) {
                            var sumSq = 0.0
                            for (i in 0 until sampleCount) {
                                val lo = buffer[i * 2].toInt() and 0xFF
                                val hi = buffer[i * 2 + 1].toInt()
                                val s = (hi shl 8) or lo
                                val signed = if (s >= 32768) s - 65536 else s
                                sumSq += signed.toDouble() * signed
                            }
                            val rms = sqrt(sumSq / sampleCount)
                            _micLevel.value = (rms / 8000.0).coerceIn(0.0, 1.0).toFloat()
                        }
                        synchronized(accumulateLock) {
                            accumulatedData.write(buffer, 0, read)
                            if (accumulatedData.size() >= MIN_SEND_BYTES) {
                                val chunk = accumulatedData.toByteArray()
                                accumulatedData.reset()
                                if (tapCount <= 3) {
                                    Log.d(TAG, "Sending chunk: ${chunk.size} bytes (~${chunk.size / 32}ms)")
                                }
                                onAudioCaptured?.invoke(chunk)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Audio capture error: ${e.message}")
                    break
                }
            }
        }, "audio-capture").also { it.start() }

        Log.d(TAG, "Audio capture started (16kHz mono PCM16)")
    }

    fun playAudio(data: ByteArray) {
        val track = audioTrack ?: return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            try { track.play() } catch (e: Exception) {
                Log.w(TAG, "AudioTrack play error: ${e.message}")
                return
            }
        }
        try {
            track.write(data, 0, data.size)
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack write error: ${e.message}")
        }
    }

    fun stopPlayback() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "stopPlayback error: ${e.message}")
        }
    }

    fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        _micLevel.value = 0f

        captureThread?.join(1000)
        captureThread = null

        if (commDeviceSet) {
            try {
                sysAudioManager?.clearCommunicationDevice()
                Log.d(TAG, "clearCommunicationDevice")
            } catch (e: Exception) {
                Log.w(TAG, "clearCommunicationDevice error: ${e.message}")
            }
            commDeviceSet = false
        }
        if (scoStarted) {
            try {
                sysAudioManager?.stopBluetoothSco()
                sysAudioManager?.isBluetoothScoOn = false
                Log.d(TAG, "BT SCO stopped")
            } catch (e: Exception) {
                Log.w(TAG, "BT SCO stop error: ${e.message}")
            }
            scoStarted = false
        }
        if (modeChanged) {
            try {
                sysAudioManager?.mode = SystemAudioManager.MODE_NORMAL
                Log.d(TAG, "Restored MODE_NORMAL")
            } catch (e: Exception) {
                Log.w(TAG, "Audio mode restore error: ${e.message}")
            }
            modeChanged = false
        }
        sysAudioManager = null

        synchronized(accumulateLock) {
            if (accumulatedData.size() > 0) {
                val chunk = accumulatedData.toByteArray()
                accumulatedData.reset()
                onAudioCaptured?.invoke(chunk)
            }
        }

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord stop error: ${e.message}")
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord release error: ${e.message}")
        }
        audioRecord = null

        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack stop error: ${e.message}")
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack release error: ${e.message}")
        }
        audioTrack = null

        Log.d(TAG, "Audio capture stopped")
    }
}
