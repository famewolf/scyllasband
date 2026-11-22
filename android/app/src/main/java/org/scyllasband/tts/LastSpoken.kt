package org.scyllasband.tts

import android.content.Context

/** Last voice that actually spoke, from any source (local synthesis, sample,
 *  system TTS). Displayed by SettingsActivity's "Last spoken by" line. */
object LastSpoken {
    private const val PREF = "last_spoken"
    private const val KEY = "voice"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)

    fun set(context: Context, voice: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, voice).apply()
    }
}
