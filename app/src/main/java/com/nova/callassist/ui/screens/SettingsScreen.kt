package com.nova.callassist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.callassist.settings.SettingsRepository
import com.nova.callassist.speech.SpeechModelManager
import com.nova.callassist.ui.theme.AmberWarning
import com.nova.callassist.ui.theme.BlueAccent
import com.nova.callassist.ui.theme.BluePrimary
import com.nova.callassist.ui.theme.CardBackground
import com.nova.callassist.ui.theme.CardBorder
import com.nova.callassist.ui.theme.EmeraldGreen
import com.nova.callassist.ui.theme.Navy900
import com.nova.callassist.ui.theme.TextPrimary
import com.nova.callassist.ui.theme.TextSecondary
import com.nova.callassist.ui.theme.TextTertiary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    modelManager: SpeechModelManager,
    onRequestCallPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    val wakeWordEnabled by settingsRepository.wakeWordEnabled.collectAsState(initial = false)
    val directCallingEnabled by settingsRepository.directCallingEnabled.collectAsState(initial = false)
    val voiceResponsesEnabled by settingsRepository.voiceResponsesEnabled.collectAsState(initial = true)
    val confirmationRequired by settingsRepository.confirmationRequired.collectAsState(initial = true)
    val modelStatus by modelManager.status.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
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
                .padding(horizontal = 20.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(10.dp))

                SectionTitle(title = "VOICE ASSISTANT")

                SettingToggleCard(
                    title = "Wake-Word Detection (\"Hey Nova\")",
                    description = "Continuously listens locally for the wake word using Vosk keyword spotting. Does not transmit audio to any server.",
                    checked = wakeWordEnabled,
                    onCheckedChange = { coroutineScope.launch { settingsRepository.setWakeWordEnabled(it) } }
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleCard(
                    title = "Spoken Voice Responses",
                    description = "Nova speaks audible confirmations and prompts using Android's offline Text-To-Speech engine.",
                    checked = voiceResponsesEnabled,
                    onCheckedChange = { coroutineScope.launch { settingsRepository.setVoiceResponsesEnabled(it) } }
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleCard(
                    title = "Require Call Confirmation",
                    description = "Asks for explicit verbal or tap confirmation before placing any phone call (strongly recommended).",
                    checked = confirmationRequired,
                    onCheckedChange = { coroutineScope.launch { settingsRepository.setConfirmationRequired(it) } }
                )

                Spacer(modifier = Modifier.height(24.dp))

                SectionTitle(title = "CALLING BEHAVIOR")

                SettingToggleCard(
                    title = "Direct Calling (ACTION_CALL)",
                    description = if (directCallingEnabled) {
                        "Direct calling is ENABLED. Calls will be placed immediately via ACTION_CALL after confirmation."
                    } else {
                        "Safe default: Uses ACTION_DIAL to open the dialer with the number pre-filled. No CALL_PHONE permission required."
                    },
                    checked = directCallingEnabled,
                    onCheckedChange = { enabled ->
                        coroutineScope.launch {
                            settingsRepository.setDirectCallingEnabled(enabled)
                            if (enabled) {
                                onRequestCallPermission()
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                SectionTitle(title = "OFFLINE SPEECH MODEL")

                ModelStatusCard(
                    modelStatus = modelStatus,
                    onDownloadClick = {
                        coroutineScope.launch {
                            modelManager.downloadAndInstallModel()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                SectionTitle(title = "BACKGROUND & BATTERY GUIDANCE")

                BatteryGuidanceCard()

                Spacer(modifier = Modifier.height(24.dp))

                SectionTitle(title = "PRIVACY & OPEN SOURCE")

                PrivacyAssuranceCard()

                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = TextTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = BluePrimary,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = CardBorder
                )
            )
        }
    }
}

@Composable
private fun ModelStatusCard(
    modelStatus: SpeechModelManager.ModelStatus,
    onDownloadClick: () -> Unit
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = BlueAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Acoustic Model Status",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            when (modelStatus) {
                is SpeechModelManager.ModelStatus.Ready -> {
                    Text(
                        text = "Model: vosk-model-small-en-us-0.15 (Ready)",
                        color = EmeraldGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Path: ${modelStatus.modelPath}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                is SpeechModelManager.ModelStatus.Checking -> {
                    Text(text = "Checking offline model status…", color = TextSecondary, fontSize = 13.sp)
                }
                is SpeechModelManager.ModelStatus.Downloading -> {
                    Text(
                        text = "Downloading offline speech model: ${modelStatus.progressPercent}% (${modelStatus.bytesDownloaded / 1048576} MB / ${modelStatus.totalBytes / 1048576} MB)",
                        color = BlueAccent,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (modelStatus.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = BlueAccent,
                        trackColor = CardBorder
                    )
                }
                is SpeechModelManager.ModelStatus.Unpacking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = BlueAccent,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Unpacking offline model into app storage…",
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                    }
                }
                is SpeechModelManager.ModelStatus.NotInstalled, is SpeechModelManager.ModelStatus.Error -> {
                    Text(
                        text = if (modelStatus is SpeechModelManager.ModelStatus.Error) {
                            "Setup error: ${modelStatus.message}"
                        } else {
                            "Model not yet installed."
                        },
                        color = AmberWarning,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap below to automatically download the official lightweight model (40 MB) directly onto your phone.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onDownloadClick,
                        colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Download Model (40 MB)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryGuidanceCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BatteryAlert,
                    contentDescription = null,
                    tint = AmberWarning,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Android System & Battery Restrictions",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Foreground Service: Required on Android 8+ to keep wake-word listening alive.\n" +
                        "• Android 14+ Restrictions: Continuous microphone capture from background is heavily restricted when the app is not in the foreground. If background wake-word stops, open Nova or tap the notification.\n" +
                        "• Battery Optimization: Exclude Nova from OS aggressive battery saver to prevent unexpected process killing.\n" +
                        "• OEM Task Killers: Devices from Xiaomi/Samsung may terminate background services unless autostart is permitted.",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun PrivacyAssuranceCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = EmeraldGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "100% Offline & Private",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Zero internet requests for speech or contacts: Audio and names never leave your device.\n" +
                        "• One-time model download: Once the voice model is downloaded, Nova works with zero internet forever.\n" +
                        "• Local matching: All name searches run purely in-memory on your phone.\n" +
                        "• Permissive License: Vosk speech engine is open source under Apache 2.0.\n" +
                        "• Version 1.0.0",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}
