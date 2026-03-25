package com.meta.wearable.dat.externalsampleapps.cameraaccess.twitch

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager as SystemAudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class DirectRtmpEncoder(
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit,
) : ConnectChecker {

    companion object {
        private const val TAG = "DirectRtmpEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEFAULT_BITRATE = 2_000_000
        private const val DEFAULT_FPS = 24
        private const val I_FRAME_INTERVAL = 1

        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNELS = 1
        private const val AUDIO_BITRATE = 128_000
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC

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

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var rtmpClient: RtmpClient? = null
    private var encoder: MediaCodec? = null
    private var encoderJob: Job? = null

    // Audio
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecordJob: Job? = null
    private var audioEncoderJob: Job? = null
    private var audioStartTimeUs = 0L
    private var sysAudioManager: SystemAudioManager? = null
    private var scoStarted = false
    private var commDeviceSet = false
    private var modeChanged = false
    private var preferredMicDevice: AudioDeviceInfo? = null
    private var micContext: Context? = null

    private var videoWidth = 0
    private var videoHeight = 0
    @Volatile
    private var isStreaming = false

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    private var totalFrames = 0L
    private var droppedFrames = 0L
    private var lastLogTime = 0L

    private var baseTimestampUs = 0L
    private var frameIndex = 0L
    private val targetFrameDurationUs = 1_000_000L / DEFAULT_FPS

    // Measured throughput
    private val _measuredBitrateKbps = MutableStateFlow(0)
    val measuredBitrateKbps: StateFlow<Int> = _measuredBitrateKbps.asStateFlow()
    private var bitrateWindowBytes = 0L
    private var bitrateWindowStartMs = 0L

    fun start(rtmpUrl: String, width: Int, height: Int, bitrate: Int = DEFAULT_BITRATE) {
        if (isStreaming) {
            Log.w(TAG, "Already streaming")
            return
        }

        Log.d(TAG, "Starting direct RTMP: ${width}x${height} @ $bitrate bps -> ${rtmpUrl.take(40)}...")
        videoWidth = width
        videoHeight = height

        if (!initEncoder(width, height, bitrate)) {
            onError("Failed to initialize H.264 encoder")
            return
        }

        initAudioEncoder()

        rtmpClient = RtmpClient(this).also { client ->
            val channelCount = AUDIO_CHANNELS
            client.setAudioInfo(AUDIO_SAMPLE_RATE, channelCount == 2)
            client.connect(rtmpUrl)
        }

        isStreaming = true
        totalFrames = 0
        droppedFrames = 0
        lastLogTime = 0
        baseTimestampUs = 0
        frameIndex = 0
        audioStartTimeUs = 0L

        startEncoderOutputProcessing()
        startAudioCapture()
    }

    private fun initAudioEncoder() {
        try {
            val format = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }
            audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            Log.d(TAG, "AAC encoder initialized: ${AUDIO_SAMPLE_RATE}Hz ch=$AUDIO_CHANNELS ${AUDIO_BITRATE / 1000}kbps")
        } catch (e: Exception) {
            Log.e(TAG, "AAC encoder init failed: ${e.message}")
            audioEncoder = null
        }
    }

    fun setMicDevice(context: Context, device: AudioDeviceInfo?) {
        micContext = context
        preferredMicDevice = device
    }

    private fun startAudioCapture() {
        val aenc = audioEncoder ?: return
        val channelConfig = if (AUDIO_CHANNELS == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { Log.e(TAG, "AudioRecord minBufSize invalid: $minBuf"); return }
        val bufSize = minBuf * 4

        val isBtSco = preferredMicDevice != null && preferredMicDevice!!.type in BT_SCO_TYPES
        val isBt    = preferredMicDevice != null && preferredMicDevice!!.type in BT_ALL_TYPES

        if (preferredMicDevice != null && micContext != null) {
            val am = micContext!!.getSystemService(Context.AUDIO_SERVICE) as SystemAudioManager
            sysAudioManager = am

            if (isBt) {
                am.mode = SystemAudioManager.MODE_IN_COMMUNICATION
                modeChanged = true
                Log.d(TAG, "AudioManager mode → MODE_IN_COMMUNICATION (type=${preferredMicDevice!!.type})")
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

            val ok = am.setCommunicationDevice(preferredMicDevice!!)
            commDeviceSet = ok
            Log.d(TAG, "setCommunicationDevice '${preferredMicDevice!!.productName}' (type=${preferredMicDevice!!.type}): $ok")
        } else {
            Log.d(TAG, "No preferred mic device — using system default")
        }

        val audioSource = if (isBt) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                          else MediaRecorder.AudioSource.MIC

        try {
            val ar = AudioRecord(
                audioSource,
                AUDIO_SAMPLE_RATE, channelConfig,
                AudioFormat.ENCODING_PCM_16BIT, bufSize,
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized"); ar.release(); return
            }
            if (preferredMicDevice != null) {
                val ok = ar.setPreferredDevice(preferredMicDevice)
                Log.d(TAG, "AudioRecord.setPreferredDevice '${preferredMicDevice!!.productName}': $ok")
            }
            audioRecord = ar
            ar.startRecording()
            Log.d(TAG, "AudioRecord started (source=$audioSource, bufSize=$bufSize)")
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start failed: ${e.message}"); return
        }

        // Feed PCM into AAC encoder
        audioRecordJob = scope.launch(Dispatchers.IO) {
            val pcmBuf = ShortArray(minBuf / 2)
            while (isStreaming) {
                val read = audioRecord?.read(pcmBuf, 0, pcmBuf.size) ?: break
                if (read <= 0) continue
                val inputIndex = aenc.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val buf = aenc.getInputBuffer(inputIndex) ?: continue
                    buf.clear()
                    for (s in pcmBuf.take(read)) {
                        buf.put((s.toInt() and 0xFF).toByte())
                        buf.put((s.toInt() shr 8 and 0xFF).toByte())
                    }
                    val nowUs = System.nanoTime() / 1000
                    if (audioStartTimeUs == 0L) audioStartTimeUs = nowUs
                    aenc.queueInputBuffer(inputIndex, 0, read * 2, nowUs - audioStartTimeUs, 0)
                }
            }
        }

        // Drain AAC encoder → RTMP
        audioEncoderJob = scope.launch(Dispatchers.IO) {
            val bufInfo = MediaCodec.BufferInfo()
            while (isStreaming) {
                val outIndex = aenc.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIndex >= 0) {
                    val outBuf = aenc.getOutputBuffer(outIndex)
                    if (outBuf == null) {
                        aenc.releaseOutputBuffer(outIndex, false)
                    } else {
                        if (bufInfo.size > 0 && (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            rtmpClient?.sendAudio(outBuf, bufInfo)
                        }
                        aenc.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        }
    }

    private fun stopAudio() {
        audioRecordJob?.cancel(); audioRecordJob = null
        audioEncoderJob?.cancel(); audioEncoderJob = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioEncoder?.stop(); audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null
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
                Log.d(TAG, "AudioManager mode restored → MODE_NORMAL")
            } catch (e: Exception) {
                Log.w(TAG, "AudioManager mode restore error: ${e.message}")
            }
            modeChanged = false
        }
        sysAudioManager = null
    }

    private fun initEncoder(width: Int, height: Int, bitrate: Int): Boolean {
        try {
            encoder = MediaCodec.createEncoderByType(MIME_TYPE)

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, DEFAULT_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                )
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }

            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder?.start()
            Log.d(TAG, "H.264 encoder initialized: ${width}x${height} I420")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Encoder init failed: ${e.message}", e)
            return false
        }
    }

    private fun startEncoderOutputProcessing() {
        encoderJob = scope.launch(Dispatchers.IO) {
            val bufferInfo = MediaCodec.BufferInfo()

            while (isStreaming) {
                try {
                    val outputIndex = encoder?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1

                    when {
                        outputIndex >= 0 -> {
                            val outputBuffer = encoder?.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    extractSpsPps(outputBuffer, bufferInfo.size)
                                } else {
                                    sendH264Data(outputBuffer, bufferInfo)
                                }
                            }
                            encoder?.releaseOutputBuffer(outputIndex, false)
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.d(TAG, "Encoder format changed: ${encoder?.outputFormat}")
                        }
                    }
                } catch (e: Exception) {
                    if (isStreaming) {
                        Log.e(TAG, "Encoder output error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun extractSpsPps(buffer: ByteBuffer, size: Int) {
        val data = ByteArray(size)
        buffer.get(data)
        buffer.rewind()

        var spsStart = -1
        var spsEnd = -1
        var ppsStart = -1

        for (i in 0 until size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                if (spsStart == -1) {
                    spsStart = i + 4
                } else if (spsEnd == -1) {
                    spsEnd = i
                    ppsStart = i + 4
                }
            }
        }

        if (spsStart >= 0 && spsEnd > spsStart && ppsStart >= 0) {
            sps = data.copyOfRange(spsStart, spsEnd)
            pps = data.copyOfRange(ppsStart, size)
            Log.d(TAG, "SPS/PPS extracted: SPS=${sps?.size}B, PPS=${pps?.size}B")

            val localSps = sps
            val localPps = pps
            if (localSps != null && localPps != null) {
                rtmpClient?.setVideoInfo(ByteBuffer.wrap(localSps), ByteBuffer.wrap(localPps), null)
            }
        }
    }

    private fun sendH264Data(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val data = ByteArray(bufferInfo.size)
        buffer.get(data)
        rtmpClient?.sendVideo(ByteBuffer.wrap(data), bufferInfo)
        trackBitrateBytes(data.size.toLong())
    }

    private fun trackBitrateBytes(bytes: Long) {
        val now = System.currentTimeMillis()
        if (bitrateWindowStartMs == 0L) bitrateWindowStartMs = now
        bitrateWindowBytes += bytes
        val elapsed = now - bitrateWindowStartMs
        if (elapsed >= 1000L) {
            // bits per ms == kbps; multiply by 8 for bits, elapsed already in ms
            val kbps = (bitrateWindowBytes * 8L / elapsed).toInt()
            _measuredBitrateKbps.value = kbps
            bitrateWindowBytes = 0L
            bitrateWindowStartMs = now
        }
    }

    /** Dynamically update the encoder bitrate without stopping the stream. */
    fun updateBitrate(bitrateBps: Int) {
        val params = Bundle().apply { putInt(MediaFormat.KEY_BIT_RATE, bitrateBps) }
        try {
            encoder?.setParameters(params)
            Log.d(TAG, "Dynamic bitrate update → ${bitrateBps / 1000} kbps")
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic bitrate update failed: ${e.message}")
        }
    }

    fun feedFrame(buffer: ByteBuffer, width: Int, height: Int, timestampUs: Long) {
        if (!isStreaming || encoder == null) return

        totalFrames++

        try {
            val inputIndex = encoder?.dequeueInputBuffer(10000) ?: -1
            if (inputIndex >= 0) {
                val inputBuffer = encoder?.getInputBuffer(inputIndex)
                inputBuffer?.clear()

                val position = buffer.position()
                val dataSize = buffer.remaining()

                val expectedSize = width * height * 3 / 2
                if (dataSize != expectedSize) {
                    Log.w(TAG, "Frame size mismatch: expected=$expectedSize got=$dataSize, dropping")
                    buffer.position(position)
                    encoder?.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    return
                }

                val inputCapacity = inputBuffer?.remaining() ?: 0
                if (dataSize > inputCapacity) {
                    Log.w(TAG, "Frame too large for buffer: $dataSize > $inputCapacity, dropping")
                    buffer.position(position)
                    encoder?.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    return
                }

                val frameCopy = ByteArray(dataSize)
                buffer.get(frameCopy)
                buffer.position(position)

                inputBuffer?.put(frameCopy)

                if (baseTimestampUs == 0L) {
                    baseTimestampUs = timestampUs
                }
                val smoothedTimestamp = baseTimestampUs + (frameIndex * targetFrameDurationUs)
                frameIndex++

                encoder?.queueInputBuffer(inputIndex, 0, dataSize, smoothedTimestamp, 0)
            } else {
                droppedFrames++
                if (droppedFrames % 10 == 0L || droppedFrames <= 3) {
                    Log.w(TAG, "Frame dropped — encoder queue full (dropped: $droppedFrames/$totalFrames)")
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastLogTime > 5000) {
                val dropRate = if (totalFrames > 0) droppedFrames * 100 / totalFrames else 0
                Log.d(TAG, "Stats: total=$totalFrames dropped=$droppedFrames ($dropRate%) idx=$frameIndex")
                lastLogTime = now
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding frame: ${e.message}", e)
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping direct RTMP")
        isStreaming = false

        stopAudio()

        encoderJob?.cancel()
        encoderJob = null

        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder: ${e.message}")
        }
        encoder = null

        try {
            rtmpClient?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting RTMP: ${e.message}")
        }
        rtmpClient = null

        sps = null
        pps = null
        totalFrames = 0
        droppedFrames = 0
        lastLogTime = 0
        baseTimestampUs = 0
        frameIndex = 0
        audioStartTimeUs = 0
        bitrateWindowBytes = 0L
        bitrateWindowStartMs = 0L
        _measuredBitrateKbps.value = 0

        Log.d(TAG, "Direct RTMP stopped")
    }

    fun release() {
        stop()
        scope.cancel()
    }

    fun isActive(): Boolean = isStreaming

    override fun onConnectionStarted(rtmpUrl: String) {
        Log.d(TAG, "RTMP connection started")
    }

    override fun onConnectionSuccess() {
        Log.d(TAG, "RTMP connected")
        onConnected()
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTMP connection failed: $reason")
        onError(reason)
        stop()
    }

    override fun onNewBitrate(bitrate: Long) {
        Log.d(TAG, "RTMP bitrate: $bitrate")
    }

    override fun onDisconnect() {
        Log.d(TAG, "RTMP disconnected")
        stop()
        onDisconnected()
    }

    override fun onAuthError() {
        Log.e(TAG, "RTMP auth error (bad stream key?)")
        onError("Auth failed — check stream key")
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "RTMP auth OK")
    }
}
