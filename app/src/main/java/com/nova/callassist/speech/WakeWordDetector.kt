package com.nova.callassist.speech

import android.os.SystemClock

/**
 * Keyword-spotting detector for "Hey Nova".
 * Uses Vosk grammar-constrained decoding to minimize CPU usage and latency.
 */
class WakeWordDetector(
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val onWakeWordDetected: () -> Unit
) : SpeechRecognitionListener {

    companion object {
        val WAKE_WORD_GRAMMAR = listOf("hey nova", "nova", "[unk]")
        private const val DEBOUNCE_COOLDOWN_MS = 2500L
    }

    private var lastWakeTime: Long = 0L
    private var isSpotting: Boolean = false

    /**
     * Starts continuous local keyword spotting for "Hey Nova".
     */
    fun startSpotting() {
        if (isSpotting) return
        isSpotting = true
        speechRecognizerManager.setListener(this)
        // 0 timeout means keep running until wake word or stopped
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
        checkWakePhrase(partialText)
    }

    override fun onFinalResult(text: String) {
        checkWakePhrase(text)
        // If still spotting and wasn't a wake word, restart listening loop
        if (isSpotting) {
            speechRecognizerManager.startListening(grammar = WAKE_WORD_GRAMMAR, timeoutMs = 0)
        }
    }

    override fun onError(errorMessage: String) {
        // If error happens while spotting (e.g. transient mic busy), retry after delay if still enabled
        if (isSpotting) {
            isSpotting = false
        }
    }

    override fun onTimeout() {
        if (isSpotting) {
            speechRecognizerManager.startListening(grammar = WAKE_WORD_GRAMMAR, timeoutMs = 0)
        }
    }

    private fun checkWakePhrase(transcript: String) {
        val clean = transcript.lowercase().trim()
        if (clean.contains("hey nova") || clean == "nova") {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWakeTime > DEBOUNCE_COOLDOWN_MS) {
                lastWakeTime = now
                stopSpotting()
                onWakeWordDetected()
            }
        }
    }
}
