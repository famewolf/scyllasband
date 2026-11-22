package org.scyllasband.tts

import org.json.JSONObject
import java.io.File

/** Reads/writes the runtime's word→phones dict in the installed bundle. */
class PronunciationManager(private val bundleDir: File) {
    private val file: File
        get() = File(bundleDir, "assets/g2p/phoneme_dict.json")

    fun entries(language: String): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val root = JSONObject(file.readText())
        val obj = root.optJSONObject(language) ?: return emptyMap()
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }

    fun put(language: String, word: String, phones: String) {
        val root = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        val obj = root.optJSONObject(language) ?: JSONObject()
        obj.put(word.trim().lowercase(), phones)
        root.put(language, obj)
        file.writeText(root.toString(2))
    }

    fun remove(language: String, word: String) {
        if (!file.isFile) return
        val root = JSONObject(file.readText())
        val obj = root.optJSONObject(language) ?: return
        obj.remove(word.trim().lowercase())
        root.put(language, obj)
        file.writeText(root.toString(2))
    }
}
