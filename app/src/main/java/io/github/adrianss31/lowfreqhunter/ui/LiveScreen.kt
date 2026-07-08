package io.github.adrianss31.lowfreqhunter.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adrianss31.lowfreqhunter.audio.CaptureEngine
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.dsp.Bands
import io.github.adrianss31.lowfreqhunter.engine.NightEngine
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import io.github.adrianss31.lowfreqhunter.widget.LiveWidgetController
import io.github.adrianss31.lowfreqhunter.ui.Render.drawSpectrum
import io.github.adrianss31.lowfreqhunter.ui.Render.drawWaterfallColumns
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private const val WF_COLS = 120

/** Colonna waterfall 64 bin (20–200 Hz) da uno spettro. */
private fun wfColumn(spec: FloatArray, binHz: Double): FloatArray {
    val out = FloatArray(NightEngine.WF_NBINS)
    val range = NightEngine.WF_FMAX - NightEngine.WF_FMIN
    for (b in 0 until NightEngine.WF_NBINS) {
        val fL = NightEngine.WF_FMIN + b / NightEngine.WF_NBINS.toDouble() * range
        val fH = NightEngine.WF_FMIN + (b + 1) / NightEngine.WF_NBINS.toDouble() * range
        val i0 = maxOf(0, (fL / binHz).roundToInt())
        val i1 = minOf(spec.size - 1, (fH / binHz).roundToInt())
        var p = 0.0
        var n = 0
        for (i in i0..i1) {
            p += 10.0.pow(spec[i] / 10.0)
            n++
        }
        out[b] = if (n > 0) (10.0 * log10(p / n + 1e-12)).toFloat() else -120f
    }
    return out
}

@Composable
fun LiveScreen() {
    val ctx = LocalContext.current
    val settings by SettingsRepo.get(ctx).flow.collectAsState(initial = AppSettings())
    val bus by MonitorBus.state.collectAsState()
    val scope = rememberCoroutineScope()

    var capture by remember { mutableStateOf<CaptureEngine?>(null) }
    val localFrames = remember { MutableStateFlow<MonitorBus.SpectrumFrame?>(null) }
    var frame by remember { mutableStateOf<MonitorBus.SpectrumFrame?>(null) }
    val wfColumns = remember { mutableStateOf<List<FloatArray>>(emptyList()) }
    var frozen by remember { mutableStateOf(false) }
    var selBand by remember { mutableStateOf(settings.engine.bands.firstOrNull()?.id) }
    var peak by remember { mutableStateOf(-120.0) }
    var capA by remember { mutableStateOf<Double?>(null) }
    var capB by remember { mutableStateOf<Double?>(null) }
    var geiger by remember { mutableStateOf(false) }

    val serviceRunning = bus.running
    val widgetLive = LiveWidgetController.isLive()
    val localRunning = capture != null
    val running = serviceRunning || localRunning || widgetLive

    fun onFrame(f: MonitorBus.SpectrumFrame) {
        if (frozen) return
        frame = f
        wfColumns.value = (wfColumns.value + wfColumn(f.spec, f.binHz)).takeLast(WF_COLS)
    }

    LaunchedEffect(serviceRunning) {
        if (serviceRunning) {
            capture?.stop()
            capture = null
            MonitorBus.spectrum.collect { f -> if (f != null) onFrame(f) }
        } else {
            localFrames.collect { f -> if (f != null) onFrame(f) }
        }
    }

    fun startLocal() {
        if (capture != null || serviceRunning || widgetLive) return
        val cap = CaptureEngine(ctx, settings.engine.fftSize, settings.engine.smoothLive)
        val ok = cap.start(scope) { spec, binHz, now ->
            val maxBins = minOf(spec.size, (2000.0 / binHz).toInt())
            localFrames.value = MonitorBus.SpectrumFrame(spec.copyOf(maxBins), binHz, now)
        }
        if (ok) {
            capture = cap
        } else {
            cap.stop()
            Toast.makeText(ctx, "Microfono non disponibile", Toast.LENGTH_LONG).show()
        }
    }

    fun stopLocal() {
        capture?.stop()
        capture = null
        geiger = false
    }

    DisposableEffect(Unit) {
        onDispose { capture?.stop(); capture = null }
    }

    // decadimento del picco
    LaunchedEffect(running) {
        while (running) {
            delay(400)
            peak = maxOf(peak - 0.3, -120.0)
        }
    }

    // livelli per banda dallo spettro corrente
    val enabled = settings.engine.enabledBands()
    if (selBand == null || enabled.none { it.id == selBand }) {
        selBand = enabled.firstOrNull()?.id
    }
    val f = frame
    val levels: Map<String, Double> =
        if (f != null && running) enabled.associate { it.id to Bands.bandDb(f.spec, f.binHz, it.lo, it.hi) }
        else emptyMap()
    val selLevel = selBand?.let { levels[it] }
    if (selLevel != null && selLevel > peak) peak = selLevel

    // geiger
    LaunchedEffect(geiger) {
        if (!geiger) return@LaunchedEffect
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        try {
            while (geiger) {
                val lvl = selBand?.let { id ->
                    frame?.let { fr ->
                        settings.engine.band(id)?.let { b -> Bands.bandDb(fr.spec, fr.binHz, b.lo, b.hi) }
                    }
                } ?: -120.0
                val norm = ((lvl + 120.0) / 80.0).coerceIn(0.0, 1.0)
                val rate = norm * norm * 30.0
                if (rate > 0.3) {
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 25)
                    delay(((1000.0 / rate) * (0.4 + Math.random() * 1.2)).toLong())
                } else {
                    delay(400)
                }
            }
        } finally {
            tg.release()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // spettro
        Panel(Modifier.fillMaxWidth()) {
            CapsLabel("Spettro · 0–${settings.specXMax} Hz")
            Spacer(Modifier.height(6.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .background(Lfh.Bg)
            ) {
                val fr = frame
                if (fr != null && running) {
                    drawSpectrum(fr.spec, fr.binHz, settings.specXMax.toDouble(), enabled, { Lfh.bandColor(it) })
                }
            }
        }

        // waterfall scorrevole (~30 s)
        Panel(Modifier.fillMaxWidth()) {
            CapsLabel("Waterfall · 20–200 Hz")
            Spacer(Modifier.height(6.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(90.dp)
            ) {
                drawWaterfallColumns(
                    wfColumns.value, WF_COLS,
                    enabled.map { Pair(it.center, Lfh.bandColor(it.id)) },
                )
            }
        }

        // valore grande + meters
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                DotValue(fmtDb(selLevel), color = if ((selLevel ?: -120.0) >= (settings.engine.band(selBand ?: "")?.thr ?: 0.0)) Lfh.Rec else Lfh.Accent, size = 46)
                Spacer(Modifier.width(10.dp))
                Column {
                    CapsLabel("dBFS · ${selBand ?: "—"}")
                    CapsLabel("picco ${fmtDb(peak)}", color = Lfh.TextFaint)
                }
            }
            Spacer(Modifier.height(10.dp))
            for (b in enabled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    CapsLabel(b.label, Modifier.width(96.dp), color = if (b.id == selBand) Lfh.Text else Lfh.TextDim)
                    SegMeter(
                        frac = (((levels[b.id] ?: -120.0) + 120.0) / 120.0).toFloat(),
                        color = Lfh.bandColor(b.id),
                        thrFrac = ((b.thr + 120.0) / 120.0).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        fmtDb(levels[b.id]),
                        color = if ((levels[b.id] ?: -120.0) >= b.thr) Lfh.Rec else Lfh.TextDim,
                        fontSize = 11.sp,
                        fontFamily = MonoFont,
                        modifier = Modifier.width(48.dp),
                    )
                }
            }
            if (serviceRunning && settings.engine.vib.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    CapsLabel("V · vibrazioni", Modifier.width(96.dp))
                    SegMeter(
                        frac = (((bus.vibDb ?: -120.0) + 120.0) / 120.0).toFloat(),
                        color = Lfh.VibColor,
                        thrFrac = ((settings.engine.vib.thr + 120.0) / 120.0).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(fmtDb(bus.vibDb), color = Lfh.TextDim, fontSize = 11.sp, fontFamily = MonoFont, modifier = Modifier.width(48.dp))
                }
            }
        }

        // selettore banda
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in enabled) {
                HwButton(
                    b.id,
                    color = if (b.id == selBand) Lfh.Bg else Lfh.bandColor(b.id),
                    borderColor = Lfh.bandColor(b.id),
                    bg = if (b.id == selBand) Lfh.bandColor(b.id) else Lfh.Surface,
                ) {
                    selBand = b.id
                    peak = -120.0
                }
            }
        }

        // comandi
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (serviceRunning) {
                CapsLabel("Il microfono è del log notturno in corso", Modifier.padding(top = 12.dp), color = Lfh.Amber)
            } else if (widgetLive) {
                CapsLabel("Live attivo dal widget · tap disco per fermare", Modifier.padding(top = 12.dp), color = Lfh.Amber)
            } else {
                HwButton(
                    if (localRunning) "⬛ stop" else "▶ avvia",
                    color = if (localRunning) Lfh.Rec else Lfh.Accent,
                    heavy = true,
                ) { if (localRunning) stopLocal() else startLocal() }
            }
            HwButton(if (frozen) "riprendi" else "freeze", enabled = running) { frozen = !frozen }
            HwButton(if (geiger) "geiger off" else "geiger", enabled = running) { geiger = !geiger }
        }

        // confronto A/B
        Panel(Modifier.fillMaxWidth()) {
            CapsLabel("Confronto acceso/spento")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HwButton("Cattura A", enabled = running) { capA = selLevel }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("A ${fmtDb(capA)}   B ${fmtDb(capB)}", color = Lfh.TextDim, fontSize = 12.sp, fontFamily = MonoFont)
                    val d = if (capA != null && capB != null) capB!! - capA!! else null
                    Text(
                        "Δ " + (d?.let { (if (it > 0) "+" else "") + "%.1f dB".format(it) } ?: "—"),
                        color = Lfh.Amber, fontSize = 15.sp, fontFamily = MonoFont,
                    )
                }
                HwButton("Cattura B", enabled = running) { capB = selLevel }
            }
        }

        CapsLabel(
        "Sorgente: ${if (serviceRunning) bus.audioSource else if (widgetLive) LiveWidgetController.state.value.source else capture?.sourceName ?: "—"} · livelli dBFS non calibrati in dB SPL",
        color = Lfh.TextFaint,
        )
    }
}
