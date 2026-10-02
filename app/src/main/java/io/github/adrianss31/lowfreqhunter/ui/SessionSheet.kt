package io.github.adrianss31.lowfreqhunter.ui

import android.graphics.Bitmap
import android.media.MediaPlayer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.ClipEntity
import io.github.adrianss31.lowfreqhunter.data.Exporter
import io.github.adrianss31.lowfreqhunter.data.HourStats
import io.github.adrianss31.lowfreqhunter.data.LfhDb
import io.github.adrianss31.lowfreqhunter.data.LlmExporter
import io.github.adrianss31.lowfreqhunter.data.MeasurementContext
import io.github.adrianss31.lowfreqhunter.data.SessionBundle
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.data.deleteSessionData
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EventKind
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Lavori che devono finire anche se il foglio si chiude (export, cancellazioni). */
val BgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private val ctxJson = Json { ignoreUnknownKeys = true }

@Composable
fun SessionSheet(id: String) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val dao = remember { LfhDb.get(ctx).dao() }
    val sessions by remember { dao.sessionsFlow() }.collectAsState(initial = emptyList())
    val bus by MonitorBus.state.collectAsState()
    val loaded by SettingsRepo.get(ctx).flow.collectAsState(initial = null)
    val settings = loaded ?: AppSettings()
    var curId by remember(id) { mutableStateOf(id) }
    var dir by remember { mutableIntStateOf(0) }
    var bundle by remember { mutableStateOf<SessionBundle?>(null) }
    LaunchedEffect(curId) {
        bundle = null
        bundle = withContext(Dispatchers.IO) { SessionBundle.load(dao, curId) }
    }
    val ordered = sessions.sortedBy { it.startedAt }
    val cur = ordered.firstOrNull { it.id == curId }
    fun step(d: Int) {
        val i = ordered.indexOfFirst { it.id == curId } + d
        if (i < 0 || i >= ordered.size) {
            shell.toast("Nessuna altra sessione")
            return
        }
        dir = d
        curId = ordered[i].id
    }

    // intestazione: ‹ etichetta ›
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Key(Modifier.size(40.dp), hap = Hap.TICK, onClick = { step(-1) }) { Sans("‹", size = 16.sp, weight = FontWeight.Medium) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Sans(cur?.shortLabel() ?: "—", size = 18.sp, weight = FontWeight.Bold, spacing = (-0.02).em, maxLines = 1)
            if (cur != null) {
                val live = cur.id == bus.sessionId && bus.running
                val e = cur.endS(live)
                val room = runCatching { ctxJson.decodeFromString<MeasurementContext>(cur.contextJson).room }.getOrNull().orEmpty()
                Mono(
                    "${fmtClockShort(cur.startedAt)} → ${if (live) "IN CORSO" else fmtClockShort(e * 1000)} · ${fmtDur(e - cur.startedAt / 1000).uppercase()}" +
                        (if (room.isNotBlank()) " · ${room.uppercase()}" else ""),
                    size = 9.sp, spacing = 0.08.em, maxLines = 1, align = TextAlign.Center,
                )
            }
        }
        Key(Modifier.size(40.dp), hap = Hap.TICK, onClick = { step(1) }) { Sans("›", size = 16.sp, weight = FontWeight.Medium) }
    }

    AnimatedContent(
        bundle,
        contentKey = { it?.session?.id },
        transitionSpec = {
            val d = dir
            (slideInHorizontally(tween(400)) { if (d == 0) 0 else it / 8 * d } + fadeIn(tween(300))) togetherWith fadeOut(tween(120))
        },
        label = "session",
    ) { b ->
        if (b == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                val inf = rememberInfiniteTransition(label = "load")
                val a by inf.animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "loadA")
                Mono("CARICAMENTO…", size = 10.sp, color = Lfh.InkDim.copy(alpha = a))
            }
        } else {
            SessionBody(b, settings, live = b.session.id == bus.sessionId && bus.running)
        }
    }
}

@Composable
private fun SessionBody(b: SessionBundle, settings: AppSettings, live: Boolean) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val density = LocalDensity.current.density
    val t0 = b.samples.firstOrNull()?.t ?: (b.session.startedAt / 1000)
    val t1 = maxOf(t0 + 60, b.samples.lastOrNull()?.t ?: b.session.lastT)
    var scrub by remember(b.session.id) { mutableStateOf<Long?>(null) }
    var zoom by remember(b.session.id) { mutableStateOf(false) }
    val win = if (!zoom) Pair(t0, t1) else {
        val c = scrub ?: ((t0 + t1) / 2)
        val lo = (c - 3600).coerceIn(t0, maxOf(t0, t1 - 7200))
        Pair(lo, minOf(t1, lo + 7200))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── timeline + spettrogramma ───────────────────────────────────────
        DarkPanel(radius = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono("TIMELINE · −80…−50 DBFS", Modifier.weight(1f), color = Lfh.PaperDim)
                Box(
                    Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Lfh.Panel3)
                        .press(Hap.TICK) { zoom = !zoom }
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { Mono(if (zoom) "↺ TUTTO" else "ZOOM 2H", size = 9.sp, color = Lfh.Paper, weight = FontWeight.SemiBold, spacing = 0.1.em) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(130.dp)) {
                val wPx = (maxWidth.value * density).toInt()
                val hPx = (130 * density).toInt()
                val img by produceState<Bitmap?>(null, b.session.id, win, wPx) {
                    value = withContext(Dispatchers.Default) { SessionRender.timeline(b, wPx, hPx, density, win.first, win.second) }
                }
                img?.let {
                    Image(it.asImageBitmap(), "Timeline della sessione", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
                val winRef by rememberUpdatedState(win)
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(b.session.id) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                fun tAt(x: Float): Long {
                                    val w = winRef
                                    return w.first + ((x / size.width).coerceIn(0f, 1f) * (w.second - w.first)).toLong()
                                }
                                scrub = tAt(down.position.x)
                                var horizontal: Boolean? = null
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    val d = ch.position - down.position
                                    if (horizontal == null && d.getDistance() > viewConfiguration.touchSlop) horizontal = abs(d.x) > abs(d.y)
                                    if (horizontal == false) break
                                    if (horizontal == true) {
                                        ch.consume()
                                        scrub = tAt(ch.position.x)
                                    }
                                }
                            }
                        },
                ) {
                    val s = scrub ?: return@Canvas
                    if (s < win.first || s > win.second) return@Canvas
                    val x = (s - win.first).toFloat() / maxOf(1L, win.second - win.first) * size.width
                    drawRect(Lfh.Orange, Offset(x - 0.75.dp.toPx(), 0f), Size(1.5.dp.toPx(), size.height))
                    drawCircle(Lfh.Orange, 4.dp.toPx(), Offset(x, 4.dp.toPx()))
                }
            }
            val s = scrub
            Mono(
                if (s == null) "Tocca o trascina la timeline" else scrubText(b, s),
                size = 10.sp, color = if (s == null) Lfh.PaperDim else Lfh.Paper, spacing = 0.sp, maxLines = 1,
            )
            val wfImg = remember(b.session.id, win) {
                val sl = b.slices.filter { it.t >= win.first && it.t <= win.second + 30 }.map { it.bins }
                WfBitmaps.fromSlices(sl, minCols = if (zoom) 1 else 60)
            }
            Canvas(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(4.dp))) {
                drawRect(Lfh.WfBg)
                if (wfImg != null) {
                    drawImage(
                        wfImg,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
        }

        // ── bande ──────────────────────────────────────────────────────────
        LightCard(bg = Lfh.Key, padV = 4.dp) {
            b.channels.forEachIndexed { i, ch ->
                val evs = b.events.filter { it.band == ch }
                val tot = evs.sumOf { it.durationS }
                val peak = evs.mapNotNull { it.peakDb }.maxOrNull()
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.08f)))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Lfh.channelColor(ch)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Sans(b.cfg.channelLabel(ch), size = 13.sp, weight = FontWeight.SemiBold)
                        Mono(
                            "${evs.size} eventi · ${fmtDur(tot)} attivo" +
                                (evs.count { it.kind == EventKind.PULSE }.takeIf { it > 0 }?.let { " · $it ∿" } ?: ""),
                            size = 10.sp, spacing = 0.sp,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Mono(peak?.let { "MAX ${fmtDb(it)}" } ?: "—", size = 11.sp, color = Lfh.Ink, spacing = 0.sp)
                        fmtSpl(peak, settings.calib)?.let { Mono(it, size = 9.sp, color = Lfh.InkDim, spacing = 0.sp) }
                    }
                }
            }
        }

        // ── diario ─────────────────────────────────────────────────────────
        LightCard(bg = Lfh.Key, gap = 2.dp) {
            Mono("DIARIO DELLA SESSIONE", Modifier.padding(bottom = 4.dp), size = 9.sp)
            val diary = buildList {
                for (m in b.markers) {
                    if (m.origin.startsWith("nota: ")) add(Triple(m.t, "NOTA" to Lfh.Ink, m.origin.removePrefix("nota: ")))
                    else add(Triple(m.t, "MARKER" to Lfh.Ink, if (m.origin == "pc") "lo sento adesso (dal PC)" else "lo sento adesso"))
                }
                for (g in b.gaps) add(Triple(g.startT, "INTERRUZ." to Lfh.OrangeInk, "${fmtDur(g.durationS)} senza audio"))
                for (e in b.events) {
                    if (e.durationS < 20 * 60) continue
                    add(Triple(e.startT, "EVENTO ${b.cfg.channelShort(e.band)}" to Lfh.InkDim, "${fmtDur(e.durationS)} · max ${fmtDb(e.peakDb)}"))
                }
            }.sortedBy { it.first }
            if (diary.isEmpty()) {
                DiaryRow("—", "", Lfh.Ink, "Nessuna nota o interruzione")
            } else {
                diary.forEach { (t, k, text) -> DiaryRow(fmtClockShort(t * 1000), k.first, k.second, text) }
            }
        }

        // ── clip ───────────────────────────────────────────────────────────
        LightCard(bg = Lfh.Key, gap = 2.dp) {
            Mono("CLIP AUDIO", Modifier.padding(bottom = 4.dp), size = 9.sp)
            if (b.clips.isEmpty()) {
                Sans(
                    if (b.cfg.clipsEnabled) "Nessuna clip per questa sessione." else "Clip disattivate per questa sessione (Setup → Clip audio).",
                    Modifier.padding(vertical = 4.dp), size = 12.sp, color = Lfh.InkDim,
                )
            } else {
                ClipList(b)
            }
        }

        // ── export ─────────────────────────────────────────────────────────
        fun export(name: String, mime: String, produce: () -> ByteArray) {
            BgScope.launch {
                runCatching {
                    val bytes = produce()
                    Exporter.saveToDocuments(ctx, name, mime, bytes)
                    withContext(Dispatchers.Main) { shell.toast("Salvato in Documents/LowFreqHunter · $name") }
                    Exporter.share(ctx, name, mime, bytes)
                }.onFailure {
                    withContext(Dispatchers.Main) { shell.toast("Errore export: ${it.message}") }
                }
            }
        }
        val base = Exporter.baseName(b)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextKey("REPORT PNG", Modifier.weight(1f).height(48.dp), bg = Lfh.Orange) {
                    export("${base}_report.png", "image/png") { Exporter.reportPng(b, settings.calib) }
                }
                TextKey("JSON", Modifier.weight(1f).height(48.dp)) {
                    export("$base.json", "application/json") { Exporter.json(b, settings.calib).toByteArray() }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextKey("CSV EVENTI", Modifier.weight(1f).height(48.dp)) {
                    export("${base}_eventi.csv", "text/csv") { Exporter.eventsCsv(b).toByteArray() }
                }
                TextKey("CSV CAMPIONI", Modifier.weight(1f).height(48.dp)) {
                    export("${base}_campioni.csv", "text/csv") { Exporter.samplesCsv(b).toByteArray() }
                }
            }
        }
        DangerKey("ELIMINA SESSIONE", Modifier.fillMaxWidth(), height = 46.dp) {
            if (live) {
                shell.toast("Ferma prima la registrazione")
                return@DangerKey
            }
            shell.ask(
                "Eliminare ${b.session.shortLabel()}?",
                "Campioni, eventi, clip e spettrogramma verranno rimossi. Gli export già salvati restano.",
            ) {
                val sid = b.session.id
                val clips = b.clips.map { it.path }
                shell.sheet = null
                BgScope.launch {
                    clips.forEach { runCatching { File(it).delete() } }
                    deleteSessionData(LfhDb.get(ctx).dao(), sid)
                    HourStats.delete(ctx, sid)
                }
                shell.toast("Sessione eliminata")
            }
        }
    }
}

private fun scrubText(b: SessionBundle, t: Long): String {
    val parts = mutableListOf(fmtClockShort(t * 1000))
    if (b.samples.isNotEmpty()) {
        var lo = 0
        var hi = b.samples.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (b.samples[mid].t < t) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && t - b.samples[lo - 1].t < b.samples[lo].t - t) lo--
        if (abs(b.samples[lo].t - t) < 120) {
            val lv = b.levels[lo]
            for (band in b.cfg.enabledBands()) lv[band.id]?.let { parts.add("${band.center.toInt()} Hz ${fmtDb(it)}") }
            if (b.cfg.vib.enabled) b.samples[lo].vibDb?.let { parts.add("Vib ${fmtDb(it)}") }
        } else {
            parts.add("nessun dato")
        }
    }
    return parts.joinToString("  ·  ")
}

@Composable
private fun DiaryRow(t: String, k: String, kc: Color, text: String) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.07f)))
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Mono(t, Modifier.width(38.dp).alignByBaseline(), size = 11.sp, spacing = 0.sp)
            Mono(k, Modifier.width(74.dp).alignByBaseline(), size = 9.sp, color = kc, weight = FontWeight.SemiBold, spacing = 0.1.em, maxLines = 1)
            Sans(text, Modifier.weight(1f).alignByBaseline(), size = 12.sp)
        }
    }
}

/** Forma d'onda grezza (30 barre) di una clip WAV 16 bit mono. */
private fun wavePeaks(path: String, n: Int = 30): FloatArray? = runCatching {
    RandomAccessFile(path, "r").use { f ->
        val len = f.length() - 44
        if (len <= 0) return@use null
        val frames = len / 2
        val out = FloatArray(n)
        val buf = ByteArray(4096)
        for (i in 0 until n) {
            val a = 44 + (frames * i / n) * 2
            val bEnd = 44 + (frames * (i + 1) / n) * 2
            f.seek(a)
            var pos = a
            var m = 0
            while (pos < bEnd) {
                val r = f.read(buf, 0, minOf(buf.size.toLong(), bEnd - pos).toInt())
                if (r <= 0) break
                val bb = ByteBuffer.wrap(buf, 0, r).order(ByteOrder.LITTLE_ENDIAN)
                while (bb.remaining() >= 2) m = maxOf(m, abs(bb.short.toInt()))
                pos += r
            }
            out[i] = m / 32768f
        }
        val mx = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
        FloatArray(n) { (0.18f + 0.82f * out[it] / mx) }
    }
}.getOrNull()

@Composable
private fun ClipList(b: SessionBundle) {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    fun stop() {
        player[0]?.let { runCatching { it.stop() }; it.release() }
        player[0] = null
        playing = null
        progress = 0f
    }
    DisposableEffect(Unit) { onDispose { stop() } }
    LaunchedEffect(playing) {
        while (playing != null) {
            val p = player[0] ?: break
            progress = runCatching { p.currentPosition.toFloat() / maxOf(1, p.duration) }.getOrDefault(0f)
            delay(80)
        }
    }
    b.clips.forEachIndexed { i, clip ->
        val on = playing == clip.id
        if (i >= 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.07f)))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val bg by animateColorAsState(if (on) Lfh.Orange else Lfh.Key, label = "clipBg")
            Key(Modifier.size(44.dp), bg = bg, radius = 22.dp, onClick = {
                if (on) {
                    stop()
                } else {
                    stop()
                    runCatching {
                        val mp = MediaPlayer()
                        mp.setDataSource(clip.path)
                        mp.prepare()
                        mp.setOnCompletionListener { stop() }
                        mp.start()
                        player[0] = mp
                        playing = clip.id
                    }.onFailure { shell.toast("Clip non riproducibile") }
                }
            }) { Sans(if (on) "■" else "▶", size = 13.sp, weight = FontWeight.SemiBold) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Mono("${fmtClockShort(clip.t * 1000)} · ${b.cfg.channelLabel(clip.band)} · ${clipSeconds(clip, b.session.sampleRate)} s", size = 11.sp, color = Lfh.Ink, spacing = 0.sp)
                val peaks by produceState<FloatArray?>(null, clip.path) { value = withContext(Dispatchers.IO) { wavePeaks(clip.path) } }
                Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    val pk = peaks ?: FloatArray(30) { 0.3f }
                    pk.forEachIndexed { j, h ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height((h * 14).dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(if (on && j / 30f <= progress) Lfh.Orange else Color.Black.copy(alpha = 0.22f)),
                        )
                    }
                }
            }
            TextKey("CONDIVIDI", Modifier.height(36.dp), bg = Lfh.Card, radius = 7.dp, size = 9.sp) {
                val f = File(clip.path)
                if (f.exists()) Exporter.shareFile(ctx, f, clip.mime) else shell.toast("File della clip non trovato")
            }
        }
    }
}

private fun clipSeconds(c: ClipEntity, sampleRate: Int): Int {
    val len = File(c.path).length() - 44
    return if (len > 0 && sampleRate > 0) (len / 2 / sampleRate).toInt() else 0
}

// ── Dossier per LLM ──────────────────────────────────────────────────────

@Composable
fun DossierSheet(monthKey: Int) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val dao = remember { LfhDb.get(ctx).dao() }
    val sessions by remember { dao.sessionsFlow() }.collectAsState(initial = emptyList())
    val bus by MonitorBus.state.collectAsState()
    val active = if (bus.running) bus.sessionId else null
    var preset by remember { mutableStateOf("7") }
    var part by remember { mutableStateOf("all") }
    var raw by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var lastFile by remember { mutableStateOf<File?>(null) }

    val eligible = sessions.filter { it.endedAt != null && it.id != active }.sortedBy { it.startedAt }
    val pool = when (part) {
        "N" -> eligible.filter { it.part() == "Notte" }
        "G" -> eligible.filter { it.part() == "Giorno" }
        else -> eligible
    }
    val lastT = eligible.lastOrNull()?.startedAt ?: 0L
    val sel = when (preset) {
        "month" -> pool.filter { monthKeyOf(it.startedAt) == monthKey }
        else -> pool.filter { it.startedAt > lastT - preset.toLong() * 86_400_000L }
    }.takeLast(31)
    val selIds = sel.map { it.id }.toSet()
    // le opzioni cambiate invalidano il dossier già pronto
    LaunchedEffect(selIds, raw, audio) {
        lastFile = null
        status = ""
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Sans("Dossier per LLM", Modifier.weight(1f), size = 19.sp, weight = FontWeight.Bold, spacing = (-0.02).em)
            Dot("%02d".format(sel.size), size = 24.sp, color = Lfh.Orange)
        }
        Sans(
            (if (sel.isNotEmpty()) "${sel.first().shortLabel()} → ${sel.last().shortLabel()} · " else "Nessuna sessione conclusa nel periodo · ") +
                "Il dossier resta locale: caricalo nella chat che preferisci.",
            size = 12.sp, color = Lfh.InkDim, lineHeight = 17.sp,
        )
        Segmented(
            listOf("7" to "GIORNI", "14" to "GIORNI", "month" to "MESE"),
            preset, { preset = it }, Modifier.fillMaxWidth(), height = 48.dp, radius = 10.dp,
            cell = { v, l, fg ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Dot(if (v == "month") MESI[monthKey % 12].uppercase() else v, size = 17.sp, color = fg, lineHeight = 1.em)
                    Mono(l, size = 8.sp, color = fg, weight = FontWeight.SemiBold, spacing = 0.1.em)
                }
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth().height(26.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                val bars = eligible.takeLast(31)
                bars.forEach { s ->
                    val c by animateColorAsState(if (s.id in selIds) Lfh.Orange else Color.Black.copy(alpha = 0.16f), label = "bar")
                    Box(
                        Modifier
                            .weight(1f)
                            .height((4 + minOf(22, s.eventsCount * 3)).dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(c),
                    )
                }
                repeat(31 - bars.size) { Box(Modifier.weight(1f)) }
            }
            Mono("ULTIME 31 SESSIONI · ARANCIO = INCLUSE · ALTEZZA = EVENTI", size = 9.sp, spacing = 0.08.em)
        }
        Segmented(
            listOf("all" to "TUTTE", "N" to "SOLO NOTTI", "G" to "SOLO GIORNI"),
            part, { part = it }, Modifier.fillMaxWidth(), radius = 10.dp,
        )
        Column {
            SettingRow("Includi campioni al secondo", "File più grande, analisi più fine") {
                LfhSwitch(raw) { if (!busy) raw = !raw }
            }
            SettingRow("Includi le prime 3 clip per sessione", "Possono contenere voci") {
                LfhSwitch(audio) { if (!busy) audio = !audio }
            }
        }
        val inf = rememberInfiniteTransition(label = "dos")
        val sweep by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dosP")
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .press(Hap.HEAVY) {
                    if (busy) return@press
                    val f = lastFile
                    if (f != null && f.exists()) {
                        Exporter.shareFile(ctx, f, "application/zip")
                        return@press
                    }
                    if (sel.isEmpty()) {
                        shell.toast("Nessuna sessione selezionata")
                        return@press
                    }
                    busy = true
                    status = ""
                    val ids = sel.map { it.id }
                    BgScope.launch {
                        try {
                            val file = LlmExporter.export(ctx, ids, raw, audio)
                            withContext(Dispatchers.Main) {
                                lastFile = file
                                status = "Dossier pronto: ${ids.size} sessioni. Salvalo dalla condivisione."
                            }
                            Exporter.shareFile(ctx, file, "application/zip")
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { status = "Esportazione non completata: ${e.message}" }
                        } finally {
                            withContext(Dispatchers.Main) { busy = false }
                        }
                    }
                }
                .keyFace(12.dp, Lfh.Orange, depth = 3.dp, edge = 0.18f, inset = 0.16f)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.Black.copy(alpha = 0.16f), size = Size(size.width * sweep, size.height))
                }
            }
            Mono(
                when {
                    busy -> "PREPARAZIONE…"
                    lastFile != null -> "CONDIVIDI DI NUOVO"
                    else -> "CREA E CONDIVIDI ZIP"
                },
                size = 11.sp, color = Lfh.Ink, weight = FontWeight.SemiBold,
            )
        }
        if (status.isNotEmpty()) Sans(status, size = 12.sp, color = Lfh.InkDim, lineHeight = 17.sp)
    }
}
