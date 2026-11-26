# FieldCapture — Demo Script (<= 5 Minutes)

**Total Runtime:** ~4 minutes 30 seconds  
**Environment:** Android Emulator (with Mock Device Kit + Firebase AI Logic) or Android Device  
**Honesty Rule:** Explicitly state during the demo that Mock Device Kit sample scenes (and optional Android rear camera bridge) are used in place of physical Ray-Ban Meta glasses.

---

## 0:00 – 0:45 | Problem Statement & Architecture Overview
- **Show:** Launch **FieldCapture** on the **Capture** tab.
- **Point Out:**
  - Active claim header (`CLM-PROP-88412`, `1420 Oakridge Ave`) and session status pill (`OFFLINE`).
  - Four-tab architecture: **Capture**, **Job & Est**, **Sync Queue**, **Eval & Cost**.
  - Mention that the app isolates Meta Wearables DAT `1.0.1` behind `WearableSessionGateway` and runs on-device CV quality gates + Room + WorkManager + Firebase AI Logic (`gemini-3.8-flash` authenticated via Firebase App Check, zero embedded Gemini API key).

## 0:45 – 1:45 | End-to-End Flow #1: Pair -> Stream -> Voice Command `capture nameplate`
1. Tap **Pair Mock Glasses** -> status updates to `PAIRED`, viewfinder shows `MOCK SAMPLE` provenance badge (`HVAC Nameplate`).
2. Tap **Start** -> `DeviceSession` reaches `STARTED`, camera stream opens (`STREAMING`), live CV telemetry strip updates (`BLUR`, `LUMA`, `FRAME`).
3. Trigger **`capture nameplate`** (via mic button or Quick-Command button):
   - Show on-device CV gate passing.
   - Show Gemini 3.8 Flash extraction progress overlay.
4. Switch to **Job & Est** tab:
   - Point out capture count incremented (`1 captures`), provenance chip (`Mock Device Kit Sample Scene`), extracted nameplate fields (`manufacturer`, `modelNumber`, `serialNumber`, `manufactureDate`, `voltagePhase`, `refrigerantType`) with per-field confidence badges, and auto-generated HVAC estimate line items (`HVC-CMP-10`, `HVC-COI-11`).
   - Tap a field row (e.g. `manufactureDate`) to demonstrate human-in-the-loop override (`EDITED 100%`) which re-queues sync.

## 1:45 – 2:35 | End-to-End Flow #2: `capture damage [room] [surface]` & `note: [free text]` + Quality Retake Gate
1. Return to **Capture** tab and select **Basement Drywall Damage** scene.
2. Demonstrate argument validation by typing `capture damage` -> show `MissingArguments` prompt (`Specify room and surface...`).
3. Tap **`capture damage Basement Drywall`** -> show damage extraction (`room`, `surface`, `damageType`, `severity`, `estimatedAreaSqFt`) and drywall/water mitigation estimate codes (`WTR-DRY-01`, `DRY-RPL-02`).
4. Submit **`note: Main breaker lug shows scorch marks; shutoff tagged`** -> show structured note fields (`summary`, `category`, `safetyHazard`, `followUpRequired`) and safety line item (`GEN-SAF-14`).
5. Tap **capture nameplate** again without switching scene -> demonstrate **duplicate capture prevention** blocking identical accidental re-captures.

## 2:35 – 3:35 | Offline-First Queue, HTTP 503 Recovery & Reconnect Resilience
1. Tap **Sim Drop** on the **Capture** tab -> show `RECONNECTING` backoff recovery preserving the saved Room job & captures.
2. Open **Sync Queue** tab -> show `UPLOAD_MEDIA` and `SYNC_JOB` operations queued in Room with persistent `X-Idempotency-Key` headers.
3. Tap **Sim 503**, then **Sync Now** -> show `HTTP 503` failure recorded with `attemptCount = 1`, zero lost local evidence, and idempotency key preserved.
4. Tap **Sync Now** again (with connectivity restored / loopback healthy) -> show `HTTP 200 OK` and `SYNCED` state across captures and outbox.

## 3:35 – 4:30 | 42-Frame Batch Evaluation & Failure Analysis
1. Open **Eval & Cost** tab.
2. Point out **Import Photos** (zero-permission Android Photo Picker) and toggle **Include Live Gemini 3.8 Flash AI** (explain CV-only <2s benchmark vs live Gemini batch).
3. Tap **Run 40-Frame Eval**:
   - Show dataset composition (`42` frames: `14` nameplates, `12` low-light perturbations, clearly separated `Real` vs `Synthetic`).
   - Show CV rejection breakdown (`Blur`, `Dark`, `Glare`, `Framing`), `P50`/`P95` latencies, token cost telemetry, **Top 5 Failure Cases**, and **Report (JSON)** export dialog.
