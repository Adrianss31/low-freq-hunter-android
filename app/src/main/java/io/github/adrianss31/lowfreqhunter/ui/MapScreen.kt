package io.github.adrianss31.lowfreqhunter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.adrianss31.lowfreqhunter.audio.CaptureEngine
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.Exporter
import io.github.adrianss31.lowfreqhunter.data.LfhDb
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.data.SurveyEntity
import io.github.adrianss31.lowfreqhunter.data.SurveyPointEntity
import io.github.adrianss31.lowfreqhunter.dsp.Bands
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.Idw
import io.github.adrianss31.lowfreqhunter.sensor.VibrationEngine
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private const val DWELL_S = 10
private const val MAX_ZOOM = 6f

@Composable
fun MapScreen() {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val dao = remember { LfhDb.get(ctx).dao() }
    val loaded by SettingsRepo.get(ctx).flow.collectAsState(initial = null)
    val settings = loaded ?: AppSettings()
    val surveysDesc by remember { dao.surveysFlow() }.collectAsState(initial = null)
    val counts by remember { dao.surveyCountsFlow() }.collectAsState(initial = emptyList())
    val surveys = (surveysDesc ?: emptyList()).sortedBy { it.createdAt }
    val selId = shell.mapSel?.takeIf { id -> surveys.any { it.id == id } } ?: surveys.lastOrNull()?.id

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = dockClearance()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // rilievi + nuovo
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (s in surveys) {
                val on = s.id == selId
                Key(Modifier.height(44.dp), bg = if (on) Lfh.Ink else Lfh.Key, radius = 10.dp, sunk = on, onClick = { shell.mapSel = s.id }) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Sans(s.name, size = 13.sp, color = if (on) Lfh.Paper else Lfh.Ink, weight = FontWeight.SemiBold, maxLines = 1)
                        Mono(
                            "${counts.firstOrNull { it.surveyId == s.id }?.n ?: 0}",
                            size = 10.sp, color = (if (on) Lfh.Paper else Lfh.Ink).copy(alpha = 0.7f), spacing = 0.sp,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .height(44.dp)
                    .drawBehind {
                        drawRoundRect(
                            Color.Black.copy(alpha = 0.28f),
                            cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                        )
                    }
                    .press(Hap.TAP) {
                        val s = SurveyEntity(
                            id = UUID.randomUUID().toString(),
                            name = "Rilievo ${surveys.size + 1}",
                            createdAt = System.currentTimeMillis(),
                            imagePath = null,
                            cfgJson = json.encodeToString(settings.engine),
                        )
                        BgScope.launch { dao.upsertSurvey(s) }
                        shell.mapSel = s.id
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Mono("+ NUOVO", size = 10.sp, color = Lfh.Ink, weight = FontWeight.SemiBold, spacing = 0.1.em) }
        }

        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selId != null) {
                SurveyPanel(selId, settings)
            } else if (surveysDesc != null) {
                DarkPanel(pad = 16.dp, gap = 6.dp) {
                    Dot("NESSUN RILIEVO", Modifier.fillMaxWidth().padding(top = 12.dp), size = 34.sp, color = Lfh.Paper, align = TextAlign.Center)
                    Sans(
                        "Crea un rilievo e misura stanza per stanza: tocca dove sei, l'app ascolta $DWELL_S s e ne nasce una heatmap per frequenza.",
                        Modifier.fillMaxWidth().padding(bottom = 12.dp), size = 12.sp, color = Lfh.PaperDim, align = TextAlign.Center, lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SurveyPanel(surveyId: String, settings: AppSettings) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val dao = remember { LfhDb.get(ctx).dao() }
    val points by remember(surveyId) { dao.surveyPointsFlow(surveyId) }.collectAsState(initial = emptyList())
    val bus by MonitorBus.state.collectAsState()
    var survey by remember(surveyId) { mutableStateOf<SurveyEntity?>(null) }
    LaunchedEffect(surveyId) { survey = withContext(Dispatchers.IO) { dao.survey(surveyId) } }

    // ── microfono: dal servizio se registra, altrimenti locale ───────────
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    var frame by remember { mutableStateOf<MonitorBus.SpectrumFrame?>(null) }
    LaunchedEffect(bus.running, resumed) {
        if (bus.running) {
            MonitorBus.spectrum.collect { if (it != null) frame = it }
        } else if (resumed) {
            val flow = MutableStateFlow<MonitorBus.SpectrumFrame?>(null)
            val cap = CaptureEngine(ctx, settings.engine.fftSize, settings.engine.smoothLive)
            val ok = runCatching {
                cap.start(this) { spec, binHz, t ->
                    val maxBins = minOf(spec.size, (2000.0 / binHz).toInt())
                    flow.value = MonitorBus.SpectrumFrame(spec.copyOf(maxBins), binHz, t)
                }
            }.getOrDefault(false)
            if (!ok) {
                cap.stop()
                shell.toast("Microfono non disponibile")
                return@LaunchedEffect
            }
            try {
                flow.collect { if (it != null) frame = it }
            } finally {
                cap.stop()
            }
        }
    }
    var vibNow by remember { mutableStateOf<Double?>(null) }
    DisposableEffect(settings.engine.vib.enabled) {
        val v = if (settings.engine.vib.enabled) {
            VibrationEngine(ctx).takeIf { it.available }?.also { eng -> eng.start { db -> vibNow = db } }
        } else null
        onDispose { v?.stop() }
    }

    // ── piantina ─────────────────────────────────────────────────────────
    var bg by remember(surveyId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(survey?.imagePath) {
        val path = survey?.imagePath
        bg = if (path != null) withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() } else null
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val s = survey
        if (uri != null && s != null) {
            scope.launch(Dispatchers.IO) {
                val f = copyPlanImage(ctx, uri, surveyId)
                if (f != null) {
                    val upd = s.copy(imagePath = f.absolutePath)
                    dao.upsertSurvey(upd)
                    withContext(Dispatchers.Main) { survey = upd }
                }
            }
        }
    }

    // ── canale e heatmap ─────────────────────────────────────────────────
    val channels = settings.engine.enabledBands().map { it.id } +
        if (settings.engine.vib.enabled) listOf(Channels.VIB) else emptyList()
    var selCh by remember { mutableStateOf<String?>(null) }
    val ch = selCh?.takeIf { it in channels } ?: channels.firstOrNull()
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    fun valueOf(p: SurveyPointEntity, c: String): Double? =
        if (c == Channels.VIB) p.vibDb
        else runCatching { json.decodeFromString<Map<String, Double>>(p.levelsJson)[c] }.getOrNull()
    // IDW su una griglia 72×54: pochi ms anche con decine di punti
    val heat = remember(points, ch, mapSize) {
        val c = ch
        if (c == null || mapSize.width <= 0) null
        else {
            val pts = points.mapNotNull { p -> valueOf(p, c)?.let { Idw.Point(p.x, p.y, it) } }
            HeatmapRender.render(pts, mapSize.width / 2, mapSize.height / 2)
        }
    }

    // ── zoom, mirino, misura ─────────────────────────────────────────────
    var mapScale by remember(surveyId) { mutableStateOf(1f) }
    var mapPan by remember(surveyId) { mutableStateOf(Offset.Zero) }
    var pending by remember(surveyId) { mutableStateOf<Offset?>(null) }
    var sampling by remember { mutableStateOf<Triple<Float, Float, Int>?>(null) }
    val samplingJob = remember { arrayOfNulls<Job>(1) }

    fun clampPan(pan: Offset, scale: Float) = Offset(
        pan.x.coerceIn(mapSize.width * (1f - scale), 0f),
        pan.y.coerceIn(mapSize.height * (1f - scale), 0f),
    )
    fun toContent(off: Offset) = Offset((off.x - mapPan.x) / mapScale, (off.y - mapPan.y) / mapScale)
    fun zoomTo(newScale: Float) {
        val s = newScale.coerceIn(1f, MAX_ZOOM)
        if (s == mapScale) return
        val focus = pending?.let { Offset(it.x * mapSize.width * mapScale + mapPan.x, it.y * mapSize.height * mapScale + mapPan.y) }
            ?: Offset(mapSize.width / 2f, mapSize.height / 2f)
        mapPan = clampPan((mapPan - focus) * (s / mapScale) + focus, s)
        mapScale = s
    }
    fun cancelSampling() {
        samplingJob[0]?.cancel()
        samplingJob[0] = null
        sampling = null
    }
    DisposableEffect(surveyId) { onDispose { cancelSampling() } }
    fun startSampling(x: Float, y: Float) {
        if (samplingJob[0] != null) return
        Haptics.heavy(view)
        samplingJob[0] = scope.launch {
            try {
                val powers = HashMap<String, Double>()
                var vibPow = 0.0
                var vibN = 0
                var n = 0
                val bands = settings.engine.enabledBands()
                for (sec in DWELL_S downTo 1) {
                    sampling = Triple(x, y, sec)
                    Haptics.tick(view)
                    repeat(4) {
                        delay(250)
                        frame?.let { fr ->
                            for (b in bands) {
                                val db = Bands.bandDb(fr.spec, fr.binHz, b.lo, b.hi)
                                powers[b.id] = (powers[b.id] ?: 0.0) + 10.0.pow(db / 10.0)
                            }
                            n++
                        }
                        vibNow?.let {
                            vibPow += 10.0.pow(it / 10.0)
                            vibN++
                        }
                    }
                }
                if (n > 0) {
                    val levels = powers.mapValues { (_, p) -> 10.0 * log10(p / n + 1e-12) }
                    val vib = if (vibN > 0) 10.0 * log10(vibPow / vibN + 1e-12) else null
                    withContext(Dispatchers.IO) {
                        dao.insertSurveyPoint(
                            SurveyPointEntity(
                                id = UUID.randomUUID().toString(), surveyId = surveyId, x = x, y = y,
                                levelsJson = json.encodeToString(levels), vibDb = vib,
                                t = System.currentTimeMillis() / 1000, dwellS = DWELL_S,
                            ),
                        )
                    }
                    Haptics.heavy(view)
                    val c = ch
                    val v = c?.let { if (it == Channels.VIB) vib else levels[it] }
                    shell.toast("Punto misurato · ${fmtDb(v)} ${if (c == Channels.VIB) "dB(g)" else "dBFS"}")
                } else {
                    shell.toast("Nessun segnale dal microfono")
                }
            } finally {
                sampling = null
                samplingJob[0] = null
            }
        }
    }

    val s = survey ?: return
    val chLabel = ch?.let { settings.engine.channelLabel(it).uppercase() } ?: "—"

    DarkPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono("$chLabel · HEATMAP", Modifier.weight(1f), color = Lfh.PaperDim, maxLines = 1)
            Mono("${"%.1f".format(mapScale)}×", color = Lfh.Orange, weight = FontWeight.SemiBold)
        }
        val ratio = bg?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
        val inf = rememberInfiniteTransition(label = "aim")
        val breath by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "aimB")
        val ripple by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900)), label = "aimR")
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio.coerceIn(0.5f, 2.5f))
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0B0B0A))
                .onSizeChanged { mapSize = it }
                .clipToBounds()
                // niente onDoubleTap: farebbe aspettare ~300 ms ogni tocco
                .pointerInput(points, mapSize) {
                    detectTapGestures(
                        onTap = { off ->
                            val c = toContent(off)
                            if (samplingJob[0] != null) {
                                cancelSampling()
                                shell.toast("Misura annullata")
                            } else if (size.width > 0 && c.x in 0f..size.width.toFloat() && c.y in 0f..size.height.toFloat()) {
                                Haptics.tap(view)
                                pending = Offset(c.x / size.width, c.y / size.height)
                            }
                        },
                        onLongPress = { off ->
                            val c = toContent(off)
                            if (mapSize.width == 0) return@detectTapGestures
                            val nearest = points.minByOrNull { p -> hypot(p.x * mapSize.width - c.x, p.y * mapSize.height - c.y) }
                                ?: return@detectTapGestures
                            val dist = hypot(nearest.x * mapSize.width - c.x, nearest.y * mapSize.height - c.y)
                            if (dist < mapSize.width * 0.06f) {
                                Haptics.heavy(view)
                                BgScope.launch { dao.deleteSurveyPoint(nearest.id) }
                                shell.toast("Punto eliminato")
                            }
                        },
                    )
                }
                // pinch = zoom; con il mirino un dito lo rifinisce (movimento
                // relativo, il dito può stare lontano); da zoomati un dito sposta
                .pointerInput(mapSize) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            val pan = event.calculatePan()
                            when {
                                pressed > 1 -> {
                                    val zoom = event.calculateZoom()
                                    val centroid = event.calculateCentroid()
                                    val newScale = (mapScale * zoom).coerceIn(1f, MAX_ZOOM)
                                    var newPan = mapPan
                                    if (newScale != mapScale && centroid.isSpecified) {
                                        newPan = (newPan - centroid) * (newScale / mapScale) + centroid
                                    }
                                    newPan += pan
                                    mapScale = newScale
                                    mapPan = clampPan(newPan, newScale)
                                    if (zoom != 1f || pan != Offset.Zero) event.changes.forEach { it.consume() }
                                }
                                pressed == 1 && pending != null && samplingJob[0] == null && pan != Offset.Zero && mapSize.width > 0 -> {
                                    pending = pending?.let {
                                        Offset(
                                            (it.x + pan.x / mapScale / mapSize.width * 0.6f).coerceIn(0f, 1f),
                                            (it.y + pan.y / mapScale / mapSize.height * 0.6f).coerceIn(0f, 1f),
                                        )
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                                pressed == 1 && mapScale > 1f && pan != Offset.Zero -> {
                                    mapPan = clampPan(mapPan + pan, mapScale)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = mapScale
                        scaleY = mapScale
                        translationX = mapPan.x
                        translationY = mapPan.y
                    },
            ) {
                val b = bg
                if (b != null) {
                    Image(b.asImageBitmap(), "Piantina", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = 0.75f)
                } else {
                    Canvas(Modifier.fillMaxSize()) {
                        val step = 18.dp.toPx()
                        var gy = step
                        while (gy < size.height) {
                            var gx = step
                            while (gx < size.width) {
                                drawCircle(Lfh.Panel3, radius = 1.5f, center = Offset(gx, gy))
                                gx += step
                            }
                            gy += step
                        }
                    }
                }
                heat?.let { Image(it.bitmap.asImageBitmap(), "Heatmap", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                Canvas(Modifier.fillMaxSize()) {
                    val k = 1f / mapScale
                    for (p in points) {
                        val c = Offset(p.x * size.width, p.y * size.height)
                        drawCircle(Lfh.Panel, 4.5.dp.toPx() * k, c)
                        drawCircle(Lfh.Paper, 2.5.dp.toPx() * k, c)
                    }
                    val smp = sampling
                    val target = smp?.let { Offset(it.first, it.second) } ?: pending
                    if (target != null) {
                        val c = Offset(target.x * size.width, target.y * size.height)
                        val r = 16.dp.toPx() * k * (if (smp == null) 1f + 0.06f * breath else 1f)
                        val w = 1.5.dp.toPx() * k
                        drawCircle(Lfh.Orange, r, c, style = Stroke(w))
                        val a0 = 9.dp.toPx() * k
                        val a1 = 28.dp.toPx() * k
                        for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                            drawLine(Lfh.Orange, Offset(c.x + dx * a0, c.y + dy * a0), Offset(c.x + dx * a1, c.y + dy * a1), strokeWidth = w)
                        }
                        if (smp != null) {
                            val prog = (DWELL_S - smp.third + 1).toFloat() / DWELL_S
                            drawArc(
                                Lfh.Orange, -90f, 360f * prog, false,
                                Offset(c.x - 16.dp.toPx() * k, c.y - 16.dp.toPx() * k),
                                Size(32.dp.toPx() * k, 32.dp.toPx() * k),
                                style = Stroke(3.dp.toPx() * k),
                            )
                            for (j in 0 until 3) {
                                val q = (ripple + j / 3f) % 1f
                                drawCircle(Lfh.Orange.copy(alpha = (1f - q) * 0.6f), (16.dp.toPx() + q * 40.dp.toPx()) * k, c, style = Stroke(1.dp.toPx() * k))
                            }
                        }
                    }
                }
            }
        }
        val smp = sampling
        val hint = when {
            smp != null -> "MISURA IN CORSO · STAI FERMO · TOCCA LA PIANTA PER ANNULLARE"
            pending != null -> "TRASCINA PER RIFINIRE, POI MISURA"
            else -> "${points.size} PUNTI · TOCCA LA PIANTA ≈ DOVE SEI · PIZZICA PER ZOOMARE"
        }
        val hintC by animateColorAsState(if (pending != null || smp != null) Lfh.Orange else Lfh.PaperDim, label = "hint")
        Mono(hint, size = 10.sp, color = hintC, spacing = 0.06.em, lineHeight = 14.sp)
        heat?.let { hm ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono(fmtDb(hm.minDb), size = 9.sp, color = Lfh.PaperDim, spacing = 0.sp)
                Canvas(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))) {
                    val n = 40
                    val w = size.width / n
                    for (i in 0 until n) drawRect(Render.wfColor(i / (n - 1f)), Offset(i * w, 0f), Size(w + 1f, size.height))
                }
                Mono("${fmtDb(hm.maxDb)} ${if (ch == Channels.VIB) "DB(G)" else "DBFS"}", size = 9.sp, color = Lfh.PaperDim, spacing = 0.sp)
            }
        }
    }

    // frequenza della heatmap
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in channels) {
            val on = c == ch
            Key(Modifier.weight(1f).height(44.dp), bg = if (on) Lfh.Ink else Lfh.Key, sunk = on, hap = Hap.TICK, onClick = { selCh = c }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Lfh.channelColor(c)))
                    Mono(
                        if (c == Channels.VIB) "VIB" else "${settings.engine.channelShort(c)} Hz",
                        size = 11.sp, color = if (on) Lfh.Paper else Lfh.Ink, weight = FontWeight.SemiBold, spacing = 0.sp, maxLines = 1,
                    )
                }
            }
        }
    }

    // zoom − + · MISURA · annulla
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Key(Modifier.width(52.dp).height(60.dp), radius = 10.dp, hap = Hap.TICK, onClick = { zoomTo(mapScale - 0.5f) }) { Sans("−", size = 22.sp) }
        Key(Modifier.width(52.dp).height(60.dp), radius = 10.dp, hap = Hap.TICK, onClick = { zoomTo(mapScale + 0.5f) }) { Sans("+", size = 22.sp) }
        val measBg by animateColorAsState(if (pending != null || sampling != null) Lfh.Orange else Lfh.Track2, label = "meas")
        Key(
            Modifier.weight(1f).height(60.dp), bg = measBg, radius = 10.dp, depth = 3.dp, hap = Hap.HEAVY,
            onClick = {
                val p = pending
                when {
                    sampling != null -> {}
                    p == null -> shell.toast("Prima tocca la pianta dove sei")
                    else -> {
                        pending = null
                        startSampling(p.x, p.y)
                    }
                }
            },
        ) {
            val smpNow = sampling
            if (smpNow != null) {
                val prog = (DWELL_S - smpNow.third + 1).toFloat() / DWELL_S
                Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))) {
                    drawRect(Color.Black.copy(alpha = 0.14f), size = Size(size.width * prog, size.height))
                }
            }
            Mono(
                if (smpNow != null) "MISURA… ${smpNow.third}" else "MISURA",
                size = 13.sp, color = Lfh.Ink, weight = FontWeight.Bold, spacing = 0.16.em,
            )
        }
        Key(Modifier.width(52.dp).height(60.dp), radius = 10.dp, onClick = {
            if (sampling != null) cancelSampling()
            pending = null
            if (mapScale > 1f) {
                mapScale = 1f
                mapPan = Offset.Zero
            }
        }) { Sans("✕", size = 15.sp, weight = FontWeight.Medium) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextKey("EXPORT PNG", Modifier.weight(1f).height(44.dp), bg = Lfh.Card) {
            val c = ch ?: return@TextKey
            if (points.isEmpty()) {
                shell.toast("Nessun punto da esportare")
                return@TextKey
            }
            val label = settings.engine.channelLabel(c)
            val pts = points.mapNotNull { p -> valueOf(p, c)?.let { Idw.Point(p.x, p.y, it) } }
            val xy = points.map { Pair(it.x, it.y) }
            val plan = bg
            BgScope.launch {
                runCatching {
                    val bytes = Exporter.surveyPng(s, pts, xy, label, plan)
                    val name = "${s.name.replace(Regex("\\W+"), "_")}_${label.replace(Regex("\\W+"), "")}.png"
                    Exporter.saveToDocuments(ctx, name, "image/png", bytes)
                    withContext(Dispatchers.Main) { shell.toast("Salvato · Documents/LowFreqHunter/$name") }
                    Exporter.share(ctx, name, "image/png", bytes)
                }.onFailure { withContext(Dispatchers.Main) { shell.toast("Errore export: ${it.message}") } }
            }
        }
        TextKey(if (s.imagePath == null) "PIANTINA" else "CAMBIA PIANTINA", Modifier.weight(1f).height(44.dp), bg = Lfh.Card) {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    DangerKey("ELIMINA RILIEVO", Modifier.fillMaxWidth()) {
        shell.ask("Eliminare ${s.name}?", "Tutti i punti misurati e la piantina del rilievo verranno rimossi.") {
            cancelSampling()
            val img = s.imagePath
            shell.mapSel = null
            BgScope.launch {
                img?.let { runCatching { File(it).delete() } }
                dao.deleteSurveyPoints(surveyId)
                dao.deleteSurvey(surveyId)
            }
            shell.toast("Rilievo eliminato")
        }
    }
    Mono(
        "MISURA SEMPRE ALLA STESSA ALTEZZA, TELEFONO APPOGGIATO · TIENI PREMUTO UN PUNTO PER ELIMINARLO",
        Modifier.fillMaxWidth().padding(horizontal = 8.dp), size = 9.sp, spacing = 0.08.em, align = TextAlign.Center, lineHeight = 14.sp,
    )
}

/** Copia la piantina scelta in filesDir/surveys ridotta a max 1600 px. */
private fun copyPlanImage(ctx: Context, uri: Uri, surveyId: String): File? = runCatching {
    val src = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(src, 0, src.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val dir = File(ctx.filesDir, "surveys").apply { mkdirs() }
    val f = File(dir, "$surveyId.jpg")
    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    bmp.recycle()
    f
}.getOrNull()
