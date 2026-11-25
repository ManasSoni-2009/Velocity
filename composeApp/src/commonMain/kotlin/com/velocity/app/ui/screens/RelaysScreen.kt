package com.velocity.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.BondingUiState
import com.velocity.app.domain.ServerProfile
import com.velocity.app.domain.ServerUriCodec
import com.velocity.app.platform.VelocityHaptic
import com.velocity.app.platform.rememberHapticPerformer
import com.velocity.app.ui.components.RelayQuotaBar
import com.velocity.app.ui.components.VelocityIcons
import com.velocity.app.ui.theme.LocalVelocitySemantic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaysScreen(
    state: BondingUiState,
    onSelectServer: (ServerProfile) -> Unit,
    onDeleteServer: (String) -> Unit,
    onOpenImportDialog: () -> Unit,
    onCloseImportDialog: () -> Unit,
    onImportUri: (String) -> Boolean,
    onAddManualServer: (name: String, host: String, port: String, psk: String) -> Boolean,
    onOpenSelfHostSheet: () -> Unit,
    onCloseSelfHostSheet: () -> Unit,
    onProbeServers: () -> Unit,
    onCopySnippet: (label: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalVelocitySemantic.current
    val haptic = rememberHapticPerformer()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Relays",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = semantic.primaryInk,
                )
                Text(
                    text = if (state.servers.isEmpty()) "No servers configured" else "${state.servers.size} saved",
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.secondaryInk,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.servers.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            haptic(VelocityHaptic.TAP)
                            onProbeServers()
                        },
                        enabled = !state.isProbingServers,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, semantic.cardBorder),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            text = if (state.isProbingServers) "Pinging…" else "Ping",
                            style = MaterialTheme.typography.labelLarge,
                            color = semantic.primaryInk,
                        )
                    }
                }

                Button(
                    onClick = {
                        haptic(VelocityHaptic.TAP)
                        onOpenImportDialog()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = semantic.accent,
                        contentColor = semantic.onAccent,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    VelocityIcons.PlusIcon(color = semantic.onAccent, size = 16.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Add",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        if (state.servers.isEmpty()) {
            EmptyRelayOnboardingCard(
                onAddClick = {
                    haptic(VelocityHaptic.TAP)
                    onOpenImportDialog()
                },
                onHostClick = {
                    haptic(VelocityHaptic.TAP)
                    onOpenSelfHostSheet()
                },
            )
        } else {
            state.servers.forEach { server ->
                val isSelected = server.id == state.selectedServerId
                RelayRowCard(
                    server = server,
                    isSelected = isSelected,
                    onClick = {
                        haptic(VelocityHaptic.TICK)
                        onSelectServer(server)
                    },
                    onDelete = {
                        haptic(VelocityHaptic.TAP)
                        onDeleteServer(server.id)
                    },
                )
            }

            // Quiet link to Self-Host guide sheet
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(semantic.mutedSurface.copy(alpha = 0.6f))
                    .border(1.dp, semantic.cardBorder, RoundedCornerShape(18.dp))
                    .clickable {
                        haptic(VelocityHaptic.TAP)
                        onOpenSelfHostSheet()
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Host your own relay",
                        style = MaterialTheme.typography.titleMedium,
                        color = semantic.primaryInk,
                    )
                    Text(
                        text = "Docker & Linux setup instructions",
                        style = MaterialTheme.typography.bodyMedium,
                        color = semantic.secondaryInk,
                    )
                }
                VelocityIcons.ChevronIcon(color = semantic.secondaryInk, size = 16.dp)
            }
        }

        Spacer(modifier = Modifier.height(104.dp))
        Spacer(modifier = Modifier.navigationBarsPadding())
    }

    if (state.showImportDialog) {
        AddRelayBottomSheet(
            importError = state.importError,
            onDismiss = onCloseImportDialog,
            onImportUri = onImportUri,
            onAddManual = onAddManualServer,
        )
    }

    if (state.showSelfHostSheet) {
        SelfHostGuideBottomSheet(
            sampleServer = state.selectedServer ?: ServerProfile(
                id = "self-host",
                name = "My Relay",
                host = "your-server-ip",
                port = 51820,
                pskHex = "0123456789abcdef0123456789abcdef",
            ),
            onCopySnippet = onCopySnippet,
            onDismiss = onCloseSelfHostSheet,
        )
    }
}

@Composable
private fun EmptyRelayOnboardingCard(
    onAddClick: () -> Unit,
    onHostClick: () -> Unit,
) {
    val semantic = LocalVelocitySemantic.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(semantic.card)
            .border(1.dp, semantic.cardBorder, RoundedCornerShape(24.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(semantic.wifiBg),
            contentAlignment = Alignment.Center,
        ) {
            VelocityIcons.GlobeIcon(color = semantic.accent, size = 22.dp)
        }

        Text(
            text = "Connect your relay server",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = semantic.primaryInk,
        )

        Text(
            text = "Velocity combines Wi-Fi and Cellular traffic through your private relay. Paste a velocity:// invite link or enter your server IP and key.",
            style = MaterialTheme.typography.bodyMedium,
            color = semantic.secondaryInk,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = onAddClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = semantic.accent,
                contentColor = semantic.onAccent,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
        ) {
            Text("Add Relay Server", style = MaterialTheme.typography.labelLarge)
        }

        OutlinedButton(
            onClick = onHostClick,
            border = androidx.compose.foundation.BorderStroke(1.dp, semantic.cardBorder),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
        ) {
            Text(
                text = "How to host your own relay",
                style = MaterialTheme.typography.labelLarge,
                color = semantic.primaryInk,
            )
        }
    }
}

@Composable
private fun RelayRowCard(
    server: ServerProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val semantic = LocalVelocitySemantic.current
    val bg = if (isSelected) semantic.wifiBg.copy(alpha = 0.72f) else semantic.card
    val border = if (isSelected) semantic.accent else semantic.cardBorder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 68.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(if (isSelected) 1.5.dp else 1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) semantic.accent else semantic.cardBorder),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = semantic.primaryInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${server.host}:${server.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.secondaryInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Honest Latency Readout
            val (statusText, statusBg, statusInk) = when {
                server.isReachable == false -> Triple("Offline", semantic.errorBg, semantic.errorInk)
                server.wifiRttMs != null || server.cellularRttMs != null -> {
                    val best = listOfNotNull(server.wifiRttMs, server.cellularRttMs).min()
                    Triple("$best ms", semantic.successBg, semantic.successInk)
                }
                else -> Triple("Untested", semantic.mutedSurface, semantic.secondaryInk)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(statusBg)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = statusInk,
                    maxLines = 1,
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp),
            ) {
                VelocityIcons.TrashIcon(
                    color = semantic.secondaryInk,
                    size = 18.dp,
                )
            }
        }

        val quota = server.quota
        if (quota != null && quota.hasValidLimit) {
            RelayQuotaBar(
                quota = quota,
                compact = true,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRelayBottomSheet(
    importError: String?,
    onDismiss: () -> Unit,
    onImportUri: (String) -> Boolean,
    onAddManual: (name: String, host: String, port: String, psk: String) -> Boolean,
) {
    val semantic = LocalVelocitySemantic.current
    var modeTab by remember { mutableStateOf(0) } // 0 = URI, 1 = Manual
    var uriText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var hostText by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("51820") }
    var keyText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = semantic.card,
        contentColor = semantic.primaryInk,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Add Relay Server",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = semantic.primaryInk,
            )

            // Mode Switcher Pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(semantic.mutedSurface)
                    .padding(4.dp),
            ) {
                listOf("Paste Link", "Manual").forEachIndexed { idx, label ->
                    val selected = modeTab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) semantic.accent else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { modeTab = idx },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) semantic.onAccent else semantic.primaryInk,
                        )
                    }
                }
            }

            if (modeTab == 0) {
                OutlinedTextField(
                    value = uriText,
                    onValueChange = { uriText = it },
                    label = { Text("velocity:// invite link") },
                    placeholder = { Text("velocity://<key>@<host>:51820?name=Relay") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("Name (optional)") },
                    placeholder = { Text("My Home Server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = hostText,
                        onValueChange = { hostText = it },
                        label = { Text("Host or IP") },
                        placeholder = { Text("203.0.113.10") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("Pre-shared key (16+ chars)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (importError != null) {
                Text(
                    text = importError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.errorInk,
                )
            }

            Button(
                onClick = {
                    val ok = if (modeTab == 0) {
                        onImportUri(uriText)
                    } else {
                        onAddManual(nameText, hostText, portText, keyText)
                    }
                    if (ok) {
                        uriText = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = semantic.accent,
                    contentColor = semantic.onAccent,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 50.dp),
            ) {
                Text("Save Relay", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelfHostGuideBottomSheet(
    sampleServer: ServerProfile,
    onCopySnippet: (label: String, text: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val semantic = LocalVelocitySemantic.current
    var tab by remember { mutableStateOf(0) }
    val ociCmd = remember(sampleServer) { ServerUriCodec.oracleCloudSnippet(sampleServer) }
    val dockerCmd = remember(sampleServer) { ServerUriCodec.dockerSnippet(sampleServer) }
    val linuxCmd = remember(sampleServer) { ServerUriCodec.linuxCliSnippet(sampleServer) }
    val currentCmd = when (tab) {
        0 -> ociCmd
        1 -> dockerCmd
        else -> linuxCmd
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = semantic.card,
        contentColor = semantic.primaryInk,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Host Your Own Relay",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = semantic.primaryInk,
            )
            Text(
                text = "Oracle Cloud Always Free gives 4 ARM64 OCPUs, 24 GB RAM & 10 TB/month outbound free.",
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.secondaryInk,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(semantic.mutedSurface)
                    .padding(4.dp),
            ) {
                listOf("Oracle Cloud", "Docker", "Linux").forEachIndexed { idx, label ->
                    val selected = tab == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) semantic.accent else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { tab = idx },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) semantic.onAccent else semantic.primaryInk,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(semantic.mutedSurface.copy(alpha = 0.7f))
                    .border(1.dp, semantic.cardBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Text(
                    text = currentCmd,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = semantic.primaryInk,
                )
            }

            Button(
                onClick = {
                    val label = when (tab) {
                        0 -> "Oracle Cloud command"
                        1 -> "Docker command"
                        else -> "Linux command"
                    }
                    onCopySnippet(label, currentCmd)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = semantic.accent,
                    contentColor = semantic.onAccent,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
            ) {
                Text("Copy Command", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
