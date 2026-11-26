package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "inspection_jobs")
data class InspectionJobEntity(
    @PrimaryKey val jobId: String,
    val claimNumber: String,
    val propertyAddress: String,
    val inspectorName: String,
    val sessionState: String,
    val disconnectsRecovered: Int,
    val syncStatus: String,
    val serverRevision: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "capture_evidences",
    foreignKeys = [
        ForeignKey(
            entity = InspectionJobEntity::class,
            parentColumns = ["jobId"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("jobId")]
)
data class CaptureEvidenceEntity(
    @PrimaryKey val captureId: String,
    val jobId: String,
    val commandType: String,
    val rawVoiceTranscript: String,
    val room: String?,
    val surface: String?,
    val localImagePath: String?,
    val remoteMediaUrl: String?,
    val qualityJson: String,
    val extractionJson: String?,
    val extractionError: String?,
    val overallConfidence: Float,
    val estimateCodesJson: String,
    val syncStatus: String,
    val createdAtEpochMs: Long,
    @ColumnInfo(defaultValue = "UNKNOWN") val evidenceSource: String = "UNKNOWN",
    val imageSha256: String? = null
)

@Entity(
    tableName = "sync_outbox",
    indices = [Index("jobId"), Index("status")]
)
data class SyncOutboxEntity(
    @PrimaryKey val outboxId: String,
    val jobId: String,
    val captureId: String?,
    val operationType: String,
    val idempotencyKey: String,
    val payloadJson: String,
    val attemptCount: Int,
    val lastHttpError: String?,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "telemetry_metrics",
    indices = [Index("jobId"), Index("sourceMode")]
)
data class TelemetryMetricEntity(
    @PrimaryKey val metricId: String,
    val jobId: String,
    val captureId: String,
    val sourceMode: String,
    val qualityLatencyMs: Long,
    val aiLatencyMs: Long,
    val syncLatencyMs: Long,
    val promptTokens: Int,
    val outputTokens: Int,
    val estimatedCostUsd: Double,
    val modelId: String,
    val timestampEpochMs: Long
)
