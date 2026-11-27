# FieldCapture — Demo Script and Recording Notes

**Recorded Runtime:** Approximately 4 minutes 11 seconds  
**Environment:** Android Emulator with Mock Device Kit  
**Recording Type:** Screen recording without voiceover  
**Purpose:** Reviewer guide explaining the functionality and observed results shown in the submitted demonstration.

> **Important disclosure:** This demonstration uses the Mock Device Kit and sample inspection imagery. It does not demonstrate a connection to physical Ray-Ban Meta glasses. The written notes below describe the recorded application behavior and distinguish implemented workflows from functionality that remains unverified.

---

##Video Link : https://drive.google.com/file/d/1vU329f9Enk2kRIsadytVrNfM6uS27aMM/view?usp=sharing

## 0:00 – 0:40 | Application Introduction and Capture Interface

**Show:** Launch FieldCapture and open the Capture screen.

**What the recording demonstrates:**
- The FieldCapture application interface.
- Mock Device Kit pairing and simulated wearable session controls.
- The available capture workflow using sample inspection imagery.
- HVAC nameplate sample imagery and image-quality indicators.

**Technical context:**

The application is built using Kotlin and Jetpack Compose. Its wearable integration architecture uses Meta Wearables Device Access Toolkit (DAT 1.0.1), with a Mock Device Kit workflow for emulator-based development and testing.

The application also includes on-device image-quality analysis and local evidence-persistence infrastructure.

**Limitation:** Physical Ray-Ban Meta glasses pairing, streaming, and camera capture have not been verified.

---

## 0:40 – 1:30 | Capture Workflow and Image Quality Indicators

**Show:** Continue through the Capture screen and its available controls.

**What the recording demonstrates:**
- The simulated device and capture interface.
- HVAC nameplate sample imagery.
- Available image-quality indicators.
- The application's capture workflow.

**Technical context:**

The `ImageQualityAnalyzer` evaluates image characteristics such as:
- Blur using Laplacian variance.
- Brightness using BT.601 luminance.
- Framing using Sobel-based analysis.

These checks are intended to identify potentially unusable inspection evidence before further processing.

**Limitation:** The recording demonstrates the emulator-based workflow, not a verified physical glasses camera session or a complete real-world voice-command workflow.

---

## 1:30 – 2:20 | Job & Estimate and AI Extraction Status

**Show:** Open the Job & Estimate screen.

**What the recording demonstrates:**
- The captured inspection record.
- One capture associated with the job.
- The extraction and estimate interface.
- The observed Firebase App Check error.

**Observed result:**

The application displayed the following Firebase error:

`Firebase App Check token is invalid (Too many attempts)`

The AI extraction request did not complete successfully. Consequently:
- Successful live Gemini inference was not demonstrated.
- Structured estimate line items were not generated.
- The displayed estimate total remained `$0.00`.

The captured evidence remained available in the application.

**Technical context:**

FieldCapture integrates Firebase AI Logic with Gemini for multimodal inspection-evidence extraction. However, successful live extraction and automatic estimate generation remain unverified and require further Firebase App Check configuration and testing.

---

## 2:20 – 3:05 | Offline-First Synchronization Interface

**Show:** Open the Sync Queue screen.

**What the recording demonstrates:**
- The synchronization queue interface.
- Two synchronization operations marked `SYNCED`.
- The application's local synchronization workflow.

**Technical context:**

The application includes:
- Room-based local persistence.
- A synchronization outbox.
- WorkManager background processing.
- Retry and idempotency infrastructure.
- A REST API integration layer.

**Important limitation:**

The recorded synchronization operations were processed through the `LoopbackRestInterceptor`, which simulates backend responses.

The `SYNCED` status therefore demonstrates the local mock synchronization workflow only. It does not establish successful uploads to a deployed production backend or cloud media storage.

---

## 3:05 – 4:11 | Evaluation, Cost Telemetry and JSON Report

**Show:** Open the Evaluation & Cost screen.

**What the recording demonstrates:**
- The bundled 42-frame evaluation pool.
- Computer-vision evaluation results.
- Evaluation telemetry.
- JSON evaluation report export.

**Observed evaluation status:**
- The evaluation pool contained 42 frames.
- No additional photos were imported through Android Photo Picker during the recorded session.
- AI latency was displayed as `0 ms`.
- Token usage was displayed as `0`.
- Estimated AI cost was displayed as `$0.000000`.

**Technical context:**

The evaluation infrastructure includes synthetic image perturbations and computer-vision quality checks intended to assess image conditions such as blur, brightness, and framing.

The exported JSON report provides a structured representation of the evaluation results.

**Important evaluation limitations:**
- The demonstrated evaluation is based on the bundled sample and synthetic evaluation pool.
- Live Gemini batch inference was not successfully demonstrated.
- Real-world extraction accuracy has not been established.
- The exported report contained `realFrameCount: 1` and `syntheticFrameCount: 41`, while the interface showed zero Photo Picker imports. The source classification of that additional frame has not been independently verified and should not be interpreted as proof of a genuine physical field photograph.

---

## Final Notes and Verified Scope

The submitted recording demonstrates the Android application interface, Mock Device Kit workflow, sample inspection evidence, Job & Estimate interface, mock synchronization queue, and evaluation/reporting interface.

The following areas require further validation:

1. Physical Ray-Ban Meta glasses integration and camera streaming.
2. Successful live Gemini extraction and structured estimate generation.
3. Production REST backend and cloud media synchronization.
4. Collection of at least 40 genuine field-inspection images with ground-truth annotations.
5. Real-world extraction accuracy and failure-case evaluation.
6. Consistent final UI validation on a physical Android device.

The project successfully compiled as a debug build, and the local JVM test results reported 14 passing tests, one skipped test, and zero failures.

**The recording is submitted as an emulator-based prototype demonstration. Features that were not successfully demonstrated are explicitly identified as unverified rather than presented as completed functionality.**
