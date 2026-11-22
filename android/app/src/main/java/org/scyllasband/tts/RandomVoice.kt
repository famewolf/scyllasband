package org.scyllasband.tts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.random.Random

private val Context.randomVoiceDataStore by preferencesDataStore(name = "random_voice_pref")

/**
 * Picks a real voice for the "random" pseudo-voice. Cooldown and exclusions are
 * persisted so they survive process restart. The clock and RNG are injectable
 * for deterministic tests.
 */
class RandomVoice(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val rng: Random = Random.Default,
) {
    companion object {
        const val COOLDOWN_MS = 5 * 60 * 1000L
        const val ID = "random"
    }

    private val lastVoiceKey = stringPreferencesKey("last_voice")
    private val lastAtKey = longPreferencesKey("last_at")
    private val excludedKey = stringSetPreferencesKey("excluded")

    /** Voice id that will actually speak for the given locale. */
    suspend fun pick(locale: Locale?): String {
        val prefs = context.randomVoiceDataStore.data.first()
        val last = prefs[lastVoiceKey]
        val lastAt = prefs[lastAtKey] ?: 0L
        val excluded = prefs[excludedKey] ?: emptySet()
        var allowed = VoiceCatalog.allVoiceIds - excluded
        if (allowed.isEmpty()) allowed = VoiceCatalog.allVoiceIds
        val now = clock()

        if (last != null && now - lastAt < COOLDOWN_MS && last in allowed &&
            supportsLocale(last, locale)) {
            return last
        }

        val candidates = allowed.filter { supportsLocale(it, locale) }.ifEmpty { allowed.toList() }
        val chosen = candidates[rng.nextInt(candidates.size)]
        context.randomVoiceDataStore.edit { p ->
            p[lastVoiceKey] = chosen
            p[lastAtKey] = now
        }
        return chosen
    }

    suspend fun lastUsed(): Pair<String, Long>? {
        val prefs = context.randomVoiceDataStore.data.first()
        val voice = prefs[lastVoiceKey] ?: return null
        return voice to (prefs[lastAtKey] ?: 0L)
    }

    /** Clears the last-pick marker (used after the demo so it does not consume the cooldown). */
    suspend fun clearLast() {
        context.randomVoiceDataStore.edit { p ->
            p.remove(lastVoiceKey)
            p.remove(lastAtKey)
        }
    }

    suspend fun excluded(): Set<String> =
        context.randomVoiceDataStore.data.first()[excludedKey] ?: emptySet()

    suspend fun setExcluded(voices: Set<String>) {
        context.randomVoiceDataStore.edit { it[excludedKey] = voices }
    }

    private fun supportsLocale(voice: String, locale: Locale?): Boolean {
        if (locale == null) return true
        val lang = locale.toString().replace('-', '_').lowercase()
        return try {
            VoiceCatalog.languageFor(voice, locale)
            true
        } catch (e: UnsupportedLocaleException) {
            false
        }
    }
}
