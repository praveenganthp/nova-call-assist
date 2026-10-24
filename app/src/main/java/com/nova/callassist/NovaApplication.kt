package com.nova.callassist

import android.app.Application
import com.nova.callassist.audio.AudioFeedbackManager
import com.nova.callassist.calling.CallManager
import com.nova.callassist.contacts.ContactsRepository
import com.nova.callassist.settings.SettingsRepository
import com.nova.callassist.speech.SpeechModelManager
import com.nova.callassist.assistant.AssistantController

/**
 * Application class for Nova. Initializes core dependencies without heavy DI frameworks.
 */
class NovaApplication : Application() {

    lateinit var contactsRepository: ContactsRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var callManager: CallManager
        private set

    lateinit var audioFeedbackManager: AudioFeedbackManager
        private set

    lateinit var speechModelManager: SpeechModelManager
        private set

    lateinit var assistantController: AssistantController
        private set

    override fun onCreate() {
        super.onCreate()

        contactsRepository = ContactsRepository(this)
        settingsRepository = SettingsRepository(this)
        callManager = CallManager(this)
        audioFeedbackManager = AudioFeedbackManager(this)
        speechModelManager = SpeechModelManager(this)

        assistantController = AssistantController(
            context = this,
            contactsRepository = contactsRepository,
            settingsRepository = settingsRepository,
            callManager = callManager,
            audioFeedbackManager = audioFeedbackManager,
            modelManager = speechModelManager
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        assistantController.destroy()
    }
}
