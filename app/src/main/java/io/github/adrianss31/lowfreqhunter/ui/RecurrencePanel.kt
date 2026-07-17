package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import io.github.adrianss31.lowfreqhunter.engine.Recurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Quante sessioni recenti entrano nella heatmap. */
private const val MAX_NIGHTS = 14

/**
 * Heatmap di ricorrenza: righe = sessioni recenti, colonne = ora del giorno,
 * colore = livello massimo registrato rispetto alla soglia su una scala
 * −10…+10 dB (la soglia è il centro della scala, non il punto di partenza:
 * si vede anche quanto il rumore si avvicina senza superarla). Rende visibile a colpo
 * d'occhio il disturbo che torna ogni notte alla stessa ora. Filtrabile per
 * banda: sorgenti diverse (es. un 50 Hz quasi continuo e un 100 Hz a orari
 * precisi) hanno firme orarie diverse che sommate si coprirebbero a vicenda.
 */
@Composable
fun RecurrencePanel(sessions: List<SessionEntity>, dao: LfhDao, cfg: EngineCfg) {
    var nights by remember { mutableStateOf<List<Recurrence.NightLevels>>(emptyList()) }
    var selBand by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessions) {
        nights = withContext(Dispatchers.IO) {
            val recent = sessions
                .filter { (it.endedAt ?: it.lastT * 1000) > it.startedAt }
                .take(MAX_NIGHTS)
            if (recent.size < 2) return@withContext emptyList()
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            recent.map { s ->
                // soglie della cfg snapshot di quella sessione: la scala
                // colori è centrata sulla soglia (−10…+10 dB attorno)
                val snap = runCatching { json.decodeFromString<EngineCfg>(s.cfgJson) }.getOrNull()
                val thr = buildMap {
                    snap?.bands?.forEach { put(it.id, it.thr) }
                    snap?.vib?.let { put(Channels.VIB, it.thr) }
                }
                val all = dao.samples(s.id)
                val step = maxOf(1, all.size / 3000)
                val levels = buildList {
                    for (i in all.indices step step) {
                        val smp = all[i]
                        val lv = runCatching {
                            json.decodeFromString<Map<String, Double>>(smp.lvJson)
                        }.getOrNull() ?: continue
                        val over = buildMap {
                            for ((band, db) in lv) {
                                thr[band]?.let { put(band, (db - it).toFloat()) }
                            }
                            smp.vibDb?.let { v ->
                                thr[Channels.VIB]?.let { put(Channels.VIB, (v - it).toFloat()) }
                            }
                        }
                        if (over.isNotEmpty()) add(Recurrence.LevelSample(smp.t, over))
                    }
                }
                Recurrence.nightLevels(fmtDateShort(s.startedAt), levels)
            }
        }
    }

    if (nights.size < 2) return

    val channels = remember(nights) {
        nights.flatMap { it.maxOverByBand.keys }.distinct().sortedBy { cfg.channelSortKey(it) }
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
                val over = n.maxOver(selBand)
                with(Render) { label(n.label, 0f, y + rowH - 4f) }
                for (bkt in 0 until Recurrence.BUCKETS) {
                    // colore = livello max relativo alla soglia, scala
                    // −10…+10 dB (la soglia è il CENTRO, non il punto zero)
                    val v = over[bkt]
                    val color = if (v.isNaN()) Lfh.Bg else Render.wfColor(Recurrence.lvlScale(v))
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
        Spacer(Modifier.height(8.dp))
        // legenda: scala −10…+10 dB centrata sulla soglia
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CapsLabel("−10 dB", color = Lfh.TextFaint)
            Spacer(Modifier.width(6.dp))
            Canvas(Modifier.weight(1f).height(6.dp)) {
                val n = 40
                val w = size.width / n
                for (i in 0 until n) {
                    drawRect(
                        Render.wfColor(i / (n - 1f)),
                        Offset(i * w, 0f), Size(w + 1f, size.height),
                    )
                }
                // tacca al centro = soglia
                drawRect(
                    androidx.compose.ui.graphics.Color.White,
                    Offset(size.width / 2 - 1f, -2f), Size(2f, size.height + 4f),
                )
            }
            Spacer(Modifier.width(6.dp))
            CapsLabel("+10 dB", color = Lfh.TextFaint)
        }
        CapsLabel(
            "colore = livello max vs soglia (tacca bianca = soglia) · nero = non monitorato",
            color = Lfh.TextFaint,
        )
    }
}
