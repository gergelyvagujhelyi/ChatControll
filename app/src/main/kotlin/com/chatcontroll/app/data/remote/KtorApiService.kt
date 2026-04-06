package com.chatcontroll.app.data.remote

import com.chatcontroll.app.BuildConfig
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.data.remote.dto.AckRequest
import com.chatcontroll.app.data.remote.dto.BootstrapRequest
import com.chatcontroll.app.data.remote.dto.BootstrapResponse
import com.chatcontroll.app.data.remote.dto.CallSignalRequest
import com.chatcontroll.app.data.remote.dto.CallSignalResponse
import com.chatcontroll.app.data.remote.dto.IceServersResponse
import com.chatcontroll.app.data.remote.dto.KeyBundleDto
import com.chatcontroll.app.data.remote.dto.KeyRotationRequest
import com.chatcontroll.app.data.remote.dto.KeyRotationResponse
import com.chatcontroll.app.data.remote.dto.PendingMessageDto
import com.chatcontroll.app.data.remote.dto.PushTokenRequest
import com.chatcontroll.app.data.remote.dto.ResolveShareCodeResponse
import com.chatcontroll.app.data.remote.dto.SendMessageRequest
import com.chatcontroll.app.data.remote.dto.SendMessageResponse
import io.ktor.client.HttpClient
import android.util.Base64
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production API client that communicates with the Python FastAPI relay server.
 *
 * Authenticated requests include an Authorization header with a signed token:
 * ``Bearer <user_id>.<timestamp_ms>.<signature_b64>``
 *
 * The server verifies the Ed25519 signature against the public signing key
 * stored at bootstrap, proving the caller holds the private key.
 */
@OptIn(ExperimentalSerializationApi::class)
@Singleton
class KtorApiService @Inject constructor(
    private val keyManager: KeyManager,
) : ApiService, java.io.Closeable {

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            })
        }
        install(Logging) {
            // Use INFO (method + URL only) instead of HEADERS to avoid
            // leaking Authorization tokens to logcat in debug builds.
            level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
        }
        defaultRequest {
            url(BuildConfig.API_BASE_URL)
            contentType(ContentType.Application.Json)
        }
    }

    override fun close() {
        client.close()
    }

    private fun userId(): String =
        keyManager.getUserId() ?: throw IllegalStateException("No identity — bootstrap first")

    /**
     * Generate a signed auth token: ``<user_id>.<timestamp_ms>.<signature_b64>``
     */
    private fun authToken(): String {
        val uid = userId()
        val ts = System.currentTimeMillis().toString()
        val payload = "$uid.$ts"
        val signature = keyManager.sign(payload.toByteArray(Charsets.UTF_8))
        val sigB64 = Base64.encodeToString(signature, Base64.NO_WRAP)
        return "$payload.$sigB64"
    }

    override suspend fun bootstrapIdentity(request: BootstrapRequest): BootstrapResponse {
        val response: HttpResponse = client.post("/v1/identity/bootstrap") {
            setBody(request)
        }
        check(response.status.isSuccess()) { "Bootstrap failed: ${response.status}" }
        return response.body()
    }

    override suspend fun fetchKeyBundle(userId: String): KeyBundleDto? {
        val response: HttpResponse = client.get("/v1/identity/$userId/keys") {
            header("Authorization", "Bearer ${authToken()}")
        }
        if (response.status.value == 404) return null
        check(response.status.isSuccess()) { "Fetch key bundle failed: ${response.status}" }
        return response.body()
    }

    override suspend fun resolveShareCode(shareCode: String): ResolveShareCodeResponse? {
        val response: HttpResponse = client.get("/v1/identity/resolve/$shareCode") {
            header("Authorization", "Bearer ${authToken()}")
        }
        if (response.status.value == 404) return null
        check(response.status.isSuccess()) { "Resolve share code failed: ${response.status}" }
        return response.body()
    }

    override suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse {
        val response: HttpResponse = client.post("/v1/messages/send") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "Send message failed: ${response.status}" }
        return response.body()
    }

    override suspend fun fetchPendingMessages(): List<PendingMessageDto> {
        val response: HttpResponse = client.get("/v1/messages/pending") {
            header("Authorization", "Bearer ${authToken()}")
        }
        check(response.status.isSuccess()) { "Fetch pending failed: ${response.status}" }
        return response.body()
    }

    override suspend fun acknowledgeMessages(request: AckRequest) {
        val response: HttpResponse = client.post("/v1/messages/ack") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "ACK failed: ${response.status}" }
    }

    override suspend fun registerPushToken(request: PushTokenRequest) {
        val response: HttpResponse = client.post("/v1/push/register") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "Register push token failed: ${response.status}" }
    }

    override suspend fun unregisterPushToken() {
        val response: HttpResponse = client.delete("/v1/push/register") {
            header("Authorization", "Bearer ${authToken()}")
        }
        check(response.status.isSuccess()) { "Unregister push token failed: ${response.status}" }
    }

    override suspend fun sendCallSignal(request: CallSignalRequest): CallSignalResponse {
        val response: HttpResponse = client.post("/v1/calls/signal") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "Call signal failed: ${response.status}" }
        return response.body()
    }

    override suspend fun getIceServers(): IceServersResponse {
        val response: HttpResponse = client.get("/v1/calls/ice-servers") {
            header("Authorization", "Bearer ${authToken()}")
        }
        check(response.status.isSuccess()) { "Get ICE servers failed: ${response.status}" }
        return response.body()
    }

    override suspend fun rotateKeys(request: KeyRotationRequest): KeyRotationResponse {
        val response: HttpResponse = client.put("/v1/identity/me/keys") {
            header("Authorization", "Bearer ${authToken()}")
            setBody(request)
        }
        check(response.status.isSuccess()) { "Key rotation failed: ${response.status}" }
        return response.body()
    }

    override suspend fun deleteIdentity() {
        val response: HttpResponse = client.delete("/v1/identity/me") {
            header("Authorization", "Bearer ${authToken()}")
        }
        check(response.status.isSuccess()) { "Identity deletion failed: ${response.status}" }
    }
}
