package io.github.adrianss31.lowfreqhunter.engine

import kotlin.math.log10
import kotlin.math.pow

/** Exact aggregation of stored one-second samples; missing seconds stay missing. */
object DossierStats {
    data class Stats(val count: Int, val meanDb: Double, val maxDb: Double, val p10Db: Double, val above: Int)
    fun summarize(values: List<Double>, threshold: Double): Stats? {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return null
        val sorted = finite.sorted()
        return Stats(finite.size, 10 * log10(finite.sumOf { 10.0.pow(it / 10) } / finite.size),
            sorted.last(), sorted[((sorted.size - 1) * 0.1).toInt()], finite.count { it >= threshold })
    }
}
