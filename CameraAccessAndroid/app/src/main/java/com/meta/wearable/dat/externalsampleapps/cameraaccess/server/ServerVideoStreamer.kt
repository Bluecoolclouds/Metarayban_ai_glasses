package com.meta.wearable.dat.externalsampleapps.cameraaccess.server

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class ServerVideoStreamer(
    private val context: Context,
    private val onChunk: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "ServerVideoStreamer"
        private const val CHUNK_SIZE = 32 * 1024
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 2_000_000
        private const val FRAME_RATE = 30
        private const val AUDIO_BITRATE = 128_000
        private const val AUDIO_SAMPLE_RATE = 44100
    }

    private var mediaRecorder: MediaRecorder? = null
    private var readPfd: ParcelFileDescriptor? = null
    private var writePfd: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    fun prepare(): Surface {
        val pipes = ParcelFileDescriptor.createPipe()
        readPfd = pipes[0]
        writePfd = pipes[1]

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            setVideoFrameRate(FRAME_RATE)
            setVideoEncodingBitRate(VIDEO_BITRATE)
            setAudioEncodingBitRate(AUDIO_BITRATE)
            setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            setOutputFile(writePfd!!.fileDescriptor)
            prepare()
        }

        mediaRecorder = recorder
        Log.d(TAG, "MediaRecorder prepared (MPEG-TS output)")
        return recorder.surface
    }

    fun start() {
        val recorder = mediaRecorder ?: return
        isRunning.set(true)
        recorder.start()
        Log.d(TAG, "MediaRecorder started")
        startReaderThread()
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.d(TAG, "Stopping ServerVideoStreamer")

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaRecorder: ${e.message}")
        }
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaRecorder: ${e.message}")
        }
        mediaRecorder = null

        try {
            writePfd?.close()
        } catch (e: Exception) {}
        writePfd = null

        readerThread?.interrupt()
        readerThread?.join(2000)
        readerThread = null

        try {
            readPfd?.close()
        } catch (e: Exception) {}
        readPfd = null

        Log.d(TAG, "ServerVideoStreamer stopped")
    }

    private fun startReaderThread() {
        val fd = readPfd ?: return
        readerThread = Thread({
            val inputStream = FileInputStream(fd.fileDescriptor)
            val buffer = ByteArray(CHUNK_SIZE)
            try {
                while (isRunning.get()) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead <= 0) break
                    val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                    onChunk(chunk)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Reader error: ${e.message}")
                }
            } finally {
                try { inputStream.close() } catch (e: Exception) {}
                Log.d(TAG, "Reader thread exited")
            }
        }, "ServerVideoReader").also { it.isDaemon = true; it.start() }
    }
}
