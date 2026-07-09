package io.github.adrianss31.lowfreqhunter

import io.github.adrianss31.lowfreqhunter.engine.Idw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdwTest {

    @Test
    fun duePuntiGradienteMonotono() {
        // -80 dB a sinistra, -40 dB a destra: lungo la mezzeria il valore
        // deve crescere in modo monotono da sinistra a destra
        val pts = listOf(
            Idw.Point(0.1f, 0.5f, -80.0),
            Idw.Point(0.9f, 0.5f, -40.0),
        )
        val gw = 20
        val gh = 10
        val g = Idw.grid(pts, gw, gh)
        val midRow = (gh / 2) * gw
        for (x in 1 until gw) {
            assertTrue(
                "non monotono a x=$x: ${g[midRow + x - 1]} -> ${g[midRow + x]}",
                g[midRow + x] >= g[midRow + x - 1] - 1e-9,
            )
        }
        // vicino ai punti il valore si avvicina a quello misurato
        assertTrue(g[midRow + 1] < -70.0)
        assertTrue(g[midRow + gw - 2] > -50.0)
    }

    @Test
    fun cellaSulPuntoRestituisceIlValoreEsatto() {
        // punto esattamente al centro di una cella (griglia 10x10 → centro cella (2,5) = 0.25,0.55)
        val pts = listOf(
            Idw.Point(0.25f, 0.55f, -33.0),
            Idw.Point(0.8f, 0.2f, -90.0),
        )
        val g = Idw.grid(pts, 10, 10)
        assertEquals(-33.0, g[5 * 10 + 2], 0.01)
    }

    @Test
    fun unSoloPuntoGrigliaCostante() {
        val g = Idw.grid(listOf(Idw.Point(0.5f, 0.5f, -60.0)), 8, 8)
        for (v in g) assertEquals(-60.0, v, 0.01)
    }

    @Test
    fun vuotoTuttoNaN() {
        val g = Idw.grid(emptyList(), 4, 4)
        for (v in g) assertTrue(v.isNaN())
    }
}
