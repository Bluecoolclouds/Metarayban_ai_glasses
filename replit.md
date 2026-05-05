# Smart Glasses

Android app for Meta Ray-Ban smart glasses — real-time AI assistant with Gemini Live API (vision + voice).

## Run & Operate

- **Web info page**: `node index.js` — runs on port 5000 (shows project overview in Replit preview)
- **Android app**: Build and run via Android Studio using the `CameraAccessAndroid/` directory
- **Required secrets**: `GEMINI_API_KEY` (get yours at https://aistudio.google.com/apikey)

## Stack

- **Android**: Kotlin, Jetpack Compose (Material 3), Android 14+
- **AI**: Google Gemini Live API (WebSockets, multimodal voice + vision)
- **Streaming**: RTMP via RootEncoder (Twitch), WebRTC via stream-webrtc-android (browser POV)
- **SDK**: Meta DAT Android SDK (mwdat-core, mwdat-camera, mwdat-mockdevice)
- **Networking**: OkHttp, Gson
- **Web server**: Node.js 20 (info page only)

## Where things live

- `CameraAccessAndroid/` — Android app source
  - `app/src/main/java/.../gemini/` — Gemini Live WebSocket client & audio routing
  - `app/src/main/java/.../twitch/` — RTMP streaming to Twitch
  - `app/src/main/java/.../webrtc/` — WebRTC signaling & POV streaming
  - `app/src/main/java/.../skills/` — AI skill modules (translator, calorie, cooking, etc.)
  - `app/src/main/java/.../ui/` — Jetpack Compose screens
  - `app/build.gradle.kts` — Android build config & dependencies
- `CameraAccessAndroid/app/src/main/java/.../Secrets.kt.example` — API key template (copy to `Secrets.kt`)
- `index.js` — Node.js project info page
- `docs/` — Documentation assets (screenshots)

## Architecture decisions

- **Secrets.kt is gitignored**: API keys live in `Secrets.kt` (copied from `Secrets.kt.example`); in Replit, store `GEMINI_API_KEY` as a secret
- **Foreground services**: `StreamingService` and `GeminiForegroundService` keep the pipeline alive when the screen locks
- **WebRTC signaling**: Designed to run on a Replit-hosted server; set `webrtcSignalingURL` in Secrets.kt to your Replit domain
- **Meta DAT SDK**: Fetched from GitHub Packages — requires `gpr.user` and `gpr.token` in `local.properties` for local builds

## Product

- Real-time AI conversation via Gemini Live (voice + camera vision from glasses)
- Twitch RTMP streaming direct from glasses mic/camera
- Browser-based POV viewing via WebRTC
- Phone-only fallback mode (no glasses required)
- AI skill modules: translator, calorie counter, cooking mode, scene narrator, dictaphone

## User preferences

_Populate as you build_

## Gotchas

- `Secrets.kt` must exist before building the Android app — copy from `Secrets.kt.example` and fill in values
- GitHub Packages token (`gpr.token`) is required in `local.properties` to resolve the Meta DAT SDK during Gradle sync
- The Replit web preview shows the Node.js info page only — the actual Android app runs on a physical device or emulator

## Pointers

- Gemini API keys: https://aistudio.google.com/apikey
- Meta DAT SDK docs: see `CameraAccessAndroid/README.md`
- Environment secrets skill: `.local/skills/environment-secrets/SKILL.md`
