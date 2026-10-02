package io.github.adrianss31.lowfreqhunter.ui

import android.graphics.Paint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.HourStats
import io.github.adrianss31.lowfreqhunter.data.LfhDb
import io.github.adrianss31.lowfreqhunter.data.SessionEntity
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import io.github.adrianss31.lowfreqhunter.ui.Render.monoText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs

val MESI = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")
val MESI_L = listOf(
    "GENNAIO", "FEBBRAIO", "MARZO", "APRILE", "MAGGIO", "GIUGNO",
    "LUGLIO", "AGOSTO", "SETTEMBRE", "OTTOBRE", "NOVEMBRE", "DICEMBRE",
)
private const val WD = "DLMMGVS"

/** Chiave mese: anno·12 + mese (0-based). */
fun monthKeyOf(ms: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
}

/** "Notte" / "Giorno" (dall'etichetta della sessione). */
fun SessionEntity.part(): String = label.substringBefore(' ').takeIf { it == "Notte" || it == "Giorno" } ?: "Sessione"

/** Etichetta breve: "Notte 30 set". */
fun SessionEntity.shortLabel(): String {
    val c = Calendar.getInstance().apply { timeInMillis = startedAt }
    return "${part()} ${c.get(Calendar.DAY_OF_MONTH)} ${MESI[c.get(Calendar.MONTH)]}"
}

/** Fine della sessione in epoch s (in corso = adesso). */
fun SessionEntity.endS(running: Boolean): Long {
    val end = endedAt
    return when {
        running -> System.currentTimeMillis() / 1000
        end != null -> end / 1000
        else -> lastT
    }
}

/** Ora d'inizio della riga "giorno" e ora d'inizio della notte. */
private fun AppSettings.dayHours(): Pair<Int, Int> =
    if (continuous.enabled && continuous.split2Enabled) Pair(continuous.split2Min / 60, continuous.splitMin / 60)
    else Pair(9, 21)

private class Row24(val day: Int, val dow: Int, val startS: Long)

@Composable
fun ArchiveScreen() {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val view = LocalView.current
    val dao = remember { LfhDb.get(ctx).dao() }
    val loaded by SettingsRepo.get(ctx).flow.collectAsState(initial = null)
    val settings = loaded ?: AppSettings()
    val sessions by dao.sessionsFlow().collectAsState(initial = null)
    val bus by MonitorBus.state.collectAsState()
    val all = sessions ?: emptyList()
    val runningId = if (bus.running) bus.sessionId else null

    val nowKey = monthKeyOf(System.currentTimeMillis())
    val firstKey = all.minOfOrNull { monthKeyOf(it.startedAt) } ?: nowKey
    val month = (shell.archMonth ?: nowKey).coerceIn(minOf(firstKey, nowKey), nowKey)
    val (h0, hn) = settings.dayHours()
    val nightCol = ((hn - h0) + 24) % 24

    // righe del mese: ogni giorno dalle h0 alle h0 del giorno dopo
    val rows = remember(month, h0) {
        val c = Calendar.getInstance().apply {
            clear()
            set(month / 12, month % 12, 1, h0, 0, 0)
        }
        val n = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        (1..n).map { d ->
            c.set(Calendar.DAY_OF_MONTH, d)
            c.set(Calendar.HOUR_OF_DAY, h0)
            Row24(d, c.get(Calendar.DAY_OF_WEEK), c.timeInMillis / 1000)
        }
    }
    val winS = rows.first().startS
    val winE = rows.last().startS + 86400
    val inMonth = all.filter { it.startedAt / 1000 < winE && it.endS(it.id == runningId) > winS }
    val latest = all.firstOrNull()

    // aggregati orari: cache su file, calcolati in sottofondo
    val grids = remember { mutableStateMapOf<String, HourStats.Grid>() }
    val evStarts = remember { mutableStateMapOf<String, List<Long>>() }
    val need = (inMonth + listOfNotNull(latest)).distinctBy { it.id }
    LaunchedEffect(need.map { it.id to it.lastT }) {
        for (s in need) {
            val known = grids[s.id]
            if (known == null || known.upToT < s.lastT) grids[s.id] = HourStats.load(ctx, dao, s)
        }
        val ids = inMonth.map { it.id }
        if (ids.isNotEmpty()) {
            val evs = withContext(Dispatchers.IO) { dao.eventsForSessions(ids) }
            evStarts.clear()
            evs.filter { it.band != Channels.GAP }.groupBy { it.sessionId }.forEach { (k, v) -> evStarts[k] = v.map { it.startT } }
        }
    }

    val filter = shell.bandFilter
    fun cell(hourS: Long): Float? {
        var best: Float? = null
        for (s in need) {
            val m = grids[s.id]?.hours?.get(hourS) ?: continue
            val v = if (filter == null) m.entries.filter { it.key != Channels.VIB }.maxOfOrNull { it.value } else m[filter]
            if (v != null && (best == null || v > best)) best = v
        }
        return best
    }
    fun covering(t: Long): SessionEntity? =
        inMonth.firstOrNull { it.startedAt / 1000 <= t && t < it.endS(it.id == runningId) }

    /** Sessione sotto il dito: quella che copre l'ora, o la più presente nella metà toccata. */
    fun sessionAt(r: Int, col: Int): SessionEntity? {
        val row = rows.getOrNull(r) ?: return null
        covering(row.startS + col * 3600L + 1800)?.let { return it }
        val night = col >= nightCol
        val a = row.startS + (if (night) nightCol else 0) * 3600L
        val b = row.startS + (if (night) 24 else nightCol) * 3600L
        return inMonth.maxByOrNull { s ->
            val ov = minOf(b, s.endS(s.id == runningId)) - maxOf(a, s.startedAt / 1000)
            ov
        }?.takeIf { s -> minOf(b, s.endS(s.id == runningId)) - maxOf(a, s.startedAt / 1000) > 0 }
    }

    var preview by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val intro = remember { Animatable(0f) }
    LaunchedEffect(month, filter) {
        intro.snapTo(0f)
        intro.animateTo(rows.size * 16f + 260f, tween((rows.size * 16 + 260), easing = LinearEasing))
    }
    fun changeMonth(d: Int) {
        val n = (month + d).coerceIn(minOf(firstKey, nowKey), nowKey)
        if (n == month) return
        shell.dir = d
        shell.archMonth = n
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = dockClearance()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── ultima sessione ────────────────────────────────────────────────
        if (latest != null) {
            val live = latest.id == runningId
            val endS = latest.endS(live)
            Key(Modifier.fillMaxWidth(), radius = 16.dp, onClick = { shell.sheet = Sheet.Session(latest.id) }) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Led(true, 6.dp)
                        Mono(
                            "  " + (if (live) "SESSIONE IN CORSO" else "ULTIMA SESSIONE · ${agoText(latest.startedAt)}"),
                            Modifier.weight(1f), size = 9.sp, color = Lfh.OrangeInk, weight = FontWeight.SemiBold, spacing = 0.14.em,
                        )
                        Sans("→", size = 15.sp, weight = FontWeight.Medium)
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Sans(latest.shortLabel(), size = 20.sp, weight = FontWeight.Bold, spacing = (-0.02).em, maxLines = 1)
                            Mono(
                                "${fmtClockShort(latest.startedAt)} → ${fmtClockShort(endS * 1000)} · ${fmtDur(endS - latest.startedAt / 1000).uppercase()}",
                                size = 10.sp, spacing = 0.sp, maxLines = 1,
                            )
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Dot("${latest.eventsCount}", size = 32.sp, lineHeight = 1.em)
                            Mono("EVENTI", size = 9.sp, spacing = 0.sp)
                        }
                    }
                    val g = grids[latest.id]
                    val h0s = HourStats.hourStart(latest.startedAt / 1000)
                    val n = ((endS - h0s) / 3600 + 1).toInt().coerceIn(1, 24)
                    Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (i in 0 until n) {
                            val m = g?.hours?.get(h0s + i * 3600L)
                            val v = m?.entries?.filter { it.key != Channels.VIB }?.maxOfOrNull { it.value }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        when {
                                            v == null -> Color.Black.copy(alpha = 0.07f)
                                            v < -12f -> Color(0xFF2B2838)
                                            else -> Render.heatCell(v)
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        } else if (sessions != null) {
            DarkPanel(Modifier, pad = 16.dp) {
                Dot("NESSUNA SESSIONE", Modifier.fillMaxWidth(), size = 30.sp, color = Lfh.Paper, align = TextAlign.Center)
                Sans(
                    "Premi REC per registrare: ogni giorno e ogni notte finiscono qui, una riga per giorno.",
                    Modifier.fillMaxWidth(), size = 12.sp, color = Lfh.PaperDim, align = TextAlign.Center, lineHeight = 17.sp,
                )
            }
        }

        // ── calendario ─────────────────────────────────────────────────────
        DarkPanel(gap = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canPrev = month > minOf(firstKey, nowKey)
                val canNext = month < nowKey
                MonthKey("‹", canPrev) { changeMonth(-1) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AnimatedContent(
                        month,
                        transitionSpec = {
                            val d = if (targetState > initialState) 1 else -1
                            (slideInHorizontally(tween(400)) { it / 6 * d } + fadeIn(tween(300))) togetherWith fadeOut(tween(100))
                        },
                        label = "month",
                    ) { m -> Dot(MESI_L[m % 12], size = 28.sp, color = Lfh.Paper, lineHeight = 1.em) }
                    Mono("${month / 12}", size = 9.sp, color = Lfh.PaperDim, spacing = 0.14.em)
                }
                MonthKey("›", canNext) { changeMonth(1) }
            }

            // statistiche del mese
            val monthSessions = all.filter { monthKeyOf(it.startedAt) == month }
            val hc = IntArray(24)
            for (r in rows) for (h in 0 until 24) {
                val v = cell(r.startS + h * 3600L)
                if (v != null && v > 0) hc[h]++
            }
            val pk = hc.indices.maxByOrNull { hc[it] } ?: 0
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Lfh.PanelLine),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                for ((k, v) in listOf(
                    "SESSIONI" to "${monthSessions.size}",
                    "EVENTI" to "${monthSessions.sumOf { it.eventsCount }}",
                    "ORA TIPICA" to if (hc[pk] > 0) "%02d:00".format((pk + h0) % 24) else "—",
                )) {
                    Column(
                        Modifier.weight(1f).background(Lfh.Panel).padding(horizontal = 9.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Mono(k, size = 8.sp, color = Lfh.PaperDim, spacing = 0.14.em, maxLines = 1)
                        Dot(v, size = 20.sp, color = Lfh.Paper, lineHeight = 1.em)
                    }
                }
            }

            // anteprima della riga sotto il dito
            val pv = preview
            val pvS = pv?.let { sessionAt(it.first, it.second) }
            val pbg by animateColorAsState(if (pv != null) Color(0xFF24231F) else Color(0xFF171614), label = "pvBg")
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(9.dp)).background(pbg).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (pv != null) {
                    val row = rows[pv.first]
                    Dot("%02d".format(row.day), Modifier.width(34.dp), size = 26.sp, color = Lfh.Paper, lineHeight = 1.em)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Sans(pvS?.shortLabel() ?: "Nessuna registrazione", size = 12.sp, color = Lfh.Paper, weight = FontWeight.SemiBold, maxLines = 1)
                        Mono(
                            pvS?.let { s ->
                                val e = s.endS(s.id == runningId)
                                "${fmtClockShort(s.startedAt)} → ${fmtClockShort(e * 1000)} · ${fmtDur(e - s.startedAt / 1000).uppercase()}"
                            } ?: "—",
                            size = 9.sp, color = Lfh.PaperDim, spacing = 0.sp, maxLines = 1,
                        )
                    }
                    if (pvS != null) {
                        Mono(
                            "${pvS.eventsCount} EVENTI", size = 10.sp, weight = FontWeight.SemiBold, spacing = 0.08.em,
                            color = if (pvS.eventsCount > 0) Lfh.Orange else Lfh.PaperDim,
                        )
                    }
                } else {
                    val inf = rememberInfiniteTransition(label = "breath")
                    val a by inf.animateFloat(0.25f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "breathA")
                    Box(
                        Modifier.size(16.dp).border(1.5.dp, Lfh.PaperDim, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(4.dp).clip(CircleShape).background(Lfh.PaperDim.copy(alpha = a))) }
                    Mono(
                        "TOCCA PER APRIRE · TIENI PREMUTO E SCORRI PER L'ANTEPRIMA · SINISTRA GIORNO, DESTRA NOTTE",
                        size = 9.sp, color = Lfh.PaperDim, spacing = 0.08.em, lineHeight = 13.sp,
                    )
                }
            }

            // legenda giorno/notte e ore
            Row(Modifier.fillMaxWidth().padding(start = 30.dp, end = 24.dp)) {
                Mono(
                    "GIORNO %02d–%02d".format(h0, hn), Modifier.weight(maxOf(1, nightCol).toFloat()),
                    size = 8.sp, color = Lfh.Amber, weight = FontWeight.SemiBold, spacing = 0.14.em, maxLines = 1,
                )
                Mono(
                    "NOTTE %02d–%02d".format(hn, h0), Modifier.weight(maxOf(1, 24 - nightCol).toFloat()).padding(start = 6.dp),
                    size = 8.sp, color = Lfh.Orange, weight = FontWeight.SemiBold, spacing = 0.14.em, maxLines = 1,
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = 30.dp, end = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                for (k in 0..4) Mono("%02d".format((h0 + k * 6) % 24), size = 9.sp, color = Lfh.PaperDim, spacing = 0.sp)
            }

            // heatmap: righe = giorni, colonne = ore
            val latestRef by rememberUpdatedState(latest)
            val rowsRef by rememberUpdatedState(rows)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height((rows.size * 14 - 2).dp)
                    .pointerInput(month, nightCol, inMonth.map { it.id }) {
                        val rowPx = 14.dp.toPx()
                        val lw = 30.dp.toPx()
                        val rw = 24.dp.toPx()
                        fun at(o: Offset): Pair<Int, Int> {
                            val r = (o.y / rowPx).toInt().coerceIn(0, rowsRef.size - 1)
                            val c = ((o.x - lw) / ((size.width - lw - rw) / 24f)).toInt().coerceIn(0, 23)
                            return Pair(r, c)
                        }
                        fun openAt(p: Pair<Int, Int>) {
                            val s = sessionAt(p.first, p.second)
                            if (s == null) {
                                val row = rowsRef[p.first]
                                shell.toast("Nessuna registrazione · ${if (p.second >= nightCol) "notte" else "giorno"} ${row.day} ${MESI[month % 12]}")
                            } else {
                                shell.sheet = Sheet.Session(s.id)
                            }
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = viewConfiguration.touchSlop
                            var kind = "long"
                            val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch == null || !ch.pressed) {
                                        kind = "tap"
                                        return@withTimeoutOrNull true
                                    }
                                    val d = ch.position - down.position
                                    if (d.getDistance() > slop) {
                                        kind = if (abs(d.x) > abs(d.y)) "swipe" else "scroll"
                                        return@withTimeoutOrNull true
                                    }
                                }
                                true
                            }
                            if (decided == null) kind = "long"
                            when (kind) {
                                "tap" -> {
                                    Haptics.tap(view)
                                    openAt(at(down.position))
                                }
                                "swipe" -> {
                                    var last = down.position
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        ch.consume()
                                        last = ch.position
                                    }
                                    val dx = last.x - down.position.x
                                    if (abs(dx) > 60.dp.toPx()) {
                                        Haptics.tap(view)
                                        changeMonth(if (dx < 0) 1 else -1)
                                    }
                                }
                                "long" -> {
                                    Haptics.heavy(view)
                                    var p = at(down.position)
                                    preview = p
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) break
                                        ch.consume()
                                        val np = at(ch.position)
                                        if (np != p) {
                                            if (np.first != p.first) Haptics.tick(view)
                                            p = np
                                            preview = np
                                        }
                                    }
                                    preview = null
                                    openAt(p)
                                }
                                else -> {}
                            }
                        }
                    },
            ) {
                val rowH = 12.dp.toPx()
                val rowP = 14.dp.toPx()
                val lw = 30.dp.toPx()
                val rw = 24.dp.toPx()
                val cw = (size.width - lw - rw) / 24f
                val lab = 9.sp.toPx()
                val t = intro.value
                val lt = latestRef
                val ltS = lt?.startedAt?.div(1000)
                val ltE = lt?.endS(lt.id == runningId)
                rows.forEachIndexed { r, row ->
                    val a = ((t - r * 16f) / 260f).coerceIn(0f, 1f)
                    val e = 1f - (1f - a) * (1f - a) * (1f - a)
                    if (e <= 0f) return@forEachIndexed
                    val y = r * rowP
                    val off = (1f - e) * 14.dp.toPx() * (if (shell.dir >= 0) 1 else -1)
                    val alpha = e
                    monoText("%02d".format(row.day), off, y + 9.dp.toPx(), Lfh.PaperDim.copy(alpha = alpha), lab)
                    monoText(WD[row.dow - 1].toString(), off + 16.dp.toPx(), y + 9.dp.toPx(), Color(0xFF55534C).copy(alpha = alpha), lab)
                    var any = false
                    for (h in 0 until 24) {
                        val hs = row.startS + h * 3600L
                        val v = cell(hs)
                        if (v != null) any = true
                        drawRect(Render.heatCell(v).copy(alpha = alpha), Offset(lw + h * cw + 0.5f + off, y), Size(cw - 1f, rowH))
                    }
                    val evN = inMonth.sumOf { s -> evStarts[s.id]?.count { it >= row.startS && it < row.startS + 86400 } ?: 0 }
                    monoText(
                        if (any) "$evN" else "—", size.width - 2f + off, y + 9.dp.toPx(),
                        (if (!any) Color(0xFF3A3934) else if (evN > 0) Lfh.Paper else Color(0xFF55534C)).copy(alpha = alpha),
                        lab, Paint.Align.RIGHT,
                    )
                    // ultima sessione: tacca a sinistra e cornice sulle sue ore
                    if (ltS != null && ltE != null && ltS < row.startS + 86400 && ltE > row.startS) {
                        val c0 = ((maxOf(ltS, row.startS) - row.startS) / 3600f).coerceIn(0f, 24f)
                        val c1 = ((minOf(ltE, row.startS + 86400) - row.startS) / 3600f).coerceIn(0f, 24f)
                        drawRect(Lfh.Orange.copy(alpha = alpha), Offset(lw - 4.dp.toPx() + off, y + 2.dp.toPx()), Size(2.dp.toPx(), rowH - 4.dp.toPx()))
                        drawRect(
                            Lfh.Orange.copy(alpha = alpha), Offset(lw + c0 * cw + off, y - 0.5f), Size(maxOf(2f, (c1 - c0) * cw), rowH + 1f),
                            style = Stroke(1.5.dp.toPx()),
                        )
                    }
                }
                drawRect(Lfh.Orange.copy(alpha = 0.35f), Offset(lw + nightCol * cw - 0.5f, 0f), Size(1f, size.height))
                preview?.let { (r, c) ->
                    val y = r * rowP
                    val night = c >= nightCol
                    val x0 = lw + (if (night) nightCol else 0) * cw
                    val x1 = lw + (if (night) 24 else nightCol) * cw
                    drawRect(Color.White.copy(alpha = 0.08f), Offset(lw, y), Size(24 * cw, rowH))
                    drawRect(Color.White.copy(alpha = 0.24f), Offset(x0, y), Size(x1 - x0, rowH))
                    drawRect(Lfh.Paper, Offset(x0 - 1.5f, y - 1.5f), Size(x1 - x0 + 3f, rowH + 3f), style = Stroke(1.5.dp.toPx()))
                    val tri = Path().apply {
                        moveTo(size.width - rw + 2.dp.toPx(), y + rowH / 2)
                        lineTo(size.width - rw + 7.dp.toPx(), y + rowH / 2 - 4.dp.toPx())
                        lineTo(size.width - rw + 7.dp.toPx(), y + rowH / 2 + 4.dp.toPx())
                        close()
                    }
                    drawPath(tri, Lfh.Paper)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono("SOTTO", size = 9.sp, color = Lfh.PaperDim, spacing = 0.sp)
                Canvas(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))) {
                    val n = 40
                    val w = size.width / n
                    for (i in 0 until n) drawRect(Render.wfColor(0.3f + 0.7f * i / (n - 1f)), Offset(i * w, 0f), Size(w + 1f, size.height))
                }
                Mono("SOPRA SOGLIA", size = 9.sp, color = Lfh.PaperDim, spacing = 0.sp)
            }
        }

        // ── filtro banda + dossier ─────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val opts = listOf<Pair<String?, String>>(null to "TUTTE") +
                settings.engine.enabledBands().take(3).map { it.id to "${it.center.toInt()} HZ" }
            Segmented(
                opts, filter, { shell.bandFilter = it }, Modifier.weight(1f), track = Lfh.Track, radius = 10.dp,
                cell = { v, l, fg ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (v != null) Box(Modifier.size(6.dp).clip(RoundedCornerShape(2.dp)).background(Lfh.bandColor(v)))
                        Mono(l, size = 10.sp, color = fg, weight = FontWeight.SemiBold, spacing = 0.06.em, maxLines = 1)
                    }
                },
            )
            Key(Modifier.height(44.dp), bg = Lfh.Ink, radius = 10.dp, onClick = { shell.sheet = Sheet.Dossier(month) }) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Lfh.Orange))
                    Mono("DOSSIER", size = 10.sp, color = Lfh.Paper, weight = FontWeight.SemiBold, spacing = 0.1.em)
                }
            }
        }
        Mono(
            "TRASCINA ← → SULLA HEATMAP PER CAMBIARE MESE",
            Modifier.fillMaxWidth(), size = 9.sp, spacing = 0.08.em, align = TextAlign.Center,
        )
    }
}

@Composable
private fun MonthKey(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Lfh.Panel2)
            .press(Hap.TICK, enabled = enabled, scale = 0.94f, shift = 0.dp, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Sans(symbol, size = 16.sp, color = if (enabled) Lfh.Paper else Color(0xFF4A4842), weight = FontWeight.Medium) }
}

private fun agoText(startMs: Long): String {
    val a = Calendar.getInstance().apply { timeInMillis = startMs }
    val b = Calendar.getInstance()
    var n = 0
    while (a.get(Calendar.YEAR) != b.get(Calendar.YEAR) || a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR)) {
        a.add(Calendar.DAY_OF_YEAR, 1)
        n++
        if (n > 999) break
    }
    return when (n) {
        0 -> "OGGI"
        1 -> "IERI"
        else -> "$n GIORNI FA"
    }
}
