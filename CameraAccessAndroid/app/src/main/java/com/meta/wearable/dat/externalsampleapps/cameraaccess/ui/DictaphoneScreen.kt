package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.dictaphone.DictaphoneViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.dictaphone.Recording

@Composable
fun DictaphoneScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DictaphoneViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val currentIsRecording by rememberUpdatedState(uiState.isRecording)

    DisposableEffect(Unit) {
        onDispose {
            if (currentIsRecording) {
                viewModel.toggleRecording()
            }
            viewModel.stopPlayback()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColor.SurfaceBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Dictaphone",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.size(48.dp))
            }

            RecordingControl(
                isRecording = uiState.isRecording,
                elapsedMs = uiState.recordingElapsedMs,
                onToggle = { viewModel.toggleRecording() },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "RECORDINGS",
                color = AppColor.SubtleText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
            )

            if (uiState.recordings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = AppColor.SubtleText.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No recordings yet",
                            color = AppColor.SubtleText,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = "Tap the button above to start",
                            color = AppColor.SubtleText.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.recordings, key = { it.id }) { recording ->
                        RecordingItem(
                            recording = recording,
                            isPlaying = uiState.playingRecordingId == recording.id,
                            playbackProgress = if (uiState.playingRecordingId == recording.id && uiState.playbackDurationMs > 0)
                                uiState.playbackPositionMs.toFloat() / uiState.playbackDurationMs.toFloat()
                            else 0f,
                            onTogglePlayback = { viewModel.togglePlayback(recording) },
                            onDelete = { viewModel.deleteRecording(recording) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingControl(
    isRecording: Boolean,
    elapsedMs: Long,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalSec = elapsedMs / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val timerText = "%02d:%02d".format(min, sec)

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val buttonColor by animateColorAsState(
        targetValue = if (isRecording) AppColor.Red else AppColor.Green,
        label = "buttonColor",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppColor.CardDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isRecording) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        tint = AppColor.Red,
                        modifier = Modifier
                            .size(12.dp)
                            .scale(pulseScale),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "REC",
                        color = AppColor.Red,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = timerText,
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Light,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(buttonColor)
                    .then(
                        if (isRecording) Modifier.scale(pulseScale) else Modifier
                    )
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop recording" else "Start recording",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isRecording) "Tap to stop" else "Tap to record",
                color = AppColor.SubtleText,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun RecordingItem(
    recording: Recording,
    isPlaying: Boolean,
    playbackProgress: Float,
    onTogglePlayback: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) AppColor.CardDarkElevated else AppColor.CardDark
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlaying) AppColor.MetaBlue else AppColor.MetaBlue.copy(alpha = 0.15f)
                        )
                        .clickable(onClick = onTogglePlayback),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (isPlaying) Color.White else AppColor.MetaBlue,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recording.name,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        maxLines = 1,
                    )
                    Row {
                        Text(
                            text = recording.formattedDuration,
                            color = AppColor.SubtleText,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = "  ·  ",
                            color = AppColor.SubtleText,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = recording.formattedDate,
                            color = AppColor.SubtleText,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = "  ·  ",
                            color = AppColor.SubtleText,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = recording.formattedSize,
                            color = AppColor.SubtleText,
                            fontSize = 12.sp,
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = AppColor.SubtleText,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (isPlaying) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { playbackProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = AppColor.MetaBlue,
                    trackColor = AppColor.Divider,
                )
            }
        }
    }
}
