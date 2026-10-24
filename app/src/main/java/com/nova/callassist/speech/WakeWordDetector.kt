package com.nova.callassist.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Keyword-spotting detector for "Hey Nova".
 *
 * Implements low-power, privacy-preserving on-device wake-word detection:
 * 1. Continually samples microphone audio at 16 kHz mono in a circular ring buffer.
 * 2. Uses VAD / Voice Activity Energy (RMS) gating so NO neural inference runs during silence (0% CPU).
 * 3. Only when human speech energy is detected, extracts a 1.2-second window and performs quick local spotting.
 * 4. Triggers [onWakeWordDetected] with debouncing.
 */
class WakeWordDetector(
    private val engine: SpeechRecognitionEngine,
    private val onWakeWordDetected: () -> Unit
) {

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SECONDS = 1.5f
        private const val WINDOW_SAMPLES = (SAMPLE_RATE * BUFFER_SECONDS).toInt()
        private const val VOICE_ENERGY_THRESHOLD = 500.0
        private const val DEBOUNCE_COOLDOWN_MS = 2500L

        private val WAKE_PHRASES = listOf(
            "hey nova",
            "nova",
            "hi nova",
            "hello nova",
            "ok nova",
            "okay nova",
            "hey noah",
            "noah"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isSpotting = AtomicBoolean(false)
    private var spottingJob: Job? = null
    private var lastWakeTime: Long = 0L

    @SuppressLint("MissingPermission")
    fun startSpotting() {
        if (isSpotting.get()) return
        if (!engine.isInitialized) {
            Log.w(TAG, "Cannot start wake-word spotting: Engine is not initialized")
            return
        }

        isSpotting.set(true)

        spottingJob = scope.launch {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE * 2)

            var audioRecord: AudioRecord? = null
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    isSpotting.set(false)
                    Log.e(TAG, "AudioRecord failed to initialize for wake word")
                    return@launch
                }

                audioRecord.startRecording()
                Log.i(TAG, "Wake-word spotting active for 'Hey Nova' (Voice-Gated)")

                val readChunk = ShortArray(1600) // 100 ms chunks
                val ringBuffer = ShortArray(WINDOW_SAMPLES)
                var ringIndex = 0
                var totalSamplesAccumulated = 0

                var voiceDetected = false
                var voiceStartTime = 0L

                while (isSpotting.get() && isActive) {
                    val read = audioRecord.read(readChunk, 0, readChunk.size)
                    if (read > 0) {
                        for (i in 0 until read) {
                            ringBuffer[ringIndex] = readChunk[i]
                            ringIndex = (ringIndex + 1) % WINDOW_SAMPLES
                        }
                        totalSamplesAccumulated += read

                        val rms = calculateRms(readChunk, read)
                        val now = SystemClock.elapsedRealtime()

                        if (rms > VOICE_ENERGY_THRESHOLD) {
                            if (!voiceDetected) {
                                voiceDetected = true
                                voiceStartTime = now
                            }
                        } else if (voiceDetected && (now - voiceStartTime >= 800L)) {
                            // Voice utterance just finished and we have at least 800ms of audio
                            voiceDetected = false
                            if (totalSamplesAccumulated >= WINDOW_SAMPLES && (now - lastWakeTime > DEBOUNCE_COOLDOWN_MS)) {
                                val windowData = FloatArray(WINDOW_SAMPLES)
                                for (i in 0 until WINDOW_SAMPLES) {
                                    val idx = (ringIndex + i) % WINDOW_SAMPLES
                                    windowData[i] = ringBuffer[idx] / 32768.0f
                                }

                                try {
                                    // Quick 2-thread transcription of 1.5s window
                                    val transcript = engine.transcribe(windowData, threads = 2)
                                    Log.d(TAG, "Wake spot transcript: \"$transcript\"")
                                    if (matchesWakeWord(transcript)) {
                                        lastWakeTime = SystemClock.elapsedRealtime()
                                        Log.i(TAG, "Wake word detected: \"$transcript\"")
                                        mainHandler.post {
                                            stopSpotting()
                                            onWakeWordDetected()
                                        }
                                        break
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Wake transcribe error: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in wake-word detection loop: ${e.message}", e)
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) {
                    // Ignore cleanup exceptions
                }
            }
        }
    }

    fun stopSpotting() {
        isSpotting.set(false)
        spottingJob?.cancel()
        spottingJob = null
    }

    fun isSpotting(): Boolean = isSpotting.get()

    fun destroy() {
        stopSpotting()
    }

    private fun matchesWakeWord(transcript: String): Boolean {
        val clean = transcript.lowercase().trim()
            .replace(Regex("[^a-z\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return WAKE_PHRASES.any { phrase ->
            clean == phrase || clean.contains(phrase)
        }
    }

    private fun calculateRms(buffer: ShortArray, readSize: Int): Double {
        if (readSize <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until readSize) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / readSize)
    }
}
