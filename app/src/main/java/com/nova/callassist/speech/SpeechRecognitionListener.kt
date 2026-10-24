package com.nova.callassist.speech

interface SpeechRecognitionListener {
    fun onPartialResult(partialText: String)
    fun onFinalResult(text: String)
    fun onError(errorMessage: String)
    fun onTimeout()
}
