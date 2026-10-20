package com.octanovus.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.octanovus.data.ApiClient
import com.octanovus.data.ApiResult
import com.octanovus.data.AppPrefs

import com.octanovus.data.MenuItem
import com.octanovus.data.toMenu
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MenuState(
    val items: List<MenuItem> = emptyList(),
    val selectedCategory: String? = null,
    val query: String = "",
    val loaded: Boolean = false,
    /** The desktop's last menu refresh failed, so the list may be out of date. */
    val stale: Boolean = false,
    val error: String? = null,
    /** The desktop rejected the device token. The app goes back to pairing. */
    val unauthorised: Boolean = false,
) {
    val categories: List<String> get() = items.map { it.categoryName }.distinct()
    /** The chosen category, or [ALL_CATEGORIES]. Shows every item until a category is chosen. */
    val activeCategory: String get() = selectedCategory?.takeIf { it in categories } ?: ALL_CATEGORIES
    val searching: Boolean get() = query.isNotBlank()

    /** True when items from several categories are listed, so each group gets a heading. */
    val groupedByCategory: Boolean get() = searching || activeCategory == ALL_CATEGORIES

    /** A search looks through every category. Otherwise the chosen category (or all) shows. */
    val visibleItems: List<MenuItem>
        get() = when {
            searching -> items.filter {
                it.name.contains(
                    query.trim(),
                    ignoreCase = true
                ) || it.searchKey.contains(query.trim(), ignoreCase = true)
            }

            activeCategory == ALL_CATEGORIES -> items
            else -> items.filter { it.categoryName == activeCategory }
        }

    companion object {
        /** Stands for "all categories". Not a real category name. */
        const val ALL_CATEGORIES = "\u0000all"
    }
}

/** Loads the outlet menu from the desktop and refreshes it every minute while the screen is open. */
class MenuViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val api = ApiClient(prefs)

    private var etag: String? = null

    private val _state = MutableStateFlow(MenuState())
    val state: StateFlow<MenuState> = _state.asStateFlow()

    fun selectCategory(category: String) = _state.update { it.copy(selectedCategory = category) }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    /** Runs until the calling coroutine is cancelled (when the screen is hidden). */
    suspend fun pollLoop() {
        while (true) {
            delay(refresh())
        }
    }

    /** One refresh. Returns how many milliseconds to wait before the next one. */
    suspend fun refresh(): Long =
        when (val result = api.getIfChanged("/api/menu-items", etag)) {
            is ApiResult.Ok -> {
                val reply = result.value
                etag = reply.etag
                val menu = reply.json?.toMenu()
                _state.update {
                    it.copy(
                        // 304: keep the list we have.
                        items = menu?.items ?: it.items,
                        stale = if (menu != null) menu.lastError != null else it.stale,
                        loaded = true,
                        error = null,
                    )
                }
                REFRESH_MS
            }

            is ApiResult.Err -> {
                if (result.code == 401) {
                    prefs.clearPairing()
                    _state.update { it.copy(unauthorised = true) }
                } else {
                    val message = when (result.code) {
                        429 -> "Too many requests. Slowing down."
                        503 -> "Desktop app is not ready."
                        500 -> "Something went wrong on the desktop."
                        else -> result.message
                    }
                    _state.update { it.copy(error = message) }
                }
                // Try again sooner when there is nothing to show yet.
                if (_state.value.loaded) REFRESH_MS else RETRY_MS
            }
        }

    companion object {
        private const val REFRESH_MS = 60_000L
        private const val RETRY_MS = 5_000L
    }
}
