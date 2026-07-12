package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import io.github.adrianss31.lowfreqhunter.dsp.Bands
import io.github.adrianss31.lowfreqhunter.dsp.Ema
import io.github.adrianss31.lowfreqhunter.engine.BandCfg
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import kotlin.math.exp

/**
 * Interpola gli spettri (≈4/s dal microfono) a ogni frame di composizione:
 * curva e meter si muovono fluidi a 60 fps invece di scattare a ogni analisi.
 * Solo presentazione — trigger e dati registrati restano sui valori grezzi.
 */
@Composable
fun rememberSmoothedFrame(
    target: MonitorBus.SpectrumFrame?,
    active: Boolean,
    tauMs: Float = 160f,
): MonitorBus.SpectrumFrame? {
    var display by remember { mutableStateOf<MonitorBus.SpectrumFrame?>(null) }
    val cur by rememberUpdatedState(target)
    LaunchedEffect(active) {
        if (!active) {
            display = null
            return@LaunchedEffect
        }
        var buf = FloatArray(0)
        var lastNs = 0L
        while (true) {
            withFrameNanos { now ->
                val t = cur ?: return@withFrameNanos
                if (buf.size != t.spec.size) {
                    buf = t.spec.copyOf()
                } else {
                    val k = 1f - exp(-((now - lastNs) / 1e6f) / tauMs)
                    for (i in buf.indices) buf[i] += (t.spec[i] - buf[i]) * k
                }
                lastNs = now
                display = MonitorBus.SpectrumFrame(buf.copyOf(), t.binHz, t.t)
            }
        }
    }
    return if (active) display ?: target else target
}

/**
 * Livelli di banda per i testi numerici: media esponenziale (~1 s) aggiornata
 * solo all'arrivo di un nuovo spettro — le cifre respirano invece di
 * sfarfallare a ogni misura.
 */
class TextLevels(private val tauS: Double = 1.2) {
    private val emas = HashMap<String, Ema>()
    private var lastT = 0L
    private var cache: Map<String, Double> = emptyMap()

    fun push(frame: MonitorBus.SpectrumFrame, bands: List<BandCfg>): Map<String, Double> {
        if (frame.t == lastT && cache.size == bands.size) return cache
        lastT = frame.t
        val out = HashMap<String, Double>()
        for (b in bands) {
            val raw = Bands.bandDb(frame.spec, frame.binHz, b.lo, b.hi)
            out[b.id] = emas.getOrPut(b.id) { Ema(tauS) }.push(raw, frame.t)
        }
        cache = out
        return out
    }
}
