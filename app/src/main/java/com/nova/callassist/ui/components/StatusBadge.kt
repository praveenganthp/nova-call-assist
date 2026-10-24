package com.nova.callassist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.callassist.assistant.AssistantState
import com.nova.callassist.ui.theme.AmberWarning
import com.nova.callassist.ui.theme.BlueAccent
import com.nova.callassist.ui.theme.EmeraldGreen
import com.nova.callassist.ui.theme.RoseError
import com.nova.callassist.ui.theme.TextSecondary

@Composable
fun StatusBadge(
    state: AssistantState,
    modifier: Modifier = Modifier
) {
    val (label, indicatorColor, bgTint) = when (state) {
        is AssistantState.Idle -> Triple("Standby", TextSecondary, Color(0x2294A3B8))
        is AssistantState.ListeningForWakeWord -> Triple("Listening for \"Hey Nova\"", BlueAccent, Color(0x2238BDF8))
        is AssistantState.ListeningForCommand -> Triple("Listening…", EmeraldGreen, Color(0x2210B981))
        is AssistantState.MatchingContact -> Triple("Finding Contact…", BlueAccent, Color(0x2238BDF8))
        is AssistantState.AwaitingConfirmation -> Triple("Confirmation Needed", AmberWarning, Color(0x22F59E0B))
        is AssistantState.AmbiguousMatches -> Triple("Multiple Matches", AmberWarning, Color(0x22F59E0B))
        is AssistantState.InitiatingCall -> Triple("Calling…", EmeraldGreen, Color(0x2210B981))
        is AssistantState.CallInitiated -> Triple("Call Placed", EmeraldGreen, Color(0x2210B981))
        is AssistantState.Cancelled -> Triple("Cancelled", TextSecondary, Color(0x2294A3B8))
        is AssistantState.PermissionRequired -> Triple("Permissions Needed", AmberWarning, Color(0x22F59E0B))
        is AssistantState.Error -> Triple("Error", RoseError, Color(0x22EF4444))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgTint)
            .border(1.dp, indicatorColor.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = indicatorColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
