package com.translander.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val SERVICE_ENABLED_KEY = booleanPreferencesKey("service_enabled")
        private val THEME_KEY = stringPreferencesKey("theme_mode")
        private val BUTTON_X_KEY = intPreferencesKey("button_x")
        private val BUTTON_Y_KEY = intPreferencesKey("button_y")
        private val AUTO_LOAD_MODEL_KEY = booleanPreferencesKey("auto_load_model")
        private val DICTIONARY_ENABLED_KEY = booleanPreferencesKey("dictionary_enabled")
        private val AUDIO_MONITOR_ENABLED_KEY = booleanPreferencesKey("audio_monitor_enabled")
        private val MONITORED_FOLDERS_KEY = stringSetPreferencesKey("monitored_folders")
        private val FLOATING_BUTTON_SIZE_KEY = stringPreferencesKey("floating_button_size")

        const val BUTTON_SIZE_SMALL = "small"   // 44dp
        const val BUTTON_SIZE_MEDIUM = "medium" // 56dp (default)
        const val BUTTON_SIZE_LARGE = "large"   // 72dp
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }

    val serviceEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SERVICE_ENABLED_KEY] ?: false
    }

    val themeMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_KEY] ?: THEME_SYSTEM
    }

    val buttonPosition: Flow<Pair<Int, Int>> = context.dataStore.data.map { preferences ->
        val x = preferences[BUTTON_X_KEY] ?: -1
        val y = preferences[BUTTON_Y_KEY] ?: -1
        Pair(x, y)
    }

    val autoLoadModel: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_LOAD_MODEL_KEY] ?: false
    }

    val dictionaryEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DICTIONARY_ENABLED_KEY] ?: true
    }

    val audioMonitorEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUDIO_MONITOR_ENABLED_KEY] ?: false
    }

    val monitoredFolders: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[MONITORED_FOLDERS_KEY] ?: emptySet()
    }

    val floatingButtonSize: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[FLOATING_BUTTON_SIZE_KEY] ?: BUTTON_SIZE_MEDIUM
    }

    suspend fun setFloatingButtonSize(size: String) {
        context.dataStore.edit { preferences ->
            preferences[FLOATING_BUTTON_SIZE_KEY] = size
        }
    }

    suspend fun setDictionaryEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DICTIONARY_ENABLED_KEY] = enabled
        }
    }

    suspend fun setAudioMonitorEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUDIO_MONITOR_ENABLED_KEY] = enabled
        }
    }

    suspend fun setMonitoredFolders(folders: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[MONITORED_FOLDERS_KEY] = folders
        }
    }

    suspend fun setAutoLoadModel(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_LOAD_MODEL_KEY] = enabled
        }
    }

    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SERVICE_ENABLED_KEY] = enabled
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
}
