/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.CAMERA
import android.Manifest.permission.INTERNET
import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.memory.MemoryRepository
import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.skills.SkillManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.CameraAccessScaffold
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
  companion object {
    val PERMISSIONS: Array<String> = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET, RECORD_AUDIO, CAMERA, POST_NOTIFICATIONS)
    } else {
        arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET, RECORD_AUDIO, CAMERA)
    }
  }

  val viewModel: WearablesViewModel by viewModels()

  private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
  private val permissionMutex = Mutex()
  private val permissionsResultLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(permissionStatus)
        permissionContinuation = null
      }

  suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
    return permissionMutex.withLock {
      suspendCancellableCoroutine { continuation ->
        permissionContinuation = continuation
        continuation.invokeOnCancellation { permissionContinuation = null }
        permissionsResultLauncher.launch(permission)
      }
    }
  }

  private var wearablesInitialized = false

  private val permissionsLauncher =
      registerForActivityResult(RequestMultiplePermissions()) { permissionsResult ->
        val granted = permissionsResult.entries.all { it.value }
        if (granted) {
          initializeWearables()
        } else {
          viewModel.setRecentError(
              "Allow All Permissions (Bluetooth, Bluetooth Connect, Internet, Microphone, Camera)"
          )
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    SettingsManager.init(this)
    MemoryRepository.init(this)
    SkillManager.init(this)

    initializeWearables()

    if (!allPermissionsGranted()) {
      permissionsLauncher.launch(PERMISSIONS)
    }

    setContent {
      CameraAccessScaffold(
          wearablesVM = viewModel,
          onRequestWearablesPermission = ::requestWearablesPermission,
      )
    }
  }

  private fun allPermissionsGranted(): Boolean {
    return PERMISSIONS.all {
      ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
  }

  private fun initializeWearables() {
    if (!wearablesInitialized) {
      Log.d("MainActivity", "Initializing Wearables SDK")
      Wearables.initialize(this)
      wearablesInitialized = true
      viewModel.startMonitoring()
    }
  }
}
