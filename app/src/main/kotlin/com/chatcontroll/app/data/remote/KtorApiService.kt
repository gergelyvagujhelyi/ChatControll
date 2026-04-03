package com.chatcontroll.app.data.remote

import com.chatcontroll.app.BuildConfig
import com.chatcontroll.app.crypto.KeyManager
import com.chatcontroll.app.data.remote.dto.AckRequest
import com.chatcontroll.app.data.remote.dto.BootstrapRequest
import com.chatcontroll.app.data.remote.dto.BootstrapResponse
import com.chatcontroll.app.data.remote.dto.KeyBundleDto
import com.chatcontroll.app.data.remote.dto.PendingMessageDto
import com.chatcontroll.app.data.remote.dto.PushTokenRequest
import com.chatcontroll.app.data.remote.dto.ResolveShareCodeResponse
import com.chatcontroll.app.data.remote.dto.SendMessageRequest
import com.chatcontroll.app.data.remote.dto.SendMessageResponse
import io.ktor.client.HttpClient
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
 * All requests include the X-User-Id header for routing.
 * No authentication tokens are used — the server trusts the user_id header.
 * In production, add HMAC-signed request authentication.
 */
@OptIn(ExperimentalSerializationApi::class)
@Singleton
class KtorApiService @Inject constructor(
    private val keyManager: KeyManager,
) : ApiService {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            })
        }
        install(Logging) {
            level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
        }
        defaultRequest {
            url(BuildConfig.API_BASE_URL)
            contentType(ContentType.Application.Json)
        }
    }

    private fun userId(): String =
        keyManager.getUserId() ?: throw IllegalStateException("No identity — bootstrap first")

    override suspend fun bootstrapIdentity(request: BootstrapRequest): BootstrapResponse {
        val response: HttpResponse = client.post("/v1/identity/bootstrap") {
            setBody(request)
        }
        check(response.status.isSuccess()) { "Bootstrap failed: ${response.status}" }
        return response.body()
    }

    override suspend fun fetchKeyBundle(userId: String): KeyBundleDto? {
        val response: HttpResponse = client.get("/v1/identity/$userId/keys") {
            header("X-User-Id", userId())
        }
        if (response.status.value == 404) return null
        check(response.status.isSuccess()) { "Fetch key bundle failed: ${response.status}" }
        return response.body()
    }

    override suspend fun resolveShareCode(shareCode: String): ResolveShareCodeResponse? {
        val response: HttpResponse = client.get("/v1/identity/resolve/$shareCode") {
            header("X-User-Id", userId())
        }
        if (response.status.value == 404) return null
        check(response.status.isSuccess()) { "Resolve share code failed: ${response.status}" }
        return response.body()
    }

    override suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse {
        val response: HttpResponse = client.post("/v1/messages/send") {
            header("X-User-Id", userId())
            setBody(request)
        }
        check(response.status.isSuccess()) { "Send message failed: ${response.status}" }
        return response.body()
    }

    override suspend fun fetchPendingMessages(): List<PendingMessageDto> {
        val response: HttpResponse = client.get("/v1/messages/pending") {
            header("X-User-Id", userId())
        }
        check(response.status.isSuccess()) { "Fetch pending failed: ${response.status}" }
        return response.body()
    }

    override suspend fun acknowledgeMessages(request: AckRequest) {
        val response: HttpResponse = client.post("/v1/messages/ack") {
            header("X-User-Id", userId())
            setBody(request)
        }
        check(response.status.isSuccess()) { "ACK failed: ${response.status}" }
    }

    override suspend fun registerPushToken(request: PushTokenRequest) {
        val response: HttpResponse = client.post("/v1/push/register") {
            header("X-User-Id", userId())
            setBody(request)
        }
        check(response.status.isSuccess()) { "Register push token failed: ${response.status}" }
    }

    override suspend fun unregisterPushToken() {
        val response: HttpResponse = client.delete("/v1/push/register") {
            header("X-User-Id", userId())
        }
        check(response.status.isSuccess()) { "Unregister push token failed: ${response.status}" }
    }
}
