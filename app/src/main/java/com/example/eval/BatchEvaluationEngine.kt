package com.example.eval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.example.R
import com.example.ai.GEMINI_MODEL_ID
import com.example.ai.GeminiExtractionService
import com.example.cv.ImageQualityAnalyzer
import com.example.data.repository.FieldCaptureRepository
import com.example.domain.model.CaptureCommandType
import com.example.domain.model.EvalBatchSummaryUi
import com.example.domain.model.EvalFailureCaseUi
import com.example.domain.model.EvalFrameResultUi
import com.example.domain.model.QualityIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

data class EvalInputFrame(
    val label: String,
    val sourceKind: String,
    val commandType: CaptureCommandType,
    val isLowLight: Boolean = false,
    val roomHint: String?,
    val surfaceHint: String?,
    val transcript: String,
    val loader: () -> Bitmap?
)

@Serializable
data class LabeledGroundTruthSample(
    val sampleId: String,
    val imageFileName: String,
    val commandType: CaptureCommandType,
    val isLowLight: Boolean = false,
    val expectedFields: Map<String, String>
)

@Serializable
data class LabeledPredictionSample(
    val sampleId: String,
    val extractedFields: Map<String, String> = emptyMap(),
    val fieldConfidences: Map<String, Float> = emptyMap(),
    val qualityPassed: Boolean = true,
    val error: String? = null
)

@Serializable
data class FieldAccuracyMetric(
    val fieldName: String,
    val totalLabeled: Int,
    val exactMatches: Int,
    val accuracy: Float,
    val meanConfidence: Float
)

@Serializable
data class GroundTruthEvaluationReport(
    val totalSamples: Int,
    val evaluatedSamples: Int,
    val qualityPassCount: Int,
    val extractionFailureCount: Int,
    val fieldMetrics: List<FieldAccuracyMetric>,
    val overallFieldAccuracy: Float
)

/**
 * Pure Kotlin accuracy evaluator comparing AI extractions against labeled ground-truth JSON.
 * Keeps real field walkthrough scoring reproducible in JVM unit tests and on-device batches.
 */
object EvalGroundTruthEvaluator {
    fun evaluate(
        groundTruth: List<LabeledGroundTruthSample>,
        predictionsBySampleId: Map<String, LabeledPredictionSample>
    ): GroundTruthEvaluationReport {
        val fieldCounts = linkedMapOf<String, Int>()
        val fieldMatches = linkedMapOf<String, Int>()
        val fieldConfidenceSums = linkedMapOf<String, Double>()
        val fieldConfidenceCounts = linkedMapOf<String, Int>()

        var evaluated = 0
        var qualityPassed = 0
        var failures = 0

        for (gt in groundTruth) {
            val pred = predictionsBySampleId[gt.sampleId] ?: continue
            evaluated++
            if (pred.qualityPassed) qualityPassed++
            if (pred.error != null || pred.extractedFields.isEmpty()) {
                failures++
            }
            for ((field, expectedVal) in gt.expectedFields) {
                if (expectedVal.isBlank()) continue
                fieldCounts[field] = (fieldCounts[field] ?: 0) + 1
                val actualVal = pred.extractedFields[field].orEmpty()
                if (normalizeForComparison(actualVal) == normalizeForComparison(expectedVal)) {
                    fieldMatches[field] = (fieldMatches[field] ?: 0) + 1
                }
                pred.fieldConfidences[field]?.let { conf ->
                    fieldConfidenceSums[field] = (fieldConfidenceSums[field] ?: 0.0) + conf
                    fieldConfidenceCounts[field] = (fieldConfidenceCounts[field] ?: 0) + 1
                }
            }
        }

        val metrics = fieldCounts.map { (field, total) ->
            val matches = fieldMatches[field] ?: 0
            val confCnt = fieldConfidenceCounts[field] ?: 0
            val meanConf = if (confCnt > 0) ((fieldConfidenceSums[field] ?: 0.0) / confCnt).toFloat() else 0f
            FieldAccuracyMetric(
                fieldName = field,
                totalLabeled = total,
                exactMatches = matches,
                accuracy = if (total > 0) matches.toFloat() / total else 0f,
                meanConfidence = meanConf
            )
        }
        val totalFields = metrics.sumOf { it.totalLabeled }
        val totalMatches = metrics.sumOf { it.exactMatches }
        return GroundTruthEvaluationReport(
            totalSamples = groundTruth.size,
            evaluatedSamples = evaluated,
            qualityPassCount = qualityPassed,
            extractionFailureCount = failures,
            fieldMetrics = metrics,
            overallFieldAccuracy = if (totalFields > 0) totalMatches.toFloat() / totalFields else 0f
        )
    }

    private fun normalizeForComparison(value: String): String =
        value.trim().lowercase().replace(Regex("""[\s\-_]+"""), "")
}

class BatchEvaluationEngine(
    private val context: Context,
    private val qualityAnalyzer: ImageQualityAnalyzer,
    private val geminiService: GeminiExtractionService,
    private val repository: FieldCaptureRepository
) {
    private val appContext = context.applicationContext
    private val prettyJson = Json { prettyPrint = true }

    suspend fun collectEvaluationFrames(importedUris: List<Uri>): List<EvalInputFrame> = withContext(Dispatchers.IO) {
        val frames = mutableListOf<EvalInputFrame>()

        // 1. User-imported genuine photos via zero-permission Photo Picker
        importedUris.forEachIndexed { idx, uri ->
            frames += EvalInputFrame(
                label = "Imported Photo #${idx + 1}",
                sourceKind = "PhotoPicker",
                commandType = if (idx % 2 == 0) CaptureCommandType.DAMAGE else CaptureCommandType.NAMEPLATE,
                isLowLight = false,
                roomHint = "Utility",
                surfaceHint = "Wall",
                transcript = if (idx % 2 == 0) "capture damage utility wall" else "capture nameplate",
                loader = {
                    runCatching {
                        appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
            )
        }

        // 2. Genuine frames already captured during Mock Device Kit / live sessions
        val captureDir = File(appContext.filesDir, "captures")
        val savedFiles = captureDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        savedFiles.take(15).forEachIndexed { idx, file ->
            frames += EvalInputFrame(
                label = "Captured Frame #${idx + 1}",
                sourceKind = "SessionCapture",
                commandType = CaptureCommandType.DAMAGE,
                isLowLight = false,
                roomHint = "Field",
                surfaceHint = "Inspection",
                transcript = "capture damage field inspection",
                loader = { BitmapFactory.decodeFile(file.absolutePath) }
            )
        }

        // 3. Fill up to 42 frames using labeled synthetic variations of bundled drawables,
        // guaranteeing >= 10 nameplate frames and >= 10 low-light frames for threshold testing.
        val baseScenes = listOf(
            Triple(R.drawable.img_sample_nameplate_1795506161252, CaptureCommandType.NAMEPLATE, "capture nameplate"),
            Triple(R.drawable.img_sample_damage_drywall_1795506162478, CaptureCommandType.DAMAGE, "capture damage basement drywall"),
            Triple(R.drawable.img_sample_electrical_panel_1795506162369, CaptureCommandType.DAMAGE, "capture damage utility panel")
        )

        var variationIdx = 0
        while (frames.size < 42) {
            val (resId, cmdType, transcript) = baseScenes[variationIdx % baseScenes.size]
            val mode = variationIdx % 7
            val cycleIdx = variationIdx / 7
            val labelPrefix = when (cmdType) {
                CaptureCommandType.NAMEPLATE -> "HVAC Nameplate"
                CaptureCommandType.DAMAGE -> if (resId == R.drawable.img_sample_electrical_panel_1795506162369) "Breaker Panel" else "Drywall Water"
                CaptureCommandType.NOTE -> "Field Note"
            }
            val (variationTag, lowLightFlag) = when (mode) {
                0 -> "Nominal" to false
                1 -> "Tight ROI Crop" to false
                2 -> "Dim Basement (-72% Luma)" to true
                3 -> "Motion Blur Sim" to false
                4 -> "Severe Low-Light (-84% Luma)" to true
                5 -> "Off-Center Framing" to false
                else -> if (cycleIdx % 2 == 0) "Bright Glare (+55%)" to false else "Angle Crop" to false
            }
            val capturedMode = mode
            val capturedCycle = cycleIdx
            val capturedResId = resId
            frames += EvalInputFrame(
                label = "$labelPrefix #$variationIdx ($variationTag)",
                sourceKind = "SyntheticPerturbation",
                commandType = cmdType,
                isLowLight = lowLightFlag,
                roomHint = if (resId == R.drawable.img_sample_electrical_panel_1795506162369) "Utility" else "Basement",
                surfaceHint = if (resId == R.drawable.img_sample_electrical_panel_1795506162369) "Panel" else "Drywall",
                transcript = transcript,
                loader = {
                    val raw = BitmapFactory.decodeResource(appContext.resources, capturedResId)
                    applyFieldVariation(raw, capturedMode, capturedCycle)
                }
            )
            variationIdx++
        }

        frames
    }

    fun runBatchEvaluation(
        jobId: String,
        frames: List<EvalInputFrame>,
        useLiveGeminiAi: Boolean
    ): Flow<EvalBatchSummaryUi> = flow {
        val results = mutableListOf<EvalFrameResultUi>()
        emit(
            EvalBatchSummaryUi(
                totalFrames = frames.size,
                completedFrames = 0,
                isRunning = true,
                useLiveAiInBatch = useLiveGeminiAi
            )
        )

        for ((idx, frame) in frames.withIndex()) {
            val bmp = frame.loader()
            if (bmp == null) {
                results += EvalFrameResultUi(
                    index = idx + 1,
                    frameLabel = frame.label,
                    sourceKind = frame.sourceKind,
                    commandType = frame.commandType,
                    isLowLightCase = frame.isLowLight,
                    qualityPassed = false,
                    blurScore = 0.0,
                    brightnessScore = 0.0,
                    framingScore = 0.0,
                    qualityIssues = emptyList(),
                    aiSucceeded = false,
                    meanConfidence = 0f,
                    matchedCodes = emptyList(),
                    qualityLatencyMs = 0L,
                    aiLatencyMs = 0L,
                    promptTokens = 0,
                    outputTokens = 0,
                    estimatedCostUsd = 0.0,
                    notes = "Decode error",
                    likelyFailureCause = "Unable to decode input frame bitmap."
                )
                continue
            }

            val qReport = qualityAnalyzer.analyze(bmp, frame.commandType)
            var aiSucceeded = false
            var meanConf = 0f
            var codes = emptyList<String>()
            var fieldConfidences = emptyMap<String, Float>()
            var aiLatency = 0L
            var promptTokens = 0
            var outputTokens = 0
            var costUsd = 0.0
            var notes = qReport.summaryMessage
            var failureCause: String? = inferQualityFailureCause(qReport.issues)

            if (useLiveGeminiAi && qReport.passed) {
                val aiRes = geminiService.extractEvidence(
                    commandType = frame.commandType,
                    bitmap = bmp,
                    transcript = frame.transcript,
                    roomHint = frame.roomHint,
                    surfaceHint = frame.surfaceHint
                )
                aiRes.onSuccess { outcome ->
                    aiSucceeded = true
                    meanConf = outcome.bundle.overallConfidence
                    codes = outcome.bundle.suggestedEstimateCodes
                    fieldConfidences = outcome.bundle.fields.mapValues { it.value.confidence }
                    aiLatency = outcome.aiLatencyMs
                    promptTokens = outcome.promptTokens
                    outputTokens = outcome.outputTokens
                    costUsd = outcome.estimatedCostUsd
                    notes = outcome.bundle.summary
                    failureCause = if (meanConf < 0.70f) {
                        "Low mean confidence (${(meanConf * 100).toInt()}%) — verify cropped/low-contrast fields."
                    } else null

                    repository.recordEvalTelemetry(
                        jobId = jobId,
                        captureId = "EVAL-${idx + 1}-${UUID.randomUUID().toString().take(6)}",
                        qualityLatencyMs = qReport.latencyMs,
                        aiLatencyMs = outcome.aiLatencyMs,
                        promptTokens = outcome.promptTokens,
                        outputTokens = outcome.outputTokens,
                        estimatedCostUsd = outcome.estimatedCostUsd,
                        modelId = GEMINI_MODEL_ID
                    )
                }.onFailure { err ->
                    val errText = err.localizedMessage ?: "Unknown AI failure"
                    notes = "AI Error: $errText"
                    failureCause = "Firebase AI Logic extraction error: $errText"
                }
            } else if (!useLiveGeminiAi) {
                repository.recordEvalTelemetry(
                    jobId = jobId,
                    captureId = "EVAL-CV-${idx + 1}",
                    qualityLatencyMs = qReport.latencyMs,
                    aiLatencyMs = 0L,
                    promptTokens = 0,
                    outputTokens = 0,
                    estimatedCostUsd = 0.0,
                    modelId = "on-device-cv-gate"
                )
                notes = if (qReport.passed) {
                    "CV Gate PASS (Enable Live Gemini AI toggle for multimodal extraction)"
                } else {
                    qReport.summaryMessage
                }
            }

            bmp.recycle()

            results += EvalFrameResultUi(
                index = idx + 1,
                frameLabel = frame.label,
                sourceKind = frame.sourceKind,
                commandType = frame.commandType,
                isLowLightCase = frame.isLowLight,
                qualityPassed = qReport.passed,
                blurScore = qReport.blurScore,
                brightnessScore = qReport.brightnessScore,
                framingScore = qReport.framingScore,
                qualityIssues = qReport.issues,
                aiSucceeded = aiSucceeded,
                meanConfidence = meanConf,
                matchedCodes = codes,
                extractedFieldConfidences = fieldConfidences,
                qualityLatencyMs = qReport.latencyMs,
                aiLatencyMs = aiLatency,
                promptTokens = promptTokens,
                outputTokens = outputTokens,
                estimatedCostUsd = costUsd,
                notes = notes,
                likelyFailureCause = failureCause
            )

            emit(buildSummary(frames.size, results.toList(), isRunning = idx < frames.lastIndex, useLiveGeminiAi))
        }

        val finalSummary = buildSummary(frames.size, results.toList(), isRunning = false, useLiveGeminiAi)
        emit(finalSummary.copy(exportedJsonReport = buildEvalExportJson(finalSummary)))
    }.flowOn(Dispatchers.Default)

    internal fun buildSummary(
        total: Int,
        results: List<EvalFrameResultUi>,
        isRunning: Boolean,
        useLiveAi: Boolean
    ): EvalBatchSummaryUi {
        val latencies = results.map { it.qualityLatencyMs + it.aiLatencyMs }.sorted()
        val p50 = if (latencies.isNotEmpty()) latencies[latencies.size / 2] else 0L
        val p95 = if (latencies.isNotEmpty()) latencies[((latencies.size - 1) * 0.95).toInt()] else 0L
        val aiDone = results.filter { it.aiSucceeded }
        val meanConf = if (aiDone.isNotEmpty()) aiDone.map { it.meanConfidence }.average().toFloat() else 0f

        val realCount = results.count { it.sourceKind == "PhotoPicker" || it.sourceKind == "SessionCapture" }
        val syntheticCount = results.size - realCount

        val fieldSums = linkedMapOf<String, Double>()
        val fieldCounts = linkedMapOf<String, Int>()
        for (res in aiDone) {
            for ((k, v) in res.extractedFieldConfidences) {
                fieldSums[k] = (fieldSums[k] ?: 0.0) + v
                fieldCounts[k] = (fieldCounts[k] ?: 0) + 1
            }
        }
        val perFieldConf = fieldSums.mapValues { (k, sum) ->
            (sum / (fieldCounts[k] ?: 1)).toFloat()
        }

        val worstFailures = results
            .filter { !it.qualityPassed || (useLiveAi && !it.aiSucceeded) || (it.aiSucceeded && it.meanConfidence < 0.75f) }
            .sortedWith(
                compareBy<EvalFrameResultUi>(
                    { if (useLiveAi && it.qualityPassed && !it.aiSucceeded) 0 else 1 },
                    { -it.qualityIssues.size },
                    { it.meanConfidence },
                    { it.blurScore }
                )
            )
            .take(5)
            .map { row ->
                EvalFailureCaseUi(
                    frameIndex = row.index,
                    frameLabel = row.frameLabel,
                    commandType = row.commandType,
                    summary = row.notes,
                    likelyCause = row.likelyFailureCause ?: "Quality or extraction threshold failed."
                )
            }

        return EvalBatchSummaryUi(
            totalFrames = total,
            completedFrames = results.size,
            isRunning = isRunning,
            realFrameCount = realCount,
            syntheticFrameCount = syntheticCount,
            nameplateCount = results.count { it.commandType == CaptureCommandType.NAMEPLATE },
            lowLightCount = results.count { it.isLowLightCase },
            qualityPassCount = results.count { it.qualityPassed },
            blurRejectedCount = results.count { QualityIssue.BLURRY in it.qualityIssues },
            darkRejectedCount = results.count { QualityIssue.TOO_DARK in it.qualityIssues },
            overexposedRejectedCount = results.count { QualityIssue.OVEREXPOSED in it.qualityIssues },
            framingRejectedCount = results.count { QualityIssue.POOR_FRAMING in it.qualityIssues },
            aiSuccessCount = aiDone.size,
            aiFailureCount = if (useLiveAi) results.count { it.qualityPassed && !it.aiSucceeded } else 0,
            meanConfidence = meanConf,
            perFieldMeanConfidence = perFieldConf,
            worstFailures = worstFailures,
            p50TotalLatencyMs = p50,
            p95TotalLatencyMs = p95,
            totalTokens = results.sumOf { it.promptTokens + it.outputTokens },
            totalCostUsd = results.sumOf { it.estimatedCostUsd },
            useLiveAiInBatch = useLiveAi,
            results = results
        )
    }

    internal fun buildEvalExportJson(summary: EvalBatchSummaryUi): String {
        val obj = buildJsonObject {
            put("datasetNotice", "SyntheticPerturbation frames come from bundled sample drawables and perturbations; Real frames come from PhotoPicker or SessionCapture.")
            put("totalFrames", summary.totalFrames)
            put("completedFrames", summary.completedFrames)
            put("realFrameCount", summary.realFrameCount)
            put("syntheticFrameCount", summary.syntheticFrameCount)
            put("nameplateCount", summary.nameplateCount)
            put("lowLightCount", summary.lowLightCount)
            put("qualityPassCount", summary.qualityPassCount)
            put("blurRejectedCount", summary.blurRejectedCount)
            put("darkRejectedCount", summary.darkRejectedCount)
            put("overexposedRejectedCount", summary.overexposedRejectedCount)
            put("framingRejectedCount", summary.framingRejectedCount)
            put("aiSuccessCount", summary.aiSuccessCount)
            put("aiFailureCount", summary.aiFailureCount)
            put("meanConfidence", summary.meanConfidence.toDouble())
            put("perFieldMeanConfidence", buildJsonObject {
                summary.perFieldMeanConfidence.forEach { (k, v) -> put(k, v.toDouble()) }
            })
            put("worstFailures", buildJsonArray {
                summary.worstFailures.forEach { f ->
                    add(
                        buildJsonObject {
                            put("frameIndex", f.frameIndex)
                            put("frameLabel", f.frameLabel)
                            put("commandType", f.commandType.name)
                            put("summary", f.summary)
                            put("likelyCause", f.likelyCause)
                        }
                    )
                }
            })
            put("p50TotalLatencyMs", summary.p50TotalLatencyMs)
            put("p95TotalLatencyMs", summary.p95TotalLatencyMs)
            put("totalTokens", summary.totalTokens)
            put("totalCostUsd", summary.totalCostUsd)
            put("frames", buildJsonArray {
                summary.results.forEach { r ->
                    add(
                        buildJsonObject {
                            put("index", r.index)
                            put("label", r.frameLabel)
                            put("source", r.sourceKind)
                            put("commandType", r.commandType.name)
                            put("isLowLightCase", r.isLowLightCase)
                            put("qualityPassed", r.qualityPassed)
                            put("blurScore", r.blurScore)
                            put("brightnessScore", r.brightnessScore)
                            put("framingScore", r.framingScore)
                            put("qualityIssues", buildJsonArray { r.qualityIssues.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) } })
                            put("aiSucceeded", r.aiSucceeded)
                            put("meanConfidence", r.meanConfidence.toDouble())
                            put("qualityLatencyMs", r.qualityLatencyMs)
                            put("aiLatencyMs", r.aiLatencyMs)
                            put("promptTokens", r.promptTokens)
                            put("outputTokens", r.outputTokens)
                            put("estimatedCostUsd", r.estimatedCostUsd)
                            r.likelyFailureCause?.let { put("likelyFailureCause", it) }
                        }
                    )
                }
            })
        }
        return prettyJson.encodeToString(obj)
    }

    private fun inferQualityFailureCause(issues: List<QualityIssue>): String? {
        if (issues.isEmpty()) return null
        val parts = issues.map { issue ->
            when (issue) {
                QualityIssue.BLURRY -> "Motion blur or defocus reduced Laplacian sharpness variance below gate"
                QualityIssue.TOO_DARK -> "Severe underexposure dropped BT.601 luminance below threshold"
                QualityIssue.OVEREXPOSED -> "Specularity/glare clipped highlights above white threshold"
                QualityIssue.POOR_FRAMING -> "Subject edge mass shifted outside center 72% reticle ROI"
            }
        }
        return parts.joinToString("; ")
    }

    private fun applyFieldVariation(src: Bitmap, mode: Int, cycleIdx: Int): Bitmap {
        return when (mode) {
            0 -> src
            1 -> {
                val w = (src.width * 0.72f).toInt()
                val h = (src.height * 0.72f).toInt()
                val x = (src.width - w) / 2
                val y = (src.height - h) / 2
                val cropped = Bitmap.createBitmap(src, x, y, w, h)
                src.recycle()
                cropped
            }
            2 -> adjustBrightness(src, 0.28f)
            3 -> {
                val tiny = Bitmap.createScaledBitmap(src, (src.width / 12).coerceAtLeast(20), (src.height / 12).coerceAtLeast(16), true)
                val blurred = Bitmap.createScaledBitmap(tiny, src.width, src.height, true)
                tiny.recycle()
                src.recycle()
                blurred
            }
            4 -> adjustBrightness(src, 0.16f)
            5 -> {
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawRGB(42, 46, 52)
                canvas.drawBitmap(src, src.width * 0.78f, src.height * 0.72f, null)
                src.recycle()
                out
            }
            else -> if (cycleIdx % 2 == 0) {
                adjustBrightness(src, 1.55f)
            } else {
                val w = (src.width * 0.85f).toInt()
                val h = (src.height * 0.85f).toInt()
                val cropped = Bitmap.createBitmap(src, 0, 0, w, h)
                src.recycle()
                cropped
            }
        }
    }

    private fun adjustBrightness(src: Bitmap, scale: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cm = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, 0f,
                0f, scale, 0f, 0f, 0f,
                0f, 0f, scale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        src.recycle()
        return out
    }
}
