package com.velocity.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.BondingUiState
import com.velocity.app.domain.ServerProfile
import com.velocity.app.domain.ServerUriCodec
import com.velocity.app.ui.theme.VelocityColors

@Composable
fun ServersScreen(
    state: BondingUiState,
    onSelectServer: (ServerProfile) -> Unit,
    onOpenImportDialog: () -> Unit,
    onCloseImportDialog: () -> Unit,
    onImportUri: (String) -> Boolean,
    onProbeServers: () -> Unit,
    onCopySnippet: (label: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var inputUriText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        // Top Header with Probe and Import Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "BONDING RELAY SERVERS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Text(
                    text = "${state.servers.size} Available Locations",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onProbeServers,
                    enabled = !state.isProbingServers,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1F2937),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = if (state.isProbingServers) "Probing..." else "Ping All",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Button(
                    onClick = onOpenImportDialog,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VelocityColors.ElectricBlue,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "+ Import",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Server List
        state.servers.forEach { server ->
            val isSelected = server.id == state.selectedServerId
            ServerItemCard(
                server = server,
                isSelected = isSelected,
                onClick = { onSelectServer(server) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Self-Hosted Relay Guide & Docker/Linux Snippets
        SelfHostedRelayGuideCard(
            selectedServer = state.selectedServer,
            onCopySnippet = onCopySnippet,
        )

        Spacer(modifier = Modifier.height(80.dp)) // Clearance for floating bottom pill bar
    }

    // Import velocity:// URI Dialog
    if (state.showImportDialog) {
        AlertDialog(
            onDismissRequest = onCloseImportDialog,
            title = {
                Text(
                    text = "Import Velocity Relay",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            },
            text = {
                Column {
                    Text(
                        text = "Paste a velocity:// connection URI generated by your relay server:",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = inputUriText,
                        onValueChange = { inputUriText = it },
                        placeholder = {
                            Text(
                                "velocity://<psk>@<host>:51820?name=MyRelay",
                                fontSize = 11.sp,
                                color = Color.Gray,
                            )
                        },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.importError?.let { err ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = err,
                            fontSize = 11.sp,
                            color = VelocityColors.StatusError,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (onImportUri(inputUriText)) {
                            inputUriText = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VelocityColors.ElectricBlue,
                    ),
                ) {
                    Text("Import & Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onCloseImportDialog) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF111827),
            shape = RoundedCornerShape(16.dp),
        )
    }
}

@Composable
private fun ServerItemCard(
    server: ServerProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val cardBg = if (isSelected) Color(0xFF1E293B) else Color(0xFF101827).copy(alpha = 0.85f)
    val borderColor = if (isSelected) VelocityColors.CyanGlow else Color(0xFF1F2937)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) VelocityColors.CyanGlow else Color.Gray),
            )
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = server.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                    )
                    if (server.isSelfHosted) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(VelocityColors.VioletAccent.copy(alpha = 0.3f))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = "Self-Hosted",
                                fontSize = 8.sp,
                                color = VelocityColors.PurpleGlow,
                            )
                        }
                    }
                }
                Text(
                    text = "${server.host}:${server.port} • ${server.region}",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 1,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Dual-Path Latency Readout
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "Wi-Fi: ${server.wifiRttMs?.let { "$it ms" } ?: "--"}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = VelocityColors.WifiLink,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = "Cell: ${server.cellularRttMs?.let { "$it ms" } ?: "--"}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = VelocityColors.CellularLink,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SelfHostedRelayGuideCard(
    selectedServer: ServerProfile,
    onCopySnippet: (label: String, text: String) -> Unit,
) {
    val cardBg = Color(0xFF0F172A).copy(alpha = 0.95f)
    val cardBorder = Color(0xFF1E293B)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "SELF-HOSTED RELAY SETUP",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = Color.White.copy(alpha = 0.6f),
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Run your own low-latency Linux relay in AWS/Hetzner/DigitalOcean with full NAT and ChaCha20-Poly1305 acceleration.",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Docker One-Liner
        Text(
            text = "Docker Run (Linux / VPS):",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = VelocityColors.CyanGlow,
        )
        Spacer(modifier = Modifier.height(4.dp))
        val dockerCmd = ServerUriCodec.dockerSnippet(selectedServer)
        CodeBox(code = dockerCmd, onCopy = { onCopySnippet("Docker command", dockerCmd) })

        Spacer(modifier = Modifier.height(10.dp))

        // Native Linux CLI One-Liner
        Text(
            text = "Native Binary (systemd / root):",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = VelocityColors.CyanGlow,
        )
        Spacer(modifier = Modifier.height(4.dp))
        val linuxCmd = ServerUriCodec.linuxCliSnippet(selectedServer)
        CodeBox(code = linuxCmd, onCopy = { onCopySnippet("Linux CLI command", linuxCmd) })
    }
}

@Composable
private fun CodeBox(code: String, onCopy: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF070B12))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Column {
            Text(
                text = code,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF94A3B8),
                lineHeight = 14.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onCopy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E293B),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp),
                ) {
                    Text("Copy", fontSize = 10.sp)
                }
            }
        }
    }
}
