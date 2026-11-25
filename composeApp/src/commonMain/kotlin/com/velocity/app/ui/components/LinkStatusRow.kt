package com.velocity.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.*
import com.velocity.app.platform.LiquidGlassSurface
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.LocalVelocitySemantic

/**
 * Clean human-readable link status pills for Wi-Fi and Cellular.
 * Displays real connectivity readiness ("Internet ready", "Connected · No internet",
 * "Sign in required", "Off") and live Mbps only when actively transferring.
 */
@Composable
fun LinkStatusRow(
    networkHealth: NetworkHealthSnapshot,
    telemetry: CombinedTelemetry,
    connectionState: ConnectionState,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalVelocitySemantic.current
    val a11y = LocalVelocityA11y.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinkPillCard(
                health = networkHealth.wifi,
                liveTelemetry = telemetry.wifi,
                isBonded = connectionState == ConnectionState.BONDED,
                accentColor = semantic.wifiTint,
                tintBg = semantic.wifiBg,
                modifier = Modifier.weight(1f),
            )
            LinkPillCard(
                health = networkHealth.cellular,
                liveTelemetry = telemetry.cellular,
                isBonded = connectionState == ConnectionState.BONDED,
                accentColor = semantic.cellTint,
                tintBg = semantic.cellBg,
                modifier = Modifier.weight(1f),
            )
        }

        AnimatedVisibility(
            visible = networkHealth.advisoryMessage != null,
            enter = fadeIn(tween(220)) + expandVertically(),
            exit = fadeOut(tween(180)) + shrinkVertically(),
        ) {
            networkHealth.advisoryMessage?.let { msg ->
                LiquidGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    tint = semantic.warningBg.copy(alpha = 0.72f),
                    elevation = 6.dp,
                    reduceTransparency = a11y.reduceTransparency,
                    highContrast = a11y.highContrast,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        VelocityIcons.AlertCircleIcon(
                            color = semantic.warningInk,
                            size = 18.dp,
                        )
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = semantic.warningInk,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkPillCard(
    health: LinkHealth,
    liveTelemetry: LinkTelemetry,
    isBonded: Boolean,
    accentColor: Color,
    tintBg: Color,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalVelocitySemantic.current
    val a11y = LocalVelocityA11y.current
    val isActiveInBond = isBonded && liveTelemetry.active
    val hasIssue = health.readiness == LinkReadiness.NO_INTERNET ||
        health.readiness == LinkReadiness.CAPTIVE_PORTAL
    val isReady = health.readiness.hasInternet

    val containerColor = Color.Unspecified

    val statusDotColor = when {
        isActiveInBond -> semantic.successInk
        hasIssue -> semantic.warningInk
        isReady -> semantic.successInk
        else -> semantic.secondaryInk.copy(alpha = 0.45f)
    }

    val statusText = when {
        isActiveInBond && liveTelemetry.downloadMbps >= 0.1 ->
            "${((liveTelemetry.downloadMbps * 10).toInt() / 10.0)} Mbps"
        isActiveInBond -> "Active"
        else -> health.detailText
    }

    val statusInk = when {
        hasIssue -> semantic.warningInk
        isActiveInBond -> accentColor
        isReady -> semantic.primaryInk
        else -> semantic.secondaryInk
    }

    LiquidGlassSurface(
        modifier = modifier,
        cornerRadius = 22.dp,
        tint = containerColor,
        elevation = if (isActiveInBond) 10.dp else 4.dp,
        reduceTransparency = a11y.reduceTransparency,
        highContrast = a11y.highContrast,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (health.type == LinkType.WIFI) {
                    VelocityIcons.WifiIcon(
                        color = if (isReady || isActiveInBond) accentColor else semantic.secondaryInk,
                        size = 18.dp,
                    )
                } else {
                    VelocityIcons.CellularIcon(
                        color = if (isReady || isActiveInBond) accentColor else semantic.secondaryInk,
                        activeBars = health.signalBars.coerceIn(0, 4),
                        size = 18.dp,
                    )
                }

                Text(
                    text = health.networkLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = semantic.primaryInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusDotColor),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (hasIssue) {
                VelocityIcons.AlertCircleIcon(
                    color = semantic.warningInk,
                    size = 14.dp,
                )
            } else if (isActiveInBond) {
                VelocityIcons.CheckIcon(
                    color = semantic.successInk,
                    size = 14.dp,
                )
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isActiveInBond || hasIssue) FontWeight.SemiBold else FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
                color = statusInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        }
    }
}
