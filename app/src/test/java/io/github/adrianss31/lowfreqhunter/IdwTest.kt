package io.github.adrianss31.lowfreqhunter

import io.github.adrianss31.lowfreqhunter.engine.Idw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdwTest {

    @Test
    fun duePuntiGradienteMonotonoTraIPunti() {
        // -80 dB a sinistra (x=0.1), -40 dB a destra (x=0.9): TRA i due punti
        // il valore cresce in modo monotono. (Fuori dai punti l'IDW ha
        // correttamente un estremo locale sul punto misurato.)
        val pts = listOf(
            Idw.Point(0.1f, 0.5f, -80.0),
            Idw.Point(0.9f, 0.5f, -40.0),
        )
        val gw = 20
        val gh = 10
        val g = Idw.grid(pts, gw, gh)
        val midRow = (gh / 2) * gw
        // celle con centro dentro (0.1, 0.9): gx = 2..17
        for (x in 3..17) {
            assertTrue(
                "non monotono a x=$x: ${g[midRow + x - 1]} -> ${g[midRow + x]}",
                g[midRow + x] >= g[midRow + x - 1] - 1e-9,
            )
        }
        // vicino ai punti il valore si avvicina a quello misurato
        assertTrue("sx=${g[midRow + 2]}", g[midRow + 2] < -70.0)
        assertTrue("dx=${g[midRow + 17]}", g[midRow + 17] > -50.0)
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
