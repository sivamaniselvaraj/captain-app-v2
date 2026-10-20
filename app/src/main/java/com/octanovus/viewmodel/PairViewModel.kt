package com.octanovus.viewmodel

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octanovus.data.ApiClient
import com.octanovus.data.ApiResult
import com.octanovus.data.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pairing has two steps so the keyboard never covers the fields:
 *   step 1: desktop IP, port and the 6-digit code
 *   step 2: waiter email and password
 * The device name is read-only. It is the phone model plus a short phone ID.
 */
data class PairState(
    val step: Int = 1,
    val ip: String = "",
    val port: String = "5000",
    val code: String = "",
    val deviceName: String = "",
    val email: String = "",
    val password: String = "",
    val ipError: String? = null,
    val portError: String? = null,
    val codeError: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val paired: Boolean = false,
) {
    val serverUrl: String get() = "http://${ip.trim()}:${port.trim()}"
    val canGoNext: Boolean get() = ip.isNotBlank() && port.isNotBlank() && code.length == 6
    val canPair: Boolean get() = !busy && email.isNotBlank() && password.isNotEmpty()
}

internal fun validateIp(ip: String): String? {
    val value = ip.trim()
    if (value == "0.0.0.0") return "Use the desktop's Wi-Fi IP, for example 192.168.1.20."
    val parts = value.split('.')
    val valid = parts.size == 4 && parts.all {
        it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255
    }
    return if (valid) null else "Enter the IP as four numbers, like 192.168.1.20."
}

internal fun validatePort(port: String): String? {
    val number = port.trim().toIntOrNull()
    return if (number != null && number in 1..65535) null else "Enter a port from 1 to 65535."
}

class PairViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val api = ApiClient(prefs)

    private val _state = MutableStateFlow(initialState(app))
    val state: StateFlow<PairState> = _state.asStateFlow()

    fun onIpChange(value: String) = _state.update {
        it.copy(ip = value.filter { c -> c.isDigit() || c == '.' }.take(15), ipError = null, error = null)
    }

    fun onPortChange(value: String) = _state.update {
        it.copy(port = value.filter(Char::isDigit).take(5), portError = null, error = null)
    }

    fun onCodeChange(value: String) = _state.update {
        it.copy(code = value.filter(Char::isDigit).take(6), codeError = null, error = null)
    }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    /** Step 1 to step 2, after checking the IP, port and code. */
    fun next() {
        val s = _state.value
        if (s.busy) return
        val ipError = validateIp(s.ip)
        val portError = validatePort(s.port)
        val codeError = if (s.code.length == 6) null else "Enter the 6-digit code."
        if (ipError != null || portError != null || codeError != null) {
            _state.update { it.copy(ipError = ipError, portError = portError, codeError = codeError) }
            return
        }
        _state.update { it.copy(step = 2, error = null) }
    }

    fun back() {
        if (_state.value.busy) return
        _state.update { it.copy(step = 1, error = null) }
    }

    fun pair() {
        val s = _state.value
        if (!s.canPair) return

        // Check step 1 again, in case a field changed.
        val ipError = validateIp(s.ip)
        val portError = validatePort(s.port)
        if (ipError != null || portError != null || s.code.length != 6) {
            _state.update { it.copy(step = 1, ipError = ipError, portError = portError) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            prefs.serverUrl = s.serverUrl

            // The desktop checks the waiter's login and the code, then returns the device token.
            when (val result = api.pair(s.code, s.deviceName, s.email.trim(), s.password)) {
                is ApiResult.Ok -> {
                    val token = result.value.optString("token")
                    if (token.isBlank()) {
                        _state.update {
                            it.copy(busy = false, password = "", error = "Pairing did not return a device token.")
                        }
                        return@launch
                    }
                    prefs.deviceToken = token
                    prefs.pairedUserName = result.value.optString("userName")
                    _state.update { it.copy(busy = false, password = "", paired = true) }
                }
                is ApiResult.Err -> _state.update {
                    // 401 means the email or password was refused: stay on step 2.
                    // Anything else (code, address, device name, network) is fixed on step 1.
                    it.copy(
                        busy = false,
                        password = "",
                        step = if (result.code == 401) 2 else 1,
                        error = result.message,
                    )
                }
            }
        }
    }

    private fun initialState(app: Application): PairState {
        val match = Regex("""^https?://([^:/]+):(\d{1,5})/?$""").matchEntire(prefs.serverUrl.trim())
        val savedIp = match?.groupValues?.get(1).orEmpty()
        return PairState(
            ip = if (savedIp == "0.0.0.0") "" else savedIp,
            port = match?.groupValues?.get(2) ?: "5000",
            deviceName = defaultDeviceName(app),
        )
    }

    /** Phone model plus the last 4 characters of the phone ID, for example "Pixel 7 · a3f9". */
    private fun defaultDeviceName(app: Application): String {
        val androidId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val suffix = androidId.takeLast(4)
        val model = Build.MODEL.ifBlank { "Android phone" }
        val name = if (suffix.isBlank()) model else "$model · $suffix"
        return name.take(60)
    }
}
