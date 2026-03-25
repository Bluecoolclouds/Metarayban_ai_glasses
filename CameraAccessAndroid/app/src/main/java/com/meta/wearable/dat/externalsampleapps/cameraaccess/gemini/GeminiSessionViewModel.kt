package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.AudioDeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.memory.MemoryRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.service.GeminiForegroundService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.skills.CookingModeSkill
import com.meta.wearable.dat.externalsampleapps.cameraaccess.skills.TranslatorSkill
import com.meta.wearable.dat.externalsampleapps.cameraaccess.skills.SkillManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GeminiUiState(
    val isGeminiActive: Boolean = false,
    val connectionState: GeminiConnectionState = GeminiConnectionState.Disconnected,
    val isModelSpeaking: Boolean = false,
    val errorMessage: String? = null,
    val userTranscript: String = "",
    val aiTranscript: String = "",
    val isAutoReconnectEnabled: Boolean = false,
    val activeSkillName: String? = null,
)

class GeminiSessionViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "GeminiSessionVM"
    }

    private val _uiState = MutableStateFlow(GeminiUiState())
    val uiState: StateFlow<GeminiUiState> = _uiState.asStateFlow()

    private val geminiService = GeminiLiveService()
    private val audioManager = AudioManager()

    val micLevel: StateFlow<Float> = audioManager.micLevel
    private var lastVideoFrameTime: Long = 0
    private var latestVideoFrame: Bitmap? = null
    private var stateObservationJob: Job? = null
    private var shouldAutoReconnect = false
    private var reconnectJob: Job? = null
    private var pendingSkillReconnect = false

    var streamingMode: StreamingMode = StreamingMode.GLASSES

    @Volatile private var micMuted = false

    fun muteMic(muted: Boolean) {
        micMuted = muted
    }

    fun startSession() {
        if (_uiState.value.isGeminiActive) {
            if (_uiState.value.connectionState != GeminiConnectionState.Disconnected) return
            Log.d(TAG, "startSession: isGeminiActive=true but connection is dead — restarting")
            stopSessionInternal()
        }

        if (!GeminiConfig.isConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Gemini API key not configured. Open Settings and add your key from https://aistudio.google.com/apikey"
            )
            return
        }

        shouldAutoReconnect = true
        reconnectJob?.cancel()
        _uiState.value = _uiState.value.copy(isGeminiActive = true, isAutoReconnectEnabled = true)

        GeminiForegroundService.start(getApplication())

        SkillManager.injectMessage = { text ->
            geminiService.injectTextMessage(text)
        }
        SkillManager.frameProvider = { latestVideoFrame }
        SkillManager.cameraActiveCheck = { isCameraActive() }
        SkillManager.resumeTick()

        audioManager.onAudioCaptured = lambda@{ data ->
            if (micMuted) return@lambda
            if (streamingMode == StreamingMode.PHONE && geminiService.isModelSpeaking.value) return@lambda
            geminiService.sendAudio(data)
        }

        geminiService.onAudioReceived = { data ->
            audioManager.playAudio(data)
        }

        geminiService.onInterrupted = {
            audioManager.stopPlayback()
        }

        geminiService.onInputTranscription = { text ->
            _uiState.value = _uiState.value.copy(
                userTranscript = _uiState.value.userTranscript + text,
                aiTranscript = ""
            )
            // Refresh video window so windowed skills (e.g. CalorieSkill) get fresh frames on each utterance
            SkillManager.refreshSkillVideoWindow()
            if (SkillManager.checkActivation(text)) {
                val skillName = SkillManager.activeSkill?.name
                Log.d(TAG, "Skill changed: $skillName — will reconnect after turn completes")
                _uiState.value = _uiState.value.copy(activeSkillName = skillName)
                pendingSkillReconnect = true
            }
        }

        geminiService.onOutputTranscription = { text ->
            _uiState.value = _uiState.value.copy(
                aiTranscript = _uiState.value.aiTranscript + text
            )
            SkillManager.processAiOutput(text)
            currentAiTurnBuffer.append(text)
            if (SkillManager.activeSkill is TranslatorSkill) {
                TranslatorSkill.appendLive(text)
            }
        }

        geminiService.onTurnComplete = {
            val fullTurnText = currentAiTurnBuffer.toString()
            currentAiTurnBuffer.clear()
            if (fullTurnText.contains("<memory_save")) {
                extractAndSaveMemoryTags(fullTurnText)
            }
            if (SkillManager.activeSkill is TranslatorSkill) {
                TranslatorSkill.commitCurrent()
            }
            _uiState.value = _uiState.value.copy(userTranscript = "")

            val cookingSkill = SkillManager.activeSkill as? CookingModeSkill
            val cookingStateChanged = cookingSkill?.consumeStateChanged() == true

            if (pendingSkillReconnect || cookingStateChanged) {
                pendingSkillReconnect = false
                Log.d(TAG, "Turn complete — reconnecting with updated prompt (skillChange=$pendingSkillReconnect, cookingState=$cookingStateChanged)")
                reconnectWithUpdatedPrompt()
            }
        }

        geminiService.onDisconnected = { reason ->
            if (_uiState.value.isGeminiActive) {
                val willReconnect = shouldAutoReconnect
                stopSessionInternal()
                if (willReconnect) {
                    Log.d(TAG, "Unexpected disconnect, auto-reconnecting in 3s: $reason")
                    reconnectJob = viewModelScope.launch {
                        delay(3000)
                        if (shouldAutoReconnect) startSession()
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Connection lost: ${reason ?: "Unknown error"}"
                    )
                }
            }
        }

        viewModelScope.launch {
            stateObservationJob = viewModelScope.launch {
                while (isActive) {
                    delay(100)
                    _uiState.value = _uiState.value.copy(
                        connectionState = geminiService.connectionState.value,
                        isModelSpeaking = geminiService.isModelSpeaking.value,
                    )
                }
            }

            geminiService.connect { setupOk ->
                if (!setupOk) {
                    val msg = when (val state = geminiService.connectionState.value) {
                        is GeminiConnectionState.Error -> state.message
                        else -> "Failed to connect to Gemini"
                    }
                    _uiState.value = _uiState.value.copy(errorMessage = msg)
                    geminiService.disconnect()
                    stateObservationJob?.cancel()
                    _uiState.value = _uiState.value.copy(
                        isGeminiActive = false,
                        isAutoReconnectEnabled = shouldAutoReconnect,
                        connectionState = GeminiConnectionState.Disconnected
                    )
                    if (shouldAutoReconnect) {
                        Log.d(TAG, "Connect failed, retrying in 5s")
                        reconnectJob = viewModelScope.launch {
                            delay(5000)
                            if (shouldAutoReconnect) startSession()
                        }
                    }
                    return@connect
                }

                try {
                    val preferredDeviceId = SettingsManager.preferredMicDeviceId
                    val preferredDevice = if (preferredDeviceId != 0) {
                        AudioDeviceSelector.getDeviceInfoById(getApplication(), preferredDeviceId)
                            .also { device ->
                                if (device != null) Log.d(TAG, "Using preferred mic: ${device.productName} (id=$preferredDeviceId)")
                                else Log.w(TAG, "Preferred mic id=$preferredDeviceId not found, falling back to default")
                            }
                    } else null
                    audioManager.startCapture(getApplication(), preferredDevice)
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Mic capture failed: ${e.message}"
                    )
                    geminiService.disconnect()
                    stateObservationJob?.cancel()
                    _uiState.value = _uiState.value.copy(
                        isGeminiActive = false,
                        isAutoReconnectEnabled = false,
                        connectionState = GeminiConnectionState.Disconnected
                    )
                }
            }
        }
    }

    fun stopSession() {
        shouldAutoReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        stopSessionInternal()
        GeminiForegroundService.stop(getApplication())
    }

    private fun stopSessionInternal(clearSkills: Boolean = true) {
        currentAiTurnBuffer.clear()
        audioManager.stopCapture()
        geminiService.disconnect()
        stateObservationJob?.cancel()
        stateObservationJob = null
        SkillManager.pauseTick()
        SkillManager.injectMessage = null
        SkillManager.frameProvider = null
        SkillManager.cameraActiveCheck = null
        if (clearSkills) {
            SkillManager.deactivateAll()
        }
        _uiState.value = GeminiUiState(activeSkillName = if (clearSkills) null else SkillManager.activeSkill?.name)
    }

    fun deactivateSkill() {
        if (SkillManager.activeSkill == null) return
        SkillManager.deactivateAll()
        _uiState.value = _uiState.value.copy(activeSkillName = null)
        reconnectWithUpdatedPrompt()
    }

    private fun reconnectWithUpdatedPrompt() {
        if (!_uiState.value.isGeminiActive) return
        val skillName = SkillManager.activeSkill?.name
        Log.d(TAG, "Reconnecting Gemini with updated prompt (active skill: $skillName)")
        stopSessionInternal(clearSkills = false)
        viewModelScope.launch {
            delay(500)
            startSession()
        }
    }

    fun speakMessage(text: String) {
        if (!_uiState.value.isGeminiActive) {
            Log.d(TAG, "Cannot speak message — Gemini not active")
            return
        }
        geminiService.injectTextMessage("Please say the following message out loud to the user exactly as written, do not add anything else: \"$text\"")
    }

    private var lastCameraFrameMs: Long = 0L
    private val currentAiTurnBuffer = StringBuilder()

    fun sendVideoFrameIfThrottled(bitmap: Bitmap) {
        latestVideoFrame = bitmap
        lastCameraFrameMs = System.currentTimeMillis()
        if (!_uiState.value.isGeminiActive) return
        if (_uiState.value.connectionState != GeminiConnectionState.Ready) return
        // Only forward frames to Gemini when a vision skill is active or a visual query window is open
        if (!SkillManager.shouldSendVideoToGemini) return
        val now = System.currentTimeMillis()
        if (now - lastVideoFrameTime < GeminiConfig.VIDEO_FRAME_INTERVAL_MS) return
        lastVideoFrameTime = now
        geminiService.sendVideoFrame(bitmap)
    }

    fun isCameraActive(): Boolean {
        if (lastCameraFrameMs == 0L) return false
        return (System.currentTimeMillis() - lastCameraFrameMs) < 10_000L
    }

    private fun extractAndSaveMemoryTags(text: String) {
        val regex = Regex(
            """<memory_save\s+category="([^"]+)"\s*>(.*?)</memory_save>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        var savedCount = 0
        for (match in regex.findAll(text)) {
            val category = match.groupValues[1].trim().lowercase()
            val content = match.groupValues[2].trim()
            if (content.isNotEmpty()) {
                MemoryRepository.save(category, content)
                savedCount++
            }
        }
        if (savedCount > 0) {
            Log.d(TAG, "Saved $savedCount memory entries from AI output")
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}
