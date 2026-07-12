package io.github.adrianss31.lowfreqhunter.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adrianss31.lowfreqhunter.audio.CaptureEngine
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.dsp.Bands
import io.github.adrianss31.lowfreqhunter.engine.NightEngine
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
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
    var colSeq by remember { mutableIntStateOf(0) }
    var frozen by remember { mutableStateOf(false) }
    var selBand by remember { mutableStateOf(settings.engine.bands.firstOrNull()?.id) }
    var peak by remember { mutableStateOf(-120.0) }
    var capA by remember { mutableStateOf<Double?>(null) }
    var capB by remember { mutableStateOf<Double?>(null) }
    var geiger by remember { mutableStateOf(false) }

    val serviceRunning = bus.running
    val localRunning = capture != null
    val running = serviceRunning || localRunning

    fun onFrame(f: MonitorBus.SpectrumFrame) {
        if (frozen) return
        frame = f
        wfColumns.value = (wfColumns.value + wfColumn(f.spec, f.binHz)).takeLast(WF_COLS)
        colSeq++
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
        if (capture != null || serviceRunning) return
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
    // due velocità di smoothing: lo spettro interpolato a 60 fps muove curva
    // e meter in modo fluido, la media ~1 s tiene ferme le cifre dei testi
    val smoothFrame = rememberSmoothedFrame(frame, running)
    val textSmoother = remember { TextLevels() }
    val f = smoothFrame
    val levels: Map<String, Double> =
        if (f != null && running) enabled.associate { it.id to Bands.bandDb(f.spec, f.binHz, it.lo, it.hi) }
        else emptyMap()
    val rawFrame = frame
    val textLevels: Map<String, Double> =
        if (rawFrame != null && running) textSmoother.push(rawFrame, enabled) else emptyMap()
    val selLevel = selBand?.let { textLevels[it] }
    if (selLevel != null && selLevel > peak) peak = selLevel

    // geiger — relativo alla soglia della banda selezionata: silenzioso
    // finché il livello è >36 dB sotto soglia, accelera avvicinandosi,
    // satura a soglia raggiunta. (Prima mappava il livello assoluto e con
    // il rumore ambiente ticchettava sempre al massimo.)
    LaunchedEffect(geiger) {
        if (!geiger) return@LaunchedEffect
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
        try {
            while (geiger) {
                val band = selBand?.let { settings.engine.band(it) }
                val lvl = band?.let { b ->
                    frame?.let { fr -> Bands.bandDb(fr.spec, fr.binHz, b.lo, b.hi) }
                } ?: -120.0
                val thr = band?.thr ?: -55.0
                val norm = ((lvl - (thr - 36.0)) / 36.0).coerceIn(0.0, 1.0)
                val rate = norm * norm * 25.0
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
                val fr = f
                if (fr != null && running) {
                    drawSpectrum(fr.spec, fr.binHz, settings.specXMax.toDouble(), enabled, { Lfh.bandColor(it) })
                }
            }
        }

        // waterfall scorrevole (~30 s), con slittamento continuo tra colonne
        val wfShift = remember { Animatable(0f) }
        LaunchedEffect(colSeq) {
            if (colSeq == 0) return@LaunchedEffect
            wfShift.snapTo(1f)
            wfShift.animateTo(0f, tween(240, easing = LinearEasing))
        }
        Panel(Modifier.fillMaxWidth()) {
            CapsLabel("Waterfall · 20–200 Hz")
            Spacer(Modifier.height(6.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(135.dp)
                    .clipToBounds()
            ) {
                drawWaterfallColumns(
                    wfColumns.value, WF_COLS,
                    enabled.map { Pair(it.center, Lfh.bandColor(it.id)) },
                    shiftFrac = wfShift.value,
                )
            }
        }

        // valore grande + meters
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                DotValue(fmtDb(selLevel), color = if ((selLevel ?: -120.0) >= (settings.engine.band(selBand ?: "")?.thr ?: 0.0)) Lfh.Rec else Lfh.Accent, size = 46)
                Spacer(Modifier.width(10.dp))
                Column {
                    CapsLabel("dBFS · ${selBand?.let { settings.engine.channelLabel(it) } ?: "—"}")
                    CapsLabel("picco ${fmtDb(peak)}", color = Lfh.TextFaint)
                    fmtSpl(selLevel, settings.calib)?.let {
                        CapsLabel("$it (stima)", color = Lfh.Amber)
                    }
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
                        fmtDb(textLevels[b.id]),
                        color = if ((textLevels[b.id] ?: -120.0) >= b.thr) Lfh.Rec else Lfh.TextDim,
                        fontSize = 11.sp,
                        fontFamily = MonoFont,
                        modifier = Modifier.width(48.dp),
                    )
                }
            }
            if (serviceRunning && settings.engine.vib.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    CapsLabel("Vibrazioni", Modifier.width(96.dp))
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
                    b.label,
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
            "Sorgente: ${if (serviceRunning) bus.audioSource else capture?.sourceName ?: "—"} · " +
                if (settings.calib.enabled) "SPL stimato con offset ${settings.calib.offsetDb.toInt()} dB"
                else "livelli dBFS non calibrati in dB SPL",
            color = Lfh.TextFaint,
        )
    }
}
