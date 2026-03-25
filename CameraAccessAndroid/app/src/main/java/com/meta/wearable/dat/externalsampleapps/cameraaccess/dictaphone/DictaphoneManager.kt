package com.meta.wearable.dat.externalsampleapps.cameraaccess.dictaphone

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager as SystemAudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(
    val id: String,
    val name: String,
    val filePath: String,
    val durationMs: Long,
    val createdAt: Long,
    val sizeBytes: Long,
) {
    val formattedDuration: String
        get() {
            val totalSec = durationMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "%d:%02d".format(min, sec)
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            return sdf.format(Date(createdAt))
        }

    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            return if (kb > 1024) "%.1f MB".format(kb / 1024.0)
            else "%.0f KB".format(kb)
        }
}

class DictaphoneManager(private val context: Context) {

    companion object {
        private const val TAG = "DictaphoneManager"
        private const val DIR_NAME = "dictaphone"
        private const val SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128_000
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

    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private var recordingStartTime: Long = 0L

    // AudioRecord path
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var audioTrackIndex = -1
    private var recordingThread: Thread? = null
    @Volatile
    private var isRecordingActive = false

    private var sysAudioManager: SystemAudioManager? = null
    private var scoStarted = false
    private var commDeviceSet = false
    private var modeChanged = false

    private val recordingsDir: File
        get() {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    @SuppressLint("MissingPermission")
    fun startRecording(preferredDevice: AudioDeviceInfo? = null): Boolean {
        if (isRecordingActive) return false
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(recordingsDir, "REC_$timestamp.m4a")
            currentFile = file

            val isBtSco = preferredDevice != null && preferredDevice.type in BT_SCO_TYPES
            val isAnyBt = preferredDevice != null && preferredDevice.type in BT_ALL_TYPES

            if (preferredDevice != null) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as SystemAudioManager
                sysAudioManager = am

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

            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = minBuf * 4

            val audioSource = if (isAnyBt) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                              else MediaRecorder.AudioSource.VOICE_RECOGNITION

            val ar = AudioRecord(
                audioSource,
                SAMPLE_RATE, channelConfig,
                AudioFormat.ENCODING_PCM_16BIT, bufSize,
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                ar.release()
                currentFile = null
                return false
            }
            audioRecord = ar

            if (preferredDevice != null) {
                val routed = ar.setPreferredDevice(preferredDevice)
                Log.d(TAG, "AudioRecord.setPreferredDevice '${preferredDevice.productName}' (type=${preferredDevice.type}): $routed")
            }

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            audioEncoder = enc

            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mediaMuxer = muxer
            audioTrackIndex = -1

            ar.startRecording()
            isRecordingActive = true
            recordingStartTime = System.currentTimeMillis()

            recordingThread = Thread({
                recordingLoop(enc, muxer, bufSize)
            }, "dictaphone-capture").also { it.start() }

            Log.d(TAG, "Recording started: ${file.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseRecordingResources()
            currentFile = null
            false
        }
    }

    private fun recordingLoop(enc: MediaCodec, muxer: MediaMuxer, bufSize: Int) {
        val pcmBuf = ByteArray(bufSize)
        val bufInfo = MediaCodec.BufferInfo()
        var muxerStarted = false
        var presentationTimeUs = 0L
        val bytesPerSec = SAMPLE_RATE * 2 // mono 16-bit

        while (isRecordingActive) {
            // Feed PCM into encoder
            val ar = audioRecord ?: break
            val read = ar.read(pcmBuf, 0, pcmBuf.size)
            if (read > 0) {
                val inputIndex = enc.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val buf = enc.getInputBuffer(inputIndex)
                    buf?.clear()
                    buf?.put(pcmBuf, 0, read)
                    enc.queueInputBuffer(inputIndex, 0, read, presentationTimeUs, 0)
                    presentationTimeUs += (read.toLong() * 1_000_000L) / bytesPerSec
                }
            }

            // Drain encoder output
            while (true) {
                val outIndex = enc.dequeueOutputBuffer(bufInfo, 0)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            audioTrackIndex = muxer.addTrack(enc.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            Log.d(TAG, "MediaMuxer started, track=$audioTrackIndex")
                        }
                    }
                    outIndex >= 0 -> {
                        val outBuf = enc.getOutputBuffer(outIndex)
                        if (outBuf != null && bufInfo.size > 0 &&
                            (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                            muxerStarted) {
                            muxer.writeSampleData(audioTrackIndex, outBuf, bufInfo)
                        }
                        enc.releaseOutputBuffer(outIndex, false)
                        if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                    else -> break
                }
            }
        }

        // Flush encoder EOS
        try {
            val inputIndex = enc.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                enc.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            val bufInfo2 = MediaCodec.BufferInfo()
            var eosDrained = false
            while (!eosDrained) {
                val outIndex = enc.dequeueOutputBuffer(bufInfo2, 10_000)
                if (outIndex >= 0) {
                    val outBuf = enc.getOutputBuffer(outIndex)
                    if (outBuf != null && bufInfo2.size > 0 &&
                        (bufInfo2.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                        muxerStarted) {
                        muxer.writeSampleData(audioTrackIndex, outBuf, bufInfo2)
                    }
                    enc.releaseOutputBuffer(outIndex, false)
                    if ((bufInfo2.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eosDrained = true
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "EOS flush error: ${e.message}")
        }

        try { if (muxerStarted) muxer.stop(); muxer.release() } catch (e: Exception) {
            Log.w(TAG, "Muxer stop error: ${e.message}")
        }
        Log.d(TAG, "Recording loop finished")
    }

    fun stopRecording(): Recording? {
        if (!isRecordingActive) return null
        isRecordingActive = false

        try { audioRecord?.stop() } catch (_: Exception) {}
        recordingThread?.join(3000)
        recordingThread = null

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

        releaseRecordingResources()

        val file = currentFile ?: return null
        currentFile = null

        val duration = System.currentTimeMillis() - recordingStartTime

        return if (file.exists() && file.length() > 0) {
            val recording = Recording(
                id = file.nameWithoutExtension,
                name = file.nameWithoutExtension.replace("_", " "),
                filePath = file.absolutePath,
                durationMs = duration,
                createdAt = System.currentTimeMillis(),
                sizeBytes = file.length(),
            )
            Log.d(TAG, "Recording saved: ${file.name}, duration=${recording.formattedDuration}")
            recording
        } else {
            Log.w(TAG, "Recording file missing or empty: ${file.absolutePath}")
            null
        }
    }

    fun getElapsedMs(): Long {
        return if (recordingStartTime > 0 && isRecordingActive) System.currentTimeMillis() - recordingStartTime else 0
    }

    private fun releaseRecordingResources() {
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioEncoder?.stop(); audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null
        mediaMuxer = null
    }

    fun loadRecordings(): List<Recording> {
        val files = recordingsDir.listFiles { f -> f.extension == "m4a" } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.mapNotNull { file ->
            try {
                val mp = MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
                val duration = mp.duration.toLong()
                mp.release()

                Recording(
                    id = file.nameWithoutExtension,
                    name = file.nameWithoutExtension.replace("_", " "),
                    filePath = file.absolutePath,
                    durationMs = duration,
                    createdAt = file.lastModified(),
                    sizeBytes = file.length(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read recording: ${file.name}", e)
                null
            }
        }
    }

    fun playRecording(recording: Recording, onComplete: () -> Unit): Boolean {
        stopPlayback()
        return try {
            player = MediaPlayer().apply {
                setDataSource(recording.filePath)
                prepare()
                setOnCompletionListener { onComplete() }
                start()
            }
            Log.d(TAG, "Playing: ${recording.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play recording", e)
            false
        }
    }

    fun stopPlayback() {
        try {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        player = null
    }

    fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (_: Exception) { false }

    fun getPlaybackPosition(): Int = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }

    fun getPlaybackDuration(): Int = try { player?.duration ?: 0 } catch (_: Exception) { 0 }

    fun seekTo(position: Int) { try { player?.seekTo(position) } catch (_: Exception) {} }

    fun deleteRecording(recording: Recording): Boolean {
        stopPlayback()
        return try {
            File(recording.filePath).delete().also {
                Log.d(TAG, "Deleted recording: ${recording.name} = $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete recording", e)
            false
        }
    }

    fun release() {
        isRecordingActive = false
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioEncoder?.stop(); audioEncoder?.release() } catch (_: Exception) {}
        audioEncoder = null
        stopPlayback()
    }
}
