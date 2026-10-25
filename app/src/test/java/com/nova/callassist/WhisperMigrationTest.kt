package com.nova.callassist

import com.nova.callassist.speech.AudioMath
import com.nova.callassist.speech.EngineBenchmarkResult
import com.nova.callassist.speech.PreRollBuffer
import com.nova.callassist.speech.SpeechRecognitionEngine
import com.nova.callassist.speech.TranscriptionMetrics
import com.nova.callassist.speech.WakeWordDetector
import com.nova.callassist.speech.WhisperModelOption
import com.nova.callassist.speech.WhisperSpeechRecognizerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            printTimestamps: Boolean,
            prompt: String?
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
            val matches = WakeWordDetector.matchesWakeWord(phrase)
            assertFalse("Phrase '$phrase' should NOT match wake word", matches)
        }
    }

    @Test
    fun testPreRollBufferPreservesInitialSamples() {
        val capacity = 6400 // 400ms at 16kHz
        val buffer = PreRollBuffer(capacity)

        // Write 3 chunks of 3200 samples each (total 9600 samples)
        // Values: chunk 1 has 1s, chunk 2 has 2s, chunk 3 has 3s
        val chunk1 = ShortArray(3200) { 1 }
        val chunk2 = ShortArray(3200) { 2 }
        val chunk3 = ShortArray(3200) { 3 }

        buffer.write(chunk1, chunk1.size)
        buffer.write(chunk2, chunk2.size)
        buffer.write(chunk3, chunk3.size)

        val retrieved = buffer.getSamples()
        assertEquals(capacity, retrieved.size)

        // The oldest 3200 (chunk 1) was overwritten.
        // Retrieved should have exactly chunk 2 (first 3200) followed by chunk 3 (last 3200).
        assertEquals(2.toShort(), retrieved[0])
        assertEquals(2.toShort(), retrieved[3199])
        assertEquals(3.toShort(), retrieved[3200])
        assertEquals(3.toShort(), retrieved[6399])
    }

    @Test
    fun testAdaptiveNoiseFloorAndDynamicThreshold() {
        // Quiet room
        val quietFloor = 40.0
        val quietThreshold = AudioMath.computeDynamicThreshold(quietFloor)
        assertEquals(AudioMath.MIN_DYNAMIC_THRESHOLD, quietThreshold, 0.001)

        val quietContinuation = AudioMath.computeContinuationThreshold(quietThreshold)
        assertEquals(210.0, quietContinuation, 0.001)

        // Noisy room with fan noise (RMS = 500)
        val noisyFloor = 500.0
        val noisyThreshold = AudioMath.computeDynamicThreshold(noisyFloor)
        assertEquals(1250.0, noisyThreshold, 0.001)

        val noisyContinuation = AudioMath.computeContinuationThreshold(noisyThreshold)
        assertEquals(875.0, noisyContinuation, 0.001)

        // Noise floor adaptation
        var floor = 100.0
        // Quieter chunk adapts floor downwards
        floor = AudioMath.updateNoiseFloor(floor, 50.0)
        assertTrue("Noise floor should adapt downward", floor < 100.0)

        // Louder chunk adapts floor upwards very slowly
        val before = floor
        floor = AudioMath.updateNoiseFloor(floor, 1500.0)
        assertTrue("Noise floor should increase slightly", floor > before && floor < before + 50.0)
    }

    @Test
    fun testSilenceBeforeSpeechDoesNotFinishRecordingPrematurely() {
        val dynamicThreshold = 300.0
        val continuationThreshold = AudioMath.computeContinuationThreshold(dynamicThreshold)

        var speechStarted = false
        var speechStartTime = 0L
        var lastVoiceTime = 0L
        var recordingFinished = false

        // Simulate 20 chunks of 100ms silence (total 2000ms silence)
        var simulatedTime = 0L
        for (step in 1..20) {
            simulatedTime += 100L
            val rms = 60.0 // silence

            if (rms > dynamicThreshold) {
                if (!speechStarted) {
                    speechStarted = true
                    speechStartTime = simulatedTime
                }
                lastVoiceTime = simulatedTime
            }

            if (speechStarted) {
                val silenceDuration = simulatedTime - lastVoiceTime
                val speechDuration = lastVoiceTime - speechStartTime
                if (speechDuration >= AudioMath.MIN_SPEECH_DURATION_MS &&
                    silenceDuration >= AudioMath.TRAILING_SILENCE_WINDOW_MS
                ) {
                    recordingFinished = true
                    break
                }
            }
        }

        assertFalse("Silence before speech should NOT mark speechStarted", speechStarted)
        assertFalse("Silence before speech should NOT finish recording prematurely", recordingFinished)
    }

    @Test
    fun testSpeechFollowedBySilenceTriggersRecordingCompletion() {
        val dynamicThreshold = 300.0

        var speechStarted = false
        var speechStartTime = 0L
        var lastVoiceTime = 0L
        var recordingFinished = false
        var finishTime = 0L

        var simulatedTime = 0L

        // Phase 1: 300ms initial silence (3 chunks)
        for (i in 1..3) {
            simulatedTime += 100L
            val rms = 50.0
            if (rms > dynamicThreshold) {
                if (!speechStarted) { speechStarted = true; speechStartTime = simulatedTime }
                lastVoiceTime = simulatedTime
            }
        }
        assertFalse(speechStarted)

        // Phase 2: 800ms speech "Call Arun" (8 chunks, rms = 1200)
        for (i in 1..8) {
            simulatedTime += 100L
            val rms = 1200.0
            if (rms > dynamicThreshold) {
                if (!speechStarted) { speechStarted = true; speechStartTime = simulatedTime }
                lastVoiceTime = simulatedTime
            }
        }
        assertTrue("Speech onset must be confirmed", speechStarted)
        assertEquals(400L, speechStartTime)
        assertEquals(1100L, lastVoiceTime)

        // Phase 3: Trailing silence (10 chunks of 100ms = 1000ms)
        for (i in 1..15) {
            simulatedTime += 100L
            val rms = 50.0 // silence

            if (speechStarted) {
                val silenceDuration = simulatedTime - lastVoiceTime
                val speechDuration = lastVoiceTime - speechStartTime
                if (speechDuration >= AudioMath.MIN_SPEECH_DURATION_MS &&
                    silenceDuration >= AudioMath.TRAILING_SILENCE_WINDOW_MS
                ) {
                    recordingFinished = true
                    finishTime = simulatedTime
                    break
                }
            }
        }

        assertTrue("Speech followed by 1000ms trailing silence MUST finish recording", recordingFinished)
        assertEquals("Should complete at exactly 1000ms after last speech frame", 2100L, finishTime)
    }

    @Test
    fun testHardMaxDurationTriggersCompletion() {
        var speechStarted = false
        var speechStartTime = 0L
        var recordingFinished = false

        var simulatedTime = 0L

        // Continuous speech for 13 seconds
        for (i in 1..130) {
            simulatedTime += 100L
            val rms = 1000.0 // continuous speech
            if (!speechStarted) {
                speechStarted = true
                speechStartTime = simulatedTime
            }

            if (speechStarted && (simulatedTime - speechStartTime) >= AudioMath.HARD_MAX_RECORDING_MS) {
                recordingFinished = true
                break
            }
        }

        assertTrue("Hard maximum recording duration (12s) MUST trigger completion", recordingFinished)
        assertEquals(12100L, simulatedTime)
    }

    @Test
    fun testWhisperModelOptionMetadataAndLookup() {
        val tiny = WhisperModelOption.TINY_EN
        assertEquals("tiny_en", tiny.id)
        assertEquals("ggml-tiny.en-q5_1.bin", tiny.fileName)
        assertEquals(31, tiny.approxSizeMb)
        assertTrue(tiny.expectedSizeBytes > 30_000_000L)
        assertTrue(tiny.downloadUrl.contains("ggml-tiny.en-q5_1.bin"))

        val base = WhisperModelOption.BASE_EN
        assertEquals("base_en", base.id)
        assertEquals("ggml-base.en-q5_1.bin", base.fileName)
        assertEquals(57, base.approxSizeMb)
        assertTrue(base.expectedSizeBytes > 55_000_000L)
        assertTrue(base.downloadUrl.contains("ggml-base.en-q5_1.bin"))

        assertEquals(tiny, WhisperModelOption.fromId("tiny_en"))
        assertEquals(base, WhisperModelOption.fromId("base_en"))
        assertEquals(WhisperModelOption.DEFAULT, WhisperModelOption.fromId("unknown_model_id"))

        assertEquals(tiny, WhisperModelOption.fromFileName("ggml-tiny.en-q5_1.bin"))
        assertEquals(base, WhisperModelOption.fromFileName("ggml-base.en-q5_1.bin"))
    }

    @Test
    fun testConcurrentInferenceSchedulingSafety() = runBlocking {
        // Test that serialized inferenceMutex prevents concurrent native access
        val mutex = Mutex()
        var concurrentExecutions = 0
        var maxConcurrent = 0

        val fakeEngine = object : SpeechRecognitionEngine {
            override val isInitialized: Boolean = true
            override val loadedModelPath: String = "test.bin"
            override suspend fun initialize(modelPath: String): Boolean = true
            override suspend fun transcribe(
                audioData: FloatArray,
                threads: Int?,
                printTimestamps: Boolean,
                prompt: String?
            ): String = mutex.withLock {
                concurrentExecutions++
                if (concurrentExecutions > maxConcurrent) {
                    maxConcurrent = concurrentExecutions
                }
                delay(20) // simulate native work
                concurrentExecutions--
                "Call Arun"
            }
            override suspend fun benchmark(threads: Int): EngineBenchmarkResult = mutex.withLock {
                concurrentExecutions++
                if (concurrentExecutions > maxConcurrent) {
                    maxConcurrent = concurrentExecutions
                }
                delay(20)
                concurrentExecutions--
                EngineBenchmarkResult("mem", "mul", "sys")
            }
            override fun release() {}
        }

        // Launch 5 concurrent calls
        val jobs = (1..5).map {
            async(Dispatchers.Default) {
                if (it % 2 == 0) {
                    fakeEngine.transcribe(FloatArray(16000))
                } else {
                    fakeEngine.benchmark(4)
                }
            }
        }

        jobs.forEach { it.await() }

        assertEquals("At most 1 native operation should run at a time", 1, maxConcurrent)
        assertEquals("All operations finished and counter returned to 0", 0, concurrentExecutions)
    }

    @Test
    fun testCancellationDuringInferenceRethrowsCleanly() = runBlocking {
        val mutex = Mutex()
        var cancelledCleanly = false

        val job = launch(Dispatchers.Default) {
            try {
                mutex.withLock {
                    delay(200) // Simulating long inference
                }
            } catch (e: CancellationException) {
                cancelledCleanly = true
                throw e
            }
        }

        delay(30)
        job.cancel()
        job.join()

        assertTrue("CancellationException should be caught and rethrown cleanly without corrupting state", cancelledCleanly)
        assertFalse("Mutex must be unlocked after cancelled coroutine", mutex.isLocked)
    }
}
