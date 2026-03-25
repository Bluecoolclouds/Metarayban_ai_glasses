/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraAccessScaffold - DAT Application Navigation Orchestrator
//
// This scaffold demonstrates a typical DAT application navigation pattern based on device
// registration and streaming states from the DAT API.
//
// DAT State-Based Navigation:
// - HomeScreen: When NOT registered (uiState.isRegistered = false) Shows initial registration UI
//   calling Wearables.startRegistration()
// - MainMenuScreen: When registered (uiState.isRegistered = true) but not streaming. Shows all
//   available modes and device connection status. Disconnect button available in top bar.
// - StreamScreen: When actively streaming (uiState.isStreaming = true) Shows live video from
//   StreamSession.videoStream and photo capture UI
//
// The scaffold also provides a debug menu (in DEBUG builds) that gives access to
// MockDeviceKitScreen for testing DAT functionality without physical devices.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiSessionViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.skills.SkillManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlinx.coroutines.delay

@Composable
fun CameraAccessScaffold(
    wearablesVM: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
    geminiViewModel: GeminiSessionViewModel = viewModel(),
) {
  val context = LocalContext.current
  val application = context.applicationContext as android.app.Application

  val streamViewModel: StreamViewModel = viewModel(
      factory = StreamViewModel.Factory(
          application = application,
          wearablesViewModel = wearablesVM,
      )
  )

  val uiState by wearablesVM.uiState.collectAsStateWithLifecycle()
  val geminiUiState by geminiViewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }

  // Wire Gemini VM to Stream VM so frames reach Gemini even before StreamScreen is visible
  LaunchedEffect(geminiViewModel) {
      streamViewModel.geminiViewModel = geminiViewModel
  }

  LaunchedEffect(Unit) {
      if (!geminiUiState.isGeminiActive) {
          geminiViewModel.startSession()
      }
  }

  // Auto-reconnect Gemini everywhere in the app (not just in StreamScreen)
  LaunchedEffect(geminiUiState.connectionState) {
      if (geminiUiState.connectionState == GeminiConnectionState.Disconnected &&
          !geminiUiState.isGeminiActive &&
          geminiUiState.isAutoReconnectEnabled) {
          delay(5000)
          if (!geminiUiState.isGeminiActive && geminiUiState.isAutoReconnectEnabled) {
              geminiViewModel.startSession()
          }
      }
  }

  // Wire voice camera commands directly to StreamViewModel — works even with locked screen
  LaunchedEffect(streamViewModel) {
      SkillManager.onCameraCommand = { action ->
          when (action) {
              "start" -> streamViewModel.startStream()
              "stop"  -> streamViewModel.stopStream()
          }
      }
  }

  // Observe camera permission errors and show snackbar
  LaunchedEffect(uiState.recentError) {
    uiState.recentError?.let { errorMessage ->
      snackbarHostState.showSnackbar(errorMessage)
      wearablesVM.clearCameraPermissionError()
    }
  }

  Surface(modifier = modifier.fillMaxSize(), color = AppColor.SurfaceBlack) {
    Box(modifier = Modifier.fillMaxSize()) {
      when {
        uiState.isSettingsVisible ->
            SettingsScreen(
                onBack = { wearablesVM.hideSettings() },
            )
        uiState.isCalorieVisible ->
            CalorieScreen(
                onBack = { wearablesVM.hideCalorie() },
            )
        uiState.isDictaphoneVisible ->
            DictaphoneScreen(
                onBack = { wearablesVM.hideDictaphone() },
            )
        uiState.isMainMenuVisible ->
            MainMenuScreen(
                viewModel = wearablesVM,
                geminiViewModel = geminiViewModel,
                onRequestWearablesPermission = onRequestWearablesPermission,
            )
        uiState.isStreaming ->
            StreamScreen(
                wearablesViewModel = wearablesVM,
                isPhoneMode = uiState.isPhoneMode,
                geminiViewModel = geminiViewModel,
            )
        uiState.isRegistered ->
            MainMenuScreen(
                viewModel = wearablesVM,
                geminiViewModel = geminiViewModel,
                onRequestWearablesPermission = onRequestWearablesPermission,
            )
        else ->
            HomeScreen(
                viewModel = wearablesVM,
            )
      }

      TranslatorPanel(
          visible = geminiUiState.activeSkillName == "Translator",
          onClose = { geminiViewModel.deactivateSkill() },
          modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
      )

      SnackbarHost(
          hostState = snackbarHostState,
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .padding(horizontal = 16.dp, vertical = 32.dp),
          snackbar = { data ->
            Snackbar(
                shape = RoundedCornerShape(16.dp),
                containerColor = AppColor.CardDark,
                contentColor = androidx.compose.ui.graphics.Color.White,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Camera Access error",
                    tint = AppColor.Red,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(data.visuals.message)
              }
            }
          },
      )

    }
  }
}
