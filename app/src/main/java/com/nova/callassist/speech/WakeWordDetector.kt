package com.nova.callassist.speech

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keyword-spotting detector for "Hey Nova".
 *
 * Implements low-power, privacy-preserving on-device wake-word detection:
 * 1. Driven by [AudioCaptureCoordinator] to prevent microphone contention.
 * 2. Uses adaptive VAD / Voice Activity Energy gating so NO neural inference runs during silence (0% CPU).
 * 3. Only when human speech energy is detected, extracts a candidate utterance window (1.0–1.8s)
 *    and runs targeted Whisper transcription primed with the wake phrase prompt.
 * 4. Triggers [onWakeWordDetected] with debouncing and TTS feedback suppression.
 */
class WakeWordDetector(
    private val engine: SpeechRecognitionEngine,
    private val audioCaptureCoordinator: AudioCaptureCoordinator,
    private val isTtsSpeakingProvider: () -> Boolean = { false },
    private val onWakeWordDetected: () -> Unit
) {

    companion object {
        private const val TAG = "WakeWordDetector"
        const val SAMPLE_RATE = 16000
        const val DEBOUNCE_COOLDOWN_MS = 2500L
        const val MIN_UTTERANCE_MS = 300L
        const val MAX_UTTERANCE_MS = 2000L
        const val WAKE_PROMPT = "Hey Nova, Nova, Hi Nova, Hello Nova, Okay Nova."

        val WAKE_PHRASES = listOf(
            "hey nova",
            "nova",
            "hi nova",
            "hello nova",
            "ok nova",
            "okay nova",
            "hey noah",
            "noah",
            "hey nora",
            "nora"
        )

        /**
         * Normalizes transcript and tests against known wake-word phrase variants.
         */
        fun matchesWakeWord(transcript: String): Boolean {
            val clean = transcript.lowercase().trim()
                .replace(Regex("[^a-z\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (clean.isEmpty()) return false

            return WAKE_PHRASES.any { phrase ->
                clean == phrase || clean.startsWith("$phrase ") || clean.endsWith(" $phrase") || clean.contains(" $phrase ")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isSpotting = AtomicBoolean(false)
    private var lastWakeTime: Long = 0L

    // Utterance accumulation state
    private val preRollBuffer = PreRollBuffer(capacity = (SAMPLE_RATE * 0.35f).toInt()) // 350ms pre-roll
    private val currentCandidateSamples = ArrayList<Short>(SAMPLE_RATE * 2)
    private var voiceActive = false
    private var voiceStartTime = 0L
    private var lastVoiceTime = 0L
    private var isTranscribingCandidate = AtomicBoolean(false)

    fun startSpotting() {
        if (isSpotting.get()) return
        if (!engine.isInitialized) {
            Log.w(TAG, "Cannot start wake-word spotting: Whisper engine is not initialized.")
            return
        }

        isSpotting.set(true)
        resetUtteranceState()

        audioCaptureCoordinator.startWakeWordMode { chunk, readSize, rms, noiseFloor ->
            onAudioFrame(chunk, readSize, rms, noiseFloor)
        }
        Log.i(TAG, "Wake-word spotting active for 'Hey Nova' (Voice-Gated)")
    }

    private fun onAudioFrame(chunk: ShortArray, readSize: Int, rms: Double, noiseFloor: Double) {
        if (!isSpotting.get()) return

        // Always feed pre-roll ring buffer
        preRollBuffer.write(chunk, readSize)

        // If TTS is currently speaking, suppress wake word detection so the assistant does not trigger itself
        if (isTtsSpeakingProvider()) {
            if (voiceActive) resetUtteranceState()
            return
        }

        val dynamicThreshold = (noiseFloor * 2.0 + 100.0).coerceIn(350.0, 1600.0)
        val continuationThreshold = dynamicThreshold * 0.70
        val now = SystemClock.elapsedRealtime()

        if (rms > dynamicThreshold) {
            if (!voiceActive) {
                voiceActive = true
                voiceStartTime = now
                currentCandidateSamples.clear()
                // Prepend pre-roll audio so "Hey" is never clipped
                val preRoll = preRollBuffer.getSamples()
                for (s in preRoll) {
                    currentCandidateSamples.add(s)
                }
            }
            lastVoiceTime = now
        }

        if (voiceActive) {
            for (i in 0 until readSize) {
                currentCandidateSamples.add(chunk[i])
            }

            val speechDuration = lastVoiceTime - voiceStartTime
            val trailingSilence = now - lastVoiceTime

            // Check if utterance just ended (silence of 250ms)
            if (trailingSilence >= 250L) {
                voiceActive = false

                if (speechDuration in MIN_UTTERANCE_MS..MAX_UTTERANCE_MS &&
                    (now - lastWakeTime > DEBOUNCE_COOLDOWN_MS) &&
                    !isTranscribingCandidate.get()
                ) {
                    val candidateCopy = ShortArray(currentCandidateSamples.size) { currentCandidateSamples[it] }
                    currentCandidateSamples.clear()
                    evaluateWakeCandidate(candidateCopy)
                } else {
                    currentCandidateSamples.clear()
                }
            } else if ((now - voiceStartTime) > MAX_UTTERANCE_MS + 500L) {
                // Speech is too long to be a wake word (e.g. continuous conversation), discard
                resetUtteranceState()
            }
        }
    }

    private fun evaluateWakeCandidate(samples: ShortArray) {
        if (!engine.isInitialized || samples.isEmpty()) return

        isTranscribingCandidate.set(true)
        scope.launch {
            try {
                val floatData = AudioMath.shortArrayToNormalizedFloat(samples)
                val transcript = engine.transcribe(
                    floatData,
                    threads = 2,
                    prompt = WAKE_PROMPT
                )
                Log.d(TAG, "Wake candidate transcribed: \"$transcript\" (${samples.size} samples)")

                if (matchesWakeWord(transcript)) {
                    lastWakeTime = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Wake word triggered: \"$transcript\"!")
                    mainHandler.post {
                        stopSpotting()
                        onWakeWordDetected()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error evaluating wake candidate: ${e.message}")
            } finally {
                isTranscribingCandidate.set(false)
            }
        }
    }

    private fun resetUtteranceState() {
        voiceActive = false
        voiceStartTime = 0L
        lastVoiceTime = 0L
        currentCandidateSamples.clear()
    }

    fun stopSpotting() {
        if (isSpotting.compareAndSet(true, false)) {
            resetUtteranceState()
            audioCaptureCoordinator.stopCapture()
        }
    }

    fun isSpotting(): Boolean = isSpotting.get()

    fun destroy() {
        stopSpotting()
    }
}
