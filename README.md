# FieldCapture — Hands-Free Field Capture on AI Glasses

FieldCapture is an offline-first Android (Kotlin + Jetpack Compose) field inspection application built for **Ray-Ban Meta AI Glasses** using the **Meta Wearables Device Access Toolkit (`mwdat` 1.0.1)**, **on-device CV quality gates**, **Room + WorkManager** outbox synchronization, and **Firebase AI Logic (`gemini-3.8-flash`)** protected by **Firebase App Check**.

---

## 1. Architecture Overview

```
Voice / Quick-Cmd ──▶ VoiceCommandParser (VoiceCommandSpec registry)
                            │
                            ▼
WearableSessionGateway (DAT 1.0.1 + Mock Device Kit, requires STREAMING state)
                            │ (Bitmap + EvidenceSource provenance)
                            ▼
ImageQualityAnalyzer (On-Device Laplacian Blur / BT.601 Luma / Sobel Framing)
   ├── FAIL ──▶ Retake Alert Card (Explicit Override or Discard)
   └── PASS ──▶ Duplicate SHA-256 Check ──▶ Room DB (CaptureEvidenceEntity v2)
                                                  │
                                                  ├──▶ GeminiExtractionService (Firebase AI Logic + App Check)
                                                  │       ├── Per-Field Confidence + needsReview (<80%)
                                                  │       ├── Retry AI without Recapture
                                                  │       └── 14-Code Estimate Catalog Matcher
                                                  │
                                                  └──▶ SyncOutboxEntity (UPLOAD_MEDIA + SYNC_JOB + Idempotency Key)
                                                          └──▶ WorkManager (SyncWorker) + Retrofit/OkHttp
```

### Core Packages (`app/src/main/java/com/example/`)
- `ai/`: `AppCheckInit.kt` (`configureAppCheck`, `AppCheckCoordinator`), `GeminiExtractionService.kt` (`gemini-3.8-flash` JSON schema extraction).
- `wearable/`: `WearableSessionGateway.kt` (`DatWearableSessionGateway`), `MockSceneMediaGenerator.kt`.
- `cv/`: `ImageQualityAnalyzer.kt` (`QualityThresholds` + deterministic blur/luma/framing checks).
- `voice/`: `VoiceCommandParser.kt` (extensible `VoiceCommandSpec` registry), `VoiceInputController.kt`.
- `data/`: `local/` (Room DB `v2` with `MIGRATION_1_2`), `remote/` (`FieldCaptureRestApi` + `LoopbackRestInterceptor`), `repository/` (`FieldCaptureRepository`), `sync/` (`SyncWorker`).
- `domain/model/`: `FieldCaptureModels.kt` (14-code `EstimateCodeCatalog`, `EvidenceSource` provenance, `JobRecordJson` v1.0).
- `eval/`: `BatchEvaluationEngine.kt` (42-frame pool + `EvalGroundTruthEvaluator`).
- `ui/`: Compose Material 3 screens (`CaptureDeckScreen`, `JobEvidenceScreen`, `SyncQueueScreen`, `EvalTelemetryScreen`) + `FieldCaptureViewModel`.

---

## 2. Prerequisites
- **Android Studio** (Meerkat / Narwhal or newer) with JDK 17+ and Android SDK 36 (`minSdk = 29`, `targetSdk = 36`).
- **Android Emulator or Device** (API 29+) with internet access for Firebase App Check & Gemini calls.
- **Firebase Project**: `gen-lang-client-0675019109` (already bound via `app/google-services.json` and `app/src/main/res/values/firebase_applet_config.xml`).

---

## 3. Setup & Run in Under 10 Minutes

1. **Open Project**: Open the repository root in Android Studio (or build from CLI using `gradle` — note `local.properties` should NOT be committed; `ANDROID_SDK_ROOT` is used in CI/cloud builds).
2. **Firebase Configuration (Already Provisioned)**:
   - **Project ID:** `gen-lang-client-0675019109`
   - **Android Package:** `com.aistudio.fieldcapture.krmxvp`
   - **Firebase App ID:** `1:710382825344:android:217d80d8206c797f415b65`
   - **AI Auth Model:** Uses **Firebase AI Logic (`Firebase.ai`) + Firebase App Check**. **Do NOT add a `GEMINI_API_KEY`**.
3. **App Check Debug Token (Important for Local Android Studio Launches)**:
   - When launched from **AI Studio**, `FIREBASE_APPCHECK_DEBUG_TOKEN` is injected via launch `Intent` extra and committed synchronously in `AppCheckInit.kt`.
   - When launching locally from **Android Studio** without that `Intent` extra:
     1. Run the app once and filter Logcat for `DebugAppCheckProvider`.
     2. Copy the generated UUID debug secret.
     3. Register that UUID in **Firebase Console -> App Check -> Apps -> `com.aistudio.fieldcapture.krmxvp` -> Manage debug tokens**.
   - **Release builds** automatically switch to `RecaptchaAppCheckProviderFactory` using `recaptcha_site_key` in `app/src/main/res/values/firebase_applet_config.xml`.
4. **Run the App**:
   - Select the `app` run configuration in Android Studio and press **Run** on an emulator or physical Android phone.

---

## 4. Using Mock Device Kit & Voice Commands

### Mock Device Kit Workflow
1. On the **Capture** tab, tap **Pair Mock Glasses** (`pair_mock_glasses_button`).
2. Tap **Start** (`session_start_button`) so `DeviceSession` reaches `STARTED` and `STREAMING`. *(Photo capture intentionally refuses while stopped or paused).*
3. Choose a scene pill (**HVAC Nameplate**, **Basement Drywall Damage**, **Electrical Breaker Panel**), or toggle **Route Android Rear Camera Feed** (`CAMERA` runtime permission).
   - Viewfinder & Job cards display explicit provenance badges (`MOCK SAMPLE` vs `REAR CAM BRIDGE`) so bundled mock scenes are never confused with real camera captures.
4. Use **Sim Drop** (`simulate_disconnect_button`), **Power**, **Hinges**, or **Donned** switches to test backoff reconnect recovery without losing Room evidence.

### Voice Command Examples
Trigger via the microphone button (`RECORD_AUDIO` permission) or the Quick-Command bar:
1. **`capture nameplate`** — Captures active frame, validates nameplate sharpness (`>= 95.0` Laplacian variance), extracts `manufacturer`, `modelNumber`, `serialNumber`, `manufactureDate`, `voltagePhase`, `amperageOrBtu`, `refrigerantType`, and maps HVAC/electrical estimate codes.
2. **`capture damage basement drywall`** (`capture damage [room] [surface]`) — Validates missing room/surface arguments (`MissingArguments` feedback if incomplete), runs damage CV gates, extracts `room`, `surface`, `damageType`, `severity`, `estimatedAreaSqFt`, risk, and recommended action.
3. **`note: Main breaker lug shows scorch marks`** (`note: [free text]`) — Converts field speech/text into structured claim notes (`summary`, `category`, `safetyHazard`, `followUpRequired`, `referencedComponents`) with optional active frame context.

*Interview Extensibility:* Adding a 4th command only requires registering a new `VoiceCommandSpec` object in `VoiceCommandParser.defaultSpecs` and handling its `ParsedVoiceCommand` sealed subtype in `FieldCaptureViewModel`.

---

## 5. Offline-First Sync & Retry

- Every capture persists immediately to Room (`CaptureEvidenceEntity`) **before** Firebase AI extraction runs.
- If network connectivity is off or Firebase App Check/Gemini fails, the photo and record remain safely in **Job & Est**, and users can tap **Retry AI Extraction (Keep Saved Photo)** without re-shooting.
- Sync operations (`UPLOAD_MEDIA` followed by `SYNC_JOB`) are queued in `SyncOutboxEntity` with stable `X-Idempotency-Key` UUIDs and executed via `SyncWorker` (`WorkManager` exponential backoff `15s`) or **Sync Queue -> Sync Now**.
- Toggle **Loopback Mock REST Server** + **Sim 503** on **Sync Queue** to test transient HTTP `503` recovery without duplicate media uploads.

---

## 6. Testing Instructions

Run all JVM unit tests (voice parsing, 4th-command extensibility, CV thresholds & 42-frame benchmark, Gemini schema parsing & 0% confidence rejection, duplicate prevention, AI retry, offline HTTP 503 sync recovery, and ground-truth accuracy evaluator):

```bash
gradle :app:testDebugUnitTest
```

Compile the full Android debug APK:

```bash
gradle :app:assembleDebug
```

*Note:* `ExampleRobolectricTest.kt` is marked `@Ignore` because Robolectric's prebuilt `android-all` JAR requires external Maven downloads unavailable in offline sandbox environments; equivalent repository/CV/parser flows are tested via `ExampleUnitTest.kt`.

---

## 7. Known Limitations
- **Physical Ray-Ban Meta Glasses Not Tested:** Only `MockDeviceKit` simulation has been exercised; real Bluetooth/Wi-Fi P2P pairing via the Meta AI companion app and physical `mwdat` Developer Portal credentials (`APPLICATION_ID` / `CLIENT_TOKEN`) remain unverified on hardware.
- **Loopback REST Backend:** Cloud REST endpoints (`v1/inspections/{jobId}/media/{captureId}`, `v1/inspections/{jobId}/sync`) run against `LoopbackRestInterceptor` by default until pointed at a deployed backend URL in **Sync Queue**.
- **Real-World Evaluation Dataset Pending:** The 42-frame default evaluation run uses bundled sample drawables + synthetic perturbations (`SyntheticPerturbation`); genuine walkthrough photos must be imported via **Eval & Cost -> Import Photos** and scored against `eval_dataset/ground_truth_template.json`.
- **Android Emulator Speech Service:** Emulators without Google Speech Services use the Quick-Command buttons/text bar fallback (`VoiceInputController`).

---

## 8. Two-Week Future Development Plan
- **Week 1 (Hardware, Auth & Data Collection):**
  - Days 1–2: Register real Meta Wearables Developer Portal credentials, add `BLUETOOTH_CONNECT` runtime permission prompt, and verify pairing + `stream.capturePhoto()` HEIC capture on physical Ray-Ban Meta glasses.
  - Days 3–5: Collect >= 40 real field walkthrough photos (>= 10 nameplates, >= 10 low-light shots), complete `eval_dataset/ground_truth_template.json`, and run live `gemini-3.8-flash` per-field accuracy evaluation.
- **Week 2 (Production Backend, Calibration & Field Hardening):**
  - Days 6–8: Connect `FieldCaptureRestApi` to a production REST ingestion service + object storage presigned uploads (replacing loopback mode).
  - Days 9–11: Tune `QualityThresholds` ROC curves on physical 12 MP / 720p glasses frames and measure Bluetooth HFP voice command WER in noisy mechanical rooms.
  - Days 12–14: End-to-end instrumented QA on physical hardware, thermal/battery profiling over 30-minute inspections, and Play App Check release verification.

---

## 9. Documentation Deliverables
- [Evaluation Report](docs/evaluation_report.md)
- [Dataset Guide & Ground Truth Template](eval_dataset/README.md)
- [Meta Glasses & DAT 1.0 Research Memo](docs/research_memo.md)
- [Job Record Schema & Templates](docs/job_records/README.md)
- [<= 5-Minute Demo Script](docs/demo_script.md)

---

## 10. AI Tools Used & Disclosure
- **In-App Inference:** Google Gemini (`gemini-3.8-flash`) via **Firebase AI Logic** (`com.google.firebase:firebase-ai`) for structured multimodal field extraction and estimate code matching.
- **Engineering Assistance:** AI coding assistance was used during codebase audit, debugging (App Check token handling, Room/WorkManager sync fixes, CV/eval test coverage), and documentation preparation. All architectural claims, test results, and synthetic-vs-real dataset distinctions in this repository were verified directly against source files and Gradle test execution.
