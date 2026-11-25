package com.velocity.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.velocity.app.domain.RelayQuotaInfo
import com.velocity.app.ui.theme.LocalVelocitySemantic

/**
 * Clean, Pantone-aligned monthly egress budget indicator shared across all users of a relay.
 */
@Composable
fun RelayQuotaBar(
    quota: RelayQuotaInfo,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (!quota.hasValidLimit) return

    val semantic = LocalVelocitySemantic.current
    val animatedFraction by animateFloatAsState(
        targetValue = quota.fractionUsed,
        animationSpec = tween(durationMillis = 450),
    )

    val isWarning = quota.percentUsed >= 80.0
    val isCritical = quota.quotaExceeded || quota.percentUsed >= 95.0
    val barColor = when {
        isCritical -> semantic.errorInk
        isWarning -> semantic.warningInk
        else -> semantic.accent
    }

    val containerModifier = if (compact) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(semantic.card)
            .border(1.dp, semantic.cardBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    }

    Column(
        modifier = containerModifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (compact) "Monthly limit" else "${quota.provider} limit",
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = semantic.secondaryInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "${quota.usedFormatted()} / ${quota.limitFormatted()}",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = if (isCritical) semantic.errorInk else semantic.primaryInk,
                maxLines = 1,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 5.dp else 7.dp)
                .clip(CircleShape)
                .background(semantic.mutedSurface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedFraction.coerceAtLeast(0.02f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(barColor),
            )
        }

        val pct = ((quota.percentUsed * 10.0).toLong() / 10.0)
        val resetLabel = quota.resetDateLabel()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val leftNote = when {
                quota.quotaExceeded -> "Monthly limit reached · Forwarding paused"
                else -> "$pct% of shared pool used"
            }
            Text(
                text = leftNote,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = if (isCritical) semantic.errorInk else semantic.secondaryInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (resetLabel.isNotEmpty() || quota.activeSessions > 0) {
                val rightNote = buildString {
                    if (!compact && quota.activeSessions > 0) {
                        append("${quota.activeSessions} online")
                    }
                    if (resetLabel.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append("Resets $resetLabel")
                    }
                }
                Text(
                    text = rightNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = semantic.secondaryInk,
                    maxLines = 1,
                )
            }
        }
    }
}
