package com.nova.callassist.speech

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException

/**
 * SpeechRecognizerManager implementation using Vosk offline speech engine.
 * Completely local, zero cloud dependencies.
 */
class VoskSpeechRecognizerManager(
    private val model: Model
) : SpeechRecognizerManager, RecognitionListener {

    companion object {
        private const val SAMPLE_RATE = 16000.0f
    }

    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var listener: SpeechRecognitionListener? = null
    private var isCurrentlyListening = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    override fun startListening(grammar: List<String>?, timeoutMs: Long) {
        if (isCurrentlyListening) {
            stopListening()
        }

        try {
            // Build recognizer with optional grammar
            val rec = if (!grammar.isNullOrEmpty()) {
                val grammarJson = JSONArray(grammar).toString()
                Recognizer(model, SAMPLE_RATE, grammarJson)
            } else {
                Recognizer(model, SAMPLE_RATE)
            }
            recognizer = rec

            val service = SpeechService(rec, SAMPLE_RATE)
            speechService = service
            service.startListening(this)
            isCurrentlyListening = true

            // Schedule timeout
            if (timeoutMs > 0) {
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                timeoutRunnable = Runnable {
                    if (isCurrentlyListening) {
                        stopListening()
                        listener?.onTimeout()
                    }
                }
                mainHandler.postDelayed(timeoutRunnable!!, timeoutMs)
            }
        } catch (e: IOException) {
            isCurrentlyListening = false
            listener?.onError("Failed to start speech service: ${e.message}")
        } catch (e: Exception) {
            isCurrentlyListening = false
            listener?.onError("Microphone error: ${e.message}")
        }
    }

    override fun stopListening() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        if (isCurrentlyListening) {
            try {
                speechService?.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isCurrentlyListening = false
        }
    }

    override fun cancelListening() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        if (isCurrentlyListening) {
            try {
                speechService?.cancel()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isCurrentlyListening = false
        }
    }

    override fun isListening(): Boolean = isCurrentlyListening

    override fun setListener(listener: SpeechRecognitionListener?) {
        this.listener = listener
    }

    override fun destroy() {
        cancelListening()
        speechService?.shutdown()
        speechService = null
        recognizer?.close()
        recognizer = null
        listener = null
    }

    // Vosk RecognitionListener callbacks

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        try {
            val json = JSONObject(hypothesis)
            val partial = json.optString("partial", "").trim()
            if (partial.isNotBlank()) {
                mainHandler.post {
                    listener?.onPartialResult(partial)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResult(hypothesis: String?) {
        handleFinalHypothesis(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        handleFinalHypothesis(hypothesis)
    }

    private fun handleFinalHypothesis(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text", "").trim()
            if (text.isNotBlank()) {
                mainHandler.post {
                    listener?.onFinalResult(text)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onError(exception: Exception?) {
        isCurrentlyListening = false
        val message = exception?.message ?: "Unknown recognition error"
        mainHandler.post {
            listener?.onError(message)
        }
    }

    override fun onTimeout() {
        isCurrentlyListening = false
        mainHandler.post {
            listener?.onTimeout()
        }
    }
}
