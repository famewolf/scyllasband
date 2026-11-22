package org.scyllasband.tts

import android.content.Context
import android.speech.tts.SynthesisRequest
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.scyllasband.android.ScyllasBandDelivery
import java.util.Locale
import kotlin.math.ln
import kotlin.math.round

private val Context.deliveryDataStore by preferencesDataStore(name = "delivery_pref")

/** Stores the app's delivery sliders and resolves them for each synthesis call. */
class DeliveryPreference(private val context: Context) {
    private val intensityKey = floatPreferencesKey("intensity")
    private val paceKey = floatPreferencesKey("pace")
    private val pitchKey = floatPreferencesKey("pitch")
    private val hasValuesKey = androidx.datastore.preferences.core.booleanPreferencesKey("has_values")

    suspend fun values(): ScyllasBandDelivery {
        val p = context.deliveryDataStore.data.first()
        if (p[hasValuesKey] != true) return ScyllasBandDelivery()
        return ScyllasBandDelivery(
            intensity = p[intensityKey],
            pace = p[paceKey],
            pitch = p[pitchKey],
        )
    }

    suspend fun set(intensity: Float, pace: Float, pitch: Float) {
        context.deliveryDataStore.edit { p ->
            p[intensityKey] = intensity
            p[paceKey] = pace
            p[pitchKey] = pitch
            p[hasValuesKey] = true
        }
    }

    /** Clears overrides so each voice's own defaults apply. */
    suspend fun reset() {
        context.deliveryDataStore.edit { it[hasValuesKey] = false }
    }

    /** App sliders are the base; Android's rate/pitch settings override when set. */
    suspend fun resolve(locale: Locale, request: SynthesisRequest?): ScyllasBandDelivery {
        val base = values()
        var intensity = base.intensity
        var pace = base.pace
        var pitch = base.pitch
        if (request != null) {
            val rate = request.speechRate / 100f
            if (rate > 0f && rate != 1f) {
                pace = ((ln(rate.toDouble()) / ln(2.0)) * 2).toFloat().coerceIn(-2f, 2f)
            }
            val p = request.pitch / 100f
            if (p > 0f && p != 1f) {
                pitch = ((ln(p.toDouble()) / ln(2.0)) * 12 / 6).toFloat().coerceIn(-2f, 2f)
            }
        }
        return ScyllasBandDelivery(intensity, pace, pitch)
    }
}
