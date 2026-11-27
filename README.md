# FieldCapture — Hands-Free Field Capture on AI Glasses

FieldCapture is an offline-first Android field-inspection application built using Kotlin and Jetpack Compose. It is designed for hands-free property, HVAC, and electrical inspections using Ray-Ban Meta AI Glasses through the Meta Wearables Device Access Toolkit (DAT 1.0.1).

The application brings together voice-command workflows, image-quality validation, structured evidence capture, AI-assisted extraction, local persistence, synchronization infrastructure, and evaluation telemetry.

The project includes a Mock Device Kit workflow for development and emulator-based testing.

---

## 1. Architecture Overview

```text
Voice Commands / Quick Commands
              |
              v
     VoiceCommandParser
     (VoiceCommandSpec Registry)
              |
              v
   WearableSessionGateway
   (DAT 1.0.1 + Mock Device Kit)
              |
              v
     ImageQualityAnalyzer
     (Blur / Brightness / Framing)
              |
       +------+------+
       |             |
      FAIL          PASS
       |             |
       v             v
   Retake Alert   Duplicate SHA-256 Check
                     |
                     v
                 Room Database
                     |
          +----------+----------+
          |                     |
          v                     v
  GeminiExtractionService   SyncOutboxEntity
  (Firebase AI Logic)       (Upload / Sync)
          |                     |
          v                     v
  Structured Extraction    WorkManager
  Confidence Scoring       SyncWorker
          |                     |
          v                     v
  Estimate Code Matcher    REST API Layer
```

### Core packages

Located under `app/src/main/java/com/example/`:

- `ai/` — App Check initialization and Gemini extraction service.
- `wearable/` — Wearable session gateway and mock scene media generation.
- `cv/` — Image-quality analysis, including blur, brightness, and framing checks.
- `voice/` — Voice-command parser and voice-input controller.
- `data/` — Room database, REST API, repository, and synchronization worker.
- `domain/model/` — Inspection models, evidence provenance, and estimate-code catalog.
- `eval/` — Batch evaluation engine and ground-truth evaluator.
- `ui/` — Jetpack Compose screens and application ViewModel.

### Main application screens

- Capture Deck
- Job & Estimate
- Sync Queue
- Evaluation & Cost Telemetry

---

## 2. Technology Stack

| Category | Technologies |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Android architecture | ViewModel, Repository |
| Wearable integration | Meta Wearables Device Access Toolkit 1.0.1 |
| Development simulation | Mock Device Kit |
| Local database | Room |
| Background processing | WorkManager |
| Networking | Retrofit, OkHttp |
| AI integration | Firebase AI Logic, Gemini 3.8 Flash |
| App security | Firebase App Check |
| Image processing | On-device computer vision |
| Testing | JUnit, Robolectric test infrastructure |
| Build system | Gradle |

---

## 3. Prerequisites

- Android Studio (Meerkat, Narwhal, or newer).
- JDK 17 or later.
- Android SDK 36.
- Android Emulator or Android device with API 29 or later.
- Internet connectivity for Firebase App Check and Gemini requests.

### Firebase configuration

The project is configured with the following Firebase application:

- Firebase Project ID: `gen-lang-client-0675019109`
- Android Package: `com.aistudio.fieldcapture.krmxvp`
- Firebase App ID: `1:710382825344:android:217d80d8206c797f415b65`

The application uses Firebase AI Logic and Firebase App Check.

No manually embedded Gemini API key is required.

**Configuration note:** The `google-services.json` file must be obtained from the authorized Firebase project and placed inside the `app/` directory. It is intentionally excluded from Git version control.

### Firebase App Check

For local development:

1. Launch the application.
2. Open Android Studio Logcat.
3. Filter for `DebugAppCheckProvider`.
4. Retrieve the generated debug token.
5. Register the token in Firebase Console → App Check → Apps → Manage debug tokens.

The application includes App Check initialization and token handling for Firebase requests.

---

## 4. Setup and Running the Application

### Step 1: Clone the repository

```bash
git clone <YOUR_GITHUB_REPOSITORY_URL>
```

### Step 2: Open the project

Open the cloned project root in Android Studio and allow Gradle synchronization to complete.

### Step 3: Configure Firebase

Place the authorized `google-services.json` file in:

```text
app/google-services.json
```

Ensure the Firebase configuration and App Check settings are correctly configured.

### Step 4: Build the application

On Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

### Step 5: Run the application

Select an Android Emulator or connected Android device in Android Studio and press Run.

Alternatively, launch the installed application using ADB:

```powershell
adb shell monkey -p com.aistudio.fieldcapture.krmxvp 1
```

---

## 5. Mock Device Kit and Capture Workflow

The application includes a simulated wearable-device workflow for development and testing.

### Mock Device Kit workflow

1. Open the Capture tab.
2. Select **Pair Mock Glasses**.
3. Select **Start** to initiate the simulated session.
4. Choose an available scene:
   - HVAC Nameplate
   - Basement Drywall Damage
   - Electrical Breaker Panel
5. Use the available device-state controls to simulate power, hinge, and wearing-state changes.
6. Use the simulated disconnect control to exercise reconnection handling.

The application distinguishes mock sample imagery from rear-camera imagery using evidence-source provenance.

A physical Ray-Ban Meta device connection is not claimed by the mock workflow.

### Voice command examples

The application provides structured command parsing and a microphone/quick-command interface.

Supported command formats include:

**1. Nameplate capture**

```text
capture nameplate
```

Intended extraction fields include:

- Manufacturer
- Model number
- Serial number
- Manufacture date
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

**3. Inspection note**

```text
note: Main breaker lug shows scorch marks
```

Intended structured note fields include:

- Summary
- Category
- Safety hazard
- Follow-up requirement
- Referenced components

The Quick-Command interface provides an alternative input method when speech recognition is unavailable.

---

## 6. Image Quality Validation

The application includes an on-device image-quality analysis component.

The `ImageQualityAnalyzer` evaluates captured images using:

- Laplacian variance for blur detection.
- BT.601 luminance for brightness analysis.
- Sobel-based analysis for framing assessment.

Images that do not meet configured quality thresholds can be flagged for review or retake.

The quality-analysis implementation is intended to reduce unusable inspection evidence before AI processing.

Threshold calibration against real-world Meta glasses camera images remains future work.

---

## 7. AI-Assisted Evidence Extraction

The application integrates Firebase AI Logic with Gemini 3.8 Flash for multimodal field-evidence extraction.

The intended processing workflow is:

1. Capture and persist inspection evidence.
2. Submit the image and relevant context for AI processing.
3. Extract structured inspection information.
4. Associate confidence information with extracted fields.
5. Flag low-confidence information for review.
6. Map supported findings to estimate-code candidates.

The extraction service includes structured-output handling and retry functionality.

### Observed development result

During emulator testing, an AI extraction request timed out after approximately 60 seconds.

The captured evidence remained available in the application, but successful extraction and population of structured estimate fields could not be verified in that run.

Therefore, successful end-to-end AI extraction is not claimed as a completed validation result.


**Additional observed Firebase App Check error:**

During the recorded demonstration, the Job & Estimate screen also displayed:

`Firebase App Check token is invalid (Too many attempts)`

The extraction request consequently failed, and successful live Gemini inference could not be demonstrated. The captured evidence remained available locally, but no estimate line items were generated and the displayed estimated total remained `$0.00`.

Firebase App Check authentication and successful live AI extraction require further validation.


---

## 8. Offline-First Data Persistence and Synchronization

The application uses Room for local evidence persistence and WorkManager for background synchronization.

### Local persistence

Captured evidence is designed to be stored locally before AI extraction and synchronization.

The local database includes inspection evidence and synchronization-related entities.

### Synchronization workflow

The synchronization architecture includes:

- `SyncOutboxEntity`
- Media upload operations
- Job synchronization operations
- Idempotency keys
- WorkManager background execution
- Retry handling with exponential backoff
- Retrofit and OkHttp networking

### Mock backend mode

The current development workflow includes a `LoopbackRestInterceptor` that simulates REST backend responses.

The Sync Queue screen displayed successfully processed operations against the loopback mock server during development.

This demonstrates the mock synchronization workflow, not successful communication with a deployed production backend.

A production REST backend and actual cloud media storage remain integration work.


### Recorded demonstration results

The submitted emulator demonstration showed two synchronization operations marked `SYNCED` against the loopback mock REST server.

This demonstrates the locally simulated synchronization workflow only. It does not establish successful production-server uploads, cloud media persistence, or end-to-end synchronization with a deployed backend.


---

## 9. Evaluation and Cost Telemetry

The application includes an evaluation and telemetry interface.

The evaluation infrastructure contains:

- A 42-frame sample evaluation pool.
- Synthetic image perturbations.
- Ground-truth evaluation components.
- AI latency and token telemetry.
- Estimated inference-cost tracking.
- Photo import functionality for additional evaluation data.

### Evaluation status

The bundled evaluation pool is intended for development and testing.

During the observed emulator session:

- The 42-frame sample pool was displayed.
- No real photos had been imported through the Photo Picker.
- AI latency and token usage were displayed as zero.
- Real-world per-field accuracy was not established.

The required real-world evaluation dataset has not yet been completed.

To complete the evaluation, genuine inspection images should be collected and imported, including:

- At least 10 nameplate images.
- At least 10 low-light images.
- Additional field-inspection images to reach at least 40 total frames.

The images should be annotated against the ground-truth template before calculating extraction accuracy and identifying the five worst-performing cases.

Synthetic samples must not be represented as real-world inspection results.


### Evaluation report source-count discrepancy

During the recorded demonstration, the evaluation interface displayed zero photos imported through Android Photo Picker.

However, the exported evaluation report contained:

- `realFrameCount: 1`
- `syntheticFrameCount: 41`

The origin and classification of the additional frame have not yet been independently verified. It may be associated with a session-captured frame, but this must be checked against the source-classification logic in `BatchEvaluationEngine`.

Therefore, the exported report's source counts should not be interpreted as evidence of one genuine physical field photograph.

The current evaluation is presented as a synthetic CV quality-check benchmark. Real-world field accuracy remains unmeasured.


---

## 10. Build and Test Results

The following checks were performed locally during development.

### Android debug build

Command:

```powershell
.\gradlew.bat :app:assembleDebug
```

Result:

**BUILD SUCCESSFUL**

The Android debug APK compiled successfully.

### JVM unit tests

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Result:

**BUILD SUCCESSFUL**

The generated test-result XML files reported:

| Test suite | Total | Passed | Failed | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| `ExampleUnitTest` | 14 | 14 | 0 | 0 | 0 |
| `ExampleRobolectricTest` | 1 | 0 | 0 | 0 | 1 |
| **Total** | **15** | **14** | **0** | **0** | **1** |

All 14 tests in `ExampleUnitTest` passed.

One Robolectric test was skipped. It is marked with `@Ignore` because the Robolectric Android runtime dependency requires external Maven downloads that were unavailable in the original offline development environment.

### Testing scope

The unit-test suite includes coverage for repository functionality such as:

- Voice-command parsing.
- Command extensibility.
- Image-quality thresholds.
- Evaluation logic.
- Gemini response/schema handling.
- Duplicate-evidence prevention.
- AI retry handling.
- Synchronization recovery.
- Ground-truth evaluation.

Passing unit tests demonstrate the behavior covered by those tests. They do not establish complete physical-device, live-AI, or production-backend functionality.

### Manual verification notes

The application was launched in the Android Emulator during development.

The emulator subsequently experienced display-rendering problems, which prevented consistent final UI verification.

AI extraction timeout and mock-backend behavior were also observed during application testing.

---

## 11. Known Limitations

The following items remain unverified or incomplete:

### 1. Physical Ray-Ban Meta Glasses

The development workflow uses Mock Device Kit simulation.

Real Bluetooth/Wi-Fi P2P pairing, physical glasses streaming, and camera capture have not been verified on hardware.

Physical Wearables Developer Portal credentials, including `APPLICATION_ID` and `CLIENT_TOKEN`, must be configured and validated for hardware integration.

### 2. Production Backend

The current synchronization workflow uses `LoopbackRestInterceptor` in mock mode.

A deployed REST backend and production media-storage integration have not been verified.

### 3. Live AI Extraction

A Gemini extraction request timed out during observed emulator testing.

Successful structured extraction and estimate population remain unverified.

### 4. Real-World Evaluation Dataset

The bundled 42-frame evaluation pool includes sample imagery and synthetic perturbations.

The required 40 or more genuine field-inspection images, ground-truth annotations, accuracy measurements, and five worst-case failure analyses remain pending.

### 5. Speech Recognition

Speech recognition depends on the Android device's available speech services and runtime permissions.

The Quick-Command interface provides an alternative input method. Full voice-command execution through physical AI glasses has not been verified.

### 6. Emulator Rendering

The Android Emulator experienced display-rendering issues during the final development checks.

The debug build and unit-test execution succeeded, but consistent final emulator UI validation was not achieved.

---

## 12. Future Development Plan

### Phase 1 — Hardware Integration and Data Collection

- Configure Meta Wearables Developer Portal credentials.
- Validate physical device pairing and session lifecycle.
- Test streaming and photo capture on Ray-Ban Meta glasses.
- Collect at least 40 genuine inspection images.
- Complete ground-truth annotations.
- Evaluate live Gemini extraction accuracy.

### Phase 2 — Backend and Production Readiness

- Connect the REST API to a deployed backend.
- Implement production media storage and upload handling.
- Validate offline-to-online synchronization.
- Calibrate image-quality thresholds using real camera images.
- Measure speech recognition performance in noisy environments.
- Perform hardware testing for battery, thermal behavior, and connection stability.
- Validate release-mode Firebase App Check configuration.

---

## 13. Documentation Deliverables

The project documentation and supporting deliverables are intended to include:

- Evaluation report.
- Dataset guide and ground-truth template.
- Meta Glasses and DAT 1.0 research memo.
- Job record schema and JSON templates.
- Demonstration video (maximum five minutes).
- Demo script and recording notes (supporting documentation).

Only completed and genuinely verified deliverables should be presented as finished.

---

## 14. Development Tools and AI Assistance

The project was developed using modern Android development tools and AI-assisted workflows.

### Development environment

- Android Studio — Android application development, debugging, emulator testing, and Gradle builds.
- Kotlin and Jetpack Compose — Application implementation and user interface.
- Git and GitHub — Version control and source-code management.

### AI-assisted development

- Google AI Studio and Gemini — Assisted with code exploration, implementation, debugging, and test-case development.
- ChatGPT — Used for technical discussions, troubleshooting, code reviews, architecture guidance, and documentation.

### AI integration within the application

Gemini 3.8 Flash is integrated through Firebase AI Logic for multimodal evidence extraction and structured field-inspection data processing.

AI-assisted development tools and the AI model integrated into the application serve different purposes.

---

## 15. Conclusion

FieldCapture provides an Android-based foundation for hands-free field inspection workflows, combining wearable-device integration architecture, structured capture commands, image-quality analysis, local persistence, AI extraction integration, and synchronization infrastructure.

The project includes a working Android debug build and a locally executed unit-test suite with 14 passing tests.

Further work is required to validate physical AI glasses, successful live AI extraction, production backend synchronization, and real-world evaluation performance.

The implementation is designed to provide a foundation for extending the prototype into a field-ready inspection application.
