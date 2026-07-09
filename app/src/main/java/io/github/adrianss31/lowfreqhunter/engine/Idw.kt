package io.github.adrianss31.lowfreqhunter.engine

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Interpolazione inverse-distance-weighting per la heatmap della casa.
 * Kotlin puro: testabile su JVM. Coordinate normalizzate 0..1.
 */
object Idw {

    data class Point(val x: Float, val y: Float, val value: Double)

    /**
     * Griglia [gw]×[gh] (row-major, riga 0 in alto) interpolata dai punti.
     * IDW classico con potenza [power]; un punto a distanza ~zero domina la
     * cella (valore esatto ai punti misurati). Con un solo punto la griglia
     * è costante. Lista vuota → griglia di NaN.
     */
    fun grid(points: List<Point>, gw: Int, gh: Int, power: Double = 2.0): DoubleArray {
        val out = DoubleArray(gw * gh) { Double.NaN }
        if (points.isEmpty()) return out
        for (gy in 0 until gh) {
            val cy = (gy + 0.5f) / gh
            for (gx in 0 until gw) {
                val cx = (gx + 0.5f) / gw
                var num = 0.0
                var den = 0.0
                var exact = Double.NaN
                for (p in points) {
                    val dx = (cx - p.x).toDouble()
                    val dy = (cy - p.y).toDouble()
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < 1e-4) {
                        exact = p.value
                        break
                    }
                    val wgt = 1.0 / dist.pow(power)
                    num += p.value * wgt
                    den += wgt
                }
                out[gy * gw + gx] = if (!exact.isNaN()) exact else num / den
            }
        }
        return out
    }
}
