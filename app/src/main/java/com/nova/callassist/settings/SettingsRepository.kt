package com.nova.callassist.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nova_settings")

/**
 * Manages user preferences locally via Jetpack DataStore.
 * Zero remote telemetry, zero cloud storage.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val KEY_DIRECT_CALL_ENABLED = booleanPreferencesKey("direct_call_enabled")
        val KEY_VOICE_RESPONSES_ENABLED = booleanPreferencesKey("voice_responses_enabled")
        val KEY_CONFIRMATION_REQUIRED = booleanPreferencesKey("confirmation_required")
        val KEY_MODEL_LANGUAGE = stringPreferencesKey("model_language")
        val KEY_CPU_THREADS = intPreferencesKey("cpu_threads")
    }

    private val dataStore = context.dataStore

    val cpuThreads: Flow<Int> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[KEY_CPU_THREADS] ?: 4
        }

    val wakeWordEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[KEY_WAKE_WORD_ENABLED] ?: false
        }

    val directCallingEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[KEY_DIRECT_CALL_ENABLED] ?: false
        }

    val voiceResponsesEnabled: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[KEY_VOICE_RESPONSES_ENABLED] ?: true
        }

    val confirmationRequired: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[KEY_CONFIRMATION_REQUIRED] ?: true
        }

    val modelLanguage: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[KEY_MODEL_LANGUAGE] ?: "en-us"
        }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_WAKE_WORD_ENABLED] = enabled
        }
    }

    suspend fun setDirectCallingEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_DIRECT_CALL_ENABLED] = enabled
        }
    }

    suspend fun setVoiceResponsesEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_VOICE_RESPONSES_ENABLED] = enabled
        }
    }

    suspend fun setConfirmationRequired(required: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_CONFIRMATION_REQUIRED] = required
        }
    }

    suspend fun setModelLanguage(language: String) {
        dataStore.edit { preferences ->
            preferences[KEY_MODEL_LANGUAGE] = language
        }
    }

    suspend fun setCpuThreads(threads: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_CPU_THREADS] = threads.coerceIn(1, 8)
        }
    }
}
