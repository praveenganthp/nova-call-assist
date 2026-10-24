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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * SpeechRecognizerManager powered by on-device Whisper.cpp.
 *
 * Audio Pipeline:
 * 1. Captures 16 kHz Mono 16-bit PCM via standard Android AudioRecord.
 * 2. Uses adaptive voice-activity / energy monitoring (RMS) to detect speech onset and offset.
 * 3. Normalizes 16-bit PCM shorts to 32-bit floats (sample / 32768.0f).
 * 4. Dispatches Whisper transcription on background coroutines.
 * 5. Measures real-time performance metrics (latency, RTF, CPU threads).
 */
class WhisperSpeechRecognizerManager(
    private val engine: SpeechRecognitionEngine,
    private val threadCount: Int = 4
) : SpeechRecognizerManager {

    companion object {
        private const val TAG = "WhisperSpeechRec"
        const val SAMPLE_RATE = 16000
        private const val VOICE_ENERGY_THRESHOLD = 450.0
        private const val SILENCE_DETECTION_WINDOW_MS = 1200L
        private const val MIN_SPEECH_DURATION_MS = 400L

        /**
         * Converts 16-bit PCM short array to 32-bit normalized float array ([-1.0, 1.0]).
         */
        fun shortArrayToNormalizedFloat(shorts: ShortArray): FloatArray {
            val floats = FloatArray(shorts.size)
            for (i in shorts.indices) {
                floats[i] = shorts[i] / 32768.0f
            }
            return floats
        }

        /**
         * Calculates the Root Mean Square (RMS) energy of an audio buffer.
         */
        fun calculateRms(buffer: ShortArray, readSize: Int): Double {
            if (readSize <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until readSize) {
                val sample = buffer[i].toDouble()
                sum += sample * sample
            }
            return sqrt(sum / readSize)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var listener: SpeechRecognitionListener? = null
    private val isRecording = AtomicBoolean(false)
    private var recordJob: Job? = null

    private val _lastMetrics = MutableStateFlow<TranscriptionMetrics?>(null)
    val lastMetrics: StateFlow<TranscriptionMetrics?> = _lastMetrics.asStateFlow()

    @SuppressLint("MissingPermission")
    override fun startListening(grammar: List<String>?, timeoutMs: Long) {
        if (isRecording.get()) {
            stopListening()
        }

        if (!engine.isInitialized) {
            listener?.onError("Whisper speech engine is not initialized.")
            return
        }

        isRecording.set(true)
        mainHandler.post {
            listener?.onPartialResult("Listening…")
        }

        recordJob = scope.launch {
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
                    isRecording.set(false)
                    mainHandler.post {
                        listener?.onError("AudioRecord initialization failed. Microphone may be busy.")
                    }
                    return@launch
                }

                audioRecord.startRecording()

                val buffer = ShortArray(1024)
                val recordedSamples = ArrayList<Short>(SAMPLE_RATE * 5)
                val startTime = SystemClock.elapsedRealtime()

                var speechStarted = false
                var speechStartTime = 0L
                var lastVoiceTime = 0L

                while (isRecording.get() && isActive) {
                    val readCount = audioRecord.read(buffer, 0, buffer.size)
                    if (readCount > 0) {
                        for (i in 0 until readCount) {
                            recordedSamples.add(buffer[i])
                        }

                        val rms = calculateRms(buffer, readCount)
                        val now = SystemClock.elapsedRealtime()

                        if (rms > VOICE_ENERGY_THRESHOLD) {
                            if (!speechStarted) {
                                speechStarted = true
                                speechStartTime = now
                            }
                            lastVoiceTime = now
                        } else if (speechStarted) {
                            // Silence after speech detected
                            val silenceDuration = now - lastVoiceTime
                            val speechDuration = lastVoiceTime - speechStartTime
                            if (speechDuration >= MIN_SPEECH_DURATION_MS && silenceDuration >= SILENCE_DETECTION_WINDOW_MS) {
                                Log.d(TAG, "Silence detected after speech, stopping audio capture")
                                break
                            }
                        }

                        // Check timeout
                        if (timeoutMs > 0 && (now - startTime) >= timeoutMs) {
                            Log.d(TAG, "Audio capture timeout reached ($timeoutMs ms)")
                            break
                        }
                    } else if (readCount < 0) {
                        Log.w(TAG, "AudioRecord read error: $readCount")
                        break
                    }
                }

                // Stop recording hardware
                try {
                    audioRecord.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping audioRecord: ${e.message}")
                }

                isRecording.set(false)

                if (recordedSamples.isEmpty()) {
                    mainHandler.post {
                        listener?.onTimeout()
                    }
                    return@launch
                }

                // Process speech with Whisper
                mainHandler.post {
                    listener?.onPartialResult("Transcribing…")
                }

                val floatArray = ShortArray(recordedSamples.size).apply {
                    for (i in indices) {
                        this[i] = recordedSamples[i]
                    }
                }.let { shortArrayToNormalizedFloat(it) }

                val inferenceStart = SystemClock.elapsedRealtime()
                val transcript = engine.transcribe(floatArray, threads = threadCount)
                val inferenceDuration = SystemClock.elapsedRealtime() - inferenceStart

                val audioDurationSec = recordedSamples.size.toFloat() / SAMPLE_RATE
                val rtf = if (audioDurationSec > 0) (inferenceDuration / 1000f) / audioDurationSec else 0f

                val metrics = TranscriptionMetrics(
                    durationMs = inferenceDuration,
                    audioDurationSeconds = audioDurationSec,
                    realTimeFactor = rtf,
                    threadCount = threadCount,
                    transcript = transcript
                )
                _lastMetrics.value = metrics
                Log.i(TAG, "Whisper transcription: \"$transcript\" in ${inferenceDuration}ms (RTF: ${"%.2f".format(rtf)}x)")

                mainHandler.post {
                    if (transcript.isNotBlank()) {
                        listener?.onFinalResult(transcript)
                    } else {
                        listener?.onTimeout()
                    }
                }

            } catch (e: Exception) {
                isRecording.set(false)
                Log.e(TAG, "Exception during audio recording/transcription: ${e.message}", e)
                mainHandler.post {
                    listener?.onError("Speech recognition error: ${e.localizedMessage ?: "Unknown"}")
                }
            } finally {
                try {
                    audioRecord?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing audioRecord: ${e.message}")
                }
            }
        }
    }

    override fun stopListening() {
        if (isRecording.compareAndSet(true, false)) {
            // Setting isRecording to false causes the recording loop to exit and process recorded audio
        }
    }

    override fun cancelListening() {
        isRecording.set(false)
        recordJob?.cancel()
        recordJob = null
    }

    override fun isListening(): Boolean = isRecording.get()

    override fun setListener(listener: SpeechRecognitionListener?) {
        this.listener = listener
    }

    override fun destroy() {
        cancelListening()
        listener = null
    }
}
