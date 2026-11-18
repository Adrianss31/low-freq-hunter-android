package io.github.adrianss31.lowfreqhunter.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import io.github.adrianss31.lowfreqhunter.dsp.ControfaseEngine
import io.github.adrianss31.lowfreqhunter.dsp.ControfaseSnapshot
import io.github.adrianss31.lowfreqhunter.dsp.PeakCandidate
import io.github.adrianss31.lowfreqhunter.dsp.TonePhaseState
import io.github.adrianss31.lowfreqhunter.dsp.ToneSnapshot
import io.github.adrianss31.lowfreqhunter.service.AudioDeviceChoice
import io.github.adrianss31.lowfreqhunter.service.ControfaseBus
import io.github.adrianss31.lowfreqhunter.service.ControfaseService
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private val CyanPhase = Color(0xFFB9DCE6)

@Composable
fun ControfaseScreen() {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val snap by ControfaseBus.snapshot.collectAsState()
    val err by ControfaseBus.error.collectAsState()
    val inputs by ControfaseBus.inputs.collectAsState()
    val outputs by ControfaseBus.outputs.collectAsState()
    val selIn by ControfaseBus.selectedInputId.collectAsState()
    val selOut by ControfaseBus.selectedOutputId.collectAsState()

    var nightMode by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var manualFreq by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        ControfaseBus.refreshDevices(ctx)
    }

    fun hasMicPermission(): Boolean =
        snap.simMode || ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun doScan() {
        if (!hasMicPermission()) {
            shell.toast("Concedi il permesso microfono dalle impostazioni dell'app.")
            return
        }
        ControfaseService.startScan(ctx)
    }

    fun doStartSelected() {
        if (!hasMicPermission()) {
            shell.toast("Concedi il permesso microfono dalle impostazioni dell'app.")
            return
        }
        ControfaseService.startSelected(ctx)
    }

    val engine = ControfaseBus.engine
    val busy = snap.scanning || snap.calibrating || snap.guideStep != null
    val enabled = snap.channels.filter { it.enabled }
    val allCal = enabled.isNotEmpty() && enabled.all { it.pathDb != null }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = dockClearance()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        err?.let { msg ->
            LightCard(Modifier.press(Hap.TAP) { ControfaseBus.error.value = null }, bg = Lfh.Key) {
                Mono("⚠ $msg", size = 10.sp, color = Lfh.OrangeInk, spacing = 0.04.em, lineHeight = 15.sp)
            }
        }

        // ── Display principale ─────────────────────────────────────────────
        DarkPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val active = snap.autoOn && snap.outputOn
                val statusLabel = when {
                    snap.simMode && snap.running -> "SIMULAZIONE ATTIVA"
                    active -> "CONTROFASE ATTIVA"
                    snap.running -> "MICROFONO IN ASCOLTO"
                    else -> "CONTROFASE · STANDBY"
                }
                Led(snap.running)
                Spacer(Modifier.width(6.dp))
                Mono(
                    statusLabel,
                    Modifier.weight(1f),
                    color = if (active) Lfh.Amber else Lfh.PaperDim,
                    weight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Mono(
                    snap.audioSource.ifBlank { "20–500 HZ" },
                    size = 9.sp,
                    color = Lfh.PaperDim,
                    align = TextAlign.End,
                    maxLines = 1,
                )
            }

            Sans(snap.headline, size = 18.sp, color = Lfh.Paper, weight = FontWeight.SemiBold)
            Mono(snap.sub, size = 10.sp, color = Lfh.PaperDim, spacing = 0.02.em, lineHeight = 14.sp)

            ControfaseSpectrum(
                before = snap.specBefore,
                now = snap.specNow,
                binHz = snap.specBinHz,
                peaks = snap.peaks,
                channels = snap.channels,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val mainLabel = when {
                    busy -> "IN CORSO…"
                    enabled.isEmpty() -> "CERCA I TONI"
                    allCal && !snap.autoOn -> "AVVIA"
                    else -> "CALIBRA E CANCELLA"
                }
                if (!(snap.autoOn && snap.outputOn)) {
                    Key(
                        Modifier.weight(1f).height(42.dp),
                        bg = Lfh.Orange,
                        enabled = !busy,
                        onClick = { if (enabled.isEmpty()) doScan() else doStartSelected() },
                    ) {
                        Mono(mainLabel, size = 10.sp, color = Lfh.Ink, weight = FontWeight.SemiBold, maxLines = 1)
                    }
                } else {
                    Key(
                        Modifier.weight(1f).height(42.dp),
                        bg = Lfh.Key,
                        onClick = { nightMode = true },
                    ) {
                        Mono("MODALITÀ NOTTE", size = 10.sp, color = Lfh.Ink, weight = FontWeight.SemiBold, maxLines = 1)
                    }
                }

                if (enabled.isNotEmpty() || (snap.autoOn && snap.outputOn)) {
                    Key(
                        Modifier.height(42.dp),
                        bg = Lfh.Panel2,
                        enabled = !busy,
                        onClick = { doScan() },
                    ) {
                        Mono("CERCA", Modifier.padding(horizontal = 12.dp), size = 10.sp, color = Lfh.Paper, weight = FontWeight.SemiBold)
                    }
                }

                if (snap.running || snap.outputOn) {
                    Key(
                        Modifier.height(42.dp),
                        bg = Lfh.Panel3,
                        onClick = {
                            engine.stopCancellation()
                            ControfaseService.stop(ctx)
                        },
                    ) {
                        Mono("FERMA", Modifier.padding(horizontal = 12.dp), size = 10.sp, color = Lfh.Paper, weight = FontWeight.SemiBold)
                    }
                }
            }

            if (snap.message.isNotBlank()) {
                Mono(snap.message, size = 10.sp, color = CyanPhase, spacing = 0.02.em, lineHeight = 14.sp)
            }
        }

        // ── Picchi trovati nello spettro (selezione manuale) ───────────────
        if (snap.peaks.isNotEmpty()) {
            LightCard(gap = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono("PICCHI TROVATI", Modifier.weight(1f), size = 9.sp, weight = FontWeight.SemiBold)
                    Mono("TOCCA PER SCEGLIERE", size = 9.sp, color = Lfh.InkFaint)
                }
                for (p in snap.peaks) {
                    val ch = snap.channels.firstOrNull { abs(it.freq - p.freq) < ControfaseEngine.MIN_SPACING }
                    val selected = ch != null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Lfh.Ink else Lfh.Field)
                            .press(Hap.TICK, enabled = !busy) {
                                if (ch == null) engine.addChannel(p.freq) else engine.removeChannel(ch.id)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Led(selected, size = 8.dp)
                        Dot("%.2f".format(p.freq), size = 18.sp, color = if (selected) Lfh.Paper else Lfh.Ink)
                        Mono("HZ", size = 9.sp, color = if (selected) Lfh.PaperDim else Lfh.InkDim)
                        Spacer(Modifier.weight(1f))
                        val note = if (p.freq < 80.0) " · BASSA FREQ." else ""
                        Mono(
                            "+%.0f dB$note".format(p.promDb),
                            size = 9.sp,
                            color = if (selected) Lfh.Amber else Lfh.InkDim,
                        )
                    }
                }
            }
        }

        // ── Toni selezionati ───────────────────────────────────────────────
        for (ch in snap.channels) {
            ToneCard(
                ch = ch,
                autoOn = snap.autoOn,
                busy = busy,
                onToggle = { engine.setChannelEnabled(ch.id, !ch.enabled) },
                onRecal = {
                    if (!snap.running) doStartSelected() else engine.calibrateChannels(ch.id)
                },
                onRemove = { engine.removeChannel(ch.id) },
                onManualAmp = { ampDb -> engine.setManualW(ch.id, ampDb, ch.phaseDeg) },
                onManualPhase = { deg -> engine.setManualW(ch.id, ch.outDb ?: -30.0, deg) },
            )
        }

        // ── Aggiunta manuale ───────────────────────────────────────────────
        LightCard(gap = 8.dp) {
            Mono("AGGIUNGI TONO A MANO (HZ)", size = 9.sp, weight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FieldBox(
                    value = manualFreq,
                    onValue = { manualFreq = it },
                    placeholder = "es. 100 o 64.2",
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                )
                RowCta("AGGIUNGI") {
                    val f = manualFreq.replace(',', '.').trim().toDoubleOrNull()
                    if (f == null || f !in 15.0..1000.0) {
                        shell.toast("Scrivi una frequenza tra 15 e 1000 Hz")
                    } else {
                        engine.addChannel(f)
                        manualFreq = ""
                    }
                }
            }
            if (snap.channels.any { it.enabled }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextKey("RICALIBRA TUTTI", Modifier.height(34.dp), size = 9.sp) {
                        if (!snap.running) doStartSelected() else engine.calibrateChannels(null)
                    }
                }
            }
        }

        // ── Dispositivi & Regolazioni avanzate ─────────────────────────────
        LightCard(gap = 8.dp) {
            Row(
                Modifier.fillMaxWidth().press(Hap.TICK) { showSettings = !showSettings }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono("DISPOSITIVI E REGOLAZIONI", Modifier.weight(1f), size = 9.sp, weight = FontWeight.SemiBold)
                Mono(if (showSettings) "CHIUDI" else "APRI", size = 9.sp, color = Lfh.OrangeInk)
            }
            Expand(showSettings) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingRow(
                        "Modalità simulazione",
                        "Prova interna a 64 e 100 Hz senza microfono né cassa",
                        divider = false,
                    ) {
                        LfhSwitch(snap.simMode) {
                            engine.setParams(simMode = !snap.simMode)
                            if (snap.running) {
                                engine.stopCancellation()
                                ControfaseService.stop(ctx)
                            }
                        }
                    }
                    SettingRow(
                        "Controllo automatico",
                        "Adatta fase e ampiezza in continuo",
                    ) {
                        LfhSwitch(snap.autoOn) { engine.setAuto(!snap.autoOn) }
                    }
                    SettingRow(
                        "Uscita toni",
                        "Accende o spegne tutti i toni in controfase",
                    ) {
                        LfhSwitch(snap.outputOn) { engine.setOutput(!snap.outputOn) }
                    }

                    DevicePicker("MICROFONO", inputs, selIn) { id ->
                        ControfaseBus.selectedInputId.value = id
                        ControfaseService.instance?.applyPreferredDevices()
                    }
                    DevicePicker("ALTOPARLANTE", outputs, selOut) { id ->
                        ControfaseBus.selectedOutputId.value = id
                        ControfaseService.instance?.applyPreferredDevices()
                    }

                    SliderRow(
                        label = "REATTIVITÀ",
                        value = snap.react.toDouble(),
                        min = 0.0,
                        max = 100.0,
                        step = 5.0,
                        fmt = { v -> if (v < 25) "CALMA" else if (v < 60) "MEDIA" else "RAPIDA" },
                    ) { engine.setParams(react = it.toInt()) }

                    SliderRow(
                        label = "VOLUME MAX PER TONO",
                        value = snap.maxAmpDb,
                        min = -40.0,
                        max = 0.0,
                        step = 1.0,
                        fmt = { "%.0f dBFS".format(it) },
                    ) { engine.setParams(maxAmpDb = it) }

                    SliderRow(
                        label = "TONO DI PROVA CALIBRAZIONE",
                        value = snap.testDb,
                        min = -50.0,
                        max = -6.0,
                        step = 1.0,
                        fmt = { "%.0f dBFS".format(it) },
                    ) { engine.setParams(testDb = it) }

                    SliderRow(
                        label = "SENSIBILITÀ RILEVAMENTO PICCHI",
                        value = snap.minPromDb,
                        min = 6.0,
                        max = 20.0,
                        step = 1.0,
                        fmt = { "+%.0f dB".format(it) },
                    ) { engine.setParams(minPromDb = it) }
                }
            }
        }
    }

    if (nightMode) {
        NightDialog(
            snap = snap,
            onStop = {
                engine.stopCancellation()
                ControfaseService.stop(ctx)
                nightMode = false
            },
            onExit = { nightMode = false },
        )
    }
}

@Composable
private fun DevicePicker(
    title: String,
    devices: List<AudioDeviceChoice>,
    selectedId: Int?,
    onPick: (Int?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Mono(title, size = 9.sp, weight = FontWeight.SemiBold)
        val options = listOf(null to "Predefinito") + devices.map { it.id to it.label }
        for ((id, label) in options) {
            val active = id == selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) Lfh.Ink else Lfh.Field)
                    .press(Hap.TICK) { onPick(id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Led(active, size = 6.dp)
                Sans(label, size = 12.sp, color = if (active) Lfh.Paper else Lfh.Ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ToneCard(
    ch: ToneSnapshot,
    autoOn: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRecal: () -> Unit,
    onRemove: () -> Unit,
    onManualAmp: (Double) -> Unit,
    onManualPhase: (Double) -> Unit,
) {
    var expanded by remember(ch.id) { mutableStateOf(false) }
    val stateTxt = when {
        !ch.enabled -> "DISATTIVATO"
        ch.noisy && (ch.state == TonePhaseState.LOCKED || ch.state == TonePhaseState.ADAPTING) -> "PAUSA RUMORE ESTERNO"
        ch.state == TonePhaseState.NEW -> "DA CALIBRARE"
        ch.state == TonePhaseState.CALIBRATING -> "CALIBRAZIONE…"
        ch.state == TonePhaseState.READY -> "PRONTO"
        ch.state == TonePhaseState.ADAPTING -> "CERCA CONTROFASE"
        ch.state == TonePhaseState.LOCKED -> "STABILE"
        else -> "CALIBRAZIONE FALLITA"
    }

    LightCard(gap = 8.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot("%.2f".format(ch.freq), size = 26.sp)
                    Mono("HZ", Modifier.padding(bottom = 4.dp), size = 10.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Led(ch.enabled && ch.state == TonePhaseState.LOCKED)
                    Mono(
                        stateTxt,
                        size = 9.sp,
                        color = if (ch.state == TonePhaseState.LOCKED) Lfh.Ink else Lfh.OrangeInk,
                        weight = FontWeight.SemiBold,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val redTxt = ch.reductionDb?.let { "−%.1f dB".format(max(0.0, it)) } ?: "—"
                Dot(redTxt, size = 22.sp, color = if ((ch.reductionDb ?: 0.0) >= 6.0) Lfh.Ink else Lfh.InkDim)
                Mono("RESIDUO", size = 8.sp, color = Lfh.InkFaint)
            }
        }

        // Onda ambra (ronzio) + azzurro tratteggiato (controfase) + residuo chiaro
        ToneWavePreview(ch.freq, ch.reductionDb, ch.outDb != null && ch.enabled)

        if (ch.weakLowEnd) {
            Mono(
                "⚠ Sotto 80 Hz gli altoparlanti piccoli emettono pochissimo (percorso ${ch.pathDb?.let { "%.1f dB".format(it) } ?: "—"}).",
                size = 9.sp,
                color = Lfh.OrangeInk,
                spacing = 0.02.em,
                lineHeight = 13.sp,
            )
        } else if (ch.atLimit) {
            Mono(
                "⚠ Uscita al massimo: avvicina la cassa o alza il volume.",
                size = 9.sp,
                color = Lfh.OrangeInk,
                spacing = 0.02.em,
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mono(
                if (expanded) "NASCONDI DETTAGLI" else "DETTAGLI E CONTROLLI",
                Modifier.weight(1f).press(Hap.TICK) { expanded = !expanded }.padding(vertical = 4.dp),
                size = 9.sp,
                color = Lfh.InkDim,
                weight = FontWeight.SemiBold,
            )
            LfhSwitch(ch.enabled, onToggle)
        }

        Expand(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatCell("MICROFONO", ch.levelDb?.let { "%.1f dB".format(it) } ?: "—")
                    StatCell("USCITA", ch.outDb?.let { "%.1f dB".format(it) } ?: "spento")
                    StatCell("PERCORSO S", ch.pathDb?.let { "%.1f dB".format(it) } ?: "—")
                    StatCell("FASE", "%.0f°".format(ch.phaseDeg))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key(Modifier.weight(1f).height(36.dp), enabled = !busy, onClick = onRecal) {
                        Mono("RICALIBRA", size = 9.sp, weight = FontWeight.SemiBold)
                    }
                    DangerKey("TOGLI", Modifier.weight(1f), height = 36.dp, onClick = onRemove)
                }
                if (!autoOn) {
                    SliderRow(
                        label = "AMPIEZZA MANUALE",
                        value = ch.outDb ?: -30.0,
                        min = -60.0,
                        max = 0.0,
                        step = 0.5,
                        fmt = { "%.1f dB".format(it) },
                        onCommit = onManualAmp,
                    )
                    SliderRow(
                        label = "FASE MANUALE",
                        value = ch.phaseDeg,
                        min = 0.0,
                        max = 360.0,
                        step = 1.0,
                        fmt = { "%.0f°".format(it) },
                        onCommit = onManualPhase,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Mono(label, size = 8.sp, color = Lfh.InkFaint)
        Mono(value, size = 10.sp, color = Lfh.Ink, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun ToneWavePreview(freq: Double, reductionDb: Double?, playing: Boolean) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Lfh.Panel)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        val mid = size.height / 2f
        val amp = size.height * 0.38f
        val cycles = (freq / 33.0).coerceIn(1.5, 4.0).toFloat()
        val n = 96
        val rDb = reductionDb?.coerceAtLeast(0.0)
        val resAmp = when {
            !playing -> amp
            rDb == null -> amp * 0.5f
            else -> amp * 10.0.pow(-rDb / 20.0).toFloat()
        }

        drawLine(Lfh.PanelLine, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())

        val hum = Path()
        val anti = Path()
        val res = Path()
        for (i in 0..n) {
            val t = i.toFloat() / n
            val x = t * size.width
            val th = t * cycles * 2f * PI.toFloat()
            val yHum = mid - amp * sin(th)
            val yAnti = mid - max(0f, amp - resAmp) * sin(th + PI.toFloat())
            val yRes = mid - resAmp * sin(th + 0.6f)
            if (i == 0) {
                hum.moveTo(x, yHum); anti.moveTo(x, yAnti); res.moveTo(x, yRes)
            } else {
                hum.lineTo(x, yHum); anti.lineTo(x, yAnti); res.lineTo(x, yRes)
            }
        }
        drawPath(hum, Lfh.Amber, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        if (playing) {
            drawPath(
                anti,
                CyanPhase,
                style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
            drawPath(res, Lfh.Paper, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun ControfaseSpectrum(
    before: FloatArray?,
    now: FloatArray?,
    binHz: Double,
    peaks: List<PeakCandidate>,
    channels: List<ToneSnapshot>,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Lfh.WfBg)
            .padding(6.dp),
    ) {
        val fMin = 20.0
        val fMax = 500.0
        fun xOf(f: Double): Float {
            val p = (ln(f.coerceIn(fMin, fMax) / fMin) / ln(fMax / fMin)).toFloat()
            return p * size.width
        }

        for (f in listOf(20.0, 50.0, 100.0, 200.0, 500.0)) {
            val x = xOf(f)
            drawLine(Lfh.PanelLine, Offset(x, 0f), Offset(x, size.height), 1f)
        }

        val live = now ?: return@Canvas
        if (binHz <= 0.0) return@Canvas
        val k0 = max(1, ceil(fMin / binHz).toInt())
        val k1 = min(live.size - 1, floor(fMax / binHz).toInt())
        if (k1 <= k0) return@Canvas

        var hi = -200f
        var lo = 0f
        for (k in k0..k1) {
            hi = max(hi, live[k])
            lo = min(lo, live[k])
        }
        if (before != null) {
            for (k in k0..min(k1, before.size - 1)) hi = max(hi, before[k])
        }
        hi += 3f
        lo = max(lo, hi - 80f)
        val span = (hi - lo).coerceAtLeast(10f)
        fun yOf(v: Float) = size.height * (1f - ((v - lo) / span).coerceIn(0f, 1f))

        for (ch in channels) {
            if (!ch.enabled) continue
            val x = xOf(ch.freq)
            drawLine(Lfh.Orange.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
        }

        if (before != null) {
            val pBefore = Path()
            for (k in k0..min(k1, before.size - 1)) {
                val x = xOf(k * binHz)
                val y = yOf(before[k])
                if (k == k0) pBefore.moveTo(x, y) else pBefore.lineTo(x, y)
            }
            drawPath(
                pBefore,
                Lfh.Amber.copy(alpha = 0.7f),
                style = Stroke(1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
            )
        }

        val pNow = Path()
        for (k in k0..k1) {
            val x = xOf(k * binHz)
            val y = yOf(live[k])
            if (k == k0) pNow.moveTo(x, y) else pNow.lineTo(x, y)
        }
        drawPath(pNow, Lfh.Paper, style = Stroke(1.6.dp.toPx()))

        for (p in peaks) {
            val sel = channels.any { abs(it.freq - p.freq) < ControfaseEngine.MIN_SPACING }
            val pt = Offset(xOf(p.freq), yOf(p.levelDb.toFloat()))
            drawCircle(if (sel) Lfh.Orange else Lfh.PaperDim, radius = 3.5.dp.toPx(), center = pt)
        }
    }
}

@Composable
private fun NightDialog(
    snap: ControfaseSnapshot,
    onStop: () -> Unit,
    onExit: () -> Unit,
) {
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0B10))
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(CyanPhase))
                    Mono(snap.audioSource.ifBlank { "ATTIVO" }, size = 10.sp, color = Lfh.PaperDim)
                }
                Mono(if (snap.autoOn) "CORREZIONE CONTINUA" else "MANUALE", size = 10.sp, color = Lfh.PaperDim)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Dot(snap.headline.uppercase(), size = 28.sp, color = Lfh.Paper)
                val reds = snap.channels.filter { it.enabled }.mapNotNull { it.reductionDb }
                val minRed = reds.minOrNull()
                if (minRed != null) {
                    Dot("−%.1f dB".format(max(0.0, minRed)), size = 44.sp, color = CyanPhase)
                }
                for (ch in snap.channels.filter { it.enabled }) {
                    Mono(
                        "%.2f Hz · %s".format(ch.freq, ch.reductionDb?.let { "−%.1f dB".format(max(0.0, it)) } ?: "—"),
                        size = 11.sp,
                        color = Lfh.PaperDim,
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Key(Modifier.weight(1f).height(48.dp), bg = Lfh.Panel2, onClick = onStop) {
                    Mono("FERMA TUTTO", size = 10.sp, color = Lfh.Orange, weight = FontWeight.SemiBold)
                }
                Key(Modifier.weight(1f).height(48.dp), bg = Lfh.Panel3, onClick = onExit) {
                    Mono("ESCI", size = 10.sp, color = Lfh.Paper, weight = FontWeight.SemiBold)
                }
            }
        }
    }
}
