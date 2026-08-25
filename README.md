# Safir AI Humanoid

Persistent full-screen AI humanoid for Android.

## Product goal
- Avatar remains visible through IDLE / LISTENING / THINKING / SPEAKING / EMOTION.
- 30 HeyGen motion assets form a reusable motion library.
- ElevenLabs provides low-latency speech audio.
- Lip-sync is handled separately from body-motion selection.
- API keys stay on the backend / GitHub secrets, never in the APK.

## Repository layout
- `android/` Kotlin + Jetpack Compose client
- `backend/` orchestration boundary for LLM / ElevenLabs / HeyGen
- `motion-library/` motion manifest and source IDs
- `docs/` architecture and integration notes
- `.github/workflows/` CI builds

## First milestone
Build an installable Android debug APK that opens directly to a persistent avatar surface and can transition between motion states without replacing the screen.
