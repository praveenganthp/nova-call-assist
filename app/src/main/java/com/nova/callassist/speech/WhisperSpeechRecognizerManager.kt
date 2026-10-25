package com.nova.callassist.speech

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SpeechRecognizerManager powered by on-device Whisper.cpp and coordinated by [AudioCaptureCoordinator].
 *
 * Audio & Inference Pipeline:
 * 1. Coordinates with [AudioCaptureCoordinator] to ensure exclusive ownership of the microphone.
 * 2. Prepend 400ms pre-roll audio so initial consonants ("Call") are never clipped.
 * 3. Detects speech onset and automatically terminates capture on 1000ms trailing silence.
 * 4. Stops microphone hardware before initiating CPU inference.
 * 5. Runs Whisper on dedicated background coroutines with task-specific vocabulary prompting.
 * 6. Emits performance metrics and enforces a 15-second watchdog safety timeout.
 */
class WhisperSpeechRecognizerManager(
    private val engine: SpeechRecognitionEngine,
    private val audioCaptureCoordinator: AudioCaptureCoordinator,
    private val threadCount: Int = 4
) : SpeechRecognizerManager {

    companion object {
        private const val TAG = "WhisperSpeechRec"
        const val SAMPLE_RATE = AudioMath.SAMPLE_RATE
        const val COMMAND_PROMPT = "Call Arun, Call Praveen, Call Bala, Call Mom, Yes, No, Cancel."

        /**
         * For backwards compatibility and unit testing.
         */
        fun shortArrayToNormalizedFloat(shorts: ShortArray): FloatArray =
            AudioMath.shortArrayToNormalizedFloat(shorts)

        fun calculateRms(buffer: ShortArray, readSize: Int): Double =
            AudioMath.calculateRms(buffer, readSize)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var listener: SpeechRecognitionListener? = null
    private val isSessionActive = AtomicBoolean(false)
    private var watchdogJob: Job? = null
    private var transcriptionJob: Job? = null

    private val _lastMetrics = MutableStateFlow<TranscriptionMetrics?>(null)
    val lastMetrics: StateFlow<TranscriptionMetrics?> = _lastMetrics.asStateFlow()

    override fun startListening(grammar: List<String>?, timeoutMs: Long) {
        if (!engine.isInitialized) {
            mainHandler.post {
                listener?.onError("Whisper speech engine is not initialized.")
            }
            return
        }

        cancelListening()
        isSessionActive.set(true)

        mainHandler.post {
            listener?.onPartialResult("Listening…")
        }

        // Start 15-second safety watchdog
        watchdogJob = scope.launch {
            delay(AudioMath.WATCHDOG_TIMEOUT_MS)
            if (isSessionActive.get()) {
                Log.w(TAG, "Watchdog timeout reached (15s). Resetting audio state.")
                audioCaptureCoordinator.stopCapture()
                isSessionActive.set(false)
                mainHandler.post {
                    listener?.onTimeout()
                }
            }
        }

        audioCaptureCoordinator.startCommandMode(
            AudioCaptureCoordinator.CommandCallbacks(
                onSpeechOnset = {
                    mainHandler.post {
                        listener?.onPartialResult("Hearing speech…")
                    }
                },
                onAudioReady = { audioData, audioDurationSec ->
                    processCommandAudio(audioData, audioDurationSec)
                },
                onTimeout = {
                    isSessionActive.set(false)
                    watchdogJob?.cancel()
                    mainHandler.post {
                        listener?.onTimeout()
                    }
                },
                onError = { errorMsg ->
                    isSessionActive.set(false)
                    watchdogJob?.cancel()
                    mainHandler.post {
                        listener?.onError(errorMsg)
                    }
                }
            )
        )
    }

    private fun processCommandAudio(audioData: FloatArray, audioDurationSec: Float) {
        mainHandler.post {
            listener?.onPartialResult("Transcribing…")
        }

        transcriptionJob = scope.launch(Dispatchers.Default) {
            try {
                val inferenceStart = SystemClock.elapsedRealtime()
                val transcript = engine.transcribe(
                    audioData,
                    threads = threadCount,
                    prompt = COMMAND_PROMPT
                )
                val inferenceDuration = SystemClock.elapsedRealtime() - inferenceStart
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

                isSessionActive.set(false)
                watchdogJob?.cancel()

                mainHandler.post {
                    if (transcript.isNotBlank()) {
                        listener?.onFinalResult(transcript)
                    } else {
                        Log.i(TAG, "Transcript was empty/blank; triggering onTimeout")
                        listener?.onTimeout()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                isSessionActive.set(false)
                watchdogJob?.cancel()
                Log.d(TAG, "Command transcription cancelled normally.")
                throw e
            } catch (e: Exception) {
                isSessionActive.set(false)
                watchdogJob?.cancel()
                Log.e(TAG, "Whisper transcription error: ${e.message}", e)
                mainHandler.post {
                    listener?.onError("Speech recognition error: ${e.localizedMessage ?: "Unknown"}")
                }
            }
        }
    }

    override fun stopListening() {
        if (isSessionActive.get()) {
            audioCaptureCoordinator.stopCapture()
        }
    }

    override fun cancelListening() {
        isSessionActive.set(false)
        watchdogJob?.cancel()
        watchdogJob = null
        transcriptionJob?.cancel()
        transcriptionJob = null
        audioCaptureCoordinator.cancelCapture()
    }

    override fun isListening(): Boolean = isSessionActive.get()

    override fun setListener(listener: SpeechRecognitionListener?) {
        this.listener = listener
    }

    override fun destroy() {
        cancelListening()
        listener = null
    }
}
