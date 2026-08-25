# Safir AI Humanoid architecture

## Runtime pipeline

1. Android keeps the avatar surface permanently visible.
2. Microphone input moves the state machine to `LISTENING`.
3. Speech is transcribed and sent to the LLM through the backend.
4. The LLM returns response text plus behavior metadata such as emotion, energy and gesture.
5. ElevenLabs generates speech as a low-latency audio stream.
6. The motion engine chooses a compatible HeyGen body-motion asset.
7. Lip-sync is a separate visual layer/runtime concern so body-motion selection is not coupled to TTS.
8. When playback completes, the avatar transitions back to `IDLE`; barge-in cancels current speech and returns immediately to `LISTENING`.

## Why this split

The 30 HeyGen clips are treated as a behavior library, not as complete talking videos. This lets the app reuse consistent body motion while speech and facial synchronization evolve independently.

## Android playback strategy

Use AndroidX Media3/ExoPlayer for video playback. Preload the likely next state motions so a transition can begin without showing a blank frame. Keep one persistent Compose screen and swap media sources inside the avatar player instead of navigating between screens.

## Voice strategy

ElevenLabs supports streaming TTS and WebSocket TTS. For an LLM that emits text incrementally, the WebSocket form can accept partial text and return partial audio chunks. For a complete reply available up front, HTTP streaming can be simpler.

## Security boundary

No provider API key is stored in the APK. Android authenticates to the Safir backend. The backend owns provider credentials, session creation, rate limits, cancellation and provider-specific APIs.

## Motion selection contract

Suggested model output:

```json
{
  "state": "SPEAKING",
  "emotion": "confident",
  "energy": 0.65,
  "gesture": "open_hand"
}
```

The motion engine maps this metadata to one of the registered motion IDs and applies cooldown rules to avoid repetition.
