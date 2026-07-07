package io.github.adrianss31.lowfreqhunter

import io.github.adrianss31.lowfreqhunter.engine.EventStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineTest {

    private fun sm() = EventStateMachine("A")

    @Test
    fun apreDopoMinOnEChiudeDopoMinOff() {
        val m = sm()
        var started = -1L
        // sopra soglia (-50 > -55) da t=10: RISING a 10, ACTIVE a 12 (minOn=2)
        for (t in 10L..15L) {
            val ev = m.step(-50.0, -55.0, 3.0, 2, 3, t, { _, s -> started = s })
            assertNull(ev)
        }
        assertTrue(m.isActive)
        assertEquals(10L, started)
        assertEquals(10L, m.activeSince)
        // sotto soglia−isteresi (-60 < -58) da t=16: FALLING a 16, chiude a 19 (minOff=3)
        var closed: io.github.adrianss31.lowfreqhunter.engine.EventData? = null
        for (t in 16L..19L) {
            val ev = m.step(-60.0, -55.0, 3.0, 2, 3, t)
            if (ev != null) closed = ev
        }
        assertNotNull(closed)
        assertEquals(10L, closed!!.startT)
        assertEquals(16L, closed.endT)
        assertEquals(6L, closed.durationS)
        assertEquals(-50.0, closed.peakDb!!, 0.01)
    }

    @Test
    fun picchiBreviNonAprono() {
        val m = sm()
        // un solo secondo sopra soglia con minOn=5: non deve aprire
        m.step(-40.0, -55.0, 3.0, 5, 5, 0)
        m.step(-90.0, -55.0, 3.0, 5, 5, 1)
        m.step(-90.0, -55.0, 3.0, 5, 5, 2)
        assertEquals(EventStateMachine.State.IDLE, m.state)
    }

    @Test
    fun isteresiTieneApertoNellaZonaGrigia() {
        val m = sm()
        for (t in 0L..5L) m.step(-50.0, -55.0, 3.0, 2, 2, t)
        assertTrue(m.isActive)
        // -57 è sotto la soglia ON (-55) ma sopra la OFF (-58): resta ACTIVE
        m.step(-57.0, -55.0, 3.0, 2, 2, 6)
        assertTrue(m.isActive)
    }

    @Test
    fun forceCloseChiudeConOrarioDato() {
        val m = sm()
        for (t in 0L..5L) m.step(-50.0, -55.0, 3.0, 2, 2, t)
        val ev = m.forceClose(100L)
        assertNotNull(ev)
        assertEquals(0L, ev!!.startT)
        assertEquals(100L, ev.endT)
        assertEquals(EventStateMachine.State.IDLE, m.state)
    }
}
