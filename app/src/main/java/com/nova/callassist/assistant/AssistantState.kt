package com.nova.callassist.assistant

import com.nova.callassist.contacts.ContactModel
import com.nova.callassist.contacts.PhoneNumber

/**
 * State machine representing all operational phases of Nova Voice Assistant.
 */
sealed class AssistantState {

    /**
     * Assistant is inactive / standby.
     */
    object Idle : AssistantState()

    /**
     * Continuous offline keyword spotting active, listening for "Hey Nova".
     */
    object ListeningForWakeWord : AssistantState()

    /**
     * Active listening for a spoken user command.
     */
    data class ListeningForCommand(
        val partialTranscript: String = ""
    ) : AssistantState()

    /**
     * Speech recording has completed; on-device Whisper neural inference is executing.
     */
    data class Transcribing(
        val audioDurationSeconds: Float = 0f
    ) : AssistantState()

    /**
     * Speech transcribed; parsing spoken intent and resolving actions.
     */
    data class ProcessingCommand(
        val transcript: String
    ) : AssistantState()

    /**
     * Processing a recognized speech command and matching against contacts.
     */
    data class MatchingContact(
        val spokenQuery: String
    ) : AssistantState()

    /**
     * A clear contact match was found. Waiting for verbal or manual user confirmation.
     */
    data class AwaitingConfirmation(
        val contact: ContactModel,
        val selectedNumber: PhoneNumber,
        val confidence: Float
    ) : AssistantState()

    /**
     * Multiple plausible matches were found. User must pick the intended contact.
     */
    data class AmbiguousMatches(
        val query: String,
        val candidates: List<ContactModel>
    ) : AssistantState()

    /**
     * User confirmed the call; intent is being prepared and dispatched.
     */
    data class InitiatingCall(
        val contact: ContactModel,
        val number: PhoneNumber,
        val isDirectCall: Boolean
    ) : AssistantState()

    /**
     * Call intent dispatched successfully to the system dialer or telecom.
     */
    data class CallInitiated(
        val contact: ContactModel,
        val number: PhoneNumber
    ) : AssistantState()

    /**
     * The interaction was cancelled by user command, timeout, or dismissal.
     */
    data class Cancelled(
        val reason: String = "Interaction cancelled"
    ) : AssistantState()

    /**
     * One or more required runtime permissions are missing.
     */
    data class PermissionRequired(
        val missingPermissions: List<String>
    ) : AssistantState()

    /**
     * An error occurred (e.g. mic in use, model missing, no phone number found).
     */
    data class Error(
        val message: String
    ) : AssistantState()
}
