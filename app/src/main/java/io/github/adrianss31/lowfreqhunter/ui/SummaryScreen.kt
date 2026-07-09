package io.github.adrianss31.lowfreqhunter.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adrianss31.lowfreqhunter.data.Exporter
import io.github.adrianss31.lowfreqhunter.data.LfhDb
import io.github.adrianss31.lowfreqhunter.data.SessionBundle
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.deleteSessionData
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import io.github.adrianss31.lowfreqhunter.ui.Render.drawTimelineDark
import io.github.adrianss31.lowfreqhunter.ui.Render.drawWaterfallSlices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SummaryScreen() {
    val ctx = LocalContext.current
    val dao = remember { LfhDb.get(ctx).dao() }
    val repo = remember { SettingsRepo.get(ctx) }
    val settings by repo.flow.collectAsState(initial = AppSettings())
    val sessions by dao.sessionsFlow().collectAsState(initial = emptyList())
    val bus by MonitorBus.state.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    var bundle by remember { mutableStateOf<SessionBundle?>(null) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    
    // Toggle per vista eventi: per-evento (flat) vs per-banda (aggregato)
    var eventsViewPerBand by remember { mutableStateOf(settings.eventsViewPerBand) }
    
    // Sync with settings
    LaunchedEffect(settings.eventsViewPerBand) {
        eventsViewPerBand = settings.eventsViewPerBand
    }

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
                            val dur = s.endedAt?.let { fmtDur((it - s.startedAt) / 1000) } ?: "in corso"
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
            CapsLabel("Timeline")
            Spacer(Modifier.height(6.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) { drawTimelineDark(b) }
        }

        if (b.slices.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                CapsLabel("Spettrogramma · 20–200 Hz")
                Spacer(Modifier.height(6.dp))
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                ) {
                    drawWaterfallSlices(
                        b.slices.map { Pair(it.t, it.bins) },
                        b.cfg.enabledBands().map { Pair(it.center, Lfh.bandColor(it.id)) },
                    )
                }
            }
        }

        // statistiche
        Panel(Modifier.fillMaxWidth()) {
            val totalS = b.session.endedAt?.let { (it - b.session.startedAt) / 1000 } ?: 0L
            val noisyS = b.events.sumOf { it.durationS }
            val longest = b.events.maxOfOrNull { it.durationS } ?: 0L
            val peak = b.events.mapNotNull { it.peakDb }.maxOrNull()
            val gapS = b.gaps.sumOf { it.durationS }
            val perBand = b.events.groupBy { it.band }.entries.joinToString("  ") { "${it.key}:${it.value.size}" }
            StatRow("Durata", fmtDur(totalS))
            StatRow("Eventi", "${b.events.size}" + if (perBand.isNotEmpty()) "  ($perBand)" else "")
            StatRow("Tempo sopra soglia", fmtDur(noisyS))
            StatRow("Evento più lungo", fmtDur(longest))
            StatRow("Picco max", peak?.let { "%.1f dBFS".format(it) } ?: "—")
            StatRow("Marker", "${b.markers.size}")
            if (gapS > 0) StatRow("⚠ Monitoraggio interrotto", "${fmtDur(gapS)} (${b.gaps.size} gap)", Lfh.Amber)
        }

        // eventi
        Panel(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CapsLabel("Eventi")
                HwSwitch(eventsViewPerBand) { 
                    eventsViewPerBand = !eventsViewPerBand
                    // Salva preferenza
                    scope.launch { repo.update { it.copy(eventsViewPerBand = eventsViewPerBand) } }
                }
                CapsLabel(if (eventsViewPerBand) "per banda" else "per evento", color = Lfh.TextDim)
            }
            Spacer(Modifier.height(6.dp))
            if (b.events.isEmpty() && b.gaps.isEmpty()) {
                Text("Nessun evento sopra soglia.", color = Lfh.TextDim, fontSize = 12.sp)
            } else if (eventsViewPerBand) {
                // Vista per-banda: 1 riga per banda con attivazioni aggregate
                BandEventsView(b = b)
            } else {
                // Vista per-evento: elenco piatto cronologico (originale)
                FlatEventsView(b = b)
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
                            "${clip.band}  ${fmtClock(clip.t * 1000)}",
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
                    export("${Exporter.baseName(b)}_report.png", "image/png") { Exporter.reportPng(b) }
                }
                HwButton("json", Modifier.weight(1f)) {
                    export("${Exporter.baseName(b)}.json", "application/json") { Exporter.json(b).toByteArray() }
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
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HwButton("csv bande", Modifier.weight(1f)) {
                    export("${Exporter.baseName(b)}_bande.csv", "text/csv") { Exporter.bandsSummaryCsv(b).toByteArray() }
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

@Composable
private fun StatRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = Lfh.Text) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        CapsLabel(label, Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontFamily = MonoFont)
    }
}

/** Vista eventi aggregata per banda: 1 riga per banda con tutte le attivazioni */
@Composable
private fun BandEventsView(b: SessionBundle) {
    val enabledBands = b.cfg.enabledBands()
    val eventsByBand = b.events.groupBy { it.band }
    
    for (bandCfg in enabledBands) {
        val bandEvents = eventsByBand[bandCfg.id] ?: emptyList()
        if (bandEvents.isEmpty()) continue
        
        val totalDuration = bandEvents.sumOf { it.durationS }
        val eventCount = bandEvents.size
        val peakDb = bandEvents.mapNotNull { it.peakDb }.maxOrNull()
        val avgDb = bandEvents.mapNotNull { it.avgDb }.average().let { if (it.isNaN()) null else it }
        
        // Frequenza come etichetta principale
        val freqLabel = bandCfg.freqShort
        val color = if (bandCfg.id == Channels.VIB) Lfh.VibColor else Lfh.bandColor(bandCfg.id)
        
        Panel(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Indicatore colore + etichetta frequenza
                Box(
                    Modifier
                        .size(16.dp)
                        .background(color)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    freqLabel,
                    color = color,
                    fontSize = 13.sp,
                    fontFamily = MonoFont,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                CapsLabel("${eventCount} attivazioni", color = Lfh.TextDim)
                Spacer(Modifier.width(8.dp))
                CapsLabel(fmtDur(totalDuration), color = Lfh.TextDim)
                Spacer(Modifier.width(8.dp))
                Text(
                    peakDb?.let { "%.1f dBFS".format(it) } ?: "—",
                    color = Lfh.TextDim,
                    fontSize = 12.sp,
                    fontFamily = MonoFont
                )
            }
            
            // Elenco orari attivazioni (inline, scorrevole se tante)
            if (bandEvents.size <= 5) {
                for (ev in bandEvents.sortedBy { it.startT }) {
                    Text(
                        "  ${fmtClock(ev.startT * 1000)}–${fmtClock(ev.endT * 1000)}  ${fmtDur(ev.durationS)}  ${ev.peakDb?.let { "%.1f".format(it) } ?: "—"} dBFS",
                        color = Lfh.TextFaint,
                        fontSize = 11.sp,
                        fontFamily = MonoFont,
                        modifier = Modifier.padding(top = 2.dp, bottom = 1.dp, start = 24.dp)
                    )
                }
            } else {
                // Mostra prima e ultima + contatore
                val first = bandEvents.minByOrNull { it.startT }!!
                val last = bandEvents.maxByOrNull { it.startT }!!
                Text(
                    "  ${fmtClock(first.startT * 1000)}–${fmtClock(last.endT * 1000)}  (${bandEvents.size} attivazioni, ${fmtDur(totalDuration)} totali)  picco: ${peakDb?.let { "%.1f".format(it) } ?: "—"} dBFS",
                    color = Lfh.TextFaint,
                    fontSize = 11.sp,
                    fontFamily = MonoFont,
                    modifier = Modifier.padding(top = 2.dp, bottom = 1.dp, start = 24.dp)
                )
            }
        }
    }
    
    // Gap
    if (b.gaps.isNotEmpty()) {
        CapsLabel("Gap monitoraggio", color = Lfh.Amber)
        Spacer(Modifier.height(4.dp))
        for (gap in b.gaps.sortedBy { it.startT }) {
            Text(
                "—  ${fmtClock(gap.startT * 1000)}–${fmtClock(gap.endT * 1000)}  ${fmtDur(gap.durationS)}  gap",
                color = Lfh.TextFaint,
                fontSize = 12.sp,
                fontFamily = MonoFont,
                modifier = Modifier.padding(vertical = 3.dp)
            )
        }
    }
}

/** Vista eventi piatta originale (per-evento cronologico) */
@Composable
private fun FlatEventsView(b: SessionBundle) {
    var n = 0
    for (ev in (b.events + b.gaps).sortedBy { it.startT }) {
        if (ev.band == Channels.GAP) {
            Text(
                "—  ${fmtClock(ev.startT * 1000)}–${fmtClock(ev.endT * 1000)}  ${fmtDur(ev.durationS)}  gap",
                color = Lfh.TextFaint, fontSize = 12.sp, fontFamily = MonoFont,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        } else {
            n++
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(
                    "%2d".format(n), color = Lfh.TextFaint, fontSize = 12.sp, fontFamily = MonoFont,
                    modifier = Modifier.width(26.dp),
                )
                Text(
                    // Mostra frequenza invece di lettera
                    b.cfg.band(ev.band)?.freqShort ?: ev.band,
                    color = if (ev.band == Channels.VIB) Lfh.VibColor else Lfh.bandColor(ev.band),
                    fontSize = 12.sp, fontFamily = MonoFont,
                    modifier = Modifier.width(50.dp), // più spazio per "50 Hz"
                )
                Text(
                    "${fmtClock(ev.startT * 1000)}–${fmtClock(ev.endT * 1000)}  ${fmtDur(ev.durationS)}",
                    color = Lfh.Text, fontSize = 12.sp, fontFamily = MonoFont,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    ev.peakDb?.let { "%.1f".format(it) } ?: "—",
                    color = Lfh.TextDim, fontSize = 12.sp, fontFamily = MonoFont,
                )
            }
        }
    }
}
