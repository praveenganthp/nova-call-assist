package com.nova.callassist

import com.nova.callassist.assistant.AssistantState
import com.nova.callassist.contacts.ContactModel
import com.nova.callassist.contacts.PhoneNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStateMachineTest {

    @Test
    fun testIdleToListeningTransition() {
        var state: AssistantState = AssistantState.Idle
        assertEquals(AssistantState.Idle, state)

        state = AssistantState.ListeningForCommand(partialTranscript = "call")
        assertTrue(state is AssistantState.ListeningForCommand)
        assertEquals("call", (state as AssistantState.ListeningForCommand).partialTranscript)
    }

    @Test
    fun testMatchingContactTransition() {
        val state: AssistantState = AssistantState.MatchingContact(spokenQuery = "Arun")
        assertTrue(state is AssistantState.MatchingContact)
        assertEquals("Arun", (state as AssistantState.MatchingContact).spokenQuery)
    }

    @Test
    fun testAwaitingConfirmationState() {
        val contact = ContactModel(
            id = "1",
            lookupKey = "key_1",
            displayName = "Arun Kumar",
            phoneNumbers = listOf(PhoneNumber("+919876543210", "Mobile"))
        )
        val state: AssistantState = AssistantState.AwaitingConfirmation(
            contact = contact,
            selectedNumber = contact.phoneNumbers.first(),
            confidence = 1.0f
        )

        assertTrue(state is AssistantState.AwaitingConfirmation)
        val awaiting = state as AssistantState.AwaitingConfirmation
        assertEquals("Arun Kumar", awaiting.contact.displayName)
        assertEquals("+919876543210", awaiting.selectedNumber.number)
    }

    @Test
    fun testAmbiguousMatchesState() {
        val candidates = listOf(
            ContactModel("1", "k1", "Arun Kumar", emptyList()),
            ContactModel("2", "k2", "Arun Prakash", emptyList())
        )
        val state: AssistantState = AssistantState.AmbiguousMatches(
            query = "Arun",
            candidates = candidates
        )

        assertTrue(state is AssistantState.AmbiguousMatches)
        assertEquals(2, (state as AssistantState.AmbiguousMatches).candidates.size)
    }

    @Test
    fun testInitiatingCallState() {
        val contact = ContactModel("1", "k1", "Arun", listOf(PhoneNumber("1234567890", "Mobile")))
        val state: AssistantState = AssistantState.InitiatingCall(
            contact = contact,
            number = contact.phoneNumbers.first(),
            isDirectCall = false
        )

        assertTrue(state is AssistantState.InitiatingCall)
        val init = state as AssistantState.InitiatingCall
        assertEquals(false, init.isDirectCall)
    }

    @Test
    fun testCancellationState() {
        val state: AssistantState = AssistantState.Cancelled(reason = "User said cancel")
        assertTrue(state is AssistantState.Cancelled)
        assertEquals("User said cancel", (state as AssistantState.Cancelled).reason)
    }

    @Test
    fun testErrorState() {
        val state: AssistantState = AssistantState.Error(message = "Microphone unavailable")
        assertTrue(state is AssistantState.Error)
        assertEquals("Microphone unavailable", (state as AssistantState.Error).message)
    }

    @Test
    fun testPermissionRequiredState() {
        val state: AssistantState = AssistantState.PermissionRequired(
            missingPermissions = listOf("android.permission.RECORD_AUDIO")
        )
        assertTrue(state is AssistantState.PermissionRequired)
        assertEquals(1, (state as AssistantState.PermissionRequired).missingPermissions.size)
    }

    @Test
    fun testCompleteStateProgression() {
        // IDLE -> LISTENING_FOR_COMMAND -> TRANSCRIBING -> PROCESSING_COMMAND -> AWAITING_CONFIRMATION
        var state: AssistantState = AssistantState.Idle
        assertEquals(AssistantState.Idle, state)

        state = AssistantState.ListeningForCommand(partialTranscript = "Hearing speech…")
        assertTrue(state is AssistantState.ListeningForCommand)

        state = AssistantState.Transcribing(audioDurationSeconds = 2.4f)
        assertTrue(state is AssistantState.Transcribing)
        assertEquals(2.4f, (state as AssistantState.Transcribing).audioDurationSeconds, 0.01f)

        state = AssistantState.ProcessingCommand(transcript = "Call Arun")
        assertTrue(state is AssistantState.ProcessingCommand)
        assertEquals("Call Arun", (state as AssistantState.ProcessingCommand).transcript)

        val contact = ContactModel("1", "k1", "Arun Kumar", listOf(PhoneNumber("+919876543210", "Mobile")))
        state = AssistantState.AwaitingConfirmation(contact, contact.phoneNumbers.first(), 0.95f)
        assertTrue(state is AssistantState.AwaitingConfirmation)
    }
}
