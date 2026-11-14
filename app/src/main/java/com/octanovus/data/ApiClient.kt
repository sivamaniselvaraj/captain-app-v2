package com.octanovus.data

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Talks to the desktop app over the local network only.
 * The phone never talks to the cloud database, including for sign-in.
 *
 * Every protected request sends two things:
 *   Authorization: Bearer fop_<id>.<secret>   (the device token)
 *   X-Captain-Pin: <4-digit PIN>               (the waiter's PIN, once logged in)
 * PROPOSED header name, to be confirmed by the desktop team.
 */
/** Result of a conditional GET. [json] is null when the desktop answered 304 Not Modified. */
data class Conditional(val json: JSONObject?, val etag: String?)

class ApiClient(private val prefs: AppPrefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** For the live stream: the desktop sends a keep-alive every 20 s, so 45 s of silence means it is dead. */
    private val streamHttp by lazy { http.newBuilder().readTimeout(45, TimeUnit.SECONDS).build() }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun endpoint(path: String): String = prefs.serverUrl + path

    /** GET /api/health (no token). Expects {"status":"ok", ...}. */
    suspend fun health(): ApiResult<JSONObject> =
        execute { Request.Builder().url(endpoint("/api/health")).get().build() }

    /**
     * Pairs this phone. The waiter's email and password go to the desktop,
     * which checks them with the cloud. The phone does not sign in itself.
     * PROPOSED body, not yet in the desktop API document:
     *   POST /api/signin  { "code", "deviceName", "email", "password" }  (no token)
     *   200 -> { "token": "fop_<id>.<secret>", "deviceId", "deviceName", "userName" }
     */
    suspend fun pair(
        code: String,
        deviceName: String,
        email: String,
        password: String,
    ): ApiResult<JSONObject> {
        val body = JSONObject()
            .put("code", code)
            .put("deviceName", deviceName)
            .put("email", email)
            .put("password", password)
            .toString()
            .toRequestBody(jsonMedia)
        return execute {
            Request.Builder()
                .url(endpoint("/api/signin"))
                .post(body)
                .build()
        }
    }

    /**
     * GET /api/me with the device token only (no PIN yet). Used on the splash screen to find out
     * whether the desktop still accepts this device. 401 or 403 means it was revoked.
     */
    suspend fun checkDevice(): ApiResult<JSONObject> = protectedGet("/api/me")

    /**
     * Sets the waiter's 4-digit PIN for this device.
     * PROPOSED: POST /api/device/pin { "pin" } with the device token. The desktop stores a hash.
     */
    suspend fun setPin(pin: String): ApiResult<JSONObject> =
        protectedPost("/api/pin/set", JSONObject().put("pin", pin), pinHeader = null)

    /**
     * PIN login from the splash screen. The desktop checks the PIN.
     * PROPOSED: POST /api/session { "pin" } with the device token.
     * On success the PIN is kept in memory and sent with later requests.
     */
    suspend fun loginWithPin(pin: String): ApiResult<JSONObject> {
        val result = protectedPost("/api/pin/verify", JSONObject().put("pin", pin), pinHeader = null)
        if (result is ApiResult.Ok) {
            Session.pin = pin
            // The desktop returns an unlock token (hash). Later requests send it as X-Unlock-Token.
            val token = UNLOCK_TOKEN_FIELDS
                .map { result.value.optString(it) }
                .firstOrNull { it.isNotBlank() }
            if (token == null) {
                Log.w("CaptainApi", "PIN login response has no unlock token field")
            }
            Session.unlockToken = token
        }
        return result
    }

    /** GET with the device token and PIN. Use for every protected call. */
    suspend fun protectedGet(path: String): ApiResult<JSONObject> {
        val builder = authorisedBuilder(path) ?: return NOT_PAIRED
        return execute { builder.get().build() }
    }

    /**
     * GET that sends `If-None-Match` when [etag] is known. A 304 answer comes back as
     * `Conditional(json = null, etag)`, meaning nothing changed.
     */
    suspend fun getIfChanged(path: String, etag: String?): ApiResult<Conditional> {
        val builder = authorisedBuilder(path) ?: return NOT_PAIRED
        etag?.let { builder.header("If-None-Match", it) }
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(builder.get().build()).execute().use { response ->
                    if (response.code == 304) {
                        return@use ApiResult.Ok(Conditional(json = null, etag = etag))
                    }
                    val text = response.body?.string().orEmpty()
                    val json = try {
                        JSONObject(text)
                    } catch (e: JSONException) {
                        null
                    }
                    if (response.isSuccessful) {
                        ApiResult.Ok(Conditional(json ?: JSONObject(), response.header("ETag")))
                    } else {
                        failure(response.code, json?.optString("error").orEmpty())
                    }
                }
            } catch (e: IOException) {
                Log.e("CaptainApi", "Request to ${prefs.serverUrl} failed", e)
                ApiResult.Err(
                    "Cannot reach the desktop at ${prefs.serverUrl}. " +
                        "Cause: ${e.javaClass.simpleName}: ${e.message ?: "no message"}.",
                )
            }
        }
    }

    /**
     * Marks one item of an order as served.
     * PROPOSED path and body, to be replaced with the desktop's real endpoint:
     * POST /api/orders/{orderId}/items/{itemId}/served  {}
     */
    suspend fun markItemServed(orderId: String, itemId: String): ApiResult<JSONObject> =
        protectedPost(
            "/api/orders/${android.net.Uri.encode(orderId)}/items/${android.net.Uri.encode(itemId)}/served",
            JSONObject(),
        )

    /** GET /api/notifications?after=<id>: what was missed since the last id received. */
    suspend fun catchUp(afterId: Long): ApiResult<List<AppNotification>> =
        when (val result = protectedGet("/api/notifications?after=$afterId")) {
            is ApiResult.Ok -> ApiResult.Ok(result.value.toNotifications())
            is ApiResult.Err -> result
        }

    /**
     * Opens GET /api/events (Server-Sent Events) and calls [onNotification] for each notification
     * until the stream ends. Blocks, so run it in a coroutine that can be cancelled.
     * Returns Ok when the stream closed or the coroutine was cancelled, and Err when it could not
     * connect or the connection dropped. A 401 means the device was revoked.
     */
    suspend fun streamEvents(
        lastId: Long,
        onOpen: () -> Unit,
        onNotification: (AppNotification) -> Unit,
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val builder = authorisedBuilder("/api/events") ?: return@withContext NOT_PAIRED
        builder.header("Accept", "text/event-stream")
        if (lastId > 0) builder.header("Last-Event-ID", lastId.toString())
        val call = streamHttp.newCall(builder.get().build())
        // Cancelling the coroutine closes the connection, which ends the blocking read below.
        val stopOnCancel = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            val outcome: ApiResult<Unit> = call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    val message = try {
                        JSONObject(text).optString("error")
                    } catch (e: JSONException) {
                        ""
                    }
                    return@use failure(response.code, message)
                }
                onOpen()
                val source = response.body?.source() ?: return@use ApiResult.Ok(Unit)
                var event = ""
                val data = StringBuilder()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            // A blank line ends one event.
                            if ((event.isEmpty() || event == "notification") && data.isNotBlank()) {
                                runCatching { JSONObject(data.toString().trim()).toNotification() }
                                    .getOrNull()
                                    ?.let(onNotification)
                            }
                            event = ""
                            data.setLength(0)
                        }
                        line.startsWith(":") -> Unit // keep-alive comment
                        else -> {
                            val colon = line.indexOf(':')
                            val field = if (colon < 0) line else line.substring(0, colon)
                            val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
                            if (field == "event") event = value
                            else if (field == "data") data.append(value).append('\n')
                        }
                    }
                }
                ApiResult.Ok(Unit)
            }
            outcome
        } catch (e: IOException) {
            if (!isActive) ApiResult.Ok(Unit)
            else {
                Log.w("CaptainApi", "Event stream dropped", e)
                ApiResult.Err("Lost the connection to the desktop. ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            }
        } finally {
            stopOnCancel?.dispose()
        }
    }

    /** POST with the device token and PIN. Use for every protected call. */
    suspend fun protectedPost(
        path: String,
        body: JSONObject,
        pinHeader: String? = Session.pin,
    ): ApiResult<JSONObject> {
        val builder = authorisedBuilder(path, pinHeader) ?: return NOT_PAIRED
        return execute { builder.post(body.toString().toRequestBody(jsonMedia)).build() }
    }

    private fun authorisedBuilder(path: String, pinHeader: String? = Session.pin): Request.Builder? {
        val token = prefs.deviceToken ?: return null
        val builder = Request.Builder()
            .url(endpoint(path))
            .header("Authorization", "Bearer $token")
        pinHeader?.let { builder.header("X-Captain-Pin", it) }
        Session.unlockToken?.let { builder.header("X-Unlock-Token", it) }

        return builder
    }

    private suspend fun execute(build: () -> Request): ApiResult<JSONObject> =
        withContext(Dispatchers.IO) {
            val request = try {
                build()
            } catch (e: IllegalArgumentException) {
                return@withContext ApiResult.Err("The desktop address is not a valid URL.")
            }
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val json = try {
                        JSONObject(text)
                    } catch (e: JSONException) {
                        null
                    }
                    if (response.isSuccessful) {
                        ApiResult.Ok<JSONObject>(json ?: JSONObject())
                    } else {
                        failure(response.code, json?.optString("error").orEmpty())
                    }
                }
            } catch (e: IOException) {
                Log.e("CaptainApi", "Request to ${prefs.serverUrl} failed", e)
                ApiResult.Err(
                    "Cannot reach the desktop at ${prefs.serverUrl}. " +
                            "Cause: ${e.javaClass.simpleName}: ${e.message ?: "no message"}. " +
                            "Check the address and that the desktop app is running on the same Wi-Fi.",
                )
            }
        }

    /**
     * Builds the error for a failed reply. When the waiter was signed in and the desktop asks for
     * the PIN again (idle too long), the login is dropped and the app returns to PIN login.
     * The code becomes [PIN_EXPIRED] so no screen mistakes it for a revoked device (401).
     * PROPOSED: the desktop answers 401, 403 or 423 with a message that mentions the PIN.
     */
    private fun failure(code: Int, serverMessage: String): ApiResult.Err {
        val message = serverMessage.ifBlank { "Desktop returned HTTP $code." }
        val signedIn = Session.pin != null || Session.unlockToken != null
        if (signedIn && code in PIN_ERROR_CODES && PIN_REQUIRED.containsMatchIn(message) &&
            !message.contains("pair", ignoreCase = true)
        ) {
            Session.expire()
            return ApiResult.Err(message, PIN_EXPIRED)
        }
        return ApiResult.Err(message, code)
    }

    companion object {
        const val PIN_EXPIRED = 423
        private val PIN_ERROR_CODES = setOf(401, 403, 423)
        private val PIN_REQUIRED = Regex("\\b(pin|unlock|session)\\b", RegexOption.IGNORE_CASE)

        private val NOT_PAIRED = ApiResult.Err("This phone is not paired yet.", 401)

        /** Names the PIN login response may use for the unlock token, tried in order. */
        private val UNLOCK_TOKEN_FIELDS = listOf("tokenHash", "unlockToken", "token_hash", "token")
    }
}
