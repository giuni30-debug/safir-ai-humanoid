package com.safir.ai.humanoid

/**
 * Provider-neutral bridge between streamed TTS audio and the avatar state machine.
 *
 * ElevenLabs' realtime WebSocket returns base64 audio chunks and an `is_final`
 * marker. This coordinator deliberately reacts to received audio, not guessed
 * timers: the first non-empty audio chunk starts SPEAKING; final completion ends
 * the voice turn; interruption is explicit and idempotent.
 */
class TtsStreamCoordinator(
    private val behavior: SpeechBehavior,
    private val onDecision: (VoiceSyncDecision) -> Unit,
) {
    private var firstAudioSeen = false
    private var terminal = false

    fun onTurnStarted() {
        if (terminal) return
        onDecision(VoiceSyncEngine.decide(VoiceSyncEvent.TURN_STARTED, behavior))
    }

    fun onAudioChunk(base64Audio: String?) {
        if (terminal || base64Audio.isNullOrBlank()) return

        if (!firstAudioSeen) {
            firstAudioSeen = true
            onDecision(VoiceSyncEngine.decide(VoiceSyncEvent.FIRST_AUDIO_FRAME, behavior))
        }
    }

    fun onFinalFrame() {
        if (terminal) return
        terminal = true
        onDecision(VoiceSyncEngine.decide(VoiceSyncEvent.AUDIO_COMPLETED, behavior))
    }

    fun interrupt() {
        if (terminal) return
        terminal = true
        onDecision(VoiceSyncEngine.decide(VoiceSyncEvent.INTERRUPTED, behavior))
    }
}
