package io.github.adrianss31.lowfreqhunter

import io.github.adrianss31.lowfreqhunter.data.ContinuousCfg
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EventData
import io.github.adrianss31.lowfreqhunter.engine.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class ContinuousTest {

    @Test
    fun unSoloSpezzamento() {
        val c = ContinuousCfg(enabled = true, splitMin = 0)
        assertEquals(listOf(0), c.splitMins())
        assertEquals("Notte", c.labelPrefixAt(3 * 60))
        assertEquals("Notte", c.labelPrefixAt(15 * 60))
    }

    @Test
    fun dueSpezzamenti_notteEGiorno() {
        // notturna 21→7, diurna 7→21
        val c = ContinuousCfg(enabled = true, splitMin = 21 * 60, split2Enabled = true, split2Min = 7 * 60)
        assertEquals(listOf(21 * 60, 7 * 60), c.splitMins())
        assertEquals("Notte", c.labelPrefixAt(23 * 60))
        assertEquals("Notte", c.labelPrefixAt(3 * 60))
        assertEquals("Notte", c.labelPrefixAt(21 * 60))   // inizio fascia notturna
        assertEquals("Giorno", c.labelPrefixAt(7 * 60))   // inizio fascia diurna
        assertEquals("Giorno", c.labelPrefixAt(12 * 60))
    }

    @Test
    fun dueSpezzamentiNonScavalcanti() {
        // notturna 2→14 (caso limite senza scavalco di mezzanotte)
        val c = ContinuousCfg(enabled = true, splitMin = 2 * 60, split2Enabled = true, split2Min = 14 * 60)
        assertEquals("Notte", c.labelPrefixAt(8 * 60))
        assertEquals("Giorno", c.labelPrefixAt(20 * 60))
        assertEquals("Giorno", c.labelPrefixAt(1 * 60))
    }

    @Test
    fun intensitaMediaPerFascia() {
        val utc = TimeZone.getTimeZone("UTC")
        // evento 00:00→00:30 con picco +15 dB sopra soglia (heat 1.0)
        // ed evento 01:00→01:30 appena sopra soglia (heat minima)
        val night = Recurrence.night(
            "n1", 0, 4 * 3600,
            listOf(
                EventData("A", 0, 1800, 1800, -40.0, -45.0),
                EventData("A", 3600, 3600 + 1800, 1800, -55.0, -56.0),
            ),
            utc,
            thr = mapOf("A" to -55.0),
        )
        val heat = night.heat("A")
        assertEquals(1f, heat[0], 0.001f)                       // (−40)−(−55) = +15 dB → 1.0
        assertEquals(Recurrence.heatOf(0.0), heat[2], 0.001f)   // picco = soglia → minimo
        assertEquals(0f, heat[1], 0f)                           // fascia senza eventi
    }

    @Test
    fun heatSaturaENonScendeSottoIlMinimo() {
        assertEquals(1f, Recurrence.heatOf(30.0), 0f)
        assertEquals(0.06f, Recurrence.heatOf(-5.0), 0f)
    }

    @Test
    fun gapNonProduceHeat() {
        val utc = TimeZone.getTimeZone("UTC")
        val night = Recurrence.night(
            "n1", 0, 3600,
            listOf(EventData(Channels.GAP, 0, 1800, 1800, null, null)),
            utc,
        )
        assertEquals(0f, night.heat().sum(), 0f)
    }
}
