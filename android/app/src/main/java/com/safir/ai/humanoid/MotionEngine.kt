package com.safir.ai.humanoid

data class MotionSpec(
    val id: String,
    val state: AvatarState,
    val loopable: Boolean,
    val transitionGroup: String,
)

object MotionEngine {
    private val idle = listOf(
        MotionSpec("01_IDLE_BREATH_SOFT", AvatarState.IDLE, true, "neutral"),
        MotionSpec("02_IDLE_WEIGHT_SHIFT", AvatarState.IDLE, true, "neutral"),
        MotionSpec("03_IDLE_HEAD_TILT", AvatarState.IDLE, true, "neutral"),
        MotionSpec("04_IDLE_EYE_SCAN", AvatarState.IDLE, true, "neutral"),
        MotionSpec("05_IDLE_SHOULDER_RESET", AvatarState.IDLE, true, "neutral"),
        MotionSpec("06_IDLE_MICRO_LEAN", AvatarState.IDLE, true, "neutral"),
    )

    private val listening = listOf(
        MotionSpec("07_LISTEN_FOCUS", AvatarState.LISTENING, true, "neutral"),
        MotionSpec("08_LISTEN_NOD_SINGLE", AvatarState.LISTENING, false, "neutral"),
        MotionSpec("09_LISTEN_NOD_DOUBLE", AvatarState.LISTENING, false, "neutral"),
        MotionSpec("10_LISTEN_LEAN_FORWARD", AvatarState.LISTENING, false, "neutral"),
        MotionSpec("11_LISTEN_HAND_READY", AvatarState.LISTENING, false, "neutral"),
    )

    private val thinking = listOf(
        MotionSpec("12_THINK_LOOK_SIDE", AvatarState.THINKING, false, "neutral"),
        MotionSpec("13_THINK_CHIN_TOUCH", AvatarState.THINKING, false, "neutral"),
        MotionSpec("14_THINK_EYES_UP", AvatarState.THINKING, false, "neutral"),
        MotionSpec("15_THINK_STILL_PROCESSING", AvatarState.THINKING, true, "neutral"),
        MotionSpec("16_THINK_RETURN", AvatarState.THINKING, false, "neutral"),
    )

    private val speaking = listOf(
        MotionSpec("17_SPEAK_CALM_OPEN_HAND", AvatarState.SPEAKING, false, "speak"),
        MotionSpec("18_SPEAK_EXPLAIN_ONE_HAND", AvatarState.SPEAKING, false, "speak"),
        MotionSpec("19_SPEAK_EXPLAIN_TWO_HANDS", AvatarState.SPEAKING, false, "speak"),
        MotionSpec("20_SPEAK_CONFIDENT", AvatarState.SPEAKING, false, "speak"),
        MotionSpec("21_SPEAK_AFFIRM", AvatarState.SPEAKING, false, "speak"),
        MotionSpec("22_SPEAK_EMPHASIS", AvatarState.SPEAKING, false, "speak"),
        MotionSpec("23_SPEAK_WARM", AvatarState.SPEAKING, false, "speak"),
        MotionSpec("24_SPEAK_SERIOUS", AvatarState.SPEAKING, false, "speak"),
    )

    private val emotion = listOf(
        MotionSpec("25_EMOTION_SMILE", AvatarState.EMOTION, false, "neutral"),
        MotionSpec("26_EMOTION_LAUGH_SOFT", AvatarState.EMOTION, false, "neutral"),
        MotionSpec("27_EMOTION_SURPRISED", AvatarState.EMOTION, false, "neutral"),
        MotionSpec("28_EMOTION_CURIOUS", AvatarState.EMOTION, false, "neutral"),
        MotionSpec("29_EMOTION_EMPATHETIC", AvatarState.EMOTION, false, "neutral"),
    )

    val speakToIdle = MotionSpec("30_TRANSITION_SPEAK_TO_IDLE", AvatarState.IDLE, false, "bridge")

    fun primaryFor(state: AvatarState): MotionSpec = when (state) {
        AvatarState.IDLE -> idle.first()
        AvatarState.LISTENING -> listening.first()
        AvatarState.THINKING -> thinking[3]
        AvatarState.SPEAKING -> speaking.first()
        AvatarState.EMOTION -> emotion.first()
    }

    fun all(): List<MotionSpec> = idle + listening + thinking + speaking + emotion + speakToIdle
}
