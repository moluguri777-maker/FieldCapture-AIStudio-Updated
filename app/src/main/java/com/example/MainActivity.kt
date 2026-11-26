
package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ai.configureAppCheck
import com.example.domain.model.SyncStatus
import com.example.ui.screens.CaptureDeckScreen
import com.example.ui.screens.EvalTelemetryScreen
import com.example.ui.screens.JobEvidenceScreen
import com.example.ui.screens.SyncQueueScreen
import com.example.ui.theme.FieldCaptureTheme
import com.example.ui.viewmodel.FieldCaptureViewModel
import com.example.wearable.WearablePermissions
import kotlinx.serialization.Serializable

@Serializable
data object CaptureRoute

@Serializable
data object JobRoute

@Serializable
data object SyncRoute

@Serializable
data object EvalRoute

private data class NavDestinationItem(
    val route: Any,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
    val badgeCount: Int = 0
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check before ViewModels, UI, or Firebase calls
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        setContent {
            val viewModel: FieldCaptureViewModel = viewModel()
            val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()

            FieldCaptureTheme(darkTheme = pipelineState.isDarkFieldTheme) {
                FieldCaptureAppShell(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureAppCheck(this, intent)
    }
}

@Composable
private fun FieldCaptureAppShell(viewModel: FieldCaptureViewModel) {

    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    var hasRequestedBluetoothAtStartup by rememberSaveable { mutableStateOf(false) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it } &&
            WearablePermissions.hasBluetoothConnectPermission(context)
        viewModel.onBluetoothPermissionResult(allGranted)
    }

    val requestBluetoothPermissions: () -> Unit = {
        val perms = WearablePermissions.requiredBluetoothPermissions()
        if (perms.isEmpty() || WearablePermissions.hasBluetoothConnectPermission(context)) {
            viewModel.onBluetoothPermissionResult(true)
        } else {
            bluetoothPermissionLauncher.launch(perms)
        }
    }

    val openAppSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    LaunchedEffect(Unit) {
        if (WearablePermissions.hasBluetoothConnectPermission(context)) {
            viewModel.onBluetoothPermissionResult(true)
        } else {
            viewModel.onBluetoothPermissionResult(false)
            if (!hasRequestedBluetoothAtStartup) {
                hasRequestedBluetoothAtStartup = true
                bluetoothPermissionLauncher.launch(WearablePermissions.requiredBluetoothPermissions())
            }
        }
    }

    val activeJob by viewModel.activeJob.collectAsStateWithLifecycle()
    val wearableState by viewModel.wearableState.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()
    val captures by viewModel.captures.collectAsStateWithLifecycle()
    val estimateItems by viewModel.estimateLineItems.collectAsStateWithLifecycle()
    val outboxItems by viewModel.outboxItems.collectAsStateWithLifecycle()
    val httpTraces by viewModel.httpTraces.collectAsStateWithLifecycle()
    val loopbackMode by viewModel.loopbackMode.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val isSyncingNow by viewModel.isSyncingNow.collectAsStateWithLifecycle()
    val isNetworkOnline by viewModel.isNetworkOnline.collectAsStateWithLifecycle()
    val exportedJobJson by viewModel.exportedJobJson.collectAsStateWithLifecycle()
    val evalSummary by viewModel.evalSummary.collectAsStateWithLifecycle()
    val telemetryRecords by viewModel.telemetryRecords.collectAsStateWithLifecycle()
    val importedEvalUris by viewModel.importedEvalUris.collectAsStateWithLifecycle()

    val pendingSyncCount = outboxItems.count {
        it.status == SyncStatus.QUEUED ||
                it.status == SyncStatus.FAILED
    }

    val destinations = listOf(

        NavDestinationItem(
            route = CaptureRoute,
            labelRes = R.string.nav_capture,
            selectedIcon = Icons.Filled.Videocam,
            unselectedIcon = Icons.Outlined.Videocam,
            testTag = "nav_capture"
        ),

        NavDestinationItem(
            route = JobRoute,
            labelRes = R.string.nav_job,
            selectedIcon = Icons.AutoMirrored.Filled.Assignment,
            unselectedIcon = Icons.AutoMirrored.Outlined.Assignment,
            testTag = "nav_job",
            badgeCount = captures.size
        ),

        NavDestinationItem(
            route = SyncRoute,
            labelRes = R.string.nav_sync,
            selectedIcon = Icons.Filled.CloudSync,
            unselectedIcon = Icons.Outlined.CloudSync,
            testTag = "nav_sync",
            badgeCount = pendingSyncCount
        ),

        NavDestinationItem(
            route = EvalRoute,
            labelRes = R.string.nav_eval,
            selectedIcon = Icons.Filled.Analytics,
            unselectedIcon = Icons.Outlined.Analytics,
            testTag = "nav_eval"
        )
    )

    fun navigateTo(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {

        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,

            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        destinations.forEach { item ->

                            val selected =
                                currentDest?.hasRoute(item.route::class) == true

                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    NavIconWithBadge(item, selected)
                                },
                                label = {
                                    Text(stringResource(item.labelRes))
                                },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }

        ) { innerPadding ->

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {

                if (useRail) {
                    NavigationRail {
                        destinations.forEach { item ->

                            val selected =
                                currentDest?.hasRoute(item.route::class) == true

                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    NavIconWithBadge(item, selected)
                                },
                                label = {
                                    Text(stringResource(item.labelRes))
                                },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {

                    NavHost(
                        navController = navController,
                        startDestination = CaptureRoute,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 780.dp)
                    ) {

                        composable<CaptureRoute> {
                            CaptureDeckScreen(
                                job = activeJob,
                                wearableState = wearableState,
                                voiceState = voiceState,
                                pipelineState = pipelineState,
                                onToggleTheme = viewModel::toggleDarkFieldTheme,
                                onRequestBluetoothPermission = requestBluetoothPermissions,
                                onOpenAppSettings = openAppSettings,
                                onPairMock = {
                                    if (WearablePermissions.hasBluetoothConnectPermission(context)) {
                                        viewModel.pairMockGlasses()
                                    } else {
                                        requestBluetoothPermissions()
                                    }
                                },
                                onUnpairMock = viewModel::unpairMockGlasses,
                                onSetPower = viewModel::setMockPower,
                                onSetFolded = viewModel::setMockFolded,
                                onSetDonned = viewModel::setMockDonned,
                                onSelectScene = viewModel::selectMockScene,
                                onToggleDeviceCamera = viewModel::toggleDeviceCameraFeed,
                                onStartSession = {
                                    if (WearablePermissions.hasBluetoothConnectPermission(context)) {
                                        viewModel.startSession()
                                    } else {
                                        requestBluetoothPermissions()
                                    }
                                },
                                onPauseSession = viewModel::pauseSession,
                                onResumeSession = viewModel::resumeSession,
                                onStopSession = viewModel::stopSession,
                                onSimulateDisconnect = viewModel::simulateUnexpectedDisconnect,
                                onStartVoice = viewModel::startVoiceListening,
                                onStopVoice = viewModel::stopVoiceListening,
                                onSubmitCommand = viewModel::submitCommandText,
                                onOverrideQuality = viewModel::overrideQualityAndKeep,
                                onDiscardQuality = viewModel::discardFailedCapture
                            )
                        }

                        composable<JobRoute> {
                            JobEvidenceScreen(
                                job = activeJob,
                                captures = captures,
                                estimateItems = estimateItems,
                                exportedJson = exportedJobJson,
                                onUpdateField = viewModel::updateFieldValue,
                                onToggleEstimateCode = viewModel::toggleEstimateCode,
                                onRetryExtraction = viewModel::retryExtraction,
                                onDeleteCapture = viewModel::deleteCapture,
                                onExportJobJson = viewModel::refreshJobJsonExport,
                                onDismissExportJson = viewModel::dismissJobJsonExport
                            )
                        }

                        composable<SyncRoute> {
                            SyncQueueScreen(
                                outboxItems = outboxItems,
                                httpTraces = httpTraces,
                                loopbackMode = loopbackMode,
                                baseUrl = baseUrl,
                                isNetworkOnline = isNetworkOnline,
                                isSyncingNow = isSyncingNow,
                                onToggleLoopback = viewModel::setLoopbackMode,
                                onUpdateBaseUrl = viewModel::updateRestBaseUrl,
                                onSimulateHttp503 = viewModel::simulateNextHttp503,
                                onSyncNow = viewModel::syncNow,
                                onClearSynced = viewModel::clearSyncedOutbox
                            )
                        }

                        composable<EvalRoute> {
                            EvalTelemetryScreen(
                                evalSummary = evalSummary,
                                telemetryRecords = telemetryRecords,
                                importedUriCount = importedEvalUris.size,
                                onImportFrames = viewModel::addImportedEvalUris,
                                onToggleLiveAi = viewModel::setEvalUseLiveAi,
                                onStartEval = viewModel::start40FrameEvaluation,
                                onStopEval = viewModel::stopEvaluation
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavIconWithBadge(
    item: NavDestinationItem,
    selected: Boolean
) {
    val icon = if (selected) item.selectedIcon else item.unselectedIcon
    val label = stringResource(item.labelRes)

    if (item.badgeCount > 0) {
        BadgedBox(
            badge = {
                Badge {
                    Text(item.badgeCount.toString())
                }
            }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label
            )
        }
    } else {
        Icon(
            imageVector = icon,
            contentDescription = label
        )
    }
}
