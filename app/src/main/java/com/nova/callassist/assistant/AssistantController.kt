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
import com.nova.callassist.speech.AudioCaptureCoordinator
import com.nova.callassist.speech.SpeechModelManager
import com.nova.callassist.speech.SpeechRecognitionListener
import com.nova.callassist.speech.SpeechRecognizerManager
import com.nova.callassist.speech.TranscriptionMetrics
import com.nova.callassist.speech.WakeWordDetector
import com.nova.callassist.speech.WhisperModelOption
import com.nova.callassist.speech.WhisperSpeechRecognizerManager
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

    private val _lastMetrics = MutableStateFlow<TranscriptionMetrics?>(null)
    val lastMetrics: StateFlow<TranscriptionMetrics?> = _lastMetrics.asStateFlow()

    private val _diagnostics = MutableStateFlow(AssistantDiagnostics())
    val diagnostics: StateFlow<AssistantDiagnostics> = _diagnostics.asStateFlow()

    val audioCaptureCoordinator = AudioCaptureCoordinator()

    private val commandParser = CommandParser()
    private val contactMatcher = ContactMatcher()

    private var speechRecognizerManager: WhisperSpeechRecognizerManager? = null
    private var wakeWordDetector: WakeWordDetector? = null

    private var isListeningForConfirmation = false
    private var pendingCall: Pair<ContactModel, PhoneNumber>? = null
    private var wakeWordTriggerCount = 0
    private var lastWakeTimestamp = 0L

    init {
        scope.launch {
            val prefModelId = settingsRepository.selectedModelId.first()
            modelManager.initModel(prefModelId)
            updateDiagnostics()
        }
        scope.launch {
            audioCaptureCoordinator.telemetry.collect { tel ->
                _diagnostics.value = _diagnostics.value.copy(
                    currentRms = tel.currentRms,
                    ambientNoiseFloor = tel.ambientNoiseFloor,
                    dynamicSpeechThreshold = tel.dynamicThreshold,
                    speechOnsetDetected = tel.speechOnsetDetected,
                    audioRecordStatus = tel.statusMessage
                )
            }
        }
    }

    private fun updateDiagnostics() {
        val m = _lastMetrics.value
        _diagnostics.value = _diagnostics.value.copy(
            lastAudioDurationSec = m?.audioDurationSeconds ?: 0f,
            lastInferenceDurationMs = m?.durationMs ?: 0L,
            lastRealTimeFactor = m?.realTimeFactor ?: 0f,
            activeModelName = modelManager.getActiveModelOption().displayName,
            lastTranscript = m?.transcript ?: _lastTranscript.value,
            wakeWordTriggerCount = wakeWordTriggerCount,
            lastWakeTimestamp = lastWakeTimestamp
        )
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
            audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.WAKE_WORD_DETECTED)
            startCommandListening(prompt = null)
        }
    }

    /**
     * Downloads and initializes the offline speech model for non-technical users.
     */
    fun triggerModelDownload(option: WhisperModelOption = WhisperModelOption.DEFAULT) {
        scope.launch {
            val result = modelManager.downloadAndInstallModel(option)
            if (result.isSuccess) {
                settingsRepository.setSelectedModelId(option.id)
                val threads = settingsRepository.cpuThreads.first()
                initRecognizerManager(threads)
                updateDiagnostics()
                audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.CONFIRMATION_ACK)
                audioFeedbackManager.speak("${option.displayName} is ready.")
                startAssistant()
            } else {
                audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.CANCEL_NACK)
                _state.value = AssistantState.Error(
                    "Model download failed: ${result.exceptionOrNull()?.message ?: "Network error"}"
                )
            }
        }
    }

    /**
     * Stops wake-word spotting while keeping manual listening available.
     */
    fun stopWakeWord() {
        wakeWordDetector?.stopSpotting()
        if (_state.value is AssistantState.ListeningForWakeWord) {
            _state.value = AssistantState.Idle
        }
    }

    /**
     * Switches to an already-installed Whisper model safely under mutex lock.
     */
    fun switchModel(option: WhisperModelOption) {
        scope.launch {
            val wasActive = _isAssistantActive.value
            if (wasActive) {
                wakeWordDetector?.stopSpotting()
                speechRecognizerManager?.stopListening()
            }

            val result = modelManager.selectAndLoadModel(option)
            if (result.isSuccess) {
                settingsRepository.setSelectedModelId(option.id)
                val threads = settingsRepository.cpuThreads.first()
                initRecognizerManager(threads)
                updateDiagnostics()
                audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.CONFIRMATION_ACK)
                if (wasActive) {
                    val wakeWordEnabled = settingsRepository.wakeWordEnabled.first()
                    if (wakeWordEnabled) {
                        startWakeWordListening()
                    }
                }
            } else {
                audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.CANCEL_NACK)
                _state.value = AssistantState.Error(
                    "Failed to switch model: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                )
            }
        }
    }

    private fun initRecognizerManager(threads: Int) {
        val recognizer = WhisperSpeechRecognizerManager(
            engine = modelManager.speechEngine,
            audioCaptureCoordinator = audioCaptureCoordinator,
            threadCount = threads
        )
        speechRecognizerManager = recognizer
        scope.launch {
            recognizer.lastMetrics.collect { m ->
                if (m != null) {
                    _lastMetrics.value = m
                    updateDiagnostics()
                }
            }
        }
    }

    private fun ensureSpeechEngineReady(onReady: () -> Unit) {
        if (modelManager.isReady()) {
            scope.launch {
                val threads = settingsRepository.cpuThreads.first()
                if (speechRecognizerManager == null) {
                    initRecognizerManager(threads)
                }
                onReady()
            }
        } else {
            scope.launch {
                val status = modelManager.initModel()
                if (status is SpeechModelManager.ModelStatus.Ready) {
                    val threads = settingsRepository.cpuThreads.first()
                    initRecognizerManager(threads)
                    onReady()
                } else {
                    _state.value = AssistantState.Error(
                        "Offline speech model is not ready. Please tap 'Download Model' above."
                    )
                }
            }
        }
    }

    private fun startWakeWordListening() {
        wakeWordDetector?.stopSpotting()
        if (!modelManager.isReady()) return

        wakeWordDetector = WakeWordDetector(
            engine = modelManager.speechEngine,
            audioCaptureCoordinator = audioCaptureCoordinator,
            isTtsSpeakingProvider = { audioFeedbackManager.isSpeaking() }
        ) { transcript ->
            scope.launch {
                onWakeWordDetected(transcript)
            }
        }
        _state.value = AssistantState.ListeningForWakeWord
        wakeWordDetector?.startSpotting()
    }

    private fun onWakeWordDetected(transcript: String? = null) {
        wakeWordTriggerCount++
        lastWakeTimestamp = System.currentTimeMillis()
        updateDiagnostics()
        audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.WAKE_WORD_DETECTED)

        val parsed = transcript?.let { commandParser.parse(it) }
        if (parsed is CommandParser.Command.Call) {
            _state.value = AssistantState.ProcessingCommand(transcript)
            handleCommandTranscript(transcript)
        } else {
            // User said "Hey Nova" -> greet and ask who to call!
            startCommandListening(prompt = "Hey! Who would you like to call?")
        }
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
        scope.launch {
            val threads = settingsRepository.cpuThreads.first()
            if (speechRecognizerManager == null) {
                initRecognizerManager(threads)
            }
            speechRecognizerManager?.setListener(this@AssistantController)
            speechRecognizerManager?.startListening(timeoutMs = 12000)
        }
    }

    override fun onPartialResult(partialText: String) {
        _lastTranscript.value = partialText
        if (partialText.contains("Transcribing", ignoreCase = true)) {
            _state.value = AssistantState.Transcribing(_lastMetrics.value?.audioDurationSeconds ?: 0f)
        } else if (_state.value is AssistantState.ListeningForCommand) {
            _state.value = AssistantState.ListeningForCommand(partialText)
        }
    }

    override fun onFinalResult(text: String) {
        _lastTranscript.value = text
        _state.value = AssistantState.ProcessingCommand(text)
        speechRecognizerManager?.stopListening()

        if (isListeningForConfirmation) {
            handleConfirmationTranscript(text)
        } else {
            handleCommandTranscript(text)
        }
    }

    override fun onError(errorMessage: String) {
        _diagnostics.value = _diagnostics.value.copy(lastError = errorMessage)
        audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.CANCEL_NACK)
        _state.value = AssistantState.Error(errorMessage)
        resumeStandbyAfterDelay(3500)
    }

    override fun onTimeout() {
        if (isListeningForConfirmation) {
            cancelCurrentInteraction("Confirmation timed out")
        } else if (_state.value is AssistantState.ListeningForCommand || _state.value is AssistantState.Transcribing) {
            cancelCurrentInteraction("No command heard")
        }
    }

    private fun handleCommandTranscript(transcript: String) {
        val command = commandParser.parse(transcript)

        when (command) {
            is CommandParser.Command.Call -> {
                processCallTarget(targetName = command.targetName, rawTranscript = transcript)
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
                processCallTarget(targetName = transcript, rawTranscript = transcript)
            }
        }
    }

    private fun processCallTarget(targetName: String, rawTranscript: String = targetName) {
        _state.value = AssistantState.MatchingContact(targetName)

        val matchStart = System.currentTimeMillis()
        scope.launch {
            val contacts = contactsRepository.loadContacts()
            val result = contactMatcher.match(targetName, contacts)
            val matchDuration = System.currentTimeMillis() - matchStart

            // Privacy-preserving debug logging: log raw & parsed target, match result, and latency (no phone numbers or address book dump)
            android.util.Log.d(
                "AssistantController",
                "[MATCH] raw=\"$rawTranscript\", target=\"$targetName\", contactsCount=${contacts.size}, " +
                        "result=${result::class.simpleName}, matchDurationMs=$matchDuration"
            )

            val inferenceMs = _lastMetrics.value?.durationMs ?: 0L
            _diagnostics.value = _diagnostics.value.copy(
                contactMatchDurationMs = matchDuration,
                totalLatencyMs = inferenceMs + matchDuration
            )

            when (result) {
                is MatchResult.Single -> {
                    onSingleContactMatched(result.contact, result.selectedNumber, result.confidence)
                }
                is MatchResult.Ambiguous -> {
                    onMultipleContactsMatched(result.query, result.candidates)
                }
                is MatchResult.NoPhoneNumber -> {
                    audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.CANCEL_NACK)
                    val msg = "${result.contact.displayName} has no phone number."
                    _state.value = AssistantState.Error(msg)
                    audioFeedbackManager.speak(msg)
                    resumeStandbyAfterDelay(4500)
                }
                is MatchResult.NoMatch -> {
                    // Play negative chime to make it unmistakably clear no match was found
                    audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.CANCEL_NACK)
                    val spokenMsg = "I couldn't find a contact named $targetName."
                    val uiMsg = "No contact found matching \"$targetName\" (Recognized: \"$rawTranscript\"). Check your contacts list or spelling, or choose manually."
                    _state.value = AssistantState.Error(uiMsg)
                    audioFeedbackManager.speak(spokenMsg)
                    resumeStandbyAfterDelay(4500)
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
        scope.launch {
            val threads = settingsRepository.cpuThreads.first()
            if (speechRecognizerManager == null) {
                initRecognizerManager(threads)
            }
            speechRecognizerManager?.setListener(this@AssistantController)
            speechRecognizerManager?.startListening(timeoutMs = 6000)
        }
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
            audioFeedbackManager.playChime(AudioFeedbackManager.ToneType.CANCEL_NACK)
            val msg = "${contact.displayName} has no phone number."
            _state.value = AssistantState.Error(msg)
            audioFeedbackManager.speak(msg)
            resumeStandbyAfterDelay(3500)
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
     * Dismisses the active error message and returns to idle.
     */
    fun dismissError() {
        if (_state.value is AssistantState.Error) {
            _state.value = AssistantState.Idle
        }
    }

    /**
     * Called by MainActivity when a required permission is granted by the user.
     */
    fun onPermissionGranted(permission: String) {
        if (permission == Manifest.permission.CALL_PHONE) {
            val pending = pendingCall
            if (pending != null) {
                pendingCall = null
                executeCall(pending.first, pending.second)
                return
            }
        }
        val missing = checkMissingPermissions()
        if (missing.isEmpty()) {
            if (_state.value is AssistantState.PermissionRequired) {
                _state.value = AssistantState.Idle
            }
        } else {
            _state.value = AssistantState.PermissionRequired(missing)
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
                        pendingCall = null
                        _state.value = AssistantState.CallInitiated(contact, number)
                        resumeStandbyAfterDelay(4000)
                    }
                    is CallManager.CallResult.PermissionDenied -> {
                        // Remember pending call so granting permission executes the call immediately
                        pendingCall = Pair(contact, number)
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
        audioCaptureCoordinator.cancelCapture()
        speechRecognizerManager?.destroy()
        speechRecognizerManager = null
        wakeWordDetector?.destroy()
        wakeWordDetector = null
    }
}
