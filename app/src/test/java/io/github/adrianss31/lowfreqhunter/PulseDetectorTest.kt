package io.github.adrianss31.lowfreqhunter

import io.github.adrianss31.lowfreqhunter.engine.EventData
import io.github.adrianss31.lowfreqhunter.engine.EventKind
import io.github.adrianss31.lowfreqhunter.engine.PulseDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alimenta il detector a 4 passi/secondo (il rate reale degli spettri) con
 * livelli sintetici: −40 sopra soglia, −80 sotto. Soglia −55, isteresi 3,
 * maxBurst 10 s (il minOn della macchina a stati).
 */
class PulseDetectorTest {

    private val dtS = 0.25

    /** Esegue [pattern] (secondi sopra, secondi sotto, alternati, inizia sopra)
     *  e ritorna gli eventi emessi. */
    private fun feed(pd: PulseDetector, startS: Double, pattern: List<Double>, tail: Double = 60.0): List<EventData> {
        val out = ArrayList<EventData>()
        var t = startS
        var above = true
        for (dur in pattern) {
            var left = dur
            while (left > 0) {
                pd.step(if (above) -40.0 else -80.0, -55.0, 3.0, t)?.let { out.add(it) }
                t += dtS
                left -= dtS
            }
            above = !above
        }
        var left = tail
        while (left > 0) {
            pd.step(-80.0, -55.0, 3.0, t)?.let { out.add(it) }
            t += dtS
            left -= dtS
        }
        return out
    }

    @Test
    fun raffichIrregolariDiventanoUnEventoPulsante() {
        val pd = PulseDetector("B", maxBurstS = 10.0)
        // 2 s suono, 4 s pausa, 0.5 s, 3 s pausa, 1 s — poi silenzio
        val evs = feed(pd, 1000.0, listOf(2.0, 4.0, 0.5, 3.0, 1.0))
        assertEquals(1, evs.size)
        val ev = evs[0]
        assertEquals(EventKind.PULSE, ev.kind)
        assertEquals("B", ev.band)
        assertEquals(1000, ev.startT)              // inizio della prima raffica
        // fine ~ ultima raffica: 2+4+0.5+3+1 = 10.5 s dopo l'inizio
        assertTrue("endT=${ev.endT}", ev.endT in 1009L..1011L)
        assertNotNull(ev.peakDb)
        assertEquals(-40.0, ev.peakDb!!, 0.5)
    }

    @Test
    fun dueRaffichSoleNonBastano() {
        val pd = PulseDetector("B", maxBurstS = 10.0)
        val evs = feed(pd, 0.0, listOf(2.0, 5.0, 1.0), tail = 200.0)
        assertTrue(evs.isEmpty())
    }

    @Test
    fun runLungoIgnorato() {
        // 15 s continuativi: è materia della macchina a stati, non una raffica
        val pd = PulseDetector("B", maxBurstS = 10.0)
        val evs = feed(pd, 0.0, listOf(15.0, 5.0, 1.0, 5.0, 1.0), tail = 200.0)
        assertTrue(evs.isEmpty())   // restano solo 2 raffiche vere
    }

    @Test
    fun raffichTroppoDistantiNonAprono() {
        val pd = PulseDetector("B", maxBurstS = 10.0, windowS = 90.0)
        // 3 raffiche ma distribuite su oltre 90 s
        val evs = feed(pd, 0.0, listOf(1.0, 60.0, 1.0, 60.0, 1.0), tail = 200.0)
        assertTrue(evs.isEmpty())
    }

    @Test
    fun flushChiudeEpisodioAperto() {
        val pd = PulseDetector("B", maxBurstS = 10.0)
        feed(pd, 0.0, listOf(1.0, 2.0, 1.0, 2.0, 1.0), tail = 0.0)
        val ev = pd.flush()
        assertNotNull(ev)
        assertEquals(EventKind.PULSE, ev!!.kind)
        assertNull(pd.flush())      // dopo il flush è tutto azzerato
    }
}
