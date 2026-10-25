package com.nova.callassist.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Pure mathematical utilities and signal processing algorithms for Android voice capture.
 * Decoupled from Android hardware to allow 100% JVM unit test coverage.
 */
object AudioMath {
    const val SAMPLE_RATE = 16000
    const val PRE_ROLL_SAMPLES = 6400 // 400 ms at 16 kHz
    const val MIN_SPEECH_DURATION_MS = 350L
    const val TRAILING_SILENCE_WINDOW_MS = 1000L
    const val INITIAL_SILENCE_TIMEOUT_MS = 5000L
    const val HARD_MAX_RECORDING_MS = 12000L
    const val WATCHDOG_TIMEOUT_MS = 15000L

    const val MIN_DYNAMIC_THRESHOLD = 300.0
    const val MAX_DYNAMIC_THRESHOLD = 1800.0

    /**
     * Converts 16-bit PCM short array to 32-bit normalized float array ([-1.0, 1.0]).
     */
    fun shortArrayToNormalizedFloat(shorts: ShortArray, length: Int = shorts.size): FloatArray {
        val size = length.coerceIn(0, shorts.size)
        val floats = FloatArray(size)
        for (i in 0 until size) {
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

    /**
     * Updates an exponential moving average (EMA) estimation of ambient room noise floor.
     */
    fun updateNoiseFloor(currentFloor: Double, currentRms: Double): Double {
        if (currentFloor <= 0.0) return currentRms
        return if (currentRms < currentFloor) {
            currentFloor * 0.85 + currentRms * 0.15
        } else {
            currentFloor * 0.98 + currentRms * 0.02
        }
    }

    /**
     * Computes dynamic voice energy threshold based on ambient noise floor.
     */
    fun computeDynamicThreshold(noiseFloor: Double): Double {
        return (noiseFloor * 2.2 + 150.0).coerceIn(MIN_DYNAMIC_THRESHOLD, MAX_DYNAMIC_THRESHOLD)
    }

    /**
     * Threshold to remain in speech (hysteresis factor 0.70x).
     */
    fun computeContinuationThreshold(dynamicThreshold: Double): Double {
        return dynamicThreshold * 0.70
    }
}

/**
 * High-performance circular buffer holding pre-roll audio samples so the first word ("Call")
 * is never truncated during speech onset latency.
 */
class PreRollBuffer(val capacity: Int = AudioMath.PRE_ROLL_SAMPLES) {
    private val buffer = ShortArray(capacity)
    private var writeIndex = 0
    private var count = 0

    @Synchronized
    fun write(samples: ShortArray, length: Int) {
        val n = length.coerceIn(0, samples.size)
        for (i in 0 until n) {
            buffer[writeIndex] = samples[i]
            writeIndex = (writeIndex + 1) % capacity
            if (count < capacity) count++
        }
    }

    @Synchronized
    fun getSamples(): ShortArray {
        val result = ShortArray(count)
        val start = if (count < capacity) 0 else writeIndex
        for (i in 0 until count) {
            result[i] = buffer[(start + i) % capacity]
        }
        return result
    }

    @Synchronized
    fun clear() {
        writeIndex = 0
        count = 0
    }
}

/**
 * Single Owner for Android Microphone Capture.
 *
 * Guarantees:
 * 1. Exactly ONE [AudioRecord] instance in the entire application at any given time.
 * 2. Complete prevention of hardware contention, device-busy errors, or resource lockups.
 * 3. Low-power, voice-activity gated wake-word monitoring.
 * 4. Pre-roll buffered, adaptive silence-gated command capture.
 * 5. Hardware released before CPU-intensive Whisper neural inference.
 */
class AudioCaptureCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private const val TAG = "AudioCaptureCoord"
    }

    enum class Mode {
        IDLE,
        WAKE_WORD,
        COMMAND
    }

    data class CaptureTelemetry(
        val mode: Mode = Mode.IDLE,
        val currentRms: Double = 0.0,
        val ambientNoiseFloor: Double = 0.0,
        val dynamicThreshold: Double = 0.0,
        val speechOnsetDetected: Boolean = false,
        val statusMessage: String = "Idle"
    )

    private val captureMutex = Mutex()
    private val currentMode = AtomicReference(Mode.IDLE)
    private var captureJob: Job? = null
    private var activeAudioRecord: AudioRecord? = null

    private val preRollBuffer = PreRollBuffer()

    private val _telemetry = MutableStateFlow(CaptureTelemetry())
    val telemetry: StateFlow<CaptureTelemetry> = _telemetry.asStateFlow()

    // Listeners for active modes
    private var wakeWordFrameConsumer: ((ShortArray, Int, Double, Double) -> Unit)? = null
    private var commandCallbacks: CommandCallbacks? = null

    data class CommandCallbacks(
        val onSpeechOnset: () -> Unit,
        val onAudioReady: (FloatArray, Float) -> Unit,
        val onTimeout: () -> Unit,
        val onError: (String) -> Unit
    )

    /**
     * Starts continuous low-power wake-word capture.
     */
    fun startWakeWordMode(onAudioFrame: (ShortArray, Int, Double, Double) -> Unit) {
        scope.launch {
            captureMutex.withLock {
                wakeWordFrameConsumer = onAudioFrame
                commandCallbacks = null
                currentMode.set(Mode.WAKE_WORD)

                if (activeAudioRecord == null || captureJob == null || !captureJob!!.isActive) {
                    launchAudioCaptureLoop()
                } else {
                    _telemetry.value = _telemetry.value.copy(
                        mode = Mode.WAKE_WORD,
                        statusMessage = "Listening for 'Hey Nova'"
                    )
                }
            }
        }
    }

    /**
     * Transitions or starts active command recording with VAD silence detection and pre-roll.
     */
    fun startCommandMode(callbacks: CommandCallbacks) {
        scope.launch {
            captureMutex.withLock {
                commandCallbacks = callbacks
                wakeWordFrameConsumer = null
                currentMode.set(Mode.COMMAND)

                if (activeAudioRecord == null || captureJob == null || !captureJob!!.isActive) {
                    launchAudioCaptureLoop()
                } else {
                    _telemetry.value = _telemetry.value.copy(
                        mode = Mode.COMMAND,
                        speechOnsetDetected = false,
                        statusMessage = "Listening for command…"
                    )
                }
            }
        }
    }

    /**
     * Stops any active recording loop and releases the microphone hardware.
     */
    fun stopCapture() {
        scope.launch {
            captureMutex.withLock {
                stopInternal()
            }
        }
    }

    /**
     * Synchronous cancel/stop used during destruction or cancellations.
     */
    fun cancelCapture() {
        currentMode.set(Mode.IDLE)
        captureJob?.cancel()
        captureJob = null
        try {
            activeAudioRecord?.stop()
            activeAudioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling AudioRecord: ${e.message}")
        }
        activeAudioRecord = null
        wakeWordFrameConsumer = null
        commandCallbacks = null
        _telemetry.value = CaptureTelemetry(mode = Mode.IDLE, statusMessage = "Stopped")
    }

    private fun stopInternal() {
        currentMode.set(Mode.IDLE)
        captureJob?.cancel()
        captureJob = null
        try {
            activeAudioRecord?.stop()
            activeAudioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        activeAudioRecord = null
        wakeWordFrameConsumer = null
        commandCallbacks = null
        _telemetry.value = CaptureTelemetry(mode = Mode.IDLE, statusMessage = "Idle")
    }

    @SuppressLint("MissingPermission")
    private fun launchAudioCaptureLoop() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioMath.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(AudioMath.SAMPLE_RATE * 2)

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioMath.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize. State: ${record.state}")
                record.release()
                val cb = commandCallbacks
                stopInternal()
                cb?.onError("Microphone is currently in use or unavailable.")
                return
            }

            record.startRecording()
            activeAudioRecord = record
            Log.i(TAG, "Exclusive AudioRecord session started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting AudioRecord: ${e.message}", e)
            try { record?.release() } catch (ignored: Exception) {}
            val cb = commandCallbacks
            stopInternal()
            cb?.onError("Microphone initialization error: ${e.localizedMessage ?: "Unknown"}")
            return
        }

        captureJob = scope.launch(Dispatchers.Default) {
            val audioRecord = activeAudioRecord ?: return@launch
            val chunkBuffer = ShortArray(1024)
            val commandSamples = ArrayList<Short>(AudioMath.SAMPLE_RATE * 6)

            var ambientNoiseFloor = 40.0
            var speechOnset = false
            var speechStartTime = 0L
            var lastVoiceTime = 0L
            val commandCaptureStartTime = SystemClock.elapsedRealtime()

            try {
                while (isActive && currentMode.get() != Mode.IDLE) {
                    val readSize = audioRecord.read(chunkBuffer, 0, chunkBuffer.size)

                    if (readSize > 0) {
                        val rms = AudioMath.calculateRms(chunkBuffer, readSize)
                        ambientNoiseFloor = AudioMath.updateNoiseFloor(ambientNoiseFloor, rms)
                        val dynamicThreshold = AudioMath.computeDynamicThreshold(ambientNoiseFloor)
                        val continuationThreshold = AudioMath.computeContinuationThreshold(dynamicThreshold)
                        val now = SystemClock.elapsedRealtime()

                        // Update pre-roll buffer continuously
                        preRollBuffer.write(chunkBuffer, readSize)

                        when (currentMode.get()) {
                            Mode.WAKE_WORD -> {
                                _telemetry.value = CaptureTelemetry(
                                    mode = Mode.WAKE_WORD,
                                    currentRms = rms,
                                    ambientNoiseFloor = ambientNoiseFloor,
                                    dynamicThreshold = dynamicThreshold,
                                    speechOnsetDetected = false,
                                    statusMessage = "Listening for 'Hey Nova'"
                                )
                                wakeWordFrameConsumer?.invoke(chunkBuffer, readSize, rms, ambientNoiseFloor)
                            }

                            Mode.COMMAND -> {
                                if (rms > dynamicThreshold) {
                                    if (!speechOnset) {
                                        speechOnset = true
                                        speechStartTime = now
                                        commandCallbacks?.onSpeechOnset?.invoke()

                                        // Prepend pre-roll buffer to command samples
                                        val preRoll = preRollBuffer.getSamples()
                                        for (s in preRoll) {
                                            commandSamples.add(s)
                                        }
                                        Log.d(TAG, "Speech onset detected! Prepended ${preRoll.size} pre-roll samples.")
                                    }
                                    lastVoiceTime = now
                                }

                                if (speechOnset) {
                                    for (i in 0 until readSize) {
                                        commandSamples.add(chunkBuffer[i])
                                    }

                                    val silenceDuration = now - lastVoiceTime
                                    val speechDuration = lastVoiceTime - speechStartTime

                                    // Trailing silence detection
                                    if (speechDuration >= AudioMath.MIN_SPEECH_DURATION_MS &&
                                        silenceDuration >= AudioMath.TRAILING_SILENCE_WINDOW_MS
                                    ) {
                                        Log.i(TAG, "Trailing silence detected ($silenceDuration ms). Finishing command capture.")
                                        finishCommandCapture(commandSamples)
                                        break
                                    }

                                    // Hard maximum recording limit
                                    if ((now - speechStartTime) >= AudioMath.HARD_MAX_RECORDING_MS) {
                                        Log.i(TAG, "Hard maximum command duration reached. Finishing command capture.")
                                        finishCommandCapture(commandSamples)
                                        break
                                    }
                                } else {
                                    // Initial silence timeout (no speech spoken)
                                    if ((now - commandCaptureStartTime) >= AudioMath.INITIAL_SILENCE_TIMEOUT_MS) {
                                        Log.i(TAG, "Initial silence timeout. No speech detected within ${AudioMath.INITIAL_SILENCE_TIMEOUT_MS}ms.")
                                        val cb = commandCallbacks
                                        stopInternal()
                                        cb?.onTimeout?.invoke()
                                        break
                                    }
                                }

                                _telemetry.value = CaptureTelemetry(
                                    mode = Mode.COMMAND,
                                    currentRms = rms,
                                    ambientNoiseFloor = ambientNoiseFloor,
                                    dynamicThreshold = dynamicThreshold,
                                    speechOnsetDetected = speechOnset,
                                    statusMessage = if (speechOnset) "Hearing speech…" else "Listening…"
                                )
                            }

                            Mode.IDLE -> break
                        }
                    } else if (readSize < 0) {
                        Log.e(TAG, "AudioRecord read returned error code: $readSize")
                        val cb = commandCallbacks
                        stopInternal()
                        cb?.onError?.invoke("Microphone read error (code $readSize)")
                        break
                    } else {
                        // readSize == 0, avoid tight spin
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during audio capture loop: ${e.message}", e)
                val cb = commandCallbacks
                stopInternal()
                cb?.onError?.invoke("Audio capture failure: ${e.localizedMessage ?: "Unknown"}")
            } finally {
                Log.d(TAG, "Audio capture loop exiting. Releasing AudioRecord.")
                try {
                    audioRecord.stop()
                    audioRecord.release()
                } catch (ignored: Exception) {}
                if (activeAudioRecord === audioRecord) {
                    activeAudioRecord = null
                }
            }
        }
    }

    private fun finishCommandCapture(recordedSamples: List<Short>) {
        val cb = commandCallbacks
        // Stop hardware immediately before inference
        stopInternal()

        if (recordedSamples.isEmpty()) {
            cb?.onTimeout?.invoke()
            return
        }

        val shortArr = ShortArray(recordedSamples.size) { recordedSamples[it] }
        val floatArr = AudioMath.shortArrayToNormalizedFloat(shortArr)
        val audioDurationSec = shortArr.size.toFloat() / AudioMath.SAMPLE_RATE

        Log.i(TAG, "Command audio captured: ${shortArr.size} samples ($audioDurationSec sec). Triggering processing.")
        cb?.onAudioReady?.invoke(floatArr, audioDurationSec)
    }

    fun isCapturing(): Boolean = currentMode.get() != Mode.IDLE && activeAudioRecord != null
}
