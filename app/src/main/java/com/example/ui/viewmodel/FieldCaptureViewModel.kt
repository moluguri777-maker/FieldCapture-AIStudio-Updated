package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiExtractionService
import com.example.cv.ImageQualityAnalyzer
import com.example.data.local.InspectionJobEntity
import com.example.data.repository.FieldCaptureRepository
import com.example.domain.model.CaptureCommandType
import com.example.domain.model.CaptureEvidenceUi
import com.example.domain.model.EstimateLineItem
import com.example.domain.model.EvalBatchSummaryUi
import com.example.domain.model.EvidenceSource
import com.example.domain.model.HttpTraceLog
import com.example.domain.model.OutboxItemUi
import com.example.domain.model.QualityReport
import com.example.domain.model.SessionLifecycleState
import com.example.domain.model.TelemetryRecordUi
import com.example.eval.BatchEvaluationEngine
import com.example.voice.ParsedVoiceCommand
import com.example.voice.VoiceCommandParser
import com.example.voice.VoiceInputController
import com.example.voice.VoiceParseResult
import com.example.voice.VoiceUiState
import com.example.wearable.DatWearableSessionGateway
import com.example.wearable.MockFeedScene
import com.example.wearable.WearableGatewayState
import com.example.wearable.WearableSessionGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val VM_TAG = "FieldCaptureViewModel"

data class PendingQualityReview(
    val command: ParsedVoiceCommand,
    val bitmap: Bitmap,
    val evidenceSource: EvidenceSource,
    val qualityReport: QualityReport
)

data class CapturePipelineUiState(
    val isExtracting: Boolean = false,
    val activeStepMessage: String? = null,
    val commandFeedback: String? = null,
    val feedbackIsError: Boolean = false,
    val pendingQualityReview: PendingQualityReview? = null,
    val liveQualityPreview: QualityReport? = null,
    val isDarkFieldTheme: Boolean = true
)

class FieldCaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FieldCaptureRepository(application)
    private val qualityAnalyzer = ImageQualityAnalyzer()
    private val geminiService = GeminiExtractionService(application.applicationContext)
    private val evalEngine = BatchEvaluationEngine(application, qualityAnalyzer, geminiService, repository)
    private val wearableGateway: WearableSessionGateway = DatWearableSessionGateway(application)

    private val voiceController = VoiceInputController(application) { transcript ->
        submitCommandText(transcript)
    }

    val wearableState: StateFlow<WearableGatewayState> = wearableGateway.state
    val voiceState: StateFlow<VoiceUiState> = voiceController.state

    val isNetworkOnline: StateFlow<Boolean> = repository.observeNetworkOnline()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _pipelineState = MutableStateFlow(CapturePipelineUiState())
    val pipelineState: StateFlow<CapturePipelineUiState> = _pipelineState.asStateFlow()

    val activeJob: StateFlow<InspectionJobEntity?> = repository.activeJobFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @Suppress("OPT_IN_USAGE")
    val captures: StateFlow<List<CaptureEvidenceUi>> = activeJob
        .map { it?.jobId }
        .distinctUntilChanged()
        .flatMapLatest { jobId ->
            if (jobId == null) flowOf(emptyList()) else repository.observeCaptures(jobId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val estimateLineItems: StateFlow<List<EstimateLineItem>> = captures
        .map { repository.buildEstimateLineItems(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val outboxItems: StateFlow<List<OutboxItemUi>> = repository.outboxFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val telemetryRecords: StateFlow<List<TelemetryRecordUi>> = repository.telemetryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val httpTraces: StateFlow<List<HttpTraceLog>> = repository.transportConfig.httpTraces
    val loopbackMode: StateFlow<Boolean> = repository.transportConfig.loopbackMode
    val baseUrl: StateFlow<String> = repository.transportConfig.baseUrl

    private val _exportedJobJson = MutableStateFlow<String?>(null)
    val exportedJobJson: StateFlow<String?> = _exportedJobJson.asStateFlow()

    private val _isSyncingNow = MutableStateFlow(false)
    val isSyncingNow: StateFlow<Boolean> = _isSyncingNow.asStateFlow()

    private val _importedEvalUris = MutableStateFlow<List<Uri>>(emptyList())
    val importedEvalUris: StateFlow<List<Uri>> = _importedEvalUris.asStateFlow()

    private val _evalSummary = MutableStateFlow(EvalBatchSummaryUi(totalFrames = 42))
    val evalSummary: StateFlow<EvalBatchSummaryUi> = _evalSummary.asStateFlow()

    private var evalJob: Job? = null

    init {
        viewModelScope.launch {
            repository.ensureDefaultJob()
        }
        viewModelScope.launch {
            combine(
                activeJob.map { it?.jobId }.filterNotNull().distinctUntilChanged(),
                wearableState.map { it.lifecycleState.name to it.disconnectsRecovered }.distinctUntilChanged()
            ) { jobId, (sessionState, disconnects) ->
                Triple(jobId, sessionState, disconnects)
            }.collect { (jobId, sessionState, disconnects) ->
                runCatching {
                    repository.updateJobSessionTelemetry(
                        jobId = jobId,
                        sessionState = sessionState,
                        disconnectsRecovered = disconnects
                    )
                }.onFailure { err ->
                    Log.e(VM_TAG, "Failed to update job session telemetry", err)
                }
            }
        }
        viewModelScope.launch {
            wearableState
                .map { it.previewFrame }
                .distinctUntilChanged()
                .collect { frame ->
                    if (frame != null) {
                        runCatching {
                            qualityAnalyzer.analyze(frame, CaptureCommandType.NAMEPLATE)
                        }.onSuccess { previewReport ->
                            _pipelineState.value = _pipelineState.value.copy(liveQualityPreview = previewReport)
                        }
                    } else {
                        _pipelineState.value = _pipelineState.value.copy(liveQualityPreview = null)
                    }
                }
        }
    }

    fun toggleDarkFieldTheme() {
        _pipelineState.value = _pipelineState.value.copy(
            isDarkFieldTheme = !_pipelineState.value.isDarkFieldTheme
        )
    }

    // --- Wearable & Mock Device Kit Controls ---
    fun onBluetoothPermissionResult(granted: Boolean) {
        wearableGateway.onBluetoothPermissionUpdated(granted)
    }

    fun pairMockGlasses() = wearableGateway.pairMockGlasses()
    fun unpairMockGlasses() = wearableGateway.unpairMockGlasses()
    fun setMockPower(on: Boolean) = wearableGateway.setMockPower(on)
    fun setMockFolded(folded: Boolean) = wearableGateway.setMockFolded(folded)
    fun setMockDonned(donned: Boolean) = wearableGateway.setMockDonned(donned)
    fun selectMockScene(scene: MockFeedScene) = wearableGateway.selectMockScene(scene)
    fun toggleDeviceCameraFeed(enabled: Boolean) = wearableGateway.toggleDeviceCameraFeed(enabled)
    fun startSession() = wearableGateway.startSession()
    fun pauseSession() = wearableGateway.pauseSession()
    fun resumeSession() = wearableGateway.resumeSession()
    fun stopSession() = wearableGateway.stopSession()
    fun simulateUnexpectedDisconnect() = wearableGateway.simulateUnexpectedDisconnect()

    // --- Voice & Command Pipeline ---
    fun startVoiceListening() = voiceController.startListening()
    fun stopVoiceListening() = voiceController.stopListening()

    fun submitCommandText(rawCommand: String) {
        when (val parsed = VoiceCommandParser.parse(rawCommand)) {
            is VoiceParseResult.Matched -> executeParsedCommand(parsed.command)
            is VoiceParseResult.MissingArguments -> {
                _pipelineState.value = _pipelineState.value.copy(
                    commandFeedback = parsed.hint,
                    feedbackIsError = true
                )
            }
            is VoiceParseResult.Unrecognized -> {
                _pipelineState.value = _pipelineState.value.copy(
                    commandFeedback = parsed.hint,
                    feedbackIsError = true
                )
            }
        }
    }

    private fun executeParsedCommand(command: ParsedVoiceCommand) {
        if (_pipelineState.value.isExtracting || _pipelineState.value.activeStepMessage != null) {
            return
        }
        viewModelScope.launch {
            try {
                val job = activeJob.value ?: repository.ensureDefaultJob()

                if (command is ParsedVoiceCommand.TechnicianNote) {
                    val isStreaming = wearableState.value.lifecycleState == SessionLifecycleState.STREAMING
                    val attachedFrame = if (isStreaming) wearableState.value.previewFrame else null
                    val noteSource = when {
                        attachedFrame == null -> EvidenceSource.VOICE_NOTE_ONLY
                        wearableState.value.hardware.usingDeviceCamera -> EvidenceSource.ANDROID_REAR_CAMERA_MOCK
                        else -> EvidenceSource.MOCK_DEVICE_KIT_SCENE
                    }
                    val duplicate = repository.findDuplicateCapture(
                        jobId = job.jobId,
                        commandType = command.commandType,
                        transcript = command.rawTranscript,
                        room = null,
                        surface = null,
                        imageSha256 = null
                    )
                    if (duplicate != null) {
                        _pipelineState.value = _pipelineState.value.copy(
                            commandFeedback = "Duplicate note skipped — already logged in ${duplicate.captureId}.",
                            feedbackIsError = true
                        )
                        return@launch
                    }
                    val noteQuality = QualityReport(
                        passed = true,
                        blurScore = 100.0,
                        brightnessScore = 128.0,
                        framingScore = 1.0,
                        darkPixelRatio = 0.0,
                        clippedPixelRatio = 0.0,
                        issues = emptyList(),
                        summaryMessage = if (attachedFrame != null) {
                            "Voice Note (with active stream frame)"
                        } else {
                            "Voice Note (audio-only)"
                        },
                        latencyMs = 1L
                    )
                    val sha256 = attachedFrame?.let { repository.computeBitmapSha256(it) }
                    persistAndExtract(
                        jobId = job.jobId,
                        command = command,
                        bitmap = attachedFrame,
                        evidenceSource = noteSource,
                        qualityReport = noteQuality,
                        imageSha256 = sha256
                    )
                    return@launch
                }

                // Require paired glasses AND active STREAMING state before capturing photo evidence
                val gw = wearableState.value
                if (!gw.hardware.paired) {
                    _pipelineState.value = _pipelineState.value.copy(
                        commandFeedback = "Mock glasses are not paired. Tap 'Pair Mock Glasses' and then 'Start' before capturing.",
                        feedbackIsError = true
                    )
                    return@launch
                }
                if (gw.lifecycleState != SessionLifecycleState.STREAMING) {
                    _pipelineState.value = _pipelineState.value.copy(
                        commandFeedback = "Live stream is ${gw.lifecycleState.name}. Tap 'Start' (or 'Resume') before capturing.",
                        feedbackIsError = true
                    )
                    return@launch
                }

                _pipelineState.value = _pipelineState.value.copy(
                    activeStepMessage = "Capturing frame via Meta DAT 1.0…",
                    commandFeedback = null,
                    feedbackIsError = false
                )

                val captureResult = wearableGateway.capturePhotoFrame()
                captureResult.onFailure { err ->
                    _pipelineState.value = _pipelineState.value.copy(
                        activeStepMessage = null,
                        commandFeedback = "Capture failed: ${err.localizedMessage ?: "No camera frame available"}",
                        feedbackIsError = true
                    )
                }.onSuccess { frame ->
                    _pipelineState.value = _pipelineState.value.copy(
                        activeStepMessage = "Running on-device quality gates (blur / darkness / framing)…"
                    )
                    val qReport = qualityAnalyzer.analyze(frame.bitmap, command.commandType)
                    if (!qReport.passed) {
                        _pipelineState.value = _pipelineState.value.copy(
                            activeStepMessage = null,
                            pendingQualityReview = PendingQualityReview(
                                command = command,
                                bitmap = frame.bitmap,
                                evidenceSource = frame.source,
                                qualityReport = qReport
                            ),
                            commandFeedback = "${qReport.summaryMessage} — Adjust lighting/framing or retake.",
                            feedbackIsError = true
                        )
                        return@onSuccess
                    }

                    val roomHint = (command as? ParsedVoiceCommand.CaptureDamage)?.room
                    val surfaceHint = (command as? ParsedVoiceCommand.CaptureDamage)?.surface
                    val sha256 = withContext(Dispatchers.Default) { repository.computeBitmapSha256(frame.bitmap) }
                    val duplicate = repository.findDuplicateCapture(
                        jobId = job.jobId,
                        commandType = command.commandType,
                        transcript = command.rawTranscript,
                        room = roomHint,
                        surface = surfaceHint,
                        imageSha256 = sha256
                    )
                    if (duplicate != null) {
                        _pipelineState.value = _pipelineState.value.copy(
                            activeStepMessage = null,
                            commandFeedback = "Duplicate capture blocked — ${duplicate.captureId} already captured for this frame/command. Switch scene or change room/surface.",
                            feedbackIsError = true
                        )
                        return@onSuccess
                    }

                    persistAndExtract(
                        jobId = job.jobId,
                        command = command,
                        bitmap = frame.bitmap,
                        evidenceSource = frame.source,
                        qualityReport = qReport,
                        imageSha256 = sha256
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(VM_TAG, "Unexpected capture pipeline error", e)
                _pipelineState.value = _pipelineState.value.copy(
                    isExtracting = false,
                    activeStepMessage = null,
                    commandFeedback = "Capture failed: ${e.localizedMessage ?: "Unexpected error"}",
                    feedbackIsError = true
                )
            }
        }
    }

    fun overrideQualityAndKeep() {
        if (_pipelineState.value.isExtracting || _pipelineState.value.activeStepMessage != null) return
        val pending = _pipelineState.value.pendingQualityReview ?: return
        _pipelineState.value = _pipelineState.value.copy(pendingQualityReview = null)
        viewModelScope.launch {
            val job = activeJob.value ?: repository.ensureDefaultJob()
            val sha256 = withContext(Dispatchers.Default) { repository.computeBitmapSha256(pending.bitmap) }
            val overriddenReport = pending.qualityReport.copy(userOverridden = true)
            persistAndExtract(
                jobId = job.jobId,
                command = pending.command,
                bitmap = pending.bitmap,
                evidenceSource = pending.evidenceSource,
                qualityReport = overriddenReport,
                imageSha256 = sha256
            )
        }
    }

    fun discardFailedCapture() {
        _pipelineState.value.pendingQualityReview ?: return
        _pipelineState.value = _pipelineState.value.copy(
            pendingQualityReview = null,
            commandFeedback = "Discarded low-quality frame — hold steady, improve lighting, and retake.",
            feedbackIsError = false
        )
    }

    fun retryExtraction(captureId: String) {
        if (_pipelineState.value.isExtracting || _pipelineState.value.activeStepMessage != null) return
        viewModelScope.launch {
            val entity = repository.getCaptureEntity(captureId)
            if (entity == null) {
                _pipelineState.value = _pipelineState.value.copy(
                    commandFeedback = "Cannot retry: capture $captureId no longer exists.",
                    feedbackIsError = true
                )
                return@launch
            }
            val cmdType = runCatching { CaptureCommandType.valueOf(entity.commandType) }
                .getOrDefault(CaptureCommandType.NOTE)

            val bitmap = withContext(Dispatchers.IO) {
                val path = entity.localImagePath ?: return@withContext null
                val f = File(path)
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            }
            if (entity.localImagePath != null && bitmap == null) {
                _pipelineState.value = _pipelineState.value.copy(
                    commandFeedback = "Cannot retry $captureId: local photo missing from storage.",
                    feedbackIsError = true
                )
                return@launch
            }

            _pipelineState.value = _pipelineState.value.copy(
                isExtracting = true,
                activeStepMessage = "Retrying Gemini 3.8 Flash extraction for $captureId…",
                commandFeedback = null,
                feedbackIsError = false
            )

            val aiRes = geminiService.extractEvidence(
                commandType = cmdType,
                bitmap = bitmap,
                transcript = entity.rawVoiceTranscript,
                roomHint = entity.room,
                surfaceHint = entity.surface
            )

            aiRes.onSuccess { outcome ->
                repository.updateCaptureWithExtraction(
                    captureId = captureId,
                    bundle = outcome.bundle,
                    errorMessage = null,
                    aiLatencyMs = outcome.aiLatencyMs,
                    promptTokens = outcome.promptTokens,
                    outputTokens = outcome.outputTokens,
                    estimatedCostUsd = outcome.estimatedCostUsd,
                    qualityLatencyMs = 0L,
                    sourceMode = "AI_RETRY"
                )
                _pipelineState.value = _pipelineState.value.copy(
                    isExtracting = false,
                    activeStepMessage = null,
                    commandFeedback = "Retry succeeded for $captureId • Conf ${(outcome.bundle.overallConfidence * 100).toInt()}%",
                    feedbackIsError = false
                )
            }.onFailure { err ->
                val errMsg = err.localizedMessage ?: "AI extraction retry failed"
                runCatching {
                    repository.updateCaptureWithExtraction(
                        captureId = captureId,
                        bundle = null,
                        errorMessage = errMsg,
                        aiLatencyMs = 0L,
                        promptTokens = 0,
                        outputTokens = 0,
                        estimatedCostUsd = 0.0,
                        qualityLatencyMs = 0L,
                        sourceMode = "AI_RETRY_FAIL"
                    )
                }
                _pipelineState.value = _pipelineState.value.copy(
                    isExtracting = false,
                    activeStepMessage = null,
                    commandFeedback = "Retry failed for $captureId: $errMsg",
                    feedbackIsError = true
                )
            }
        }
    }

    private suspend fun persistAndExtract(
        jobId: String,
        command: ParsedVoiceCommand,
        bitmap: Bitmap?,
        evidenceSource: EvidenceSource,
        qualityReport: QualityReport,
        imageSha256: String?
    ) {
        val captureId = "CAP-${UUID.randomUUID().toString().take(8).uppercase()}"
        val roomHint = (command as? ParsedVoiceCommand.CaptureDamage)?.room
        val surfaceHint = (command as? ParsedVoiceCommand.CaptureDamage)?.surface

        try {
            val localPath = bitmap?.let { repository.saveCaptureImage(captureId, it) }
            repository.saveInitialCapture(
                captureId = captureId,
                jobId = jobId,
                commandType = command.commandType,
                transcript = command.rawTranscript,
                room = roomHint,
                surface = surfaceHint,
                localImagePath = localPath,
                qualityReport = qualityReport,
                evidenceSource = evidenceSource,
                imageSha256 = imageSha256
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(VM_TAG, "Failed to save capture evidence $captureId to Room", e)
            _pipelineState.value = _pipelineState.value.copy(
                isExtracting = false,
                activeStepMessage = null,
                commandFeedback = "Database save failed: ${e.localizedMessage ?: "Unable to persist capture"}",
                feedbackIsError = true
            )
            return
        }

        _pipelineState.value = _pipelineState.value.copy(
            isExtracting = true,
            activeStepMessage = "Extracting fields & confidence via Gemini 3.8 Flash…",
            feedbackIsError = false
        )

        val aiRes = geminiService.extractEvidence(
            commandType = command.commandType,
            bitmap = bitmap,
            transcript = command.rawTranscript,
            roomHint = roomHint,
            surfaceHint = surfaceHint
        )

        aiRes.onSuccess { outcome ->
            try {
                repository.updateCaptureWithExtraction(
                    captureId = captureId,
                    bundle = outcome.bundle,
                    errorMessage = null,
                    aiLatencyMs = outcome.aiLatencyMs,
                    promptTokens = outcome.promptTokens,
                    outputTokens = outcome.outputTokens,
                    estimatedCostUsd = outcome.estimatedCostUsd,
                    qualityLatencyMs = qualityReport.latencyMs
                )
                _pipelineState.value = _pipelineState.value.copy(
                    isExtracting = false,
                    activeStepMessage = null,
                    commandFeedback = "Saved $captureId (${evidenceSource.displayLabel}) • Conf ${(outcome.bundle.overallConfidence * 100).toInt()}% • ${outcome.aiLatencyMs}ms",
                    feedbackIsError = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(VM_TAG, "Failed to update extraction results for $captureId", e)
                _pipelineState.value = _pipelineState.value.copy(
                    isExtracting = false,
                    activeStepMessage = null,
                    commandFeedback = "Database error saving extraction: ${e.localizedMessage ?: "Unknown DB error"}",
                    feedbackIsError = true
                )
            }
        }.onFailure { err ->
            val errMsg = err.localizedMessage ?: "AI extraction failed"
            runCatching {
                repository.updateCaptureWithExtraction(
                    captureId = captureId,
                    bundle = null,
                    errorMessage = errMsg,
                    aiLatencyMs = 0L,
                    promptTokens = 0,
                    outputTokens = 0,
                    estimatedCostUsd = 0.0,
                    qualityLatencyMs = qualityReport.latencyMs
                )
            }.onFailure { dbErr ->
                Log.e(VM_TAG, "Failed to store extraction error state for $captureId", dbErr)
            }
            _pipelineState.value = _pipelineState.value.copy(
                isExtracting = false,
                activeStepMessage = null,
                commandFeedback = "Saved $captureId locally (AI error: $errMsg)",
                feedbackIsError = true
            )
        }
    }

    // --- Job Review & Estimate Actions ---
    fun updateFieldValue(captureId: String, fieldKey: String, newValue: String) {
        viewModelScope.launch {
            repository.updateExtractedField(captureId, fieldKey, newValue)
        }
    }

    fun toggleEstimateCode(captureId: String, code: String) {
        viewModelScope.launch {
            repository.toggleEstimateCode(captureId, code)
        }
    }

    fun deleteCapture(captureId: String) {
        viewModelScope.launch {
            repository.deleteCapture(captureId)
        }
    }

    fun refreshJobJsonExport() {
        val jobId = activeJob.value?.jobId ?: return
        viewModelScope.launch {
            _exportedJobJson.value = repository.buildStructuredJobJsonString(jobId)
        }
    }

    fun dismissJobJsonExport() {
        _exportedJobJson.value = null
    }

    // --- Sync Queue & REST Transport ---
    fun setLoopbackMode(enabled: Boolean) {
        repository.transportConfig.setLoopbackMode(enabled)
    }

    fun updateRestBaseUrl(url: String) {
        repository.transportConfig.setBaseUrl(url)
    }

    fun simulateNextHttp503() {
        repository.transportConfig.setSimulateNextFailure(true)
    }

    fun syncNow() {
        viewModelScope.launch {
            _isSyncingNow.value = true
            repository.processPendingOutbox()
            _isSyncingNow.value = false
        }
    }

    fun clearSyncedOutbox() {
        viewModelScope.launch {
            repository.clearSyncedOutbox()
        }
    }

    // --- 40-Frame Batch Evaluator & Cost/Latency Telemetry ---
    fun addImportedEvalUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _importedEvalUris.value = (_importedEvalUris.value + uris).distinct()
    }

    fun setEvalUseLiveAi(enabled: Boolean) {
        _evalSummary.value = _evalSummary.value.copy(useLiveAiInBatch = enabled)
    }

    fun start40FrameEvaluation() {
        if (_evalSummary.value.isRunning) return
        val jobId = activeJob.value?.jobId ?: "JOB-2026-4082"
        val useLive = _evalSummary.value.useLiveAiInBatch
        evalJob?.cancel()
        evalJob = viewModelScope.launch {
            val frames = evalEngine.collectEvaluationFrames(_importedEvalUris.value)
            evalEngine.runBatchEvaluation(jobId, frames, useLive).collect { summary ->
                _evalSummary.value = summary
            }
        }
    }

    fun stopEvaluation() {
        evalJob?.cancel()
        _evalSummary.value = _evalSummary.value.copy(isRunning = false)
    }

    override fun onCleared() {
        super.onCleared()
        voiceController.release()
        wearableGateway.release()
    }
}
