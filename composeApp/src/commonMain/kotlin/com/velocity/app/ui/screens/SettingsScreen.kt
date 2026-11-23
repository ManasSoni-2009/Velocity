package com.velocity.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.AppTunnelRule
import com.velocity.app.domain.BondingUiState
import com.velocity.app.domain.ThemeMode
import com.velocity.app.domain.TunnelRulePolicy
import com.velocity.app.domain.VelocitySettings
import com.velocity.app.ui.theme.VelocityColors

@Composable
fun SettingsScreen(
    state: BondingUiState,
    onUpdateRule: (packageName: String, policy: TunnelRulePolicy) -> Unit,
    onUpdateSettings: ((VelocitySettings) -> VelocitySettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var appSearchQuery by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = "TUNING & POLICIES",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = Color.White.copy(alpha = 0.6f),
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Link Balancing & Bandwidth Card
        LinkBalancingSettingsCard(
            settings = state.settings,
            onUpdateSettings = onUpdateSettings,
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Appearance & Accessibility (Apple HIG Liquid Glass)
        AppearanceSettingsCard(
            settings = state.settings,
            onUpdateSettings = onUpdateSettings,
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Per-App Split Tunneling Section
        SplitTunnelingCard(
            rules = state.appRules,
            searchQuery = appSearchQuery,
            onSearchChange = { appSearchQuery = it },
            onUpdateRule = onUpdateRule,
        )

        Spacer(modifier = Modifier.height(80.dp)) // Clearance for floating bottom pill bar
    }
}

@Composable
private fun LinkBalancingSettingsCard(
    settings: VelocitySettings,
    onUpdateSettings: ((VelocitySettings) -> VelocitySettings) -> Unit,
) {
    val cardBg = Color(0xFF101827).copy(alpha = 0.9f)
    val cardBorder = Color(0xFF1F2937)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "TRAFFIC & DATA CONTROLS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = Color.White.copy(alpha = 0.6f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Wi-Fi Bias Slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Wi-Fi Scheduling Bias",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Text(
                text = "${settings.wifiBiasPercent}%",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = VelocityColors.WifiLink,
            )
        }
        Slider(
            value = settings.wifiBiasPercent.toFloat(),
            onValueChange = { value ->
                onUpdateSettings { it.copy(wifiBiasPercent = value.toInt()) }
            },
            valueRange = 0f..100f,
            steps = 9,
            colors = SliderDefaults.colors(
                thumbColor = VelocityColors.CyanGlow,
                activeTrackColor = VelocityColors.WifiLink,
                inactiveTrackColor = Color(0xFF1E293B),
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Cellular Monthly Cap
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Cellular Data Cap",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Text(
                text = "${settings.cellularDataCapMb / 1024} GB",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = VelocityColors.CellularLink,
            )
        }
        Slider(
            value = (settings.cellularDataCapMb / 1024).toFloat(),
            onValueChange = { value ->
                onUpdateSettings { it.copy(cellularDataCapMb = value.toInt() * 1024) }
            },
            valueRange = 1f..30f,
            steps = 28,
            colors = SliderDefaults.colors(
                thumbColor = VelocityColors.CellularLink,
                activeTrackColor = VelocityColors.CellularLink,
                inactiveTrackColor = Color(0xFF1E293B),
            ),
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Adaptive FEC Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Adaptive XOR FEC Parity",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Text(
                    text = "Auto-recovers packet loss on jittery links",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
            Switch(
                checked = settings.fecEnabled,
                onCheckedChange = { checked ->
                    onUpdateSettings { it.copy(fecEnabled = checked) }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = VelocityColors.ElectricBlue,
                    uncheckedTrackColor = Color(0xFF1E293B),
                ),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Direct DNS Guard Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = "Direct DNS Guard (Port 53)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Text(
                    text = "Prevents DNS blackholes via direct Cloudflare/Google resolver",
                    fontSize = 10.sp,
                    color = VelocityColors.CyanGlow.copy(alpha = 0.85f),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = settings.directDnsGuard,
                onCheckedChange = { checked ->
                    onUpdateSettings { it.copy(directDnsGuard = checked) }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = VelocityColors.CombinedGlow,
                    uncheckedTrackColor = Color(0xFF1E293B),
                ),
            )
        }
    }
}

@Composable
private fun AppearanceSettingsCard(
    settings: VelocitySettings,
    onUpdateSettings: ((VelocitySettings) -> VelocitySettings) -> Unit,
) {
    val cardBg = Color(0xFF101827).copy(alpha = 0.9f)
    val cardBorder = Color(0xFF1F2937)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "LIQUID GLASS & ACCESSIBILITY",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = Color.White.copy(alpha = 0.6f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Reduce Transparency
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Reduce Transparency",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Text(
                    text = "Replaces backdrop blurs with opaque surfaces",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
            Switch(
                checked = settings.reduceTransparency,
                onCheckedChange = { checked ->
                    onUpdateSettings { it.copy(reduceTransparency = checked) }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = VelocityColors.ElectricBlue,
                    uncheckedTrackColor = Color(0xFF1E293B),
                ),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Increase Contrast
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Increase Contrast",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Text(
                    text = "Enhances specular rim highlights & card borders",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
            Switch(
                checked = settings.highContrast,
                onCheckedChange = { checked ->
                    onUpdateSettings { it.copy(highContrast = checked) }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = VelocityColors.ElectricBlue,
                    uncheckedTrackColor = Color(0xFF1E293B),
                ),
            )
        }
    }
}

@Composable
private fun SplitTunnelingCard(
    rules: List<AppTunnelRule>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onUpdateRule: (packageName: String, policy: TunnelRulePolicy) -> Unit,
) {
    val cardBg = Color(0xFF101827).copy(alpha = 0.9f)
    val cardBorder = Color(0xFF1F2937)

    val filteredRules = remember(rules, searchQuery) {
        if (searchQuery.isBlank()) rules else {
            rules.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "PER-APP SPLIT TUNNELING",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
            Text(
                text = "${filteredRules.size} Apps",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search installed applications...", fontSize = 12.sp, color = Color.Gray) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredRules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (rules.isEmpty()) "No installed apps detected (Desktop mode)" else "No matching apps found",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
        } else {
            for (app in filteredRules.take(20)) {
                AppRuleRow(app = app, onPolicyChange = { onUpdateRule(app.packageName, it) })
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun AppRuleRow(
    app: AppTunnelRule,
    onPolicyChange: (TunnelRulePolicy) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F172A))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = app.packageName,
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.4f),
                maxLines = 1,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TunnelRulePolicy.entries.forEach { policy ->
                val isSelected = app.policy == policy
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) {
                                when (policy) {
                                    TunnelRulePolicy.BONDED -> VelocityColors.CombinedGlow.copy(alpha = 0.25f)
                                    TunnelRulePolicy.LOW_LATENCY -> VelocityColors.ElectricBlue.copy(alpha = 0.25f)
                                    TunnelRulePolicy.BYPASS -> Color(0xFF6B7280).copy(alpha = 0.25f)
                                }
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onPolicyChange(policy) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = policy.label,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}
