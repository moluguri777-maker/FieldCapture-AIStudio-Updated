# FieldCapture — Evaluation Report

> **Important Dataset Disclosure:** All quantitative CV metrics reported below were genuinely measured in `ExampleUnitTest.qualityAnalyzer_bundledDrawablesAndSynthetic42FrameCvBenchmark` using **3 bundled sample drawables** and **42 synthetic geometric/photometric perturbations** (`sourceKind = SyntheticPerturbation`). **Zero real-world field walkthrough photos or live Gemini AI batch extraction runs were executed inside this offline build environment.** Per-field Gemini extraction accuracy (`manufacturer`, `modelNumber`, `serialNumber`, `manufactureDate`, `room`, `surface`, `damageType`, `estimatedAreaSqFt`) is therefore explicitly marked **PENDING REAL FIELD DATA** and must not be fabricated.

---

## 1. Dataset Composition & Real vs. Synthetic Separation

### Synthetic Validation Pool (42 Frames — Executed on JVM)
Generated deterministically by `BatchEvaluationEngine.collectEvaluationFrames` (and mirrored identically in `ExampleUnitTest`) from three bundled field scenes (`img_sample_nameplate_1795506161252.jpg`, `img_sample_damage_drywall_1795506162478.jpg`, `img_sample_electrical_panel_1795506162369.jpg`):

| Subset | Frame Count | Share of 42 | Assignment Requirement | Source Provenance |
| --- | ---: | ---: | --- | --- |
| **Total Evaluated Frames** | **42** | 100.0% | `>= 40` | `SyntheticPerturbation` |
| **HVAC / Equipment Nameplates** | **14** | 33.3% | `>= 10` | `SyntheticPerturbation` |
| **Damage / Electrical Panel Scenes** | **28** | 66.7% | N/A | `SyntheticPerturbation` |
| **Low-Light Examples** (`-72%` & `-84%` luma) | **12** | 28.6% | `>= 10` | `SyntheticPerturbation` |
| **Real Walkthrough Photos** | **0** | 0.0% | Pending capture | `PhotoPicker` / `SessionCapture` |

The 7 deterministic perturbation modes cycled across all 3 scenes (`variationIdx = 0..41`) are:
1. **Mode 0 — Nominal:** Unmodified bundled scene (6 frames).
2. **Mode 1 — Tight ROI Crop:** Center 72% crop (6 frames).
3. **Mode 2 — Dim Basement (`-72%` Luma):** RGB scaled by `0.28f` (6 low-light frames).
4. **Mode 3 — Motion Blur Sim:** `1/12x` bilinear downsample + upscale (6 frames).
5. **Mode 4 — Severe Low-Light (`-84%` Luma):** RGB scaled by `0.16f` (6 low-light frames).
6. **Mode 5 — Off-Center Framing:** Frame shifted to `(0.78w, 0.72h)` over a flat neutral canvas `(42, 46, 52)` (6 frames).
7. **Mode 6 — Glare / Angle Crop:** Alternates `+55%` brightness scale (`1.55f`, 3 frames) and top-left `85%` angle crop (3 frames).

### Real Walkthrough Data Status
- **Status:** **PENDING REAL FIELD DATA COLLECTION.**
- **Tooling Implemented & Ready:**
  - `eval_dataset/ground_truth_template.json` — Labeled schema for `sampleId`, `imageFileName`, `commandType`, `isLowLight`, and `expectedFields`.
  - **Eval & Cost -> Import Photos** (`ActivityResultContracts.PickMultipleVisualMedia`) — Imports genuine walkthrough photos tagged as `sourceKind = "PhotoPicker"` (and live captures from `filesDir/captures` tagged as `"SessionCapture"`).
  - `EvalGroundTruthEvaluator.evaluate(...)` — Verified via unit test (`evalGroundTruthEvaluator_computesPerFieldAccuracyCorrectly`) to score normalized exact-match per-field accuracy and mean confidence once live Gemini predictions are exported.

---

## 2. Measured CV Quality Gate Results (42 Synthetic Frames)

Executed via `gradle :app:testDebugUnitTest --rerun-tasks` (`TEST-com.example.ExampleUnitTest.xml`, 2026-11-25):

```text
JVM_CV_BENCHMARK total=42 nameplates=14 lowLight=12 pass=23 darkRejected=12 blurRejected=4 framingRejected=4 overexposedRejected=1
```

| Quality Metric | Threshold (`QualityThresholds`) | Measured Count (42 Frames) | Notes |
| --- | --- | ---: | --- |
| **Nominal Baseline Pass (3/3)** | All gates pass | **3 / 3** (100%) | All 3 unperturbed bundled drawables pass CV validation. |
| **Overall CV Gate Pass** | Zero triggered `QualityIssue`s | **23 / 42** (54.8%) | Sharp nominal, tight-ROI, angle-crop, and non-clipped frames pass. |
| **Overall CV Gate Rejected** | `>= 1` triggered `QualityIssue` | **19 / 42** (45.2%) | 21 total issue flags across 19 rejected frames (multi-issue overlap on 2 frames). |
| **Too Dark (`TOO_DARK`)** | `meanLuma < 45.0` OR `> 65%` pixels `< 28.0` | **12** | Caught all dark/underexposed low-light cases in the sweep (`lowLight=12`). |
| **Motion Blur (`BLURRY`)** | LapVar `< 95.0` (Nameplate) / `< 65.0` (Damage) | **4** | Higher nameplate threshold (`95.0`) protects micro-text OCR legibility. |
| **Poor Framing (`POOR_FRAMING`)** | `framingScore < 0.36` OR weak center / strong border | **4** | Flags edge-heavy / off-center frames where target slips outside 72% reticle. |
| **Overexposed (`OVEREXPOSED`)** | `meanLuma > 230.0` OR `> 45%` pixels `> 242.0` | **1** | Flags severe flash/specular washout without over-rejecting bright walls. |

### Threshold Calibration Rationale (`cv/ImageQualityAnalyzer.kt`)
- **320px Normalized Width (`NORMALIZED_MAX_WIDTH = 320`):** Eliminates megapixel dependency so Laplacian variance thresholds remain consistent between 720p glasses streams, 12 MP glasses captures, and Android rear-camera previews.
- **Split Blur Gate (`95.0` vs `65.0`):** Small stamped serial numbers on metal HVAC nameplates degrade rapidly under slight hand shake; smooth drywall water stains naturally have lower texture frequency, so lowering the damage gate to `65.0` avoids false retake prompts on plain wall surfaces.
- **Retake Guard & Explicit Override:** Failed frames never silently persist as clean captures—`FieldCaptureViewModel` presents `QualityGateAlertCard` with targeted retake advice and only saves a flagged capture if the technician explicitly selects **Keep Anyway (Flagged)**.

---

## 3. Per-Field Gemini Extraction Accuracy Methodology & Status

### Schema & Confidence Gate (`ai/GeminiExtractionService.kt`)
- Model: `gemini-3.8-flash` via **Firebase AI Logic (`Firebase.ai`)** + **Firebase App Check**.
- **Nameplate fields:** `equipmentType`, `manufacturer`, `modelNumber`, `serialNumber`, `manufactureDate`, `voltagePhase`, `amperageOrBtu`, `refrigerantType`.
- **Damage fields:** `room`, `surface`, `damageType`, `severity`, `estimatedAreaSqFt`, `moistureOrThermalRisk`, `recommendedAction`.
- **Note fields:** `summary`, `category`, `safetyHazard`, `followUpRequired`, `referencedComponents`.
- **Per-field verification rules:**
  - Every field emits `{ "value": String, "confidence": Float (0.0..1.0) }`.
  - Any field with `confidence < 0.80` or `"Unknown"` is marked `needsReview = true` in the UI (`Review` badge) and editable inline on **Job & Est**.
  - A bundle with zero extractable fields or `<= 0%` overall confidence throws `IllegalStateException` and records an explicit `extractionError` so the UI never displays `"Mean Confidence: 0%"` as a successful capture.

### Per-Field Measured Results Table

| Command Type | Field Name | Real Samples Evaluated | Exact-Match Accuracy (Normalized) | Mean Confidence | Verification Status |
| --- | --- | ---: | --- | --- | --- |
| `NAMEPLATE` | `manufacturer` | 0 *(2 synthetic unit-test assertions)* | **PENDING REAL FIELD DATA** | **PENDING** | JSON schema & evaluator verified in `ExampleUnitTest` |
| `NAMEPLATE` | `modelNumber` | 0 | **PENDING REAL FIELD DATA** | **PENDING** | JSON schema & evaluator verified in `ExampleUnitTest` |
| `NAMEPLATE` | `serialNumber` | 0 | **PENDING REAL FIELD DATA** | **PENDING** | JSON schema & evaluator verified in `ExampleUnitTest` |
| `NAMEPLATE` | `manufactureDate` | 0 | **PENDING REAL FIELD DATA** | **PENDING** | JSON schema & evaluator verified in `ExampleUnitTest` |
| `DAMAGE` | `room` | 0 | **PENDING REAL FIELD DATA** | **PENDING** | JSON schema & evaluator verified in `ExampleUnitTest` |
| `DAMAGE` | `surface` | 0 | **PENDING REAL FIELD DATA** | **PENDING** | JSON schema & evaluator verified in `ExampleUnitTest` |
| `DAMAGE` | `damageType` | 0 | **PENDING REAL FIELD DATA** | **PENDING** | JSON schema & evaluator verified in `ExampleUnitTest` |
| `DAMAGE` | `estimatedAreaSqFt` | 0 | **PENDING REAL FIELD DATA** | **PENDING** | JSON schema & evaluator verified in `ExampleUnitTest` |
| `NOTE` | `summary` / `category` / `safetyHazard` | 0 | **PENDING REAL FIELD DATA** | **PENDING** | JSON schema & evaluator verified in `ExampleUnitTest` |

*Evaluator Verification:* `EvalGroundTruthEvaluator.evaluate(...)` is unit-tested (`evalGroundTruthEvaluator_computesPerFieldAccuracyCorrectly`) using fixture ground-truth/prediction pairs (verifying punctuation normalization such as `24ACC-636A003 == 24ACC636A003`, `exactMatches / totalLabeled`, and `meanConfidence`).

---

## 4. Failure Analysis: Five Worst Failure Modes & Likely Causes

Based on the 42-frame CV benchmark, App Check telemetry diagnostics, and batch evaluation ranking (`BatchEvaluationEngine.buildSummary` -> `worstFailures`), the five highest-impact failure modes and their root causes are:

1. **Severe Low-Light Underexposure (`-84%` Luma / Unlit Crawlspaces — 12 CV rejections across `-72%` & `-84%` modes)**
   - **Symptom:** Frame fails `TOO_DARK` (`meanLuma < 45.0` or `> 65%` dark pixels), and gradient contrast collapses (`Laplacian variance ∝ scale²`).
   - **Likely Cause:** Physical glasses have no integrated LED flash/torch; unlit basements/mechanical closets drop nameplate stamped aluminum text into sensor read noise.
   - **Mitigation:** Pre-capture CV gate blocks wasted Gemini calls and prompts technician to turn on room light or work headlamp.

2. **Off-Center Subject Outside Reticle (`POOR_FRAMING` — 4 CV rejections)**
   - **Symptom:** Target equipment or panel sits near frame edge (`framingScore < 0.36` or weak center edge density vs. strong outer border edges).
   - **Likely Cause:** Ray-Ban Meta glasses are rigid head-mounted cameras without an optical eyepiece reticle; technicians turning their head early clip half of the nameplate outside the center `72%` ROI.
   - **Mitigation:** Pre-capture reticle feedback instructs technician to center the target; high-contrast edge-of-frame cases where framing also drops texture contrast require manual crop inspection.

3. **Motion Blur vs. Smooth-Surface Threshold Boundary (`BLURRY` — 4 CV rejections across 6 blur simulations)**
   - **Symptom:** High-frequency nameplate typography drops sharply below `95.0` under blur, whereas lower-frequency scenes near `65.0` (`DAMAGE` threshold) can straddle the pass/fail boundary unless blur severity is pronounced.
   - **Likely Cause:** A single global Laplacian cutoff across heterogeneous textures trades false positives on smooth painted drywall for false negatives on mildly shaken damage photos.
   - **Mitigation:** Split `BLUR_VARIANCE_NAMEPLATE (95.0)` from `BLUR_VARIANCE_GENERAL (65.0)` and retain `Keep Anyway (Flagged)` override; real walkthrough ROC curves are still needed to refine `65.0`.

4. **Glare Washout on Specular Metal / Bright Surfaces (`OVEREXPOSED` — 1 CV rejection out of 3 `+55%` tests)**
   - **Symptom:** Clipped white specular highlights (`> 242` luma) obliterate stamped serial digits on brushed aluminum/galvanized HVAC housings.
   - **Likely Cause:** Direct afternoon sunlight or overhead work lamps hitting reflective metal plates locally saturate sensor pixels even when whole-frame `meanLuma` stays near borderline levels.
   - **Mitigation:** Combine whole-frame `LUMA_MAX_MEAN = 230.0` with `CLIPPED_PIXEL_MAX_RATIO = 0.45`; recommend angling head 15° off specular axis.

5. **Firebase App Check Invalid / Dummy Token on Unregistered Debug Launches (`aiFailureCount` when Live AI Enabled)**
   - **Symptom:** Previously threw `"Firebase App Check token is invalid."` on `gemini-3.8-flash` requests and displayed `"Mean Confidence: 0%"` on saved records.
   - **Likely Cause:** When launched locally outside AI Studio without registering the debug UUID in Firebase Console (or due to an async debug secret race condition), Firebase App Check returns the SDK's dummy fallback token (`eyJlcnJvciI6IlVOS05PV05fRVJST1IifQ==`).
   - **Mitigation:** `AppCheckInit.kt` now commits debug tokens synchronously before provider installation and clears stale cached tokens; `AppCheckCoordinator` detects dummy tokens pre-flight with actionable setup guidance; `FieldCaptureRepository` preserves the local Room photo (`CaptureEvidenceEntity`) and enables one-tap **Retry AI Extraction (Keep Saved Photo)** once the token is registered.

---

## 5. Next Steps to Complete Live Walkthrough Evaluation
1. Launch debug APK on an Android device/emulator with verified App Check debug token.
2. Collect `>= 40` real smartphone/glasses inspection photos (`>= 10` HVAC/electrical nameplates, `>= 10` dim/low-light areas).
3. Populate `eval_dataset/ground_truth_template.json` with human-verified ground truth.
4. Import the real photos via **Eval & Cost -> Import Photos** with **Include Live Gemini 3.8 Flash AI** enabled, export the JSON report, and run `EvalGroundTruthEvaluator.evaluate(...)` to fill Section 3 with verified numbers.
