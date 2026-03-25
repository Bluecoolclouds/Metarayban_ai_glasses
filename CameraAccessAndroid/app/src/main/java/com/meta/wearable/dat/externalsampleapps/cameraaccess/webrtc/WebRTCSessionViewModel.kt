package com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

sealed class WebRTCConnectionState {
    object Disconnected : WebRTCConnectionState()
    object Connecting : WebRTCConnectionState()
    object WaitingForPeer : WebRTCConnectionState()
    object Connected : WebRTCConnectionState()
    object Backgrounded : WebRTCConnectionState()
    data class Error(val message: String) : WebRTCConnectionState()
}

data class WebRTCUiState(
    val isActive: Boolean = false,
    val isSignalingOnly: Boolean = false,
    val connectionState: WebRTCConnectionState = WebRTCConnectionState.Disconnected,
    val roomCode: String = "",
    val isMuted: Boolean = false,
    val isTTSEnabled: Boolean = false,
    val errorMessage: String? = null,
    val remoteVideoTrack: VideoTrack? = null,
    val hasRemoteVideo: Boolean = false,
    val twitchRtmpUrl: String? = null,
)

class WebRTCSessionViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "WebRTCSessionVM"
    }

    private val _uiState = MutableStateFlow(WebRTCUiState())
    val uiState: StateFlow<WebRTCUiState> = _uiState.asStateFlow()

    private var webRTCClient: WebRTCClient? = null
    private var signalingClient: SignalingClient? = null
    private var savedRoomCode: String? = null
    private var upgradeJob: Job? = null
    private var signalingReconnectJob: Job? = null
    private var signalingRetryCount: Int = 0

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // App came to foreground
            handleReturnToForeground()
        }
    }

    fun startSession() {
        if (_uiState.value.isActive && !_uiState.value.isSignalingOnly) return

        if (!WebRTCConfig.isConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "WebRTC signaling URL not configured."
            )
            return
        }

        val wasSignalingOnly = _uiState.value.isSignalingOnly

        _uiState.value = _uiState.value.copy(
            isActive = true,
            isSignalingOnly = false,
            connectionState = if (wasSignalingOnly) WebRTCConnectionState.WaitingForPeer else WebRTCConnectionState.Connecting,
        )

        if (wasSignalingOnly && savedRoomCode != null) {
            Log.d(TAG, "Upgrading from signaling-only to full stream (room: $savedRoomCode)")
            viewModelScope.launch {
                val iceServers = WebRTCConfig.fetchIceServers()
                setupWebRTCClient(iceServers)
            }
            return
        }

        savedRoomCode = null

        viewModelScope.launch {
            val iceServers = WebRTCConfig.fetchIceServers()
            setupWebRTCClient(iceServers)
            connectSignaling(rejoinCode = null)
            observeForeground()
        }
    }

    fun connectSignalingOnly() {
        if (_uiState.value.isActive) return

        if (!WebRTCConfig.isConfigured) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "WebRTC signaling URL not configured."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isActive = true,
            isSignalingOnly = true,
            connectionState = WebRTCConnectionState.Connecting,
        )
        savedRoomCode = null

        viewModelScope.launch {
            connectSignaling(rejoinCode = null)
            observeForeground()
        }
    }

    fun downgradeToSignalingOnly() {
        webRTCClient?.close()
        webRTCClient = null
        _uiState.value = _uiState.value.copy(
            isSignalingOnly = true,
            connectionState = WebRTCConnectionState.WaitingForPeer,
            hasRemoteVideo = false,
            remoteVideoTrack = null,
        )
        Log.d(TAG, "Downgraded to signaling-only (room: $savedRoomCode)")
    }

    fun stopSession() {
        removeForegroundObserver()
        signalingReconnectJob?.cancel()
        signalingReconnectJob = null
        signalingRetryCount = 0
        webRTCClient?.close()
        webRTCClient = null
        signalingClient?.disconnect()
        signalingClient = null
        savedRoomCode = null
        _uiState.value = WebRTCUiState()
    }

    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        _uiState.value = _uiState.value.copy(isMuted = newMuted)
        webRTCClient?.muteAudio(newMuted)
    }

    fun copyRoomCode() {
        val code = _uiState.value.roomCode
        if (code.isNotEmpty()) {
            val clipboard = getApplication<Application>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Room Code", code))
        }
    }

    fun pushVideoFrame(bitmap: Bitmap) {
        if (!_uiState.value.isActive) return
        if (_uiState.value.connectionState != WebRTCConnectionState.Connected) return
        webRTCClient?.pushVideoFrame(bitmap)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    var onGlassesMessage: ((String) -> Unit)? = null
    var onVideoStreamStarted: (() -> Unit)? = null
    var onVideoStreamStopped: (() -> Unit)? = null

    fun toggleTTS() {
        val newState = !_uiState.value.isTTSEnabled
        _uiState.value = _uiState.value.copy(isTTSEnabled = newState)
        signalingClient?.sendTTSToggle(newState)
    }

    fun sendVideoStreamStart(format: String) {
        signalingClient?.sendVideoStreamStart(format)
    }

    fun sendVideoStreamStop() {
        signalingClient?.sendVideoStreamStop()
    }

    fun sendBinaryChunk(data: ByteArray) {
        signalingClient?.sendBinaryChunk(data)
    }

    // -- Private --

    private fun setupWebRTCClient(iceServers: List<PeerConnection.IceServer>) {
        webRTCClient?.close()
        val client = WebRTCClient(getApplication())
        client.delegate = object : WebRTCClientDelegate {
            override fun onConnectionStateChanged(state: PeerConnection.IceConnectionState) {
                viewModelScope.launch { handleConnectionStateChange(state) }
            }
            override fun onIceCandidateGenerated(candidate: IceCandidate) {
                signalingClient?.sendCandidate(candidate)
            }
            override fun onRemoteVideoTrackReceived(track: VideoTrack) {
                viewModelScope.launch {
                    _uiState.value = _uiState.value.copy(
                        remoteVideoTrack = track,
                        hasRemoteVideo = true,
                    )
                    Log.d(TAG, "Remote video track received")
                }
            }
            override fun onRemoteVideoTrackRemoved(track: VideoTrack) {
                viewModelScope.launch {
                    _uiState.value = _uiState.value.copy(
                        remoteVideoTrack = null,
                        hasRemoteVideo = false,
                    )
                    Log.d(TAG, "Remote video track removed")
                }
            }
        }
        client.setup(iceServers)
        webRTCClient = client
    }

    private fun connectSignaling(rejoinCode: String?) {
        signalingClient?.disconnect()

        val signaling = SignalingClient()
        signalingClient = signaling

        signaling.onConnected = {
            viewModelScope.launch {
                signalingRetryCount = 0
                signalingReconnectJob?.cancel()
                if (rejoinCode != null) {
                    Log.d(TAG, "Reconnected, rejoining room: $rejoinCode")
                    signalingClient?.rejoinRoom(rejoinCode)
                } else {
                    signalingClient?.createRoom()
                }
            }
        }

        signaling.onMessageReceived = { message ->
            viewModelScope.launch { handleSignalingMessage(message) }
        }

        signaling.onDisconnected = { reason ->
            viewModelScope.launch {
                if (!_uiState.value.isActive) return@launch
                if (savedRoomCode != null) {
                    _uiState.value = _uiState.value.copy(
                        connectionState = WebRTCConnectionState.Backgrounded
                    )
                    Log.d(TAG, "Signaling disconnected (backgrounded), will rejoin: $reason")
                } else {
                    // Initial connection failed — auto-retry with backoff (503, network errors, etc.)
                    val maxRetries = 8
                    val delayMs = minOf(3000L * (1 shl signalingRetryCount), 30_000L)
                    if (signalingRetryCount < maxRetries) {
                        signalingRetryCount++
                        Log.w(TAG, "Signaling connect failed ($reason), retry ${signalingRetryCount}/$maxRetries in ${delayMs}ms")
                        _uiState.value = _uiState.value.copy(
                            connectionState = WebRTCConnectionState.Connecting,
                            errorMessage = null,
                        )
                        signalingReconnectJob?.cancel()
                        signalingReconnectJob = viewModelScope.launch {
                            delay(delayMs)
                            if (_uiState.value.isActive) connectSignaling(rejoinCode = null)
                        }
                    } else {
                        signalingRetryCount = 0
                        stopSession()
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "Signaling disconnected: ${reason ?: "Unknown"}"
                        )
                    }
                }
            }
        }

        signaling.connect(WebRTCConfig.signalingServerURL)
    }

    private fun handleSignalingMessage(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.RoomCreated -> {
                _uiState.value = _uiState.value.copy(
                    roomCode = message.room,
                    connectionState = WebRTCConnectionState.WaitingForPeer,
                )
                savedRoomCode = message.room
                Log.d(TAG, "Room created: ${message.room}")
            }
            is SignalingMessage.RoomRejoined -> {
                _uiState.value = _uiState.value.copy(
                    roomCode = message.room,
                    connectionState = WebRTCConnectionState.WaitingForPeer,
                )
                savedRoomCode = message.room
                Log.d(TAG, "Room rejoined: ${message.room}")
            }
            is SignalingMessage.PeerJoined -> {
                if (_uiState.value.isSignalingOnly) {
                    Log.d(TAG, "Peer joined — upgrading from signaling-only to full WebRTC")
                    _uiState.value = _uiState.value.copy(
                        isSignalingOnly = false,
                        connectionState = WebRTCConnectionState.WaitingForPeer,
                    )
                    upgradeJob?.cancel()
                    upgradeJob = viewModelScope.launch {
                        val iceServers = WebRTCConfig.fetchIceServers()
                        if (!isActive) return@launch
                        setupWebRTCClient(iceServers)
                        if (!isActive || _uiState.value.isSignalingOnly) return@launch
                        Log.d(TAG, "WebRTC client ready, creating offer")
                        webRTCClient?.createOffer { sdp ->
                            if (!_uiState.value.isSignalingOnly) {
                                signalingClient?.sendSdp(sdp)
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "Peer joined, creating offer")
                    webRTCClient?.createOffer { sdp ->
                        signalingClient?.sendSdp(sdp)
                    }
                }
            }
            is SignalingMessage.Answer -> {
                if (!_uiState.value.isSignalingOnly) {
                    webRTCClient?.setRemoteSdp(message.sdp) { error ->
                        error?.let { Log.e(TAG, "Error setting remote SDP: $it") }
                    }
                }
            }
            is SignalingMessage.Candidate -> {
                if (!_uiState.value.isSignalingOnly) {
                    webRTCClient?.addRemoteCandidate(message.candidate) { error ->
                        error?.let { Log.e(TAG, "Error adding ICE candidate: $it") }
                    }
                }
            }
            is SignalingMessage.PeerLeft -> {
                Log.d(TAG, "Peer left — downgrading to signaling-only")
                upgradeJob?.cancel()
                upgradeJob = null
                webRTCClient?.close()
                webRTCClient = null
                _uiState.value = _uiState.value.copy(
                    isSignalingOnly = true,
                    connectionState = WebRTCConnectionState.WaitingForPeer,
                    hasRemoteVideo = false,
                    remoteVideoTrack = null,
                )
            }
            is SignalingMessage.Error -> {
                // If rejoin fails (room expired), create a new room
                if (savedRoomCode != null && message.message == "Room not found") {
                    Log.d(TAG, "Rejoin failed (room expired), creating new room")
                    savedRoomCode = null
                    signalingClient?.createRoom()
                } else {
                    _uiState.value = _uiState.value.copy(errorMessage = message.message)
                }
            }
            is SignalingMessage.GlassesMessage -> {
                Log.d(TAG, "Glasses message: ${message.message}")
                onGlassesMessage?.invoke(message.message)
            }
            is SignalingMessage.Reminder -> {
                Log.d(TAG, "Reminder: ${message.message}")
                onGlassesMessage?.invoke("Reminder: ${message.message}")
            }
            is SignalingMessage.TTSState -> {
                Log.d(TAG, "TTS state from server: ${message.enabled}")
                _uiState.value = _uiState.value.copy(isTTSEnabled = message.enabled)
            }
            is SignalingMessage.TwitchConfig -> {
                Log.d(TAG, "Twitch config received")
                _uiState.value = _uiState.value.copy(twitchRtmpUrl = message.rtmpUrl)
            }
            is SignalingMessage.VideoStreamStarted -> {
                Log.d(TAG, "Server video stream started")
                onVideoStreamStarted?.invoke()
            }
            is SignalingMessage.VideoStreamStopped -> {
                Log.d(TAG, "Server video stream stopped by server")
                onVideoStreamStopped?.invoke()
            }
            is SignalingMessage.RoomJoined, is SignalingMessage.Offer -> {
                // Not handled by streamer side
            }
        }
    }

    private fun handleConnectionStateChange(state: PeerConnection.IceConnectionState) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                _uiState.value = _uiState.value.copy(
                    connectionState = WebRTCConnectionState.Connected
                )
                Log.d(TAG, "Peer connected")
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                _uiState.value = _uiState.value.copy(
                    connectionState = WebRTCConnectionState.WaitingForPeer
                )
            }
            PeerConnection.IceConnectionState.FAILED -> {
                _uiState.value = _uiState.value.copy(
                    connectionState = WebRTCConnectionState.Error("Connection failed")
                )
            }
            PeerConnection.IceConnectionState.CLOSED -> {
                _uiState.value = _uiState.value.copy(
                    connectionState = WebRTCConnectionState.Disconnected
                )
            }
            else -> {}
        }
    }

    private fun handleReturnToForeground() {
        val code = savedRoomCode ?: return
        if (!_uiState.value.isActive) return

        val currentState = _uiState.value.connectionState
        if (currentState is WebRTCConnectionState.Connected ||
            currentState is WebRTCConnectionState.WaitingForPeer) {
            Log.d(TAG, "App returned to foreground, connection still active — no reconnect needed")
            return
        }

        Log.d(TAG, "App returned to foreground, reconnecting to room: $code (state was: $currentState)")
        _uiState.value = _uiState.value.copy(
            connectionState = WebRTCConnectionState.Connecting,
            remoteVideoTrack = null,
            hasRemoteVideo = false,
        )

        webRTCClient?.close()

        viewModelScope.launch {
            val iceServers = WebRTCConfig.fetchIceServers()
            setupWebRTCClient(iceServers)
            connectSignaling(rejoinCode = code)
        }
    }

    private fun observeForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    private fun removeForegroundObserver() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}
