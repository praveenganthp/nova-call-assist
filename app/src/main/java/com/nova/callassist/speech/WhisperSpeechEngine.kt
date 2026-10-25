package com.nova.callassist.speech

import android.os.SystemClock
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperCpuConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SpeechRecognitionEngine implementation powered by whisper.cpp.
 * Performs fast, 100% on-device offline inference using quantized or fp16 Whisper models.
 * Strictly synchronized via [inferenceMutex] to prevent native concurrency crashes or invalid contexts.
 */
class WhisperSpeechEngine : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "WhisperSpeechEngine"
    }

    private val inferenceMutex = Mutex()
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

        val queueStart = SystemClock.elapsedRealtime()
        inferenceMutex.withLock {
            val queueWaitMs = SystemClock.elapsedRealtime() - queueStart
            Log.d(TAG, "[MODEL_INIT] Acquired mutex in ${queueWaitMs}ms. Initializing model from $modelPath (${file.length() / 1048576} MB)...")

            try {
                // Safely release previous context under lock
                val oldCtx = whisperContext
                whisperContext = null
                currentModelPath = null
                oldCtx?.release()

                whisperContext = WhisperContext.createContextFromFile(modelPath)
                currentModelPath = modelPath
                Log.i(TAG, "[MODEL_INIT] Whisper model loaded successfully from $modelPath")
                true
            } catch (e: CancellationException) {
                Log.d(TAG, "[MODEL_INIT] Initialization cancelled.")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[MODEL_INIT] Failed to load Whisper context: ${e.message}", e)
                whisperContext = null
                currentModelPath = null
                false
            }
        }
    }

    override suspend fun transcribe(
        audioData: FloatArray,
        threads: Int?,
        printTimestamps: Boolean,
        prompt: String?
    ): String = withContext(Dispatchers.Default) {
        val queueStart = SystemClock.elapsedRealtime()
        inferenceMutex.withLock {
            val queueWaitMs = SystemClock.elapsedRealtime() - queueStart
            val ctx = whisperContext ?: throw IllegalStateException("WhisperSpeechEngine is not initialized.")
            val numThreads = threads ?: WhisperCpuConfig.preferredThreadCount

            Log.d(TAG, "[INFERENCE] Start: samples=${audioData.size} (${audioData.size / 16000f}s), threads=$numThreads, queueWait=${queueWaitMs}ms")
            val inferenceStart = SystemClock.elapsedRealtime()

            val rawText = try {
                ctx.transcribeData(
                    audioData,
                    printTimestamp = printTimestamps,
                    threads = numThreads,
                    prompt = prompt
                )
            } catch (e: CancellationException) {
                Log.d(TAG, "[INFERENCE] Cancelled during native transcription.")
                throw e
            }

            val inferenceDuration = SystemClock.elapsedRealtime() - inferenceStart
            val cleanText = cleanWhisperTranscript(rawText)
            Log.i(TAG, "[INFERENCE] Done: text=\"$cleanText\" in ${inferenceDuration}ms (queueWait: ${queueWaitMs}ms)")
            cleanText
        }
    }

    override suspend fun benchmark(threads: Int): EngineBenchmarkResult = withContext(Dispatchers.Default) {
        val queueStart = SystemClock.elapsedRealtime()
        inferenceMutex.withLock {
            val queueWaitMs = SystemClock.elapsedRealtime() - queueStart
            val ctx = whisperContext ?: throw IllegalStateException("Whisper context not initialized.")
            Log.d(TAG, "[BENCHMARK] Start: threads=$threads, queueWait=${queueWaitMs}ms")

            val memBench = ctx.benchMemory(threads)
            val mulMatBench = ctx.benchGgmlMulMat(threads)
            val sysInfo = WhisperContext.getSystemInfo()
            EngineBenchmarkResult(
                memoryBandwidth = memBench,
                ggmlMulMat = mulMatBench,
                systemInfo = sysInfo
            )
        }
    }

    override fun release() {
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                inferenceMutex.withLock {
                    val ctx = whisperContext
                    whisperContext = null
                    currentModelPath = null
                    ctx?.release()
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
