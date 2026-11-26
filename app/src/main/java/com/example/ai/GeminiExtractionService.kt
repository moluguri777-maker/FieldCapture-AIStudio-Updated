package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.domain.model.CaptureCommandType
import com.example.domain.model.ConfidenceField
import com.example.domain.model.EstimateCodeCatalog
import com.example.domain.model.ExtractedDataBundle
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max

private const val TAG = "GeminiExtraction"
const val GEMINI_MODEL_ID = "gemini-3.8-flash"

// Indicative Gemini Flash pricing per 1M tokens for stretch-goal cost telemetry
const val INPUT_COST_PER_1M_USD = 0.15
const val OUTPUT_COST_PER_1M_USD = 0.60

data class AiExtractionOutcome(
    val bundle: ExtractedDataBundle,
    val aiLatencyMs: Long,
    val promptTokens: Int,
    val outputTokens: Int,
    val estimatedCostUsd: Double,
    val modelId: String = GEMINI_MODEL_ID
)

class GeminiExtractionService(
    private val appContext: Context? = null
) {

    private fun confidenceFieldSchema(fieldDescription: String): Schema = Schema.obj(
        properties = mapOf(
            "value" to Schema.string(description = fieldDescription),
            "confidence" to Schema.double(description = "Confidence score strictly between 0.0 and 1.0")
        )
    )

    private val estimateEnumValues = EstimateCodeCatalog.items.map { it.code }

    private val nameplateSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "equipmentType" to confidenceFieldSchema("Equipment category e.g. Condenser, Furnace, Air Handler, Electrical Panel"),
                "manufacturer" to confidenceFieldSchema("Manufacturer / brand name"),
                "modelNumber" to confidenceFieldSchema("Exact model number printed on nameplate"),
                "serialNumber" to confidenceFieldSchema("Exact serial number printed on nameplate"),
                "manufactureDate" to confidenceFieldSchema("Manufacture date or 4-digit year if visibly printed on nameplate, or Unknown"),
                "voltagePhase" to confidenceFieldSchema("Voltage and phase rating e.g. 208-230V 1PH"),
                "amperageOrBtu" to confidenceFieldSchema("Max fuse/breaker amperage or BTU capacity"),
                "refrigerantType" to confidenceFieldSchema("Refrigerant type e.g. R-410A or N/A"),
                "summary" to Schema.string("Concise 1-sentence technical summary of equipment state"),
                "estimateCodes" to Schema.array(
                    items = Schema.enumeration(estimateEnumValues, "Matching estimate code"),
                    description = "1 to 3 relevant codes from the property/HVAC/electrical catalog"
                )
            )
        )
    }

    private val damageSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "room" to confidenceFieldSchema("Room or zone name"),
                "surface" to confidenceFieldSchema("Impacted surface e.g. drywall ceiling, subfloor, roof shingle, main breaker"),
                "damageType" to confidenceFieldSchema("Primary damage type e.g. water stain, mold growth, thermal scorch, hail dent"),
                "severity" to confidenceFieldSchema("Severity classification: MINOR, MODERATE, or SEVERE"),
                "estimatedAreaSqFt" to confidenceFieldSchema("Estimated damaged area in sq ft or component count"),
                "moistureOrThermalRisk" to confidenceFieldSchema("Active moisture, mold, or electrical fire risk assessment"),
                "recommendedAction" to confidenceFieldSchema("Recommended remediation or repair action"),
                "summary" to Schema.string("Concise 1-sentence claim adjustment summary"),
                "estimateCodes" to Schema.array(
                    items = Schema.enumeration(estimateEnumValues, "Matching estimate code"),
                    description = "1 to 4 relevant estimate line-item codes"
                )
            )
        )
    }

    private val noteSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "summary" to confidenceFieldSchema("Normalized technical note summary"),
                "category" to confidenceFieldSchema("Trade category: Water Mitigation, Structural, HVAC, Electrical, Roofing, or Safety"),
                "safetyHazard" to confidenceFieldSchema("true or false plus brief hazard note if applicable"),
                "followUpRequired" to confidenceFieldSchema("Required next inspection or specialist action"),
                "referencedComponents" to confidenceFieldSchema("Comma-separated equipment or building components mentioned"),
                "estimateCodes" to Schema.array(
                    items = Schema.enumeration(estimateEnumValues, "Matching estimate code"),
                    description = "0 to 3 relevant estimate line-item codes"
                )
            )
        )
    }

    private val nameplateModel by lazy {
        Firebase.ai.generativeModel(
            modelName = GEMINI_MODEL_ID,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = nameplateSchema
                temperature = 0.1f
            }
        )
    }

    private val damageModel by lazy {
        Firebase.ai.generativeModel(
            modelName = GEMINI_MODEL_ID,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = damageSchema
                temperature = 0.15f
            }
        )
    }

    private val noteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = GEMINI_MODEL_ID,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = noteSchema
                temperature = 0.15f
            }
        )
    }

    suspend fun extractEvidence(
        commandType: CaptureCommandType,
        bitmap: Bitmap?,
        transcript: String,
        roomHint: String?,
        surfaceHint: String?
    ): Result<AiExtractionOutcome> = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        var scaledBitmap: Bitmap? = null
        try {
            // Pre-validate Firebase App Check token before calling Firebase AI Logic so dummy error
            // tokens produce an actionable diagnostic and never masquerade as 0% extractions.
            if (appContext != null) {
                val appCheckCheck = AppCheckCoordinator.awaitValidAppCheckToken(appContext, forceRefresh = false)
                if (appCheckCheck.isFailure) {
                    val err = appCheckCheck.exceptionOrNull()
                        ?: IllegalStateException("Firebase App Check token validation failed.")
                    return@withContext Result.failure(err)
                }
            }

            scaledBitmap = bitmap?.let { downscaleForInference(it, maxEdge = 1024) }
            val promptText = buildPrompt(commandType, transcript, roomHint, surfaceHint)
            val model = when (commandType) {
                CaptureCommandType.NAMEPLATE -> nameplateModel
                CaptureCommandType.DAMAGE -> damageModel
                CaptureCommandType.NOTE -> noteModel
            }

            val inputContent = content {
                if (scaledBitmap != null) {
                    image(scaledBitmap)
                }
                text(promptText)
            }

            val response = try {
                withTimeout(60_000L) { model.generateContent(inputContent) }
            } catch (e: Exception) {
                val msg = e.localizedMessage.orEmpty()
                val looksLikeAppCheckError = msg.contains("App Check", ignoreCase = true) ||
                    msg.contains("AppCheck", ignoreCase = true) ||
                    msg.contains("403", ignoreCase = true)
                if (looksLikeAppCheckError && appContext != null) {
                    Log.w(TAG, "Retrying Firebase AI Logic request after forced App Check token refresh.")
                    val refreshed = AppCheckCoordinator.awaitValidAppCheckToken(appContext, forceRefresh = true)
                    if (refreshed.isFailure) {
                        throw refreshed.exceptionOrNull() ?: e
                    }
                    withTimeout(60_000L) { model.generateContent(inputContent) }
                } else {
                    throw e
                }
            }

            val rawJson = response.text
                ?: return@withContext Result.failure(IllegalStateException("Gemini returned an empty response."))

            val bundle = parseStructuredBundle(rawJson, commandType)
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val promptTokens = response.usageMetadata?.promptTokenCount ?: estimateFallbackTokens(promptText, scaledBitmap != null)
            val outputTokens = response.usageMetadata?.candidatesTokenCount ?: (rawJson.length / 4).coerceAtLeast(32)
            val costUsd = (promptTokens / 1_000_000.0) * INPUT_COST_PER_1M_USD +
                (outputTokens / 1_000_000.0) * OUTPUT_COST_PER_1M_USD

            Result.success(
                AiExtractionOutcome(
                    bundle = bundle,
                    aiLatencyMs = elapsedMs,
                    promptTokens = promptTokens,
                    outputTokens = outputTokens,
                    estimatedCostUsd = costUsd
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini extraction timed out after 60s", e)
            Result.failure(IllegalStateException("AI extraction timed out after 60s. Evidence saved locally in queue."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini extraction failed", e)
            Result.failure(e)
        } finally {
            if (scaledBitmap != null && scaledBitmap !== bitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
        }
    }

    private fun buildPrompt(
        type: CaptureCommandType,
        transcript: String,
        roomHint: String?,
        surfaceHint: String?
    ): String {
        val codeCatalogText = EstimateCodeCatalog.items.joinToString("\n") {
            "- ${it.code}: ${it.title} (${it.trade})"
        }
        return when (type) {
            CaptureCommandType.NAMEPLATE -> """
                You are a field HVAC and electrical inspection extractor.
                Analyze the captured nameplate photo and voice command ("$transcript").
                Extract equipmentType, manufacturer, modelNumber, serialNumber, manufactureDate (if visibly printed), voltagePhase, amperageOrBtu, and refrigerantType accurately.
                Only report values supported by the image or voice command. If a field is unreadable or absent, set value to "Unknown" with low confidence (< 0.45).
                Provide calibrated confidence in [0.0, 1.0] for each field.
                Select matching estimate codes from:
                $codeCatalogText
            """.trimIndent()

            CaptureCommandType.DAMAGE -> """
                You are a property insurance & electrical/HVAC damage adjuster.
                Analyze the captured inspection photo and voice command ("$transcript").
                Hinted room: "${roomHint ?: "Unknown"}", hinted surface: "${surfaceHint ?: "Unknown"}".
                Extract damage details, severity, risk, and calibrated confidence in [0.0, 1.0] for every field.
                Select matching estimate codes from:
                $codeCatalogText
            """.trimIndent()

            CaptureCommandType.NOTE -> """
                You are a property insurance and mechanical inspection assistant.
                Structure this technician field note ("$transcript") using any attached visual context.
                Extract normalized summary, trade category, safety hazard status, follow-up action, and referenced components, each with confidence in [0.0, 1.0].
                Select relevant estimate codes from:
                $codeCatalogText
            """.trimIndent()
        }
    }

    internal fun parseStructuredBundle(rawJson: String, type: CaptureCommandType): ExtractedDataBundle {
        val json = Json.parseToJsonElement(rawJson).jsonObject
        val expectedKeys = when (type) {
            CaptureCommandType.NAMEPLATE -> listOf(
                "equipmentType", "manufacturer", "modelNumber", "serialNumber",
                "manufactureDate", "voltagePhase", "amperageOrBtu", "refrigerantType"
            )
            CaptureCommandType.DAMAGE -> listOf(
                "room", "surface", "damageType", "severity",
                "estimatedAreaSqFt", "moistureOrThermalRisk", "recommendedAction"
            )
            CaptureCommandType.NOTE -> listOf(
                "summary", "category", "safetyHazard", "followUpRequired", "referencedComponents"
            )
        }

        val fieldMap = linkedMapOf<String, ConfidenceField>()
        for (key in expectedKeys) {
            val obj = (json[key] as? JsonObject)
                ?: if (key == "manufactureDate") (json["manufactureYear"] as? JsonObject) else null
            if (obj != null) {
                val valStr = obj["value"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { "Unknown" } ?: "Unknown"
                val rawConf = obj["confidence"]?.jsonPrimitive?.doubleOrNull
                val conf = (rawConf ?: if (valStr.equals("Unknown", ignoreCase = true)) 0.25 else 0.5)
                    .toFloat()
                    .coerceIn(0f, 1f)
                fieldMap[key] = ConfidenceField(
                    value = valStr,
                    confidence = conf,
                    needsReview = conf < 0.80f || valStr.equals("Unknown", ignoreCase = true)
                )
            } else {
                fieldMap[key] = ConfidenceField(
                    value = "Unknown",
                    confidence = 0.2f,
                    needsReview = true
                )
            }
        }

        val summary = if (type == CaptureCommandType.NOTE) {
            fieldMap["summary"]?.value ?: "Technician field note"
        } else {
            json["summary"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "Inspection capture extracted"
        }

        val codesJson = runCatching { json["estimateCodes"]?.jsonArray }.getOrNull()
        val codes = mutableListOf<String>()
        if (codesJson != null) {
            for (elem in codesJson) {
                val c = elem.jsonPrimitive.contentOrNull ?: continue
                if (EstimateCodeCatalog.byCode(c) != null && !codes.contains(c)) {
                    codes += c
                }
            }
        }
        if (codes.isEmpty()) {
            val combinedText = summary + " " + fieldMap.values.joinToString(" ") { it.value }
            codes += EstimateCodeCatalog.matchFromText(combinedText, maxMatches = 2).map { it.code }
        }

        val meanConf = if (fieldMap.isNotEmpty()) {
            fieldMap.values.map { it.confidence }.average().toFloat()
        } else {
            0f
        }

        // Never treat 0% confidence as a valid extraction.
        if (meanConf <= 0f) {
            throw IllegalStateException("Extraction returned 0% mean confidence and cannot be accepted as valid evidence.")
        }

        return ExtractedDataBundle(
            fields = fieldMap,
            overallConfidence = meanConf,
            summary = summary,
            suggestedEstimateCodes = codes
        )
    }

    private fun downscaleForInference(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / largest.toFloat()
        val w = (bitmap.width * scale).toInt().coerceAtLeast(64)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(64)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun estimateFallbackTokens(prompt: String, hasImage: Boolean): Int {
        val textTokens = (prompt.length / 4).coerceAtLeast(64)
        return if (hasImage) textTokens + 258 else textTokens
    }
}
