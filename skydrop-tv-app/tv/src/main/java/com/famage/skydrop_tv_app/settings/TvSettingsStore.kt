package com.famage.skydrop_tv_app.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.famage.skydrop_tv_app.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings Store aligned strictly with SkyDrop Android architecture.
 * Default receiver port is 8444 (Android receiver convention).
 */
class TvSettingsStore(
    private val prefs: SharedPreferences
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _isReceiverActive = MutableStateFlow(prefs.getBoolean(KEY_RECEIVER_ACTIVE, true))
    val isReceiverActive: StateFlow<Boolean> = _isReceiverActive.asStateFlow()

    private val _listeningPort = MutableStateFlow(prefs.getInt(KEY_PORT, 8444))
    val listeningPort: StateFlow<Int> = _listeningPort.asStateFlow()

    private val _saveLocation = MutableStateFlow(
        prefs.getString(KEY_SAVE_LOCATION, null) ?: "Downloads/SkyDrop"
    )
    val saveLocation: StateFlow<String> = _saveLocation.asStateFlow()

    private val _themePreference = MutableStateFlow(loadThemePreference())
    val themePreference: StateFlow<ThemePreference> = _themePreference.asStateFlow()

    fun setReceiverActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_RECEIVER_ACTIVE, active).apply()
        _isReceiverActive.value = active
    }

    fun setThemePreference(pref: ThemePreference) {
        prefs.edit().putString(KEY_THEME_PREFERENCE, pref.name).apply()
        _themePreference.value = pref
    }

    private fun loadThemePreference(): ThemePreference {
        val stored = prefs.getString(KEY_THEME_PREFERENCE, null) ?: return ThemePreference.SYSTEM
        return try {
            ThemePreference.valueOf(stored)
        } catch (_: IllegalArgumentException) {
            ThemePreference.SYSTEM
        }
    }

    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return "$manufacturer $model"
    }

    companion object {
        private const val PREFS_NAME = "skydrop_tv_settings_prefs"
        private const val KEY_RECEIVER_ACTIVE = "receiver_active"
        private const val KEY_PORT = "daemon_port"
        private const val KEY_SAVE_LOCATION = "save_location"
        private const val KEY_THEME_PREFERENCE = "theme_preference"

        @Volatile
        private var INSTANCE: TvSettingsStore? = null

        fun getInstance(context: Context): TvSettingsStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TvSettingsStore(context).also { INSTANCE = it }
            }
    }
}
