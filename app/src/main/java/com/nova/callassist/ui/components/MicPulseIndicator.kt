package com.nova.callassist.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nova.callassist.assistant.AssistantState
import com.nova.callassist.ui.theme.AmberWarning
import com.nova.callassist.ui.theme.BlueAccent
import com.nova.callassist.ui.theme.BluePrimary
import com.nova.callassist.ui.theme.EmeraldGreen
import com.nova.callassist.ui.theme.RoseError
import com.nova.callassist.ui.theme.TextPrimary

@Composable
fun MicPulseIndicator(
    state: AssistantState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPulsing = state is AssistantState.ListeningForCommand ||
            state is AssistantState.ListeningForWakeWord ||
            state is AssistantState.AwaitingConfirmation ||
            state is AssistantState.InitiatingCall

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale1 by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isPulsing) 1.28f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state is AssistantState.ListeningForCommand) 900 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "outerScale"
    )

    val scale2 by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isPulsing) 1.15f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state is AssistantState.ListeningForCommand) 900 else 1800, delayMillis = 150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "innerScale"
    )

    val (primaryColor, accentColor) = when (state) {
        is AssistantState.ListeningForCommand -> Pair(EmeraldGreen, Color(0xFF34D399))
        is AssistantState.ListeningForWakeWord -> Pair(BluePrimary, BlueAccent)
        is AssistantState.AwaitingConfirmation, is AssistantState.AmbiguousMatches -> Pair(AmberWarning, Color(0xFFFBBF24))
        is AssistantState.InitiatingCall, is AssistantState.CallInitiated -> Pair(EmeraldGreen, Color(0xFF6EE7B7))
        is AssistantState.Error -> Pair(RoseError, Color(0xFFF87171))
        else -> Pair(BluePrimary, BlueAccent)
    }

    val icon = when (state) {
        is AssistantState.ListeningForCommand -> Icons.Default.Mic
        is AssistantState.InitiatingCall, is AssistantState.CallInitiated -> Icons.Default.PhoneInTalk
        is AssistantState.Error, is AssistantState.PermissionRequired -> Icons.Default.Warning
        else -> Icons.Default.MicNone
    }

    Box(
        modifier = modifier.size(200.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing ring
        if (isPulsing) {
            Box(
                modifier = Modifier
                    .size(170.dp)
                    .scale(scale1)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f))
            )

            // Inner pulsing ring
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .scale(scale2)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.25f))
            )
        }

        // Center mic button
        Box(
            modifier = Modifier
                .size(105.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(accentColor, primaryColor)
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Microphone Button",
                tint = TextPrimary,
                modifier = Modifier.size(46.dp)
            )
        }
    }
}
