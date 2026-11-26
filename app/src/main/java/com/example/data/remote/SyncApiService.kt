package com.example.data.remote

import com.example.domain.model.HttpTraceLog
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class MediaUploadResponseDto(
    val captureId: String,
    val mediaUrl: String,
    val checksumVerified: Boolean,
    val serverTimestampMs: Long
)

@JsonClass(generateAdapter = true)
data class JobSyncResponseDto(
    val jobId: String,
    val serverRevision: Int,
    val acceptedCaptures: Int,
    val syncedAtEpochMs: Long
)

interface FieldCaptureRestApi {
    @Multipart
    @PUT("v1/inspections/{jobId}/media/{captureId}")
    suspend fun uploadMedia(
        @Path("jobId") jobId: String,
        @Path("captureId") captureId: String,
        @Header("X-Idempotency-Key") idempotencyKey: String,
        @Part image: MultipartBody.Part,
        @Part("metadataJson") metadataJson: RequestBody
    ): MediaUploadResponseDto

    @POST("v1/inspections/{jobId}/sync")
    suspend fun syncJobRecord(
        @Path("jobId") jobId: String,
        @Header("X-Idempotency-Key") idempotencyKey: String,
        @Body jobRecordJson: RequestBody
    ): JobSyncResponseDto

    @GET("v1/inspections/{jobId}")
    suspend fun fetchJobRecord(
        @Path("jobId") jobId: String
    ): JobSyncResponseDto
}

class SyncTransportConfig {
    private val _loopbackMode = MutableStateFlow(true)
    val loopbackMode: StateFlow<Boolean> = _loopbackMode.asStateFlow()

    private val _simulateNextFailure = MutableStateFlow(false)
    val simulateNextFailure: StateFlow<Boolean> = _simulateNextFailure.asStateFlow()

    private val _baseUrl = MutableStateFlow("https://api.fieldcapture.example.com/")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _httpTraces = MutableStateFlow<List<HttpTraceLog>>(emptyList())
    val httpTraces: StateFlow<List<HttpTraceLog>> = _httpTraces.asStateFlow()

    private var revisionCounter = 1

    fun setLoopbackMode(enabled: Boolean) {
        _loopbackMode.value = enabled
    }

    fun setSimulateNextFailure(enabled: Boolean) {
        _simulateNextFailure.value = enabled
    }

    fun setBaseUrl(url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        _baseUrl.value = normalized
    }

    fun recordTrace(trace: HttpTraceLog) {
        _httpTraces.value = (listOf(trace) + _httpTraces.value).take(30)
    }

    fun nextRevision(): Int = revisionCounter++
}

/**
 * Intercepts Retrofit/OkHttp calls when Loopback Mock Server mode is enabled so
 * full multipart/JSON serialization, idempotency headers, and retry behavior can be
 * exercised deterministically without an external cloud deployment.
 */
class LoopbackRestInterceptor(
    private val config: SyncTransportConfig
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val idempotencyKey = request.header("X-Idempotency-Key") ?: "none"
        val startMs = System.currentTimeMillis()

        if (!config.loopbackMode.value) {
            val response = chain.proceed(request)
            config.recordTrace(
                HttpTraceLog(
                    id = UUID.randomUUID().toString(),
                    method = request.method,
                    path = request.url.encodedPath,
                    statusCode = response.code,
                    latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                    idempotencyKey = idempotencyKey,
                    summary = "Live HTTP ${response.code}",
                    timestampEpochMs = System.currentTimeMillis()
                )
            )
            return response
        }

        // Simulate transient HTTP 503 if requested by assessor
        if (config.simulateNextFailure.value) {
            config.setSimulateNextFailure(false)
            val errBody = """{"error":"Simulated 503 upstream timeout"}"""
                .toResponseBody("application/json".toMediaType())
            config.recordTrace(
                HttpTraceLog(
                    id = UUID.randomUUID().toString(),
                    method = request.method,
                    path = request.url.encodedPath,
                    statusCode = 503,
                    latencyMs = 42L,
                    idempotencyKey = idempotencyKey,
                    summary = "Simulated 503 (queued for exponential retry)",
                    timestampEpochMs = System.currentTimeMillis()
                )
            )
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Service Unavailable")
                .body(errBody)
                .build()
        }

        val path = request.url.encodedPath
        val jsonBody = when {
            path.contains("/media/") -> {
                val captureId = path.substringAfterLast("/")
                """{"captureId":"$captureId","mediaUrl":"https://cdn.fieldcapture.local/media/$captureId.jpg","checksumVerified":true,"serverTimestampMs":${System.currentTimeMillis()}}"""
            }
            path.endsWith("/sync") -> {
                val jobId = path.substringBeforeLast("/sync").substringAfterLast("/")
                val rev = config.nextRevision()
                """{"jobId":"$jobId","serverRevision":$rev,"acceptedCaptures":1,"syncedAtEpochMs":${System.currentTimeMillis()}}"""
            }
            else -> {
                val jobId = path.substringAfterLast("/")
                """{"jobId":"$jobId","serverRevision":1,"acceptedCaptures":0,"syncedAtEpochMs":${System.currentTimeMillis()}}"""
            }
        }

        val latency = 28L
        config.recordTrace(
            HttpTraceLog(
                id = UUID.randomUUID().toString(),
                method = request.method,
                path = path,
                statusCode = 200,
                latencyMs = latency,
                idempotencyKey = idempotencyKey,
                summary = "Loopback 200 OK",
                timestampEpochMs = System.currentTimeMillis()
            )
        )

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(jsonBody.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

object RestApiFactory {
    fun create(config: SyncTransportConfig): FieldCaptureRestApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(LoopbackRestInterceptor(config))
            .addInterceptor(logging)
            .build()

        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()

        val baseUrl = config.baseUrl.value.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        } ?: "https://api.fieldcapture.example.com/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(FieldCaptureRestApi::class.java)
    }
}
