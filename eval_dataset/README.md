# FieldCapture Evaluation Dataset Guide

## Overview
The evaluation harness in `BatchEvaluationEngine` requires **at least 40 frames**, including **>= 10 nameplates** and **>= 10 low-light frames**.

To prevent any confusion between synthetic test perturbations and genuine field data, every frame is tagged with an explicit `sourceKind`:
- **Real frames (`PhotoPicker` / `SessionCapture`)**: Genuine walkthrough photos imported via the zero-permission Android Photo Picker or captured during live sessions.
- **Synthetic test frames (`SyntheticPerturbation`)**: Controlled geometric/photometric perturbations derived from the 3 bundled sample drawables (`img_sample_nameplate_1795506161252.jpg`, `img_sample_damage_drywall_1795506162478.jpg`, `img_sample_electrical_panel_1795506162369.jpg`) used exclusively to validate CV thresholds and pipeline latency when real photos are not yet imported.

## Default 42-Frame Synthetic Pool Composition (Before User Photo Import)
When 0 real photos are imported, `BatchEvaluationEngine.collectEvaluationFrames` constructs 42 frames (`variationIdx = 0..41`) across 3 scenes and 7 variation modes:
- **14 Nameplate frames** (`variationIdx % 3 == 0`)
- **14 Drywall Water Damage frames** (`variationIdx % 3 == 1`)
- **14 Electrical Breaker Panel frames** (`variationIdx % 3 == 2`)
- **12 Low-Light frames** (Mode 2: `-72% Luma` x 6; Mode 4: `-84% Luma` x 6)
- **6 Motion-Blur frames** (Mode 3: 1/12x bilinear downsample + upscale)
- **6 Off-Center Framing frames** (Mode 5: shifted onto dark background margin)
- **3 Bright Glare frames** (Mode 6 even cycles: `+55%` brightness)
- **3 Tight/Angle Crops & 6 Nominal + 6 Tight ROI frames**

## Collecting & Scoring Real Field Walkthrough Data
1. Capture or select >= 40 real field photos (including >= 10 HVAC/electrical nameplates and >= 10 dim basement/crawlspace low-light shots).
2. Fill out `ground_truth_template.json` with verified `expectedFields` for each image.
3. Import the real photos in **Eval & Cost -> Import Photos** (Android Photo Picker) with **Include Live Gemini 3.8 Flash AI** enabled, run the batch, and export the JSON report.
4. Pass ground-truth JSON and exported predictions into `EvalGroundTruthEvaluator.evaluate(...)` to compute exact per-field accuracy and calibration metrics.
