package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.ai.GEMINI_MODEL_ID
import com.example.data.local.CaptureEvidenceEntity
import com.example.data.local.FieldCaptureDatabase
import com.example.data.local.InspectionDao
import com.example.data.local.InspectionJobEntity
import com.example.data.local.SyncOutboxEntity
import com.example.data.local.TelemetryMetricEntity
import com.example.data.remote.RestApiFactory
import com.example.data.remote.SyncTransportConfig
import com.example.data.sync.SyncWorker
import com.example.domain.model.CaptureCommandType
import com.example.domain.model.CaptureEvidenceUi
import com.example.domain.model.CaptureJson
import com.example.domain.model.ConfidenceField
import com.example.domain.model.EstimateCodeCatalog
import com.example.domain.model.EstimateLineItem
import com.example.domain.model.EvidenceSource
import com.example.domain.model.ExtractedDataBundle
import com.example.domain.model.JobRecordJson
import com.example.domain.model.OutboxItemUi
import com.example.domain.model.QualityReport
import com.example.domain.model.SessionSummaryJson
import com.example.domain.model.SyncBatchResult
import com.example.domain.model.SyncStatus
import com.example.domain.model.TelemetryRecordUi
import com.example.domain.model.TelemetrySummaryJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class FieldCaptureRepository internal constructor(
    private val appContext: Context?,
    private val dao: InspectionDao,
    val transportConfig: SyncTransportConfig = SyncTransportConfig(),
    private val scheduleWorkerOnEnqueue: Boolean = true
) {
    constructor(
        context: Context,
        transportConfig: SyncTransportConfig = SyncTransportConfig()
    ) : this(
        appContext = context.applicationContext,
        dao = FieldCaptureDatabase.getInstance(context.applicationContext).inspectionDao(),
        transportConfig = transportConfig
    )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val activeJobFlow: Flow<InspectionJobEntity?> = dao.observeActiveJob()

    fun observeCaptures(jobId: String): Flow<List<CaptureEvidenceUi>> =
        dao.observeCapturesForJob(jobId).map { list -> list.map { it.toUi() } }

    val outboxFlow: Flow<List<OutboxItemUi>> =
        dao.observeOutbox().map { list ->
            list.map { item ->
                OutboxItemUi(
                    outboxId = item.outboxId,
                    jobId = item.jobId,
                    captureId = item.captureId,
                    operationType = item.operationType,
                    idempotencyKey = item.idempotencyKey,
                    payloadPreview = item.payloadJson.take(260),
                    attemptCount = item.attemptCount,
                    lastHttpError = item.lastHttpError,
                    status = runCatching { SyncStatus.valueOf(item.status) }.getOrDefault(SyncStatus.QUEUED),
                    updatedAtEpochMs = item.updatedAtEpochMs
                )
            }
        }

    val telemetryFlow: Flow<List<TelemetryRecordUi>> =
        dao.observeTelemetry().map { list ->
            list.map {
                TelemetryRecordUi(
                    metricId = it.metricId,
                    captureId = it.captureId,
                    sourceMode = it.sourceMode,
                    qualityLatencyMs = it.qualityLatencyMs,
                    aiLatencyMs = it.aiLatencyMs,
                    syncLatencyMs = it.syncLatencyMs,
                    promptTokens = it.promptTokens,
                    outputTokens = it.outputTokens,
                    estimatedCostUsd = it.estimatedCostUsd,
                    modelId = it.modelId,
                    timestampEpochMs = it.timestampEpochMs
                )
            }
        }

    fun observeNetworkOnline(): Flow<Boolean> {
        val ctx = appContext ?: return flowOf(false)
        return callbackFlow {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            fun checkOnline(): Boolean {
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }

            trySend(checkOnline())
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(checkOnline())
                }

                override fun onLost(network: Network) {
                    trySend(checkOnline())
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    trySend(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }
            }
            val registered = runCatching {
                cm.registerDefaultNetworkCallback(callback)
                true
            }.getOrDefault(false)

            awaitClose {
                if (registered) {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
            }
        }.distinctUntilChanged()
    }

    suspend fun ensureDefaultJob(): InspectionJobEntity = withContext(Dispatchers.IO) {
        val existing = dao.getJobById("JOB-2026-4082")
        if (existing != null) return@withContext existing
        val now = System.currentTimeMillis()
        val job = InspectionJobEntity(
            jobId = "JOB-2026-4082",
            claimNumber = "CLM-PROP-88412",
            propertyAddress = "1420 Oakridge Ave, Austin TX 78704",
            inspectorName = "Alex Mercer (Field Adj.)",
            sessionState = "PAIRED_IDLE",
            disconnectsRecovered = 0,
            syncStatus = SyncStatus.LOCAL_ONLY.name,
            serverRevision = 0,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        dao.insertJobIfAbsent(job)
        dao.getJobById(job.jobId) ?: job
    }

    suspend fun updateJobSessionTelemetry(jobId: String, sessionState: String, disconnectsRecovered: Int) {
        withContext(Dispatchers.IO) {
            val job = dao.getJobById(jobId) ?: return@withContext
            if (job.sessionState == sessionState && job.disconnectsRecovered == disconnectsRecovered) {
                return@withContext
            }
            dao.updateJobSessionTelemetry(
                jobId = jobId,
                sessionState = sessionState,
                disconnectsRecovered = disconnectsRecovered,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }
    }

    fun computeBitmapSha256(bitmap: Bitmap): String {
        val w = bitmap.width
        val h = bitmap.height
        val rowPixels = IntArray(w)
        val digest = MessageDigest.getInstance("SHA-256")
        val rowBytes = ByteArray(w * 4)
        for (y in 0 until h) {
            bitmap.getPixels(rowPixels, 0, w, 0, y, w, 1)
            var bi = 0
            for (px in rowPixels) {
                rowBytes[bi++] = ((px ushr 24) and 0xFF).toByte()
                rowBytes[bi++] = ((px ushr 16) and 0xFF).toByte()
                rowBytes[bi++] = ((px ushr 8) and 0xFF).toByte()
                rowBytes[bi++] = (px and 0xFF).toByte()
            }
            digest.update(rowBytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun findDuplicateCapture(
        jobId: String,
        commandType: CaptureCommandType,
        transcript: String,
        room: String?,
        surface: String?,
        imageSha256: String?
    ): CaptureEvidenceEntity? = withContext(Dispatchers.IO) {
        val existingList = dao.getCapturesForJob(jobId)
        val cleanTranscript = transcript.trim()
        existingList.firstOrNull { cap ->
            if (cap.extractionError != null) return@firstOrNull false
            if (cap.commandType != commandType.name) return@firstOrNull false
            when (commandType) {
                CaptureCommandType.NOTE -> {
                    cap.rawVoiceTranscript.trim().equals(cleanTranscript, ignoreCase = true)
                }
                CaptureCommandType.NAMEPLATE -> {
                    imageSha256 != null && cap.imageSha256 == imageSha256
                }
                CaptureCommandType.DAMAGE -> {
                    imageSha256 != null &&
                        cap.imageSha256 == imageSha256 &&
                        cap.room.orEmpty().equals(room.orEmpty(), ignoreCase = true) &&
                        cap.surface.orEmpty().equals(surface.orEmpty(), ignoreCase = true)
                }
            }
        }
    }

    suspend fun getCaptureEntity(captureId: String): CaptureEvidenceEntity? = withContext(Dispatchers.IO) {
        dao.getCaptureById(captureId)
    }

    suspend fun saveCaptureImage(captureId: String, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val ctx = checkNotNull(appContext) { "Context required to save capture image" }
        val dir = File(ctx.filesDir, "captures").apply { mkdirs() }
        val file = File(dir, "$captureId.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        file.absolutePath
    }

    suspend fun saveInitialCapture(
        captureId: String,
        jobId: String,
        commandType: CaptureCommandType,
        transcript: String,
        room: String?,
        surface: String?,
        localImagePath: String?,
        qualityReport: QualityReport,
        evidenceSource: EvidenceSource = EvidenceSource.UNKNOWN,
        imageSha256: String? = null
    ) = withContext(Dispatchers.IO) {
        val entity = CaptureEvidenceEntity(
            captureId = captureId,
            jobId = jobId,
            commandType = commandType.name,
            rawVoiceTranscript = transcript,
            room = room,
            surface = surface,
            localImagePath = localImagePath,
            remoteMediaUrl = null,
            qualityJson = json.encodeToString(qualityReport),
            extractionJson = null,
            extractionError = null,
            overallConfidence = 0f,
            estimateCodesJson = "[]",
            syncStatus = SyncStatus.QUEUED.name,
            createdAtEpochMs = System.currentTimeMillis(),
            evidenceSource = evidenceSource.name,
            imageSha256 = imageSha256
        )
        dao.upsertCapture(entity)
        dao.updateJobSyncState(
            jobId = jobId,
            syncStatus = SyncStatus.QUEUED.name,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        enqueueCaptureSync(jobId, captureId)
    }

    suspend fun updateCaptureWithExtraction(
        captureId: String,
        bundle: ExtractedDataBundle?,
        errorMessage: String?,
        aiLatencyMs: Long,
        promptTokens: Int,
        outputTokens: Int,
        estimatedCostUsd: Double,
        qualityLatencyMs: Long,
        sourceMode: String = "LIVE_CAPTURE"
    ) = withContext(Dispatchers.IO) {
        val existing = dao.getCaptureById(captureId)
            ?: throw IllegalStateException("Capture record $captureId was not found in local database.")
        val updated = existing.copy(
            extractionJson = bundle?.let { json.encodeToString(it) },
            extractionError = errorMessage,
            overallConfidence = bundle?.overallConfidence ?: 0f,
            estimateCodesJson = json.encodeToString(bundle?.suggestedEstimateCodes ?: emptyList<String>())
        )
        dao.upsertCapture(updated)
        dao.updateJobSyncState(
            jobId = existing.jobId,
            syncStatus = SyncStatus.QUEUED.name,
            updatedAtEpochMs = System.currentTimeMillis()
        )

        dao.insertTelemetry(
            TelemetryMetricEntity(
                metricId = UUID.randomUUID().toString(),
                jobId = existing.jobId,
                captureId = captureId,
                sourceMode = sourceMode,
                qualityLatencyMs = qualityLatencyMs,
                aiLatencyMs = aiLatencyMs,
                syncLatencyMs = 0L,
                promptTokens = promptTokens,
                outputTokens = outputTokens,
                estimatedCostUsd = estimatedCostUsd,
                modelId = GEMINI_MODEL_ID,
                timestampEpochMs = System.currentTimeMillis()
            )
        )

        enqueueCaptureSync(existing.jobId, captureId)
    }

    suspend fun recordEvalTelemetry(
        jobId: String,
        captureId: String,
        qualityLatencyMs: Long,
        aiLatencyMs: Long,
        promptTokens: Int,
        outputTokens: Int,
        estimatedCostUsd: Double,
        modelId: String
    ) = withContext(Dispatchers.IO) {
        dao.insertTelemetry(
            TelemetryMetricEntity(
                metricId = UUID.randomUUID().toString(),
                jobId = jobId,
                captureId = captureId,
                sourceMode = "EVAL_BATCH",
                qualityLatencyMs = qualityLatencyMs,
                aiLatencyMs = aiLatencyMs,
                syncLatencyMs = 0L,
                promptTokens = promptTokens,
                outputTokens = outputTokens,
                estimatedCostUsd = estimatedCostUsd,
                modelId = modelId,
                timestampEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateExtractedField(
        captureId: String,
        fieldKey: String,
        newValue: String
    ) = withContext(Dispatchers.IO) {
        val entity = dao.getCaptureById(captureId) ?: return@withContext
        val bundle = entity.extractionJson?.let {
            runCatching { json.decodeFromString<ExtractedDataBundle>(it) }.getOrNull()
        } ?: return@withContext

        val mutable = bundle.fields.toMutableMap()
        val old = mutable[fieldKey]
        mutable[fieldKey] = ConfidenceField(
            value = newValue.trim(),
            confidence = 1.0f,
            needsReview = false,
            editedByUser = true
        )
        val updatedBundle = bundle.copy(
            fields = mutable,
            overallConfidence = if (mutable.isNotEmpty()) {
                mutable.values.map { it.confidence }.average().toFloat()
            } else {
                old?.confidence ?: 1f
            }
        )
        dao.upsertCapture(
            entity.copy(
                extractionJson = json.encodeToString(updatedBundle),
                overallConfidence = updatedBundle.overallConfidence,
                syncStatus = SyncStatus.QUEUED.name
            )
        )
        enqueueJobRecordSync(entity.jobId)
    }

    suspend fun toggleEstimateCode(captureId: String, code: String) = withContext(Dispatchers.IO) {
        val entity = dao.getCaptureById(captureId) ?: return@withContext
        val currentCodes = runCatching {
            json.decodeFromString<List<String>>(entity.estimateCodesJson)
        }.getOrDefault(emptyList()).toMutableList()
        if (currentCodes.contains(code)) currentCodes.remove(code) else currentCodes.add(code)
        dao.upsertCapture(
            entity.copy(
                estimateCodesJson = json.encodeToString(currentCodes),
                syncStatus = SyncStatus.QUEUED.name
            )
        )
        enqueueJobRecordSync(entity.jobId)
    }

    suspend fun deleteCapture(captureId: String) = withContext(Dispatchers.IO) {
        dao.deleteCapture(captureId)
    }

    suspend fun enqueueCaptureSync(jobId: String, captureId: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val capture = dao.getCaptureById(captureId)
        if (capture?.localImagePath != null && capture.remoteMediaUrl == null) {
            val mediaOutboxId = "MEDIA-$captureId"
            val existingMedia = dao.getOutboxItemById(mediaOutboxId)
            if (existingMedia?.status != SyncStatus.SYNCED.name) {
                val payload = """{"captureId":"$captureId","file":"${File(capture.localImagePath).name}"}"""
                dao.upsertOutboxItem(
                    SyncOutboxEntity(
                        outboxId = mediaOutboxId,
                        jobId = jobId,
                        captureId = captureId,
                        operationType = "UPLOAD_MEDIA",
                        idempotencyKey = existingMedia?.idempotencyKey ?: UUID.randomUUID().toString(),
                        payloadJson = payload,
                        attemptCount = existingMedia?.attemptCount ?: 0,
                        lastHttpError = existingMedia?.lastHttpError,
                        status = SyncStatus.QUEUED.name,
                        createdAtEpochMs = existingMedia?.createdAtEpochMs ?: now,
                        updatedAtEpochMs = now
                    )
                )
            }
        }
        enqueueJobRecordSync(jobId)
        scheduleAutoSyncWorker()
    }

    suspend fun enqueueJobRecordSync(jobId: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val jobJson = buildStructuredJobJsonString(jobId)
        val outboxId = "JOBSYNC-$jobId"
        val existing = dao.getOutboxItemById(outboxId)
        val reusable = existing?.takeIf {
            it.status == SyncStatus.QUEUED.name || it.status == SyncStatus.FAILED.name
        }

        dao.upsertOutboxItem(
            SyncOutboxEntity(
                outboxId = outboxId,
                jobId = jobId,
                captureId = null,
                operationType = "SYNC_JOB",
                idempotencyKey = reusable?.idempotencyKey ?: UUID.randomUUID().toString(),
                payloadJson = jobJson,
                attemptCount = reusable?.attemptCount ?: 0,
                lastHttpError = reusable?.lastHttpError,
                status = SyncStatus.QUEUED.name,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now
            )
        )
        scheduleAutoSyncWorker()
    }

    fun scheduleAutoSyncWorker() {
        if (!scheduleWorkerOnEnqueue) return
        val ctx = appContext ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "fieldcapture_outbox_sync",
            ExistingWorkPolicy.REPLACE,
            work
        )
    }

    fun isNetworkConnected(): Boolean {
        val ctx = appContext ?: return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun processPendingOutbox(): SyncBatchResult = withContext(Dispatchers.IO) {
        val pending = dao.getPendingOutboxItems()
        if (pending.isEmpty()) return@withContext SyncBatchResult(syncedCount = 0, failedCount = 0)

        val api = RestApiFactory.create(transportConfig)
        var syncedCount = 0
        var failedCount = 0
        val failedCaptureIds = mutableSetOf<String>()

        // Upload media before job JSON sync
        val ordered = pending.sortedBy { if (it.operationType == "UPLOAD_MEDIA") 0 else 1 }
        for (item in ordered) {
            val startNs = System.nanoTime()
            dao.upsertOutboxItem(
                item.copy(status = SyncStatus.SYNCING.name, updatedAtEpochMs = System.currentTimeMillis())
            )
            try {
                if (item.operationType == "UPLOAD_MEDIA" && item.captureId != null) {
                    val cap = dao.getCaptureById(item.captureId)
                    val path = cap?.localImagePath
                    if (cap != null && path != null && File(path).exists()) {
                        val file = File(path)
                        val bodyPart = MultipartBody.Part.createFormData(
                            "image",
                            file.name,
                            file.asRequestBody("image/jpeg".toMediaType())
                        )
                        val metaBody = cap.qualityJson.toRequestBody("application/json".toMediaType())
                        val resp = api.uploadMedia(
                            jobId = item.jobId,
                            captureId = item.captureId,
                            idempotencyKey = item.idempotencyKey,
                            image = bodyPart,
                            metadataJson = metaBody
                        )
                        dao.upsertCapture(
                            cap.copy(
                                remoteMediaUrl = resp.mediaUrl,
                                syncStatus = SyncStatus.SYNCED.name
                            )
                        )
                    }
                } else {
                    val freshPayload = buildStructuredJobJsonString(item.jobId)
                    val body = freshPayload.toRequestBody("application/json".toMediaType())
                    val resp = api.syncJobRecord(
                        jobId = item.jobId,
                        idempotencyKey = item.idempotencyKey,
                        jobRecordJson = body
                    )
                    val jobSyncStatus = if (failedCaptureIds.isEmpty()) SyncStatus.SYNCED.name else SyncStatus.QUEUED.name
                    dao.updateJobSynced(
                        jobId = item.jobId,
                        serverRevision = resp.serverRevision,
                        syncStatus = jobSyncStatus,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                    dao.getCapturesForJob(item.jobId).forEach { cap ->
                        val hasFailedMedia = cap.captureId in failedCaptureIds
                        val mediaReady = cap.localImagePath == null || cap.remoteMediaUrl != null || !File(cap.localImagePath).exists()
                        if (!hasFailedMedia && mediaReady && cap.syncStatus != SyncStatus.SYNCED.name) {
                            dao.upsertCapture(cap.copy(syncStatus = SyncStatus.SYNCED.name))
                        } else if (hasFailedMedia && cap.syncStatus != SyncStatus.FAILED.name) {
                            dao.upsertCapture(cap.copy(syncStatus = SyncStatus.FAILED.name))
                        }
                    }
                }
                val syncMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                dao.upsertOutboxItem(
                    item.copy(
                        status = SyncStatus.SYNCED.name,
                        attemptCount = item.attemptCount + 1,
                        lastHttpError = null,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
                if (item.captureId != null) {
                    dao.insertTelemetry(
                        TelemetryMetricEntity(
                            metricId = UUID.randomUUID().toString(),
                            jobId = item.jobId,
                            captureId = item.captureId,
                            sourceMode = "REST_SYNC",
                            qualityLatencyMs = 0L,
                            aiLatencyMs = 0L,
                            syncLatencyMs = syncMs,
                            promptTokens = 0,
                            outputTokens = 0,
                            estimatedCostUsd = 0.0,
                            modelId = "rest-sync",
                            timestampEpochMs = System.currentTimeMillis()
                        )
                    )
                }
                syncedCount++
            } catch (e: Exception) {
                failedCount++
                item.captureId?.let { cid ->
                    failedCaptureIds += cid
                    dao.getCaptureById(cid)?.let { cap ->
                        dao.upsertCapture(cap.copy(syncStatus = SyncStatus.FAILED.name))
                    }
                }
                dao.upsertOutboxItem(
                    item.copy(
                        status = SyncStatus.FAILED.name,
                        attemptCount = item.attemptCount + 1,
                        lastHttpError = e.localizedMessage ?: "HTTP sync error",
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }
        }
        SyncBatchResult(syncedCount = syncedCount, failedCount = failedCount)
    }

    suspend fun clearSyncedOutbox() = withContext(Dispatchers.IO) {
        dao.clearSyncedOutbox()
    }

    suspend fun buildStructuredJobJsonString(jobId: String): String = withContext(Dispatchers.IO) {
        val job = dao.getJobById(jobId) ?: ensureDefaultJob()
        val captures = dao.getCapturesForJob(jobId)
        val telemetry = dao.getAllTelemetry()

        val captureJsons = captures.map { cap ->
            val quality = runCatching { json.decodeFromString<QualityReport>(cap.qualityJson) }
                .getOrElse { QualityReport(false, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList(), "Unknown", 0L) }
            val bundle = cap.extractionJson?.let {
                runCatching { json.decodeFromString<ExtractedDataBundle>(it) }.getOrNull()
            }
            val codes = runCatching { json.decodeFromString<List<String>>(cap.estimateCodesJson) }
                .getOrDefault(emptyList())
            val sourceEnum = runCatching { EvidenceSource.valueOf(cap.evidenceSource) }
                .getOrDefault(EvidenceSource.UNKNOWN)
            CaptureJson(
                captureId = cap.captureId,
                commandType = runCatching { CaptureCommandType.valueOf(cap.commandType) }
                    .getOrDefault(CaptureCommandType.NOTE),
                timestampEpochMs = cap.createdAtEpochMs,
                rawVoiceTranscript = cap.rawVoiceTranscript,
                room = cap.room,
                surface = cap.surface,
                evidenceSource = sourceEnum.name,
                isMockOrSample = sourceEnum.isMockOrSample,
                remoteMediaUrl = cap.remoteMediaUrl,
                qualityCheck = quality,
                extractedFields = bundle?.fields ?: emptyMap(),
                overallConfidence = cap.overallConfidence,
                estimateCodes = codes
            )
        }

        val lineItems = buildEstimateLineItems(captures.map { it.toUi() })
        val passRate = if (captureJsons.isEmpty()) 1f else {
            captureJsons.count { it.qualityCheck.passed || it.qualityCheck.userOverridden }.toFloat() / captureJsons.size
        }
        val aiTelemetry = telemetry.filter { it.aiLatencyMs > 0L }
        val syncTelemetry = telemetry.filter { it.syncLatencyMs > 0L }
        val qTelemetry = telemetry.filter { it.qualityLatencyMs > 0L }

        val record = JobRecordJson(
            jobId = job.jobId,
            claimNumber = job.claimNumber,
            propertyAddress = job.propertyAddress,
            inspectorName = job.inspectorName,
            createdAtEpochMs = job.createdAtEpochMs,
            sessionSummary = SessionSummaryJson(
                totalCaptures = captureJsons.size,
                disconnectsRecovered = job.disconnectsRecovered,
                avgQualityPassRate = passRate
            ),
            captures = captureJsons,
            estimateLineItems = lineItems,
            telemetrySummary = TelemetrySummaryJson(
                totalRequests = aiTelemetry.size,
                avgQualityLatencyMs = qTelemetry.map { it.qualityLatencyMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L,
                avgAiLatencyMs = aiTelemetry.map { it.aiLatencyMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L,
                avgSyncLatencyMs = syncTelemetry.map { it.syncLatencyMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L,
                totalPromptTokens = telemetry.sumOf { it.promptTokens },
                totalOutputTokens = telemetry.sumOf { it.outputTokens },
                totalEstimatedCostUsd = telemetry.sumOf { it.estimatedCostUsd }
            )
        )
        json.encodeToString(record)
    }

    fun buildEstimateLineItems(captures: List<CaptureEvidenceUi>): List<EstimateLineItem> {
        val result = mutableListOf<EstimateLineItem>()
        val seen = mutableSetOf<String>()
        for (cap in captures) {
            for (code in cap.estimateCodes) {
                val def = EstimateCodeCatalog.byCode(code) ?: continue
                if (seen.add("${cap.captureId}:$code")) {
                    result += EstimateLineItem(
                        code = def.code,
                        trade = def.trade,
                        description = def.title,
                        unit = def.unit,
                        quantity = def.defaultQty,
                        unitRateUsd = def.unitRateUsd,
                        extendedUsd = def.defaultQty * def.unitRateUsd,
                        confidence = cap.extraction?.overallConfidence ?: 0.75f,
                        sourceCaptureId = cap.captureId
                    )
                }
            }
        }
        return result
    }

    private fun CaptureEvidenceEntity.toUi(): CaptureEvidenceUi {
        val quality = runCatching { json.decodeFromString<QualityReport>(qualityJson) }
            .getOrElse { QualityReport(false, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList(), "Unknown", 0L) }
        val bundle = extractionJson?.let {
            runCatching { json.decodeFromString<ExtractedDataBundle>(it) }.getOrNull()
        }
        val codes = runCatching { json.decodeFromString<List<String>>(estimateCodesJson) }
            .getOrDefault(emptyList())
        val sourceEnum = runCatching { EvidenceSource.valueOf(evidenceSource) }
            .getOrDefault(EvidenceSource.UNKNOWN)
        return CaptureEvidenceUi(
            captureId = captureId,
            jobId = jobId,
            commandType = runCatching { CaptureCommandType.valueOf(commandType) }.getOrDefault(CaptureCommandType.NOTE),
            rawVoiceTranscript = rawVoiceTranscript,
            room = room,
            surface = surface,
            localImagePath = localImagePath,
            remoteMediaUrl = remoteMediaUrl,
            qualityReport = quality,
            extraction = bundle,
            extractionError = extractionError,
            estimateCodes = codes,
            syncStatus = runCatching { SyncStatus.valueOf(syncStatus) }.getOrDefault(SyncStatus.QUEUED),
            createdAtEpochMs = createdAtEpochMs,
            evidenceSource = sourceEnum,
            imageSha256 = imageSha256
        )
    }
}
