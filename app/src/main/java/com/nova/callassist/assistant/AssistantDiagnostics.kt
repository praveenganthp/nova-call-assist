package com.nova.callassist.assistant

/**
 * Real-time diagnostics model providing deep visibility into the speech recognition,
 * audio capture hardware, silence gating, and wake-word system.
 */
data class AssistantDiagnostics(
    val lastAudioDurationSec: Float = 0f,
    val speechOnsetDetected: Boolean = false,
    val currentRms: Double = 0.0,
    val ambientNoiseFloor: Double = 0.0,
    val dynamicSpeechThreshold: Double = 0.0,
    val lastInferenceDurationMs: Long = 0L,
    val lastRealTimeFactor: Float = 0f,
    val lastTranscript: String = "",
    val wakeWordTriggerCount: Int = 0,
    val lastWakeTimestamp: Long = 0L,
    val audioRecordStatus: String = "Idle",
    val lastError: String? = null
)
