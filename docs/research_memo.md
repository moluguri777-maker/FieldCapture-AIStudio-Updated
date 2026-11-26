# Research Memo: Meta AI Glasses & Wearables Device Access Toolkit (DAT 1.0) Integration

**Project:** FieldCapture — Hands-Free Field Capture on AI Glasses  
**SDK Version Used in Codebase:** `com.meta.wearable:mwdat-core:1.0.1`, `mwdat-camera:1.0.1`, `mwdat-mockdevice:1.0.1`  
**Verification Scope Notice:** Only Mock Device Kit simulation paths were executed in the Android emulator / local environment. No physical Ray-Ban Meta glasses hardware was attached or tested.

---

## 1. Executive Summary

FieldCapture targets **Ray-Ban Meta AI Glasses** through the official **Meta Wearables Device Access Toolkit (DAT 1.0.1)** (`Wearables`, `AutoDeviceSelector`, `DeviceSession`, `Camera`, `Stream`). Because physical AI glasses require Bluetooth + Wi-Fi P2P pairing through the Meta AI (formerly Meta View) Android companion application—unavailable inside a headless Android emulator—the integration isolates all DAT calls behind `WearableSessionGateway` (`DatWearableSessionGateway`) so **Mock Device Kit (`mwdat-mockdevice`)** exercises the identical lifecycle state machine during development.

---

## 2. Physical Hardware & SDK Architecture Requirements

### 2.1 Official Registration & Manifest Configuration
Physical glasses do not allow arbitrary third-party apps to open raw Bluetooth camera sockets. Production deployment requires:
1. Enrolling the organization and app bundle ID (`com.aistudio.fieldcapture.krmxvp`) in the [Meta Wearables Developer Portal](https://wearables.developer.meta.com/).
2. Generating a real `APPLICATION_ID` (`com.meta.wearable.mwdat.APPLICATION_ID`) and `CLIENT_TOKEN` (`com.meta.wearable.mwdat.CLIENT_TOKEN`), currently populated in `app/build.gradle.kts` with mock placeholders (`fieldcapture-mock-app` / `fieldcapture-mock-token`).
3. Having the **Meta AI companion app** installed, signed in, and paired with the physical glasses on the host Android phone. During first launch, `Wearables.registrationState` transitions through companion app authorization before `Wearables.createSession(...)` succeeds.

### 2.2 Permissions & OS Requirements
- **`BLUETOOTH_CONNECT`** (runtime on Android 12+ / API 31+): Required to discover and maintain the BLE control link to the glasses. *(Note: FieldCapture currently declares `BLUETOOTH_CONNECT` in `AndroidManifest.xml`; runtime prompt flow must be added before distributing to physical Android 12+ devices).*
- **`RECORD_AUDIO`**: Required for Android `SpeechRecognizer` / hands-free voice capture (`capture nameplate`, `capture damage [room] [surface]`, `note: [text]`).
- **`INTERNET` & `ACCESS_NETWORK_STATE`**: Required for Firebase App Check token exchange, Firebase AI Logic (`gemini-3.8-flash`), and WorkManager background upload sync.
- **`CAMERA`**: Only required in FieldCapture when routing the Android phone's own rear lens into Mock Device Kit (`CameraFacing.BACK`); physical glasses frames arrive over DAT rather than `android.hardware.camera2`.

### 2.3 Session Lifecycle & Camera Access Rules (Verified in `mwdat` 1.0.1 API)
1. **Single-Initialization Gate**: `Wearables.initialize(context)` / `MockDeviceKit.enable(...)` must execute once per process.
2. **Lifecycle Ordering**:
   - Pair & verify `Wearables.registrationState`.
   - Call `Wearables.createSession(AutoDeviceSelector())`.
   - Call `session.start()` and wait for `session.state == DeviceSessionState.STARTED` (FieldCapture checks this with an 8-second timeout before attaching a camera).
   - Call `session.addCamera(StreamConfiguration())` **only after** `STARTED` is reached.
   - Start preview via `camera.stream.start()`, collect `stream.videoStream`, and trigger high-resolution still capture via `stream.capturePhoto()` (`PhotoData.Bitmap` or `PhotoData.HEIC`).
3. **Hinge / Wear / Power Events**: Folding the glasses hinges (`fold()`), powering off, or removing the glasses can suspend/stop `DeviceSessionState` (`STOPPED`). FieldCapture monitors `session.state`, `session.errors`, and `stream.state` to enter `RECONNECTING` with exponential backoff (`1.2s`, `2.0s`, `3.5s`) while preserving the active `InspectionJobEntity` and Room outbox.

### 2.4 Real-World Hardware Constraints & Operational Limits
- **Streaming Resolution Ceiling**: While Ray-Ban Meta hardware captures 12 MP still photos natively, DAT 1.0 SDK live video streaming is bandwidth-capped over wireless bridge (up to 720p @ 30fps depending on `VideoQuality` and RF link conditions). Using still capture (`stream.capturePhoto()`) rather than low-res video frames is critical for reading small serial numbers on HVAC nameplates.
- **Radio & Thermal Budget**: Sustained live video streaming drains the frames' compact battery (typical mixed-use ~4 hours; continuous streaming significantly shorter) and triggers thermal throttling in hot attics/rooftops. FieldCapture uses **brief on-demand sessions** plus **pause/resume** rather than always-on recording.
- **No USB Tethering**: Glasses charge via the pogo-pin case/temple interface; all data flows wirelessly via BLE + Wi-Fi P2P through the companion app.
- **Bluetooth HFP/SCO Audio Contention**: Routing microphone input over Bluetooth Hands-Free Profile lowers audio bandwidth (~8–16 kHz) and introduces ~200–500 ms latency compared to the phone's built-in wideband mic. Our `VoiceCommandParser` strips ASR trailing punctuation and validates missing arguments so partial/noisy transcripts fail safely.

---

## 3. Verification Matrix: Mock Device Kit vs. Physical Hardware

| Capability | Status | Exact Verification Basis |
| :--- | :--- | :--- |
| `Wearables` & `MockDeviceKit` SDK compilation & linking (`1.0.1`) | **Implemented & Compiles** | Verified via Gradle debug build (`compile_applet`). |
| Pairing `GlassesModel.RAYBAN_META` in `MockDeviceKit` | **Implemented (Mock)** | `DatWearableSessionGateway.pairMockGlassesInternal()`. |
| `DeviceSession.start()` -> `STARTED` -> `addCamera()` -> `Stream.start()` | **Implemented (Mock)** | `DatWearableSessionGateway.startSession()`. |
| Simulated hinge fold / power loss & automatic reconnect recovery | **Implemented (Mock)** | `simulateUnexpectedDisconnect()`, `setMockFolded()`, `setMockPower()`. |
| Mock photo capture (`stream.capturePhoto()`) + provenance tagging | **Implemented (Mock)** | Explicitly tagged `MOCK_DEVICE_KIT_SCENE` vs `ANDROID_REAR_CAMERA_MOCK` so bundled samples are never confused with physical glasses photos. |
| Physical Bluetooth + Wi-Fi P2P pairing via Meta AI companion app | **NOT VERIFIED** | Requires physical Ray-Ban Meta glasses + Meta Developer Portal credentials (`APPLICATION_ID` / `CLIENT_TOKEN`). |
| Real 12 MP HEIC capture & outdoor RF interference recovery | **NOT VERIFIED** | Cannot be exercised without physical Meta hardware. |
| Bluetooth glasses mic HFP ASR accuracy in noisy HVAC environments | **NOT VERIFIED** | Emulator/JVM tests validate parser rules only, not physical HFP acoustic WER. |

---

## 4. Primary Reference Sources

- Meta Wearables Developer Portal (Official DAT Documentation & App Registration): https://wearables.developer.meta.com/
- Meta Wearables Device Access Toolkit for Android (Official GitHub Repository & Samples): https://github.com/meta/meta-wearables-dat-android
- Meta Wearables Documentation (Device Session Lifecycle, Camera Streaming, Mock Device Kit): https://wearables.developer.meta.com/docs/
- Android Runtime Bluetooth Permissions (`BLUETOOTH_CONNECT`): https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Firebase AI Logic & App Check for Android: https://firebase.google.com/docs/ai-logic/get-started?platform=android
