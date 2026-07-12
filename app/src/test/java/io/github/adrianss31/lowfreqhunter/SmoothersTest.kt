package io.github.adrianss31.lowfreqhunter

import io.github.adrianss31.lowfreqhunter.dsp.Ema
import io.github.adrianss31.lowfreqhunter.dsp.MovingMedian
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothersTest {

    @Test
    fun medianaIgnoraGliSpikeIsolati() {
        val m = MovingMedian(5)
        m.push(50.0)
        m.push(50.0)
        // uno spike (armonica intercettata per un attimo) non sposta la mediana
        assertEquals(50.0, m.push(100.0), 1e-9)
        assertEquals(50.0, m.push(50.0), 1e-9)
    }

    @Test
    fun medianaSegueUnCambioPersistente() {
        val m = MovingMedian(5)
        repeat(5) { m.push(50.0) }
        m.push(100.0)
        m.push(100.0)
        // dopo 3 valori su 5 la maggioranza cambia
        assertEquals(100.0, m.push(100.0), 1e-9)
    }

    @Test
    fun emaConvergeConCostanteDiTempo() {
        val e = Ema(1.0)
        assertEquals(0.0, e.push(0.0, 0), 1e-9)
        // dopo 1 tau il valore copre ~63% del gradino
        val v = e.push(10.0, 1000)
        assertTrue("v=$v", v > 6.0 && v < 7.0)
        // dopo ~5 tau è praticamente arrivato
        val v5 = e.push(10.0, 6000)
        assertTrue("v5=$v5", v5 > 9.9)
    }

    @Test
    fun emaRobustaAiTimestampNonMonotoni() {
        val e = Ema(1.0)
        e.push(0.0, 1000)
        val v = e.push(10.0, 500) // clock che torna indietro: dt trattato come 0
        assertEquals(0.0, v, 1e-9)
    }
}
