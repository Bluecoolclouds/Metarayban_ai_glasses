# Smart Glasses — Android

Android app for Meta Ray-Ban smart glasses. Streams video from the glasses, talks to Gemini Live AI using the glasses mic, streams to Twitch — all in real time.

## Features

- Receives video stream from glasses camera (DAT SDK)
- Voice AI assistant via Gemini Live API — listens through the glasses mic
- Twitch streaming with glasses audio (RTMP, H.264/AAC)
- Browser POV viewer via WebRTC
- Voice recording via dictaphone
- Phone mode — same pipeline using the phone camera

## Requirements

- Android 14+ (API 34+)
- Android Studio Ladybug or newer
- GitHub token with `read:packages` scope (for Meta DAT SDK)
- Gemini API key

## Setup

### 1. GitHub Packages

The DAT SDK is distributed via GitHub Packages. Add to `local.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.token=YOUR_GITHUB_TOKEN
```

Get a token: [github.com/settings/tokens](https://github.com/settings/tokens) → New token → check `read:packages`.

With `gh` CLI:
```bash
gh auth token
# if missing the scope:
gh auth refresh -s read:packages
```

### 2. Secrets

```bash
cp app/src/main/java/.../cameraaccess/Secrets.kt.example \
   app/src/main/java/.../cameraaccess/Secrets.kt
```

Only `GEMINI_API_KEY` is required. Everything else (Twitch, WebRTC server) can be configured at runtime in the app Settings.

### 3. Run

File → Sync Project with Gradle Files → select device → Run (Shift+F10)

Wireless debugging: Developer Options → Wireless debugging → `adb pair <ip>:<port>`

## Enabling Developer Mode on the glasses

1. Open the **Meta AI** app
2. Settings (gear icon) → App Info
3. Tap the **version number 5 times** — Developer Mode unlocks
4. Go back to Settings → enable **Developer Mode**

## Code structure

```
app/src/main/java/.../cameraaccess/
├── gemini/
│   ├── AudioManager.kt           # PCM capture from glasses + playback
│   ├── GeminiLiveService.kt      # Gemini Live API WebSocket client
│   ├── GeminiSessionViewModel.kt
│   └── GeminiConfig.kt           # API key, model, system prompt
├── twitch/
│   ├── DirectRtmpEncoder.kt      # H.264 + AAC MediaCodec → RTMP
│   └── TwitchStreamManager.kt
├── stream/
│   ├── StreamViewModel.kt        # Frame pipeline, screen-off optimization
│   └── StreamUiState.kt
├── audio/
│   ├── AudioDeviceSelector.kt    # BLE/BT device discovery
│   └── MicLevelMonitor.kt        # Mic level indicator
├── dictaphone/
│   └── DictaphoneManager.kt      # Voice recording
├── service/
│   ├── GeminiForegroundService.kt
│   └── StreamingService.kt       # Wake lock when screen is locked
├── ui/
│   ├── StreamScreen.kt           # Main screen
│   └── StreamSettingsPanel.kt
├── settings/
│   └── SettingsManager.kt
└── webrtc/
    ├── WebRTCClient.kt
    └── WebRTCSessionViewModel.kt
```

## How glasses mic routing works (BLE)

Meta Ray-Ban is a BLE device (TYPE_BLE_HEADSET, type=26). Three steps are required to capture audio from it:

```kotlin
// 1. Switch audio mode
audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

// 2. Set BLE device as the communication device
audioManager.setCommunicationDevice(glassesDevice)

// 3. Bind the AudioRecord to the device
audioRecord.setPreferredDevice(glassesDevice)
```

**Critical:** any other active `AudioRecord` (e.g. `MicLevelMonitor`) will block `setCommunicationDevice`. The mic level monitor stops automatically when Gemini or Twitch is active.

## Troubleshooting

**Gradle 401** — invalid GitHub token, check `local.properties`

**Gemini can't hear glasses mic** — select glasses in the mic picker; confirm `setCommunicationDevice` returns `true` in logs

**Twitch no audio from glasses** — same: select glasses in mic picker before starting the stream

**Stream quality drops when screen is locked** — fixed in current version: JPEG pipeline is suspended on screen off, CPU is freed for the encoder
