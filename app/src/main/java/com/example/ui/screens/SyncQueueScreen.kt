package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.HttpTraceLog
import com.example.domain.model.OutboxItemUi
import com.example.domain.model.SyncStatus
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusEmerald

@Composable
fun SyncQueueScreen(
    outboxItems: List<OutboxItemUi>,
    httpTraces: List<HttpTraceLog>,
    loopbackMode: Boolean,
    baseUrl: String,
    isNetworkOnline: Boolean,
    isSyncingNow: Boolean,
    onToggleLoopback: (Boolean) -> Unit,
    onUpdateBaseUrl: (String) -> Unit,
    onSimulateHttp503: () -> Unit,
    onSyncNow: () -> Unit,
    onClearSynced: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draftUrl by remember(baseUrl) { mutableStateOf(baseUrl) }
    val pendingCount = outboxItems.count { it.status == SyncStatus.QUEUED || it.status == SyncStatus.FAILED }
    val syncedCount = outboxItems.count { it.status == SyncStatus.SYNCED }

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
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Offline-First REST Sync Queue", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "Pending: $pendingCount • Synced: $syncedCount • Net: ${if (isNetworkOnline) "ONLINE" else "OFFLINE"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isNetworkOnline) StatusEmerald else StatusAmber
                            )
                        }
                        Icon(
                            imageVector = if (isNetworkOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                            contentDescription = "Network connectivity state",
                            tint = if (isNetworkOnline) StatusEmerald else StatusAmber
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Loopback Mock REST Server", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                text = "Intercepts Retrofit/OkHttp with real JSON & idempotency headers",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = loopbackMode,
                            onCheckedChange = onToggleLoopback,
                            modifier = Modifier.testTag("sync_loopback_switch")
                        )
                    }

                    if (!loopbackMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = draftUrl,
                                onValueChange = { draftUrl = it },
                                label = { Text("REST Base URL") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(
                                onClick = { onUpdateBaseUrl(draftUrl) },
                                modifier = Modifier.heightIn(min = 52.dp)
                            ) {
                                Text("Set")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSyncNow,
                            enabled = !isSyncingNow,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("sync_now_button")
                        ) {
                            if (isSyncingNow) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.sync_now))
                        }
                        OutlinedButton(
                            onClick = onSimulateHttp503,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("simulate_http_503_button")
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Sim 503")
                        }
                        OutlinedButton(
                            onClick = onClearSynced,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("clear_synced_button")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear synced items", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // Room Outbox Items
        item {
            Text(
                text = "Room Outbox Operations (${outboxItems.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (outboxItems.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Outbox empty. Capturing evidence automatically queues media & job JSON sync operations.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(outboxItems, key = { it.outboxId }) { item ->
            val statusColor = when (item.status) {
                SyncStatus.SYNCED -> StatusEmerald
                SyncStatus.SYNCING -> ElectricCyan
                SyncStatus.QUEUED, SyncStatus.LOCAL_ONLY -> StatusAmber
                SyncStatus.FAILED -> StatusCrimson
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${item.operationType} • ${item.outboxId}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            color = statusColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "${item.status.name} (try #${item.attemptCount})",
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(
                        text = "X-Idempotency-Key: ${item.idempotencyKey}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = item.payloadPreview,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    item.lastHttpError?.let { err ->
                        Text(
                            text = "Last error: $err",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusCrimson
                        )
                    }
                }
            }
        }

        // HTTP Trace Log
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Http, contentDescription = null, tint = ElectricCyan)
                Text("REST Transport Trace (${httpTraces.size})", style = MaterialTheme.typography.titleMedium)
            }
        }

        items(httpTraces, key = { it.id }) { trace ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${trace.method} ${trace.path}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "HTTP ${trace.statusCode} • ${trace.latencyMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (trace.statusCode in 200..299) StatusEmerald else StatusCrimson
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Text(
                        text = "${trace.summary} • key=${trace.idempotencyKey.take(12)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
