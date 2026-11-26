package com.example.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val TOKEN_STORE_PREFIX = "com.google.firebase.appcheck.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"
private const val EXPECTED_PROJECT_ID = "gen-lang-client-0675019109"
private const val EXPECTED_APP_ID = "1:710382825344:android:217d80d8206c797f415b65"
private const val DUMMY_ERROR_TOKEN = "eyJlcnJvciI6IlVOS05PV05fRVJST1IifQ=="

private fun safeLogD(tag: String, msg: String) {
    runCatching { Log.d(tag, msg) }
}

private fun safeLogW(tag: String, msg: String) {
    runCatching { Log.w(tag, msg) }
}

private fun safeLogE(tag: String, msg: String, tr: Throwable? = null) {
    runCatching {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }
}

data class AppCheckDiagnosticState(
    val providerMode: String = "UNINITIALIZED",
    val receivedIntentDebugToken: Boolean = false,
    val lastError: String? = null
)

object AppCheckCoordinator {
    @Volatile
    var diagnosticState: AppCheckDiagnosticState = AppCheckDiagnosticState()
        internal set

    private val tokenRotationEpoch = AtomicLong(0L)
    private val lastRefreshedEpoch = AtomicLong(0L)
    private val providerInstallLock = Any()

    @Volatile
    private var installedProviderSignature: String? = null

    val pendingForceRefresh: Boolean
        get() = tokenRotationEpoch.get() > lastRefreshedEpoch.get()

    internal fun markDebugTokenRotated() {
        tokenRotationEpoch.incrementAndGet()
    }

    internal fun installProviderIfNeeded(
        persistenceKey: String,
        providerMode: String,
        installer: () -> Unit
    ): Boolean {
        val signature = "$persistenceKey:$providerMode"
        if (installedProviderSignature == signature) {
            return false
        }
        synchronized(providerInstallLock) {
            if (installedProviderSignature == signature) {
                return false
            }
            installer()
            installedProviderSignature = signature
            return true
        }
    }

    internal fun resetForTesting() {
        synchronized(providerInstallLock) {
            installedProviderSignature = null
        }
        tokenRotationEpoch.set(0L)
        lastRefreshedEpoch.set(0L)
        diagnosticState = AppCheckDiagnosticState()
    }

    /**
     * Requests a valid Firebase App Check token before calling Firebase AI Logic.
     * Detects the Firebase SDK's DUMMY_TOKEN fallback and translates 403 / provider
     * failures into actionable diagnostics rather than letting an invalid header reach Gemini.
     */
    suspend fun awaitValidAppCheckToken(
        context: Context?,
        forceRefresh: Boolean = false
    ): Result<String> {
        if (context == null) {
            return Result.failure(IllegalStateException("Application context is required for App Check."))
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            return Result.failure(IllegalStateException("FirebaseApp is not initialized."))
        }

        return awaitValidAppCheckTokenWithFetcher(
            forceRefresh = forceRefresh,
            fetcher = { shouldForce -> fetchTokenOnce(shouldForce) }
        )
    }

    private const val MAX_ROTATION_RETRIES = 3

    internal suspend fun awaitValidAppCheckTokenWithFetcher(
        forceRefresh: Boolean,
        fetcher: suspend (Boolean) -> Result<String>
    ): Result<String> {
        var rotationRetries = 0
        var staleCacheRetried = false
        var force = forceRefresh

        while (true) {
            val epochBefore = tokenRotationEpoch.get()
            val mustForce = force || (epochBefore > lastRefreshedEpoch.get())
            val attempt = fetcher(mustForce)

            // Discard result if the debug secret rotated during this fetch.
            val epochAfter = tokenRotationEpoch.get()
            if (epochAfter > epochBefore) {
                if (rotationRetries >= MAX_ROTATION_RETRIES) {
                    val msg = "App Check debug token rotated repeatedly during fetch (exceeded $MAX_ROTATION_RETRIES retries)."
                    safeLogE(TAG, msg)
                    diagnosticState = diagnosticState.copy(lastError = msg)
                    return Result.failure(IllegalStateException(msg))
                }
                rotationRetries++
                force = true
                continue
            }

            if (attempt.isSuccess) {
                if (mustForce) {
                    lastRefreshedEpoch.updateAndGet { prev -> maxOf(prev, epochBefore) }
                }
                // Confirm epoch is still unchanged immediately before returning the token.
                if (tokenRotationEpoch.get() != epochBefore) {
                    if (rotationRetries >= MAX_ROTATION_RETRIES) {
                        val msg = "App Check debug token rotated repeatedly during fetch (exceeded $MAX_ROTATION_RETRIES retries)."
                        safeLogE(TAG, msg)
                        diagnosticState = diagnosticState.copy(lastError = msg)
                        return Result.failure(IllegalStateException(msg))
                    }
                    rotationRetries++
                    force = true
                    continue
                }
                return attempt
            }

            // If a non-forced read hit a stale/dummy cache entry, retry once with forceRefresh = true.
            if (!mustForce && !staleCacheRetried) {
                staleCacheRetried = true
                force = true
                continue
            }
            return attempt
        }
    }

    private suspend fun fetchTokenOnce(forceRefresh: Boolean): Result<String> {
        return try {
            val result = withTimeout(12_000L) {
                suspendCancellableCoroutine<Result<String>> { cont ->
                    FirebaseAppCheck.getInstance()
                        .getAppCheckToken(forceRefresh)
                        .addOnSuccessListener { appCheckToken ->
                            val token = appCheckToken.token
                            if (token.isBlank() || token == DUMMY_ERROR_TOKEN || token.startsWith("eyJlcnJvciI6")) {
                                val err = IllegalStateException(buildActionableError("App Check returned an invalid placeholder token."))
                                diagnosticState = diagnosticState.copy(lastError = err.message)
                                if (cont.isActive) cont.resume(Result.failure(err))
                            } else {
                                diagnosticState = diagnosticState.copy(lastError = null)
                                if (cont.isActive) cont.resume(Result.success(token))
                            }
                        }
                        .addOnFailureListener { exc ->
                            val err = IllegalStateException(buildActionableError(exc.localizedMessage ?: exc.javaClass.simpleName), exc)
                            diagnosticState = diagnosticState.copy(lastError = err.message)
                            if (cont.isActive) cont.resume(Result.failure(err))
                        }
                }
            }
            result
        } catch (e: Exception) {
            val err = IllegalStateException(buildActionableError(e.localizedMessage ?: "App Check token request failed."), e)
            diagnosticState = diagnosticState.copy(lastError = err.message)
            Result.failure(err)
        }
    }

    private fun buildActionableError(rawMessage: String): String {
        val state = diagnosticState
        return if (BuildConfig.DEBUG) {
            val tokenHint = if (state.receivedIntentDebugToken) {
                "A debug token was provided via launch Intent; verify network connectivity to firebaseappcheck.googleapis.com and that the token matches project $EXPECTED_PROJECT_ID."
            } else {
                "No FIREBASE_APPCHECK_DEBUG_TOKEN launch extra was supplied. Launch via AI Studio OR register the DebugAppCheckProvider UUID printed in Logcat under Firebase Console -> App Check for app $EXPECTED_APP_ID ($EXPECTED_PROJECT_ID)."
            }
            "Firebase App Check token is invalid ($rawMessage). $tokenHint"
        } else {
            "Firebase App Check reCAPTCHA attestation failed ($rawMessage). Verify Play Store / WebView network access and reCAPTCHA v3 key in Firebase Console ($EXPECTED_PROJECT_ID)."
        }
    }
}

internal inline fun <T> resolveFirebaseAppOrReport(provider: () -> T?): T? {
    val app = provider()
    if (app == null) {
        val message = "FirebaseApp.initializeApp returned null. Verify app/google-services.json and Android package configuration."
        safeLogE(TAG, message)
        AppCheckCoordinator.diagnosticState = AppCheckDiagnosticState(
            providerMode = "UNINITIALIZED",
            receivedIntentDebugToken = false,
            lastError = message
        )
    }
    return app
}

internal fun syncDebugTokenAndCheckRotation(
    debugToken: String?,
    readCurrentSecret: () -> String?,
    writeSecretSync: (String) -> Boolean,
    clearTokenCacheSync: () -> Boolean
): Boolean {
    val trimmed = debugToken?.trim()
    if (trimmed.isNullOrEmpty()) {
        return false
    }
    val previousSecret = readCurrentSecret()
    if (previousSecret != trimmed) {
        // Synchronous commit() ensures DebugAppCheckProvider reads the updated secret immediately
        // and clears any stale cached App Check JWT issued under an older secret.
        val secretSaved = writeSecretSync(trimmed)
        val cacheCleared = clearTokenCacheSync()
        if (!secretSaved || !cacheCleared) {
            safeLogW(TAG, "Debug secret or App Check token cache commit returned false during rotation.")
        }
        AppCheckCoordinator.markDebugTokenRotated()
        safeLogD(TAG, "Updated App Check debug secret from Intent extra and marked token for forced refresh.")
    } else {
        safeLogD(TAG, "App Check debug secret already matches Intent extra.")
    }
    return true
}

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        val app = resolveFirebaseAppOrReport {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            } else {
                FirebaseApp.getInstance()
            }
        } ?: return

        val persistenceKey = app.persistenceKey

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            val debugPrefs = context.getSharedPreferences(
                "$DEBUG_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE
            )
            val tokenPrefs = context.getSharedPreferences(
                "$TOKEN_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE
            )

            val receivedExtra = syncDebugTokenAndCheckRotation(
                debugToken = debugToken,
                readCurrentSecret = { debugPrefs.getString(DEBUG_SECRET_KEY, null) },
                writeSecretSync = { secret -> debugPrefs.edit().putString(DEBUG_SECRET_KEY, secret).commit() },
                clearTokenCacheSync = { tokenPrefs.edit().clear().commit() }
            )
            if (!receivedExtra) {
                safeLogW(
                    TAG,
                    "No $DEBUG_TOKEN_EXTRA Intent extra found; DebugAppCheckProvider will use persisted debug secret."
                )
            }

            val installed = AppCheckCoordinator.installProviderIfNeeded(
                persistenceKey = persistenceKey,
                providerMode = "DEBUG_PROVIDER"
            ) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
            }
            AppCheckCoordinator.diagnosticState = AppCheckDiagnosticState(
                providerMode = "DEBUG_PROVIDER",
                receivedIntentDebugToken = receivedExtra,
                lastError = null
            )
            if (installed) {
                safeLogD(TAG, "DebugAppCheckProviderFactory installed for ${context.packageName}.")
            }
        } else {
            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    val installed = AppCheckCoordinator.installProviderIfNeeded(
                        persistenceKey = persistenceKey,
                        providerMode = "RECAPTCHA_V3"
                    ) {
                        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                            RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                        )
                    }
                    AppCheckCoordinator.diagnosticState = AppCheckDiagnosticState(
                        providerMode = "RECAPTCHA_V3",
                        receivedIntentDebugToken = false,
                        lastError = null
                    )
                    if (installed) {
                        safeLogD(TAG, "RecaptchaAppCheckProviderFactory installed.")
                    }
                } else {
                    val msg = "recaptcha_site_key resource string is blank."
                    safeLogE(TAG, msg)
                    AppCheckCoordinator.diagnosticState = AppCheckCoordinator.diagnosticState.copy(
                        lastError = msg
                    )
                }
            } else {
                val msg = "recaptcha_site_key string resource not found in package."
                safeLogE(TAG, msg)
                AppCheckCoordinator.diagnosticState = AppCheckCoordinator.diagnosticState.copy(
                    lastError = msg
                )
            }
        }
    } catch (e: Exception) {
        safeLogE(TAG, "Failed to initialize Firebase App Check", e)
        AppCheckCoordinator.diagnosticState = AppCheckCoordinator.diagnosticState.copy(
            lastError = e.localizedMessage ?: e.javaClass.simpleName
        )
    }
}
