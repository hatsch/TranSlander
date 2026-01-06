package com.voicekeyboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val SERVICE_ENABLED_KEY = booleanPreferencesKey("service_enabled")
        private val LANGUAGE_KEY = stringPreferencesKey("preferred_language")
        private val THEME_KEY = stringPreferencesKey("theme_mode")
        private val BUTTON_X_KEY = intPreferencesKey("button_x")
        private val BUTTON_Y_KEY = intPreferencesKey("button_y")

        const val LANGUAGE_AUTO = "auto"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }

    val serviceEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SERVICE_ENABLED_KEY] ?: false
    }

    val preferredLanguage: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: LANGUAGE_AUTO
    }

    val themeMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_KEY] ?: THEME_SYSTEM
    }

    val buttonPosition: Flow<Pair<Int, Int>> = context.dataStore.data.map { preferences ->
        val x = preferences[BUTTON_X_KEY] ?: -1
        val y = preferences[BUTTON_Y_KEY] ?: -1
        Pair(x, y)
    }

    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SERVICE_ENABLED_KEY] = enabled
        }
    }

    suspend fun setPreferredLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language
        }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = mode
        }
    }

    suspend fun setButtonPosition(x: Int, y: Int) {
        context.dataStore.edit { preferences ->
            preferences[BUTTON_X_KEY] = x
            preferences[BUTTON_Y_KEY] = y
        }
    }

    data class Language(
        val code: String,
        val displayName: String
    )

    fun getSupportedLanguages(): List<Language> = listOf(
        Language("auto", "Auto-detect"),
        Language("en", "English"),
        Language("de", "German"),
        Language("fr", "French"),
        Language("es", "Spanish"),
        Language("it", "Italian"),
        Language("pt", "Portuguese"),
        Language("nl", "Dutch"),
        Language("pl", "Polish"),
        Language("ru", "Russian"),
        Language("uk", "Ukrainian"),
        Language("cs", "Czech"),
        Language("sk", "Slovak"),
        Language("hu", "Hungarian"),
        Language("ro", "Romanian"),
        Language("bg", "Bulgarian"),
        Language("hr", "Croatian"),
        Language("sl", "Slovenian"),
        Language("el", "Greek"),
        Language("da", "Danish"),
        Language("sv", "Swedish"),
        Language("fi", "Finnish"),
        Language("et", "Estonian"),
        Language("lv", "Latvian"),
        Language("lt", "Lithuanian"),
        Language("mt", "Maltese")
    )
}
