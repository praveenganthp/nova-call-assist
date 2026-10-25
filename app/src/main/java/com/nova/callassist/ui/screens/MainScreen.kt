package com.nova.callassist.ui.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.callassist.assistant.AssistantController
import com.nova.callassist.assistant.AssistantState
import com.nova.callassist.speech.SpeechModelManager
import com.nova.callassist.ui.components.AmbiguousCandidateRow
import com.nova.callassist.ui.components.ContactConfirmationCard
import com.nova.callassist.ui.components.MicPulseIndicator
import com.nova.callassist.ui.components.StatusBadge
import com.nova.callassist.ui.theme.AmberWarning
import com.nova.callassist.ui.theme.BlueAccent
import com.nova.callassist.ui.theme.BluePrimary
import com.nova.callassist.ui.theme.CardBackground
import com.nova.callassist.ui.theme.CardBorder
import com.nova.callassist.ui.theme.EmeraldGreen
import com.nova.callassist.ui.theme.Navy900
import com.nova.callassist.ui.theme.RoseError
import com.nova.callassist.ui.theme.TextPrimary
import com.nova.callassist.ui.theme.TextSecondary
import com.nova.callassist.ui.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    controller: AssistantController,
    onNavigateToSettings: () -> Unit,
    onRequestPermissions: (List<String>) -> Unit,
    onOpenAppSettings: () -> Unit
) {
    val state by controller.state.collectAsState()
    val transcript by controller.lastTranscript.collectAsState()
    val isAssistantActive by controller.isAssistantActive.collectAsState()
    val modelStatus by controller.modelManager.status.collectAsState()

    var manualTestCommand by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NOVA",
                            color = BlueAccent,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "• Offline Calling",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Navy900
                )
            )
        },
        containerColor = Navy900
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(10.dp))

                // 1. One-Tap Offline Speech Model Setup Card (for non-tech users)
                if (modelStatus !is SpeechModelManager.ModelStatus.Ready) {
                    ModelSetupBanner(
                        modelStatus = modelStatus,
                        onDownloadClick = { controller.triggerModelDownload() }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // 2. Permission Warning Card (handles RECORD_AUDIO, READ_CONTACTS, and CALL_PHONE)
                if (state is AssistantState.PermissionRequired) {
                    val missing = (state as AssistantState.PermissionRequired).missingPermissions
                    PermissionWarningCard(
                        missing = missing,
                        onRequest = { onRequestPermissions(missing) },
                        onOpenSettings = onOpenAppSettings
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // 3. Clear Visible Error / Unknown Contact Banner
                if (state is AssistantState.Error) {
                    val errorMsg = (state as AssistantState.Error).message
                    ErrorMessageCard(
                        message = errorMsg,
                        onDismiss = { controller.dismissError() }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Status Badge
                StatusBadge(state = state)

                Spacer(modifier = Modifier.height(20.dp))

                // Central Animated Mic Pulse
                MicPulseIndicator(
                    state = state,
                    onClick = {
                        if (state is AssistantState.ListeningForCommand) {
                            controller.stopAssistant()
                        } else {
                            controller.startListeningManually()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Instruction hint
                Text(
                    text = when (state) {
                        is AssistantState.ListeningForWakeWord -> "Say \"Hey Nova\" or tap the microphone"
                        is AssistantState.ListeningForCommand -> "Say \"Call [Contact Name]\""
                        is AssistantState.Transcribing -> "Transcribing your voice with Whisper…"
                        is AssistantState.ProcessingCommand -> "Processing: \"${(state as AssistantState.ProcessingCommand).transcript}\""
                        is AssistantState.AwaitingConfirmation -> "Say \"Yes\" to confirm, or \"Cancel\""
                        is AssistantState.AmbiguousMatches -> "Say the contact's full name or select below"
                        is AssistantState.Error -> "Check contact name or retry"
                        else -> "Tap the microphone to speak"
                    },
                    color = TextSecondary,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Live transcript card
                TranscriptCard(
                    transcript = transcript,
                    state = state
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Contact confirmation card if awaiting confirmation
            if (state is AssistantState.AwaitingConfirmation) {
                val confirming = state as AssistantState.AwaitingConfirmation
                item {
                    ContactConfirmationCard(
                        contact = confirming.contact,
                        number = confirming.selectedNumber,
                        onConfirm = { controller.confirmCall() },
                        onCancel = { controller.cancelCurrentInteraction("Cancelled by user") }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Ambiguous candidates list
            if (state is AssistantState.AmbiguousMatches) {
                val ambiguous = state as AssistantState.AmbiguousMatches
                item {
                    Text(
                        text = "Multiple contacts found. Select one:",
                        color = AmberWarning,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }
                itemsIndexed(ambiguous.candidates) { index, candidate ->
                    AmbiguousCandidateRow(
                        index = index + 1,
                        contact = candidate,
                        onSelect = { controller.selectAmbiguousCandidate(candidate) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item {
                    OutlinedButton(
                        onClick = { controller.cancelCurrentInteraction("Cancelled") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = "Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            item {
                // Assistant Service Control Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!isAssistantActive) {
                        Button(
                            onClick = { controller.startAssistant() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BluePrimary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Start",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Start Assistant", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = { controller.stopAssistant() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RoseError)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Stop Assistant", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    OutlinedButton(
                        onClick = { controller.startListeningManually() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = "Tap to Speak", color = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Manual Command Simulator / Tester
                CommandTestingInput(
                    value = manualTestCommand,
                    onValueChange = { manualTestCommand = it },
                    onSubmit = {
                        if (manualTestCommand.isNotBlank()) {
                            controller.onFinalResult(manualTestCommand)
                            manualTestCommand = ""
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Banner enabling non-technical users to download and set up the offline voice model with one tap.
 */
@Composable
private fun ModelSetupBanner(
    modelStatus: SpeechModelManager.ModelStatus,
    onDownloadClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x2238BDF8))
            .border(1.dp, BlueAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = BlueAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Offline Voice Recognition Setup",
                    color = BlueAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            when (modelStatus) {
                is SpeechModelManager.ModelStatus.Downloading -> {
                    Text(
                        text = "Downloading offline speech model: ${modelStatus.progressPercent}% (${modelStatus.bytesDownloaded / 1048576} MB / ${modelStatus.totalBytes / 1048576} MB)",
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (modelStatus.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = BlueAccent,
                        trackColor = CardBorder
                    )
                }
                else -> {
                    Text(
                        text = "Download the lightweight offline Whisper model (31 MB) once. After this download, Nova hears and dials 100% offline with zero internet forever.",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onDownloadClick,
                        colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Download Whisper Model (31 MB)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Dedicated visual error card when no contact matches or an error occurs.
 */
@Composable
private fun ErrorMessageCard(
    message: String,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x22EF4444))
            .border(1.dp, RoseError.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = RoseError,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "No Match Found",
                    color = RoseError,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionWarningCard(
    missing: List<String>,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val isCallPermissionOnly = missing.size == 1 && missing.contains(Manifest.permission.CALL_PHONE)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x22F59E0B))
            .border(1.dp, AmberWarning.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = AmberWarning,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isCallPermissionOnly) "Phone Call Permission Required" else "Permissions Required",
                    color = AmberWarning,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isCallPermissionOnly) {
                    "Direct Calling is enabled. Nova requires Phone Call permission (CALL_PHONE) to dial numbers directly without opening the dialer."
                } else {
                    "Nova needs microphone and contacts access to recognize voice commands and search your phonebook."
                },
                color = TextPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "Grant Permission", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder(true)
                ) {
                    Text(text = "Open App Settings")
                }
            }
        }
    }
}

@Composable
private fun TranscriptCard(
    transcript: String,
    state: AssistantState
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = "VOICE TRANSCRIPT",
                color = TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (transcript.isNotBlank()) {
                    "\"$transcript\""
                } else if (state is AssistantState.ListeningForCommand) {
                    "Listening for your command…"
                } else if (state is AssistantState.Transcribing) {
                    "Transcribing voice with Whisper…"
                } else {
                    "No recent voice command."
                },
                color = if (transcript.isNotBlank()) TextPrimary else TextSecondary,
                fontSize = 16.sp,
                fontWeight = if (transcript.isNotBlank()) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun CommandTestingInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column {
            Text(
                text = "TEST COMMAND (TYPED FALLBACK)",
                color = TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(text = "e.g. Call Arun, Cancel", color = TextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BlueAccent,
                        unfocusedBorderColor = CardBorder
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSubmit() })
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onSubmit,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BluePrimary)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Test",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
