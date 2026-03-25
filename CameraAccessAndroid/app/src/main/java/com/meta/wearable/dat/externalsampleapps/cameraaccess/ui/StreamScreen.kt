/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.AudioDeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.AudioInputDevice
import com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.MicLevelMonitor
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import androidx.compose.ui.viewinterop.AndroidView
import com.pedro.library.view.OpenGlView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import kotlinx.coroutines.delay
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamingMode
import com.meta.wearable.dat.externalsampleapps.cameraaccess.twitch.ChatTTSManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.twitch.TwitchChatClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.twitch.TwitchStreamManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCSessionViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    isPhoneMode: Boolean = false,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
    geminiViewModel: GeminiSessionViewModel = viewModel(),
    webrtcViewModel: WebRTCSessionViewModel = viewModel(),
) {
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
    val geminiUiState by geminiViewModel.uiState.collectAsStateWithLifecycle()
    val webrtcUiState by webrtcViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val twitchManager = remember { TwitchStreamManager(context) }
    val twitchState by twitchManager.state.collectAsStateWithLifecycle()
    val measuredBitrateKbps by twitchManager.measuredBitrateKbps.collectAsStateWithLifecycle()
    val twitchOpenGlView = remember { OpenGlView(context).apply { holder.setFixedSize(1, 1) } }

    val twitchChatClient = remember { TwitchChatClient() }
    val chatMessages by twitchChatClient.messages.collectAsStateWithLifecycle()
    val isChatConnected by twitchChatClient.isConnected.collectAsStateWithLifecycle()
    var isChatVisible by remember { mutableStateOf(false) }
    var isTTSEnabled by remember { mutableStateOf(false) }
    var showStreamSettings by remember { mutableStateOf(false) }
    val chatTTSManager = remember { ChatTTSManager(context) }
    val pendingChatMessages = remember { mutableStateListOf<com.meta.wearable.dat.externalsampleapps.cameraaccess.twitch.TwitchChatMessage>() }

    DisposableEffect(twitchManager) {
        onDispose { twitchManager.release() }
    }

    // Mic picker + level monitor
    val micMonitor = remember { MicLevelMonitor() }
    val micLevel by micMonitor.level.collectAsStateWithLifecycle()
    val availableMics = remember { mutableStateListOf<AudioInputDevice>() }
    var selectedMicId by remember { mutableStateOf(SettingsManager.preferredMicDeviceId) }
    val micScope = rememberCoroutineScope()

    // One-time: load available mic list
    LaunchedEffect(Unit) {
        availableMics.clear()
        availableMics.addAll(AudioDeviceSelector.getAvailableInputDevices(context))
    }

    // Start/restart monitor when mic selection or streaming state changes.
    // Paused during Twitch/Gemini so the real recorder owns the mic exclusively —
    // critical for BLE/Bluetooth routing via setCommunicationDevice.
    LaunchedEffect(selectedMicId, twitchState.isStreaming, twitchState.isConnecting, geminiUiState.isGeminiActive) {
        micMonitor.stop()
        if (!twitchState.isStreaming && !twitchState.isConnecting && !geminiUiState.isGeminiActive) {
            val device = if (selectedMicId != 0)
                AudioDeviceSelector.getDeviceInfoById(context, selectedMicId) else null
            micMonitor.start(micScope, device)
        }
    }

    DisposableEffect(micMonitor) {
        onDispose { micMonitor.stop() }
    }

    DisposableEffect(chatTTSManager) {
        chatTTSManager.onSpeakingStarted = { geminiViewModel.muteMic(true) }
        chatTTSManager.onSpeakingFinished = { geminiViewModel.muteMic(false) }
        onDispose {
            chatTTSManager.onSpeakingStarted = null
            chatTTSManager.onSpeakingFinished = null
            geminiViewModel.muteMic(false)
            chatTTSManager.shutdown()
        }
    }

    LaunchedEffect(chatMessages.size) {
        if (!isTTSEnabled || chatMessages.isEmpty()) return@LaunchedEffect
        val last = chatMessages.last()
        if (geminiUiState.isModelSpeaking) {
            if (pendingChatMessages.size < 5) pendingChatMessages.add(last)
        } else {
            chatTTSManager.speakMessage(last.username, last.text)
        }
    }

    LaunchedEffect(geminiUiState.isModelSpeaking) {
        if (!geminiUiState.isModelSpeaking && isTTSEnabled && pendingChatMessages.isNotEmpty()) {
            delay(400)
            val toSpeak = pendingChatMessages.toList()
            pendingChatMessages.clear()
            toSpeak.forEach { msg ->
                chatTTSManager.speakMessage(msg.username, msg.text)
            }
        }
    }

    DisposableEffect(twitchChatClient) {
        val channel = SettingsManager.twitchChannelName
        if (channel.isNotBlank()) {
            twitchChatClient.connect(channel)
        }
        onDispose { twitchChatClient.disconnect() }
    }

    LaunchedEffect(twitchState.isStreaming, twitchState.isDirectEncoding) {
        if (twitchState.isStreaming && twitchState.isDirectEncoding) {
            streamViewModel.twitchRawFrameCallback = { buffer, width, height, timestampUs ->
                twitchManager.feedRawFrame(buffer, width, height, timestampUs)
            }
        } else {
            streamViewModel.twitchRawFrameCallback = null
        }
    }

    // Wire Gemini VM to Stream VM for frame forwarding
    LaunchedEffect(geminiViewModel) {
        streamViewModel.geminiViewModel = geminiViewModel
    }

    // Wire WebRTC VM to Stream VM for frame forwarding
    LaunchedEffect(webrtcViewModel) {
        streamViewModel.webrtcViewModel = webrtcViewModel
    }

    LaunchedEffect(webrtcViewModel) {
        webrtcViewModel.onVideoStreamStopped = {
            streamViewModel.onServerVideoStoppedByServer()
        }
    }

    // Wire glasses messages from WebRTC signaling to Gemini for speech
    LaunchedEffect(webrtcViewModel, geminiViewModel) {
        webrtcViewModel.onGlassesMessage = { message ->
            geminiViewModel.speakMessage(message)
        }
    }

    // Start stream or phone camera
    LaunchedEffect(isPhoneMode) {
        if (isPhoneMode) {
            geminiViewModel.streamingMode = StreamingMode.PHONE
            streamViewModel.startPhoneCamera()
        } else {
            geminiViewModel.streamingMode = StreamingMode.GLASSES
            streamViewModel.startStream()
        }
    }

    // WebRTC signaling disabled — not needed currently
    // LaunchedEffect(Unit) {
    //     if (!webrtcUiState.isActive) {
    //         webrtcViewModel.connectSignalingOnly()
    //     }
    // }

    // Safety net: if Gemini drops mid-session and ViewModel's own reconnect fails,
    // restart from the screen side after a longer delay.
    LaunchedEffect(geminiUiState.connectionState) {
        if (geminiUiState.connectionState == GeminiConnectionState.Disconnected &&
            !geminiUiState.isGeminiActive &&
            geminiUiState.isAutoReconnectEnabled) {
            delay(7000)
            if (!geminiUiState.isGeminiActive && geminiUiState.isAutoReconnectEnabled) {
                geminiViewModel.startSession()
            }
        }
    }

    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(streamUiState.keepScreenOn) {
        if (streamUiState.keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    

    // Show errors as toasts
    LaunchedEffect(geminiUiState.errorMessage) {
        geminiUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            geminiViewModel.clearError()
        }
    }
    LaunchedEffect(webrtcUiState.errorMessage) {
        webrtcUiState.errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            webrtcViewModel.clearError()
        }
    }
    LaunchedEffect(twitchState.error) {
        twitchState.error?.let { msg ->
            Toast.makeText(context, "Twitch: $msg", Toast.LENGTH_LONG).show()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Hidden OpenGlView for RtmpCamera2 (Twitch streaming)
        AndroidView(
            factory = { twitchOpenGlView },
            modifier = Modifier.size(1.dp),
        )

        // Video feed
        streamUiState.videoFrame?.let { videoFrame ->
            Image(
                bitmap = videoFrame.asImageBitmap(),
                contentDescription = stringResource(R.string.live_stream),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        if (streamUiState.streamSessionState == StreamSessionState.STARTING ||
            streamUiState.streamSessionState == StreamSessionState.STARTED) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                if (streamUiState.streamSessionState == StreamSessionState.STARTED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Waiting for camera...",
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        streamUiState.streamError?.let { error ->
            Text(
                text = error,
                color = Color(0xFFFF6B6B),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

        // Overlays + controls
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

            // Stream settings panel (slides from top)
            AnimatedVisibility(
                visible = showStreamSettings,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                StreamSettingsPanel(
                    isPhoneMode = isPhoneMode,
                    isStreaming = twitchState.isStreaming || twitchState.isConnecting,
                    onDismiss = { showStreamSettings = false },
                    onApplyBitrate = if (twitchState.isStreaming && !isPhoneMode) {
                        { twitchManager.updateBitrate(SettingsManager.effectiveBitrateBps()) }
                    } else null,
                    modifier = Modifier.padding(horizontal = 0.dp),
                )
            }

            // Gear button + measured bitrate badge — top-right
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (measuredBitrateKbps > 0) {
                    val bitrateLabel = if (measuredBitrateKbps >= 1000)
                        "${"%.1f".format(measuredBitrateKbps / 1000.0)} Mbps"
                    else
                        "$measuredBitrateKbps kbps"
                    Text(
                        text = bitrateLabel,
                        color = when {
                            measuredBitrateKbps >= 1500 -> Color(0xFF5AE05A)
                            measuredBitrateKbps >= 500  -> Color(0xFFFFB347)
                            else                        -> Color(0xFFFF6B6B)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                IconButton(onClick = { showStreamSettings = !showStreamSettings }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Настройки стрима",
                        tint = Color.White.copy(alpha = if (showStreamSettings) 1f else 0.7f),
                    )
                }
            }

            // Top overlays (below status bar)
            Column(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 8.dp)) {
                // Gemini overlay
                if (geminiUiState.isGeminiActive) {
                    GeminiOverlay(uiState = geminiUiState)
                }

                // WebRTC overlay
                if (webrtcUiState.isActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    WebRTCOverlay(uiState = webrtcUiState)
                }
            }

            // Controls at bottom
            ControlsRow(
                onStopStream = {
                    if (twitchState.isStreaming) twitchManager.stop()
                    if (geminiUiState.isGeminiActive) geminiViewModel.stopSession()
                    if (webrtcUiState.isActive) webrtcViewModel.stopSession()
                    streamViewModel.stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                },
                onCapturePhoto = { streamViewModel.capturePhoto() },
                onToggleAI = {
                    if (geminiUiState.isGeminiActive) {
                        geminiViewModel.stopSession()
                    } else {
                        geminiViewModel.startSession()
                        // WebRTC signaling disabled — not needed currently
                        // if (!webrtcUiState.isActive) {
                        //     webrtcViewModel.connectSignalingOnly()
                        // }
                    }
                },
                isAIActive = geminiUiState.isGeminiActive,
                onToggleTTS = { isChatVisible = !isChatVisible },
                isTTSActive = isChatVisible,
                isChatAvailable = SettingsManager.twitchChannelName.isNotBlank(),
                isServerConnected = webrtcUiState.isActive,
                onToggleTwitch = {
                    if (twitchState.isStreaming || twitchState.isConnecting) {
                        twitchManager.stop()
                        if (isPhoneMode) {
                            streamViewModel.resumePhoneCamera()
                        }
                    } else {
                        val rtmpUrl = SettingsManager.buildRtmpUrl() ?: webrtcUiState.twitchRtmpUrl
                        rtmpUrl?.let { url ->
                            if (!isPhoneMode) {
                                val w = streamUiState.rawFrameWidth.takeIf { it > 0 } ?: 640
                                val h = streamUiState.rawFrameHeight.takeIf { it > 0 } ?: 480
                                val prefMicId = SettingsManager.preferredMicDeviceId
                                val prefMicDev = if (prefMicId != 0)
                                    AudioDeviceSelector.getDeviceInfoById(context, prefMicId) else null
                                twitchManager.startDirectEncoding(url, w, h, prefMicDev)
                            } else {
                                streamViewModel.pausePhoneCamera()
                                twitchManager.start(url, twitchOpenGlView)
                            }
                        }
                    }
                },
                isTwitchStreaming = twitchState.isStreaming || twitchState.isConnecting,
                isTwitchAvailable = SettingsManager.buildRtmpUrl() != null || webrtcUiState.twitchRtmpUrl != null,
                onToggleLive = {
                    if (streamUiState.isServerVideoActive) {
                        streamViewModel.stopServerVideo()
                    } else {
                        streamViewModel.startServerVideo()
                    }
                },
                isLiveActive = streamUiState.isServerVideoActive,
                micLevel = micLevel,
                availableMics = availableMics,
                selectedMicId = selectedMicId,
                onMicSelected = { device ->
                    SettingsManager.preferredMicDeviceId = device.id
                    selectedMicId = device.id
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            TwitchChatPanel(
                visible = isChatVisible,
                messages = chatMessages,
                isConnected = isChatConnected,
                isTTSActive = isTTSEnabled,
                onToggleTTS = {
                    isTTSEnabled = !isTTSEnabled
                    if (!isTTSEnabled) {
                        chatTTSManager.stop()
                        pendingChatMessages.clear()
                        geminiViewModel.muteMic(false)
                    }
                },
                onClose = {
                    isChatVisible = false
                    isTTSEnabled = false
                    chatTTSManager.stop()
                    pendingChatMessages.clear()
                    geminiViewModel.muteMic(false)
                },
            )
        }
    }

    // Share photo dialog
    streamUiState.capturedPhoto?.let { photo ->
        if (streamUiState.isShareDialogVisible) {
            SharePhotoDialog(
                photo = photo,
                onDismiss = { streamViewModel.hideShareDialog() },
                onShare = { bitmap ->
                    streamViewModel.sharePhoto(bitmap)
                    streamViewModel.hideShareDialog()
                },
            )
        }
    }
}
