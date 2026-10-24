package com.nova.callassist

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nova.callassist.assistant.AssistantService
import com.nova.callassist.ui.screens.MainScreen
import com.nova.callassist.ui.screens.SettingsScreen
import com.nova.callassist.ui.theme.NovaTheme

enum class Screen {
    Main,
    Settings
}

class MainActivity : ComponentActivity() {

    private lateinit var app: NovaApplication

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val contactsGranted = permissions[Manifest.permission.READ_CONTACTS] == true
        val callGranted = permissions[Manifest.permission.CALL_PHONE] == true

        if (audioGranted || contactsGranted) {
            app.contactsRepository.invalidateCache()
        }

        permissions.forEach { (perm, granted) ->
            if (granted) {
                app.assistantController.onPermissionGranted(perm)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as NovaApplication

        handleIntent(intent)
        requestRequiredPermissions()

        setContent {
            NovaTheme {
                var currentScreen by remember { mutableStateOf(Screen.Main) }

                when (currentScreen) {
                    Screen.Main -> {
                        MainScreen(
                            controller = app.assistantController,
                            onNavigateToSettings = { currentScreen = Screen.Settings },
                            onRequestPermissions = { missing -> requestRequiredPermissions(missing) },
                            onOpenAppSettings = { openAppSettings() }
                        )
                    }
                    Screen.Settings -> {
                        SettingsScreen(
                            settingsRepository = app.settingsRepository,
                            modelManager = app.speechModelManager,
                            controller = app.assistantController,
                            onRequestCallPermission = {
                                permissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
                            },
                            onOpenAppSettings = { openAppSettings() },
                            onNavigateBack = { currentScreen = Screen.Main }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            AssistantService.ACTION_LISTEN_NOW,
            Intent.ACTION_VOICE_COMMAND -> {
                app.assistantController.startListeningManually()
            }
        }
    }

    /**
     * Requests required permissions. If a specific list of missing permissions is provided,
     * requests those; otherwise requests standard runtime permissions.
     */
    fun requestRequiredPermissions(specificPermissions: List<String>? = null) {
        if (!specificPermissions.isNullOrEmpty()) {
            permissionLauncher.launch(specificPermissions.toTypedArray())
            return
        }

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    /**
     * Opens the device App Info Settings screen so users can manually grant
     * permissions if the OS is blocking dialog prompts.
     */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }
}
