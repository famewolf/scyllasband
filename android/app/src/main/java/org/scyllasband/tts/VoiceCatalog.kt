package org.scyllasband.tts

import android.speech.tts.Voice
import org.scyllasband.android.ScyllasBandBundleInfo
import java.util.Locale

class UnsupportedLocaleException(message: String) : Exception(message)

object VoiceCatalog {
    // Voice/locale pairs from the v4 release manifest. Kept here so Android can
    // see voices the moment the service binds, before the model finishes loading.
    private val VOICES: Map<String, List<String>> = mapOf(
        "ariadne" to listOf("en_us", "de", "es", "fr", "it", "vi"),
        "felix" to listOf("en_us", "de", "es", "fr", "it", "vi"),
        "gwen" to listOf("en_us", "de", "es", "fr", "it", "vi"),
        "kincade" to listOf("en_us", "de", "es", "fr", "it", "vi"),
        "max" to listOf("en_us", "de", "es", "fr", "it", "vi"),
        "poppy" to listOf("en_us", "de", "es", "fr", "it", "vi"),
        "priya" to listOf("en_us", "de", "es", "fr", "it", "vi"),
        "rex" to listOf("en_us", "de", "es", "fr", "it", "vi"),
        "scylla" to listOf("en_us", "de", "es", "fr", "it", "vi"),
        "stone" to listOf("en_us", "de", "es", "fr", "it", "vi"),
        "thistle" to listOf("en_us", "de", "es", "fr", "it", "vi"),
        "ink" to listOf("en_gb", "de", "es", "fr", "it", "vi"),
        "orpheus" to listOf("en_gb", "de", "es", "fr", "it", "vi"),
        "tuesday" to listOf("en_gb", "de", "es", "fr", "it", "vi"),
        RandomVoice.ID to listOf("en_us"),
    )
    val DEFAULT_VOICE = "orpheus"
    val allVoiceIds: Set<String> get() = VOICES.keys

    /** One Android Voice per voice/locale pair — instant, no model required. */
    fun androidVoices(): Set<Voice> = VOICES.flatMap { (id, langs) ->
        langs.map { lang ->
            val parts = lang.split("_")
            val locale = if (parts.size == 2) Locale(parts[0], parts[1]) else Locale(parts[0])
            Voice("$id-$lang", locale, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
        }
    }.toSet()

    fun androidVoices(info: ScyllasBandBundleInfo): Set<Voice> = androidVoices()

    /** Locale used for pronunciation overrides. British voices with a US/neutral
     *  locale use the US word list, so American pronunciations win. */
    fun overrideLocale(voiceId: String, requested: String? = null): String {
        if (requested != null) return requested
        return if (voiceId in setOf("ink", "orpheus", "tuesday")) "en_gb" else "en_us"
    }

    fun defaultLanguage(voiceId: String): String =
        if (voiceId in setOf("ink", "orpheus", "tuesday")) "en_gb" else "en_us"

    /** A real voice name ("id-lang") for the framework's default-voice lookup.
     *  Android may query with 3-letter codes (eng/USA) or a bare "en"; normalize
     *  those and return a name that actually exists in androidVoices(), otherwise
     *  the framework greys out its sliders and Play does nothing. */
    fun defaultVoiceNameFor(lang: String?, country: String?): String {
        val l = when ((lang ?: "").lowercase()) {
            "eng" -> "en"; "deu" -> "de"; "spa" -> "es"; "fra" -> "fr"; "ita" -> "it"; "vie" -> "vi"
            else -> (lang ?: "").lowercase()
        }.ifEmpty { "en" }
        val c = when ((country ?: "").uppercase()) {
            "USA" -> "US"; "GBR" -> "GB"; else -> (country ?: "").uppercase()
        }
        val want = if (c.isNotBlank()) "${l}_${c.lowercase()}" else l
        val candidates = VOICES.entries.filter { entry -> entry.key != RandomVoice.ID }
        val id = candidates.firstOrNull { entry -> want in entry.value }?.key
            ?: candidates.firstOrNull { entry -> entry.value.any { v -> v.startsWith(l) } }?.key
            ?: DEFAULT_VOICE
        val chosenLang = if (want in (VOICES[id] ?: emptyList())) want else defaultLanguage(id)
        val name = "$id-$chosenLang"
        return if (name in androidVoices().map { it.name }) name else "$DEFAULT_VOICE-en_gb"
    }

    /** Map an incoming Android locale to a language the voice supports. */
    fun languageFor(voiceId: String, locale: Locale?): String {
        val langs = VOICES[voiceId] ?: throw UnsupportedLocaleException("Unknown voice $voiceId")
        val lang = locale?.toString()?.replace('-', '_')?.lowercase() ?: defaultLanguage(voiceId)
        if (lang in langs || lang == "en") return lang
        val base = lang.split("_")[0]
        if (base in langs) return base
        if (base == "en" || lang.startsWith("en")) return defaultLanguage(voiceId)
        throw UnsupportedLocaleException("Voice $voiceId does not support $lang")
    }

    fun languageFor(voiceId: String, locale: Locale?, info: ScyllasBandBundleInfo): String =
        languageFor(voiceId, locale)
}
