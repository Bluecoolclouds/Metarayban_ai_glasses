package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

private val CardBg = Color(0xFF0C0F1A)
private val NavBg  = Color(0xFF080C18)
private val Cam    = Color(0xFF4B9EFF)
private val CamBg  = Color(0xFF1A2A3F)
private val Str    = Color(0xFF00D46A)
private val StrBg  = Color(0xFF1A2E24)
private val Dic    = Color(0xFFFF4D6D)
private val DicBg  = Color(0xFF2E1A20)
private val Trn    = Color(0xFF22D3EE)
private val TrnBg  = Color(0xFF0F2430)

@Composable
fun MainMenuScreen(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
    geminiViewModel: GeminiSessionViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val uiState       by viewModel.uiState.collectAsStateWithLifecycle()
    val geminiUiState by geminiViewModel.uiState.collectAsStateWithLifecycle()
    val geminiMicLevel by geminiViewModel.micLevel.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val activity    = LocalActivity.current
    val context     = LocalContext.current
    var dropdownExpanded by remember { mutableStateOf(false) }

    val isRegistered        = uiState.isRegistered
    val hasDevice           = uiState.hasActiveDevice
    val isDisconnectEnabled = uiState.registrationState is RegistrationState.Registered

    val isGeminiActive = geminiUiState.isGeminiActive
    val geminiState    = geminiUiState.connectionState

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NavBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState),
        ) {

            // ── Header ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isRegistered) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AppColor.MetaBlue.copy(alpha = 0.18f))
                            .clickable { viewModel.hideMainMenu() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AppColor.MetaBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AppColor.MetaBlue.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "VS",
                            color = AppColor.MetaBlue,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.smart_glasses_icon),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Meta",
                        color = Color.White.copy(alpha = 0.70f),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "  |  ",
                        color = Color.White.copy(alpha = 0.20f),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "Ray-Ban",
                        color = Color.White.copy(alpha = 0.70f),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                IconButton(onClick = { viewModel.showSettings() }) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }

                if (isRegistered) {
                    Box {
                        IconButton(onClick = { dropdownExpanded = true }) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LinkOff,
                                    contentDescription = "Disconnect",
                                    tint = Color.White.copy(alpha = 0.55f),
                                    modifier = Modifier.size(17.dp),
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

            Spacer(Modifier.height(4.dp))

            // ── Device card ───────────────────────────────────────────────
            DeviceCard(
                hasDevice      = hasDevice,
                isGeminiActive = isGeminiActive,
                geminiState    = geminiState,
                micLevel       = geminiMicLevel,
                onToggleGemini = {
                    if (isGeminiActive) geminiViewModel.stopSession()
                    else geminiViewModel.startSession()
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(20.dp))

            // ── Quick Access ──────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("БЫСТРЫЙ ДОСТУП")
                Spacer(Modifier.height(10.dp))
                QuickAccessGrid(
                    hasDevice   = hasDevice,
                    onCamera    = { viewModel.navigateToStreaming(onRequestWearablesPermission) },
                    onStream    = { viewModel.navigateToStreaming(onRequestWearablesPermission) },
                    onDictaphone = { viewModel.showDictaphone() },
                    onTranslate = { viewModel.navigateToPhoneMode() },
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Functions ─────────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SectionLabel("ФУНКЦИИ")
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FeatureChip(
                            icon = Icons.Default.SmartToy,
                            iconColor = AppColor.Purple,
                            title = "AI Ассистент",
                            subtitle = "Gemini Live",
                            onClick = {
                                if (isGeminiActive) geminiViewModel.stopSession()
                                else geminiViewModel.startSession()
                            },
                        )
                        FeatureChip(
                            icon = Icons.Default.Visibility,
                            iconColor = AppColor.Cyan,
                            title = "Нарратор сцен",
                            subtitle = "Описание камеры",
                        )
                        FeatureChip(
                            icon = Icons.Default.TextFields,
                            iconColor = AppColor.Orange,
                            title = "Чтение текста",
                            subtitle = "OCR",
                        )
                        FeatureChip(
                            icon = Icons.Default.Restaurant,
                            iconColor = AppColor.Red,
                            title = "Калории",
                            subtitle = "Дневник питания",
                            onClick = { viewModel.showCalorie() },
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FeatureChip(
                            icon = Icons.Default.CameraAlt,
                            iconColor = AppColor.MetaBlue,
                            title = "POV Стриминг",
                            subtitle = "WebRTC в браузер",
                            onClick = { viewModel.navigateToStreaming(onRequestWearablesPermission) },
                        )
                        FeatureChip(
                            icon = Icons.Default.LiveTv,
                            iconColor = AppColor.TwitchPurple,
                            title = "Twitch",
                            subtitle = "RTMP эфир",
                        )
                        FeatureChip(
                            icon = Icons.Default.Language,
                            iconColor = AppColor.DeepBlue,
                            title = "Режим готовки",
                            subtitle = "Рецепты + таймер",
                        )
                        FeatureChip(
                            icon = Icons.Default.Settings,
                            iconColor = AppColor.SubtleText,
                            title = "Настройки",
                            subtitle = "Все параметры",
                            onClick = { viewModel.showSettings() },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── DeviceCard ──────────────────────────────────────────────────────────────

@Composable
private fun DeviceCard(
    hasDevice: Boolean,
    isGeminiActive: Boolean,
    geminiState: GeminiConnectionState,
    micLevel: Float,
    onToggleGemini: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(CardBg),
    ) {
        Image(
            painter = painterResource(R.drawable.rayban_meta_hero),
            contentDescription = null,
            modifier = Modifier
                .fillMaxHeight(0.88f)
                .align(Alignment.CenterEnd)
                .offset(x = 22.dp),
            contentScale = ContentScale.FillHeight,
            alpha = if (hasDevice) 1f else 0.35f,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.00f to CardBg.copy(alpha = 0.97f),
                        0.28f to CardBg.copy(alpha = 0.88f),
                        0.55f to CardBg.copy(alpha = 0.38f),
                        0.82f to Color.Transparent,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, top = 16.dp, end = 100.dp),
        ) {
            Text(
                text = "Ray-Ban Meta",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            )
            Text(
                text = "Wayfarer • Matte Black",
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (hasDevice) "73" else "—",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    lineHeight = 34.sp,
                )
                if (hasDevice) {
                    Text(
                        text = "%",
                        color = Color.White.copy(alpha = 0.65f),
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                    )
                }
            }
            Text(
                text = if (hasDevice) "Заряд очков" else "Нет подключения",
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(7.dp))
            LinearProgressIndicator(
                progress = { if (hasDevice) 0.73f else 0f },
                modifier = Modifier
                    .width(96.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = AppColor.MetaBlue,
                trackColor = Color.White.copy(alpha = 0.10f),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusBadge(
                icon = if (hasDevice) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                iconColor = if (hasDevice) AppColor.Cyan else AppColor.SubtleText,
                label = if (hasDevice) "Подключено" else "Bluetooth",
            )
            val geminiColor = when {
                isGeminiActive && geminiState is GeminiConnectionState.Ready -> AppColor.Green
                isGeminiActive -> AppColor.Orange
                else -> AppColor.SubtleText
            }
            val geminiLabel = when {
                isGeminiActive && geminiState is GeminiConnectionState.Ready -> "Gemini"
                isGeminiActive -> "Gemini…"
                else -> "AI Assistant"
            }
            StatusBadge(
                icon = Icons.Default.SmartToy,
                iconColor = geminiColor,
                label = geminiLabel,
                onClick = onToggleGemini,
            )
        }

        if (isGeminiActive) {
            GeminiMicBar(
                level = micLevel,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .width(110.dp)
                    .padding(end = 14.dp, bottom = 14.dp),
            )
        }
    }
}

// ── QuickAccessGrid ─────────────────────────────────────────────────────────

@Composable
private fun QuickAccessGrid(
    hasDevice: Boolean,
    onCamera: () -> Unit,
    onStream: () -> Unit,
    onDictaphone: () -> Unit,
    onTranslate: () -> Unit,
) {
    data class QAItem(
        val icon: ImageVector,
        val label: String,
        val sub: String,
        val color: Color,
        val bg: Color,
        val onClick: () -> Unit,
        val enabled: Boolean = true,
    )
    val items = listOf(
        QAItem(Icons.Default.CameraAlt, "Камера",    "Фото и видео",       Cam, CamBg, onCamera,    hasDevice),
        QAItem(Icons.Default.LiveTv,    "Стриминг",  "Трансляция",         Str, StrBg, onStream,    hasDevice),
        QAItem(Icons.Default.Mic,       "Диктофон",  "Запись",             Dic, DicBg, onDictaphone),
        QAItem(Icons.Default.Language,  "Перевод",   "Реальное время",     Trn, TrnBg, onTranslate),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items.forEach { item ->
            val a = if (item.enabled) 1f else 0.38f
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(item.bg)
                    .border(1.dp, item.color.copy(alpha = 0.18f * a), RoundedCornerShape(18.dp))
                    .clickable(enabled = item.enabled, onClick = item.onClick)
                    .padding(horizontal = 8.dp, vertical = 11.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = item.color.copy(alpha = a),
                        modifier = Modifier.size(17.dp),
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = item.color.copy(alpha = 0.40f * a),
                        modifier = Modifier.size(13.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = item.label,
                    color = Color.White.copy(alpha = a),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                )
                Text(
                    text = item.sub,
                    color = item.color.copy(alpha = 0.55f * a),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                )
            }
        }
    }
}

// ── Shared small composables ────────────────────────────────────────────────

@Composable
private fun StatusBadge(
    icon: ImageVector,
    iconColor: Color,
    label: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(iconColor.copy(alpha = if (onClick != null) 0.10f else 0.06f))
            .border(1.dp, iconColor.copy(alpha = if (onClick != null) 0.30f else 0.12f), CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(13.dp))
        Text(text = label, color = iconColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GeminiMicBar(level: Float, modifier: Modifier = Modifier) {
    val animLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(80),
        label = "mic",
    )
    val barColor = when {
        animLevel > 0.65f -> AppColor.Red
        animLevel > 0.30f -> AppColor.Orange
        else -> AppColor.Green
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Default.Mic, null, tint = barColor.copy(0.70f), modifier = Modifier.size(11.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(0.08f)),
        ) {
            Box(Modifier.fillMaxWidth(animLevel).height(3.dp).background(barColor))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.28f),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
    )
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
            .background(Brush.linearGradient(listOf(Color.White.copy(0.06f), Color.White.copy(0.02f))))
            .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.White.copy(0.35f), fontSize = 10.sp)
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(0.22f),
            modifier = Modifier.size(15.dp),
        )
    }
}
