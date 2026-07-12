package io.github.adrianss31.lowfreqhunter.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Port di bandDb/dominantHz da js/audio.js della PWA. */
object Bands {

    /** Potenza integrata di banda in dBFS (somma delle potenze lineari dei bin). */
    fun bandDb(spec: FloatArray, binHz: Double, lo: Double, hi: Double): Double {
        val i0 = max(0, (lo / binHz).roundToInt())
        val i1 = min(spec.size - 1, (hi / binHz).roundToInt())
        var p = 0.0
        for (i in i0..i1) p += 10.0.pow(spec[i] / 10.0)
        return 10.0 * log10(p + 1e-12)
    }

    /** Bin più forte nell'intervallo: ritorna (frequenza Hz, livello dB). */
    fun dominantHz(spec: FloatArray, binHz: Double, lo: Double, hi: Double): Pair<Double, Double> {
        val i0 = max(1, (lo / binHz).roundToInt())
        val i1 = min(spec.size - 1, (hi / binHz).roundToInt())
        var maxV = Double.NEGATIVE_INFINITY
        var maxI = i0
        for (i in i0..i1) {
            if (spec[i] > maxV) {
                maxV = spec[i].toDouble()
                maxI = i
            }
        }
        // interpolazione parabolica sui dB dei bin adiacenti: senza, la stima
        // procede a gradini di binHz anche quando il tono è fermo tra due bin
        var freq = maxI * binHz
        if (maxI in (i0 + 1) until i1) {
            val a = spec[maxI - 1].toDouble()
            val c = spec[maxI + 1].toDouble()
            val denom = a - 2.0 * maxV + c
            if (kotlin.math.abs(denom) > 1e-9) {
                val delta = (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
                freq = (maxI + delta) * binHz
            }
        }
        return Pair(freq, maxV)
    }
}
