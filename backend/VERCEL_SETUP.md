# Safir AI Humanoid Backend — Vercel setup

Project name: `safir-ai-humanoid-backend`

Repository: `giuni30-debug/safir-ai-humanoid`

Root Directory in Vercel: `backend`

Required environment variable:

- `ELEVENLABS_API_KEY` — encrypted secret, add to Production and Preview.

Do not add the key to GitHub, Android source, build.gradle, strings.xml, local logs, screenshots, or chat.

After import, verify:

1. `/api/health` returns `status: "ok"`.
2. `elevenLabsConfigured` becomes `true` only after the secret is added and the deployment is redeployed.
3. `/api/voice` is the realtime WebSocket gateway used by the Android client.

The backend owns the ElevenLabs credential and opens the authenticated provider WebSocket. The Android app must connect only to the Safir backend.
