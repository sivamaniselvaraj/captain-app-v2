package com.octanovus.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.octanovus.data.ApiClient
import com.octanovus.data.ApiResult
import com.octanovus.data.AppPrefs

data class SplashState(
    /** Saved desktop address, split for display and editing. */
    val ip: String = "",
    val port: String = "5000",
    /** True while the waiter is editing the address. */
    val editing: Boolean = false,
    val ipDraft: String = "",
    val portDraft: String = "",
    val ipError: String? = null,
    val portError: String? = null,
    val checking: Boolean = false,
    val reachable: Boolean = false,
    val statusError: String? = null,
    val pin: String = "",
    val busy: Boolean = false,
    val pinError: String? = null,
    val signedInAs: String? = null,
    /** The desktop no longer accepts this device. The app goes back to pairing. */
    val revoked: Boolean = false,
    val tokenHash: String = "",
)

class SplashViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val api = ApiClient(prefs)

    private val _state = MutableStateFlow(SplashState().withAddress(prefs.serverUrl))
    val state: StateFlow<SplashState> = _state.asStateFlow()

    init {
        checkDesktop()
    }

    // ---- Editing the desktop address after pairing -------------------------------------

    fun startEdit() = _state.update {
        it.copy(editing = true, ipDraft = it.ip, portDraft = it.port, ipError = null, portError = null)
    }

    fun cancelEdit() = _state.update { it.copy(editing = false, ipError = null, portError = null) }

    fun onIpDraftChange(value: String) = _state.update {
        it.copy(ipDraft = value.filter { c -> c.isDigit() || c == '.' }.take(15), ipError = null)
    }

    fun onPortDraftChange(value: String) = _state.update {
        it.copy(portDraft = value.filter(Char::isDigit).take(5), portError = null)
    }

    /** Validates the new address, saves it and checks the desktop again. */
    fun saveAddress() {
        val s = _state.value
        val ipError = validateIp(s.ipDraft)
        val portError = validatePort(s.portDraft)
        if (ipError != null || portError != null) {
            _state.update { it.copy(ipError = ipError, portError = portError) }
            return
        }
        prefs.serverUrl = "http://${s.ipDraft.trim()}:${s.portDraft.trim()}"
        _state.update {
            it.copy(
                ip = it.ipDraft.trim(),
                port = it.portDraft.trim(),
                editing = false,
                ipError = null,
                portError = null,
                pin = "",
                pinError = null,
            )
        }
        checkDesktop()
    }

    // ---- Desktop check ------------------------------------------------------------------

    fun checkDesktop() {
        if (prefs.serverUrl.isBlank()) {
            _state.update {
                it.copy(checking = false, reachable = false, statusError = "Enter the desktop address to connect.")
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(checking = true, statusError = null) }
            val result = api.health()
            _state.update {
                when (result) {
                    is ApiResult.Ok -> it.copy(checking = false, reachable = true, statusError = null)
                    is ApiResult.Err -> it.copy(
                        checking = false,
                        reachable = false,
                        statusError = result.message,
                    )
                }
            }
        }
    }

    /**
     * Asks the desktop whether it still accepts this device. If the device was revoked
     * (401/403), the saved pairing is cleared and the app returns to the pairing screen.
     * Network errors are ignored here: the waiter can still try the PIN.
     */
    private suspend fun verifyDevice() {
        if (prefs.deviceToken == null) return
        val result = api.checkDevice()
        if (result is ApiResult.Err && (result.code == 401 || result.code == 403)) {
            prefs.clearPairing()
            _state.update { it.copy(revoked = true, pin = "") }
        }
    }

    // ---- PIN ----------------------------------------------------------------------------

    fun onDigit(digit: String) {
        _state.update {
            if (it.busy || it.pin.length >= MAX_PIN) it
            else it.copy(pin = it.pin + digit, pinError = null)
        }
    }

    fun onDelete() {
        _state.update { it.copy(pin = it.pin.dropLast(1), pinError = null) }
    }

    fun submitPin() {
        val current = _state.value
        if (current.busy || current.pin.length < MIN_PIN || !current.reachable) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, pinError = null) }
            val result = api.loginWithPin(current.pin)
            _state.update {
                when (result) {
                    is ApiResult.Ok -> it.copy(
                        busy = false,
                        pin = "",
                        tokenHash= result.value.optString("tokenHash"),
                        signedInAs = result.value.optString("userName").ifBlank { "Waiter" },
                    )
                    is ApiResult.Err -> it.copy(
                        busy = false,
                        pin = "",
                        pinError = result.message,
                    )
                }
            }
        }
    }

    companion object {
        const val MIN_PIN = 4
        const val MAX_PIN = 4
    }
}

/** Splits a saved address such as http://192.168.1.20:5000 into IP and port. */
private fun SplashState.withAddress(url: String): SplashState {
    val match = Regex("""^https?://([^:/]+):(\d{1,5})/?$""").matchEntire(url.trim()) ?: return this
    return copy(ip = match.groupValues[1], port = match.groupValues[2])
}
