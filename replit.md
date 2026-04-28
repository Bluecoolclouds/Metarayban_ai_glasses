# VisionClaw

A WebRTC signaling server and web viewer for Meta Ray-Ban Smart Glasses POV streaming, with AI assistant integration.

## Project Structure

```
CameraAccess/
├── CameraAccess/          # iOS app (Swift/SwiftUI + Meta DAT SDK)
├── CameraAccessTests/     # iOS tests
├── CameraAccess.xcodeproj # Xcode project
└── server/                # Node.js signaling + AI server
    ├── index.js           # Main server (WebRTC signaling, HTTP, WebSocket)
    ├── ai-providers.js    # Multi-provider AI (Anthropic, OpenAI, Gemini)
    ├── telegram-bot.js    # Telegram bot integration
    ├── twitch-restreamer.js # Twitch RTMP streaming
    ├── memory.js          # Persistent memory
    ├── avito.js           # Avito scraping
    ├── skills/            # AI skill modules
    ├── public/            # Web frontend (index.html, settings.html, terminal.html)
    └── package.json

CameraAccessAndroid/       # Android app (Kotlin/Jetpack Compose + Meta DAT SDK)
```

## Tech Stack

- **Backend**: Node.js, WebSocket (ws), HTTP server
- **Frontend**: Vanilla HTML/CSS/JS (served from `server/public/`)
- **AI**: Anthropic Claude, OpenAI, Google Gemini
- **Streaming**: WebRTC (peer-to-peer), RTMP (Twitch)
- **Mobile**: iOS (Swift), Android (Kotlin)

## Running the Server

```bash
cd CameraAccess/server
node index.js
```

Server runs on port 5000 (`0.0.0.0`).

## Environment Variables

- `PORT` - Server port (default: 5000)
- `ANTHROPIC_API_KEY` - Anthropic Claude API key
- `OPENAI_API_KEY` - OpenAI API key
- `TELEGRAM_BOT_TOKEN` - Telegram bot token
- `TURN_SERVER` - Custom TURN server hostname
- `TURN_USERNAME` / `TURN_CREDENTIAL` - TURN auth credentials
- `GEMINI_API_KEY` - Real Gemini API key (for Android Gemini Live proxy)

## Workflow

- **Start application**: `cd CameraAccess/server && node index.js` on port 5000
