package io.github.adrianss31.lowfreqhunter.ui

import android.graphics.Paint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.adrianss31.lowfreqhunter.audio.CaptureEngine
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.MeasurementContext
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.dsp.Bands
import io.github.adrianss31.lowfreqhunter.dsp.MovingMedian
import io.github.adrianss31.lowfreqhunter.engine.BandCfg
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.NightEngine
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import io.github.adrianss31.lowfreqhunter.service.MonitorService
import io.github.adrianss31.lowfreqhunter.ui.Render.monoText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val WF_COLS = 120
private const val MAX_BANDS = 8

/** Prossimo avvio/stato della programmazione, per la riga di stato in standby. */
fun AppSettings.nextText(nowMs: Long = System.currentTimeMillis()): String = when (progMode()) {
    "night" -> {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val nm = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val diff = (schedule.startMin - nm + 1440) % 1440
        "AVVIO ${fmtHm(schedule.startMin)} · TRA ${fmtDur(diff * 60L).uppercase()}"
    }
    "cont" -> "CONTINUA · ${fmtHm(continuous.splitMin)}" +
        if (continuous.split2Enabled) " / ${fmtHm(continuous.split2Min)}" else ""
    else -> "MANUALE"
}

@Composable
fun MonitorScreen() {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepo.get(ctx) }
    val loaded by repo.flow.collectAsState(initial = null)
    val settings = loaded ?: AppSettings()
    val bus by MonitorBus.state.collectAsState()
    val error by MonitorBus.error.collectAsState()
    val rec = bus.running && bus.mode == "rec"
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    val blink = rememberBlink(rec)

    // ── bande: bozza locale mentre si regola, salvata dopo una pausa ────────
    var draft by remember { mutableStateOf<List<BandCfg>?>(null) }
    var commitJob by remember { mutableStateOf<Job?>(null) }
    val bands = draft ?: settings.engine.bands
    LaunchedEffect(settings.engine.bands) { if (draft == settings.engine.bands) draft = null }
    fun setBands(nb: List<BandCfg>) {
        draft = nb
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(350)
            runCatching { repo.update { it.copy(engine = it.engine.copy(bands = nb)) } }.onFailure {
                draft = null
                shell.toast(it.message ?: "Valore non valido")
            }
        }
    }
    fun patchBand(id: String, f: (BandCfg) -> BandCfg) = setBands(bands.map { if (it.id == id) f(it) else it })
    val selId = shell.selBand?.takeIf { id -> bands.any { it.id == id } }
        ?: bands.firstOrNull { it.enabled }?.id ?: bands.firstOrNull()?.id
    val selB = bands.firstOrNull { it.id == selId }
    val edit = shell.bandEdit && selB != null
    val specMax = settings.specXMax.toDouble()

    // ── spettro: dal servizio se registra, altrimenti microfono locale ──────
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    var frame by remember { mutableStateOf<MonitorBus.SpectrumFrame?>(null) }
    var micError by remember { mutableStateOf(false) }
    var wfImg by remember { mutableStateOf<ImageBitmap?>(null) }
    val wfCols = remember { ArrayList<FloatArray>() }
    var colSeq by remember { mutableIntStateOf(0) }
    val domMed = remember { MovingMedian(12) }
    var localDom by remember { mutableDoubleStateOf(0.0) }

    fun onFrame(f: MonitorBus.SpectrumFrame) {
        frame = f
        wfCols.add(Render.wfColumn(f.spec, f.binHz))
        while (wfCols.size > WF_COLS) wfCols.removeAt(0)
        wfImg = WfBitmaps.fromColumns(wfCols, WF_COLS)
        colSeq++
        localDom = domMed.push(Bands.dominantHz(f.spec, f.binHz, NightEngine.WF_FMIN, NightEngine.WF_FMAX).first)
    }

    val fft = settings.engine.fftSize
    LaunchedEffect(bus.running, resumed, fft, loaded != null) {
        if (bus.running) {
            micError = false
            MonitorBus.spectrum.collect { f -> if (f != null) onFrame(f) }
        } else if (resumed && loaded != null) {
            val flow = MutableStateFlow<MonitorBus.SpectrumFrame?>(null)
            val cap = CaptureEngine(ctx, fft, settings.engine.smoothLive)
            val ok = runCatching {
                cap.start(this) { spec, binHz, t ->
                    val maxBins = minOf(spec.size, (2000.0 / binHz).toInt())
                    flow.value = MonitorBus.SpectrumFrame(spec.copyOf(maxBins), binHz, t)
                }
            }.getOrDefault(false)
            micError = !ok
            if (!ok) {
                cap.stop()
                return@LaunchedEffect
            }
            try {
                flow.collect { f -> if (f != null) onFrame(f) }
            } finally {
                cap.stop()
            }
        }
    }

    val smooth = rememberSmoothedFrame(frame, frame != null)
    val textSmoother = remember { TextLevels() }
    val txt: Map<String, Double> = frame?.let { textSmoother.push(it, bands) } ?: emptyMap()
    val dom = if (bus.running) bus.domHz else localDom

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = dockClearance()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        error?.let { msg ->
            LightCard(Modifier.press(Hap.TAP) { MonitorBus.error.value = null }, bg = Lfh.Key) {
                Mono("⚠ $msg", size = 10.sp, color = Lfh.OrangeInk, spacing = 0.04.em, lineHeight = 15.sp)
            }
        }

        // ── display principale ─────────────────────────────────────────────
        DarkPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val stateTxt = when {
                    rec -> (if (blink) "● " else "○ ") + "REC " + fmtDur((now - bus.startedAt) / 1000).uppercase()
                    bus.running -> "SOLO ASCOLTO · WIDGET"
                    micError -> "MICROFONO NON DISPONIBILE"
                    else -> "STANDBY · ASCOLTO LIVE"
                }
                val stateC by animateColorAsState(if (rec || micError) Lfh.Orange else Lfh.PaperDim, label = "stC")
                Mono(stateTxt, Modifier.weight(1f), color = stateC, weight = FontWeight.SemiBold, maxLines = 1)
                Mono(
                    if (rec) bus.audioSource.ifBlank { "—" } else settings.nextText(now),
                    size = 9.sp, color = Lfh.PaperDim, align = TextAlign.End, maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val v = selB?.takeIf { it.enabled }?.let { txt[it.id] }
                val over = v != null && selB != null && v >= selB.thr
                val readC by animateColorAsState(if (over) Lfh.Orange else Lfh.Paper, tween(250), label = "readC")
                Dot(
                    if (v != null && v.isFinite()) "%.1f".format(v) else "—",
                    Modifier.widthIn(min = 150.dp), size = 56.sp, color = readC, lineHeight = 0.9.em,
                )
                Column(Modifier.weight(1f).padding(bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Mono("DBFS · ${selB?.let { "${it.center.toInt()} HZ" } ?: "—"}", color = Lfh.Paper, maxLines = 1)
                    Mono("DOM ${if (dom > 0) "%.1f".format(dom) else "—"} HZ", color = Lfh.PaperDim, maxLines = 1)
                    fmtSpl(v, settings.calib)?.let { Mono("${it.uppercase()} (STIMA)", color = Lfh.Amber, maxLines = 1) }
                }
            }

            // spettro: tocca una banda per selezionarla, trascina in
            // orizzontale per il centro e in verticale per la soglia
            val bandsNow by rememberUpdatedState(bands)
            val selNow by rememberUpdatedState(selB)
            val editNow by rememberUpdatedState(edit)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(168.dp)
                    .pointerInput(specMax) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val f = down.position.x / w * specMax
                            val hit = bandsNow
                                .sortedBy { abs(it.center - f) }
                                .firstOrNull { abs(f - it.center) <= maxOf(it.width + 2, specMax * 0.04) }
                            val tgt = hit ?: if (editNow) selNow else null
                            if (tgt == null) return@awaitEachGesture
                            if (hit != null && !(editNow && selNow?.id == hit.id)) {
                                Haptics.tap(view)
                                shell.selBand = hit.id
                                shell.bandEdit = true
                            }
                            var axis = 0
                            var lastC = tgt.center
                            var lastT = tgt.thr
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val d = ch.position - down.position
                                if (axis == 0) {
                                    if (d.getDistance() < viewConfiguration.touchSlop) continue
                                    axis = if (abs(d.x) > abs(d.y)) 1 else 2
                                }
                                ch.consume()
                                if (axis == 1) {
                                    val c = (tgt.center + d.x / w * specMax).roundToInt().toDouble().coerceIn(10.0, specMax)
                                    if (c != lastC) {
                                        lastC = c
                                        Haptics.tick(view)
                                        patchBand(tgt.id) { it.copy(center = c) }
                                    }
                                } else {
                                    val t = (tgt.thr - d.y / (h - 18.dp.toPx()) * 70).roundToInt().toDouble().coerceIn(-100.0, -20.0)
                                    if (t != lastT) {
                                        lastT = t
                                        Haptics.tick(view)
                                        patchBand(tgt.id) { it.copy(thr = t) }
                                    }
                                }
                            }
                        }
                    },
            ) {
                drawLiveSpectrum(smooth, specMax, bands, txt, if (edit) selB?.id else null)
            }
            Mono(
                if (edit) "TRASCINA SULLO SPETTRO O REGOLA QUI SOTTO" else "TOCCA UNA BANDA PER REGOLARLA",
                size = 9.sp, color = if (edit) Lfh.Orange else Lfh.PaperFaint,
            )
            LiveWaterfall(wfImg, colSeq, bands)
        }

        // ── bande ──────────────────────────────────────────────────────────
        val tiles = buildList<BandTile> {
            bands.forEach { add(BandTile.Band(it)) }
            if (settings.engine.vib.enabled && bus.running) add(BandTile.Vib)
            if (bands.size < MAX_BANDS) add(BandTile.Add)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tiles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { tile ->
                        Box(Modifier.weight(1f)) {
                            when (tile) {
                                is BandTile.Band -> {
                                    val b = tile.b
                                    BandChip(b, txt[b.id], edit && selId == b.id) {
                                        val open = !(shell.bandEdit && selId == b.id)
                                        shell.selBand = b.id
                                        shell.bandEdit = open
                                    }
                                }
                                BandTile.Vib -> VibChip(bus.vibDb, settings.engine.vib.thr) {
                                    shell.setupOpen = "sens"
                                    shell.go(Tab.SETUP)
                                }
                                BandTile.Add -> AddBandTile(bands.size) {
                                    val used = bands.map { it.id }.toSet()
                                    val id = "ABCDEFGHIJKLMNOPQRSTUWXYZ".map { it.toString() }.firstOrNull { it !in used }
                                    if (id != null) {
                                        val center = minOf(specMax - 10, (bands.maxOfOrNull { it.center } ?: 0.0) + 50)
                                        setBands(bands + BandCfg(id, center = center, width = 5.0, thr = -55.0))
                                        shell.selBand = id
                                        shell.bandEdit = true
                                    }
                                }
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // ── regolazione della banda selezionata ────────────────────────────
        Expand(edit) {
            val b = selB ?: return@Expand
            LightCard(padV = 4.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Lfh.bandColor(b.id)))
                    Mono("BANDA · ${b.center.toInt()} HZ", Modifier.weight(1f), size = 11.sp, color = Lfh.Ink, weight = FontWeight.SemiBold)
                    LfhSwitch(b.enabled) { patchBand(b.id) { it.copy(enabled = !it.enabled) } }
                    Box(
                        Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .border(1.dp, Lfh.OrangeInk.copy(alpha = 0.4f), RoundedCornerShape(7.dp))
                            .press(Hap.TAP) {
                                if (bands.size <= 1) {
                                    shell.toast("Serve almeno una banda")
                                } else {
                                    val rest = bands.filter { it.id != b.id }
                                    setBands(rest)
                                    shell.selBand = rest.first().id
                                    shell.bandEdit = false
                                    shell.toast("Banda ${b.center.toInt()} Hz eliminata")
                                }
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Mono("ELIMINA", size = 9.sp, color = Lfh.OrangeInk, weight = FontWeight.SemiBold, spacing = 0.1.em) }
                }
                StepSliderRow("CENTRO", b.center, 10.0, specMax, 1.0, { "${it.toInt()} Hz" }) { v ->
                    patchBand(b.id) { it.copy(center = v) }
                }
                StepSliderRow("LARGHEZZA", b.width, 1.0, 30.0, 1.0, { "±${it.toInt()} Hz" }) { v ->
                    patchBand(b.id) { it.copy(width = v) }
                }
                StepSliderRow("SOGLIA", b.thr, -100.0, -20.0, 1.0, { "${it.toInt()} dB" }) { v ->
                    patchBand(b.id) { it.copy(thr = v) }
                }
                if (rec) {
                    Mono(
                        "IN REGISTRAZIONE · LE MODIFICHE VALGONO DALLA PROSSIMA SESSIONE",
                        Modifier.padding(top = 8.dp), size = 9.sp, color = Lfh.OrangeInk, lineHeight = 13.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        // ── sessione in corso ──────────────────────────────────────────────
        if (rec) SessionCard(settings, bus, now)

        // ── contesto ───────────────────────────────────────────────────────
        val c = settings.context
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Lfh.Card)
                .border(1.dp, Color.Black.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
                .press(Hap.TAP, shift = 0.dp, scale = 0.99f) { shell.sheet = Sheet.Context }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Mono(if (rec) "CONTESTO · PROSSIMA SESSIONE" else "CONTESTO DELLA REGISTRAZIONE", size = 9.sp)
                Sans(c.room.ifBlank { "Stanza non indicata" }, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
                Sans(
                    listOf(c.position, c.conditions).filter { it.isNotBlank() }.joinToString(" · ")
                        .ifBlank { "Tocca per descrivere posizione e condizioni" },
                    size = 12.sp, color = Lfh.InkDim, maxLines = 1,
                )
            }
            Sans("→", size = 16.sp, color = Lfh.InkDim, weight = FontWeight.Medium)
        }

        Mono(
            if (rec) "PUOI SPEGNERE LO SCHERMO · LA REGISTRAZIONE CONTINUA" +
                (bus.batteryPct?.let { " · BATTERIA $it%" } ?: "")
            else "LIVELLI IN DBFS · " +
                if (settings.calib.enabled) "SPL STIMATO, OFFSET ${settings.calib.offsetDb.toInt()} DB" else "NON CALIBRATI IN DB SPL",
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            size = 9.sp, spacing = 0.08.em, align = TextAlign.Center, lineHeight = 14.sp,
        )
    }
}

private sealed interface BandTile {
    data class Band(val b: BandCfg) : BandTile
    data object Vib : BandTile
    data object Add : BandTile
}

// ── Disegno dello spettro live ───────────────────────────────────────────

private fun DrawScope.drawLiveSpectrum(
    f: MonitorBus.SpectrumFrame?,
    fx: Double,
    bands: List<BandCfg>,
    levels: Map<String, Double>,
    selId: String?,
) {
    val w = size.width
    val h = size.height
    val ph = h - 14.dp.toPx()
    val top = 4.dp.toPx()
    fun y(v: Double) = (ph - (v.coerceIn(-100.0, -30.0) + 100.0) / 70.0 * (ph - top)).toFloat()
    fun x(hz: Double) = (hz / fx * w).toFloat()
    val labPx = 9.sp.toPx()

    // griglia
    val step = when {
        fx <= 300 -> 50
        fx <= 600 -> 100
        else -> 200
    }
    var g = step
    while (g < fx) {
        val gx = x(g.toDouble())
        drawRect(Lfh.Paper.copy(alpha = 0.06f), Offset(gx, 0f), Size(1f, ph))
        monoText("$g", gx, h - 2.dp.toPx(), Lfh.PaperFaint, labPx, Paint.Align.CENTER)
        g += step
    }
    for (v in listOf(-40.0, -60.0, -80.0)) drawRect(Lfh.Paper.copy(alpha = 0.04f), Offset(0f, y(v)), Size(w, 1f))

    // bande
    for (b in bands) {
        val col = Lfh.bandColor(b.id)
        val sel = b.id == selId
        val x0 = x(b.lo)
        val x1 = x(b.hi)
        if (x0 > w) continue
        val a = if (b.enabled) 1f else 0.25f
        drawRect(col.copy(alpha = (if (sel) 0.22f else 0.11f) * a), Offset(x0, 0f), Size(maxOf(2f, x1 - x0), ph))
        val ty = y(b.thr)
        val ext = 8.dp.toPx()
        drawLine(
            col.copy(alpha = a), Offset(x0 - ext, ty), Offset(x1 + ext, ty),
            strokeWidth = (if (sel) 1.5f else 1f).dp.toPx(),
            pathEffect = if (sel) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        )
        val lv = levels[b.id]
        if (lv != null && b.enabled && lv.isFinite()) {
            drawRect(if (lv >= b.thr) Lfh.Orange else col, Offset(x0, y(lv) - 1.dp.toPx()), Size(maxOf(2f, x1 - x0), 2.dp.toPx()))
        }
        if (sel) {
            val cx = (x0 + x1) / 2
            drawCircle(col, 4.5.dp.toPx(), Offset(x1 + ext, ty))
            drawRect(col, Offset(cx - 0.5f, 0f), Size(1f, ph))
            val lbl = "${b.center.toInt()} HZ ±${b.width.toInt()} · ${b.thr.toInt()} DB"
            val tw = Render.run { textWidth(lbl, labPx) }
            val lx = (cx - tw / 2).coerceIn(2f, maxOf(2f, w - tw - 2f))
            drawRect(Lfh.Panel, Offset(lx - 3.dp.toPx(), 2.dp.toPx()), Size(tw + 6.dp.toPx(), 13.dp.toPx()))
            monoText(lbl, lx, 12.dp.toPx(), col, labPx)
        }
    }

    // curva: per pixel, massimo dei bin quando sono più fitti dei pixel,
    // interpolazione lineare quando sono più radi
    if (f == null || f.spec.size < 2) return
    val n = (w / 1.5f).toInt().coerceAtLeast(2)
    val line = Path()
    val area = Path()
    for (i in 0..n) {
        val px = i * w / n
        val f0 = px / w * fx
        val f1 = (i + 1) * w / n / w * fx
        val i0 = floor(f0 / f.binHz).toInt().coerceIn(0, f.spec.size - 1)
        val i1 = ceil(f1 / f.binHz).toInt().coerceIn(0, f.spec.size - 1)
        val v = if (i1 - i0 >= 2) {
            var m = -200f
            for (k in i0 until i1) if (f.spec[k] > m) m = f.spec[k]
            m.toDouble()
        } else {
            val fi = f0 / f.binHz
            val k = floor(fi).toInt().coerceIn(0, f.spec.size - 2)
            val fr = (fi - k).coerceIn(0.0, 1.0)
            f.spec[k] * (1 - fr) + f.spec[k + 1] * fr
        }
        val py = y(v)
        if (i == 0) {
            line.moveTo(px, py)
            area.moveTo(0f, ph)
            area.lineTo(px, py)
        } else {
            line.lineTo(px, py)
            area.lineTo(px, py)
        }
    }
    area.lineTo(w, ph)
    area.close()
    drawPath(area, Brush.verticalGradient(listOf(Lfh.Paper.copy(alpha = 0.16f), Lfh.Paper.copy(alpha = 0f)), 0f, ph))
    drawPath(line, Lfh.Paper, style = Stroke(1.3.dp.toPx()))
}

/** Waterfall live ~30 s che scorre in continuo tra un'analisi e l'altra. */
@Composable
private fun LiveWaterfall(img: ImageBitmap?, seq: Int, bands: List<BandCfg>) {
    val shift = remember { Animatable(0f) }
    LaunchedEffect(seq) {
        if (seq == 0) return@LaunchedEffect
        shift.snapTo(1f)
        shift.animateTo(0f, tween(240, easing = LinearEasing))
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .clipToBounds(),
    ) {
        drawRect(Lfh.WfBg)
        if (img != null) {
            val colW = size.width / WF_COLS
            drawImage(
                img,
                dstOffset = IntOffset((shift.value * colW).roundToInt(), 0),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.None,
            )
        }
        for (b in bands) {
            if (!b.enabled || b.center < NightEngine.WF_FMIN || b.center > NightEngine.WF_FMAX) continue
            val yy = size.height - ((b.center - NightEngine.WF_FMIN) / (NightEngine.WF_FMAX - NightEngine.WF_FMIN) * size.height).toFloat()
            drawRect(Lfh.bandColor(b.id), Offset(0f, yy - 1.dp.toPx()), Size(5.dp.toPx(), 2.dp.toPx()))
        }
        monoText("30 S · 20–200 HZ", 4.dp.toPx(), 10.dp.toPx(), Lfh.Paper.copy(alpha = 0.55f), 9.sp.toPx())
    }
}

// ── Tessere delle bande ──────────────────────────────────────────────────

@Composable
private fun BandChip(b: BandCfg, v: Double?, on: Boolean, onClick: () -> Unit) {
    val over = b.enabled && v != null && v >= b.thr
    val col = Lfh.bandColor(b.id)
    val fg = if (on) Lfh.Paper else Lfh.Ink
    val valC by animateColorAsState(
        when {
            on && over -> Lfh.Orange
            on -> Lfh.Paper
            over -> Lfh.OrangeInk
            else -> Lfh.Ink
        },
        label = "chipV",
    )
    Key(Modifier.fillMaxWidth().height(76.dp), bg = if (on) Lfh.Ink else Lfh.Key, radius = 12.dp, sunk = on, onClick = onClick) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(col.copy(alpha = if (b.enabled) 1f else 0.3f)))
                Mono("${b.center.toInt()} Hz", size = 10.sp, color = fg, weight = FontWeight.SemiBold, spacing = 0.04.em, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(if (!b.enabled) "OFF" else fmtDb(v), size = 21.sp, color = valC, lineHeight = 1.em)
                if (b.enabled) Mono("DBFS", size = 8.sp, color = if (on) Lfh.PaperDim else Lfh.InkDim, spacing = 0.sp)
            }
            MiniMeter(
                frac = if (b.enabled && v != null) ((v + 90) / 60).toFloat() else 0f,
                thrFrac = ((b.thr + 90) / 60).toFloat(),
                color = col.copy(alpha = if (b.enabled) 1f else 0.3f),
                track = if (on) Color(0xFF33322E) else Color.Black.copy(alpha = 0.1f),
                mark = fg,
            )
        }
    }
}

@Composable
private fun VibChip(v: Double?, thr: Double, onClick: () -> Unit) {
    Key(Modifier.fillMaxWidth().height(76.dp), radius = 12.dp, onClick = onClick) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Lfh.VibColor))
                Mono("VIB", size = 10.sp, color = Lfh.Ink, weight = FontWeight.SemiBold, spacing = 0.04.em)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(fmtDb(v), size = 21.sp, color = if (v != null && v >= thr) Lfh.OrangeInk else Lfh.Ink, lineHeight = 1.em)
                Mono("DB", size = 8.sp, spacing = 0.sp)
            }
            MiniMeter(
                frac = v?.let { ((it + 90) / 60).toFloat() } ?: 0f,
                thrFrac = ((thr + 90) / 60).toFloat(),
                color = Lfh.VibColor, track = Color.Black.copy(alpha = 0.1f), mark = Lfh.Ink,
            )
        }
    }
}

@Composable
private fun MiniMeter(frac: Float, thrFrac: Float, color: Color, track: Color, mark: Color) {
    val fill by androidx.compose.animation.core.animateFloatAsState(frac.coerceIn(0f, 1f), tween(250, easing = LinearEasing), label = "mini")
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val r = CornerRadius(2.dp.toPx())
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * fill, size.height), cornerRadius = r)
        drawRect(mark, Offset(size.width * thrFrac.coerceIn(0f, 1f) - 1.dp.toPx() / 2, 0f), Size(2.dp.toPx(), size.height))
    }
}

@Composable
private fun AddBandTile(count: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .drawBehind {
                drawRoundRect(
                    Color.Black.copy(alpha = 0.28f),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .press(Hap.TAP, shift = 0.dp, scale = 0.97f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Sans("+", size = 22.sp)
        Spacer(Modifier.height(4.dp))
        Mono("BANDA $count/$MAX_BANDS", size = 9.sp, weight = FontWeight.SemiBold, spacing = 0.1.em)
    }
}

// ── Sessione in corso: spettrogramma, presenza, note ─────────────────────

@Composable
private fun SessionCard(settings: AppSettings, bus: MonitorBus.State, now: Long) {
    val slices by MonitorBus.slices.collectAsState()
    val events by MonitorBus.events.collectAsState()
    val notes by MonitorBus.notes.collectAsState()
    var noteOpen by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val shell = LocalShell.current
    val evs = events.filter { it.band != Channels.GAP }
    val since = bus.activeBands.values.minOrNull()
    val img = remember(slices.size, slices.lastOrNull()?.first) { WfBitmaps.fromSlices(slices.map { it.second }, minCols = 60) }

    DarkPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("SESSIONE · ${evs.size} EVENTI · ${notes.size} NOTE", Modifier.weight(1f), color = Lfh.PaperDim, maxLines = 1)
            Mono(
                if (since != null) "● PRESENTE DA ${fmtDur(now / 1000 - since).uppercase()}" else "SILENZIO",
                color = if (since != null) Lfh.Orange else Lfh.PaperDim, weight = FontWeight.SemiBold, spacing = 0.1.em, maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(4.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Lfh.WfBg)
                if (img != null) {
                    drawImage(
                        img,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
            val inf = rememberInfiniteTransition(label = "cursor")
            val a by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "cursorA")
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Lfh.Orange.copy(alpha = a), Offset(size.width - 2.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
            }
        }
        val chans = settings.engine.enabledBands().map { it.id } +
            if (settings.engine.vib.enabled) listOf(Channels.VIB) else emptyList()
        val inf = rememberInfiniteTransition(label = "act")
        val pulse by inf.animateFloat(0.6f, 1f, infiniteRepeatable(tween(440), RepeatMode.Reverse), label = "actA")
        Canvas(Modifier.fillMaxWidth().height((maxOf(1, chans.size) * 16 - 6).dp)) {
            val lw = 46.dp.toPx()
            val rh = 10.dp.toPx()
            val t0 = bus.startedAt / 1000
            val span = maxOf(1L, now / 1000 - t0).toFloat()
            fun xs(t: Long) = lw + (t - t0) / span * (size.width - lw)
            chans.forEachIndexed { i, ch ->
                val yy = i * 16.dp.toPx()
                monoText(settings.engine.channelShort(ch).let { if (ch == Channels.VIB) it else "$it HZ" }, 0f, yy + 8.dp.toPx(), Lfh.PaperDim, 9.sp.toPx())
                drawRect(Color(0xFF1D1C19), Offset(lw, yy), Size(size.width - lw, rh))
                val thr = if (ch == Channels.VIB) settings.engine.vib.thr else settings.engine.band(ch)?.thr ?: -55.0
                for (e in evs) {
                    if (e.band != ch) continue
                    val x0 = xs(e.startT)
                    val x1 = xs(e.endT)
                    val heat = (((e.peakDb ?: thr) - thr + 10) / 20).toFloat().coerceIn(0f, 1f)
                    drawRect(Render.wfColor(0.35f + 0.65f * heat), Offset(x0, yy), Size(maxOf(3f, x1 - x0), rh))
                }
                bus.activeBands[ch]?.let { s ->
                    val x0 = xs(s)
                    drawRect(Lfh.channelColor(ch).copy(alpha = pulse), Offset(x0, yy), Size(maxOf(3f, size.width - x0), rh))
                }
            }
            for ((t, _) in notes) drawRect(Color.White, Offset(xs(t), 0f), Size(1.5.dp.toPx(), size.height))
        }
        val keyBg by animateColorAsState(if (noteOpen) Lfh.Panel3 else Lfh.Key, label = "noteBg")
        Key(Modifier.fillMaxWidth().height(44.dp).padding(top = 2.dp), bg = keyBg, depth = if (noteOpen) 0.dp else 2.dp, onClick = { noteOpen = !noteOpen }) {
            Mono(
                if (noteOpen) "CHIUDI" else "+ NOTA CON ORARIO", size = 10.sp,
                color = if (noteOpen) Lfh.Paper else Lfh.Ink, weight = FontWeight.SemiBold,
            )
        }
        Expand(noteOpen) {
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldBox(note, { note = it }, "Annota: es. spento climatizzatore", Modifier.weight(1f), dark = true, height = 44.dp)
                TextKey("SALVA", Modifier.height(44.dp).widthIn(min = 64.dp), bg = Lfh.Orange, hap = Hap.HEAVY) {
                    val t = note.trim()
                    if (t.isNotEmpty()) {
                        MonitorService.instance?.addMarker("nota: $t")
                        note = ""
                        noteOpen = false
                        shell.toast("Nota salvata alle ${fmtClockShort(System.currentTimeMillis())}")
                    }
                }
            }
        }
        notes.takeLast(3).reversed().forEach { (t, text) ->
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF23221F)))
                Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Mono(fmtClockShort(t * 1000), size = 11.sp, color = Lfh.PaperDim, spacing = 0.sp)
                    Sans(text, size = 12.sp, color = Color(0xFFCFCCC2))
                }
            }
        }
    }
}

// ── Foglio "contesto della registrazione" ────────────────────────────────

@Composable
fun ContextSheet() {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepo.get(ctx) }
    val loaded by repo.flow.collectAsState(initial = null)
    val s = loaded ?: return
    var room by remember { mutableStateOf(s.context.room) }
    var position by remember { mutableStateOf(s.context.position) }
    var conditions by remember { mutableStateOf(s.context.conditions) }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sans("Contesto della registrazione", size = 19.sp, weight = FontWeight.Bold, spacing = (-0.02).em)
        Sans(
            "Salvato con ogni sessione: aiuta a confrontare notti diverse.",
            size = 12.sp, color = Lfh.InkDim, lineHeight = 17.sp,
        )
        for ((label, value, set, ph) in listOf(
            Quad("STANZA", room, { v: String -> room = v }, "es. camera da letto"),
            Quad("POSIZIONE E ORIENTAMENTO", position, { v: String -> position = v }, "es. comodino, verticale"),
            Quad("FINESTRE, IMPIANTI, CONDIZIONI", conditions, { v: String -> conditions = v }, "es. finestre chiuse, frigo acceso"),
        )) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Mono(label, size = 9.sp, color = Lfh.Ink, weight = FontWeight.SemiBold)
                FieldBox(value, set, ph, Modifier.fillMaxWidth())
            }
        }
        Key(Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp), bg = Lfh.Ink, radius = 12.dp, onClick = {
            scope.launch {
                runCatching { repo.update { it.copy(context = MeasurementContext(room.trim(), position.trim(), conditions.trim())) } }
                    .onSuccess {
                        shell.sheet = null
                        shell.toast(
                            if (MonitorBus.state.value.running) "Contesto salvato: vale dalla prossima sessione"
                            else "Contesto salvato per le prossime sessioni",
                        )
                    }
                    .onFailure { shell.toast(it.message ?: "Salvataggio non riuscito") }
            }
        }) {
            Mono("SALVA CONTESTO", size = 11.sp, color = Lfh.Paper, weight = FontWeight.SemiBold)
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
