package com.nova.callassist.speech

/**
 * Modular engine interface for offline on-device speech recognition.
 * Encapsulates the underlying speech engine (Whisper.cpp) behind a clean abstraction.
 */
interface SpeechRecognitionEngine {

    /**
     * True if the speech model is loaded into memory and ready for inference.
     */
    val isInitialized: Boolean

    /**
     * Path of the loaded model file.
     */
    val loadedModelPath: String?

    /**
     * Initializes the speech engine using the given local model file path.
     * @return true if initialization succeeded.
     */
    suspend fun initialize(modelPath: String): Boolean

    /**
     * Transcribes an array of 16 kHz mono normalized 32-bit floating point audio samples ([-1.0, 1.0]).
     *
     * @param audioData Normalized audio samples.
     * @param threads Optional CPU inference thread count override.
     * @param printTimestamps If true, prepends segment timestamps.
     * @return Transcribed plain text.
     */
    suspend fun transcribe(
        audioData: FloatArray,
        threads: Int? = null,
        printTimestamps: Boolean = false
    ): String

    /**
     * Runs hardware diagnostics and memory bandwidth benchmarks.
     */
    suspend fun benchmark(threads: Int = 4): EngineBenchmarkResult

    /**
     * Releases native memory and inference contexts.
     */
    fun release()
}

data class EngineBenchmarkResult(
    val memoryBandwidth: String,
    val ggmlMulMat: String,
    val systemInfo: String
)

data class TranscriptionMetrics(
    val durationMs: Long,
    val audioDurationSeconds: Float,
    val realTimeFactor: Float,
    val threadCount: Int,
    val transcript: String
)
