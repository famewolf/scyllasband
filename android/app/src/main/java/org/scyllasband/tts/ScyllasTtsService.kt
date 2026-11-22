package org.scyllasband.tts

import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.scyllasband.android.ScyllasBand
import org.scyllasband.android.ScyllasBandAccelerator
import java.util.Locale

class ScyllasTtsService : TextToSpeechService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var engine: ScyllasBand? = null
    private val initLock = Any()
    private val initDone = java.util.concurrent.CountDownLatch(1)
    private var preferredVoice: String = DEFAULT_VOICE

    companion object {
        const val TAG = "ScyllasTtsService"
        const val DEFAULT_VOICE = "orpheus"
        const val OUTPUT_GAIN = 5f
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try {
                val dir = ModelManager(this@ScyllasTtsService).ensureInstalled { }
                val band = ScyllasBand.create(this@ScyllasTtsService)
                val info = band.initialize(accelerator = ScyllasBandAccelerator.AUTO, bundleDir = dir)
                preferredVoice = VoicePreference(this@ScyllasTtsService).get()?.takeIf { id ->
                    info.voices.any { it.id == id }
                } ?: info.voices.firstOrNull()?.id ?: DEFAULT_VOICE
                synchronized(initLock) { engine = band }
                android.util.Log.i(TAG, "Engine ready (voice=$preferredVoice)")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Engine init failed", e)
            } finally {
                initDone.countDown()
            }
        }
        scope.launch {
            preferredVoice = VoicePreference(this@ScyllasTtsService).get() ?: preferredVoice
        }
    }

    override fun onDestroy() {
        scope.cancel()
        engine?.close()
        super.onDestroy()
    }

    override fun onGetLanguage(): Array<String> = arrayOf("en", "GB", "")

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        // Use the voice the user picked with "Use" in our app when it supports the
        // requested locale; otherwise fall back to the first catalog voice for it.
        // Without this, the system Settings Play button always picks "ink" (the
        // first en_gb voice) and ignores the app's selection.
        // Use the already-loaded preferredVoice field. A runBlocking DataStore read
        // here would block the framework's binder thread during TTS init, which can
        // make client apps (e.g. SpeakThat) fail engine selection.
        val chosen: String? = preferredVoice
        val name = when {
            chosen == RandomVoice.ID -> {
                // Random is exposed as the voice "random-en_us"; return that name so
                // the system uses it and onSynthesizeText's pick() path resolves it.
                "random-en_us"
            }
            chosen != null -> {
            try {
                val loc = Locale(lang ?: "", country ?: "")
                val resolved = VoiceCatalog.languageFor(chosen, loc)
                "$chosen-$resolved"
            } catch (e: Exception) {
                VoiceCatalog.defaultVoiceNameFor(lang, country)
            }
            }
            else -> VoiceCatalog.defaultVoiceNameFor(lang, country)
        }
        return name
    }

    /** Our voice names are "id-lang" (e.g. ariadne-en_us), which the base class's
     *  BCP47 parser rejects. Accept any name that exists in our public voice list. */
    override fun onIsValidVoiceName(voiceName: String?): Int {
        val valid = voiceName != null &&
            (voiceName == RandomVoice.ID || VoiceCatalog.androidVoices().any { it.name == voiceName })
        return if (valid) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val locale = Locale(lang ?: "", country ?: "")
        return try {
            VoiceCatalog.languageFor(VoiceCatalog.DEFAULT_VOICE, locale)
            TextToSpeech.LANG_AVAILABLE
        } catch (e: Exception) {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetVoices(): MutableList<android.speech.tts.Voice> {
        val voices = VoiceCatalog.androidVoices().toMutableList()
        return voices
    }

    override fun onLoadVoice(voiceId: String?): Int {
        val ok = voiceId != null && (voiceId == RandomVoice.ID || VoiceCatalog.allVoiceIds.any { voiceId.startsWith(it) })
        return if (ok) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        // System TTS (e.g. the Play button in Android settings) can call this
        // while the engine is still loading. Wait for init instead of failing.
        if (synchronized(initLock) { engine } == null) {
            try {
                initDone.await(20, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val band = synchronized(initLock) { engine }
            ?: run { android.util.Log.e(TAG, "Synthesize before engine ready"); callback.error(); return }

        val locale = Locale(request.language, request.country)
        var requestedId = request.voiceName?.substringBefore('-')?.takeIf { it.isNotBlank() } ?: preferredVoice
        if (requestedId == RandomVoice.ID) {
            requestedId = runBlocking(Dispatchers.IO) { RandomVoice(this@ScyllasTtsService).pick(locale) }
        }
        LastSpoken.set(this@ScyllasTtsService, requestedId)
        val info = band.bundleInfo ?: run { callback.error(); return }
        val language = try {
            VoiceCatalog.languageFor(requestedId, locale, info)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "No language for voice=$requestedId locale=$locale", e)
            callback.error(); return
        }
        val delivery = runBlocking(Dispatchers.IO) { DeliveryPreference(this@ScyllasTtsService).resolve(locale, request) }

        callback.start(info.sampleRate, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
        val chunk = ByteArray(8192)
        var stopped = false
        band.synthesizeStreaming(
            text = request.text,
            settings = org.scyllasband.android.ScyllasBandSegmentSettings(
                voiceId = requestedId,
                language = language,
                delivery = delivery,
            ),
            onChunkStarted = { _, _, _ -> true },
            onAudioChunk = { samples, _, _ ->
                var offset = 0
                while (offset < samples.size && !stopped) {
                    val n = minOf(chunk.size / 2, samples.size - offset)
                    for (i in 0 until n) {
                        // scyllasband peaks ~0.55 (-5 dBFS); lift toward full scale.
                        // tanh soft-limits so peaks can't clip/distort.
                        val x = samples[offset + i] * OUTPUT_GAIN
                        val s = (kotlin.math.tanh(x.toDouble()) * 32767).toInt()
                        chunk[i * 2] = (s and 0xff).toByte()
                        chunk[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
                    }
                    callback.audioAvailable(chunk, 0, n * 2)
                    offset += n
                }
                !stopped
            },
        )
        if (!stopped) callback.done()
    }

    override fun onStop() {
        engine?.cancel()
    }

}
