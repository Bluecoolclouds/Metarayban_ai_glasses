package com.meta.wearable.dat.externalsampleapps.cameraaccess.twitch

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class ChatTTSManager(context: Context) {

    companion object {
        private const val TAG = "ChatTTSManager"
        private const val MAX_PENDING = 5
    }

    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingFinished: (() -> Unit)? = null

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val pendingQueue = mutableListOf<String>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.ENGLISH)
                }
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onSpeakingStarted?.invoke()
                    }

                    override fun onDone(utteranceId: String?) {
                        onSpeakingFinished?.invoke()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onSpeakingFinished?.invoke()
                    }
                })
                isReady = true
                val queued = pendingQueue.toList()
                pendingQueue.clear()
                queued.forEach { speakInternal(it) }
                Log.d(TAG, "TTS ready, language=${Locale.getDefault()}")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    private fun speakInternal(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "chat_${System.currentTimeMillis()}")
    }

    fun speakMessage(username: String, message: String) {
        val text = "$username: $message"
        if (!isReady) {
            if (pendingQueue.size < MAX_PENDING) pendingQueue.add(text)
            return
        }
        speakInternal(text)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        pendingQueue.clear()
    }
}
