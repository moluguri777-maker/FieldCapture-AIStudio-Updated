package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.EvalBatchSummaryUi
import com.example.domain.model.TelemetryRecordUi
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusEmerald
import java.util.Locale

@Composable
fun EvalTelemetryScreen(
    evalSummary: EvalBatchSummaryUi,
    telemetryRecords: List<TelemetryRecordUi>,
    importedUriCount: Int,
    onImportFrames: (List<Uri>) -> Unit,
    onToggleLiveAi: (Boolean) -> Unit,
    onStartEval: () -> Unit,
    onStopEval: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showEvalJson by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 40)
    ) { uris ->
        onImportFrames(uris)
    }

    val liveTelemetryAi = telemetryRecords.filter { it.aiLatencyMs > 0L }
    val avgQualityMs = telemetryRecords.filter { it.qualityLatencyMs > 0L }
        .map { it.qualityLatencyMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L
    val avgAiMs = liveTelemetryAi.map { it.aiLatencyMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L
    val avgSyncMs = telemetryRecords.filter { it.syncLatencyMs > 0L }
        .map { it.syncLatencyMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L
    val totalTokens = telemetryRecords.sumOf { it.promptTokens + it.outputTokens }
    val totalCostUsd = telemetryRecords.sumOf { it.estimatedCostUsd }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Stretch Goal: Cost & Latency Telemetry Dashboard
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Analytics, contentDescription = null, tint = SignalAmber)
                        Column {
                            Text("Cost & Latency Telemetry", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "Live session + batch measurements (${telemetryRecords.size} samples)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryStatBox(
                            label = "Avg Quality",
                            value = "${avgQualityMs}ms",
                            accent = StatusEmerald,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryStatBox(
                            label = "Avg AI",
                            value = "${avgAiMs}ms",
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryStatBox(
                            label = "Avg Sync",
                            value = "${avgSyncMs}ms",
                            accent = SignalAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryStatBox(
                            label = "Total Tokens",
                            value = "$totalTokens",
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryStatBox(
                            label = "Est. Cost (USD)",
                            value = String.format(Locale.US, "$%.5f", totalCostUsd),
                            accent = SignalAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Custom Compose Canvas Bar Chart of Stage Latencies
                    if (telemetryRecords.isNotEmpty()) {
                        Text(
                            text = "Recent Capture Latencies (Quality + AI + Sync ms)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LatencyBarChart(
                            samples = telemetryRecords.take(16).reversed(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                        )
                    }
                }
            }
        }

        // 40+ Frame Batch Evaluator Control Card
        item {
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
                            Text("40-Frame Genuine Batch Evaluator", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "Pool: ${evalSummary.totalFrames} frames ($importedUriCount imported via Photo Picker)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (evalSummary.exportedJsonReport != null) {
                            OutlinedButton(
                                onClick = { showEvalJson = true },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("view_eval_json_button")
                            ) {
                                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Report")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Include Live Gemini 3.8 Flash AI", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                text = "When OFF, benchmarks on-device CV quality gates across all 42 frames in <2s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = evalSummary.useLiveAiInBatch,
                            onCheckedChange = onToggleLiveAi,
                            enabled = !evalSummary.isRunning,
                            modifier = Modifier.testTag("eval_live_ai_switch")
                        )
                    }

                    if (evalSummary.isRunning || evalSummary.completedFrames > 0) {
                        val progress = if (evalSummary.totalFrames > 0) {
                            evalSummary.completedFrames.toFloat() / evalSummary.totalFrames
                        } else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Processed ${evalSummary.completedFrames}/${evalSummary.totalFrames} • Real: ${evalSummary.realFrameCount} • Synthetic: ${evalSummary.syntheticFrameCount} • Nameplates: ${evalSummary.nameplateCount} • Low-Light: ${evalSummary.lowLightCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                        Text(
                            text = "CV Pass: ${evalSummary.qualityPassCount} (Blur ${evalSummary.blurRejectedCount}, Dark ${evalSummary.darkRejectedCount}, Glare ${evalSummary.overexposedRejectedCount}, Framing ${evalSummary.framingRejectedCount}) • AI OK: ${evalSummary.aiSuccessCount}, Fail: ${evalSummary.aiFailureCount} • P50 ${evalSummary.p50TotalLatencyMs}ms / P95 ${evalSummary.p95TotalLatencyMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (evalSummary.perFieldMeanConfidence.isNotEmpty()) {
                            Text(
                                text = "Field Confidence: " + evalSummary.perFieldMeanConfidence.entries.joinToString(", ") {
                                    "${it.key}: ${(it.value * 100).toInt()}%"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusEmerald
                            )
                        }
                        if (evalSummary.worstFailures.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = "Top ${evalSummary.worstFailures.size} Failure Cases",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                    evalSummary.worstFailures.forEach { f ->
                                        Text(
                                            text = "#${f.frameIndex} ${f.frameLabel}: ${f.likelyCause}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !evalSummary.isRunning,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("import_eval_frames_button")
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.import_frames))
                        }

                        if (evalSummary.isRunning) {
                            Button(
                                onClick = onStopEval,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("stop_eval_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Stop Batch")
                            }
                        } else {
                            Button(
                                onClick = onStartEval,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("run_eval_40_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.run_eval_40))
                            }
                        }
                    }
                }
            }
        }

        items(evalSummary.results, key = { it.index }) { row ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "#${row.index} ${row.frameLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (row.qualityPassed) "PASS" else "REJECT",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (row.qualityPassed) StatusEmerald else StatusCrimson,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "${row.sourceKind} • Blur ${row.blurScore.toInt()} • Luma ${row.brightnessScore.toInt()} • Frame ${(row.framingScore * 100).toInt()}% • ${row.qualityLatencyMs + row.aiLatencyMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = row.notes,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.aiSucceeded) ElectricCyan else StatusAmber,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showEvalJson && evalSummary.exportedJsonReport != null) {
        AlertDialog(
            onDismissRequest = { showEvalJson = false },
            title = { Text("40-Frame Evaluation Report (JSON)") },
            text = {
                Box(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(evalSummary.exportedJsonReport, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(onClick = { showEvalJson = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun TelemetryStatBox(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LatencyBarChart(
    samples: List<TelemetryRecordUi>,
    modifier: Modifier = Modifier
) {
    val maxLatency = samples.maxOfOrNull { it.qualityLatencyMs + it.aiLatencyMs + it.syncLatencyMs }
        ?.coerceAtLeast(50L) ?: 100L

    Canvas(modifier = modifier) {
        val count = samples.size.coerceAtLeast(1)
        val slotW = size.width / count
        val barW = (slotW * 0.62f).coerceAtLeast(6f)
        val h = size.height

        samples.forEachIndexed { idx, s ->
            val x = idx * slotW + (slotW - barW) / 2f
            val total = (s.qualityLatencyMs + s.aiLatencyMs + s.syncLatencyMs).toFloat()
            val totalH = (total / maxLatency.toFloat()).coerceIn(0.06f, 1f) * (h - 8f)
            val color = when {
                s.aiLatencyMs > 0L -> ElectricCyan
                s.syncLatencyMs > 0L -> SignalAmber
                else -> StatusEmerald
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, h - totalH),
                size = Size(barW, totalH),
                cornerRadius = CornerRadius(6f, 6f)
            )
        }
    }
}
