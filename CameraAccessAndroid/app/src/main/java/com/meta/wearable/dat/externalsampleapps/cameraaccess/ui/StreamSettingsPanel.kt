package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

private val PanelBg = Color(0xEE0D0D14)
private val ChipSelected = Color(0xFF3D7FF5)
private val ChipUnselected = Color(0xFF1E1E2C)
private val ChipBorderSelected = Color(0xFF5A9BFF)
private val ChipBorderUnselected = Color(0xFF2E2E42)
private val TextPrimary = Color(0xFFE8E8F0)
private val TextSecondary = Color(0xFF7070A0)
private val TextDisabled = Color(0xFF404058)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamSettingsPanel(
    isPhoneMode: Boolean,
    isStreaming: Boolean,
    onDismiss: () -> Unit,
    onApplyBitrate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var bitrateKbps by remember { mutableIntStateOf(SettingsManager.streamBitrateKbps) }
    var resolution by remember { mutableStateOf(SettingsManager.streamResolution) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
            .background(PanelBg)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Настройки стрима",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        if (isStreaming) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (onApplyBitrate != null)
                        "Битрейт можно применить без перезапуска"
                    else
                        "Разрешение применится при следующем запуске",
                    color = Color(0xFFFFB347),
                    fontSize = 12.sp,
                )
                if (onApplyBitrate != null) {
                    IconButton(
                        onClick = onApplyBitrate,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Применить",
                            tint = Color(0xFF5AE05A),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Битрейт",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                0 to "Авто",
                500 to "500 Kbps",
                1000 to "1 Mbps",
                2000 to "2 Mbps",
                3000 to "3 Mbps",
                4000 to "4 Mbps",
            ).forEach { (kbps, label) ->
                SettingsChip(
                    label = label,
                    selected = bitrateKbps == kbps,
                    enabled = true,
                    onClick = {
                        bitrateKbps = kbps
                        SettingsManager.streamBitrateKbps = kbps
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Разрешение",
                color = if (isPhoneMode) TextSecondary else TextDisabled,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!isPhoneMode) {
                Text(
                    text = "• только в режиме Phone",
                    color = TextDisabled,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "auto" to "Авто",
                "720p" to "720p",
                "480p" to "480p",
                "360p" to "360p",
            ).forEach { (preset, label) ->
                SettingsChip(
                    label = label,
                    selected = resolution == preset,
                    enabled = isPhoneMode,
                    onClick = {
                        resolution = preset
                        SettingsManager.streamResolution = preset
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Color(0xFF0E0E1A)
        selected -> ChipSelected.copy(alpha = 0.25f)
        else -> ChipUnselected
    }
    val border = when {
        !enabled -> ChipBorderUnselected.copy(alpha = 0.3f)
        selected -> ChipBorderSelected
        else -> ChipBorderUnselected
    }
    val textColor = when {
        !enabled -> TextDisabled
        selected -> Color(0xFF8FBFFF)
        else -> TextPrimary
    }

    val shape = RoundedCornerShape(8.dp)
    Text(
        text = label,
        color = textColor,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
