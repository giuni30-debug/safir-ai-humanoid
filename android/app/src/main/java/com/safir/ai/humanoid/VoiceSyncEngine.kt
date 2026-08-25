package com.safir.ai.humanoid

data class SpeechBehavior(
    val emotion: String? = null,
    val energy: Double = 0.5,
    val gesture: String? = null,
)

enum class VoiceSyncEvent {
    TURN_STARTED,
    FIRST_AUDIO_FRAME,
    AUDIO_COMPLETED,
    INTERRUPTED,
}

data class VoiceSyncDecision(
    val state: AvatarState,
    val motion: MotionSpec,
)

object VoiceSyncEngine {
    fun decide(
        event: VoiceSyncEvent,
        behavior: SpeechBehavior = SpeechBehavior(),
    ): VoiceSyncDecision = when (event) {
        VoiceSyncEvent.TURN_STARTED -> VoiceSyncDecision(
            state = AvatarState.THINKING,
            motion = MotionEngine.primaryFor(AvatarState.THINKING),
        )

        VoiceSyncEvent.FIRST_AUDIO_FRAME -> VoiceSyncDecision(
            state = AvatarState.SPEAKING,
            motion = selectSpeakingMotion(behavior),
        )

        VoiceSyncEvent.AUDIO_COMPLETED -> VoiceSyncDecision(
            state = AvatarState.IDLE,
            motion = MotionEngine.speakToIdle,
        )

        VoiceSyncEvent.INTERRUPTED -> VoiceSyncDecision(
            state = AvatarState.LISTENING,
            motion = MotionEngine.primaryFor(AvatarState.LISTENING),
        )
    }

    private fun selectSpeakingMotion(behavior: SpeechBehavior): MotionSpec {
        val gesture = behavior.gesture?.lowercase().orEmpty()
        val emotion = behavior.emotion?.lowercase().orEmpty()

        val id = when {
            "emphasis" in gesture || behavior.energy >= 0.85 -> "22_SPEAK_EMPHASIS"
            "affirm" in gesture -> "21_SPEAK_AFFIRM"
            "two" in gesture || "compare" in gesture -> "19_SPEAK_EXPLAIN_TWO_HANDS"
            "explain" in gesture -> "18_SPEAK_EXPLAIN_ONE_HAND"
            "confident" in emotion || "confident" in gesture -> "20_SPEAK_CONFIDENT"
            "warm" in emotion || "empathetic" in emotion -> "23_SPEAK_WARM"
            "serious" in emotion -> "24_SPEAK_SERIOUS"
            else -> "17_SPEAK_CALM_OPEN_HAND"
        }

        return MotionEngine.all().first { it.id == id }
    }
}
