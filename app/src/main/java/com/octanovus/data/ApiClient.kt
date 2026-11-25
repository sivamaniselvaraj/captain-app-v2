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
 *   X-Unlock-Token: <unlockToken>              (from /api/pin/verify, once the PIN is entered)
 * The PIN itself is sent only to the /api/pin/(* calls.
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

    /**
     * GET /api/health. With the device token the reply also has `database` and `mode`
     * ("online" or "offline"). No PIN unlock is needed. Without a token (pairing) it is a plain check.
     */
    suspend fun health(): ApiResult<JSONObject> {
        val builder = authorisedBuilder("/api/health", unlocked = false)
        if (builder != null) {
            val result = execute { builder.get().build() }
            // A rejected token must not make the address look unreachable: ask again without it.
            if (!(result is ApiResult.Err && result.code == 401)) return result
        }
        return execute { Request.Builder().url(endpoint("/api/health")).get().build() }
    }

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
        return execute {
            Request.Builder()
                .url(endpoint("/api/signin"))
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
        }
    }

    /** What the desktop says about this phone's PIN. */
    data class PinStatus(val pinSet: Boolean, val required: Boolean)

    /**
     * GET /api/pin/status with the device token only. Used on the splash screen to find out whether
     * the desktop still accepts this device (401 means revoked) and whether a PIN exists yet.
     */
    suspend fun pinStatus(): ApiResult<PinStatus> {
        val builder = authorisedBuilder("/api/pin/status", unlocked = false) ?: return NOT_PAIRED
        return when (val result = execute { builder.get().build() }) {
            is ApiResult.Ok -> ApiResult.Ok(
                PinStatus(
                    pinSet = result.value.optBoolean("pinSet", true),
                    required = result.value.optBoolean("required", true),
                )
            )
            is ApiResult.Err -> result
        }
    }

    /** POST /api/pin/set { "pin" }: the first PIN. 400 PIN_WEAK, 409 PIN_ALREADY_SET. */
    suspend fun setPin(pin: String): ApiResult<JSONObject> =
        pinCall("/api/pin/set", JSONObject().put("pin", pin))

    /**
     * POST /api/pin/verify { "pin" }. On success the `unlockToken` is kept in memory and sent as
     * `X-Unlock-Token` on every later call. The PIN itself is not kept.
     */
    suspend fun loginWithPin(pin: String): ApiResult<JSONObject> {
        val result = pinCall("/api/pin/verify", JSONObject().put("pin", pin))
        if (result is ApiResult.Ok) {
            val token = result.value.optString("unlockToken")
            if (token.isBlank()) Log.w("CaptainApi", "PIN verify response has no unlockToken")
            Session.unlockToken = token.ifBlank { null }
        }
        return result
    }

    /** POST /api/pin/lock: ends this phone's unlock on the desktop. */
    suspend fun lock(): ApiResult<JSONObject> = pinCall("/api/pin/lock", JSONObject())

    /** POST /api/signout: revokes this phone's token on the desktop. Delete the token locally afterwards. */
    suspend fun signOut(): ApiResult<JSONObject> {
        val builder = authorisedBuilder("/api/signout", unlocked = false) ?: return NOT_PAIRED
        return execute { builder.post("{}".toRequestBody(jsonMedia)).build() }
    }

    /** GET /api/me: currency, locale and tax label. Needs the PIN unlock. */
    suspend fun me(): ApiResult<JSONObject> = protectedGet("/api/me")

    /** The /api/pin/ * calls need the device token but not the unlock token. */
    private suspend fun pinCall(path: String, body: JSONObject): ApiResult<JSONObject> {
        val builder = authorisedBuilder(path, unlocked = false) ?: return NOT_PAIRED
        return execute { builder.post(body.toString().toRequestBody(jsonMedia)).build() }
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
                        failure(response.code, json, response.header("Retry-After"))
                    }
                }
            } catch (e: IOException) {
                Log.e("CaptainApi", "Request to ${prefs.serverUrl} failed", e)
                ApiResult.Err(unreachableMessage(e))
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
        onChanged: (List<String>) -> Unit = {},
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        // live=1 asks for `event: changed` messages. An older desktop ignores it.
        val builder = authorisedBuilder("/api/events?live=1") ?: return@withContext NOT_PAIRED
        builder.header("Accept", "text/event-stream")
        if (lastId > 0) builder.header("Last-Event-ID", lastId.toString())
        val call = streamHttp.newCall(builder.get().build())
        // Cancelling the coroutine closes the connection, which ends the blocking read below.
        val stopOnCancel = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            val outcome: ApiResult<Unit> = call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    val json = try {
                        JSONObject(text)
                    } catch (e: JSONException) {
                        null
                    }
                    return@use failure(response.code, json, response.header("Retry-After"))
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
                            if (event == "changed") {
                                // {"scopes":["orders","tables"],"at":"..."}. No id: never touches lastId.
                                runCatching {
                                    val scopes = JSONObject(data.toString().trim()).optJSONArray("scopes")
                                    List(scopes?.length() ?: 0) { scopes!!.optString(it) }
                                }.getOrNull()?.let(onChanged)
                            } else if ((event.isEmpty() || event == "notification") && data.isNotBlank()) {
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
    ): ApiResult<JSONObject> {
        val builder = authorisedBuilder(path) ?: return NOT_PAIRED
        return execute { builder.post(body.toString().toRequestBody(jsonMedia)).build() }
    }

    private fun authorisedBuilder(path: String, unlocked: Boolean = true): Request.Builder? {
        val token = prefs.deviceToken ?: return null
        val builder = Request.Builder()
            .url(endpoint(path))
            .header("Authorization", "Bearer $token")
        if (unlocked) Session.unlockToken?.let { builder.header("X-Unlock-Token", it) }
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
                        failure(response.code, json, response.header("Retry-After"))
                    }
                }
            } catch (e: IOException) {
                Log.e("CaptainApi", "Request to ${prefs.serverUrl} failed", e)
                ApiResult.Err(unreachableMessage(e))
            }
        }

    /**
     * Plain-language message for a failed connection. The technical cause stays in the log.
     */
    private fun unreachableMessage(e: IOException): String =
        if (e is java.net.SocketTimeoutException) {
            "The restaurant system is slow to respond. Please try again."
        } else {
            "Can't connect to the restaurant system. Ask your manager to check that the desktop app is running."
        }

    /**
     * Builds the error for a failed reply. The desktop's `code`, `attemptsLeft` and
     * `retryAfterSeconds` are kept. When the waiter is signed in and the desktop asks for the PIN
     * again (423 PIN_REQUIRED or PIN_NOT_SET), the login is dropped and the app returns to the PIN
     * keypad. The code becomes [PIN_EXPIRED] so no screen mistakes it for a revoked device (401).
     */
    private fun failure(code: Int, json: JSONObject?, retryAfterHeader: String? = null): ApiResult.Err {
        val message = json?.optString("error").orEmpty().ifBlank { "Desktop returned HTTP $code." }
        val apiCode = json?.optString("code")?.takeIf { it.isNotBlank() && it != "null" }
        val retryAfter = json?.optInt("retryAfterSeconds", -1)?.takeIf { it >= 0 }
            ?: retryAfterHeader?.trim()?.toIntOrNull()
        val attemptsLeft = json?.optInt("attemptsLeft", -1)?.takeIf { it >= 0 }
        val signedIn = Session.unlockToken != null
        if (signedIn && code == 423 && (apiCode == "PIN_REQUIRED" || apiCode == "PIN_NOT_SET")) {
            Session.expire()
            return ApiResult.Err(message, PIN_EXPIRED, apiCode)
        }
        return ApiResult.Err(message, code, apiCode, retryAfter, attemptsLeft)
    }

    companion object {
        const val PIN_EXPIRED = 423

        private val NOT_PAIRED = ApiResult.Err("This phone is not paired yet.", 401)
    }
}
