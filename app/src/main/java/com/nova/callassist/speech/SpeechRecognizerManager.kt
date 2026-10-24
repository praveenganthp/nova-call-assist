package com.nova.callassist.speech

/**
 * Modular interface for offline speech recognition.
 * Allows switching between Vosk, Whisper.tflite, or other offline backends.
 */
interface SpeechRecognizerManager {

    /**
     * Starts listening for speech.
     * @param grammar Optional JSON array or list of valid phrases (e.g. for wake-word or constrained vocabulary).
     * @param timeoutMs Timeout in milliseconds before stopping automatically.
     */
    fun startListening(grammar: List<String>? = null, timeoutMs: Long = 8000)

    /**
     * Stops listening and processes final speech buffer.
     */
    fun stopListening()

    /**
     * Immediately cancels listening and discards current buffer.
     */
    fun cancelListening()

    /**
     * Checks if the microphone / recognizer is currently active.
     */
    fun isListening(): Boolean

    /**
     * Sets callback listener for recognition events.
     */
    fun setListener(listener: SpeechRecognitionListener?)

    /**
     * Releases audio recorder and native recognizer resources.
     */
    fun destroy()
}
