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
     * PROPOSED: POST /api/pin/verify { "pin" } with the device token.
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
                        val serverMessage = json?.optString("error").orEmpty()
                        ApiResult.Err(
                            serverMessage.ifBlank { "Desktop returned HTTP ${response.code}." },
                            response.code,
                        )
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
                        val serverMessage = json?.optString("error").orEmpty()
                        ApiResult.Err(
                            serverMessage.ifBlank { "Desktop returned HTTP ${response.code}." },
                            response.code,
                        )
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

    companion object {
        private val NOT_PAIRED = ApiResult.Err("This phone is not paired yet.", 401)

        /** Names the PIN login response may use for the unlock token, tried in order. */
        private val UNLOCK_TOKEN_FIELDS = listOf("tokenHash", "unlockToken", "token_hash", "token")
    }
}
