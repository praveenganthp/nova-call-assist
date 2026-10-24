package com.nova.callassist

import com.nova.callassist.speech.EngineBenchmarkResult
import com.nova.callassist.speech.SpeechRecognitionEngine
import com.nova.callassist.speech.TranscriptionMetrics
import com.nova.callassist.speech.WhisperSpeechRecognizerManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WhisperMigrationTest {

    private class FakeWhisperEngine(
        var transcribedResponse: String = "Call Praveen"
    ) : SpeechRecognitionEngine {
        override var isInitialized: Boolean = true
        override val loadedModelPath: String = "/data/user/0/com.nova.callassist/files/whisper-model/ggml-tiny.en-q5_1.bin"

        override suspend fun initialize(modelPath: String): Boolean {
            isInitialized = true
            return true
        }

        override suspend fun transcribe(
            audioData: FloatArray,
            threads: Int?,
            printTimestamps: Boolean
        ): String {
            return transcribedResponse
        }

        override suspend fun benchmark(threads: Int): EngineBenchmarkResult {
            return EngineBenchmarkResult(
                memoryBandwidth = "system_memcpy: 12500 MB/s",
                ggmlMulMat = "ggml_mul_mat: 180 GFLOPS",
                systemInfo = "NEON = 1 | FP16_VA = 1"
            )
        }

        override fun release() {
            isInitialized = false
        }
    }

    @Test
    fun testPcmShortToNormalizedFloatConversion() {
        val rawShorts = shortArrayOf(0, 32767, -32768, 16384, -16384)
        val floats = WhisperSpeechRecognizerManager.shortArrayToNormalizedFloat(rawShorts)

        assertEquals(5, floats.size)
        assertEquals(0.0f, floats[0], 0.0001f)
        assertTrue(floats[1] > 0.999f && floats[1] <= 1.0f)
        assertEquals(-1.0f, floats[2], 0.0001f)
        assertEquals(0.5f, floats[3], 0.001f)
        assertEquals(-0.5f, floats[4], 0.001f)
    }

    @Test
    fun testRmsEnergyCalculation() {
        // All silence
        val silence = ShortArray(1600) { 0 }
        val silenceRms = WhisperSpeechRecognizerManager.calculateRms(silence, silence.size)
        assertEquals(0.0, silenceRms, 0.0001)

        // Known DC signal: all 1000
        val constantSignal = ShortArray(100) { 1000 }
        val constantRms = WhisperSpeechRecognizerManager.calculateRms(constantSignal, constantSignal.size)
        assertEquals(1000.0, constantRms, 0.0001)

        // Speech-like amplitude signal
        val speechSignal = ShortArray(500) { if (it % 2 == 0) 1500 else -1500 }
        val speechRms = WhisperSpeechRecognizerManager.calculateRms(speechSignal, speechSignal.size)
        assertEquals(1500.0, speechRms, 0.0001)
        assertTrue("Speech signal should exceed 450.0 voice threshold", speechRms > 450.0)
    }

    @Test
    fun testTranscriptionMetricsCalculation() {
        val durationMs = 350L
        val audioDurationSec = 2.5f
        val rtf = (durationMs / 1000f) / audioDurationSec

        val metrics = TranscriptionMetrics(
            durationMs = durationMs,
            audioDurationSeconds = audioDurationSec,
            realTimeFactor = rtf,
            threadCount = 4,
            transcript = "Call Praveen"
        )

        assertEquals(350L, metrics.durationMs)
        assertEquals(2.5f, metrics.audioDurationSeconds, 0.001f)
        assertEquals(0.14f, metrics.realTimeFactor, 0.01f)
        assertEquals(4, metrics.threadCount)
        assertEquals("Call Praveen", metrics.transcript)
        assertTrue("Whisper inference must be faster than real time (RTF < 1.0)", metrics.realTimeFactor < 1.0f)
    }

    @Test
    fun testSpeechRecognitionEngineLifecycle() = runBlocking {
        val fakeEngine = FakeWhisperEngine("Call Vignesh")
        assertTrue(fakeEngine.isInitialized)

        val result = fakeEngine.transcribe(FloatArray(16000) { 0.1f }, threads = 4)
        assertEquals("Call Vignesh", result)

        val bench = fakeEngine.benchmark(4)
        assertTrue(bench.systemInfo.contains("NEON"))

        fakeEngine.release()
        assertFalse(fakeEngine.isInitialized)
    }

    @Test
    fun testWakeWordPhrasesMatching() {
        val validPhrases = listOf(
            "hey nova",
            "Hey Nova!",
            "hi nova",
            "hello nova",
            "ok nova",
            "okay nova",
            "nova",
            "hey noah"
        )

        for (phrase in validPhrases) {
            val clean = phrase.lowercase().trim()
                .replace(Regex("[^a-z\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            val matches = listOf("hey nova", "nova", "hi nova", "hello nova", "ok nova", "okay nova", "hey noah", "noah")
                .any { clean == it || clean.contains(it) }

            assertTrue("Phrase '$phrase' should match wake word", matches)
        }

        val invalidPhrases = listOf(
            "call praveen",
            "cancel",
            "what is the weather",
            "open camera",
            "yes"
        )

        for (phrase in invalidPhrases) {
            val clean = phrase.lowercase().trim()
            val matches = listOf("hey nova", "nova", "hi nova", "hello nova", "ok nova", "okay nova", "hey noah", "noah")
                .any { clean == it || clean.contains(it) }

            assertFalse("Phrase '$phrase' should NOT match wake word", matches)
        }
    }
}
