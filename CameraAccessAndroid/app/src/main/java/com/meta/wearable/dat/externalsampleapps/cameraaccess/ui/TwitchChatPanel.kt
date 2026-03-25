package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.twitch.TwitchChatMessage

private val defaultColors = listOf(
    Color(0xFFFF4500), Color(0xFF00FF7F), Color(0xFF1E90FF),
    Color(0xFFFF69B4), Color(0xFFFFD700), Color(0xFF9ACD32),
    Color(0xFFFF6347), Color(0xFF00CED1), Color(0xFFDA70D6),
    Color(0xFF5F9EA0), Color(0xFFB22222), Color(0xFFDAA520),
)

private fun usernameColor(color: String?, username: String): Color {
    if (!color.isNullOrEmpty()) {
        try {
            return Color(android.graphics.Color.parseColor(color))
        } catch (_: Exception) {}
    }
    return defaultColors[username.hashCode().and(0x7FFFFFFF) % defaultColors.size]
}

@Composable
fun TwitchChatPanel(
    visible: Boolean,
    messages: List<TwitchChatMessage>,
    isConnected: Boolean,
    isTTSActive: Boolean,
    onToggleTTS: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColor.CardDark)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (isConnected) AppColor.Green else AppColor.Red,
                                shape = CircleShape,
                            ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isConnected) "Twitch Chat" else "Connecting...",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )

                    Button(
                        onClick = onToggleTTS,
                        modifier = Modifier.height(36.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTTSActive) AppColor.TwitchPurple else AppColor.CardDarkElevated,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(
                            imageVector = if (isTTSActive) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = "TTS",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isTTSActive) "TTS On" else "TTS Off",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close chat",
                            tint = Color.White,
                        )
                    }
                }

                val listState = rememberLazyListState()

                LaunchedEffect(messages.size) {
                    if (messages.isNotEmpty()) {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }

                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isConnected) "No messages yet" else "Connecting to chat...",
                            color = AppColor.SubtleText,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(messages, key = { "${it.timestamp}_${it.username}_${it.text.hashCode()}" }) { msg ->
                            ChatMessageRow(msg)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageRow(msg: TwitchChatMessage) {
    val nameColor = usernameColor(msg.color, msg.username)
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) {
                append(msg.username)
            }
            withStyle(SpanStyle(color = Color.White.copy(alpha = 0.5f))) {
                append(": ")
            }
            withStyle(SpanStyle(color = Color.White.copy(alpha = 0.9f))) {
                append(msg.text)
            }
        },
        fontSize = 14.sp,
        lineHeight = 18.sp,
    )
}
