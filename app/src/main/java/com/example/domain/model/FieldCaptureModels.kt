package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class CaptureCommandType {
    NAMEPLATE,
    DAMAGE,
    NOTE
}

@Serializable
enum class QualityIssue {
    BLURRY,
    TOO_DARK,
    OVEREXPOSED,
    POOR_FRAMING
}

@Serializable
data class QualityReport(
    val passed: Boolean,
    val blurScore: Double,
    val brightnessScore: Double,
    val framingScore: Double,
    val darkPixelRatio: Double,
    val clippedPixelRatio: Double,
    val issues: List<QualityIssue>,
    val summaryMessage: String,
    val latencyMs: Long,
    val userOverridden: Boolean = false
)

@Serializable
data class ConfidenceField(
    val value: String,
    val confidence: Float,
    val needsReview: Boolean = confidence < 0.80f,
    val editedByUser: Boolean = false
)

@Serializable
data class ExtractedDataBundle(
    val fields: Map<String, ConfidenceField>,
    val overallConfidence: Float,
    val summary: String,
    val suggestedEstimateCodes: List<String>
)

@Serializable
data class EstimateCodeDefinition(
    val code: String,
    val trade: String,
    val title: String,
    val unit: String,
    val defaultQty: Double,
    val unitRateUsd: Double,
    val keywords: List<String>
)

@Serializable
data class EstimateLineItem(
    val code: String,
    val trade: String,
    val description: String,
    val unit: String,
    val quantity: Double,
    val unitRateUsd: Double,
    val extendedUsd: Double,
    val confidence: Float,
    val sourceCaptureId: String
)

object EstimateCodeCatalog {
    val items: List<EstimateCodeDefinition> = listOf(
        EstimateCodeDefinition("WTR-DRY-01", "Water Mitigation", "Structural Drying & Dehumidification Setup", "day", 3.0, 145.0, listOf("water", "moisture", "wet", "dehumidifier", "drying", "basement", "flood")),
        EstimateCodeDefinition("DRY-RPL-02", "Drywall", "Drywall Removal & 1/2-in Replacement + Tape/Float", "sq ft", 32.0, 9.75, listOf("drywall", "gypsum", "wall", "ceiling", "stain", "peeling", "bubbling", "hole")),
        EstimateCodeDefinition("PNT-PRM-03", "Paint & Finish", "Stain-Blocking Primer & Two-Coat Interior Paint", "sq ft", 64.0, 3.40, listOf("paint", "primer", "stain", "peeling", "wall", "ceiling")),
        EstimateCodeDefinition("INS-BAT-04", "Insulation", "Wet Batt Insulation Removal & R-19 Replacement", "sq ft", 32.0, 4.25, listOf("insulation", "batt", "fiberglass", "wet", "attic", "wall")),
        EstimateCodeDefinition("FLR-LVP-05", "Flooring", "Water-Damaged Flooring Tear-Out & Subfloor Prep", "sq ft", 45.0, 6.80, listOf("floor", "flooring", "subfloor", "baseboard", "carpet", "vinyl", "hardwood")),
        EstimateCodeDefinition("MLD-REM-06", "Remediation", "Antimicrobial Treatment & HEPA Air Scrubbing", "sq ft", 40.0, 5.50, listOf("mold", "mildew", "microbial", "spores", "musty", "hepa")),
        EstimateCodeDefinition("RFG-SHG-07", "Roofing", "Roof Shingle & Flashing Storm Damage Repair", "sq", 2.0, 465.0, listOf("roof", "shingle", "flashing", "hail", "wind", "leak", "soffit")),
        EstimateCodeDefinition("ELC-PNL-08", "Electrical", "Main Service Panel Inspection & Breaker Replacement", "ea", 1.0, 685.0, listOf("panel", "breaker", "main", "lug", "scorch", "200a", "100a", "bus", "electrical")),
        EstimateCodeDefinition("ELC-GFC-09", "Electrical", "GFCI / Arc-Fault Receptacle & Circuit Fault Repair", "ea", 2.0, 195.0, listOf("gfci", "afci", "outlet", "receptacle", "circuit", "arc", "ground")),
        EstimateCodeDefinition("HVC-CMP-10", "HVAC", "Condenser / Compressor Diagnostics & Unit Service", "ea", 1.0, 1250.0, listOf("condenser", "compressor", "hvac", "ac", "carrier", "trane", "lennox", "ton", "24acc")),
        EstimateCodeDefinition("HVC-COI-11", "HVAC", "Evaporator Coil Cleaning & Refrigerant Leak Service", "ea", 1.0, 540.0, listOf("coil", "evaporator", "refrigerant", "r-410a", "r410a", "r-22", "freon", "leak")),
        EstimateCodeDefinition("HVC-BLW-12", "HVAC", "Furnace / Air Handler Blower Motor & Capacitor Service", "ea", 1.0, 430.0, listOf("blower", "furnace", "air handler", "capacitor", "motor", "heat")),
        EstimateCodeDefinition("HVC-DCT-13", "HVAC", "Damaged Ductwork Sealing & Flex/Sheet Replacement", "lf", 18.0, 28.0, listOf("duct", "ductwork", "plenum", "vent", "register", "air")),
        EstimateCodeDefinition("GEN-SAF-14", "General Safety", "Emergency Make-Safe & Utility Isolation", "hr", 2.0, 165.0, listOf("hazard", "safety", "scorch", "shutoff", "burn", "smoke", "emergency"))
    )

    fun byCode(code: String): EstimateCodeDefinition? = items.find { it.code.equals(code, ignoreCase = true) }

    fun matchFromText(text: String, maxMatches: Int = 3): List<EstimateCodeDefinition> {
        val lower = text.lowercase()
        return items
            .map { def -> def to def.keywords.count { kw -> lower.contains(kw) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(maxMatches)
            .map { it.first }
    }
}

enum class SessionLifecycleState {
    DISCONNECTED,
    PAIRED_IDLE,
    STARTING,
    STREAMING,
    PAUSED,
    RECONNECTING,
    STOPPED,
    ERROR
}

enum class SyncStatus {
    LOCAL_ONLY,
    QUEUED,
    SYNCING,
    SYNCED,
    FAILED
}

data class MockHardwareSnapshot(
    val mockEnabled: Boolean = false,
    val paired: Boolean = false,
    val deviceName: String = "No Device",
    val poweredOn: Boolean = false,
    val unfolded: Boolean = true,
    val donned: Boolean = true,
    val batteryPercent: Int = 88,
    val cameraSourceLabel: String = "Sample Feed (Nameplate)",
    val usingDeviceCamera: Boolean = false,
    val registrationState: String = "UNREGISTERED",
    val streamStateLabel: String = "STOPPED"
)

enum class EvidenceSource(val displayLabel: String, val isMockOrSample: Boolean) {
    MOCK_DEVICE_KIT_SCENE("Mock Device Kit Sample Scene", true),
    ANDROID_REAR_CAMERA_MOCK("Android Rear Camera (DAT Mock Bridge)", false),
    META_DAT_GLASSES_CAMERA("Meta AI Glasses Camera (DAT 1.0)", false),
    PHOTO_PICKER_IMPORT("Photo Picker Import", false),
    VOICE_NOTE_ONLY("Voice Note (No Image)", false),
    UNKNOWN("Unverified Source", true)
}

data class SyncBatchResult(
    val syncedCount: Int,
    val failedCount: Int
)

data class CaptureEvidenceUi(
    val captureId: String,
    val jobId: String,
    val commandType: CaptureCommandType,
    val rawVoiceTranscript: String,
    val room: String?,
    val surface: String?,
    val localImagePath: String?,
    val remoteMediaUrl: String?,
    val qualityReport: QualityReport,
    val extraction: ExtractedDataBundle?,
    val extractionError: String?,
    val estimateCodes: List<String>,
    val syncStatus: SyncStatus,
    val createdAtEpochMs: Long,
    val evidenceSource: EvidenceSource = EvidenceSource.UNKNOWN,
    val imageSha256: String? = null
)

data class OutboxItemUi(
    val outboxId: String,
    val jobId: String,
    val captureId: String?,
    val operationType: String,
    val idempotencyKey: String,
    val payloadPreview: String,
    val attemptCount: Int,
    val lastHttpError: String?,
    val status: SyncStatus,
    val updatedAtEpochMs: Long
)

data class HttpTraceLog(
    val id: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val latencyMs: Long,
    val idempotencyKey: String,
    val summary: String,
    val timestampEpochMs: Long
)

data class TelemetryRecordUi(
    val metricId: String,
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

data class EvalFrameResultUi(
    val index: Int,
    val frameLabel: String,
    val sourceKind: String,
    val commandType: CaptureCommandType,
    val isLowLightCase: Boolean = false,
    val qualityPassed: Boolean,
    val blurScore: Double,
    val brightnessScore: Double,
    val framingScore: Double,
    val qualityIssues: List<QualityIssue>,
    val aiSucceeded: Boolean,
    val meanConfidence: Float,
    val matchedCodes: List<String>,
    val extractedFieldConfidences: Map<String, Float> = emptyMap(),
    val qualityLatencyMs: Long,
    val aiLatencyMs: Long,
    val promptTokens: Int,
    val outputTokens: Int,
    val estimatedCostUsd: Double,
    val notes: String,
    val likelyFailureCause: String? = null
)

data class EvalFailureCaseUi(
    val frameIndex: Int,
    val frameLabel: String,
    val commandType: CaptureCommandType,
    val summary: String,
    val likelyCause: String
)

data class EvalBatchSummaryUi(
    val totalFrames: Int = 0,
    val completedFrames: Int = 0,
    val isRunning: Boolean = false,
    val realFrameCount: Int = 0,
    val syntheticFrameCount: Int = 0,
    val nameplateCount: Int = 0,
    val lowLightCount: Int = 0,
    val qualityPassCount: Int = 0,
    val blurRejectedCount: Int = 0,
    val darkRejectedCount: Int = 0,
    val overexposedRejectedCount: Int = 0,
    val framingRejectedCount: Int = 0,
    val aiSuccessCount: Int = 0,
    val aiFailureCount: Int = 0,
    val meanConfidence: Float = 0f,
    val perFieldMeanConfidence: Map<String, Float> = emptyMap(),
    val worstFailures: List<EvalFailureCaseUi> = emptyList(),
    val p50TotalLatencyMs: Long = 0L,
    val p95TotalLatencyMs: Long = 0L,
    val totalTokens: Int = 0,
    val totalCostUsd: Double = 0.0,
    val useLiveAiInBatch: Boolean = false,
    val results: List<EvalFrameResultUi> = emptyList(),
    val exportedJsonReport: String? = null
)

@Serializable
data class JobRecordJson(
    val schemaVersion: String = "1.0",
    val jobId: String,
    val claimNumber: String,
    val propertyAddress: String,
    val inspectorName: String,
    val createdAtEpochMs: Long,
    val sessionSummary: SessionSummaryJson,
    val captures: List<CaptureJson>,
    val estimateLineItems: List<EstimateLineItem>,
    val telemetrySummary: TelemetrySummaryJson
)

@Serializable
data class SessionSummaryJson(
    val totalCaptures: Int,
    val disconnectsRecovered: Int,
    val avgQualityPassRate: Float
)

@Serializable
data class CaptureJson(
    val captureId: String,
    val commandType: CaptureCommandType,
    val timestampEpochMs: Long,
    val rawVoiceTranscript: String,
    val room: String?,
    val surface: String?,
    val evidenceSource: String = EvidenceSource.UNKNOWN.name,
    val isMockOrSample: Boolean = true,
    val remoteMediaUrl: String?,
    val qualityCheck: QualityReport,
    val extractedFields: Map<String, ConfidenceField>,
    val overallConfidence: Float,
    val estimateCodes: List<String>
)

@Serializable
data class TelemetrySummaryJson(
    val totalRequests: Int,
    val avgQualityLatencyMs: Long,
    val avgAiLatencyMs: Long,
    val avgSyncLatencyMs: Long,
    val totalPromptTokens: Int,
    val totalOutputTokens: Int,
    val totalEstimatedCostUsd: Double
)
