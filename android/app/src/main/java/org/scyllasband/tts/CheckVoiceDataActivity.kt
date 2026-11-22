package org.scyllasband.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Answers Android's ACTION_CHECK_TTS_DATA so the system TTS settings page can
 *  populate its language list and enable the Play button and rate/pitch sliders.
 *  Voice data is bundled/downloaded by our app, so from the framework's view all
 *  advertised locales are always available. */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AOSP's evaluateDefaultLocale() builds the engine locale as ISO-3
        // (e.g. "eng-GBR" from onGetLanguage()'s "en"/"GB") and requires one of
        // these strings to match exactly. Return ISO-3, not BCP-47.
        val locales = VoiceCatalog.androidVoices().map { voice ->
            val l = voice.locale
            val lang = try { l.getISO3Language() } catch (e: Exception) { l.language }
            val country = try { l.getISO3Country() } catch (e: Exception) { l.country }
            if (country.isNotBlank()) "$lang-$country" else lang
        }.distinct().sorted()
        val data = Intent().putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
            ArrayList(locales),
        )
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, data)
        finish()
    }
}
