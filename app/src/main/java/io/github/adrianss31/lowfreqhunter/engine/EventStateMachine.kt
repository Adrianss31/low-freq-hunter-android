package io.github.adrianss31.lowfreqhunter.engine

import kotlin.math.log10
import kotlin.math.pow

/**
 * Macchina a stati per una banda: port 1:1 di smStep da js/night.js.
 * IDLE → (livello ≥ soglia) RISING → (per ≥ minOn s) ACTIVE →
 * (livello < soglia−isteresi) FALLING → (per ≥ minOff s) chiusura evento.
 */
class EventStateMachine(val band: String) {

    enum class State { IDLE, RISING, ACTIVE, FALLING }

    var state = State.IDLE
        private set

    private var riseT = 0L
    private var fallT = 0L
    private var evStart: Long? = null
    private var peak = Double.NEGATIVE_INFINITY
    private var pSum = 0.0
    private var pN = 0

    /** Inizio dell'evento in corso, o null se non attivo. */
    val activeSince: Long? get() = if (state == State.ACTIVE || state == State.FALLING) evStart else null
    val isActive: Boolean get() = state == State.ACTIVE

    /**
     * Avanza di un passo (un secondo aggregato). Ritorna l'evento chiuso se
     * questo passo lo chiude, altrimenti null. [onStart] è chiamata quando
     * l'evento viene aperto (per far partire la clip audio).
     */
    fun step(
        level: Double,
        thrOn: Double,
        hystDb: Double,
        minOnS: Int,
        minOffS: Int,
        t: Long,
        onStart: (band: String, startT: Long) -> Unit = { _, _ -> },
    ): EventData? {
        val thrOff = thrOn - hystDb
        when (state) {
            State.IDLE -> {
                if (level >= thrOn) {
                    state = State.RISING
                    riseT = t
                }
            }
            State.RISING -> {
                if (level < thrOn) {
                    state = State.IDLE
                } else if (t - riseT >= minOnS) {
                    state = State.ACTIVE
                    evStart = riseT
                    peak = level
                    pSum = 10.0.pow(level / 10.0)
                    pN = 1
                    onStart(band, riseT)
                }
            }
            State.ACTIVE -> {
                if (level > peak) peak = level
                pSum += 10.0.pow(level / 10.0)
                pN++
                if (level < thrOff) {
                    state = State.FALLING
                    fallT = t
                }
            }
            State.FALLING -> {
                if (level >= thrOff) {
                    state = State.ACTIVE
                } else if (t - fallT >= minOffS) {
                    return close(fallT)
                }
            }
        }
        return null
    }

    /** Chiude forzatamente l'evento in corso (stop sessione o gap). */
    fun forceClose(endT: Long): EventData? {
        return if (evStart != null && (state == State.ACTIVE || state == State.FALLING)) {
            close(endT)
        } else {
            reset()
            null
        }
    }

    fun reset() {
        state = State.IDLE
        evStart = null
        peak = Double.NEGATIVE_INFINITY
        pSum = 0.0
        pN = 0
    }

    private fun close(endT: Long): EventData {
        val start = evStart ?: endT
        val avg = if (pN > 0) 10.0 * log10(pSum / pN + 1e-12) else null
        val ev = EventData(
            band = band,
            startT = start,
            endT = endT,
            durationS = endT - start,
            peakDb = if (peak.isFinite()) peak else null,
            avgDb = avg,
        )
        reset()
        return ev
    }
}
