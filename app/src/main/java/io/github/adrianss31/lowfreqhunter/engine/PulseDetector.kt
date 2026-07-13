package io.github.adrianss31.lowfreqhunter.engine

import kotlin.math.log10
import kotlin.math.pow

/**
 * Rileva rumori "pulsanti": raffiche brevi sopra soglia (qualche secondo o
 * frazione), irregolari, troppo corte perché la macchina a stati le registri
 * (che richiede minOnS secondi continuativi). Esempio reale: 2 s di suono,
 * pausa, 0,5 s, pausa, 1 s, in ordine casuale.
 *
 * Lavora a rate spettro (~4/s) sui livelli istantanei, NON sulle medie al
 * secondo: una raffica da mezzo secondo diluita nella media rischia di non
 * superare mai la soglia.
 *
 * Modello:
 *  - raffica = run sopra soglia (isteresi in uscita) più corto di [maxBurstS];
 *    i run che arrivano a maxBurstS sono materia della macchina a stati e
 *    vengono ignorati qui (niente doppio evento);
 *  - episodio = almeno [minBursts] raffiche in una finestra di [windowS];
 *    resta aperto finché arrivano raffiche e si chiude dopo [closeGapS]
 *    di silenzio, emettendo un unico EventData kind="pulse".
 *
 * Kotlin puro, testabile su JVM con clock finto.
 */
class PulseDetector(
    val band: String,
    private val maxBurstS: Double,
    private val minBursts: Int = MIN_BURSTS,
    private val windowS: Double = WINDOW_S,
    private val closeGapS: Double = CLOSE_GAP_S,
) {
    companion object {
        const val MIN_BURSTS = 3
        const val WINDOW_S = 90.0
        const val CLOSE_GAP_S = 45.0
    }

    private class Burst(val start: Double) {
        var end = start
        var peak = Double.NEGATIVE_INFINITY
        var powerSum = 0.0
        var powerN = 0
    }

    // run sopra soglia in corso
    private var run: Burst? = null
    private var runLong = false            // già oltre maxBurstS: non è una raffica

    // raffiche recenti in attesa che l'episodio si apra (finestra scorrevole)
    private val bursts = ArrayDeque<Burst>()

    // episodio pulsante aperto
    private var epStart: Double? = null
    private var epPeak = Double.NEGATIVE_INFINITY
    private var epPowerSum = 0.0
    private var epPowerN = 0
    private var lastBurstEnd = 0.0

    /**
     * Avanza di un passo (un livello istantaneo). Ritorna l'evento pulsante
     * chiuso se questo passo lo chiude, altrimenti null.
     */
    fun step(level: Double, thrOn: Double, hystDb: Double, tS: Double): EventData? {
        val thrOff = thrOn - hystDb
        val r = run
        if (r == null) {
            if (level >= thrOn) {
                run = Burst(tS).also {
                    it.peak = level
                    it.powerSum = 10.0.pow(level / 10.0)
                    it.powerN = 1
                }
                runLong = false
            }
        } else if (level >= thrOff) {
            r.end = tS
            if (level > r.peak) r.peak = level
            r.powerSum += 10.0.pow(level / 10.0)
            r.powerN++
            if (tS - r.start >= maxBurstS) runLong = true
        } else {
            if (!runLong) commitBurst(r)
            run = null
        }

        if (epStart == null) {
            // finestra scorrevole: le raffiche vecchie non contano più
            while (bursts.isNotEmpty() && bursts.first().end < tS - windowS) bursts.removeFirst()
            maybeOpen()
        }

        // chiusura: silenzio prolungato dopo l'ultima raffica
        if (epStart != null && run == null && tS - lastBurstEnd >= closeGapS) {
            return close()
        }
        return null
    }

    /** Chiusura forzata (stop sessione o gap di monitoraggio). */
    fun flush(): EventData? {
        run?.let { if (!runLong) commitBurst(it) }
        run = null
        if (epStart == null) maybeOpen()   // l'ultima raffica può completare il minimo
        val ev = if (epStart != null) close() else null
        reset()
        return ev
    }

    private fun maybeOpen() {
        if (bursts.size < minBursts) return
        epStart = bursts.first().start
        for (b in bursts) absorb(b)
        bursts.clear()
    }

    private fun commitBurst(b: Burst) {
        lastBurstEnd = b.end
        if (epStart != null) absorb(b) else bursts.addLast(b)
    }

    private fun absorb(b: Burst) {
        if (b.peak > epPeak) epPeak = b.peak
        epPowerSum += b.powerSum
        epPowerN += b.powerN
    }

    private fun close(): EventData {
        val start = epStart ?: lastBurstEnd
        val avg = if (epPowerN > 0) 10.0 * log10(epPowerSum / epPowerN + 1e-12) else null
        val ev = EventData(
            band = band,
            startT = start.toLong(),
            endT = lastBurstEnd.toLong(),
            durationS = (lastBurstEnd - start).toLong(),
            peakDb = if (epPeak.isFinite()) epPeak else null,
            avgDb = avg,
            kind = EventKind.PULSE,
        )
        reset()
        return ev
    }

    private fun reset() {
        run = null
        runLong = false
        bursts.clear()
        epStart = null
        epPeak = Double.NEGATIVE_INFINITY
        epPowerSum = 0.0
        epPowerN = 0
        lastBurstEnd = 0.0
    }
}
