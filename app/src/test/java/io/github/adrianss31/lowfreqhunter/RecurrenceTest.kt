package io.github.adrianss31.lowfreqhunter

import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EventData
import io.github.adrianss31.lowfreqhunter.engine.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class RecurrenceTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun intervalloDistribuitoSulleFasce() {
        val acc = FloatArray(Recurrence.BUCKETS)
        // 00:15 → 01:00 UTC (epoch 0 è mezzanotte)
        Recurrence.addInterval(acc, 900, 3600, utc)
        assertEquals(900f, acc[0], 0f)    // 00:15–00:30
        assertEquals(1800f, acc[1], 0f)   // 00:30–01:00
        assertEquals(0f, acc[2], 0f)
        assertEquals(2700f, acc.sum(), 0f)
    }

    @Test
    fun scavalcaLaMezzanotte() {
        val acc = FloatArray(Recurrence.BUCKETS)
        Recurrence.addInterval(acc, 86400 - 900, 86400 + 900, utc)
        assertEquals(900f, acc[Recurrence.BUCKETS - 1], 0f)
        assertEquals(900f, acc[0], 0f)
    }

    @Test
    fun gapSottrattoDallaCopertura() {
        // sessione 22:00 → 06:00 con gap 02:00 → 02:30 ed evento 02:30 → 03:00
        val start = 22L * 3600
        val end = 30L * 3600
        val night = Recurrence.night(
            "n1", start, end,
            listOf(
                EventData(Channels.GAP, 26 * 3600, 26 * 3600 + 1800, 1800, null, null),
                EventData("A", 26 * 3600 + 1800, 27 * 3600, 1800, -40.0, -45.0),
            ),
            utc,
        )
        val b0200 = (2 * 3600) / Recurrence.BUCKET_S          // fascia 02:00–02:30
        val b0230 = (2 * 3600 + 1800) / Recurrence.BUCKET_S   // fascia 02:30–03:00
        assertEquals(0f, night.coverS[b0200], 0f)             // tutta in gap
        assertEquals(1800f, night.coverS[b0230], 0f)
        assertEquals(1800f, night.activeS[b0230], 0f)
        // fascia fuori sessione (12:00) non monitorata
        assertEquals(0f, night.coverS[24], 0f)
    }

    @Test
    fun attivitaSeparataPerBanda() {
        // due bande attive in fasce diverse: il filtro le distingue, "tutte" le somma
        val night = Recurrence.night(
            "n1", 0, 4 * 3600,
            listOf(
                EventData("A", 0, 1800, 1800, -40.0, -45.0),
                EventData("B", 3600, 3600 + 900, 900, -40.0, -45.0),
            ),
            utc,
        )
        assertEquals(1800f, night.active("A")[0], 0f)
        assertEquals(0f, night.active("A")[2], 0f)
        assertEquals(900f, night.active("B")[2], 0f)
        assertEquals(0f, night.active("C")[0], 0f)   // banda mai vista: tutta zero
        assertEquals(1800f, night.active(null)[0], 0f)
        assertEquals(900f, night.activeS[2], 0f)
    }
}
