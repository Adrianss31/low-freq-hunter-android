package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import io.github.adrianss31.lowfreqhunter.engine.EventData
import io.github.adrianss31.lowfreqhunter.engine.Recurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Quante sessioni recenti entrano nella heatmap. */
private const val MAX_NIGHTS = 14

/**
 * Heatmap di ricorrenza: righe = sessioni recenti, colonne = ora del giorno,
 * colore = frazione del tempo monitorato sopra soglia. Rende visibile a colpo
 * d'occhio il disturbo che torna ogni notte alla stessa ora.
 */
@Composable
fun RecurrencePanel(sessions: List<SessionEntity>, dao: LfhDao) {
    var nights by remember { mutableStateOf<List<Recurrence.Night>>(emptyList()) }

    LaunchedEffect(sessions) {
        nights = withContext(Dispatchers.IO) {
            val recent = sessions
                .filter { (it.endedAt ?: it.lastT * 1000) > it.startedAt }
                .take(MAX_NIGHTS)
            if (recent.size < 2) return@withContext emptyList()
            val byId = dao.eventsForSessions(recent.map { it.id }).groupBy { it.sessionId }
            recent.map { s ->
                Recurrence.night(
                    label = fmtDateShort(s.startedAt),
                    startS = s.startedAt / 1000,
                    endS = (s.endedAt ?: s.lastT * 1000) / 1000,
                    events = byId[s.id].orEmpty().map {
                        EventData(it.band, it.startT, it.endT, it.durationS, it.peakDb, it.avgDb)
                    },
                )
            }
        }
    }

    if (nights.size < 2) return

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
                with(Render) { label(n.label, 0f, y + rowH - 4f) }
                for (bkt in 0 until Recurrence.BUCKETS) {
                    val cover = n.coverS[bkt]
                    val color = when {
                        cover < Recurrence.BUCKET_S * 0.05f -> Lfh.Bg           // non monitorato
                        n.activeS[bkt] <= 0f -> Lfh.Surface2                     // monitorato, silenzio
                        else -> {
                            val frac = (n.activeS[bkt] / cover).coerceIn(0f, 1f)
                            Render.wfColor(0.25f + 0.75f * frac)
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
        Spacer(Modifier.height(6.dp))
        CapsLabel(
            "colore = % del tempo sopra soglia · scuro = monitorato in silenzio",
            color = Lfh.TextFaint,
        )
    }
}
