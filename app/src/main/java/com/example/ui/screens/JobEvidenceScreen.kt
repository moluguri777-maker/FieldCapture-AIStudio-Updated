package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.InspectionJobEntity
import com.example.domain.model.CaptureEvidenceUi
import com.example.domain.model.ConfidenceField
import com.example.domain.model.EstimateCodeCatalog
import com.example.domain.model.EstimateLineItem
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SignalAmber
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusEmerald
import java.io.File
import java.text.NumberFormat
import java.util.Locale

@Composable
fun JobEvidenceScreen(
    job: InspectionJobEntity?,
    captures: List<CaptureEvidenceUi>,
    estimateItems: List<EstimateLineItem>,
    exportedJson: String?,
    onUpdateField: (captureId: String, fieldKey: String, newValue: String) -> Unit,
    onToggleEstimateCode: (captureId: String, code: String) -> Unit,
    onRetryExtraction: (captureId: String) -> Unit,
    onDeleteCapture: (captureId: String) -> Unit,
    onExportJobJson: () -> Unit,
    onDismissExportJson: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editingTarget by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var codePickerCaptureId by remember { mutableStateOf<String?>(null) }
    val currency = remember { NumberFormat.getCurrencyInstance(Locale.US) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Structured Job Record",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "${job?.jobId ?: "JOB-2026-4082"} • ${captures.size} captures • ${estimateItems.size} line items",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = onExportJobJson,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("export_job_json_button")
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("JSON")
                        }
                    }

                    val totalEstimate = estimateItems.sumOf { it.extendedUsd }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Estimated Scope Total", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = currency.format(totalEstimate),
                            style = MaterialTheme.typography.titleMedium,
                            color = SignalAmber,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Estimate Line Items Summary Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = ElectricCyan)
                        Text(
                            text = "Estimate Line Items (14-Code Catalog)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    if (estimateItems.isEmpty()) {
                        Text(
                            text = "Capture a nameplate, damage photo, or technician note to populate matched property & HVAC/electrical estimate codes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        estimateItems.forEach { line ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${line.code} • ${line.trade}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = ElectricCyan
                                    )
                                    Text(
                                        text = "${line.description} (${line.quantity} ${line.unit})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = currency.format(line.extendedUsd),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = SignalAmber
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                    }
                }
            }
        }

        if (captures.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Verified, contentDescription = null, tint = SignalAmber, modifier = Modifier.size(32.dp))
                        Text("No Evidence Captured Yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Use the Capture tab to trigger 'capture nameplate', 'capture damage [room] [surface]', or 'note: [text]'.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(captures, key = { it.captureId }) { cap ->
            CaptureEvidenceCard(
                capture = cap,
                onEditField = { key, oldVal -> editingTarget = Triple(cap.captureId, key, oldVal) },
                onOpenCodePicker = { codePickerCaptureId = cap.captureId },
                onRetryExtraction = { onRetryExtraction(cap.captureId) },
                onDelete = { onDeleteCapture(cap.captureId) }
            )
        }
    }

    // Inline Field Edit Dialog
    editingTarget?.let { (capId, fieldKey, initialValue) ->
        var draft by remember(capId, fieldKey) { mutableStateOf(initialValue) }
        AlertDialog(
            onDismissRequest = { editingTarget = null },
            title = { Text("Override Field: $fieldKey") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Verified value") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("field_override_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateField(capId, fieldKey, draft)
                        editingTarget = null
                    },
                    modifier = Modifier.testTag("field_override_save_button")
                ) {
                    Text("Save & Re-Queue Sync")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Estimate Code Picker Dialog
    codePickerCaptureId?.let { capId ->
        val cap = captures.find { it.captureId == capId }
        val activeCodes = cap?.estimateCodes?.toSet().orEmpty()
        AlertDialog(
            onDismissRequest = { codePickerCaptureId = null },
            title = { Text("Select Estimate Codes") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EstimateCodeCatalog.items.forEach { def ->
                        val selected = activeCodes.contains(def.code)
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleEstimateCode(capId, def.code) },
                            label = { Text("${def.code}: ${def.title}") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { codePickerCaptureId = null }) { Text("Done") }
            }
        )
    }

    // Structured JSON Export Preview Dialog
    exportedJson?.let { raw ->
        AlertDialog(
            onDismissRequest = onDismissExportJson,
            title = { Text("Structured JobRecord JSON (v1.0)") },
            text = {
                Box(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = raw,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {
                Button(onClick = onDismissExportJson) { Text("Close") }
            }
        )
    }
}

@Composable
private fun CaptureEvidenceCard(
    capture: CaptureEvidenceUi,
    onEditField: (String, String) -> Unit,
    onOpenCodePicker: () -> Unit,
    onRetryExtraction: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("capture_card_${capture.captureId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${capture.commandType.name} • ${capture.captureId}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                        Surface(
                            color = if (capture.evidenceSource.isMockOrSample) {
                                StatusAmber.copy(alpha = 0.16f)
                            } else {
                                StatusEmerald.copy(alpha = 0.16f)
                            },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = capture.evidenceSource.displayLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (capture.evidenceSource.isMockOrSample) StatusAmber else StatusEmerald,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        text = "\"${capture.rawVoiceTranscript}\"",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete capture", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Thumbnail + Quality Gate Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val imgFile = capture.localImagePath?.let { File(it) }?.takeIf { it.exists() }
                if (imgFile != null) {
                    val bmp = remember(imgFile.absolutePath) {
                        BitmapFactory.decodeFile(imgFile.absolutePath)
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Captured inspection photo ${capture.captureId}",
                            modifier = Modifier
                                .size(88.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (capture.qualityReport.userOverridden) {
                            "Quality Overridden by Inspector"
                        } else {
                            capture.qualityReport.summaryMessage
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (capture.qualityReport.passed) StatusEmerald else StatusAmber
                    )
                    val statusLine = when {
                        capture.extraction != null ->
                            "Mean Confidence: ${(capture.extraction.overallConfidence * 100).toInt()}% • Sync: ${capture.syncStatus.name}"
                        capture.extractionError != null ->
                            "AI Extraction Failed • Confidence N/A • Sync: ${capture.syncStatus.name}"
                        else ->
                            "AI Extraction Pending • Sync: ${capture.syncStatus.name}"
                    }
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (capture.extractionError != null) StatusCrimson else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Per-field Extracted Values with Confidence Badges or Actionable Retry Banner
            if (capture.extractionError != null || capture.extraction == null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = capture.extractionError ?: "Waiting for AI extraction.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        OutlinedButton(
                            onClick = onRetryExtraction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("retry_extraction_${capture.captureId}")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry AI Extraction (Keep Saved Photo)")
                        }
                    }
                }
            }

            capture.extraction?.let { bundle ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    bundle.fields.forEach { (key, field) ->
                        ConfidenceFieldRow(
                            fieldKey = key,
                            field = field,
                            onEdit = { onEditField(key, field.value) }
                        )
                    }
                }
            }

            // Matched Estimate Codes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                capture.estimateCodes.forEach { code ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = code,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                OutlinedButton(
                    onClick = onOpenCodePicker,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Edit Codes", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ConfidenceFieldRow(
    fieldKey: String,
    field: ConfidenceField,
    onEdit: () -> Unit
) {
    val pct = (field.confidence * 100).toInt()
    val badgeColor = when {
        field.editedByUser -> ElectricCyan
        field.confidence >= 0.85f -> StatusEmerald
        field.confidence >= 0.60f -> StatusAmber
        else -> StatusCrimson
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onEdit)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fieldKey,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = field.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                color = badgeColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            ) {
                Text(
                    text = if (field.editedByUser) "EDITED 100%" else "$pct%",
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit $fieldKey",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
