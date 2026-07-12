package io.github.adrianss31.lowfreqhunter.dsp

import kotlin.math.exp

/**
 * Smussatori per i valori *visualizzati* (livelli, frequenza dominante).
 * Non toccano la catena di rilevamento eventi, che continua a lavorare sui
 * valori grezzi: qui si stabilizza solo quello che l'occhio legge.
 */

/** Media esponenziale con costante di tempo in secondi, robusta a dt variabili. */
class Ema(private val tauS: Double) {
    private var v = Double.NaN
    private var lastMs = 0L

    fun push(x: Double, nowMs: Long): Double {
        if (v.isNaN()) {
            v = x
            lastMs = nowMs
            return x
        }
        val dt = (nowMs - lastMs).coerceAtLeast(0) / 1000.0
        lastMs = nowMs
        v += (x - v) * (1.0 - exp(-dt / tauS))
        return v
    }

    fun reset() {
        v = Double.NaN
    }
}

/**
 * Mediana mobile: per la frequenza dominante, che salta tra bin vicini e tra
 * fondamentale e armonica — una media mostrerebbe frequenze inesistenti nel
 * mezzo, la mediana si ferma sul valore più persistente.
 */
class MovingMedian(private val size: Int) {
    private val buf = ArrayDeque<Double>()

    fun push(x: Double): Double {
        buf.addLast(x)
        if (buf.size > size) buf.removeFirst()
        val s = buf.sorted()
        return s[s.size / 2]
    }

    fun reset() {
        buf.clear()
    }
}
