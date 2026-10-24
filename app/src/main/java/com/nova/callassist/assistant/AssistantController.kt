package com.nova.callassist.assistant

import android.Manifest
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.nova.callassist.audio.AudioFeedbackManager
import com.nova.callassist.calling.CallManager
import com.nova.callassist.contacts.ContactMatcher
import com.nova.callassist.contacts.ContactModel
import com.nova.callassist.contacts.ContactsRepository
import com.nova.callassist.contacts.MatchResult
import com.nova.callassist.contacts.PhoneNumber
import com.nova.callassist.settings.SettingsRepository
import com.nova.callassist.speech.SpeechModelManager
import com.nova.callassist.speech.SpeechRecognitionListener
import com.nova.callassist.speech.SpeechRecognizerManager
import com.nova.callassist.speech.VoskSpeechRecognizerManager
import com.nova.callassist.speech.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main coordinator managing state transitions, speech engine, contacts matching,
 * audio feedback, and phone call dispatching.
 */
class AssistantController(
    private val context: Context,
    val contactsRepository: ContactsRepository,
    val settingsRepository: SettingsRepository,
    val callManager: CallManager,
    val audioFeedbackManager: AudioFeedbackManager,
    val modelManager: SpeechModelManager
) : SpeechRecognitionListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private val _isAssistantActive = MutableStateFlow(false)
    val isAssistantActive: StateFlow<Boolean> = _isAssistantActive.asStateFlow()

    private val commandParser = CommandParser()
    private val contactMatcher = ContactMatcher()

    private var speechRecognizerManager: SpeechRecognizerManager? = null
    private var wakeWordDetector: WakeWordDetector? = null

    private var confirmationJob: Job? = null
    private var isListeningForConfirmation = false

    init {
        scope.launch {
            modelManager.initModel()
        }
    }

    /**
     * Checks if all mandatory runtime permissions are granted.
     */
    fun checkMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_CONTACTS)
        }
        return missing
    }

    /**
     * Starts the assistant. If wake word is enabled, begins keyword spotting.
     */
    fun startAssistant() {
        val missing = checkMissingPermissions()
        if (missing.isNotEmpty()) {
            _state.value = AssistantState.PermissionRequired(missing)
            return
        }

        _isAssistantActive.value = true

        scope.launch {
            val wakeWordEnabled = settingsRepository.wakeWordEnabled.first()
            if (wakeWordEnabled) {
                ensureSpeechEngineReady {
                    startWakeWordListening()
                }
            } else {
                _state.value = AssistantState.Idle
            }
        }
    }

    /**
     * Stops the assistant and releases active listening.
     */
    fun stopAssistant() {
        _isAssistantActive.value = false
        wakeWordDetector?.stopSpotting()
        speechRecognizerManager?.stopListening()
        audioFeedbackManager.stop()
        confirmationJob?.cancel()
        isListeningForConfirmation = false
        _state.value = AssistantState.Idle
    }

    /**
     * Manual listening trigger (fallback when wake-word is off or user taps mic).
     */
    fun startListeningManually() {
        val missing = checkMissingPermissions()
        if (missing.isNotEmpty()) {
            _state.value = AssistantState.PermissionRequired(missing)
            return
        }

        ensureSpeechEngineReady {
            startCommandListening(prompt = "Yes, I'm listening.")
        }
    }

    private fun ensureSpeechEngineReady(onReady: () -> Unit) {
        val loadedModel = modelManager.getLoadedModel()
        if (loadedModel != null) {
            if (speechRecognizerManager == null) {
                speechRecognizerManager = VoskSpeechRecognizerManager(loadedModel)
            }
            onReady()
        } else {
            scope.launch {
                val status = modelManager.initModel()
                if (status is SpeechModelManager.ModelStatus.Ready) {
                    speechRecognizerManager = VoskSpeechRecognizerManager(status.model)
                    onReady()
                } else {
                    _state.value = AssistantState.Error(
                        "Offline speech model is not ready. Please check Settings."
                    )
                }
            }
        }
    }

    private fun startWakeWordListening() {
        wakeWordDetector?.stopSpotting()
        val recognizer = speechRecognizerManager ?: return

        wakeWordDetector = WakeWordDetector(recognizer) {
            scope.launch {
                onWakeWordDetected()
            }
        }
        _state.value = AssistantState.ListeningForWakeWord
        wakeWordDetector?.startSpotting()
    }

    private fun onWakeWordDetected() {
        audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.WAKE_WORD_DETECTED)
        startCommandListening(prompt = "Hey, I'm listening.")
    }

    private fun startCommandListening(prompt: String? = null) {
        wakeWordDetector?.stopSpotting()
        isListeningForConfirmation = false
        _state.value = AssistantState.ListeningForCommand()

        scope.launch {
            val voiceResponses = settingsRepository.voiceResponsesEnabled.first()
            if (prompt != null) {
                audioFeedbackManager.speak(prompt, enabled = voiceResponses) {
                    beginAudioCaptureForCommand()
                }
            } else {
                beginAudioCaptureForCommand()
            }
        }
    }

    private fun beginAudioCaptureForCommand() {
        speechRecognizerManager?.setListener(this)
        speechRecognizerManager?.startListening(timeoutMs = 7000)
    }

    override fun onPartialResult(partialText: String) {
        _lastTranscript.value = partialText
        if (_state.value is AssistantState.ListeningForCommand) {
            _state.value = AssistantState.ListeningForCommand(partialText)
        }
    }

    override fun onFinalResult(text: String) {
        _lastTranscript.value = text
        speechRecognizerManager?.stopListening()

        if (isListeningForConfirmation) {
            handleConfirmationTranscript(text)
        } else {
            handleCommandTranscript(text)
        }
    }

    override fun onError(errorMessage: String) {
        _state.value = AssistantState.Error(errorMessage)
        resumeStandbyAfterDelay(3000)
    }

    override fun onTimeout() {
        if (isListeningForConfirmation) {
            cancelCurrentInteraction("Confirmation timed out")
        } else if (_state.value is AssistantState.ListeningForCommand) {
            cancelCurrentInteraction("No command heard")
        }
    }

    private fun handleCommandTranscript(transcript: String) {
        val command = commandParser.parse(transcript)

        when (command) {
            is CommandParser.Command.Call -> {
                processCallTarget(command.targetName)
            }
            is CommandParser.Command.Cancel -> {
                cancelCurrentInteraction("Cancelled")
            }
            is CommandParser.Command.Repeat -> {
                audioFeedbackManager.speak("Who would you like to call?") {
                    startCommandListening()
                }
            }
            is CommandParser.Command.SelectOption -> {
                // If previously in ambiguous state, select option
                val currentState = _state.value
                if (currentState is AssistantState.AmbiguousMatches) {
                    val idx = command.index - 1
                    if (idx in currentState.candidates.indices) {
                        selectAmbiguousCandidate(currentState.candidates[idx])
                    } else {
                        audioFeedbackManager.speak("Invalid option. Please choose from 1 to ${currentState.candidates.size}")
                    }
                }
            }
            is CommandParser.Command.Confirm -> {
                // Ignore confirm if not awaiting confirmation
            }
            is CommandParser.Command.Unknown -> {
                // Attempt direct contact match on raw text if it looks like a name
                processCallTarget(transcript)
            }
        }
    }

    private fun processCallTarget(targetName: String) {
        _state.value = AssistantState.MatchingContact(targetName)

        scope.launch {
            val contacts = contactsRepository.loadContacts()
            val result = contactMatcher.match(targetName, contacts)

            when (result) {
                is MatchResult.Single -> {
                    onSingleContactMatched(result.contact, result.selectedNumber, result.confidence)
                }
                is MatchResult.Ambiguous -> {
                    onMultipleContactsMatched(result.query, result.candidates)
                }
                is MatchResult.NoPhoneNumber -> {
                    val msg = "${result.contact.displayName} has no phone number."
                    _state.value = AssistantState.Error(msg)
                    audioFeedbackManager.speak(msg)
                    resumeStandbyAfterDelay(3500)
                }
                is MatchResult.NoMatch -> {
                    val msg = "I couldn't find a contact named $targetName."
                    _state.value = AssistantState.Error(msg)
                    audioFeedbackManager.speak(msg)
                    resumeStandbyAfterDelay(3500)
                }
            }
        }
    }

    private suspend fun onSingleContactMatched(
        contact: ContactModel,
        number: PhoneNumber,
        confidence: Float
    ) {
        val confirmationRequired = settingsRepository.confirmationRequired.first()
        val voiceResponses = settingsRepository.voiceResponsesEnabled.first()

        if (confirmationRequired) {
            _state.value = AssistantState.AwaitingConfirmation(contact, number, confidence)
            val prompt = "Do you want me to call ${contact.displayName}?"
            audioFeedbackManager.speak(prompt, enabled = voiceResponses) {
                listenForConfirmation()
            }
        } else {
            executeCall(contact, number)
        }
    }

    private fun listenForConfirmation() {
        isListeningForConfirmation = true
        speechRecognizerManager?.setListener(this)
        // Constrain confirmation grammar for high accuracy and fast response
        val confirmGrammar = listOf(
            "yes", "confirm", "call", "go ahead", "okay", "no", "cancel", "stop", "[unk]"
        )
        speechRecognizerManager?.startListening(grammar = confirmGrammar, timeoutMs = 6000)
    }

    private fun handleConfirmationTranscript(transcript: String) {
        isListeningForConfirmation = false
        val command = commandParser.parse(transcript)

        when (command) {
            is CommandParser.Command.Confirm -> {
                val currentState = _state.value
                if (currentState is AssistantState.AwaitingConfirmation) {
                    executeCall(currentState.contact, currentState.selectedNumber)
                }
            }
            is CommandParser.Command.Cancel -> {
                cancelCurrentInteraction("Call cancelled")
            }
            is CommandParser.Command.Repeat -> {
                val currentState = _state.value
                if (currentState is AssistantState.AwaitingConfirmation) {
                    audioFeedbackManager.speak("Do you want me to call ${currentState.contact.displayName}?") {
                        listenForConfirmation()
                    }
                }
            }
            else -> {
                audioFeedbackManager.speak("Please say yes to call, or cancel.") {
                    listenForConfirmation()
                }
            }
        }
    }

    private suspend fun onMultipleContactsMatched(query: String, candidates: List<ContactModel>) {
        _state.value = AssistantState.AmbiguousMatches(query, candidates)
        val voiceResponses = settingsRepository.voiceResponsesEnabled.first()
        val prompt = "I found ${candidates.size} contacts matching $query. Which one would you like to call?"
        audioFeedbackManager.speak(prompt, enabled = voiceResponses)
    }

    /**
     * User selected a candidate from the ambiguous matches list (via tap or voice).
     */
    fun selectAmbiguousCandidate(contact: ContactModel) {
        val number = contact.primaryOrFirstNumber
        if (number == null) {
            val msg = "${contact.displayName} has no phone number."
            _state.value = AssistantState.Error(msg)
            audioFeedbackManager.speak(msg)
            resumeStandbyAfterDelay(3000)
            return
        }

        scope.launch {
            onSingleContactMatched(contact, number, confidence = 1.0f)
        }
    }

    /**
     * Confirms the call when currently in AwaitingConfirmation state.
     */
    fun confirmCall() {
        val currentState = _state.value
        if (currentState is AssistantState.AwaitingConfirmation) {
            executeCall(currentState.contact, currentState.selectedNumber)
        }
    }

    /**
     * Cancels any active flow.
     */
    fun cancelCurrentInteraction(reason: String = "Cancelled") {
        isListeningForConfirmation = false
        speechRecognizerManager?.cancelListening()
        audioFeedbackManager.stop()
        audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.CANCEL_NACK)

        _state.value = AssistantState.Cancelled(reason)
        scope.launch {
            val voiceResponses = settingsRepository.voiceResponsesEnabled.first()
            audioFeedbackManager.speak(reason, enabled = voiceResponses)
            resumeStandbyAfterDelay(2500)
        }
    }

    private fun executeCall(contact: ContactModel, number: PhoneNumber) {
        scope.launch {
            val directCalling = settingsRepository.directCallingEnabled.first()
            val voiceResponses = settingsRepository.voiceResponsesEnabled.first()

            _state.value = AssistantState.InitiatingCall(contact, number, directCalling)

            audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.CONFIRMATION_ACK)
            audioFeedbackManager.speak("Calling ${contact.displayName}.", enabled = voiceResponses) {
                val result = callManager.placeCall(number, directCalling)
                when (result) {
                    is CallManager.CallResult.Success -> {
                        _state.value = AssistantState.CallInitiated(contact, number)
                        resumeStandbyAfterDelay(4000)
                    }
                    is CallManager.CallResult.PermissionDenied -> {
                        _state.value = AssistantState.PermissionRequired(
                            listOf(Manifest.permission.CALL_PHONE)
                        )
                    }
                    is CallManager.CallResult.NoDialerAvailable -> {
                        _state.value = AssistantState.Error("No phone dialer application found.")
                        resumeStandbyAfterDelay(3500)
                    }
                    is CallManager.CallResult.Error -> {
                        _state.value = AssistantState.Error("Call failed: ${result.message}")
                        resumeStandbyAfterDelay(3500)
                    }
                }
            }
        }
    }

    private fun resumeStandbyAfterDelay(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            if (_isAssistantActive.value) {
                val wakeWordEnabled = settingsRepository.wakeWordEnabled.first()
                if (wakeWordEnabled) {
                    startWakeWordListening()
                } else {
                    _state.value = AssistantState.Idle
                }
            } else {
                _state.value = AssistantState.Idle
            }
        }
    }

    /**
     * Cleans up all resources.
     */
    fun destroy() {
        stopAssistant()
        audioFeedbackManager.release()
        speechRecognizerManager?.destroy()
        speechRecognizerManager = null
        wakeWordDetector = null
    }
}
