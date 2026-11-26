package com.example

import com.example.ai.AppCheckCoordinator
import com.example.ai.GeminiExtractionService
import com.example.ai.resolveFirebaseAppOrReport
import com.example.ai.syncDebugTokenAndCheckRotation
import com.example.cv.ImageQualityAnalyzer
import com.example.data.local.CaptureEvidenceEntity
import com.example.data.local.InspectionDao
import com.example.data.local.InspectionJobEntity
import com.example.data.local.SyncOutboxEntity
import com.example.data.local.TelemetryMetricEntity
import com.example.data.repository.FieldCaptureRepository
import com.example.domain.model.CaptureCommandType
import com.example.domain.model.EstimateCodeCatalog
import com.example.domain.model.EvidenceSource
import com.example.domain.model.QualityIssue
import com.example.domain.model.QualityReport
import com.example.domain.model.SessionLifecycleState
import com.example.domain.model.SyncStatus
import com.example.eval.EvalGroundTruthEvaluator
import com.example.eval.LabeledGroundTruthSample
import com.example.eval.LabeledPredictionSample
import com.example.voice.ParsedVoiceCommand
import com.example.voice.VoiceCommandParser
import com.example.voice.VoiceCommandSpec
import com.example.voice.VoiceParseResult
import com.example.wearable.DatWearableSessionGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class ExampleUnitTest {

    @Test
    fun voiceParser_matchesRequiredCommands_handlesPunctuation_andFlagsMissingArgs() {
        val nameplate = VoiceCommandParser.parse("Capture nameplate.")
        assertTrue(nameplate is VoiceParseResult.Matched)
        assertEquals(CaptureCommandType.NAMEPLATE, (nameplate as VoiceParseResult.Matched).command.commandType)

        val damage = VoiceCommandParser.parse("capture damage in basement drywall ceiling!")
        assertTrue(damage is VoiceParseResult.Matched)
        val dmgCmd = (damage as VoiceParseResult.Matched).command as ParsedVoiceCommand.CaptureDamage
        assertEquals("Basement", dmgCmd.room)
        assertEquals("Drywall Ceiling", dmgCmd.surface)

        val note = VoiceCommandParser.parse("note: Main breaker lug shows scorch marks")
        assertTrue(note is VoiceParseResult.Matched)
        val noteCmd = (note as VoiceParseResult.Matched).command as ParsedVoiceCommand.TechnicianNote
        assertEquals("Main breaker lug shows scorch marks", noteCmd.noteText)

        // Missing argument validation
        val missingDamageAll = VoiceCommandParser.parse("capture damage")
        assertTrue(missingDamageAll is VoiceParseResult.MissingArguments)

        val missingDamageSurface = VoiceCommandParser.parse("capture damage kitchen")
        assertTrue(missingDamageSurface is VoiceParseResult.MissingArguments)

        val missingNoteBody = VoiceCommandParser.parse("note:")
        assertTrue(missingNoteBody is VoiceParseResult.MissingArguments)

        val invalid = VoiceCommandParser.parse("hello world")
        assertTrue(invalid is VoiceParseResult.Unrecognized)
    }

    @Test
    fun voiceParser_supportsRegisteringFourthCommandSpec() {
        val customThermalSpec = object : VoiceCommandSpec {
            override val commandName: String = "flag hazard"
            override val usageHint: String = "flag hazard [description]"
            override fun tryParse(normalizedTranscript: String, originalTranscript: String): VoiceParseResult? {
                if (!normalizedTranscript.startsWith("flag hazard", ignoreCase = true)) return null
                val body = normalizedTranscript.removePrefix("flag hazard").trim()
                return if (body.isEmpty()) {
                    VoiceParseResult.MissingArguments(commandName, originalTranscript, "Provide hazard text.")
                } else {
                    VoiceParseResult.Matched(ParsedVoiceCommand.TechnicianNote("[HAZARD] $body", originalTranscript))
                }
            }
        }
        val extendedSpecs = VoiceCommandParser.defaultSpecs + customThermalSpec
        val res = VoiceCommandParser.parse("flag hazard exposed 240V bus bar", extendedSpecs)
        assertTrue(res is VoiceParseResult.Matched)
        val note = (res as VoiceParseResult.Matched).command as ParsedVoiceCommand.TechnicianNote
        assertEquals("[HAZARD] exposed 240V bus bar", note.noteText)
    }

    @Test
    fun estimateCatalog_hasFourteenCodesAndMatchesDomainText() {
        assertEquals(14, EstimateCodeCatalog.items.size)
        assertEquals(14, EstimateCodeCatalog.items.map { it.code }.distinct().size)

        val hvacMatches = EstimateCodeCatalog.matchFromText("Carrier condenser R-410A refrigerant leak")
        assertTrue(hvacMatches.any { it.code.startsWith("HVC-") })

        val waterMatches = EstimateCodeCatalog.matchFromText("Basement drywall water stain and mold")
        assertTrue(waterMatches.any { it.code == "DRY-RPL-02" || it.code == "WTR-DRY-01" })

        val electricalMatches = EstimateCodeCatalog.matchFromText("Main 200A breaker panel scorch hazard")
        assertTrue(electricalMatches.any { it.code == "ELC-PNL-08" || it.code == "GEN-SAF-14" })
    }

    @Test
    fun qualityAnalyzer_flagsBlurDarkOverexposedAndOffCenter_andPassesSharpCentered() {
        val analyzer = ImageQualityAnalyzer()
        val w = 160
        val h = 120

        // 1. Dark flat image -> TOO_DARK & BLURRY
        val darkPixels = IntArray(w * h) { (12 shl 16) or (12 shl 8) or 12 }
        val darkReport = analyzer.analyzePixels(darkPixels, w, h, CaptureCommandType.NAMEPLATE)
        assertFalse(darkReport.passed)
        assertTrue(darkReport.issues.contains(QualityIssue.TOO_DARK))
        assertTrue(darkReport.issues.contains(QualityIssue.BLURRY))

        // 2. Washed out glare -> OVEREXPOSED
        val glarePixels = IntArray(w * h) { (250 shl 16) or (250 shl 8) or 250 }
        val glareReport = analyzer.analyzePixels(glarePixels, w, h, CaptureCommandType.DAMAGE)
        assertFalse(glareReport.passed)
        assertTrue(glareReport.issues.contains(QualityIssue.OVEREXPOSED))

        // 3. Off-center border-only contrast -> POOR_FRAMING
        val offCenterPixels = IntArray(w * h) { (128 shl 16) or (128 shl 8) or 128 }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inBorder = x < 18 || x > w - 18 || y < 14 || y > h - 14
                if (inBorder && (x % 3 == 0 || y % 3 == 0)) {
                    offCenterPixels[y * w + x] = (20 shl 16) or (20 shl 8) or 20
                }
            }
        }
        val framingReport = analyzer.analyzePixels(offCenterPixels, w, h, CaptureCommandType.NAMEPLATE)
        assertFalse(framingReport.passed)
        assertTrue("Expected POOR_FRAMING, got ${framingReport.issues}", framingReport.issues.contains(QualityIssue.POOR_FRAMING))

        // 4. Sharp high-contrast centered grid -> PASS
        val sharpPixels = IntArray(w * h) { (215 shl 16) or (215 shl 8) or 215 }
        for (y in 28 until 92) {
            for (x in 36 until 124) {
                if ((x % 8 == 0) || (y % 8 == 0)) {
                    sharpPixels[y * w + x] = (15 shl 16) or (15 shl 8) or 15
                }
            }
        }
        val sharpReport = analyzer.analyzePixels(sharpPixels, w, h, CaptureCommandType.NAMEPLATE)
        assertTrue("Expected sharp centered grid to pass, got: ${sharpReport.summaryMessage}", sharpReport.passed)
    }

    @Test
    fun qualityAnalyzer_bundledDrawablesAndSynthetic42FrameCvBenchmark() {
        val drawableNames = listOf(
            "img_sample_nameplate_1795506161252.jpg" to CaptureCommandType.NAMEPLATE,
            "img_sample_damage_drywall_1795506162478.jpg" to CaptureCommandType.DAMAGE,
            "img_sample_electrical_panel_1795506162369.jpg" to CaptureCommandType.DAMAGE
        )
        val analyzer = ImageQualityAnalyzer()
        val loadedImages = drawableNames.map { (name, cmd) ->
            val file = listOf(
                File("src/main/res/drawable/$name"),
                File("app/src/main/res/drawable/$name")
            ).firstOrNull { it.exists() }
            assertNotNull("Expected bundled drawable $name", file)
            Triple(name, cmd, ImageIO.read(file!!))
        }

        // Verify all 3 nominal drawables pass CV gates
        for ((name, cmd, img) in loadedImages) {
            val report = analyzeBufferedImage(analyzer, img, cmd)
            assertTrue("Expected $name to pass CV gate, got: ${report.summaryMessage}", report.passed)
        }

        // Run a 42-frame synthetic CV perturbation sweep matching BatchEvaluationEngine (>=10 nameplates, >=10 low-light)
        var nameplates = 0
        var lowLight = 0
        var passCount = 0
        var darkRejected = 0
        var blurRejected = 0
        var framingRejected = 0
        var overexposedRejected = 0

        for (i in 0 until 42) {
            val (_, cmd, src) = loadedImages[i % loadedImages.size]
            if (cmd == CaptureCommandType.NAMEPLATE) nameplates++
            val mode = i % 7
            val cycle = i / 7
            val isLowLight = mode == 2 || mode == 4
            if (isLowLight) lowLight++

            val perturbed = perturbBufferedImage(src, mode, cycle)
            val rep = analyzeBufferedImage(analyzer, perturbed, cmd)
            if (rep.passed) passCount++
            if (QualityIssue.TOO_DARK in rep.issues) darkRejected++
            if (QualityIssue.BLURRY in rep.issues) blurRejected++
            if (QualityIssue.POOR_FRAMING in rep.issues) framingRejected++
            if (QualityIssue.OVEREXPOSED in rep.issues) overexposedRejected++
        }

        assertTrue("Expected >=10 nameplates, got $nameplates", nameplates >= 10)
        assertTrue("Expected >=10 low-light frames, got $lowLight", lowLight >= 10)
        assertTrue("Expected low-light perturbations to trigger dark rejections ($darkRejected)", darkRejected >= 10)
        assertTrue("Expected blur perturbations to trigger blur rejections ($blurRejected)", blurRejected >= 4)
        assertTrue("Expected framing perturbations to trigger framing rejections ($framingRejected)", framingRejected >= 4)
        println("JVM_CV_BENCHMARK total=42 nameplates=$nameplates lowLight=$lowLight pass=$passCount darkRejected=$darkRejected blurRejected=$blurRejected framingRejected=$framingRejected overexposedRejected=$overexposedRejected")
    }

    @Test
    fun geminiExtractionService_parsesAllCommandSchemas_andRejectsZeroConfidence() {
        val service = GeminiExtractionService()

        val nameplateJson = """
            {
              "equipmentType": {"value": "HVAC Condenser", "confidence": 0.96},
              "manufacturer": {"value": "Carrier", "confidence": 0.98},
              "modelNumber": {"value": "24ACC636A003", "confidence": 0.97},
              "serialNumber": {"value": "3121E48291", "confidence": 0.95},
              "manufactureDate": {"value": "Unknown", "confidence": 0.35},
              "voltagePhase": {"value": "208-230V 1PH 60HZ", "confidence": 0.94},
              "amperageOrBtu": {"value": "Unknown", "confidence": 0.40},
              "refrigerantType": {"value": "R-410A", "confidence": 0.96},
              "summary": "Carrier 24ACC636A003 residential R-410A condenser unit.",
              "estimateCodes": ["HVC-CMP-10", "HVC-COI-11"]
            }
        """.trimIndent()
        val nameplateBundle = service.parseStructuredBundle(nameplateJson, CaptureCommandType.NAMEPLATE)
        assertEquals("Carrier", nameplateBundle.fields["manufacturer"]?.value)
        assertEquals("24ACC636A003", nameplateBundle.fields["modelNumber"]?.value)
        assertEquals("3121E48291", nameplateBundle.fields["serialNumber"]?.value)
        assertTrue(nameplateBundle.fields["manufactureDate"]?.needsReview == true)
        assertEquals(listOf("HVC-CMP-10", "HVC-COI-11"), nameplateBundle.suggestedEstimateCodes)

        val damageJson = """
            {
              "room": {"value": "Basement", "confidence": 0.95},
              "surface": {"value": "Drywall", "confidence": 0.92},
              "damageType": {"value": "Water stain & bubbling paint", "confidence": 0.89},
              "severity": {"value": "MODERATE", "confidence": 0.88},
              "estimatedAreaSqFt": {"value": "32 sq ft", "confidence": 0.81},
              "moistureOrThermalRisk": {"value": "Elevated moisture risk", "confidence": 0.84},
              "recommendedAction": {"value": "Cut out wet drywall and dry framing", "confidence": 0.87},
              "summary": "Moisture-stained basement drywall requires removal and drying.",
              "estimateCodes": ["WTR-DRY-01", "DRY-RPL-02"]
            }
        """.trimIndent()
        val damageBundle = service.parseStructuredBundle(damageJson, CaptureCommandType.DAMAGE)
        assertEquals("Basement", damageBundle.fields["room"]?.value)
        assertEquals("32 sq ft", damageBundle.fields["estimatedAreaSqFt"]?.value)
        assertTrue(damageBundle.overallConfidence > 0.85f)

        val zeroConfJson = """
            {
              "summary": {"value": "Unknown", "confidence": 0.0},
              "category": {"value": "Unknown", "confidence": 0.0},
              "safetyHazard": {"value": "Unknown", "confidence": 0.0},
              "followUpRequired": {"value": "Unknown", "confidence": 0.0},
              "referencedComponents": {"value": "Unknown", "confidence": 0.0},
              "estimateCodes": []
            }
        """.trimIndent()
        var threwZeroConf = false
        try {
            service.parseStructuredBundle(zeroConfJson, CaptureCommandType.NOTE)
        } catch (e: IllegalStateException) {
            threwZeroConf = true
        }
        assertTrue("Expected 0% confidence bundle to throw instead of succeeding", threwZeroConf)
    }

    @Test
    fun repository_duplicateDetection_aiRetry_and_offline503RecoveryWithoutDuplicateOutbox() = runTest {
        val dao = InMemoryInspectionDao()
        val repository = FieldCaptureRepository(
            appContext = null,
            dao = dao,
            scheduleWorkerOnEnqueue = false
        )

        val job = repository.ensureDefaultJob()
        val quality = QualityReport(
            passed = true,
            blurScore = 6931.0,
            brightnessScore = 127.0,
            framingScore = 0.85,
            darkPixelRatio = 0.01,
            clippedPixelRatio = 0.01,
            issues = emptyList(),
            summaryMessage = "PASS • Sharpness 6931 • Luma 127 • Framing 85%",
            latencyMs = 14L
        )

        // 1. Initial capture saved locally even if AI fails first (offline/AppCheck failure)
        val captureId = "CAP-NAMEPLATE01"
        val sampleSha = "abc123sha256"
        repository.saveInitialCapture(
            captureId = captureId,
            jobId = job.jobId,
            commandType = CaptureCommandType.NAMEPLATE,
            transcript = "capture nameplate",
            room = null,
            surface = null,
            localImagePath = "/tmp/captures/$captureId.jpg",
            qualityReport = quality,
            evidenceSource = EvidenceSource.MOCK_DEVICE_KIT_SCENE,
            imageSha256 = sampleSha
        )

        // Simulate failed AI extraction saved against existing record
        repository.updateCaptureWithExtraction(
            captureId = captureId,
            bundle = null,
            errorMessage = "Firebase App Check token is invalid.",
            aiLatencyMs = 0L,
            promptTokens = 0,
            outputTokens = 0,
            estimatedCostUsd = 0.0,
            qualityLatencyMs = quality.latencyMs
        )

        // Verify local capture + photo path retained when AI fails
        val afterFailure = repository.observeCaptures(job.jobId).first().first()
        assertEquals(captureId, afterFailure.captureId)
        assertNotNull(afterFailure.extractionError)
        assertEquals(EvidenceSource.MOCK_DEVICE_KIT_SCENE, afterFailure.evidenceSource)

        // Verify duplicate check permits replacing failed extraction OR catches duplicates after success
        assertNull(
            repository.findDuplicateCapture(
                jobId = job.jobId,
                commandType = CaptureCommandType.NAMEPLATE,
                transcript = "capture nameplate",
                room = null,
                surface = null,
                imageSha256 = sampleSha
            )
        )

        // 2. Retry extraction without recapturing -> updates existing captureId
        val rawJson = """
            {
              "equipmentType": {"value": "HVAC Condenser", "confidence": 0.95},
              "manufacturer": {"value": "Carrier", "confidence": 0.98},
              "modelNumber": {"value": "24ACC636A003", "confidence": 0.97},
              "serialNumber": {"value": "3121E48291", "confidence": 0.96},
              "manufactureDate": {"value": "2021", "confidence": 0.85},
              "voltagePhase": {"value": "208-230V 1PH 60HZ", "confidence": 0.93},
              "amperageOrBtu": {"value": "35A", "confidence": 0.81},
              "refrigerantType": {"value": "R-410A", "confidence": 0.97},
              "summary": "Carrier 24ACC636A003 condenser nameplate.",
              "estimateCodes": ["HVC-CMP-10", "HVC-COI-11"]
            }
        """.trimIndent()
        val bundle = GeminiExtractionService().parseStructuredBundle(rawJson, CaptureCommandType.NAMEPLATE)
        repository.updateCaptureWithExtraction(
            captureId = captureId,
            bundle = bundle,
            errorMessage = null,
            aiLatencyMs = 740L,
            promptTokens = 410,
            outputTokens = 155,
            estimatedCostUsd = 0.00015,
            qualityLatencyMs = 0L,
            sourceMode = "AI_RETRY"
        )

        val capturesAfterRetry = repository.observeCaptures(job.jobId).first()
        assertEquals(1, capturesAfterRetry.size)
        assertNull(capturesAfterRetry.first().extractionError)
        assertEquals("Carrier", capturesAfterRetry.first().extraction?.fields?.get("manufacturer")?.value)

        // Duplicate capture now blocked cleanly
        val dup = repository.findDuplicateCapture(
            jobId = job.jobId,
            commandType = CaptureCommandType.NAMEPLATE,
            transcript = "capture nameplate",
            room = null,
            surface = null,
            imageSha256 = sampleSha
        )
        assertNotNull("Expected duplicate capture to be detected after valid capture", dup)
        assertEquals(captureId, dup?.captureId)

        // 3. Offline / HTTP 503 failure & recovery test (idempotency key preserved, no duplicate outbox entries)
        val initialOutbox = repository.outboxFlow.first()
        val jobSyncKeyBefore = initialOutbox.first { it.operationType == "SYNC_JOB" }.idempotencyKey
        repository.transportConfig.setSimulateNextFailure(true)
        val failedBatch = repository.processPendingOutbox()
        assertTrue("Expected failure when HTTP 503 is simulated", failedBatch.failedCount >= 1)

        val failedOutbox = repository.outboxFlow.first().first { it.operationType == "SYNC_JOB" }
        assertEquals(SyncStatus.FAILED, failedOutbox.status)
        assertEquals(jobSyncKeyBefore, failedOutbox.idempotencyKey)

        // Enqueueing update again before retry preserves idempotency key & attempt count
        repository.updateExtractedField(captureId, "manufactureDate", "08/2021")
        val reQueuedOutbox = repository.outboxFlow.first().first { it.operationType == "SYNC_JOB" }
        assertEquals(SyncStatus.QUEUED, reQueuedOutbox.status)
        assertEquals(jobSyncKeyBefore, reQueuedOutbox.idempotencyKey)

        // Connectivity returns -> sync succeeds cleanly
        val recoveryBatch = repository.processPendingOutbox()
        assertEquals(0, recoveryBatch.failedCount)
        assertTrue(recoveryBatch.syncedCount >= 1)

        val postSyncCaptures = repository.observeCaptures(job.jobId).first()
        assertEquals(1, postSyncCaptures.size)
        assertEquals(SyncStatus.SYNCED, postSyncCaptures.first().syncStatus)

        val exportedJson = repository.buildStructuredJobJsonString(job.jobId)
        assertTrue(exportedJson.contains("24ACC636A003"))
        assertTrue(exportedJson.contains("MOCK_DEVICE_KIT_SCENE"))
        assertTrue(exportedJson.contains("08/2021"))
    }

    @Test
    fun evalGroundTruthEvaluator_computesPerFieldAccuracyCorrectly() {
        val gt = listOf(
            LabeledGroundTruthSample(
                sampleId = "S1",
                imageFileName = "np1.jpg",
                commandType = CaptureCommandType.NAMEPLATE,
                expectedFields = mapOf(
                    "manufacturer" to "Carrier",
                    "modelNumber" to "24ACC636A003",
                    "serialNumber" to "3121E48291"
                )
            ),
            LabeledGroundTruthSample(
                sampleId = "S2",
                imageFileName = "np2.jpg",
                commandType = CaptureCommandType.NAMEPLATE,
                isLowLight = true,
                expectedFields = mapOf(
                    "manufacturer" to "Trane",
                    "modelNumber" to "4TTR6036J1000A",
                    "serialNumber" to "21453K8N2F"
                )
            )
        )
        val preds = mapOf(
            "S1" to LabeledPredictionSample(
                sampleId = "S1",
                extractedFields = mapOf(
                    "manufacturer" to "Carrier",
                    "modelNumber" to "24ACC-636A003", // formatting variance normalizes cleanly
                    "serialNumber" to "3121E48291"
                ),
                fieldConfidences = mapOf("manufacturer" to 0.98f, "modelNumber" to 0.95f, "serialNumber" to 0.94f)
            ),
            "S2" to LabeledPredictionSample(
                sampleId = "S2",
                extractedFields = mapOf(
                    "manufacturer" to "Trane",
                    "modelNumber" to "4TTR6036J1000A",
                    "serialNumber" to "Unknown"
                ),
                fieldConfidences = mapOf("manufacturer" to 0.90f, "modelNumber" to 0.85f, "serialNumber" to 0.35f)
            )
        )

        val report = EvalGroundTruthEvaluator.evaluate(gt, preds)
        assertEquals(2, report.evaluatedSamples)
        assertEquals(5f / 6f, report.overallFieldAccuracy, 0.001f)
        val mfgMetric = report.fieldMetrics.first { it.fieldName == "manufacturer" }
        assertEquals(1.0f, mfgMetric.accuracy, 0.001f)
        val serialMetric = report.fieldMetrics.first { it.fieldName == "serialNumber" }
        assertEquals(0.5f, serialMetric.accuracy, 0.001f)
    }

    @Test
    fun wearableGateway_gatesSdkInitBehindBluetoothPermission_andRecoversFromStartupFailures() = runTest {
        var permissionGranted = false
        var bootstrapCalls = 0
        var shouldThrowSecurity = false
        var shouldThrowGeneric = false

        val gateway = DatWearableSessionGateway(
            appContext = null,
            permissionChecker = { permissionGranted },
            sdkBootstrap = {
                bootstrapCalls++
                if (shouldThrowSecurity) {
                    throw SecurityException("Need android.permission.BLUETOOTH_CONNECT permission for BluetoothAdapter.getBondedDevices()")
                }
                if (shouldThrowGeneric) {
                    throw IllegalStateException("DAT service unavailable")
                }
            },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined
        )

        // 1. Permission denied -> SDK MUST NOT initialize, no crash, banner flag set
        assertFalse(gateway.initializeSdkInternal())
        gateway.pairMockGlasses()
        gateway.startSession()
        assertEquals(0, bootstrapCalls)
        assertTrue(gateway.state.value.requiresBluetoothPermission)
        assertFalse(gateway.state.value.sdkInitialized)
        assertEquals(SessionLifecycleState.DISCONNECTED, gateway.state.value.lifecycleState)

        // 2. Permission granted, but SDK throws SecurityException -> graceful ERROR state, no crash
        permissionGranted = true
        shouldThrowSecurity = true
        assertFalse(gateway.initializeSdkInternal())
        assertEquals(1, bootstrapCalls)
        assertTrue(gateway.state.value.requiresBluetoothPermission)
        assertFalse(gateway.state.value.sdkInitialized)
        assertEquals(SessionLifecycleState.ERROR, gateway.state.value.lifecycleState)
        assertTrue(gateway.state.value.lastError.orEmpty().contains("BLUETOOTH_CONNECT"))

        // 3. Generic startup failure -> graceful ERROR state without marking SDK initialized
        shouldThrowSecurity = false
        shouldThrowGeneric = true
        assertFalse(gateway.initializeSdkInternal())
        assertEquals(2, bootstrapCalls)
        assertFalse(gateway.state.value.requiresBluetoothPermission)
        assertFalse(gateway.state.value.sdkInitialized)
        assertEquals(SessionLifecycleState.ERROR, gateway.state.value.lifecycleState)

        // 4. Subsequent retry succeeds cleanly once condition clears
        shouldThrowGeneric = false
        assertTrue(gateway.initializeSdkInternal())
        assertEquals(3, bootstrapCalls)
        assertFalse(gateway.state.value.requiresBluetoothPermission)
        assertTrue(gateway.state.value.sdkInitialized)
        assertEquals(SessionLifecycleState.DISCONNECTED, gateway.state.value.lifecycleState)
        assertNull(gateway.state.value.lastError)

        // 5. Idempotent after initialization
        assertTrue(gateway.initializeSdkInternal())
        assertEquals(3, bootstrapCalls)
    }

    private fun analyzeBufferedImage(
        analyzer: ImageQualityAnalyzer,
        img: BufferedImage,
        commandType: CaptureCommandType
    ): QualityReport {
        val w = 320
        val h = (img.height * (w.toFloat() / img.width)).toInt().coerceAtLeast(64)
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.drawImage(img, 0, 0, w, h, null)
        g.dispose()
        val pixels = IntArray(w * h)
        scaled.getRGB(0, 0, w, h, pixels, 0, w)
        return analyzer.analyzePixels(pixels, w, h, commandType)
    }

    private fun perturbBufferedImage(src: BufferedImage, mode: Int, cycle: Int): BufferedImage {
        val w = src.width
        val h = src.height
        return when (mode) {
            0 -> src
            1 -> src.getSubimage((w * 0.14).toInt(), (h * 0.14).toInt(), (w * 0.72).toInt(), (h * 0.72).toInt())
            2 -> scaleRgbBufferedImage(src, 0.28f)
            3 -> {
                val tiny = BufferedImage((w / 12).coerceAtLeast(20), (h / 12).coerceAtLeast(16), BufferedImage.TYPE_INT_ARGB)
                val g1 = tiny.createGraphics()
                g1.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g1.drawImage(src, 0, 0, tiny.width, tiny.height, null)
                g1.dispose()
                val blurred = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val g2 = blurred.createGraphics()
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.drawImage(tiny, 0, 0, w, h, null)
                g2.dispose()
                blurred
            }
            4 -> scaleRgbBufferedImage(src, 0.16f)
            5 -> {
                val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val g = out.createGraphics()
                g.color = java.awt.Color(42, 46, 52)
                g.fillRect(0, 0, w, h)
                g.drawImage(src, (w * 0.78f).toInt(), (h * 0.72f).toInt(), null)
                g.dispose()
                out
            }
            else -> if (cycle % 2 == 0) {
                scaleRgbBufferedImage(src, 1.55f)
            } else {
                src.getSubimage(0, 0, (w * 0.85).toInt(), (h * 0.85).toInt())
            }
        }
    }

    private fun scaleRgbBufferedImage(src: BufferedImage, factor: Float): BufferedImage {
        val w = src.width
        val h = src.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val pixels = IntArray(w * h)
        src.getRGB(0, 0, w, h, pixels, 0, w)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            val r = (((c ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
            val g = (((c ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * factor).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setRGB(0, 0, w, h, pixels, 0, w)
        return out
    }

    @Test
    fun appCheck_debugTokenRotation_clearsCacheAndForcesNextTokenRefresh() = runTest {
        AppCheckCoordinator.resetForTesting()
        try {
            var storedSecret: String? = null
            var cacheClearedCount = 0

            // 1. Initial secret persisted synchronously -> marks pendingForceRefresh
            val receivedInitial = syncDebugTokenAndCheckRotation(
                debugToken = "test-secret-v1",
                readCurrentSecret = { storedSecret },
                writeSecretSync = { storedSecret = it; true },
                clearTokenCacheSync = { cacheClearedCount++; true }
            )
            assertTrue(receivedInitial)
            assertEquals("test-secret-v1", storedSecret)
            assertEquals(1, cacheClearedCount)
            assertTrue(AppCheckCoordinator.pendingForceRefresh)

            // 2. First token request must forceRefresh=true even if caller passed false
            val observedForceFlags = mutableListOf<Boolean>()
            val res1 = AppCheckCoordinator.awaitValidAppCheckTokenWithFetcher(false) { force ->
                observedForceFlags += force
                Result.success("jwt-v1")
            }
            assertEquals("jwt-v1", res1.getOrNull())
            assertEquals(listOf(true), observedForceFlags)
            assertFalse(AppCheckCoordinator.pendingForceRefresh)

            // 3. Re-supplying identical secret must not clear cache or force refresh
            syncDebugTokenAndCheckRotation(
                debugToken = "test-secret-v1",
                readCurrentSecret = { storedSecret },
                writeSecretSync = { storedSecret = it; true },
                clearTokenCacheSync = { cacheClearedCount++; true }
            )
            assertEquals(1, cacheClearedCount)
            assertFalse(AppCheckCoordinator.pendingForceRefresh)

            val res2 = AppCheckCoordinator.awaitValidAppCheckTokenWithFetcher(false) { force ->
                observedForceFlags += force
                Result.success("jwt-v1-cached")
            }
            assertEquals("jwt-v1-cached", res2.getOrNull())
            assertEquals(listOf(true, false), observedForceFlags)

            // 4. Rotating to a new secret via onNewIntent clears cache and forces refresh
            syncDebugTokenAndCheckRotation(
                debugToken = "test-secret-v2",
                readCurrentSecret = { storedSecret },
                writeSecretSync = { storedSecret = it; true },
                clearTokenCacheSync = { cacheClearedCount++; true }
            )
            assertEquals("test-secret-v2", storedSecret)
            assertEquals(2, cacheClearedCount)
            assertTrue(AppCheckCoordinator.pendingForceRefresh)

            val res3 = AppCheckCoordinator.awaitValidAppCheckTokenWithFetcher(false) { force ->
                observedForceFlags += force
                Result.success("jwt-v2")
            }
            assertEquals("jwt-v2", res3.getOrNull())
            assertEquals(listOf(true, false, true), observedForceFlags)
            assertFalse(AppCheckCoordinator.pendingForceRefresh)

            // 5. Mid-flight rotation during non-forced fetch discards stale token and re-fetches
            val res4 = AppCheckCoordinator.awaitValidAppCheckTokenWithFetcher(false) { force ->
                observedForceFlags += force
                if (!force) {
                    AppCheckCoordinator.markDebugTokenRotated()
                    Result.success("stale-jwt-v2")
                } else {
                    Result.success("fresh-jwt-v3")
                }
            }
            assertEquals("fresh-jwt-v3", res4.getOrNull())
            assertEquals(listOf(true, false, true, false, true), observedForceFlags)
            assertFalse(AppCheckCoordinator.pendingForceRefresh)
        } finally {
            AppCheckCoordinator.resetForTesting()
        }
    }

    @Test
    fun appCheck_consecutiveMidFlightRotations_discardsBothStaleTokensAndEnforcesRetryBound() = runTest {
        AppCheckCoordinator.resetForTesting()
        try {
            // 1. Two consecutive rotations during requests -> discards both stale results and returns latest epoch token
            val forceHistory = mutableListOf<Boolean>()
            var callIndex = 0
            val result = AppCheckCoordinator.awaitValidAppCheckTokenWithFetcher(false) { force ->
                forceHistory += force
                callIndex++
                when (callIndex) {
                    1 -> {
                        // Rotation #1 occurs during initial fetch
                        AppCheckCoordinator.markDebugTokenRotated()
                        Result.success("stale-token-epoch-0")
                    }
                    2 -> {
                        // Rotation #2 occurs during first forced retry
                        AppCheckCoordinator.markDebugTokenRotated()
                        Result.success("stale-token-epoch-1")
                    }
                    else -> {
                        // No rotation during 3rd fetch -> belongs to latest epoch (2)
                        Result.success("valid-token-epoch-2")
                    }
                }
            }
            assertTrue(result.isSuccess)
            assertEquals("valid-token-epoch-2", result.getOrNull())
            assertEquals(listOf(false, true, true), forceHistory)
            assertFalse(AppCheckCoordinator.pendingForceRefresh)

            // 2. Continuous rotations beyond MAX_ROTATION_RETRIES -> fails cleanly with diagnostic error
            var boundedCalls = 0
            val exhaustedResult = AppCheckCoordinator.awaitValidAppCheckTokenWithFetcher(false) {
                boundedCalls++
                AppCheckCoordinator.markDebugTokenRotated()
                Result.success("always-stale-$boundedCalls")
            }
            assertTrue(exhaustedResult.isFailure)
            assertEquals(4, boundedCalls) // 1 initial + 3 bounded retries
            val errorMsg = exhaustedResult.exceptionOrNull()?.message.orEmpty()
            assertTrue("Expected retry limit error, got: $errorMsg", errorMsg.contains("rotated repeatedly"))
            assertEquals(errorMsg, AppCheckCoordinator.diagnosticState.lastError)
            assertTrue(AppCheckCoordinator.pendingForceRefresh)
        } finally {
            AppCheckCoordinator.resetForTesting()
        }
    }

    @Test
    fun appCheck_providerInstallGuard_installsOncePerModeAndPersistenceKey() {
        AppCheckCoordinator.resetForTesting()
        try {
            var installCount = 0
            assertTrue(AppCheckCoordinator.installProviderIfNeeded("pk-default", "DEBUG_PROVIDER") { installCount++ })
            // Repeated calls (Activity recreation / onNewIntent) must not reinstall
            assertFalse(AppCheckCoordinator.installProviderIfNeeded("pk-default", "DEBUG_PROVIDER") { installCount++ })
            assertFalse(AppCheckCoordinator.installProviderIfNeeded("pk-default", "DEBUG_PROVIDER") { installCount++ })
            assertEquals(1, installCount)

            // Different mode or persistenceKey installs once for that signature
            assertTrue(AppCheckCoordinator.installProviderIfNeeded("pk-default", "RECAPTCHA_V3") { installCount++ })
            assertFalse(AppCheckCoordinator.installProviderIfNeeded("pk-default", "RECAPTCHA_V3") { installCount++ })
            assertEquals(2, installCount)
        } finally {
            AppCheckCoordinator.resetForTesting()
        }
    }

    @Test
    fun appCheck_nullFirebaseApp_recordsDiagnosticErrorWithoutCrashing() {
        AppCheckCoordinator.resetForTesting()
        try {
            val resolved = resolveFirebaseAppOrReport<Any> { null }
            assertNull(resolved)
            assertEquals("UNINITIALIZED", AppCheckCoordinator.diagnosticState.providerMode)
            assertFalse(AppCheckCoordinator.diagnosticState.receivedIntentDebugToken)
            assertNotNull(AppCheckCoordinator.diagnosticState.lastError)
            assertTrue(AppCheckCoordinator.diagnosticState.lastError!!.contains("FirebaseApp.initializeApp returned null"))
        } finally {
            AppCheckCoordinator.resetForTesting()
        }
    }

    @Test
    fun appCheck_backupExclusionRules_excludeAppCheckStoresOnly() {
        val xmlDir = listOf(
            File("src/main/res/xml"),
            File("app/src/main/res/xml")
        ).first { it.exists() }
        val backupXml = File(xmlDir, "backup_rules.xml").readText()
        val extractionXml = File(xmlDir, "data_extraction_rules.xml").readText()

        val expectedDebugPrefix = "com.google.firebase.appcheck.debug.store.W0RFRkFVTFRd+MTo3MTAzODI4MjUzNDQ6YW5kcm9pZDoyMTdkODBkODIwNmM3OTdmNDE1YjY1.xml"
        val expectedTokenPrefix = "com.google.firebase.appcheck.store.W0RFRkFVTFRd+MTo3MTAzODI4MjUzNDQ6YW5kcm9pZDoyMTdkODBkODIwNmM3OTdmNDE1YjY1.xml"

        assertTrue(backupXml.contains("""<exclude domain="sharedpref" path="$expectedDebugPrefix""""))
        assertTrue(backupXml.contains("""<exclude domain="sharedpref" path="$expectedTokenPrefix""""))
        assertFalse(backupXml.contains("<include"))

        assertTrue(extractionXml.contains("<cloud-backup>"))
        assertTrue(extractionXml.contains("<device-transfer>"))
        assertTrue(extractionXml.contains("""<exclude domain="sharedpref" path="$expectedDebugPrefix""""))
        assertTrue(extractionXml.contains("""<exclude domain="sharedpref" path="$expectedTokenPrefix""""))
        assertFalse(extractionXml.contains("<include"))
    }
}

private class InMemoryInspectionDao : InspectionDao {
    private val jobs = MutableStateFlow<Map<String, InspectionJobEntity>>(emptyMap())
    private val captures = MutableStateFlow<Map<String, CaptureEvidenceEntity>>(emptyMap())
    private val outbox = MutableStateFlow<Map<String, SyncOutboxEntity>>(emptyMap())
    private val telemetry = MutableStateFlow<List<TelemetryMetricEntity>>(emptyList())

    override fun observeActiveJob(): Flow<InspectionJobEntity?> =
        jobs.map { map -> map.values.maxByOrNull { it.createdAtEpochMs } }

    override suspend fun getJobById(jobId: String): InspectionJobEntity? = jobs.value[jobId]

    override suspend fun insertJobIfAbsent(job: InspectionJobEntity) {
        if (!jobs.value.containsKey(job.jobId)) {
            jobs.value = jobs.value + (job.jobId to job)
        }
    }

    override suspend fun updateJobSessionTelemetry(
        jobId: String,
        sessionState: String,
        disconnectsRecovered: Int,
        updatedAtEpochMs: Long
    ) {
        val current = jobs.value[jobId] ?: return
        jobs.value = jobs.value + (
            jobId to current.copy(
                sessionState = sessionState,
                disconnectsRecovered = disconnectsRecovered,
                updatedAtEpochMs = updatedAtEpochMs
            )
        )
    }

    override suspend fun updateJobSyncState(jobId: String, syncStatus: String, updatedAtEpochMs: Long) {
        val current = jobs.value[jobId] ?: return
        jobs.value = jobs.value + (
            jobId to current.copy(
                syncStatus = syncStatus,
                updatedAtEpochMs = updatedAtEpochMs
            )
        )
    }

    override suspend fun updateJobSynced(
        jobId: String,
        serverRevision: Int,
        syncStatus: String,
        updatedAtEpochMs: Long
    ) {
        val current = jobs.value[jobId] ?: return
        jobs.value = jobs.value + (
            jobId to current.copy(
                serverRevision = serverRevision,
                syncStatus = syncStatus,
                updatedAtEpochMs = updatedAtEpochMs
            )
        )
    }

    override fun observeCapturesForJob(jobId: String): Flow<List<CaptureEvidenceEntity>> =
        captures.map { map ->
            map.values.filter { it.jobId == jobId }.sortedByDescending { it.createdAtEpochMs }
        }

    override suspend fun getCapturesForJob(jobId: String): List<CaptureEvidenceEntity> =
        captures.value.values.filter { it.jobId == jobId }.sortedBy { it.createdAtEpochMs }

    override suspend fun getCaptureById(captureId: String): CaptureEvidenceEntity? =
        captures.value[captureId]

    override suspend fun upsertCapture(capture: CaptureEvidenceEntity) {
        require(jobs.value.containsKey(capture.jobId)) {
            "FOREIGN KEY constraint failed: jobId ${capture.jobId} does not exist"
        }
        captures.value = captures.value + (capture.captureId to capture)
    }

    override suspend fun deleteCapture(captureId: String) {
        captures.value = captures.value - captureId
    }

    override fun observeOutbox(): Flow<List<SyncOutboxEntity>> =
        outbox.map { it.values.sortedByDescending { item -> item.createdAtEpochMs } }

    override suspend fun getPendingOutboxItems(): List<SyncOutboxEntity> =
        outbox.value.values
            .filter { it.status == "QUEUED" || it.status == "FAILED" }
            .sortedBy { it.createdAtEpochMs }

    override suspend fun getOutboxItemById(outboxId: String): SyncOutboxEntity? =
        outbox.value[outboxId]

    override suspend fun upsertOutboxItem(item: SyncOutboxEntity) {
        outbox.value = outbox.value + (item.outboxId to item)
    }

    override suspend fun clearSyncedOutbox() {
        outbox.value = outbox.value.filterValues { it.status != "SYNCED" }
    }

    override fun observeTelemetry(): Flow<List<TelemetryMetricEntity>> =
        telemetry.map { it.sortedByDescending { m -> m.timestampEpochMs } }

    override suspend fun getAllTelemetry(): List<TelemetryMetricEntity> = telemetry.value

    override suspend fun insertTelemetry(metric: TelemetryMetricEntity) {
        telemetry.value = telemetry.value + metric
    }
}
