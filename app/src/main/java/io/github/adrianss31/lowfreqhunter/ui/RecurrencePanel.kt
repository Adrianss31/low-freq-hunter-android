package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import io.github.adrianss31.lowfreqhunter.data.LfhDao
import io.github.adrianss31.lowfreqhunter.data.SessionEntity
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.engine.EventData
import io.github.adrianss31.lowfreqhunter.engine.Recurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Quante sessioni recenti entrano nella heatmap. */
private const val MAX_NIGHTS = 14

/**
 * Heatmap di ricorrenza: righe = sessioni recenti, colonne = ora del giorno,
 * colore = intensità del picco sopra soglia (la frazione di tempo attivo
 * modula la luminosità). Rende visibile a colpo
 * d'occhio il disturbo che torna ogni notte alla stessa ora. Filtrabile per
 * banda: sorgenti diverse (es. un 50 Hz quasi continuo e un 100 Hz a orari
 * precisi) hanno firme orarie diverse che sommate si coprirebbero a vicenda.
 */
@Composable
fun RecurrencePanel(sessions: List<SessionEntity>, dao: LfhDao, cfg: EngineCfg) {
    var nights by remember { mutableStateOf<List<Recurrence.Night>>(emptyList()) }
    var selBand by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessions) {
        nights = withContext(Dispatchers.IO) {
            val recent = sessions
                .filter { (it.endedAt ?: it.lastT * 1000) > it.startedAt }
                .take(MAX_NIGHTS)
            if (recent.size < 2) return@withContext emptyList()
            val byId = dao.eventsForSessions(recent.map { it.id }).groupBy { it.sessionId }
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            recent.map { s ->
                // soglie della cfg snapshot di quella sessione: servono per
                // l'intensità (quanto il picco le ha superate)
                val snap = runCatching { json.decodeFromString<EngineCfg>(s.cfgJson) }.getOrNull()
                val thr = buildMap {
                    snap?.bands?.forEach { put(it.id, it.thr) }
                    snap?.vib?.let { put(Channels.VIB, it.thr) }
                }
                Recurrence.night(
                    label = fmtDateShort(s.startedAt),
                    startS = s.startedAt / 1000,
                    endS = (s.endedAt ?: s.lastT * 1000) / 1000,
                    events = byId[s.id].orEmpty().map {
                        EventData(it.band, it.startT, it.endT, it.durationS, it.peakDb, it.avgDb)
                    },
                    thr = thr,
                )
            }
        }
    }

    if (nights.size < 2) return

    val channels = remember(nights) {
        nights.flatMap { it.activeByBand.keys }.distinct().sortedBy { cfg.channelSortKey(it) }
    }
    LaunchedEffect(channels) { if (selBand != null && selBand !in channels) selBand = null }

    Panel(Modifier.fillMaxWidth()) {
        CapsLabel("Ricorrenza · ultime ${nights.size} sessioni")
        Spacer(Modifier.height(8.dp))
        val rowsH = nights.size * 16
        Canvas(
            Modifier
                .fillMaxWidth()
                .height((rowsH + 20).dp),
        ) {
            val gutter = 44.dp.toPx()
            val axisH = 18.dp.toPx()
            val plotW = size.width - gutter
            val plotH = size.height - axisH
            val rowH = plotH / nights.size
            val colW = plotW / Recurrence.BUCKETS

            nights.forEachIndexed { r, n ->
                val y = r * rowH
                val active = n.active(selBand)
                val heat = n.heat(selBand)
                with(Render) { label(n.label, 0f, y + rowH - 4f) }
                for (bkt in 0 until Recurrence.BUCKETS) {
                    val cover = n.coverS[bkt]
                    // colore = intensità (dB del picco sopra soglia), non on/off;
                    // la % di tempo attivo modula la luminosità della cella
                    val color = when {
                        cover < Recurrence.BUCKET_S * 0.05f -> Lfh.Bg           // non monitorato
                        active[bkt] <= 0f -> Lfh.Surface2                        // monitorato, silenzio
                        else -> {
                            val frac = (active[bkt] / cover).coerceIn(0f, 1f)
                            Render.wfColor((0.30f + 0.70f * heat[bkt]) * (0.45f + 0.55f * frac))
                        }
                    }
                    drawRect(color, Offset(gutter + bkt * colW, y), Size(colW + 0.5f, rowH - 2f))
                }
            }
            // asse ore del giorno
            for (hh in listOf(0, 6, 12, 18, 24)) {
                val x = gutter + plotW * hh / 24f
                drawLine(
                    Lfh.Border.copy(alpha = 0.5f),
                    Offset(x.coerceAtMost(size.width - 1f), 0f),
                    Offset(x.coerceAtMost(size.width - 1f), plotH),
                )
                if (hh < 24) with(Render) { label("$hh", x + 3f, size.height - 4f) }
            }
        }
        // filtro banda: la firma oraria di ogni sorgente da sola
        if (channels.size > 1) {
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
                for (ch in listOf<String?>(null) + channels) {
                    val sel = selBand == ch
                    HwButton(
                        if (ch == null) "tutte" else cfg.channelLabel(ch),
                        color = if (sel) Lfh.Bg else chipColor(ch),
                        borderColor = chipColor(ch),
                        bg = if (sel) chipColor(ch) else Lfh.Surface,
                    ) { selBand = ch }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        CapsLabel(
            "colore = intensità del picco sopra soglia (max +15 dB) · scuro = poco o in silenzio",
            color = Lfh.TextFaint,
        )
    }
}
