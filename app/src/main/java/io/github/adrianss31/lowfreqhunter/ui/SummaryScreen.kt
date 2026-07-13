package io.github.adrianss31.lowfreqhunter.ui

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.Exporter
import io.github.adrianss31.lowfreqhunter.data.LfhDb
import io.github.adrianss31.lowfreqhunter.data.SampleEntity
import io.github.adrianss31.lowfreqhunter.data.SessionBundle
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.data.deleteSessionData
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Altezza dello spettrogramma nel dettaglio sessione (dp). */
private const val WF_H_DP = 170

@Composable
fun SummaryScreen() {
    val ctx = LocalContext.current
    val dao = remember { LfhDb.get(ctx).dao() }
    val settings by SettingsRepo.get(ctx).flow.collectAsState(initial = AppSettings())
    val sessions by dao.sessionsFlow().collectAsState(initial = emptyList())
    val bus by MonitorBus.state.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var bundle by remember { mutableStateOf<SessionBundle?>(null) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    LaunchedEffect(selected) {
        bundle = selected?.let { withContext(Dispatchers.IO) { SessionBundle.load(dao, it) } }
    }

    val b = bundle
    if (selected == null || b == null) {
        // ── elenco sessioni ──────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CapsLabel("Sessioni salvate", color = Lfh.Text)
            Spacer(Modifier.height(2.dp))
            RecurrencePanel(sessions, dao, settings.engine)
            if (sessions.isEmpty()) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("Nessuna sessione.\nAvvia un log notturno dalla scheda NOTTE.", color = Lfh.TextDim, fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
            for (s in sessions) {
                val inCorso = s.id == bus.sessionId && bus.running
                Panel(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            Haptics.tap(view)
                            if (!inCorso) selected = s.id
                        },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.label, color = Lfh.Text, fontSize = 14.sp, fontFamily = MonoFont)
                            val endMs = s.endedAt ?: (s.lastT * 1000)
                            val dur = if (inCorso) "in corso" else fmtDur((endMs - s.startedAt) / 1000)
                            CapsLabel(
                                "$dur · ${s.audioSource}" +
                                    (if (s.recovered) " · recuperata" else "") +
                                    (if (inCorso) " · REC" else ""),
                                color = if (inCorso) Lfh.Rec else Lfh.TextFaint,
                            )
                        }
                        DotValue("${s.eventsCount}", color = Lfh.Amber, size = 22)
                        CapsLabel(" EV", color = Lfh.TextFaint)
                    }
                }
            }
        }
        return
    }

    // ── dettaglio sessione ───────────────────────────────────────────────────
    var confirmDelete by remember { mutableStateOf(false) }

    // Timeline e spettrogramma renderizzati una sola volta come bitmap: lo
    // scroll non ridisegna più nulla (fix lentezza con notti intere).
    val density = LocalDensity.current.density
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val contentPx = ((screenWidthDp - 44) * density).toInt().coerceIn(320, 1400)
    var timelineBmp by remember(b.session.id) { mutableStateOf<Bitmap?>(null) }
    var waterfallBmp by remember(b.session.id) { mutableStateOf<Bitmap?>(null) }
    // canale mostrato in timeline: null = tutti (le curve spesso si sovrappongono)
    var selChannel by remember(b.session.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(b.session.id, selChannel) {
        withContext(Dispatchers.Default) {
            val tl = BitmapRender.timeline(b, contentPx, (200 * density).toInt(), density, only = selChannel)
            withContext(Dispatchers.Main) { timelineBmp = tl }
        }
    }
    LaunchedEffect(b.session.id) {
        withContext(Dispatchers.Default) {
            val wf = if (b.slices.isNotEmpty()) BitmapRender.waterfall(b, contentPx, (WF_H_DP * density).toInt(), density) else null
            withContext(Dispatchers.Main) { waterfallBmp = wf }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.session.label, color = Lfh.Text, fontSize = 15.sp, fontFamily = MonoFont)
                CapsLabel("${fmtDate(b.session.startedAt)} · sorgente ${b.session.audioSource}", color = Lfh.TextFaint)
            }
            HwButton("← elenco") { selected = null }
        }

        Panel(Modifier.fillMaxWidth()) {
            CapsLabel("Timeline · −80…−50 dBFS")
            Spacer(Modifier.height(6.dp))
            val tl = timelineBmp
            if (tl != null) {
                Image(
                    tl.asImageBitmap(),
                    contentDescription = "Timeline della sessione",
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentScale = ContentScale.FillBounds,
                )
            } else {
                Box(Modifier.fillMaxWidth().height(200.dp).background(Lfh.Bg))
            }
            // filtro canale: le curve sovrapposte si leggono male insieme
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val chipColor = { ch: String? ->
                    when (ch) {
                        null -> Lfh.Text
                        Channels.VIB -> Lfh.VibColor
                        else -> Lfh.bandColor(ch)
                    }
                }
                for (ch in listOf<String?>(null) + b.channels) {
                    val sel = selChannel == ch
                    HwButton(
                        if (ch == null) "tutte" else b.cfg.channelLabel(ch),
                        color = if (sel) Lfh.Bg else chipColor(ch),
                        borderColor = chipColor(ch),
                        bg = if (sel) chipColor(ch) else Lfh.Surface,
                    ) { selChannel = ch }
                }
            }
        }

        if (b.slices.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                CapsLabel("Spettrogramma · 20–200 Hz")
                Spacer(Modifier.height(6.dp))
                val wf = waterfallBmp
                // scrubbing: il dito seleziona una colonna (slice da 30 s) e
                // sotto compaiono orario e livelli delle bande in quel momento
                var scrubIdx by remember(b.session.id) { mutableStateOf<Int?>(null) }
                // stessa geometria del bitmap (BitmapRender.waterfall)
                val gutterPx = 30f * density
                val cols = maxOf(b.slices.size, 60)
                val colW = (contentPx - gutterPx) / cols
                fun idxOfX(x: Float, widthPx: Float): Int {
                    val bx = x / widthPx * contentPx
                    return ((bx - gutterPx) / colW).toInt().coerceIn(0, b.slices.size - 1)
                }
                if (wf != null) {
                    Box(Modifier.fillMaxWidth().height(WF_H_DP.dp)) {
                        Image(
                            wf.asImageBitmap(),
                            contentDescription = "Spettrogramma della sessione",
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                        Canvas(
                            Modifier
                                .matchParentSize()
                                .pointerInput(b.session.id) {
                                    detectTapGestures(onPress = { pos ->
                                        scrubIdx = idxOfX(pos.x, size.width.toFloat())
                                    })
                                }
                                .pointerInput(b.session.id) {
                                    detectDragGestures(
                                        onDragStart = { pos ->
                                            scrubIdx = idxOfX(pos.x, size.width.toFloat())
                                        },
                                    ) { change, _ ->
                                        change.consume()
                                        scrubIdx = idxOfX(change.position.x, size.width.toFloat())
                                    }
                                },
                        ) {
                            val si = scrubIdx ?: return@Canvas
                            val x = (gutterPx + (si + 0.5f) * colW) / contentPx * size.width
                            drawLine(
                                Color.White.copy(alpha = 0.85f),
                                Offset(x, 0f), Offset(x, size.height),
                                strokeWidth = 2f,
                            )
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(WF_H_DP.dp).background(Lfh.Bg))
                }
                Spacer(Modifier.height(6.dp))
                val si = scrubIdx
                if (si != null && si < b.slices.size) {
                    val t = b.slices[si].t
                    val idx = nearestSampleIdx(b.samples, t)
                    val parts = buildList {
                        add(fmtClock(t * 1000))
                        if (idx >= 0) {
                            val lv = b.levels[idx]
                            for (band in b.cfg.enabledBands()) {
                                lv[band.id]?.let { add("${band.center.toInt()}Hz %.1f".format(it)) }
                            }
                            if (b.cfg.vib.enabled) {
                                b.samples[idx].vibDb?.let { add("Vib %.1f".format(it)) }
                            }
                        }
                    }
                    Text(
                        parts.joinToString("  ·  "),
                        color = Lfh.Text, fontSize = 12.sp, fontFamily = MonoFont,
                    )
                } else {
                    CapsLabel("Tocca o trascina sul grafico: orario e dBFS delle bande", color = Lfh.TextFaint)
                }
            }
        }

        // statistiche
        Panel(Modifier.fillMaxWidth()) {
            val endMs = b.session.endedAt ?: (b.samples.lastOrNull()?.t?.times(1000) ?: b.session.startedAt)
            val totalS = (endMs - b.session.startedAt) / 1000
            val noisyS = b.events.sumOf { it.durationS }
            val longest = b.events.maxOfOrNull { it.durationS } ?: 0L
            val peak = b.events.mapNotNull { it.peakDb }.maxOrNull()
            val gapS = b.gaps.sumOf { it.durationS }
            StatRow("Durata", fmtDur(totalS))
            StatRow("Eventi totali", "${b.events.size}")
            StatRow("Tempo sopra soglia", fmtDur(noisyS))
            StatRow("Evento più lungo", fmtDur(longest))
            StatRow("Picco max", peak?.let { "%.1f dBFS".format(it) } ?: "—")
            fmtSpl(peak, settings.calib)?.let { StatRow("Picco max (stima SPL)", it, Lfh.Amber) }
            StatRow("Marker", "${b.markers.size}")
            if (gapS > 0) StatRow("⚠ Monitoraggio interrotto", "${fmtDur(gapS)} (${b.gaps.size} gap)", Lfh.Amber)
        }

        // eventi raggruppati per frequenza (una sezione per banda)
        Panel(Modifier.fillMaxWidth()) {
            CapsLabel("Eventi per frequenza")
            Spacer(Modifier.height(8.dp))
            if (b.events.isEmpty()) {
                Text("Nessun evento sopra soglia.", color = Lfh.TextDim, fontSize = 12.sp)
            }
            val groups = b.events.groupBy { it.band }
                .toList()
                .sortedBy { (id, _) -> b.cfg.channelSortKey(id) }
            groups.forEachIndexed { gi, (id, evs) ->
                val color = if (id == Channels.VIB) Lfh.VibColor else Lfh.bandColor(id)
                val tot = evs.sumOf { it.durationS }
                if (gi > 0) Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(10.dp).height(10.dp).background(color))
                    Spacer(Modifier.width(8.dp))
                    Text(b.cfg.channelLabel(id), color = color, fontSize = 15.sp, fontFamily = DotFont)
                    Spacer(Modifier.weight(1f))
                    CapsLabel("${evs.size} eventi · ${fmtDur(tot)} attivo", color = Lfh.TextDim)
                }
                Spacer(Modifier.height(4.dp))
                for (ev in evs.sortedBy { it.startT }) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 18.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${fmtClock(ev.startT * 1000)} → ${fmtClock(ev.endT * 1000)}",
                            color = Lfh.Text, fontSize = 12.sp, fontFamily = MonoFont,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            fmtDur(ev.durationS),
                            color = Lfh.TextDim, fontSize = 12.sp, fontFamily = MonoFont,
                            modifier = Modifier.width(62.dp),
                        )
                        Text(
                            ev.peakDb?.let { "%.1f".format(it) } ?: "—",
                            color = Lfh.TextFaint, fontSize = 12.sp, fontFamily = MonoFont,
                            modifier = Modifier.width(48.dp),
                        )
                    }
                }
            }
        }

        // interruzioni di monitoraggio (gap)
        if (b.gaps.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                CapsLabel("Interruzioni monitoraggio")
                Spacer(Modifier.height(6.dp))
                for (g in b.gaps.sortedBy { it.startT }) {
                    Text(
                        "${fmtClock(g.startT * 1000)} → ${fmtClock(g.endT * 1000)} · ${fmtDur(g.durationS)}",
                        color = Lfh.TextFaint, fontSize = 12.sp, fontFamily = MonoFont,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }

        // marker
        if (b.markers.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                CapsLabel("Marker manuali")
                Spacer(Modifier.height(6.dp))
                for (m in b.markers) {
                    Text(
                        "▲ ${fmtClock(m.t * 1000)} — \"lo sento adesso\"",
                        color = Lfh.TextDim, fontSize = 12.sp, fontFamily = MonoFont,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }

        // clip audio
        if (b.clips.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                CapsLabel("Clip audio")
                Spacer(Modifier.height(6.dp))
                for (clip in b.clips) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(
                            "${b.cfg.channelLabel(clip.band)}  ${fmtClock(clip.t * 1000)}",
                            color = Lfh.Text, fontSize = 12.sp, fontFamily = MonoFont,
                            modifier = Modifier.weight(1f),
                        )
                        HwButton("▶") {
                            runCatching {
                                MediaPlayer().apply {
                                    setDataSource(clip.path)
                                    prepare()
                                    start()
                                    setOnCompletionListener { it.release() }
                                }
                            }.onFailure {
                                Toast.makeText(ctx, "Clip non riproducibile", Toast.LENGTH_SHORT).show()
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        HwButton("condividi") {
                            val f = File(clip.path)
                            if (f.exists()) Exporter.shareFile(ctx, f, clip.mime)
                        }
                    }
                }
            }
        }

        // export
        Panel(Modifier.fillMaxWidth()) {
            CapsLabel("Export — salva in Documents/LowFreqHunter e condividi")
            Spacer(Modifier.height(8.dp))

            fun export(name: String, mime: String, produce: () -> ByteArray) {
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        val bytes = produce()
                        Exporter.saveToDocuments(ctx, name, mime, bytes)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "Salvato: Documents/LowFreqHunter/$name", Toast.LENGTH_SHORT).show()
                        }
                        Exporter.share(ctx, name, mime, bytes)
                    }.onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "Errore export: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HwButton("report png", Modifier.weight(1f), color = Lfh.Accent) {
                    export("${Exporter.baseName(b)}_report.png", "image/png") { Exporter.reportPng(b, settings.calib) }
                }
                HwButton("json", Modifier.weight(1f)) {
                    export("${Exporter.baseName(b)}.json", "application/json") { Exporter.json(b, settings.calib).toByteArray() }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HwButton("csv eventi", Modifier.weight(1f)) {
                    export("${Exporter.baseName(b)}_eventi.csv", "text/csv") { Exporter.eventsCsv(b).toByteArray() }
                }
                HwButton("csv campioni", Modifier.weight(1f)) {
                    export("${Exporter.baseName(b)}_campioni.csv", "text/csv") { Exporter.samplesCsv(b).toByteArray() }
                }
            }
        }

        HwButton("🗑 elimina sessione", Modifier.fillMaxWidth(), color = Lfh.Rec) { confirmDelete = true }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Lfh.Surface,
            title = { Text("Eliminare la sessione?", color = Lfh.Text) },
            text = { Text("Campioni, eventi, spettrogramma e clip verranno cancellati.", color = Lfh.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val id = b.session.id
                    scope.launch(Dispatchers.IO) {
                        b.clips.forEach { runCatching { File(it.path).delete() } }
                        deleteSessionData(dao, id)
                    }
                    selected = null
                }) { Text("ELIMINA", color = Lfh.Rec) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Annulla", color = Lfh.TextDim) }
            },
        )
    }
}

/** Indice del campione con t più vicino (lista ordinata per t), −1 se vuota. */
private fun nearestSampleIdx(samples: List<SampleEntity>, t: Long): Int {
    if (samples.isEmpty()) return -1
    var lo = 0
    var hi = samples.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (samples[mid].t < t) lo = mid + 1 else hi = mid
    }
    if (lo > 0 && t - samples[lo - 1].t < samples[lo].t - t) lo--
    return lo
}

@Composable
private fun StatRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = Lfh.Text) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        CapsLabel(label, Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontFamily = MonoFont)
    }
}
