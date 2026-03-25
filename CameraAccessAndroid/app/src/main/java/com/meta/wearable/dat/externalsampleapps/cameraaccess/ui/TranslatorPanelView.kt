package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.skills.TranslationEntry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.skills.TranslatorSkill

@Composable
fun TranslatorPanel(
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val history by TranslatorSkill.history.collectAsStateWithLifecycle()
    val liveText by TranslatorSkill.liveText.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xF0000000),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                )
                .navigationBarsPadding(),
        ) {
            TranslatorHeader(
                entryCount = history.size,
                onClose = onClose,
                onClear = { TranslatorSkill.clearAll() },
            )

            TranslatorHistoryList(
                history = history,
                liveText = liveText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 380.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TranslatorHeader(
    entryCount: Int,
    onClose: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(AppColor.MetaBlue),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Переводчик",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (entryCount > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$entryCount",
                color = AppColor.SubtleText,
                fontSize = 13.sp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (entryCount > 0) {
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Очистить",
                    tint = AppColor.SubtleText,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Закрыть",
                tint = AppColor.SubtleText,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(AppColor.Divider),
    )
}

@Composable
private fun TranslatorHistoryList(
    history: List<TranslationEntry>,
    liveText: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(history.size, liveText) {
        val itemCount = history.size + (if (liveText.isNotEmpty()) 1 else 0)
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    if (history.isEmpty() && liveText.isEmpty()) {
        Box(
            modifier = modifier.padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Говорите — перевод появится здесь",
                color = AppColor.SubtleText,
                fontSize = 14.sp,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        items(history, key = { it.timestamp }) { entry ->
            TranslationBubble(
                text = entry.text,
                isLive = false,
            )
        }

        if (liveText.isNotEmpty()) {
            item(key = "live") {
                TranslationBubble(
                    text = liveText,
                    isLive = true,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(4.dp)) }
    }
}

@Composable
private fun TranslationBubble(
    text: String,
    isLive: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isLive) Color(0x33007AFF) else Color(0xFF1C1C1E)
    val textColor = if (isLive) Color(0xFFADD8FF) else Color.White

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column {
            Text(
                text = text,
                color = textColor,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            if (isLive) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "● перевод...",
                    color = AppColor.MetaBlue.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}
