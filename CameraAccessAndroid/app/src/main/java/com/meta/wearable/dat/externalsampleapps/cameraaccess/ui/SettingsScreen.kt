package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.AudioDeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

private val MetaBlue = Color(0xFF0064E0)
private val CardBackground = Color(0xFF1C1C1E)
private val SurfaceBackground = Color(0xFF000000)
private val SubtleText = Color(0xFF8E8E93)
private val DividerColor = Color(0xFF2C2C2E)
private val DangerRed = Color(0xFFFF453A)
private val IconBgGreen = Color(0xFF30D158)
private val IconBgOrange = Color(0xFFFF9F0A)
private val IconBgPurple = Color(0xFFBF5AF2)
private val IconBgPink = Color(0xFFFF375F)
private val IconBgTeal = Color(0xFF64D2FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var geminiAPIKey by remember { mutableStateOf(SettingsManager.geminiAPIKey) }
    var systemPrompt by remember { mutableStateOf(SettingsManager.geminiSystemPrompt) }
    var webrtcSignalingURL by remember { mutableStateOf(SettingsManager.webrtcSignalingURL) }
    var streamingPlatform by remember { mutableStateOf(SettingsManager.streamingPlatform) }
    var twitchChannelName by remember { mutableStateOf(SettingsManager.twitchChannelName) }
    var twitchStreamKey by remember { mutableStateOf(SettingsManager.twitchStreamKey) }
    var youtubeStreamKey by remember { mutableStateOf(SettingsManager.youtubeStreamKey) }
    var customRtmpUrl by remember { mutableStateOf(SettingsManager.customRtmpUrl) }
    var selectedMicId by remember { mutableIntStateOf(SettingsManager.preferredMicDeviceId) }
    var showResetDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var audioDevices by remember { mutableStateOf(AudioDeviceSelector.getAvailableInputDevices(context)) }

    DisposableEffect(Unit) {
        val am = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                audioDevices = AudioDeviceSelector.getAvailableInputDevices(context)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                audioDevices = AudioDeviceSelector.getAvailableInputDevices(context)
                if (audioDevices.none { it.id == selectedMicId }) {
                    selectedMicId = 0
                    SettingsManager.preferredMicDeviceId = 0
                }
            }
        }
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { am.unregisterAudioDeviceCallback(callback) }
    }

    fun save() {
        SettingsManager.geminiAPIKey = geminiAPIKey.trim()
        SettingsManager.geminiSystemPrompt = systemPrompt.trim()
        SettingsManager.webrtcSignalingURL = webrtcSignalingURL.trim()
        SettingsManager.streamingPlatform = streamingPlatform
        SettingsManager.twitchChannelName = twitchChannelName.trim()
        SettingsManager.twitchStreamKey = twitchStreamKey.trim()
        SettingsManager.youtubeStreamKey = youtubeStreamKey.trim()
        SettingsManager.customRtmpUrl = customRtmpUrl.trim()
        SettingsManager.preferredMicDeviceId = selectedMicId
    }

    fun reload() {
        geminiAPIKey = SettingsManager.geminiAPIKey
        systemPrompt = SettingsManager.geminiSystemPrompt
        webrtcSignalingURL = SettingsManager.webrtcSignalingURL
        streamingPlatform = SettingsManager.streamingPlatform
        twitchChannelName = SettingsManager.twitchChannelName
        twitchStreamKey = SettingsManager.twitchStreamKey
        youtubeStreamKey = SettingsManager.youtubeStreamKey
        customRtmpUrl = SettingsManager.customRtmpUrl
        selectedMicId = SettingsManager.preferredMicDeviceId
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground)
    ) {
        TopAppBar(
            title = {
                Text(
                    "Settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                )
            },
            navigationIcon = {
                IconButton(onClick = {
                    save()
                    onBack()
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MetaBlue,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = SurfaceBackground,
                titleContentColor = Color.White,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            SettingsCategory(title = "AUDIO") {
                MicrophoneSelectorCard(
                    devices = audioDevices,
                    selectedId = selectedMicId,
                    onDeviceSelected = { id ->
                        selectedMicId = id
                        SettingsManager.preferredMicDeviceId = id
                    },
                )
            }

            SettingsCategory(title = "AI ENGINE") {
                SettingsCard {
                    SettingsTextField(
                        icon = Icons.Default.Key,
                        iconBg = IconBgGreen,
                        value = geminiAPIKey,
                        onValueChange = { geminiAPIKey = it },
                        label = "Gemini API Key",
                        placeholder = "Enter your API key",
                        isPassword = true,
                    )
                }
            }

            SettingsCategory(title = "SYSTEM PROMPT") {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsIconBadge(Icons.Default.Description, IconBgPurple)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "AI Behavior Instructions",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = Color.White,
                                fontSize = 12.sp,
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MetaBlue,
                                unfocusedBorderColor = DividerColor,
                                cursorColor = MetaBlue,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }

            SettingsCategory(title = "STREAMING") {
                SettingsCard {
                    SettingsTextField(
                        icon = Icons.Default.Wifi,
                        iconBg = IconBgTeal,
                        value = webrtcSignalingURL,
                        onValueChange = { webrtcSignalingURL = it },
                        label = "WebRTC Signaling URL",
                        placeholder = "wss://your-server.example.com",
                        keyboardType = KeyboardType.Uri,
                    )
                }
            }

            SettingsCategory(title = "BROADCAST MODE") {
                SettingsCard {
                    PlatformSelectorRow(
                        selected = streamingPlatform,
                        onSelected = {
                            streamingPlatform = it
                            SettingsManager.streamingPlatform = it
                        },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                SettingsCard {
                    SettingsTextField(
                        icon = Icons.Default.ChatBubble,
                        iconBg = AppColor.TwitchPurple,
                        value = twitchChannelName,
                        onValueChange = { twitchChannelName = it },
                        label = "Twitch Channel Name",
                        placeholder = "your_channel",
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                when (streamingPlatform) {
                    "twitch" -> SettingsCard {
                        SettingsTextField(
                            icon = Icons.Default.LiveTv,
                            iconBg = IconBgPink,
                            value = twitchStreamKey,
                            onValueChange = { twitchStreamKey = it },
                            label = "Twitch Stream Key",
                            placeholder = "live_XXXXXXXXXX",
                            isPassword = true,
                        )
                    }
                    "youtube" -> SettingsCard {
                        SettingsTextField(
                            icon = Icons.Default.LiveTv,
                            iconBg = Color(0xFFFF0000),
                            value = youtubeStreamKey,
                            onValueChange = { youtubeStreamKey = it },
                            label = "YouTube Stream Key",
                            placeholder = "xxxx-xxxx-xxxx-xxxx",
                            isPassword = true,
                        )
                    }
                    "custom_rtmp" -> SettingsCard {
                        SettingsTextField(
                            icon = Icons.Default.LiveTv,
                            iconBg = IconBgOrange,
                            value = customRtmpUrl,
                            onValueChange = { customRtmpUrl = it },
                            label = "RTMP URL",
                            placeholder = "rtmp://your-server.com/live/key",
                            keyboardType = KeyboardType.Uri,
                        )
                    }
                }
            }

            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showResetDialog = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsIconBadge(Icons.Default.RestartAlt, DangerRed)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Reset All Settings",
                        color = DangerRed,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = CardBackground,
            titleContentColor = Color.White,
            textContentColor = SubtleText,
            title = { Text("Reset Settings", fontWeight = FontWeight.Bold) },
            text = { Text("All settings will be restored to their default values. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    SettingsManager.resetAll()
                    reload()
                    showResetDialog = false
                }) {
                    Text("Reset", color = DangerRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = MetaBlue)
                }
            },
        )
    }
}

@Composable
private fun SettingsCategory(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = SubtleText,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun SettingsCard(
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsIconBadge(icon: ImageVector, backgroundColor: Color) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = DividerColor,
        modifier = Modifier.padding(start = 60.dp),
    )
}

@Composable
private fun SettingsTextField(
    icon: ImageVector,
    iconBg: Color,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconBadge(icon, iconBg)
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 12.sp) },
            placeholder = { Text(placeholder, color = SubtleText, fontSize = 13.sp) },
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                fontSize = 14.sp,
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else keyboardType
            ),
            visualTransformation = if (isPassword && !passwordVisible)
                PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility
                                else Icons.Default.VisibilityOff,
                            contentDescription = if (passwordVisible) "Hide" else "Show",
                            tint = SubtleText,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MetaBlue,
                unfocusedBorderColor = DividerColor,
                focusedLabelColor = MetaBlue,
                unfocusedLabelColor = SubtleText,
                cursorColor = MetaBlue,
            ),
            shape = RoundedCornerShape(12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MicrophoneSelectorCard(
    devices: List<com.meta.wearable.dat.externalsampleapps.cameraaccess.audio.AudioInputDevice>,
    selectedId: Int,
    onDeviceSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDevice = devices.firstOrNull { it.id == selectedId } ?: devices.first()

    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIconBadge(Icons.Default.Mic, IconBgPink)
            Spacer(modifier = Modifier.width(12.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = selectedDevice.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Audio Input", fontSize = 12.sp) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White,
                        fontSize = 14.sp,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MetaBlue,
                        unfocusedBorderColor = DividerColor,
                        focusedLabelColor = MetaBlue,
                        unfocusedLabelColor = SubtleText,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    devices.forEach { device ->
                        DropdownMenuItem(
                            text = { Text(device.name) },
                            onClick = {
                                onDeviceSelected(device.id)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformSelectorRow(
    selected: String,
    onSelected: (String) -> Unit,
) {
    val platforms = listOf(
        Triple("twitch", "Twitch", Color(0xFF9146FF)),
        Triple("youtube", "YouTube", Color(0xFFFF0000)),
        Triple("custom_rtmp", "RTMP", IconBgOrange),
    )

    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBadge(Icons.Default.LiveTv, IconBgPink)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "Platform",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            platforms.forEach { (id, label, color) ->
                val isSelected = selected == id
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) color else Color(0xFF2C2C2E)
                        )
                        .clickable { onSelected(id) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else SubtleText,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
