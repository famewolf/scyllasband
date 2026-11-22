package org.scyllasband.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** Answers Android's ACTION_GET_SAMPLE_TEXT, which the system TTS Settings page
 *  fires to obtain the text for its Play button. Without this activity the page
 *  throws ActivityNotFoundException, leaves mSampleText null, and the Play
 *  button does nothing (and its widgets stay disabled). */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val country = intent?.getStringExtra("country") ?: ""
        // A short, language-appropriate sample for the system Play button.
        val sample = if (country.uppercase() == "GB" || country.uppercase() == "GBR") {
            "Scylla's Band is ready to speak."
        } else {
            "Scylla's Band is ready to speak."
        }
        val data = Intent().putExtra("sampleText", sample)
        setResult(TextToSpeech.LANG_AVAILABLE, data)
        finish()
    }
}
