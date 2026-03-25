package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRTCUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WebRTCOverlay(
    uiState: WebRTCUiState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusPill(
            label = when {
                uiState.isSignalingOnly && uiState.connectionState is WebRTCConnectionState.WaitingForPeer -> "Server"
                uiState.isSignalingOnly && uiState.connectionState is WebRTCConnectionState.Connecting -> "Connecting..."
                uiState.connectionState is WebRTCConnectionState.Connected -> "Live"
                uiState.connectionState is WebRTCConnectionState.Connecting -> "Connecting..."
                uiState.connectionState is WebRTCConnectionState.WaitingForPeer -> "Waiting..."
                uiState.connectionState is WebRTCConnectionState.Backgrounded -> "Paused"
                uiState.connectionState is WebRTCConnectionState.Error -> "Error"
                else -> "Off"
            },
            color = when {
                uiState.isSignalingOnly && uiState.connectionState is WebRTCConnectionState.WaitingForPeer -> AppColor.MetaBlue
                uiState.connectionState is WebRTCConnectionState.Connected -> AppColor.Green
                uiState.connectionState is WebRTCConnectionState.Connecting ||
                uiState.connectionState is WebRTCConnectionState.WaitingForPeer -> AppColor.Orange
                uiState.connectionState is WebRTCConnectionState.Backgrounded -> AppColor.Orange
                uiState.connectionState is WebRTCConnectionState.Error -> AppColor.Red
                else -> AppColor.SubtleText
            },
        )

        if (uiState.roomCode.isNotEmpty()) {
            RoomCodePill(code = uiState.roomCode)
        }

        if (uiState.connectionState is WebRTCConnectionState.Connected) {
            StatusPill(
                label = if (uiState.isMuted) "Muted" else "Mic On",
                color = if (uiState.isMuted) AppColor.Red else AppColor.Green,
            )
        }
    }
}

@Composable
fun RoomCodePill(
    code: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCopied by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .background(AppColor.GlassDark, RoundedCornerShape(12.dp))
            .clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Room Code", code))
                showCopied = true
                scope.launch {
                    delay(1500)
                    showCopied = false
                }
            }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (showCopied) "Copied" else code,
            color = if (showCopied) AppColor.Green else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}
