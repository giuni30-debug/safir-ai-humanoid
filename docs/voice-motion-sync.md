# Safir AI Humanoid — Voice, Lip-Sync and Motion Synchronization

Status: architecture research note, 2026-08-25.

## Goal

Safir must respond conversationally while keeping the humanoid visually continuous. Audio timing is the source of truth for speech start/end. Body motion is selected semantically from the 30-motion library; facial/lip synchronization must follow the actual rendered audio rather than guessed delays.

## Verified platform facts

### ElevenLabs realtime TTS

Official documentation:
- https://elevenlabs.io/docs/eleven-api/guides/how-to/websockets/realtime-tts
- https://elevenlabs.io/docs/api-reference/text-to-speech/v-1-text-to-speech-voice-id-stream-input
- https://elevenlabs.io/docs/developer-guides/reducing-latency

Relevant facts:
- The TTS WebSocket is designed for partial text input and realtime audio generation.
- The endpoint can return alignment data with character start times and durations; this can be used to align animation with the generated speech.
- ElevenLabs recommends `eleven_flash_v2_5` when latency is important.
- For a realtime conversation turn, ElevenLabs recommends `flush: true` at the end of the turn.
- The classic realtime TTS WebSocket does not support `eleven_v3`; v3 has a separate Text-to-Dialogue WebSocket.
- Streaming LLM output into the TTS WebSocket reduces perceived response latency because speech can begin before the entire LLM answer is complete.

Engineering rule: do not use fixed timers to decide when the avatar begins or stops speaking. Use actual audio/player events and alignment metadata.

### Android Media3 / ExoPlayer

Official documentation:
- https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager

Relevant facts:
- `DefaultPreloadManager` is intended to preload media before playback so the next item can start faster.
- This is appropriate for Safir's small motion library because likely next states/motions can be kept warm before a transition.

Engineering rule: keep the player surface persistent, preload likely next motions, and swap media without rebuilding the visual surface.

### HeyGen / LiveAvatar realtime architecture

Official documentation:
- https://docs.liveavatar.com/
- https://help.heygen.com/en/articles/12758516-introducing-liveavatar
- https://help.heygen.com/en/articles/12758866-liveavatar-faq

Relevant facts:
- LiveAvatar is HeyGen's realtime avatar platform and supports synchronized speech/video.
- FULL mode can manage ASR/LLM/TTS/WebRTC.
- LITE mode is intended for teams that bring their own conversational stack; LiveAvatar handles realtime video while the integrator provides ASR, LLM and TTS.
- The official FAQ states that LiveAvatar gestures cannot currently be controlled dynamically during a live stream. Gestures are driven by the avatar source recording.

Critical Safir consequence: our exact 30-body-motion semantic engine cannot be assumed to map 1:1 onto dynamic LiveAvatar gestures. That would be an unsupported assumption.

### HeyGen lipsync jobs

The connected HeyGen API exposes lipsync jobs that replace audio on an existing video and re-animate lip movement. The API accepts a source video and replacement audio and supports `speed` or `precision` modes.

Engineering consequence: this is useful for high-quality lip-sync rendering and validation, but because it is a job workflow it must not be assumed to satisfy Safir's realtime conversational latency until measured in a prototype.

## Recommended Safir architecture

1. User audio -> STT.
2. LLM streams response text plus behavior metadata, e.g. state/emotion/energy/gesture.
3. ElevenLabs TTS WebSocket receives streamed text.
4. Audio chunks are played immediately; alignment metadata is recorded.
5. `SPEAKING` begins from the actual first playable audio event, not from LLM completion.
6. MotionEngine selects a body motion from IDs 17-24 using semantic metadata.
7. Gesture changes occur only at natural phrase/semantic boundaries, not per word.
8. Lip/facial synchronization follows the actual audio path. We will choose the final HeyGen realtime method only after a measured POC confirms latency and interruption behavior.
9. On barge-in: cancel TTS/audio immediately -> enter LISTENING -> stop speaking gesture cleanly.
10. On natural audio completion: play `30_TRANSITION_SPEAK_TO_IDLE` -> return to IDLE.

## Motion policy during speech

- 17 `SPEAK_CALM_OPEN_HAND`: neutral/calming answer start.
- 18 `SPEAK_EXPLAIN_ONE_HAND`: explanation, moderate energy.
- 19 `SPEAK_EXPLAIN_TWO_HANDS`: broader explanation / comparison.
- 20 `SPEAK_CONFIDENT`: confident conclusion or recommendation.
- 21 `SPEAK_AFFIRM`: agreement / confirmation.
- 22 `SPEAK_EMPHASIS`: important phrase or key warning.
- 23 `SPEAK_WARM`: supportive/warm response.
- 24 `SPEAK_SERIOUS`: serious/careful response.
- 30 `TRANSITION_SPEAK_TO_IDLE`: mandatory clean return after normal completion.

## Acceptance tests before calling voice+avatar production-ready

- Measure user speech end -> first audible Safir speech.
- Measure first audio -> first visible speaking motion.
- Verify audio remains authoritative when the LLM is still streaming.
- Verify no black frame or avatar disappearance during IDLE/LISTENING/THINKING/SPEAKING transitions.
- Verify barge-in cancels audible speech and exits SPEAKING immediately.
- Verify motion changes occur at controlled semantic boundaries.
- Verify final audio completion triggers transition 30 and then IDLE.
- Compare HeyGen realtime path vs HeyGen lipsync-job POC with measured latency; choose based on measured behavior, not assumption.
- Keep all API keys on backend only.

## Current decision

Use ElevenLabs realtime streaming and alignment as the speech timing backbone. Keep our 30-motion body engine separate from HeyGen LiveAvatar gesture control because HeyGen's current official FAQ does not expose dynamic gesture control. Treat LiveAvatar LITE and HeyGen lipsync as candidates for the facial/realtime video layer, and select the final route only after a measured POC.
