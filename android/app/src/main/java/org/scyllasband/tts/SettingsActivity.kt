package org.scyllasband.tts

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.scyllasband.android.ScyllasBand
import org.scyllasband.android.ScyllasBandSegmentSettings

class SettingsActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var voiceRow: LinearLayout
    private lateinit var currentVoiceText: TextView
    private lateinit var lastVoiceText: TextView
    private lateinit var spokenText: TextView
    private lateinit var intensityBar: SeekBar
    private lateinit var rateBar: SeekBar
    private lateinit var pitchBar: SeekBar
    private lateinit var wordField: EditText
    private lateinit var spellingField: EditText
    private lateinit var pronResult: TextView
    private val manager by lazy { ModelManager(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(16, bars.top + 32, 16, bars.bottom + 24)
            insets
        }
        // Centered brand header.
        root.addView(TextView(this).apply {
            text = getString(R.string.brand)
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        })

        val download = Button(this).apply { text = getString(R.string.download_model) }
        download.setOnClickListener {
            scope.launch {
                var high = -1
                fun show(p: Int) {
                    val clamped = p.coerceIn(0, 100)
                    if (clamped > high) {
                        high = clamped
                        runOnUiThread { statusText.text = getString(R.string.downloading, high) }
                    }
                }
                show(0)
                try {
                    manager.ensureInstalled { p -> show(p) }
                    runOnUiThread { statusText.text = getString(R.string.download_complete) }
                    refreshStatus()
                } catch (e: Exception) {
                    val msg = e.message ?: "unknown error"
                    runOnUiThread {
                        statusText.text = getString(R.string.download_failed, msg)
                        Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        root.addView(download)
        statusText = TextView(this).apply {
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        root.addView(statusText)

        // Single current-voice line: "Voices: orpheus [en-gb]   Change ▾".
        // Blank line above it separates it from the model/download row.
        voiceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 0)
        }
        currentVoiceText = TextView(this).apply {
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        voiceRow.addView(currentVoiceText)
        val playVoice = Button(this).apply {
            text = getString(R.string.play_sample)
            contentDescription = getString(R.string.play_sample_cd)
        }
        playVoice.setOnClickListener { playSelectedVoice() }
        voiceRow.addView(playVoice)
        val change = Button(this).apply { text = getString(R.string.change_voice) }
        change.setOnClickListener { showVoicePicker() }
        voiceRow.addView(change)
        root.addView(voiceRow)
        lastVoiceText = TextView(this)
        root.addView(lastVoiceText)
        spokenText = TextView(this).apply { textSize = 14f }
        root.addView(spokenText)
        // Random row stays on the main screen.
        val randomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        randomRow.addView(TextView(this).apply {
            text = getString(R.string.random_voice_label)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val demo = Button(this).apply { text = getString(R.string.demo_random) }
        demo.setOnClickListener { demoRandom() }
        randomRow.addView(demo)
        val choose = Button(this).apply { text = getString(R.string.choose_voices) }
        choose.setOnClickListener { showExclusionDialog() }
        randomRow.addView(choose)
        root.addView(randomRow)

        // Expandable section: Voice tuning (sliders + audition).
        val tuningBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tuningBody.addView(TextView(this).apply {
            text = getString(R.string.delivery_note)
            textSize = 12f
        })
        intensityBar = addSlider(tuningBody, getString(R.string.intensity_label))
        rateBar = addSlider(tuningBody, getString(R.string.rate_label))
        pitchBar = addSlider(tuningBody, getString(R.string.pitch_label))
        listOf(intensityBar, rateBar, pitchBar).forEach { bar ->
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) saveDelivery()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val testField = EditText(this).apply {
            hint = getString(R.string.local_test_hint)
            setText(getString(R.string.local_test_default))
        }
        val clearButton = Button(this).apply { text = getString(R.string.clear_test) }
        clearButton.setOnClickListener { testField.text.clear() }
        tuningBody.addView(clearButton)
        tuningBody.addView(testField)
        val testButton = Button(this).apply { text = getString(R.string.local_test_button) }
        testButton.setOnClickListener { speakLocally(testField.text.toString()) }
        tuningBody.addView(testButton)
        val reset = Button(this).apply { text = getString(R.string.reset_delivery) }
        reset.setOnClickListener { scope.launch { DeliveryPreference(this@SettingsActivity).reset(); loadDeliveryDefaults() } }
        tuningBody.addView(reset)
        addSection(root, getString(R.string.section_tuning), tuningBody)

        // Expandable section: Word Replacement.
        val wordsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pronRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wordField = EditText(this).apply {
            hint = getString(R.string.pron_word_hint)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pronRow.addView(wordField)
        spellingField = EditText(this).apply {
            hint = getString(R.string.pron_spelling_hint)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pronRow.addView(spellingField)
        val pronClear = Button(this).apply { text = getString(R.string.clear_test) }
        pronClear.setOnClickListener {
            wordField.text.clear()
            spellingField.text.clear()
            pronResult.text = ""
        }
        pronRow.addView(pronClear)
        wordsBody.addView(pronRow)
        pronResult = TextView(this)
        wordsBody.addView(pronResult)
        val convert = Button(this).apply { text = getString(R.string.pron_convert) }
        convert.setOnClickListener { convertPronunciation() }
        wordsBody.addView(convert)
        val pronPlay = Button(this).apply { text = getString(R.string.pron_play) }
        pronPlay.setOnClickListener { previewPronunciation() }
        wordsBody.addView(pronPlay)
        val pronSave = Button(this).apply { text = getString(R.string.pron_save) }
        pronSave.setOnClickListener { savePronunciation() }
        wordsBody.addView(pronSave)
        val pronList = Button(this).apply { text = getString(R.string.pron_list) }
        pronList.setOnClickListener { showPronunciationList() }
        wordsBody.addView(pronList)
        addSection(root, getString(R.string.section_words), wordsBody)

        // Blank line, then a compact centered button.
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 24) })
        val sysButton = Button(this).apply {
            text = getString(R.string.system_settings)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            minimumHeight = 0
            setPadding(16, 6, 16, 6)
            textSize = 13f
        }
        sysButton.setOnClickListener { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        root.addView(sysButton)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
        loadDeliveryDefaults()
        refreshLastVoice()
    }

    /** Collapsed-by-default section: a tappable header revealing its body. */
    private fun addSection(root: LinearLayout, title: String, body: LinearLayout) {
        val header = TextView(this).apply {
            text = "$title  ▸  ${getString(R.string.expand)}"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 12)
        }
        body.visibility = android.view.View.GONE
        root.addView(header)
        root.addView(body)
        header.setOnClickListener {
            if (body.visibility == android.view.View.GONE) {
                body.visibility = android.view.View.VISIBLE
                header.text = "$title  ▾  ${getString(R.string.collapse)}"
            } else {
                body.visibility = android.view.View.GONE
                header.text = "$title  ▸  ${getString(R.string.expand)}"
            }
        }
    }

    private fun addSlider(root: LinearLayout, label: String): SeekBar {
        root.addView(TextView(this).apply { text = label })
        val bar = SeekBar(this).apply { max = 400; progress = 200 }
        root.addView(bar)
        return bar
    }

    private fun barValue(progress: Int): Float = (progress - 200) / 100f
    private fun barProgress(value: Float): Int = (value * 100).toInt() + 200

    private fun saveDelivery() {
        scope.launch {
            DeliveryPreference(this@SettingsActivity).set(
                barValue(intensityBar.progress),
                barValue(rateBar.progress),
                barValue(pitchBar.progress),
            )
        }
    }

    private fun loadDeliveryDefaults() {
        scope.launch {
            val d = DeliveryPreference(this@SettingsActivity).values()
            val defaults = org.scyllasband.android.ScyllasBandDelivery(intensity = 0f, pace = 0f, pitch = 0f)
            runOnUiThread {
                intensityBar.progress = barProgress(d.intensity ?: defaults.intensity ?: 0f)
                rateBar.progress = barProgress(d.pace ?: defaults.pace ?: 0f)
                pitchBar.progress = barProgress(d.pitch ?: defaults.pitch ?: 0f)
            }
        }
    }

    private fun refreshLastVoice() {
        val last = LastSpoken.get(this@SettingsActivity)
        lastVoiceText.text = if (last == null) getString(R.string.last_voice_none)
            else getString(R.string.last_voice, last)
    }

    private fun refreshStatus() {
        statusText.text = getString(
            if (manager.isInstalled()) R.string.voice_ready else R.string.voice_needed
        )
        scope.launch {
            val preferred = VoicePreference(this@SettingsActivity).get()
            val name = when (preferred) {
                RandomVoice.ID -> getString(R.string.random_voice_label)
                null -> VoiceCatalog.DEFAULT_VOICE
                else -> preferred
            }
            val voice = if (preferred == RandomVoice.ID) {
                "$name  ${getString(R.string.random_voice_hint)}"
            } else {
                "$name  [${VoiceCatalog.defaultLanguage(name).replace('_', '-')}]"
            }
            currentVoiceText.text = "${getString(R.string.voices_title)}:  $voice"
            currentVoiceText.setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    /** Full list moved off the main screen: single-choice picker. */
    private fun showVoicePicker() {
        scope.launch {
            val preferred = VoicePreference(this@SettingsActivity).get()
            val ids = (VoiceCatalog.allVoiceIds - RandomVoice.ID).sorted().plus(RandomVoice.ID)
            val labels = ids.map { id ->
                if (id == RandomVoice.ID) getString(R.string.random_voice_label)
                else "$id  [${VoiceCatalog.defaultLanguage(id).replace('_', '-')}]"
            }.toTypedArray()
            val checked = ids.indexOf(preferred).coerceAtLeast(0)
            runOnUiThread {
                val builder = AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.voices_title)
                    .setSingleChoiceItems(labels, checked) { d, which ->
                        val id = ids[which]
                        scope.launch {
                            VoicePreference(this@SettingsActivity).set(id)
                            loadDeliveryDefaults()
                            refreshStatus()
                        }
                        Toast.makeText(this@SettingsActivity, "$id selected", Toast.LENGTH_SHORT).show()
                        d.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                builder.show()
            }
        }
    }

    private fun showExclusionDialog() {
        scope.launch {
            val random = RandomVoice(this@SettingsActivity)
            val excluded = random.excluded().toMutableSet()
            val voices = (VoiceCatalog.allVoiceIds - RandomVoice.ID).sorted().toTypedArray()
            val checked = BooleanArray(voices.size) { voices[it] !in excluded }
            runOnUiThread {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.exclude_title)
                    .setMultiChoiceItems(voices, checked) { _, which, isChecked ->
                        if (isChecked) excluded.remove(voices[which]) else excluded.add(voices[which])
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        scope.launch { random.setExcluded(excluded) }
                    }
                    .show()
            }
        }
    }

    private fun pronManager(): PronunciationManager = PronunciationManager(manager.bundleDir)

    private fun currentVoice(): String {
        // Do not block: the settings flow uses the stored preference.
        return runCatching { kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            VoicePreference(this@SettingsActivity).get()
        } }.getOrNull() ?: VoiceCatalog.DEFAULT_VOICE
    }

    private fun convertPronunciation() {
        val text = spellingField.text.toString().trim()
        val word = wordField.text.toString().trim()
        if (text.isBlank() || word.isBlank()) {
            pronResult.text = getString(R.string.pron_need_both)
            return
        }
        scope.launch {
            try {
                val phones = withContext(Dispatchers.Default) {
                    val band = ScyllasBand.create(this@SettingsActivity)
                    band.initialize(bundleDir = manager.bundleDir)
                    val voice = currentVoice()
                    band.phonemize(text, voice)
                }
                runOnUiThread {
                    pronResult.text = if (phones.isEmpty()) getString(R.string.pron_failed)
                        else phones.joinToString("")
                }
            } catch (e: Exception) {
                runOnUiThread { pronResult.text = e.message ?: getString(R.string.pron_failed) }
            }
        }
    }

    private fun previewPronunciation() {
        val word = wordField.text.toString().trim()
        val phones = pronResult.text.toString().trim()
        if (word.isBlank() || phones.isBlank() || phones.contains(getString(R.string.pron_need_both)[0])) {
            pronResult.text = getString(R.string.pron_convert_first)
            return
        }
        scope.launch {
            try {
                // Write a temporary override so the runtime reads it, synthesize,
                // then remove it — no permanent save until Save is tapped.
                val voice = currentVoice()
                val lang = VoiceCatalog.overrideLocale(voice)
                val mgr = pronManager()
                mgr.put(lang, word, phones)
                synthesizeAndPlay(word, voice, promptRandom = false)
                mgr.remove(lang, word)
            } catch (e: Exception) {
                runOnUiThread { pronResult.text = e.message ?: getString(R.string.pron_failed) }
            }
        }
    }

    private fun savePronunciation() {
        val word = wordField.text.toString().trim()
        val phones = pronResult.text.toString().trim()
        if (word.isBlank() || phones.isBlank()) {
            pronResult.text = getString(R.string.pron_convert_first)
            return
        }
        val voice = currentVoice()
        val lang = VoiceCatalog.overrideLocale(voice)
        pronManager().put(lang, word, phones)
        runOnUiThread { pronResult.text = getString(R.string.pron_saved, word) }
    }

    private fun showPronunciationList() {
        val voice = currentVoice()
        val lang = VoiceCatalog.overrideLocale(voice)
        val entries = pronManager().entries(lang)
        val labels = entries.keys.sorted().toTypedArray()
        if (labels.isEmpty()) {
            pronResult.text = getString(R.string.pron_empty)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pron_list))
            .setItems(labels) { _, which ->
                val word = labels[which]
                pronManager().remove(lang, word)
                pronResult.text = getString(R.string.pron_removed, word)
            }
            .setNegativeButton(getString(R.string.pron_done), null)
            .show()
    }

    /** Play a sample of the currently selected voice, or the Random demo. */
    private fun playSelectedVoice() {
        scope.launch {
            val id = VoicePreference(this@SettingsActivity).get() ?: VoiceCatalog.DEFAULT_VOICE
            if (id == RandomVoice.ID) {
                demoRandom()
            } else {
                SamplePlayer.play(this@SettingsActivity, id)
                LastSpoken.set(this@SettingsActivity, id)
                refreshLastVoice()
            }
        }
    }

    /** Demonstrates voice rotation with a bundled WAV: instant playback, no
     *  synthesis wait. The three lines are fixed, so pre-generating them (ink,
     *  orpheus, tuesday) removes the multi-second gap before audio starts. */
    private fun demoRandom() {
        scope.launch {
            SamplePlayer.playWav(this@SettingsActivity, R.raw.demo_voices)
                .onFailure { Toast.makeText(this@SettingsActivity, R.string.sample_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun speakLocally(text: String) {
        if (text.isBlank()) return
        runOnUiThread { spokenText.text = getString(R.string.spoken_label, text) }
        scope.launch {
            val prefs = VoicePreference(this@SettingsActivity)
            val chosen = prefs.get() ?: VoiceCatalog.DEFAULT_VOICE
            synthesizeAndPlay(text, chosen, promptRandom = true)
        }
    }

    private fun playPcm(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size).build()
        player.play()
        player.write(pcm, 0, pcm.size)
        val done = java.util.concurrent.CountDownLatch(1)
        player.setNotificationMarkerPosition(pcm.size / 2)
        player.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) { done.countDown() }
            override fun onPeriodicNotification(track: AudioTrack?) {}
        })
        done.await(pcm.size / 48L + 1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        player.release()
    }

    private suspend fun synthesizeAndPlay(text: String, voice: String, promptRandom: Boolean) {
            val delivery = DeliveryPreference(this@SettingsActivity).values()
            // Lock the sliders while speaking: taps during playback would otherwise
            // queue on the blocked main thread and apply only after speech stops,
            // making the controls look broken.
            setSlidersEnabled(false)
            try {
                val pcm = withContext(Dispatchers.Default) {
                    val band = ScyllasBand.create(this@SettingsActivity)
                    band.initialize(bundleDir = manager.bundleDir)
                    var voiceId = voice
                    if (voiceId == RandomVoice.ID) {
                        voiceId = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                            RandomVoice(this@SettingsActivity).pick(null)
                        }
                    }
                    LastSpoken.set(this@SettingsActivity, voiceId)
                    val language = band.bundleInfo?.voices?.firstOrNull { it.id == voiceId }?.defaultLanguage
                        ?: return@withContext ByteArray(0)
                    val out = java.io.ByteArrayOutputStream()
                    band.synthesizeStreaming(
                        text = text,
                        settings = ScyllasBandSegmentSettings(voiceId, language, delivery),
                        onChunkStarted = { _, _, _ -> true },
                        onAudioChunk = { samples, _, _ ->
                            for (s in samples) {
                                val v = (kotlin.math.tanh((s * 5f).toDouble()) * 32767).toInt()
                                out.write(v and 0xff); out.write((v shr 8) and 0xff)
                            }
                            true
                        },
                    )
                    out.toByteArray()
                }
                withContext(Dispatchers.IO) { playPcm(pcm) }
                refreshLastVoice()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, e.message ?: "Speech failed", Toast.LENGTH_LONG).show()
            } finally {
                setSlidersEnabled(true)
            }
    }

    private fun setSlidersEnabled(enabled: Boolean) {
        intensityBar.isEnabled = enabled
        rateBar.isEnabled = enabled
        pitchBar.isEnabled = enabled
    }

    override fun onResume() {
        super.onResume()
        // Persist slider values on pause via their changes; here just refresh.
        refreshLastVoice()
    }
}
