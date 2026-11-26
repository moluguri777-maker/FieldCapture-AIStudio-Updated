# FieldCapture — Hands-Free Field Capture on AI Glasses

FieldCapture is an offline-first Android field inspection application built with Kotlin and Jetpack Compose for hands-free field evidence collection using Meta Wearables Device Access Toolkit (DAT 1.0.1).

The application combines voice-command input, image-quality validation, structured evidence records, AI-assisted extraction using Gemini through Firebase AI Logic, a local Room database, and a WorkManager-based synchronization queue.

The project includes a Mock Device Kit workflow for development and testing without requiring physical Ray-Ban Meta glasses.

> **Implementation status:** The Android application builds and launches successfully. Evidence capture, image-quality indicators, job records, and the synchronization queue are demonstrated in the current build. AI extraction and some end-to-end integrations have known limitations documented below.

---

## 1. Architecture Overview

```text
Voice Input / Quick Commands
             |
             v
     VoiceCommandParser
             |
             v
     WearableSessionGateway
       (DAT 1.0.1 / Mock Kit)
             |
             v
       Evidence Capture
             |
             v
      ImageQualityAnalyzer
       (Blur / Luma / Framing)
             |
             v
      Duplicate SHA-256 Check
             |
             v
          Room DB
             |
       +-----+------+
       |            |
       v            v
GeminiExtraction   SyncOutbox
Service             Entity
       |            |
       v            v
Structured       WorkManager
Extraction        SyncWorker
       |            |
       v            v
14-Code         REST API /
Estimate        Loopback Mock
Catalog         Server
```

### Core Packages

Located under `app/src/main/java/com/example/`:

| Package | Responsibility |
|---|---|
| `ai/` | Firebase App Check initialization and Gemini extraction |
| `wearable/` | Wearable session lifecycle, DAT integration and mock scenes |
| `cv/` | On-device image-quality analysis |
| `voice/` | Speech input and extensible voice-command parsing |
| `data/` | Room database, REST API, repositories and synchronization |
| `domain/model/` | Evidence models, job records and estimate-code catalog |
| `eval/` | Batch evaluation engine and ground-truth evaluation |
| `ui/` | Jetpack Compose screens and application state management |

### Main Screens

- Capture
- Job & Estimate
- Sync Queue
- Evaluation & Cost

---

## 2. Technology Stack

| Component | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Android Architecture | MVVM |
| Local Database | Room |
| Background Processing | WorkManager |
| Networking | Retrofit / OkHttp |
| Wearable Integration | Meta Wearables DAT 1.0.1 |
| Speech Input | Android SpeechRecognizer |
| Image Quality | On-device Computer Vision |
| AI Model | Gemini 3.8 Flash |
| AI Integration | Firebase AI Logic |
| AI Security | Firebase App Check |
| Build System | Gradle |

---

## 3. Prerequisites

- Android Studio with JDK 17 or newer.
- Android SDK 36.
- Android emulator or Android device (API 29+).
- Internet access for Firebase and Gemini requests.
- Firebase project configured for Firebase AI Logic and App Check.

**Android application ID:**

`com.aistudio.fieldcapture.krmxvp`

### Firebase Configuration

The Firebase configuration file is intentionally excluded from version control.

To configure Firebase locally:

1. Obtain the Android `google-services.json` file from the configured Firebase project.
2. Place it inside the `app/` directory.
3. Ensure the Google Services Gradle plugin is configured.
4. Sync the project with Gradle files.

Do not commit Firebase configuration files, App Check debug tokens, or other local secrets to the repository.

### App Check Debug Configuration

For local debug builds:

1. Launch the application.
2. Inspect Logcat for the Firebase App Check debug provider.
3. Obtain the generated debug token.
4. Register the token in Firebase Console → App Check → Manage debug tokens.
5. Relaunch the application and verify token initialization.

Release builds are configured to use the reCAPTCHA App Check provider.

---

## 4. Setup and Run

1. Clone the repository.
2. Open the repository root in Android Studio.
3. Configure `google-services.json` locally as described above.
4. Allow Gradle synchronization to finish.
5. Select an Android emulator or connected device.
6. Run the `app` configuration.

### Build Commands

Build the debug APK:

```bash
gradle :app:assembleDebug
```

Run JVM unit tests:

```bash
gradle :app:testDebugUnitTest
```

---

## 5. Capture Workflow and Voice Commands

The Capture screen provides a field-inspection interface with a camera preview, session controls, voice input and quick-command buttons.

### Supported Command Formats

**1. Nameplate capture**

```text
capture nameplate
```

Intended extraction fields include:

- Manufacturer
- Model number
- Serial number
- Manufacturing date
- Voltage and phase
- Amperage or BTU
- Refrigerant type

**2. Damage capture**

```text
capture damage basement drywall
```

Intended structured fields include:

- Room
- Surface
- Damage type
- Severity
- Estimated area
- Risk
- Recommended action

**3. Technician note**

```text
note: Main breaker lug shows scorch marks
```

Intended structured fields include:

- Summary
- Category
- Safety hazard
- Follow-up required
- Referenced components

### Mock Device Kit

The application provides a mock-device workflow for development without physical smart glasses.

Available controls include:

- Start
- Pause
- Stop
- Sim Drop
- Mock scene selection

Mock scene images are identified separately from actual camera input.

**Important:** The mock workflow is not proof of successful physical Ray-Ban Meta hardware integration.

---

## 6. Image Quality Validation

The application includes on-device image-quality checks before evidence processing.

The quality analyzer evaluates:

- Blur using Laplacian variance.
- Brightness using luma measurements.
- Framing using edge-based analysis.

Quality results are displayed on the Capture and Job & Estimate screens.

Captured evidence can be retained locally even when subsequent AI processing fails.

---

## 7. AI-Based Evidence Extraction

FieldCapture integrates Gemini 3.8 Flash through Firebase AI Logic.

The model was selected for multimodal image understanding and structured extraction of field-inspection information from nameplate and damage photographs.

The intended workflow is:

1. Capture and save evidence.
2. Submit the evidence image for AI processing.
3. Extract structured fields.
4. Associate confidence information with extracted fields.
5. Match extracted information against the 14-code estimate catalog.
6. Allow technicians to review and retry extraction.

### Current AI Limitation

During testing, AI extraction timed out after 60 seconds and displayed a model availability / high-demand error.

- Evidence photographs were retained locally.
- Image-quality results were still displayed.
- Structured extraction results and confidence scores were unavailable for affected captures.
- Automatic estimate-line population could not be reliably demonstrated.

The retry option allows another extraction attempt without capturing the photograph again.

### AI Cost

The application includes a cost and latency telemetry interface.

A reliable per-job AI cost has not yet been established because successful extraction and token consumption were not demonstrated during the recorded tests.

Actual cost depends on model pricing, input image processing, prompt tokens, output tokens and the number of extraction attempts.

The displayed zero-token and zero-cost values should not be interpreted as measured successful processing.

---

## 8. Job Records and Estimate Catalog

The Job & Estimate screen displays:

- Structured job record information.
- Captured evidence.
- Evidence identifiers.
- AI extraction status.
- Estimate line items.
- Estimated scope total.
- Retry AI Extraction.
- Edit Codes.

The application includes a 14-code estimate catalog intended to map extracted inspection information to relevant HVAC and electrical work items.

**Current limitation:** Because AI extraction is unreliable, automatic estimate-code population and total calculation have not been fully validated. The displayed $0.00 total in the tested session reflects the absence of populated estimate items, not a verified zero-cost job.

---

## 9. Offline-First Synchronization

FieldCapture uses Room and WorkManager to support local evidence persistence and queued synchronization.

The intended synchronization workflow is:

1. Save evidence locally.
2. Create synchronization operations in the outbox.
3. Upload media.
4. Synchronize the job record.
5. Retry transient failures using background work.

The Sync Queue screen includes:

- Pending and synchronized operation counts.
- Sync Now.
- Loopback Mock REST Server toggle.
- Simulated HTTP 503 control.
- Outbox operation details.
- Idempotency keys.

### Current Backend Limitation

The current application demonstrates synchronization through a **Loopback Mock REST Server**.

The displayed synchronized operations represent processing by this mock server.

A production backend, real cloud media storage and end-to-end synchronization with a deployed REST service have not been validated.

---

## 10. Evaluation and Telemetry

The Evaluation & Cost screen provides:

- Capture-quality telemetry.
- AI latency information.
- Synchronization latency.
- Token usage.
- Estimated AI cost.
- A 42-frame batch evaluation interface.
- Photo import functionality.
- Evaluation controls.

The evaluation engine includes a bundled frame pool and synthetic perturbations for development and testing.

### Evaluation Status

A complete evaluation using at least 40 independently imported real-world field photographs has not been demonstrated.

The current screenshots show zero imported photos, zero AI tokens and zero AI latency measurements in the evaluation dashboard.

These values represent the absence of successful measured AI evaluation in that session. They are not accuracy or performance benchmarks.

A complete real-world evaluation should include:

- At least 10 nameplate images.
- At least 10 low-light images.
- Ground-truth annotations.
- Per-field extraction accuracy.
- The five worst-performing extraction cases.

---

## 11. Known Limitations and Testing Status

The following limitations are explicitly documented based on the current implementation and testing.

| Area | Current status |
|---|---|
| Android debug build | Successfully builds |
| Application launch | Successfully launched |
| Capture interface | Demonstrated |
| Image-quality indicators | Demonstrated |
| Evidence persistence | Demonstrated in the Job & Estimate screen |
| Voice command system | Implemented; complete real-world validation pending |
| AI extraction | Timed out during testing |
| Automatic estimate generation | Not reliably validated |
| Synchronization | Demonstrated with Loopback Mock REST Server |
| Production backend | Not integrated or validated |
| 40-frame real-world evaluation | Not completed |
| AI cost measurement | Not established |
| Physical Ray-Ban Meta hardware | Not validated end-to-end |

### Additional Hardware Limitations

Physical Ray-Ban Meta glasses pairing, real-world Bluetooth/Wi-Fi P2P connectivity, physical device streaming and camera capture have not been fully validated.

The mock-device workflow is available for development and demonstration.

---

## 12. Assumptions

The following assumptions were made where the assignment requirements did not specify implementation details:

1. The application uses a mock-device workflow when physical Meta glasses are unavailable.
2. A captured photograph must be saved locally before AI processing so that extraction failures do not result in evidence loss.
3. Image-quality validation should happen before submitting evidence for AI extraction.
4. AI-extracted information should be reviewable by a technician before being used for an estimate.
5. The 14-code catalog provides an initial set of HVAC and electrical estimate categories.
6. A loopback REST server is used for development and synchronization testing until a production backend is available.
7. Synthetic evaluation frames are development aids and are not substitutes for independently collected real-world evaluation images.

---

## 13. Development Tools and AI Assistance

The project was developed using modern Android development tools and AI-assisted workflows.

**Development Environment**
- Android Studio — Android application development, debugging, emulator testing and Gradle builds.
- Kotlin and Jetpack Compose — Application implementation and user interface.
- Git and GitHub — Version control and source-code management.

**AI-Assisted Development**
- Google AI Studio and Gemini — Assisted with code exploration, implementation, debugging and test-case development.
- ChatGPT — Used for technical discussions, troubleshooting, code reviews, architecture guidance and documentation.

**AI Integration Within the Application**

Gemini 3.8 Flash is integrated through Firebase AI Logic for multimodal evidence extraction and structured field-inspection data processing.

AI-assisted development tools and the AI model integrated into the application serve different purposes.

## 14. Future Improvements

### Hardware and Wearable Integration
- Validate physical Ray-Ban Meta pairing and camera streaming.
- Verify DAT permissions and session recovery on hardware.
- Test real-world field capture conditions.

### AI and Estimation
- Resolve Firebase App Check and AI extraction availability issues.
- Validate structured extraction across real nameplate and damage photographs.
- Measure per-field accuracy and confidence calibration.
- Validate estimate-code matching and total calculations.
- Measure actual token usage and cost per job.

### Backend and Synchronization
- Replace the loopback REST server with a deployed backend.
- Configure production media storage.
- Validate upload recovery and idempotency under real network interruptions.

### Evaluation
- Collect and annotate at least 40 real field photographs.
- Complete the evaluation report.
- Identify and analyze the five worst extraction failures.
- Measure real-world latency and accuracy.

---

## 15. Documentation Deliverables

The project is intended to include:

- Evaluation Report.
- Dataset Guide and Ground Truth Template.
- Meta Glasses and DAT 1.0 Research Memo.
- Job Record Schema and Templates.
- Five-Minute Demo Script.

The completion status of these supporting deliverables should be checked separately from the Android application's implementation status.

---

## 16. Conclusion

FieldCapture provides an Android foundation for hands-free field inspection, evidence capture, image-quality validation, structured job records and offline-first synchronization.

The current build demonstrates the application interface and core evidence-management workflow. AI extraction, production backend integration, physical wearable validation and complete real-world evaluation remain areas for further development.

The project documents these limitations explicitly to distinguish implemented components from functionality that has been fully validated.
