package org.scyllasband.tts

import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

private val Context.voiceDataStore by preferencesDataStore(name = "voice_pref")

class VoicePreference(private val context: Context) {
    private val key = stringPreferencesKey("preferred_voice")

    suspend fun get(): String? = context.voiceDataStore.data.first()[key]

    suspend fun set(voiceId: String) {
        context.voiceDataStore.edit { it[key] = voiceId }
    }
}
