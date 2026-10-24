package com.nova.callassist.speech

import android.os.SystemClock

/**
 * Keyword-spotting detector for "Hey Nova".
 * Continuously listens locally on the microphone using Vosk.
 * Never restarts AudioRecord in the middle of silence loops to prevent audio hardware lockup.
 */
class WakeWordDetector(
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val onWakeWordDetected: () -> Unit
) : SpeechRecognitionListener {

    companion object {
        // Broad phonetic variations of "Hey Nova" in small English acoustic models
        val WAKE_WORD_GRAMMAR = listOf(
            "hey nova",
            "nova",
            "hi nova",
            "hello nova",
            "ok nova",
            "okay nova",
            "hey noah",
            "noah",
            "[unk]"
        )

        private val WAKE_PHRASES = listOf(
            "hey nova",
            "hi nova",
            "hello nova",
            "ok nova",
            "okay nova",
            "nova",
            "hey noah",
            "noah"
        )

        private const val DEBOUNCE_COOLDOWN_MS = 2500L
    }

    private var lastWakeTime: Long = 0L
    @Volatile
    private var isSpotting: Boolean = false

    /**
     * Starts continuous local keyword spotting for "Hey Nova".
     */
    fun startSpotting() {
        if (isSpotting) return
        isSpotting = true
        speechRecognizerManager.setListener(this)
        // 0 timeout means continuous listening without timer cut-off
        speechRecognizerManager.startListening(grammar = WAKE_WORD_GRAMMAR, timeoutMs = 0)
    }

    /**
     * Stops keyword spotting.
     */
    fun stopSpotting() {
        if (!isSpotting) return
        isSpotting = false
        speechRecognizerManager.stopListening()
        speechRecognizerManager.setListener(null)
    }

    fun isSpotting(): Boolean = isSpotting

    override fun onPartialResult(partialText: String) {
        if (isSpotting) {
            checkWakePhrase(partialText)
        }
    }

    override fun onFinalResult(text: String) {
        if (isSpotting) {
            checkWakePhrase(text)
            // DO NOT restart startListening here!
            // Vosk's SpeechService already runs in a continuous audio thread loop.
            // Restarting startListening here causes AudioRecord state crashes on Android.
        }
    }

    override fun onError(errorMessage: String) {
        // If an audio error occurs, wait briefly and recover if still active
        if (isSpotting) {
            isSpotting = false
        }
    }

    override fun onTimeout() {
        // No-op for continuous spotting
    }

    private fun checkWakePhrase(transcript: String) {
        val clean = transcript.lowercase().trim()
        val matched = WAKE_PHRASES.any { phrase ->
            clean == phrase || clean.contains(phrase)
        }

        if (matched) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWakeTime > DEBOUNCE_COOLDOWN_MS) {
                lastWakeTime = now
                stopSpotting()
                onWakeWordDetected()
            }
        }
    }
}
