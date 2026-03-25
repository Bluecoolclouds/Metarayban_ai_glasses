package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.AudioInputDevice

@Composable
fun ControlsRow(
    onStopStream: () -> Unit,
    onCapturePhoto: () -> Unit,
    onToggleAI: () -> Unit,
    isAIActive: Boolean,
    onToggleTTS: () -> Unit,
    isTTSActive: Boolean,
    isChatAvailable: Boolean = false,
    isServerConnected: Boolean,
    onToggleTwitch: () -> Unit = {},
    isTwitchStreaming: Boolean = false,
    isTwitchAvailable: Boolean = false,
    onToggleLive: () -> Unit = {},
    isLiveActive: Boolean = false,
    micLevel: Float = 0f,
    availableMics: List<AudioInputDevice> = emptyList(),
    selectedMicId: Int = 0,
    onMicSelected: (AudioInputDevice) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .fillMaxWidth(),
    ) {
        // Mic level indicator bar
        if (availableMics.isNotEmpty()) {
            MicLevelBar(
                level = micLevel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .padding(bottom = 6.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = AppColor.GlassDark,
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onStopStream,
                    modifier = Modifier.height(44.dp).weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColor.DestructiveBackground,
                        contentColor = AppColor.Red,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(20.dp),
                    )
                }

                CaptureButton(onClick = onCapturePhoto)

                ControlCircleButton(
                    onClick = onToggleAI,
                    isActive = isAIActive,
                    activeColor = AppColor.Green,
                    inactiveColor = AppColor.CardDarkElevated,
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = if (isAIActive) "Stop AI" else "Start AI",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }

                ControlCircleButton(
                    onClick = onToggleTTS,
                    isActive = isTTSActive,
                    activeColor = AppColor.TwitchPurple,
                    inactiveColor = AppColor.CardDarkElevated,
                    enabled = isChatAvailable || isServerConnected,
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubble,
                        contentDescription = if (isTTSActive) "Close Chat" else "Open Chat",
                        tint = if (isChatAvailable || isServerConnected) Color.White else AppColor.SubtleText,
                        modifier = Modifier.size(20.dp),
                    )
                }

                if (isTwitchAvailable) {
                    ControlCircleButton(
                        onClick = onToggleTwitch,
                        isActive = isTwitchStreaming,
                        activeColor = AppColor.TwitchPurple,
                        inactiveColor = AppColor.CardDarkElevated,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CastConnected,
                            contentDescription = if (isTwitchStreaming) "Stop Twitch" else "Start Twitch",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (isServerConnected) {
                    ControlCircleButton(
                        onClick = onToggleLive,
                        isActive = isLiveActive,
                        activeColor = AppColor.Red,
                        inactiveColor = AppColor.CardDarkElevated,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = if (isLiveActive) "Stop Live" else "Go Live",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Mic picker button
                if (availableMics.isNotEmpty()) {
                    MicPickerButton(
                        availableMics = availableMics,
                        selectedMicId = selectedMicId,
                        onMicSelected = onMicSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun MicLevelBar(
    level: Float,
    modifier: Modifier = Modifier,
) {
    val animatedLevel by animateFloatAsState(
        targetValue = level,
        animationSpec = tween(durationMillis = 80),
        label = "micLevel",
    )

    val barColor = when {
        animatedLevel > 0.8f -> AppColor.Red
        animatedLevel > 0.5f -> AppColor.Orange
        else -> AppColor.Green
    }

    Box(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(AppColor.CardDarkElevated),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedLevel.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(barColor),
        )
    }
}

@Composable
private fun MicPickerButton(
    availableMics: List<AudioInputDevice>,
    selectedMicId: Int,
    onMicSelected: (AudioInputDevice) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val isCustomMic = selectedMicId != 0

    Box {
        ControlCircleButton(
            onClick = { expanded = true },
            isActive = isCustomMic,
            activeColor = AppColor.Cyan.copy(alpha = 0.3f),
            inactiveColor = AppColor.CardDarkElevated,
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Select Microphone",
                tint = if (isCustomMic) AppColor.Cyan else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(AppColor.CardDark),
        ) {
            Text(
                text = "Микрофон",
                color = AppColor.SubtleText,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            availableMics.forEach { device ->
                val isSelected = device.id == selectedMicId
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = device.name,
                                color = if (isSelected) AppColor.Cyan else Color.White,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AppColor.Cyan,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    onClick = {
                        onMicSelected(device)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ControlCircleButton(
    onClick: () -> Unit,
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) activeColor else inactiveColor,
            disabledContainerColor = AppColor.CardDark,
        ),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
    ) {
        content()
    }
}
