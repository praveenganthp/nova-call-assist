package com.nova.callassist.speech

import android.util.Log
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperCpuConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SpeechRecognitionEngine implementation powered by whisper.cpp.
 * Performs fast, 100% on-device offline inference using quantized or fp16 Whisper models.
 */
class WhisperSpeechEngine : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "WhisperSpeechEngine"
    }

    private var whisperContext: WhisperContext? = null
    private var currentModelPath: String? = null

    override val isInitialized: Boolean
        get() = whisperContext != null

    override val loadedModelPath: String?
        get() = currentModelPath

    override suspend fun initialize(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "Model file not found or empty: $modelPath")
            return@withContext false
        }

        try {
            release()
            Log.i(TAG, "Loading Whisper model from $modelPath (${file.length() / 1048576} MB)...")
            whisperContext = WhisperContext.createContextFromFile(modelPath)
            currentModelPath = modelPath
            Log.i(TAG, "Whisper model loaded successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Whisper context: ${e.message}", e)
            whisperContext = null
            currentModelPath = null
            false
        }
    }

    override suspend fun transcribe(
        audioData: FloatArray,
        threads: Int?,
        printTimestamps: Boolean
    ): String = withContext(Dispatchers.Default) {
        val ctx = whisperContext ?: throw IllegalStateException("WhisperSpeechEngine is not initialized.")
        val numThreads = threads ?: WhisperCpuConfig.preferredThreadCount
        val rawText = ctx.transcribeData(audioData, printTimestamp = printTimestamps, threads = numThreads)
        cleanWhisperTranscript(rawText)
    }

    override suspend fun benchmark(threads: Int): EngineBenchmarkResult = withContext(Dispatchers.Default) {
        val ctx = whisperContext ?: throw IllegalStateException("Whisper context not initialized.")
        val memBench = ctx.benchMemory(threads)
        val mulMatBench = ctx.benchGgmlMulMat(threads)
        val sysInfo = WhisperContext.getSystemInfo()
        EngineBenchmarkResult(
            memoryBandwidth = memBench,
            ggmlMulMat = mulMatBench,
            systemInfo = sysInfo
        )
    }

    override fun release() {
        try {
            val ctx = whisperContext
            whisperContext = null
            currentModelPath = null
            ctx?.let {
                // Non-blocking release
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WhisperContext: ${e.message}")
        }
    }

    /**
     * Cleans up Whisper output artifacts such as hallucinated repeating tokens,
     * parenthetical audio annotations like "(cough)", "[music]", etc.
     */
    private fun cleanWhisperTranscript(text: String): String {
        return text
            .replace(Regex("\\[.*?\\]"), " ")
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("[.,!?;:\"]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
