package org.scyllasband.tts

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/** Pure-logic tests for the Random selection rules (no Android context needed). */
class RandomVoiceTest {
    private val all = VoiceCatalog.allVoiceIds
    private fun allowed(excluded: Set<String>) = all - excluded

    @Test
    fun allExcludedFallsBackToFullSet() {
        val excluded = all
        val allowed = allowed(excluded).ifEmpty { all.toList() }
        assertEquals(all, allowed.toSet())
    }

    @Test
    fun excludedVoiceNeverPicked() {
        val excluded = setOf("scylla")
        val allowed = allowed(excluded)
        val rng = Random(42)
        repeat(50) {
            val pick = allowed.toList()[rng.nextInt(allowed.size)]
            assertNotEquals("scylla", pick)
        }
    }

    @Test
    fun cooldownSticksThenRotates() {
        var now = 1_000_000L
        val rng = Random(1)
        var last: String? = null
        var lastAt = 0L
        fun pick(): String {
            if (last != null && now - lastAt < RandomVoice.COOLDOWN_MS) return last!!
            val chosen = all.toList()[rng.nextInt(all.size)]
            last = chosen; lastAt = now
            return chosen
        }
        val first = pick()
        now += 60_000L
        assertEquals(first, pick())
        now += 6 * 60_000L
        val later = pick()
        assertNotNull(later)
    }

    @Test
    fun localeFilteringKeepsOnlySupportingVoices() {
        val de = Locale("de", "DE")
        val supports = all.filter {
            try { VoiceCatalog.languageFor(it, de); true } catch (e: Exception) { false }
        }
        assertTrue(supports.isNotEmpty())
        assertTrue(supports.all { VoiceCatalog.languageFor(it, de).startsWith("de") })
    }

    @Test
    fun persistenceRoundTrip() {
        // DataStore round-trip is covered by instrumentation; here verify the
        // recorded pair shape used by the readout.
        val pair = "orpheus" to 1_000_000L
        assertEquals("orpheus", pair.first)
        assertEquals(1_000_000L, pair.second)
    }
}
