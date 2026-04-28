# Changelog

## [Current] — 2026-03-12

### Fixed

- **Stream quality when screen is locked** — added `BroadcastReceiver(ACTION_SCREEN_OFF/ON)` to `StreamViewModel`. When the screen is off, the JPEG pipeline is skipped entirely and all CPU is available for the H.264 encoder. Twitch stream quality no longer degrades when the phone is locked
- **Mic level bar now reflects the selected microphone** — `MicLevelMonitor` accepts `AudioDeviceInfo?` and calls `setPreferredDevice()`. Automatically restarts with the new device when the mic selection changes

### Improved

- JPEG quality for Gemini/WebRTC raised from 50 to 75
- Stream settings panel: removed the close (×) button from the header; apply-bitrate button is now a small icon with no text label

---

## 2026-03-11

### Fixed

- **Twitch stream: audio from glasses mic** — `DirectRtmpEncoder.startAudioCapture()` now applies the same three-step BLE routing as Gemini: `MODE_IN_COMMUNICATION` for all BT types (including BLE), `setCommunicationDevice`, `AudioRecord.setPreferredDevice`. Added `BT_ALL_TYPES` with `TYPE_BLE_HEADSET/SPEAKER/BROADCAST`

---

## 2026-03-10

### Fixed

- **Gemini: audio from glasses mic** — `MicLevelMonitor` was holding an open `AudioRecord` with `VOICE_RECOGNITION` on the phone mic, blocking `setCommunicationDevice` from routing to the glasses. Fixed by adding `geminiUiState.isGeminiActive` as a key in the `LaunchedEffect` — the monitor now stops when Gemini is active (same behavior already applied for Twitch)

---

## 2026-03-09

### Added

- BLE mic support in `AudioManager.kt` (Gemini): added `TYPE_BLE_HEADSET` to `BT_ALL_TYPES`, added `AudioRecord.setPreferredDevice()` call, `MODE_IN_COMMUNICATION` set for all BT device types
- BLE mic support in `DictaphoneManager.kt`: same fixes
- `MicLevelMonitor` stops when Twitch streaming starts, freeing the `AudioRecord` for correct BT routing

---

## 2026-03-06

### Added

- PostgreSQL database: tables `user_memories`, `reminders`, `ai_settings`, `token_usage`
- Server (`samples/CameraAccess/server/index.js`) running on Replit
- npm dependencies installed

---

## DAT SDK history

Changes from the upstream Meta DAT SDK:

### DAT SDK v0.4.0 — 2026-02-03

- Meta Ray-Ban Display glasses support
- `hingesClosed` in `StreamSessionError`
- `UnregistrationError`, `networkUnavailable` in `RegistrationError`
- Fixed: streaming status when switching between devices

### DAT SDK v0.3.0 — 2025-12-16

- App can run in background without interrupting the stream
- Fixed: streaming status when permission is not granted

### DAT SDK v0.2.1 — 2025-12-04

- Raw `CMSampleBuffer` added to `VideoFrame`
- Streaming continues when app goes to background

### DAT SDK v0.2.0 — 2025-11-18

- SDK split into independent components
- Updated Permission API
- Adaptive Bit Rate for streaming

### DAT SDK v0.1.0 — 2025-10-30

- First release of the Wearables Device Access Toolkit
