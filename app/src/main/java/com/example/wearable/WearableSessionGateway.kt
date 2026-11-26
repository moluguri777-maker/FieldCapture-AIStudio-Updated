package com.example.wearable

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.R
import com.example.domain.model.EvidenceSource
import com.example.domain.model.MockHardwareSnapshot
import com.example.domain.model.SessionLifecycleState
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

object WearablePermissions {
    fun requiredBluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    fun hasBluetoothConnectPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }
}

enum class MockFeedScene(val label: String, val drawableRes: Int) {
    NAMEPLATE("HVAC Nameplate", R.drawable.img_sample_nameplate_1795506161252),
    DAMAGE_DRYWALL("Basement Drywall Damage", R.drawable.img_sample_damage_drywall_1795506162478),
    ELECTRICAL_PANEL("Electrical Breaker Panel", R.drawable.img_sample_electrical_panel_1795506162369)
}

data class WearableGatewayState(
    val lifecycleState: SessionLifecycleState = SessionLifecycleState.DISCONNECTED,
    val hardware: MockHardwareSnapshot = MockHardwareSnapshot(),
    val previewFrame: Bitmap? = null,
    val disconnectsRecovered: Int = 0,
    val reconnectAttempt: Int = 0,
    val sdkInitialized: Boolean = false,
    val requiresBluetoothPermission: Boolean = false,
    val statusDetail: String = "Tap 'Pair Mock Glasses' to initialize Meta DAT 1.0 + Mock Device Kit",
    val lastError: String? = null
)

data class CapturedFrame(
    val bitmap: Bitmap,
    val source: EvidenceSource
)

interface WearableSessionGateway {
    val state: StateFlow<WearableGatewayState>
    fun initializeSdk()
    fun onBluetoothPermissionUpdated(granted: Boolean)
    fun pairMockGlasses()
    fun unpairMockGlasses()
    fun setMockPower(on: Boolean)
    fun setMockFolded(folded: Boolean)
    fun setMockDonned(donned: Boolean)
    fun selectMockScene(scene: MockFeedScene)
    fun toggleDeviceCameraFeed(enabled: Boolean)
    fun startSession()
    fun pauseSession()
    fun resumeSession()
    fun stopSession()
    fun simulateUnexpectedDisconnect()
    suspend fun ensureMockGlassesPaired(): Boolean
    suspend fun capturePhotoFrame(): Result<CapturedFrame>
    fun release()
}

private const val TAG = "DatWearableGateway"

/**
 * Isolates Meta Wearables DAT 1.0.1 SDK (Wearables, DeviceSession, Camera, Stream)
 * and MockDeviceKit behind a clean lifecycle and disconnect-recovery state machine.
 */
class DatWearableSessionGateway internal constructor(
    private val appContext: Context?,
    private val permissionChecker: () -> Boolean,
    private val sdkBootstrap: (Context?) -> Unit,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : WearableSessionGateway {

    constructor(context: Context) : this(
        appContext = context.applicationContext,
        permissionChecker = { WearablePermissions.hasBluetoothConnectPermission(context.applicationContext) },
        sdkBootstrap = { ctx ->
            val targetCtx = requireNotNull(ctx) { "Application context required for DAT SDK" }
            val kit = MockDeviceKit.getInstance(targetCtx)
            // MockDeviceKit.enable internally calls Wearables.initialize(targetCtx) once
            if (!kit.isEnabled) {
                kit.enable(
                    MockDeviceKitConfig(
                        initiallyRegistered = true,
                        initialPermissionsGranted = true
                    )
                )
            }
        }
    )

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val sdkInitMutex = Mutex()
    @Volatile
    private var instanceSdkInitialized = false

    private val _state = MutableStateFlow(WearableGatewayState())
    override val state: StateFlow<WearableGatewayState> = _state.asStateFlow()

    private var registrationCollectStarted = false
    private var mockDeviceKit: MockDeviceKitInterface? = null
    private var mockGlasses: MockGlasses? = null
    private var activeSession: DeviceSession? = null
    private var activeCamera: Camera? = null
    private var activeStream: Stream? = null

    private var frameJob: Job? = null
    private var sessionStartJob: Job? = null
    private var sessionObserveJob: Job? = null
    private var reconnectJob: Job? = null

    private var currentScene: MockFeedScene = MockFeedScene.NAMEPLATE
    private var shouldBeStreaming: Boolean = false
    private val cachedSceneFiles = mutableMapOf<MockFeedScene, Uri>()
    private val cachedSceneVideoFiles = mutableMapOf<MockFeedScene, Uri>()

    companion object {
        private val initLock = Any()
        @Volatile
        private var processSdkInitialized = false
    }

    override fun initializeSdk() {
        if (!permissionChecker()) {
            markBluetoothPermissionRequired()
            return
        }
        scope.launch {
            initializeSdkInternal()
        }
    }

    override fun onBluetoothPermissionUpdated(granted: Boolean) {
        if (granted && permissionChecker()) {
            _state.value = _state.value.copy(
                requiresBluetoothPermission = false,
                lastError = null
            )
            initializeSdk()
        } else {
            markBluetoothPermissionRequired()
        }
    }

    private fun markBluetoothPermissionRequired() {
        _state.value = _state.value.copy(
            requiresBluetoothPermission = true,
            sdkInitialized = false,
            lifecycleState = if (_state.value.lifecycleState == SessionLifecycleState.STREAMING) {
                _state.value.lifecycleState
            } else {
                SessionLifecycleState.DISCONNECTED
            },
            statusDetail = "Bluetooth (Nearby devices) permission required before initializing Meta DAT 1.0",
            lastError = "BLUETOOTH_CONNECT permission not granted — tap Grant Permission to enable Mock Device Kit."
        )
    }

    internal suspend fun initializeSdkInternal(): Boolean = sdkInitMutex.withLock {
        if (!permissionChecker()) {
            markBluetoothPermissionRequired()
            return@withLock false
        }
        val alreadyReady = if (appContext != null) processSdkInitialized else instanceSdkInitialized
        if (alreadyReady) {
            _state.value = _state.value.copy(
                requiresBluetoothPermission = false,
                sdkInitialized = true
            )
            startRegistrationObserverIfNeeded()
            return@withLock true
        }

        return@withLock try {
            withContext(ioDispatcher) {
                val targetCtx = appContext
                if (targetCtx != null) {
                    synchronized(initLock) {
                        if (!processSdkInitialized) {
                            sdkBootstrap(targetCtx)
                            processSdkInitialized = true
                        }
                    }
                    if (mockDeviceKit == null) {
                        mockDeviceKit = runCatching { MockDeviceKit.getInstance(targetCtx) }.getOrNull()
                    }
                } else {
                    sdkBootstrap(null)
                }
            }
            instanceSdkInitialized = true
            _state.value = _state.value.copy(
                requiresBluetoothPermission = false,
                sdkInitialized = true,
                lifecycleState = if (_state.value.lifecycleState == SessionLifecycleState.ERROR) {
                    SessionLifecycleState.DISCONNECTED
                } else {
                    _state.value.lifecycleState
                },
                statusDetail = if (_state.value.hardware.paired) {
                    _state.value.statusDetail
                } else {
                    "Meta DAT 1.0 SDK ready — tap 'Pair Mock Glasses' to begin"
                },
                lastError = null
            )
            startRegistrationObserverIfNeeded()
            true
        } catch (se: SecurityException) {
            runCatching { Log.e(TAG, "Bluetooth permission denied while initializing DAT SDK", se) }
            _state.value = _state.value.copy(
                requiresBluetoothPermission = true,
                sdkInitialized = false,
                lifecycleState = SessionLifecycleState.ERROR,
                statusDetail = "Bluetooth permission required for Meta DAT 1.0",
                lastError = se.localizedMessage ?: "Need android.permission.BLUETOOTH_CONNECT permission"
            )
            false
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "DAT SDK initialization failed", t) }
            _state.value = _state.value.copy(
                requiresBluetoothPermission = false,
                sdkInitialized = false,
                lifecycleState = SessionLifecycleState.ERROR,
                statusDetail = "Meta DAT SDK initialization failed",
                lastError = t.localizedMessage ?: t.javaClass.simpleName
            )
            false
        }
    }

    private fun startRegistrationObserverIfNeeded() {
        if (appContext == null || registrationCollectStarted) return
        registrationCollectStarted = true
        scope.launch {
            runCatching {
                Wearables.registrationState.collect { reg ->
                    _state.value = _state.value.copy(
                        hardware = _state.value.hardware.copy(registrationState = reg.name)
                    )
                }
            }.onFailure { err ->
                Log.w(TAG, "Unable to observe Wearables.registrationState", err)
            }
        }
    }

    override fun pairMockGlasses() {
        if (!permissionChecker()) {
            markBluetoothPermissionRequired()
            return
        }
        scope.launch {
            pairMockGlassesInternal()
        }
    }

    override suspend fun ensureMockGlassesPaired(): Boolean {
        if (_state.value.hardware.paired) return true
        if (!permissionChecker()) {
            markBluetoothPermissionRequired()
            return false
        }
        return pairMockGlassesInternal()
    }

    private suspend fun pairMockGlassesInternal(): Boolean {
        if (!initializeSdkInternal()) {
            return false
        }
        val targetCtx = appContext ?: return false
        val kit = withContext(Dispatchers.IO) {
            mockDeviceKit ?: MockDeviceKit.getInstance(targetCtx).also { mockDeviceKit = it }
        }

        val pairResult = try {
            withContext(Dispatchers.IO) {
                kit.pairGlasses(GlassesModel.RAYBAN_META)
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied during pairMockGlasses", se)
            markBluetoothPermissionRequired()
            return false
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected error pairing mock glasses", t)
            _state.value = _state.value.copy(
                lifecycleState = SessionLifecycleState.ERROR,
                lastError = "Mock pairing failed: ${t.localizedMessage ?: t.javaClass.simpleName}"
            )
            return false
        }

        var pairedOk = false
        pairResult.onSuccess { glasses ->
            mockGlasses = glasses
            withContext(Dispatchers.IO) {
                glasses.powerOn()
                glasses.unfold()
                glasses.don()
                glasses.setBatteryLevel(91)
            }

            val sceneUri = ensureSceneFileUri(currentScene)
            val videoUri = ensureSceneVideoUri(currentScene)
            withContext(Dispatchers.IO) {
                runCatching {
                    if (sceneUri != null) {
                        glasses.services.camera.setCapturedImage(sceneUri)
                        glasses.services.cameraCapture.setCapturedPhoto(sceneUri)
                    }
                    if (videoUri != null) {
                        glasses.services.camera.setCameraFeed(videoUri)
                    }
                }
            }

            val preview = loadSceneBitmap(currentScene)
            _state.value = _state.value.copy(
                lifecycleState = SessionLifecycleState.PAIRED_IDLE,
                sdkInitialized = true,
                requiresBluetoothPermission = false,
                hardware = MockHardwareSnapshot(
                    mockEnabled = true,
                    paired = true,
                    deviceName = GlassesModel.RAYBAN_META.displayName,
                    poweredOn = true,
                    unfolded = true,
                    donned = true,
                    batteryPercent = 91,
                    cameraSourceLabel = currentScene.label,
                    usingDeviceCamera = false,
                    registrationState = Wearables.registrationState.value.name,
                    streamStateLabel = "IDLE"
                ),
                previewFrame = preview,
                statusDetail = "Ray-Ban Meta (Mock) paired & ready. Press Start to stream.",
                lastError = null
            )
            pairedOk = true
        }.onFailure { err, throwable ->
            Log.e(TAG, "Mock pairing failed: ${err.description}", throwable)
            _state.value = _state.value.copy(
                lifecycleState = SessionLifecycleState.ERROR,
                lastError = "Mock pairing failed: ${err.description}"
            )
        }
        return pairedOk
    }

    override fun unpairMockGlasses() {
        stopSession()
        val glasses = mockGlasses
        if (glasses != null) {
            runCatching { mockDeviceKit?.unpairDevice(glasses) }
        }
        mockGlasses = null
        _state.value = WearableGatewayState(
            lifecycleState = SessionLifecycleState.DISCONNECTED,
            statusDetail = "Mock glasses unpaired"
        )
    }

    override fun setMockPower(on: Boolean) {
        val glasses = mockGlasses ?: return
        if (on) glasses.powerOn() else glasses.powerOff()
        _state.value = _state.value.copy(
            hardware = _state.value.hardware.copy(poweredOn = on)
        )
        if (!on && _state.value.lifecycleState == SessionLifecycleState.STREAMING) {
            handleUnexpectedDrop("Glasses powered off")
        } else if (on && _state.value.lifecycleState == SessionLifecycleState.RECONNECTING) {
            attemptRecoveryReconnect()
        }
    }

    override fun setMockFolded(folded: Boolean) {
        val glasses = mockGlasses ?: return
        if (folded) glasses.fold() else glasses.unfold()
        _state.value = _state.value.copy(
            hardware = _state.value.hardware.copy(unfolded = !folded)
        )
        if (folded && _state.value.lifecycleState == SessionLifecycleState.STREAMING) {
            handleUnexpectedDrop("Hinges folded — session suspended")
        } else if (!folded && _state.value.lifecycleState == SessionLifecycleState.RECONNECTING) {
            attemptRecoveryReconnect()
        }
    }

    override fun setMockDonned(donned: Boolean) {
        val glasses = mockGlasses ?: return
        if (donned) glasses.don() else glasses.doff()
        _state.value = _state.value.copy(
            hardware = _state.value.hardware.copy(donned = donned),
            statusDetail = if (donned) "Glasses worn (donned)" else "Glasses removed (doffed)"
        )
    }

    override fun selectMockScene(scene: MockFeedScene) {
        currentScene = scene
        scope.launch {
            val uri = ensureSceneFileUri(scene)
            val videoUri = ensureSceneVideoUri(scene)
            mockGlasses?.let { glasses ->
                runCatching {
                    if (uri != null) {
                        glasses.services.camera.setCapturedImage(uri)
                        glasses.services.cameraCapture.setCapturedPhoto(uri)
                    }
                    if (videoUri != null) {
                        glasses.services.camera.setCameraFeed(videoUri)
                    }
                }
            }
            val bmp = loadSceneBitmap(scene)
            _state.value = _state.value.copy(
                previewFrame = bmp,
                hardware = _state.value.hardware.copy(
                    cameraSourceLabel = scene.label,
                    usingDeviceCamera = false
                ),
                statusDetail = "Mock feed switched to ${scene.label}"
            )
        }
    }

    override fun toggleDeviceCameraFeed(enabled: Boolean) {
        val glasses = mockGlasses ?: return
        if (enabled) {
            runCatching {
                glasses.services.camera.setCameraFeed(CameraFacing.BACK)
            }.onFailure {
                _state.value = _state.value.copy(
                    lastError = "Unable to bind device camera: ${it.localizedMessage}"
                )
                return
            }
            _state.value = _state.value.copy(
                previewFrame = null,
                hardware = _state.value.hardware.copy(
                    cameraSourceLabel = "Device Back Camera (DAT Mock)",
                    usingDeviceCamera = true
                ),
                statusDetail = "Mock camera routed from Android rear lens"
            )
        } else {
            selectMockScene(currentScene)
        }
    }

    override fun startSession() {
        if (!permissionChecker()) {
            markBluetoothPermissionRequired()
            return
        }
        shouldBeStreaming = true
        sessionStartJob?.cancel()
        reconnectJob?.cancel()
        teardownActiveStreamOnly()

        _state.value = _state.value.copy(
            lifecycleState = SessionLifecycleState.STARTING,
            statusDetail = "Starting DAT 1.0 DeviceSession…",
            lastError = null
        )

        sessionStartJob = scope.launch {
            if (!initializeSdkInternal()) {
                return@launch
            }
            if (!_state.value.hardware.paired) {
                val paired = pairMockGlassesInternal()
                if (!paired) return@launch
            }
            val hw = _state.value.hardware
            if (!hw.poweredOn || !hw.unfolded) {
                _state.value = _state.value.copy(
                    lifecycleState = SessionLifecycleState.PAIRED_IDLE,
                    lastError = "Power on and unfold glasses before starting stream."
                )
                return@launch
            }

            val sessionResult = try {
                withContext(Dispatchers.IO) {
                    Wearables.createSession(AutoDeviceSelector())
                }
            } catch (se: SecurityException) {
                Log.e(TAG, "Bluetooth permission missing during createSession", se)
                markBluetoothPermissionRequired()
                return@launch
            }
            sessionResult.onSuccess { session ->
                activeSession = session
                session.start()

                // DeviceSession.addCamera requires DeviceSessionState.STARTED
                val reachedState = withTimeoutOrNull(8_000L) {
                    session.state.first { st ->
                        st == DeviceSessionState.STARTED ||
                            st == DeviceSessionState.STOPPED ||
                            st == DeviceSessionState.STOPPING
                    }
                }

                if (reachedState != DeviceSessionState.STARTED) {
                    Log.w(TAG, "DeviceSession did not reach STARTED (state=${session.state.value})")
                    teardownActiveStreamOnly()
                    _state.value = _state.value.copy(
                        lifecycleState = SessionLifecycleState.ERROR,
                        lastError = "Session failed to start (state=${session.state.value}). Ensure mock glasses are paired and unfolded."
                    )
                    return@launch
                }

                observeSession(session)

                var hasCameraFeed = _state.value.hardware.usingDeviceCamera
                if (!hasCameraFeed) {
                    val videoUri = ensureSceneVideoUri(currentScene)
                    if (videoUri != null) {
                        runCatching {
                            mockGlasses?.services?.camera?.setCameraFeed(videoUri)
                            hasCameraFeed = true
                        }
                    }
                }

                val cameraResult = session.addCamera(StreamConfiguration())
                cameraResult.onSuccess { camera ->
                    activeCamera = camera
                    val stream = camera.stream
                    activeStream = stream

                    if (!hasCameraFeed) {
                        val preview = _state.value.previewFrame ?: loadSceneBitmap(currentScene)
                        _state.value = _state.value.copy(
                            lifecycleState = SessionLifecycleState.STREAMING,
                            previewFrame = preview,
                            hardware = _state.value.hardware.copy(streamStateLabel = "MOCK_PHOTO_READY"),
                            statusDetail = "DAT session active (mock photo capture ready)"
                        )
                        return@launch
                    }

                    val streamStartResult = stream.start()
                    streamStartResult.onSuccess {
                        collectStream(stream)
                        val preview = _state.value.previewFrame ?: loadSceneBitmap(currentScene)
                        _state.value = _state.value.copy(
                            lifecycleState = SessionLifecycleState.STREAMING,
                            previewFrame = preview,
                            hardware = _state.value.hardware.copy(streamStateLabel = "STREAMING"),
                            statusDetail = "DAT 1.0 camera stream active"
                        )
                    }.onFailure { streamErr, throwable ->
                        Log.w(TAG, "Stream.start fallback notice: ${streamErr.description}", throwable)
                        val preview = _state.value.previewFrame ?: loadSceneBitmap(currentScene)
                        _state.value = _state.value.copy(
                            lifecycleState = SessionLifecycleState.STREAMING,
                            previewFrame = preview,
                            hardware = _state.value.hardware.copy(streamStateLabel = "MOCK_PHOTO_READY"),
                            statusDetail = "DAT session active (mock photo capture ready)"
                        )
                    }
                }.onFailure { camErr, throwable ->
                    Log.e(TAG, "addCamera failed: ${camErr.description}", throwable)
                    teardownActiveStreamOnly()
                    _state.value = _state.value.copy(
                        lifecycleState = SessionLifecycleState.ERROR,
                        lastError = "addCamera failed: ${camErr.description}"
                    )
                }
            }.onFailure { sessErr, throwable ->
                Log.e(TAG, "createSession failed: ${sessErr.description}", throwable)
                _state.value = _state.value.copy(
                    lifecycleState = SessionLifecycleState.ERROR,
                    lastError = "createSession failed: ${sessErr.description}"
                )
            }
        }
    }

    override fun pauseSession() {
        if (_state.value.lifecycleState != SessionLifecycleState.STREAMING) return
        shouldBeStreaming = false
        runCatching { activeStream?.stop() }
        frameJob?.cancel()
        _state.value = _state.value.copy(
            lifecycleState = SessionLifecycleState.PAUSED,
            hardware = _state.value.hardware.copy(streamStateLabel = "PAUSED"),
            statusDetail = "Session paused — job & queue preserved"
        )
    }

    override fun resumeSession() {
        if (_state.value.lifecycleState != SessionLifecycleState.PAUSED &&
            _state.value.lifecycleState != SessionLifecycleState.RECONNECTING
        ) {
            startSession()
            return
        }
        val hw = _state.value.hardware
        if (!hw.poweredOn || !hw.unfolded) {
            _state.value = _state.value.copy(
                lastError = "Unfold & power on glasses to resume."
            )
            return
        }
        shouldBeStreaming = true
        val stream = activeStream
        if (stream != null && activeSession?.state?.value == DeviceSessionState.STARTED) {
            val res = stream.start()
            res.onSuccess {
                collectStream(stream)
            }
            _state.value = _state.value.copy(
                lifecycleState = SessionLifecycleState.STREAMING,
                hardware = _state.value.hardware.copy(streamStateLabel = "STREAMING"),
                statusDetail = "Session resumed",
                lastError = null
            )
        } else {
            startSession()
        }
    }

    override fun stopSession() {
        shouldBeStreaming = false
        sessionStartJob?.cancel()
        reconnectJob?.cancel()
        teardownActiveStreamOnly()
        _state.value = _state.value.copy(
            lifecycleState = if (_state.value.hardware.paired) {
                SessionLifecycleState.STOPPED
            } else {
                SessionLifecycleState.DISCONNECTED
            },
            hardware = _state.value.hardware.copy(streamStateLabel = "STOPPED"),
            statusDetail = "Session stopped"
        )
    }

    override fun simulateUnexpectedDisconnect() {
        if (!_state.value.hardware.paired) return
        mockGlasses?.fold()
        _state.value = _state.value.copy(
            hardware = _state.value.hardware.copy(unfolded = false)
        )
        handleUnexpectedDrop("Simulated Bluetooth / hinge drop")
    }

    override suspend fun capturePhotoFrame(): Result<CapturedFrame> = withContext(Dispatchers.IO) {
        val snapshot = _state.value
        if (!snapshot.hardware.paired) {
            return@withContext Result.failure(
                IllegalStateException("Mock glasses are not paired — pair glasses and tap Start before capturing.")
            )
        }
        if (!snapshot.hardware.poweredOn || !snapshot.hardware.unfolded) {
            return@withContext Result.failure(
                IllegalStateException("Glasses are powered off or folded — power on and unfold to capture.")
            )
        }
        if (snapshot.lifecycleState != SessionLifecycleState.STREAMING) {
            return@withContext Result.failure(
                IllegalStateException("Camera stream is ${snapshot.lifecycleState.name}. Tap Start (or Resume) before capturing.")
            )
        }

        val source = if (snapshot.hardware.usingDeviceCamera) {
            EvidenceSource.ANDROID_REAR_CAMERA_MOCK
        } else {
            EvidenceSource.MOCK_DEVICE_KIT_SCENE
        }

        val stream = activeStream
        if (stream != null) {
            val result = withTimeoutOrNull(4_000L) {
                runCatching { stream.capturePhoto() }.getOrNull()
            }
            if (result != null) {
                var capturedBitmap: Bitmap? = null
                result.onSuccess { photoData ->
                    capturedBitmap = when (photoData) {
                        is PhotoData.Bitmap -> ensureSoftwareArgb8888(photoData.bitmap)
                        is PhotoData.HEIC -> decodeHeicByteBuffer(photoData.data)?.let(::ensureSoftwareArgb8888)
                    }
                }.onFailure { err, _ ->
                    Log.w(TAG, "DAT capturePhoto returned error: ${err.description}")
                }
                val resolvedBitmap = capturedBitmap
                if (resolvedBitmap != null) {
                    return@withContext Result.success(CapturedFrame(resolvedBitmap, source))
                }
            } else {
                Log.w(TAG, "DAT capturePhoto timed out or returned null.")
            }
        }

        // Never fall back to bundled drawables when Android Rear Camera feed is active.
        val fallback = if (snapshot.hardware.usingDeviceCamera) {
            snapshot.previewFrame
        } else {
            loadSceneBitmap(currentScene) ?: snapshot.previewFrame
        }
        val safeBitmap = fallback?.let(::ensureSoftwareArgb8888)
        if (safeBitmap != null) {
            Result.success(CapturedFrame(safeBitmap, source))
        } else {
            val noFrameMsg = if (snapshot.hardware.usingDeviceCamera) {
                "No live Android rear camera frame received from DAT stream yet."
            } else {
                "No active glasses frame available to capture"
            }
            Result.failure(IllegalStateException(noFrameMsg))
        }
    }

    private fun ensureSoftwareArgb8888(src: Bitmap): Bitmap? {
        if (src.isRecycled) return null
        return runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }

    override fun release() {
        stopSession()
        val glasses = mockGlasses
        if (glasses != null) {
            runCatching { mockDeviceKit?.unpairDevice(glasses) }
        }
        mockGlasses = null
    }

    private fun observeSession(session: DeviceSession) {
        sessionObserveJob?.cancel()
        sessionObserveJob = scope.launch {
            launch {
                session.state.collect { dsState ->
                    if (dsState == DeviceSessionState.STOPPED && shouldBeStreaming) {
                        handleUnexpectedDrop("DeviceSession stopped unexpectedly")
                    }
                }
            }
            launch {
                session.errors.collect { err ->
                    Log.w(TAG, "DeviceSession error: ${err.description}")
                    if (shouldBeStreaming) {
                        handleUnexpectedDrop("DAT link error: ${err.description}")
                    }
                }
            }
        }
    }

    private fun collectStream(stream: Stream) {
        frameJob?.cancel()
        frameJob = scope.launch {
            var sawActiveStream = false
            launch {
                stream.state.collect { st ->
                    if (st == StreamState.STARTED || st == StreamState.STREAMING) {
                        sawActiveStream = true
                    }
                    _state.value = _state.value.copy(
                        hardware = _state.value.hardware.copy(streamStateLabel = st.name)
                    )
                    if (sawActiveStream && (st == StreamState.STOPPED || st == StreamState.CLOSED) && shouldBeStreaming) {
                        handleUnexpectedDrop("Stream interrupted ($st)")
                    }
                }
            }
            launch {
                stream.errorStream.collect { err ->
                    Log.w(TAG, "Stream error notice: ${err.description}")
                    if (!sawActiveStream) {
                        _state.value = _state.value.copy(
                            hardware = _state.value.hardware.copy(streamStateLabel = "MOCK_PHOTO_READY"),
                            statusDetail = "DAT session active (photo capture ready)"
                        )
                    }
                }
            }
            launch {
                stream.videoStream.collect { frame ->
                    val decoded = decodeVideoFrameIfRaw(frame)
                    if (decoded != null) {
                        _state.value = _state.value.copy(previewFrame = decoded)
                    }
                }
            }
        }
    }

    private fun handleUnexpectedDrop(reason: String) {
        teardownActiveStreamOnly()
        _state.value = _state.value.copy(
            lifecycleState = SessionLifecycleState.RECONNECTING,
            reconnectAttempt = 1,
            hardware = _state.value.hardware.copy(streamStateLabel = "RECONNECTING"),
            statusDetail = "$reason — initiating backoff recovery…",
            lastError = reason
        )
        attemptRecoveryReconnect()
    }

    private fun attemptRecoveryReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delaysMs = listOf(1200L, 2000L, 3500L)
            for ((idx, waitMs) in delaysMs.withIndex()) {
                _state.value = _state.value.copy(
                    lifecycleState = SessionLifecycleState.RECONNECTING,
                    reconnectAttempt = idx + 1,
                    statusDetail = "Recovering DAT link (attempt ${idx + 1}/${delaysMs.size})…"
                )
                delay(waitMs)
                // Auto-unfold/power-on if mock glasses were folded during simulated drop
                mockGlasses?.let { g ->
                    if (!_state.value.hardware.unfolded) {
                        g.unfold()
                    }
                    if (!_state.value.hardware.poweredOn) {
                        g.powerOn()
                    }
                    _state.value = _state.value.copy(
                        hardware = _state.value.hardware.copy(unfolded = true, poweredOn = true)
                    )
                }
                if (_state.value.hardware.paired && _state.value.hardware.unfolded && _state.value.hardware.poweredOn) {
                    _state.value = _state.value.copy(
                        disconnectsRecovered = _state.value.disconnectsRecovered + 1,
                        reconnectAttempt = 0,
                        lastError = null
                    )
                    startSession()
                    return@launch
                }
            }
            _state.value = _state.value.copy(
                lifecycleState = SessionLifecycleState.ERROR,
                statusDetail = "Reconnect exhausted — tap Start to retry",
                lastError = "Unable to recover glasses session"
            )
        }
    }

    private fun teardownActiveStreamOnly() {
        frameJob?.cancel()
        sessionObserveJob?.cancel()
        runCatching { activeStream?.stop() }
        runCatching { activeStream?.close() }
        runCatching { activeCamera?.stop() }
        runCatching { activeCamera?.close() }
        runCatching { activeSession?.stop() }
        activeStream = null
        activeCamera = null
        activeSession = null
    }

    private fun decodeVideoFrameIfRaw(frame: VideoFrame): Bitmap? {
        if (frame.isCompressed || frame.isCodecConfig) return null
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return null
        val buf = frame.buffer.duplicate()
        buf.rewind()
        val expectedRgba = w * h * 4
        if (buf.remaining() >= expectedRgba) {
            return runCatching {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                bmp
            }.getOrNull()
        }
        return null
    }

    private fun decodeHeicByteBuffer(buffer: ByteBuffer): Bitmap? {
        return runCatching {
            val dup = buffer.duplicate()
            dup.rewind()
            val bytes = ByteArray(dup.remaining())
            dup.get(bytes)
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    private suspend fun ensureSceneFileUri(scene: MockFeedScene): Uri? {
        cachedSceneFiles[scene]?.let { return it }
        val ctx = appContext ?: return null
        return MockSceneMediaGenerator.ensureSceneImageUri(ctx, scene).also {
            cachedSceneFiles[scene] = it
        }
    }

    private suspend fun ensureSceneVideoUri(scene: MockFeedScene): Uri? {
        cachedSceneVideoFiles[scene]?.let { return it }
        val ctx = appContext ?: return null
        return MockSceneMediaGenerator.ensureSceneVideoUri(ctx, scene)?.also {
            cachedSceneVideoFiles[scene] = it
        }
    }

    private suspend fun loadSceneBitmap(scene: MockFeedScene): Bitmap? {
        val ctx = appContext ?: return null
        return MockSceneMediaGenerator.loadSceneBitmap(ctx, scene)
    }
}
