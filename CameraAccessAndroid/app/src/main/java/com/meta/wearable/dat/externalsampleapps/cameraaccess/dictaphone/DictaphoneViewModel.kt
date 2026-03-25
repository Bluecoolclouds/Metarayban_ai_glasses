package com.meta.wearable.dat.externalsampleapps.cameraaccess.dictaphone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.AudioDeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DictaphoneUiState(
    val isRecording: Boolean = false,
    val recordings: List<Recording> = emptyList(),
    val playingRecordingId: String? = null,
    val recordingElapsedMs: Long = 0L,
    val playbackPositionMs: Int = 0,
    val playbackDurationMs: Int = 0,
)

class DictaphoneViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = DictaphoneManager(application)
    private val _uiState = MutableStateFlow(DictaphoneUiState())
    val uiState: StateFlow<DictaphoneUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var playbackTimerJob: Job? = null

    init {
        loadRecordings()
    }

    private fun loadRecordings() {
        viewModelScope.launch {
            val recordings = withContext(Dispatchers.IO) { manager.loadRecordings() }
            _uiState.update { it.copy(recordings = recordings) }
        }
    }

    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        stopPlayback()
        val preferredDeviceId = SettingsManager.preferredMicDeviceId
        val preferredDevice = if (preferredDeviceId != 0) {
            AudioDeviceSelector.getDeviceInfoById(getApplication(), preferredDeviceId)
        } else null
        if (manager.startRecording(preferredDevice)) {
            _uiState.update { it.copy(isRecording = true, recordingElapsedMs = 0L) }
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(100)
                    _uiState.update { it.copy(recordingElapsedMs = manager.getElapsedMs()) }
                }
            }
        }
    }

    private fun stopRecording() {
        timerJob?.cancel()
        timerJob = null
        val recording = manager.stopRecording()
        _uiState.update { it.copy(isRecording = false, recordingElapsedMs = 0L) }
        if (recording != null) {
            loadRecordings()
        }
    }

    fun togglePlayback(recording: Recording) {
        val current = _uiState.value.playingRecordingId
        if (current == recording.id) {
            stopPlayback()
        } else {
            stopPlayback()
            if (manager.playRecording(recording) { onPlaybackComplete() }) {
                _uiState.update {
                    it.copy(
                        playingRecordingId = recording.id,
                        playbackPositionMs = 0,
                        playbackDurationMs = manager.getPlaybackDuration(),
                    )
                }
                playbackTimerJob = viewModelScope.launch {
                    while (manager.isPlaying()) {
                        _uiState.update {
                            it.copy(playbackPositionMs = manager.getPlaybackPosition())
                        }
                        delay(200)
                    }
                }
            }
        }
    }

    fun seekPlayback(position: Float) {
        manager.seekTo(position.toInt())
        _uiState.update { it.copy(playbackPositionMs = position.toInt()) }
    }

    private fun onPlaybackComplete() {
        playbackTimerJob?.cancel()
        playbackTimerJob = null
        _uiState.update {
            it.copy(playingRecordingId = null, playbackPositionMs = 0, playbackDurationMs = 0)
        }
    }

    fun stopPlayback() {
        playbackTimerJob?.cancel()
        playbackTimerJob = null
        manager.stopPlayback()
        _uiState.update {
            it.copy(playingRecordingId = null, playbackPositionMs = 0, playbackDurationMs = 0)
        }
    }

    fun deleteRecording(recording: Recording) {
        if (_uiState.value.playingRecordingId == recording.id) {
            stopPlayback()
        }
        if (manager.deleteRecording(recording)) {
            loadRecordings()
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        playbackTimerJob?.cancel()
        manager.release()
    }
}
