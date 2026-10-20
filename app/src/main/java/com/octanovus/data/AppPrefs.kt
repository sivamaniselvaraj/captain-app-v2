package com.octanovus.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit

/**
 * Local settings.
 * - serverUrl: plain preferences (not secret).
 * - deviceToken: encrypted preferences (secret, shown once by the desktop at pairing).
 */
class AppPrefs(context: Context) {

    private val plain: SharedPreferences =
        context.getSharedPreferences("captain_settings", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "captain_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var serverUrl: String
        get() = plain.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = plain.edit { putString(KEY_SERVER_URL, value) }

    var deviceToken: String?
        get() = secure.getString(KEY_DEVICE_TOKEN, null)
        set(value) = secure.edit { putString(KEY_DEVICE_TOKEN, value) }

    /** Display name of the waiter the device is paired to (shown on the PIN screen). */
    var pairedUserName: String?
        get() = plain.getString("paired_user_name", null)
        set(value) = plain.edit { putString("paired_user_name", value) }

    /** True once the waiter has set their 4-digit PIN on this device. */
    var pinSet: Boolean
        get() = plain.getBoolean("pin_set", false)
        set(value) = plain.edit { putBoolean("pin_set", value) }

    /**
     * Forgets this pairing: token, PIN flag and waiter name. The desktop address stays,
     * so the pairing screen is pre-filled when the waiter pairs again.
     */
    fun clearPairing() {
        secure.edit { remove(KEY_DEVICE_TOKEN) }
        plain.edit { remove("paired_user_name").putBoolean("pin_set", false) }
        Session.pin = null
    }

    companion object {
        // Empty on first launch: the waiter types the desktop's LAN IP,
        // for example http://192.168.1.20:5000 (find it with `ipconfig` / `ip addr`).
        const val DEFAULT_SERVER_URL = ""

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }
}
