package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun MainMenuScreen(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
    geminiViewModel: GeminiSessionViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val geminiUiState by geminiViewModel.uiState.collectAsStateWithLifecycle()
    val geminiMicLevel by geminiViewModel.micLevel.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val activity = LocalActivity.current
    val context = LocalContext.current
    var dropdownExpanded by remember { mutableStateOf(false) }

    val isRegistered = uiState.isRegistered
    val hasDevice = uiState.hasActiveDevice
    val isDisconnectEnabled = uiState.registrationState is RegistrationState.Registered

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColor.SurfaceDeep),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x2006B6D4),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isRegistered) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                            .clickable { viewModel.hideMainMenu() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val pulse = rememberInfiniteTransition(label = "pulse")
                        val alpha by pulse.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                tween(900, easing = LinearEasing),
                                RepeatMode.Reverse,
                            ),
                            label = "pulse_alpha",
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (hasDevice) AppColor.Green.copy(alpha = alpha) else AppColor.Red.copy(alpha = alpha),
                                    shape = CircleShape,
                                ),
                        )
                        Text(
                            text = if (hasDevice) "Подключено" else "Отключено",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = { viewModel.showSettings() }) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                if (isRegistered) {
                    Box {
                        IconButton(onClick = { dropdownExpanded = true }) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LinkOff,
                                    contentDescription = "Disconnect",
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Disconnect",
                                        color = if (isDisconnectEnabled) AppColor.Red else AppColor.SubtleText,
                                    )
                                },
                                enabled = isDisconnectEnabled,
                                onClick = {
                                    activity?.let { viewModel.startUnregistration(it) }
                                        ?: Toast.makeText(context, "Activity not available", Toast.LENGTH_SHORT).show()
                                    dropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            GlassesHero(
                hasDevice = hasDevice,
                isGeminiActive = geminiUiState.isGeminiActive,
                geminiState = geminiUiState.connectionState,
                micLevel = geminiMicLevel,
                onToggleGemini = {
                    if (geminiUiState.isGeminiActive) geminiViewModel.stopSession()
                    else geminiViewModel.startSession()
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionLabel("СТАРТ")

                GlassActionCard(
                    icon = Icons.Default.PlayArrow,
                    iconColor = AppColor.Green,
                    title = "Запустить стриминг",
                    subtitle = "Трансляция с камеры очков",
                    enabled = hasDevice,
                    onClick = { viewModel.navigateToStreaming(onRequestWearablesPermission) },
                )
                GlassActionCard(
                    icon = Icons.Default.PhoneAndroid,
                    iconColor = AppColor.Cyan,
                    title = "Камера телефона",
                    subtitle = "Стриминг с фронтальной камеры",
                    onClick = { viewModel.navigateToPhoneMode() },
                )
                GlassActionCard(
                    icon = Icons.Default.Mic,
                    iconColor = AppColor.Orange,
                    title = "Диктофон",
                    subtitle = "Голосовой рекордер",
                    onClick = { viewModel.showDictaphone() },
                )
                GlassActionCard(
                    icon = Icons.Default.Restaurant,
                    iconColor = AppColor.Red,
                    title = "Калории",
                    subtitle = "Дневник питания и счётчик",
                    onClick = { viewModel.showCalorie() },
                )
                GlassActionCard(
                    icon = Icons.Default.Language,
                    iconColor = AppColor.DeepBlue,
                    title = "Переводчик",
                    subtitle = "Перевод речи в реальном времени",
                    onClick = { viewModel.navigateToPhoneMode() },
                )

                Spacer(modifier = Modifier.height(4.dp))
                SectionLabel("ФУНКЦИИ")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FeatureChip(Icons.Default.SmartToy, AppColor.Purple, "AI Ассистент", "Gemini Live")
                        FeatureChip(Icons.Default.Visibility, AppColor.Cyan, "Нарратор сцен", "Описание камеры")
                        FeatureChip(Icons.Default.TextFields, AppColor.Orange, "Чтение текста", "OCR")
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FeatureChip(Icons.Default.CameraAlt, AppColor.MetaBlue, "POV Стриминг", "WebRTC в браузер")
                        FeatureChip(Icons.Default.LiveTv, AppColor.TwitchPurple, "Twitch", "RTMP эфир")
                        FeatureChip(Icons.Default.Restaurant, AppColor.Red, "Режим готовки", "Рецепты + таймер")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun GlassesHero(
    hasDevice: Boolean,
    isGeminiActive: Boolean,
    geminiState: GeminiConnectionState,
    micLevel: Float = 0f,
    onToggleGemini: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(3.dp)
                        .background(AppColor.Cyan.copy(alpha = 0.20f), RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp)),
                )
                Box(
                    modifier = Modifier
                        .size(width = 96.dp, height = 64.dp)
                        .border(1.5.dp, AppColor.Cyan.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(listOf(AppColor.Cyan.copy(0.08f), Color.Transparent)),
                            RoundedCornerShape(14.dp),
                        ),
                )
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(3.dp)
                        .background(AppColor.Cyan.copy(alpha = 0.28f), CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(width = 96.dp, height = 64.dp)
                        .border(1.5.dp, AppColor.Cyan.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(listOf(AppColor.Cyan.copy(0.08f), Color.Transparent)),
                            RoundedCornerShape(14.dp),
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(6.dp)
                            .background(AppColor.Cyan.copy(alpha = 0.60f), CircleShape),
                    )
                }
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(3.dp)
                        .background(AppColor.Cyan.copy(alpha = 0.20f), RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Ray-Ban Meta",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Wayfarer · Matte Black",
            color = Color.White.copy(alpha = 0.40f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusBadge(
                icon = if (hasDevice) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                iconColor = if (hasDevice) AppColor.Cyan else AppColor.SubtleText,
                label = if (hasDevice) "BT 5.3" else "BT Off",
            )
            val geminiColor = when {
                isGeminiActive && geminiState is GeminiConnectionState.Ready -> AppColor.Green
                isGeminiActive -> AppColor.Orange
                else -> AppColor.SubtleText
            }
            val geminiLabel = when {
                isGeminiActive && geminiState is GeminiConnectionState.Ready -> "Gemini •"
                isGeminiActive -> "Gemini …"
                else -> "Gemini ○"
            }
            StatusBadge(
                icon = Icons.Default.SmartToy,
                iconColor = geminiColor,
                label = geminiLabel,
                onClick = onToggleGemini,
            )
        }

        if (isGeminiActive) {
            Spacer(modifier = Modifier.height(10.dp))
            GeminiMicBar(level = micLevel)
        }
    }
}

@Composable
private fun StatusBadge(
    icon: ImageVector,
    iconColor: Color,
    label: String,
    onClick: (() -> Unit)? = null,
) {
    val borderColor = if (onClick != null) iconColor.copy(alpha = 0.30f)
                      else Color.White.copy(alpha = 0.08f)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (onClick != null) iconColor.copy(alpha = 0.08f)
                else Color.White.copy(alpha = 0.05f)
            )
            .border(1.dp, borderColor, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            color = iconColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun GeminiMicBar(level: Float, modifier: Modifier = Modifier) {
    val animLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 80),
        label = "mic-level",
    )
    val barColor = when {
        animLevel > 0.65f -> AppColor.Red
        animLevel > 0.30f -> AppColor.Orange
        else -> AppColor.Green
    }
    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = barColor.copy(alpha = 0.70f),
            modifier = Modifier.size(11.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animLevel)
                    .height(3.dp)
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.30f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun GlassActionCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (enabled) 0.07f else 0.03f),
                        Color.White.copy(alpha = if (enabled) 0.02f else 0.01f),
                    ),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconColor.copy(alpha = 0.18f * alpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor.copy(alpha = alpha),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White.copy(alpha = alpha),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.40f * alpha),
                fontSize = 12.sp,
            )
        }
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = iconColor.copy(alpha = if (enabled) 0.6f else 0.2f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun FeatureChip(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 10.sp,
            )
        }
    }
}
