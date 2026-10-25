package com.nova.callassist.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages text-to-speech voice responses and audible chimes.
 * Operates offline using Android's native TextToSpeech engine and ToneGenerator.
 */
class AudioFeedbackManager(
    private val context: Context,
    private val onInitComplete: ((Boolean) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false
    private var toneGenerator: ToneGenerator? = null
    private val _isSpeaking = AtomicBoolean(false)

    /**
     * True if TTS is currently synthesizing or playing spoken audio.
     */
    fun isSpeaking(): Boolean = _isSpeaking.get() || (tts?.isSpeaking == true)

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
            onInitComplete?.invoke(isTtsReady)
        } else {
            isTtsReady = false
            onInitComplete?.invoke(false)
        }
    }

    /**
     * Speaks the given text using Android's offline TextToSpeech engine.
     */
    fun speak(
        text: String,
        enabled: Boolean = true,
        onDone: (() -> Unit)? = null
    ) {
        if (!enabled) {
            onDone?.invoke()
            return
        }

        if (isTtsReady && tts != null) {
            val utteranceId = "nova_speech_${System.currentTimeMillis()}"
            _isSpeaking.set(true)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    _isSpeaking.set(true)
                }
                override fun onDone(id: String?) {
                    _isSpeaking.set(false)
                    if (id == utteranceId) onDone?.invoke()
                }
                override fun onError(id: String?) {
                    _isSpeaking.set(false)
                    if (id == utteranceId) onDone?.invoke()
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            // TTS not available, play sound cue and invoke completion
            playChime(ToneType.BEEP)
            onDone?.invoke()
        }
    }

    /**
     * Stops any currently playing speech.
     */
    fun stop() {
        _isSpeaking.set(false)
        try {
            tts?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    enum class ToneType {
        WAKE_WORD_DETECTED,
        BEEP,
        CONFIRMATION_ACK,
        CANCEL_NACK
    }

    /**
     * Plays audio chimes / earcons for immediate responsive feedback.
     */
    fun playChime(type: ToneType) {
        try {
            when (type) {
                ToneType.WAKE_WORD_DETECTED -> toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
                ToneType.BEEP -> toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                ToneType.CONFIRMATION_ACK -> toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 200)
                ToneType.CANCEL_NACK -> toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Releases audio and TTS resources.
     */
    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            toneGenerator?.release()
            toneGenerator = null
            isTtsReady = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
