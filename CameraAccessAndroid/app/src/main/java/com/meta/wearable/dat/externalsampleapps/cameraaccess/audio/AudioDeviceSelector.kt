package com.meta.wearable.dat.externalsampleapps.cameraaccess.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

data class AudioInputDevice(
    val id: Int,
    val name: String,
    val type: Int,
) {
    companion object {
        val SYSTEM_DEFAULT = AudioInputDevice(id = 0, name = "System Default", type = 0)
    }
}

object AudioDeviceSelector {
    private const val TAG = "AudioDeviceSelector"

    fun getAvailableInputDevices(context: Context): List<AudioInputDevice> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val result = mutableListOf(AudioInputDevice.SYSTEM_DEFAULT)

        for (device in devices) {
            val name = buildDeviceName(device)
            result.add(AudioInputDevice(id = device.id, name = name, type = device.type))
        }

        Log.d(TAG, "Found ${result.size} input devices: ${result.map { it.name }}")
        return result
    }

    fun setPreferredDevice(context: Context, deviceId: Int): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (deviceId == 0) {
            audioManager.clearCommunicationDevice()
            Log.d(TAG, "Cleared communication device (using system default)")
            return true
        }

        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val target = devices.firstOrNull { it.id == deviceId }

        if (target == null) {
            Log.w(TAG, "Device with id $deviceId not found, clearing preference")
            audioManager.clearCommunicationDevice()
            return false
        }

        val success = audioManager.setCommunicationDevice(target)
        Log.d(TAG, "Set communication device to '${buildDeviceName(target)}' (id=$deviceId): $success")
        return success
    }

    fun clearPreferredDevice(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.clearCommunicationDevice()
        Log.d(TAG, "Cleared communication device")
    }

    fun getDeviceInfoById(context: Context, deviceId: Int): AudioDeviceInfo? {
        if (deviceId == 0) return null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == deviceId }
    }

    private fun buildDeviceName(device: AudioDeviceInfo): String {
        val productName = device.productName?.toString()?.takeIf { it.isNotBlank() && it != "0" }
        val typeName = typeToString(device.type)

        return when {
            productName != null -> "$productName ($typeName)"
            else -> typeName
        }
    }

    private fun typeToString(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
        else -> "Audio Device (type=$type)"
    }
}
