package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.InspectionJobEntity
import com.example.domain.model.QualityIssue
import com.example.domain.model.QualityReport
import com.example.domain.model.SessionLifecycleState
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusEmerald
import com.example.ui.viewmodel.CapturePipelineUiState
import com.example.ui.viewmodel.PendingQualityReview
import com.example.voice.VoiceUiState
import com.example.wearable.MockFeedScene
import com.example.wearable.WearableGatewayState

@Composable
fun CaptureDeckScreen(
    job: InspectionJobEntity?,
    wearableState: WearableGatewayState,
    voiceState: VoiceUiState,
    pipelineState: CapturePipelineUiState,
    onToggleTheme: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onPairMock: () -> Unit,
    onUnpairMock: () -> Unit,
    onSetPower: (Boolean) -> Unit,
    onSetFolded: (Boolean) -> Unit,
    onSetDonned: (Boolean) -> Unit,
    onSelectScene: (MockFeedScene) -> Unit,
    onToggleDeviceCamera: (Boolean) -> Unit,
    onStartSession: () -> Unit,
    onPauseSession: () -> Unit,
    onResumeSession: () -> Unit,
    onStopSession: () -> Unit,
    onSimulateDisconnect: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onSubmitCommand: (String) -> Unit,
    onOverrideQuality: () -> Unit,
    onDiscardQuality: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var commandText by remember { mutableStateOf("") }
    var selectedRoom by remember { mutableStateOf("Basement") }
    var selectedSurface by remember { mutableStateOf("Drywall") }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartVoice()
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onToggleDeviceCamera(true)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Bar: Active Claim + Theme Toggle + Session Status Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = job?.claimNumber ?: "CLM-PROP-88412",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = job?.propertyAddress ?: "1420 Oakridge Ave",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SessionStatusBadge(
                    state = wearableState.lifecycleState,
                    reconnectAttempt = wearableState.reconnectAttempt
                )
                IconButton(
                    onClick = onToggleTheme,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("theme_toggle_button")
                ) {
                    Icon(
                        imageVector = if (pipelineState.isDarkFieldTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "Toggle daylight or dark field mode"
                    )
                }
            }
        }

        if (wearableState.requiresBluetoothPermission) {
            BluetoothPermissionBannerCard(
                onGrantPermission = onRequestBluetoothPermission,
                onOpenSettings = onOpenAppSettings
            )
        }

        // Viewfinder HUD Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0A0E13))
                        .border(
                            width = 1.5.dp,
                            color = when (wearableState.lifecycleState) {
                                SessionLifecycleState.STREAMING -> StatusEmerald
                                SessionLifecycleState.RECONNECTING -> StatusAmber
                                SessionLifecycleState.ERROR -> StatusCrimson
                                else -> MaterialTheme.colorScheme.outline
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    val bmp = wearableState.previewFrame
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Live Meta AI glasses camera stream preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Viewfinder standby",
                                tint = ElectricCyan,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "Pair Mock Glasses to start DAT 1.0 camera feed",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }

                    // Framing Reticle Overlay via Compose Canvas
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val left = w * 0.14f
                        val right = w * 0.86f
                        val top = h * 0.14f
                        val bottom = h * 0.86f
                        val bracket = 22f
                        val reticleColor = if (pipelineState.liveQualityPreview?.passed == true) {
                            Color(0xCC22C55E)
                        } else {
                            Color(0xCCFFB300)
                        }
                        val stroke = 3f

                        // Top-left
                        drawLine(reticleColor, Offset(left, top), Offset(left + bracket, top), stroke)
                        drawLine(reticleColor, Offset(left, top), Offset(left, top + bracket), stroke)
                        // Top-right
                        drawLine(reticleColor, Offset(right, top), Offset(right - bracket, top), stroke)
                        drawLine(reticleColor, Offset(right, top), Offset(right, top + bracket), stroke)
                        // Bottom-left
                        drawLine(reticleColor, Offset(left, bottom), Offset(left + bracket, bottom), stroke)
                        drawLine(reticleColor, Offset(left, bottom), Offset(left, bottom - bracket), stroke)
                        // Bottom-right
                        drawLine(reticleColor, Offset(right, bottom), Offset(right - bracket, bottom), stroke)
                        drawLine(reticleColor, Offset(right, bottom), Offset(right, bottom - bracket), stroke)
                    }

                    // Top HUD Overlay Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xBB000000), Color.Transparent)
                                )
                            )
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (wearableState.hardware.paired) {
                                    Icons.Default.BluetoothConnected
                                } else {
                                    Icons.Default.BluetoothDisabled
                                },
                                contentDescription = "DAT link status",
                                tint = if (wearableState.hardware.paired) ElectricCyan else StatusCrimson,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = wearableState.hardware.deviceName,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                        if (wearableState.hardware.paired) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    color = if (wearableState.hardware.usingDeviceCamera) {
                                        StatusEmerald.copy(alpha = 0.25f)
                                    } else {
                                        StatusAmber.copy(alpha = 0.25f)
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (wearableState.hardware.usingDeviceCamera) {
                                            "REAR CAM BRIDGE"
                                        } else {
                                            "MOCK SAMPLE"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BatteryChargingFull,
                                        contentDescription = "Glasses battery",
                                        tint = StatusEmerald,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "${wearableState.hardware.batteryPercent}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Live Quality Telemetry Strip
                    pipelineState.liveQualityPreview?.let { q ->
                        LiveQualityStrip(
                            report = q,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                        )
                    }

                    // Extraction Progress Overlay
                    if (pipelineState.isExtracting || pipelineState.activeStepMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xB30E141B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(16.dp)
                            ) {
                                CircularProgressIndicator(color = SignalAmber)
                                Text(
                                    text = pipelineState.activeStepMessage ?: "Processing…",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // Session Status Line
                Text(
                    text = wearableState.statusDetail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Session Lifecycle Buttons: Pair / Start / Pause / Resume / Stop / Sim Disconnect
                if (!wearableState.hardware.paired) {
                    Button(
                        onClick = onPairMock,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("pair_mock_glasses_button")
                    ) {
                        Icon(Icons.Default.BluetoothConnected, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.glasses_connect_mock))
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onStartSession,
                                enabled = wearableState.lifecycleState != SessionLifecycleState.STREAMING,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("session_start_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.session_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FilledTonalButton(
                                onClick = if (wearableState.lifecycleState == SessionLifecycleState.PAUSED) {
                                    onResumeSession
                                } else {
                                    onPauseSession
                                },
                                enabled = wearableState.lifecycleState == SessionLifecycleState.STREAMING ||
                                    wearableState.lifecycleState == SessionLifecycleState.PAUSED,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("session_pause_resume_button")
                            ) {
                                Icon(
                                    imageVector = if (wearableState.lifecycleState == SessionLifecycleState.PAUSED) {
                                        Icons.Default.PlayArrow
                                    } else {
                                        Icons.Default.Pause
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (wearableState.lifecycleState == SessionLifecycleState.PAUSED) {
                                        stringResource(R.string.session_resume)
                                    } else {
                                        stringResource(R.string.session_pause)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onStopSession,
                                enabled = wearableState.lifecycleState == SessionLifecycleState.STREAMING ||
                                    wearableState.lifecycleState == SessionLifecycleState.PAUSED,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("session_stop_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.session_stop), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = onSimulateDisconnect,
                                enabled = wearableState.lifecycleState == SessionLifecycleState.STREAMING,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("simulate_disconnect_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.simulate_disconnect), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // Pending Quality Gate Warning Card (Blur / Darkness / Framing)
        AnimatedVisibility(
            visible = pipelineState.pendingQualityReview != null,
            enter = fadeIn(spring()),
            exit = fadeOut(spring())
        ) {
            pipelineState.pendingQualityReview?.let { pending ->
                QualityGateAlertCard(
                    pending = pending,
                    busy = pipelineState.isExtracting || pipelineState.activeStepMessage != null,
                    onOverride = onOverrideQuality,
                    onDiscard = onDiscardQuality
                )
            }
        }

        // Voice Command + Emulator Quick-Command Deck
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Voice & Hands-Free Commands",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = voiceState.partialTranscript.ifBlank {
                                voiceState.errorMessage ?: voiceState.statusMessage
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (voiceState.errorMessage != null) StatusAmber else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            if (voiceState.isListening) {
                                onStopVoice()
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    onStartVoice()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (voiceState.isListening) StatusCrimson else MaterialTheme.colorScheme.secondary
                        ),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("voice_listen_button")
                    ) {
                        Icon(
                            imageVector = if (voiceState.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Voice command microphone",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (voiceState.isListening) stringResource(R.string.voice_stop) else stringResource(R.string.voice_listen))
                    }
                }

                // Quick-Command Triggers for all 3 required voice commands
                Text(
                    text = "Quick-Command Triggers (Emulator / Hands-On Fallback)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedRoom == "Basement",
                        onClick = { selectedRoom = "Basement"; selectedSurface = "Drywall" },
                        label = { Text("Basement Drywall") },
                        modifier = Modifier.testTag("room_preset_basement")
                    )
                    FilterChip(
                        selected = selectedRoom == "Utility",
                        onClick = { selectedRoom = "Utility"; selectedSurface = "Breaker Panel" },
                        label = { Text("Utility Panel") },
                        modifier = Modifier.testTag("room_preset_utility")
                    )
                    FilterChip(
                        selected = selectedRoom == "Roof",
                        onClick = { selectedRoom = "Roof"; selectedSurface = "Shingles" },
                        label = { Text("Roof Shingles") },
                        modifier = Modifier.testTag("room_preset_roof")
                    )
                }

                val pipelineBusy = pipelineState.isExtracting || pipelineState.activeStepMessage != null

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onSubmitCommand("capture nameplate") },
                            enabled = !pipelineBusy,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("quick_cmd_nameplate")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("capture nameplate", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = {
                                onSubmitCommand("note: Active moisture staining near $selectedRoom $selectedSurface; verify shutoff valve")
                            },
                            enabled = !pipelineBusy,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("quick_cmd_note")
                        ) {
                            Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("note: quick log", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    FilledTonalButton(
                        onClick = { onSubmitCommand("capture damage $selectedRoom $selectedSurface") },
                        enabled = !pipelineBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("quick_cmd_damage")
                    ) {
                        Icon(Icons.Default.ReportProblem, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("capture damage $selectedRoom $selectedSurface", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = commandText,
                        onValueChange = { commandText = it },
                        enabled = !pipelineBusy,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("voice_command_input"),
                        label = { Text("Type command (e.g. note: breaker lug scorched)") },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (commandText.isNotBlank()) {
                                onSubmitCommand(commandText)
                                commandText = ""
                            }
                        },
                        enabled = !pipelineBusy && commandText.isNotBlank(),
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .testTag("voice_command_send_button")
                    ) {
                        Text("Run")
                    }
                }

                pipelineState.commandFeedback?.let { msg ->
                    val containerColor = if (pipelineState.feedbackIsError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        StatusEmerald.copy(alpha = 0.14f)
                    }
                    val contentColor = if (pipelineState.feedbackIsError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    val iconTint = if (pipelineState.feedbackIsError) {
                        StatusCrimson
                    } else {
                        StatusEmerald
                    }
                    Surface(
                        color = containerColor,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("capture_command_feedback")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (pipelineState.feedbackIsError) {
                                    Icons.Default.Warning
                                } else {
                                    Icons.Default.CheckCircle
                                },
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }

        // Mock Device Kit Hardware Controls Card
        if (wearableState.hardware.paired) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Mock Device Kit Simulator",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Recovered: ${wearableState.disconnectsRecovered}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                    }

                    // Scene Selector (Genuine Mock Feeds)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MockFeedScene.entries.forEach { scene ->
                            FilterChip(
                                selected = !wearableState.hardware.usingDeviceCamera &&
                                    wearableState.hardware.cameraSourceLabel == scene.label,
                                onClick = { onSelectScene(scene) },
                                label = { Text(scene.label) },
                                modifier = Modifier.testTag("mock_scene_${scene.name.lowercase()}")
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Power On", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = wearableState.hardware.poweredOn,
                            onCheckedChange = onSetPower,
                            modifier = Modifier.testTag("mock_power_switch")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Hinges Unfolded (Wearable Ready)", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = wearableState.hardware.unfolded,
                            onCheckedChange = { unfolded -> onSetFolded(!unfolded) },
                            modifier = Modifier.testTag("mock_unfold_switch")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Glasses Worn (Donned)", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = wearableState.hardware.donned,
                            onCheckedChange = onSetDonned,
                            modifier = Modifier.testTag("mock_don_switch")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Route Android Rear Camera Feed", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = wearableState.hardware.usingDeviceCamera,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) onToggleDeviceCamera(true)
                                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                } else {
                                    onToggleDeviceCamera(false)
                                }
                            },
                            modifier = Modifier.testTag("mock_device_cam_switch")
                        )
                    }

                    OutlinedButton(
                        onClick = onUnpairMock,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("unpair_mock_button")
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Unpair Mock Glasses")
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionStatusBadge(
    state: SessionLifecycleState,
    reconnectAttempt: Int
) {
    val (bg, fg, label) = when (state) {
        SessionLifecycleState.STREAMING -> Triple(Color(0xFF0D3320), StatusEmerald, "STREAMING")
        SessionLifecycleState.PAUSED -> Triple(Color(0xFF3B2706), StatusAmber, "PAUSED")
        SessionLifecycleState.RECONNECTING -> Triple(Color(0xFF3B2706), StatusAmber, "RECOVER #$reconnectAttempt")
        SessionLifecycleState.PAIRED_IDLE -> Triple(Color(0xFF0B3548), ElectricCyan, "PAIRED")
        SessionLifecycleState.STARTING -> Triple(Color(0xFF0B3548), ElectricCyan, "STARTING")
        SessionLifecycleState.STOPPED -> Triple(Color(0xFF1F2B38), Color(0xFF94A3B8), "STOPPED")
        SessionLifecycleState.ERROR -> Triple(Color(0xFF3D1115), StatusCrimson, "ERROR")
        SessionLifecycleState.DISCONNECTED -> Triple(Color(0xFF1F2B38), Color(0xFF94A3B8), "OFFLINE")
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.border(1.dp, fg.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(fg)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun LiveQualityStrip(
    report: QualityReport,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0xCC0E141B))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "BLUR ${report.blurScore.toInt()} • LUMA ${report.brightnessScore.toInt()} • FRAME ${(report.framingScore * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = if (report.passed) StatusEmerald else StatusAmber
        )
        Text(
            text = "${report.latencyMs}ms",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFCBD5E1)
        )
    }
}

@Composable
private fun QualityGateAlertCard(
    pending: PendingQualityReview,
    busy: Boolean,
    onOverride: () -> Unit,
    onDiscard: () -> Unit
) {
    val retakeTips = pending.qualityReport.issues.map { issue ->
        when (issue) {
            QualityIssue.BLURRY -> "• Hold head still for 0.5s and step within 18 inches for sharp text."
            QualityIssue.TOO_DARK -> "• Turn on room lighting or flashlight — frame is too dark."
            QualityIssue.OVEREXPOSED -> "• Tilt angle slightly to reduce direct glare/specular reflection."
            QualityIssue.POOR_FRAMING -> "• Center the nameplate or damaged area inside the reticle brackets."
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Quality gate warning",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Retake Recommended — Quality Gate Failed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = pending.qualityReport.summaryMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (retakeTips.isNotEmpty()) {
                Text(
                    text = retakeTips.joinToString("\n"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDiscard,
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("quality_discard_button")
                ) {
                    Text(stringResource(R.string.quality_retake))
                }
                Button(
                    onClick = onOverride,
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("quality_override_button")
                ) {
                    Text(stringResource(R.string.quality_override))
                }
            }
        }
    }
}

@Composable
private fun BluetoothPermissionBannerCard(
    onGrantPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bluetooth_permission_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BluetoothDisabled,
                    contentDescription = "Nearby devices permission required",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Nearby Devices (Bluetooth) Permission Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = "Meta Wearables DAT 1.0 and Mock Device Kit require Nearby Devices (BLUETOOTH_CONNECT) permission on Android 12+ before initializing Bluetooth device detection. Field notes, Job review, Sync Queue, and Eval remain usable.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onGrantPermission,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("grant_bluetooth_permission_button")
                ) {
                    Text("Grant Permission")
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("open_bluetooth_settings_button")
                ) {
                    Text("App Settings")
                }
            }
        }
    }
}
