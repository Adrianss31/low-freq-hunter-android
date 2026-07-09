package io.github.adrianss31.lowfreqhunter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private const val DWELL_S = 10

@Composable
fun SurveyScreen() {
    val ctx = LocalContext.current
    val dao = remember { LfhDb.get(ctx).dao() }
    val settings by SettingsRepo.get(ctx).flow.collectAsState(initial = AppSettings())
    val surveys by dao.surveysFlow().collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    val sel = selected
    if (sel != null) {
        SurveyDetail(sel, onBack = { selected = null })
        return
    }

    // ── elenco rilievi ───────────────────────────────────────────────────────
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapsLabel("Mappe della casa", color = Lfh.Text)
        Panel(Modifier.fillMaxWidth()) {
            Text(
                "Cammina per casa: tocca la mappa dove ti trovi e l'app misura " +
                    "$DWELL_S s tutte le frequenze in quel punto. Con più punti nasce " +
                    "la heatmap, una per frequenza.",
                color = Lfh.TextDim, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }
        for (s in surveys) {
            Panel(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        Haptics.tap(view)
                        selected = s.id
                    },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, color = Lfh.Text, fontSize = 14.sp, fontFamily = MonoFont)
                        CapsLabel(
                            fmtDate(s.createdAt) + if (s.imagePath != null) " · piantina" else " · griglia",
                            color = Lfh.TextFaint,
                        )
                    }
                }
            }
        }
        HwButton("＋ nuovo rilievo", Modifier.fillMaxWidth(), color = Lfh.Accent) {
            val s = SurveyEntity(
                id = UUID.randomUUID().toString(),
                name = "Rilievo " + SimpleDateFormat("dd/MM HH:mm", Locale.ITALIAN).format(Date()),
                createdAt = System.currentTimeMillis(),
                imagePath = null,
                cfgJson = json.encodeToString(settings.engine),
            )
            scope.launch(Dispatchers.IO) { dao.upsertSurvey(s) }
            selected = s.id
        }
    }
}

@Composable
private fun SurveyDetail(surveyId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { LfhDb.get(ctx).dao() }
    val settings by SettingsRepo.get(ctx).flow.collectAsState(initial = AppSettings())
    val points by dao.surveyPointsFlow(surveyId).collectAsState(initial = emptyList())
    val busState by MonitorBus.state.collectAsState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var survey by remember { mutableStateOf<SurveyEntity?>(null) }
    LaunchedEffect(surveyId) {
        survey = withContext(Dispatchers.IO) { dao.survey(surveyId) }
    }

    // ── microfono: locale, o dal servizio se sta registrando ────────────────
    var capture by remember { mutableStateOf<CaptureEngine?>(null) }
    var frame by remember { mutableStateOf<MonitorBus.SpectrumFrame?>(null) }
    val localFrames = remember { MutableStateFlow<MonitorBus.SpectrumFrame?>(null) }
    LaunchedEffect(busState.running) {
        if (busState.running) {
            capture?.stop()
            capture = null
            MonitorBus.spectrum.collect { if (it != null) frame = it }
        } else {
            if (capture == null) {
                val cap = CaptureEngine(ctx, settings.engine.fftSize, settings.engine.smoothLive)
                val ok = cap.start(scope) { spec, binHz, now ->
                    val maxBins = minOf(spec.size, (2000.0 / binHz).toInt())
                    localFrames.value = MonitorBus.SpectrumFrame(spec.copyOf(maxBins), binHz, now)
                }
                if (ok) capture = cap else {
                    cap.stop()
                    Toast.makeText(ctx, "Microfono non disponibile", Toast.LENGTH_LONG).show()
                }
            }
            localFrames.collect { if (it != null) frame = it }
        }
    }
    DisposableEffect(Unit) {
        onDispose { capture?.stop(); capture = null }
    }

    // accelerometro locale per il canale vibrazioni
    var vibNow by remember { mutableStateOf<Double?>(null) }
    DisposableEffect(settings.engine.vib.enabled) {
        val v = if (settings.engine.vib.enabled) {
            VibrationEngine(ctx).takeIf { it.available }?.also { eng -> eng.start { db -> vibNow = db } }
        } else null
        onDispose { v?.stop() }
    }

    // ── sfondo piantina ──────────────────────────────────────────────────────
    var bgBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(survey?.imagePath) {
        val path = survey?.imagePath
        bgBitmap = if (path != null) {
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        } else null
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

    // ── canale selezionato e heatmap ─────────────────────────────────────────
    val channels = settings.engine.enabledBands().map { it.id } +
        if (settings.engine.vib.enabled) listOf(Channels.VIB) else emptyList()
    var selCh by remember { mutableStateOf<String?>(null) }
    if (selCh == null || selCh !in channels) selCh = channels.firstOrNull()

    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var heat by remember { mutableStateOf<HeatmapRender.Result?>(null) }
    LaunchedEffect(points, selCh, mapSize) {
        val ch = selCh
        if (ch == null || mapSize.width <= 0) {
            heat = null
            return@LaunchedEffect
        }
        heat = withContext(Dispatchers.Default) {
            val pts = points.mapNotNull { p ->
                val v = if (ch == Channels.VIB) p.vibDb
                else runCatching { json.decodeFromString<Map<String, Double>>(p.levelsJson)[ch] }.getOrNull()
                v?.let { Idw.Point(p.x, p.y, it) }
            }
            HeatmapRender.render(pts, mapSize.width / 2, mapSize.height / 2)
        }
    }

    // ── campionamento tap + conto alla rovescia ──────────────────────────────
    var sampling by remember { mutableStateOf<Triple<Float, Float, Int>?>(null) }
    val samplingJob = remember { mutableStateOf<Job?>(null) }

    fun cancelSampling() {
        samplingJob.value?.cancel()
        samplingJob.value = null
        sampling = null
    }

    fun startSampling(x: Float, y: Float) {
        if (samplingJob.value != null) return
        Haptics.heavy(view)
        samplingJob.value = scope.launch {
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
                        vibNow?.let { vibPow += 10.0.pow(it / 10.0); vibN++ }
                    }
                }
                if (n > 0) {
                    val levels = powers.mapValues { (_, p) -> 10.0 * log10(p / n + 1e-12) }
                    val vib = if (vibN > 0) 10.0 * log10(vibPow / vibN + 1e-12) else null
                    withContext(Dispatchers.IO) {
                        dao.insertSurveyPoint(
                            SurveyPointEntity(
                                id = UUID.randomUUID().toString(),
                                surveyId = surveyId,
                                x = x, y = y,
                                levelsJson = json.encodeToString(levels),
                                vibDb = vib,
                                t = System.currentTimeMillis() / 1000,
                                dwellS = DWELL_S,
                            ),
                        )
                    }
                    Haptics.heavy(view)
                } else {
                    Toast.makeText(ctx, "Nessun segnale dal microfono", Toast.LENGTH_SHORT).show()
                }
            } finally {
                sampling = null
                samplingJob.value = null
            }
        }
    }

    fun deleteNearest(off: Offset) {
        if (mapSize.width == 0) return
        val nearest = points.minByOrNull { p ->
            hypot(p.x * mapSize.width - off.x, p.y * mapSize.height - off.y)
        } ?: return
        val dist = hypot(nearest.x * mapSize.width - off.x, nearest.y * mapSize.height - off.y)
        if (dist < mapSize.width * 0.06f) {
            Haptics.heavy(view)
            scope.launch(Dispatchers.IO) { dao.deleteSurveyPoint(nearest.id) }
        }
    }

    var confirmDelete by remember { mutableStateOf(false) }
    val s = survey ?: return

    // ── UI ───────────────────────────────────────────────────────────────────
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, color = Lfh.Text, fontSize = 15.sp, fontFamily = MonoFont)
                CapsLabel("${points.size} punti · tocca dove sei per misurare", color = Lfh.TextFaint)
            }
            HwButton("← mappe") { cancelSampling(); onBack() }
        }

        // selettore frequenza
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (ch in channels) {
                val color = if (ch == Channels.VIB) Lfh.VibColor else Lfh.bandColor(ch)
                HwButton(
                    settings.engine.channelLabel(ch),
                    color = if (ch == selCh) Lfh.Bg else color,
                    borderColor = color,
                    bg = if (ch == selCh) color else Lfh.Surface,
                ) { selCh = ch }
            }
        }

        // mappa
        val ratio = bgBitmap?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio.coerceIn(0.5f, 2.5f))
                .border(1.dp, Lfh.Border)
                .background(Lfh.Bg)
                .onSizeChanged { mapSize = it }
                .pointerInput(points, mapSize) {
                    detectTapGestures(
                        onTap = { off ->
                            if (samplingJob.value != null) {
                                cancelSampling()
                            } else if (size.width > 0) {
                                startSampling(off.x / size.width, off.y / size.height)
                            }
                        },
                        onLongPress = { off -> deleteNearest(off) },
                    )
                },
        ) {
            val bg = bgBitmap
            if (bg != null) {
                Image(
                    bg.asImageBitmap(), contentDescription = "Piantina",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                    alpha = 0.75f,
                )
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val step = 18.dp.toPx()
                    var gy = step
                    while (gy < size.height) {
                        var gx = step
                        while (gx < size.width) {
                            drawCircle(Lfh.Surface2, radius = 1.5f, center = Offset(gx, gy))
                            gx += step
                        }
                        gy += step
                    }
                }
            }
            heat?.let {
                Image(
                    it.bitmap.asImageBitmap(), contentDescription = "Heatmap",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                )
            }
            // punti + campionamento in corso
            Canvas(Modifier.fillMaxSize()) {
                for (p in points) {
                    val c = Offset(p.x * size.width, p.y * size.height)
                    drawCircle(Color.Black, radius = 7.dp.toPx() / 2 + 2f, center = c)
                    drawCircle(Color.White, radius = 7.dp.toPx() / 2, center = c)
                }
                sampling?.let { (x, y, left) ->
                    val c = Offset(x * size.width, y * size.height)
                    drawCircle(Lfh.Accent.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = c)
                    drawCircle(Lfh.Accent, radius = 4.dp.toPx(), center = c)
                    val sweep = 360f * (DWELL_S - left) / DWELL_S
                    drawArc(
                        Lfh.Accent, startAngle = -90f, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(c.x - 12.dp.toPx(), c.y - 12.dp.toPx()),
                        size = Size(24.dp.toPx(), 24.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }

        // stato: countdown o livello live del canale selezionato
        val samplingNow = sampling
        if (samplingNow != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DotValue("${samplingNow.third}", color = Lfh.Accent, size = 26)
                CapsLabel("misura in corso — tocca la mappa per annullare", color = Lfh.Accent)
            }
        } else {
            val ch = selCh
            val lvl = when {
                ch == null -> null
                ch == Channels.VIB -> vibNow
                else -> frame?.let { fr ->
                    settings.engine.band(ch)?.let { b -> Bands.bandDb(fr.spec, fr.binHz, b.lo, b.hi) }
                }
            }
            CapsLabel(
                "adesso ${selCh?.let { settings.engine.channelLabel(it) } ?: "—"}: ${fmtDb(lvl)} " +
                    (if (selCh == Channels.VIB) "dB(g)" else "dBFS"),
                color = Lfh.TextDim,
            )
        }

        // legenda heatmap
        heat?.let { hm ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fmtDb(hm.minDb), color = Lfh.TextDim, fontSize = 11.sp, fontFamily = MonoFont)
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                ) {
                    val n = 48
                    val wSeg = size.width / n
                    for (i in 0 until n) {
                        drawRect(
                            Render.wfColor(i / (n - 1f)),
                            Offset(i * wSeg, 0f),
                            Size(wSeg + 1f, size.height),
                        )
                    }
                }
                Text(fmtDb(hm.maxDb), color = Lfh.TextDim, fontSize = 11.sp, fontFamily = MonoFont)
                CapsLabel(if (selCh == Channels.VIB) "dB(g)" else "dBFS", color = Lfh.TextFaint)
            }
        }

        // azioni
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HwButton(if (s.imagePath == null) "piantina…" else "cambia piantina", Modifier.weight(1f)) {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            HwButton("export png", Modifier.weight(1f), color = Lfh.Accent, enabled = points.isNotEmpty()) {
                val ch = selCh ?: return@HwButton
                val label = settings.engine.channelLabel(ch)
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        val pts = points.mapNotNull { p ->
                            val v = if (ch == Channels.VIB) p.vibDb
                            else runCatching { json.decodeFromString<Map<String, Double>>(p.levelsJson)[ch] }.getOrNull()
                            v?.let { Idw.Point(p.x, p.y, it) }
                        }
                        val bytes = Exporter.surveyPng(s, pts, points.map { Pair(it.x, it.y) }, label, bgBitmap)
                        val name = "${s.name.replace(Regex("\\W+"), "_")}_${label.replace(Regex("\\W+"), "")}.png"
                        Exporter.saveToDocuments(ctx, name, "image/png", bytes)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "Salvato: Documents/LowFreqHunter/$name", Toast.LENGTH_SHORT).show()
                        }
                        Exporter.share(ctx, name, "image/png", bytes)
                    }.onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "Errore export: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        HwButton("🗑 elimina rilievo", Modifier.fillMaxWidth(), color = Lfh.Rec) { confirmDelete = true }

        CapsLabel(
            "Misura sempre alla stessa altezza, telefono appoggiato, mano lontana. " +
                "Long-press su un punto per eliminarlo.",
            color = Lfh.TextFaint,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Lfh.Surface,
            title = { Text("Eliminare il rilievo?", color = Lfh.Text) },
            text = { Text("Punti e piantina verranno cancellati.", color = Lfh.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    cancelSampling()
                    scope.launch(Dispatchers.IO) {
                        s.imagePath?.let { runCatching { File(it).delete() } }
                        dao.deleteSurveyPoints(surveyId)
                        dao.deleteSurvey(surveyId)
                    }
                    onBack()
                }) { Text("ELIMINA", color = Lfh.Rec) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Annulla", color = Lfh.TextDim) }
            },
        )
    }
}

/** Copia la piantina scelta in filesDir/surveys ridotta a max 1600 px. */
private fun copyPlanImage(ctx: Context, uri: Uri, surveyId: String): File? {
    return runCatching {
        val src = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            src, 0, src.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val dir = File(ctx.filesDir, "surveys").apply { mkdirs() }
        val f = File(dir, "$surveyId.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        f
    }.getOrNull()
}
