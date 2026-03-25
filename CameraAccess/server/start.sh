#!/bin/bash
# Export standard AI provider keys from Replit integrations
export ANTHROPIC_API_KEY="${AI_INTEGRATIONS_ANTHROPIC_API_KEY}"
export ANTHROPIC_BASE_URL="${AI_INTEGRATIONS_ANTHROPIC_BASE_URL}"
export OPENAI_API_KEY="${AI_INTEGRATIONS_OPENAI_API_KEY}"
export OPENAI_BASE_URL="${AI_INTEGRATIONS_OPENAI_BASE_URL}"
# NOTE: Do NOT export GEMINI_API_KEY from the integration dummy key.
# AI providers use AI_INTEGRATIONS_GEMINI_* (with proxy) for LLM calls.
# GEMINI_API_KEY (real key) is kept separately for the Android Gemini Live WebSocket proxy.

# Start OpenClaw gateway in background (port 18789)
cd "$(dirname "$0")/../../.."
node node_modules/openclaw/dist/index.js gateway \
  --port 18789 --allow-unconfigured \
  >/tmp/openclaw-gateway.log 2>&1 &
GATEWAY_PID=$!
echo "[Gateway] Started PID $GATEWAY_PID on port 18789"

# Start VisionClaw server
exec node samples/CameraAccess/server/index.js
