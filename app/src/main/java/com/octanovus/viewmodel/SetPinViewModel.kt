package com.octanovus.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octanovus.data.ApiClient
import com.octanovus.data.ApiResult
import com.octanovus.data.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * step 1: enter a new 4-digit PIN.
 * step 2: enter it again to confirm.
 */
data class SetPinState(
    val step: Int = 1,
    val first: String = "",
    val confirm: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

class SetPinViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val api = ApiClient(prefs)

    private val _state = MutableStateFlow(SetPinState())
    val state: StateFlow<SetPinState> = _state.asStateFlow()

    fun onDigit(digit: String) {
        val s = _state.value
        if (s.busy || s.done) return

        if (s.step == 1) {
            if (s.first.length >= PIN_LENGTH) return
            val first = s.first + digit
            _state.value = s.copy(
                first = first,
                step = if (first.length == PIN_LENGTH) 2 else 1,
                error = null,
            )
            return
        }

        if (s.confirm.length >= PIN_LENGTH) return
        val confirm = s.confirm + digit
        if (confirm.length < PIN_LENGTH) {
            _state.value = s.copy(confirm = confirm, error = null)
            return
        }
        if (confirm != s.first) {
            _state.value = s.copy(confirm = "", error = "The PINs do not match. Try again.")
            return
        }
        save(confirm)
    }

    fun onDelete() {
        val s = _state.value
        if (s.busy || s.done) return
        _state.value = when {
            s.step == 2 && s.confirm.isNotEmpty() -> s.copy(confirm = s.confirm.dropLast(1), error = null)
            s.step == 2 -> s.copy(step = 1, first = s.first.dropLast(1), error = null)
            else -> s.copy(first = s.first.dropLast(1), error = null)
        }
    }

    private fun save(pin: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            when (val result = api.setPin(pin)) {
                is ApiResult.Ok -> {
                    prefs.pinSet = true
                    _state.value = _state.value.copy(busy = false, done = true)
                }

                is ApiResult.Err -> _state.value = _state.value.copy(
                    busy = false,
                    confirm = "",
                    error = result.message,
                )
            }
        }
    }

    companion object {
        const val PIN_LENGTH = 4
    }
}
