# Safir AI backend

This directory will contain the orchestration service for:

- LLM streaming and behavior metadata
- ElevenLabs TTS streaming
- HeyGen / LiveAvatar session integration when required
- speech cancellation / barge-in
- authentication and rate limiting
- temporary client tokens

Provider API keys must remain in backend environment variables or deployment secrets and must never be committed or embedded in the Android APK.
