# Smart Glasses

Real-time AI assistant for Meta Ray-Ban smart glasses. Sees what you see, hears you through the glasses mic, and talks back — all in real time.

**Platform:** Android (primary) + iOS  
**Glasses:** Meta Ray-Ban (video via DAT SDK)  
**AI:** Gemini Live API — native audio, no STT latency  

<p align="center">
  <img src="docs/screenshot.jpg" alt="Smart Glasses App" width="320">
</p>

---

## Features

- **Voice AI assistant** — Gemini Live sees through the glasses camera and hears through the glasses mic. Responds natively without STT round-trips
- **Twitch streaming** — direct RTMP from glasses with audio from the glasses mic, no extra devices needed
- **Telegram bot** — text, voice (transcription + AI), photos, documents, archives; responses via Claude AI
- **Memory & reminders** — PostgreSQL-backed, personal context persists between sessions
- **WebRTC viewer** — browser-based live POV viewer from the glasses
- **Phone mode** — full pipeline testing without glasses, using the phone camera

---

## How It Works

```
Meta Ray-Ban Glasses
       |
       | video (~24fps) + audio from glasses mic
       v
Android App
       |
       |--- JPEG frames (~1fps) + PCM 16kHz ---> Gemini Live API
       |--- raw I420 frames -----------------> H.264 encoder --> RTMP --> Twitch
       |--- bitmap frames -------------------> WebRTC --> Browser viewer
       |
Gemini Live API (WebSocket)
       |
       |--- PCM audio 24kHz --> Phone speaker
       |--- Tool calls ------> PostgreSQL (memory, reminders)
```

**Key components:**
- **Gemini Live** — real-time voice + vision over WebSocket. Native audio, not STT-first
- **BLE audio routing** — three required steps for glasses mic: `MODE_IN_COMMUNICATION` + `setCommunicationDevice()` + `AudioRecord.setPreferredDevice()`
- **Screen-off optimization** — when the screen is locked, the JPEG pipeline is skipped; all CPU goes to the H.264 encoder
- **Node.js server** — WebRTC signaling, Telegram bot, AI API, PostgreSQL-backed memory

---

## Quick Start (Android)

### 1. Clone and open

```bash
git clone <repo-url>
```

Open `CameraAccessAndroid/` in Android Studio.

### 2. GitHub Packages (DAT SDK)

The Meta DAT Android SDK is distributed via GitHub Packages. You need a token with `read:packages` scope.

In `CameraAccessAndroid/local.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.token=YOUR_GITHUB_TOKEN
```

> If you have the `gh` CLI: `gh auth token`. If the token lacks the right scope: `gh auth refresh -s read:packages`

### 3. App secrets

```bash
cd CameraAccessAndroid/app/src/main/java/.../cameraaccess/
cp Secrets.kt.example Secrets.kt
```

Fill in `Secrets.kt`. Only `GEMINI_API_KEY` is required. Everything else can be configured in the app Settings at runtime.

### 4. Build and run

Gradle sync in Android Studio → select device → Run (Shift+F10).

> Wireless debugging: Developer Options → Wireless debugging → `adb pair <ip>:<port>`

### 5. First use

**Without glasses (Phone mode):**
1. Tap **"Start on Phone"** — uses the phone's back camera
2. Tap the AI button (sparkle icon) to start a Gemini Live session
3. Talk to the AI — it sees through the phone camera

**With Meta Ray-Ban glasses:**

Enable Developer Mode in the Meta AI app:
1. Open the **Meta AI** app
2. Settings (gear icon) → App Info
3. Tap the **app version number 5 times** — Developer Mode unlocks
4. Go back to Settings → toggle **Developer Mode** on

Then in the app:
1. Tap **"Start Streaming"**
2. Tap the AI button for voice + vision conversation

---

## Server (Node.js)

The server lives in `server/` and starts automatically.

Provides:
- WebRTC signaling (ICE, SDP exchange)
- Telegram bot (text, voice, photos, documents)
- REST API for AI providers (Gemini, Claude, OpenAI)
- PostgreSQL-backed memory and reminders

### Environment variables

| Variable | Purpose |
|----------|---------|
| `DATABASE_URL` | PostgreSQL connection (auto on Replit) |
| `TELEGRAM_BOT_TOKEN` | Token from @BotFather |
| `GEMINI_API_KEY` | Google AI Studio |
| `ANTHROPIC_API_KEY` | Claude (for Telegram bot) |
| `OPENAI_API_KEY` | OpenAI (optional) |

---

## Architecture (Android)

All source in `CameraAccessAndroid/app/src/main/java/.../cameraaccess/`:

| File | Purpose |
|------|---------|
| `gemini/AudioManager.kt` | AudioRecord with BLE routing: MODE_IN_COMMUNICATION + setCommunicationDevice + setPreferredDevice |
| `gemini/GeminiLiveService.kt` | OkHttp WebSocket client for Gemini Live API |
| `gemini/GeminiSessionViewModel.kt` | Session lifecycle, tool calls, UI state |
| `gemini/GeminiConfig.kt` | API key, model, system prompt |
| `twitch/DirectRtmpEncoder.kt` | H.264 MediaCodec + AAC + RTMP with BLE mic support |
| `twitch/TwitchStreamManager.kt` | Twitch stream management, reconnect, bitrate measurement |
| `stream/StreamViewModel.kt` | Frame pipeline, screen-off optimization, foreground service |
| `audio/AudioDeviceSelector.kt` | Audio device selection with BLE device support |
| `audio/MicLevelMonitor.kt` | Mic level indicator, bound to the selected device |
| `dictaphone/DictaphoneManager.kt` | Voice recording via glasses mic |
| `settings/SettingsManager.kt` | SharedPreferences with Secrets.kt fallback |
| `service/GeminiForegroundService.kt` | Keeps Gemini alive when screen is locked |
| `service/StreamingService.kt` | Wake lock + foreground service for streaming |
| `ui/StreamScreen.kt` | Main screen: Gemini + Twitch + WebRTC + mic picker |

### Audio pipeline

```
Glasses mic (BLE, TYPE_BLE_HEADSET, type=26)
   |
   | 1. AudioManager.mode = MODE_IN_COMMUNICATION
   | 2. setCommunicationDevice(glassesDevice)
   | 3. AudioRecord.setPreferredDevice(glassesDevice)
   v
AudioRecord (VOICE_COMMUNICATION, 16kHz mono, 100ms chunks)
   |
   |--- PCM Int16 --> Gemini WebSocket --> Gemini Live API
   |--- PCM Int16 --> AAC encoder --> RTMP --> Twitch
```

**Important:** `MicLevelMonitor` is stopped whenever Gemini or Twitch is active — otherwise its competing `AudioRecord` (VOICE_RECOGNITION) blocks `setCommunicationDevice` from routing to the glasses.

### Video pipeline

```
DAT SDK video stream (24fps, I420)
   |
   |--- raw I420 --> DirectRtmpEncoder --> H.264 --> RTMP --> Twitch (lossless)
   |
   | (only when screen is on OR Gemini/WebRTC is active)
   |--- I420 → NV21 → JPEG 75% → Bitmap
         |--- throttled ~1fps --> Gemini Live (visual context)
         |--- every frame -----> WebRTC (browser viewer)
         |--- UI display
```

**Screen-off optimization:** when the screen is locked, a `BroadcastReceiver(ACTION_SCREEN_OFF)` skips the JPEG pipeline entirely, freeing CPU for the H.264 encoder.

---

## Database

PostgreSQL with four tables:

| Table | Purpose |
|-------|---------|
| `user_memories` | Telegram bot personal memories |
| `reminders` | Reminders with trigger timestamps |
| `ai_settings` | AI provider configuration |
| `token_usage` | Token usage statistics |

---

## Requirements

- Android 14+ (API 34+)
- Android Studio Ladybug or newer
- GitHub token with `read:packages` scope (for DAT SDK)
- Gemini API key — [get one free](https://aistudio.google.com/apikey)
- Meta Ray-Ban glasses (optional — Phone mode works without them)

---

## Troubleshooting

**Gemini can't hear me through the glasses** — make sure the glasses are selected in the mic picker on the stream screen. Check logs for `setCommunicationDevice ... : true`.

**Twitch stream has no audio from glasses** — same fix: select the glasses as mic before starting the stream. `MicLevelMonitor` should stop automatically when the stream starts.

**Stream quality degrades when screen is locked** — fixed in the current version. `BroadcastReceiver` automatically frees CPU on screen lock.

**Gradle sync 401 Unauthorized** — GitHub token is missing or lacks `read:packages`. Check `local.properties` for `gpr.user` and `gpr.token`. Get a new token at [github.com/settings/tokens](https://github.com/settings/tokens).

**Telegram bot not responding** — check `TELEGRAM_BOT_TOKEN` in environment variables. The bot starts automatically with the server.
