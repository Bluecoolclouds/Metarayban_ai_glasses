package com.meta.wearable.dat.externalsampleapps.cameraaccess.server

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GlassesVideoStreamer(private val onChunk: (ByteArray) -> Unit) {
    companion object {
        private const val TAG = "GlassesVideoStreamer"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 1_500_000
        private const val FRAME_RATE = 24
        private const val I_FRAME_INTERVAL = 2
    }

    private var codec: MediaCodec? = null
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    private val isRunning = AtomicBoolean(false)
    private val frameQueue = LinkedBlockingQueue<Bitmap>(4)

    private var encoderThread: Thread? = null
    private var outputThread: Thread? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            VIDEO_WIDTH,
            VIDEO_HEIGHT
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder
        isRunning.set(true)

        startEncoderThread()
        startOutputThread()
        Log.d(TAG, "GlassesVideoStreamer started (raw H264 Annex B)")
    }

    fun sendFrame(bitmap: Bitmap) {
        if (!isRunning.get()) return
        frameQueue.offer(bitmap)
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.d(TAG, "Stopping GlassesVideoStreamer")

        frameQueue.clear()
        encoderThread?.interrupt()
        encoderThread?.join(2000)
        encoderThread = null

        outputThread?.interrupt()
        outputThread?.join(2000)
        outputThread = null

        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping codec: ${e.message}")
        }
        codec = null
        spsData = null
        ppsData = null
        Log.d(TAG, "GlassesVideoStreamer stopped")
    }

    private fun startEncoderThread() {
        val ptsDeltaUs = 1_000_000L / FRAME_RATE
        var ptsUs = 0L

        encoderThread = Thread({
            while (isRunning.get()) {
                try {
                    val bitmap = frameQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    val encoder = codec ?: break

                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex < 0) continue

                    val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                    inputBuffer.clear()

                    val scaled = if (bitmap.width != VIDEO_WIDTH || bitmap.height != VIDEO_HEIGHT) {
                        Bitmap.createScaledBitmap(bitmap, VIDEO_WIDTH, VIDEO_HEIGHT, false)
                    } else bitmap

                    val yuv = bitmapToNv12(scaled)
                    inputBuffer.put(yuv)

                    encoder.queueInputBuffer(inputIndex, 0, yuv.size, ptsUs, 0)
                    ptsUs += ptsDeltaUs

                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "Encoder input error: ${e.message}")
                }
            }
        }, "GlassesEncoder").also { it.isDaemon = true; it.start() }
    }

    private fun startOutputThread() {
        outputThread = Thread({
            val info = MediaCodec.BufferInfo()

            while (isRunning.get()) {
                try {
                    val encoder = codec ?: break
                    val outputIndex = encoder.dequeueOutputBuffer(info, 10_000)
                    if (outputIndex < 0) continue

                    val buffer = encoder.getOutputBuffer(outputIndex)
                    if (buffer == null) {
                        encoder.releaseOutputBuffer(outputIndex, false)
                        continue
                    }

                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data)
                    encoder.releaseOutputBuffer(outputIndex, false)

                    if (isConfig) {
                        parseSpsPps(data)
                        continue
                    }

                    val annexBData = if (isKeyFrame) buildKeyframePayload(data) else ensureAnnexB(data)
                    onChunk(annexBData)

                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "Encoder output error: ${e.message}")
                }
            }
        }, "GlassesOutput").also { it.isDaemon = true; it.start() }
    }

    private fun parseSpsPps(codecConfig: ByteArray) {
        val nalUnits = splitAnnexB(codecConfig)
        for (nal in nalUnits) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> spsData = nal
                8 -> ppsData = nal
            }
        }
        Log.d(TAG, "Parsed SPS=${spsData?.size}b PPS=${ppsData?.size}b")
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i <= data.size - 3) {
            val sc3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val sc4 = i + 3 < data.size && sc3 && data.getOrNull(i - 1) == 0.toByte()
            val isStart = (i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) ||
                    (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte())

            if (isStart) {
                if (start >= 0) result.add(data.copyOfRange(start, i))
                start = i + if (i + 3 < data.size && data[i + 2] == 0.toByte()) 4 else 3
                i = start
            } else {
                i++
            }
        }
        if (start >= 0 && start < data.size) result.add(data.copyOfRange(start, data.size))
        return result.filter { it.isNotEmpty() }
    }

    private fun ensureAnnexB(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            (data[2] == 1.toByte() || (data[2] == 0.toByte() && data[3] == 1.toByte()))
        ) return data
        return byteArrayOf(0, 0, 0, 1) + data
    }

    private fun buildKeyframePayload(idrData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val sps = spsData
        val pps = ppsData
        if (sps != null) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(sps)
        }
        if (pps != null) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(pps)
        }
        out.write(ensureAnnexB(idrData))
        return out.toByteArray()
    }

    private fun bitmapToNv12(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val nv12 = ByteArray(width * height * 3 / 2)
        var uvOffset = width * height

        for (row in 0 until height) {
            for (col in 0 until width) {
                val pixel = pixels[row * width + col]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv12[row * width + col] = y.coerceIn(16, 235).toByte()
                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    nv12[uvOffset++] = u.coerceIn(16, 240).toByte()
                    nv12[uvOffset++] = v.coerceIn(16, 240).toByte()
                }
            }
        }
        return nv12
    }
}
