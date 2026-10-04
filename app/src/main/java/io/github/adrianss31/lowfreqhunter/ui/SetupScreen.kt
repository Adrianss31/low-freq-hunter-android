package io.github.adrianss31.lowfreqhunter.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.server.LanServer
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import io.github.adrianss31.lowfreqhunter.ui.Render.monoText
import io.github.adrianss31.lowfreqhunter.update.AppUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Modalità di programmazione: "manual" | "night" (avvio/stop orari) | "cont" (continua con spezzamenti). */
fun AppSettings.progMode(): String = when {
    continuous.enabled -> "cont"
    schedule.enabled -> "night"
    else -> "manual"
}

/** Token breve per l'URL della dashboard LAN (non è crittografia, è un lucchetto). */
private fun randomToken(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..6).map { chars.random() }.joinToString("")
}

@Composable
fun SetupScreen() {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val repo = remember { SettingsRepo.get(ctx) }
    val loaded by repo.flow.collectAsState(initial = null)
    val s = loaded ?: return
    val bus by MonitorBus.state.collectAsState()
    val upd by AppUpdater.state.collectAsState()

    fun set(t: (AppSettings) -> AppSettings) {
        scope.launch { runCatching { repo.update(t) }.onFailure { shell.toast(it.message ?: "Valore non valido") } }
    }
    fun setE(t: (EngineCfg) -> EngineCfg) = set { it.copy(engine = t(it.engine)) }

    // permessi che l'utente può cambiare fuori dall'app: ricontrollati spesso
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            refresh++
        }
    }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val canExact = remember(refresh) {
        Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }
    val exempt = remember(refresh) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val installed = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val install = rememberInstaller()

    val e = s.engine
    val mode = s.progMode()
    val modeSum = when (mode) {
        "night" -> "NOTTURNO · ${fmtHm(s.schedule.startMin)}→${fmtHm(s.schedule.endMin)}"
        "cont" -> "CONTINUO · ${fmtHm(s.continuous.splitMin)}" + if (s.continuous.split2Enabled) " / ${fmtHm(s.continuous.split2Min)}" else ""
        else -> "MANUALE · SOLO REC"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, bottom = dockClearance()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (bus.running) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Lfh.Amber).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Mono(
                    "LOG IN CORSO · LE MODIFICHE AL MOTORE VALGONO DALLA PROSSIMA SESSIONE",
                    size = 9.sp, color = Lfh.Ink, weight = FontWeight.SemiBold, spacing = 0.08.em, lineHeight = 13.sp,
                )
            }
        }

        // ── 01 programma ───────────────────────────────────────────────────
        Module("prog", 1, "PROGRAMMA", modeSum, warn = mode == "night" && !canExact) {
            Mono("MODALITÀ", Modifier.padding(top = 8.dp, bottom = 6.dp), size = 9.sp, weight = FontWeight.SemiBold)
            Segmented(
                listOf("manual" to "MANUALE", "night" to "NOTTURNO", "cont" to "CONTINUO"),
                mode,
                { m ->
                    set {
                        it.copy(
                            schedule = it.schedule.copy(enabled = m == "night"),
                            continuous = it.continuous.copy(enabled = m == "cont"),
                        )
                    }
                },
                Modifier.fillMaxWidth(),
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp).alpha(if (mode == "manual") 0.45f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val a = if (mode == "cont") s.continuous.splitMin else s.schedule.startMin
                val b = when (mode) {
                    "cont" -> if (s.continuous.split2Enabled) s.continuous.split2Min else null
                    else -> s.schedule.endMin
                }
                ScheduleDial(mode, a, b) { which, m ->
                    set {
                        when {
                            mode == "cont" && which == 0 -> it.copy(continuous = it.continuous.copy(splitMin = m))
                            mode == "cont" -> it.copy(continuous = it.continuous.copy(split2Min = m))
                            which == 0 -> it.copy(schedule = it.schedule.copy(startMin = m))
                            else -> it.copy(schedule = it.schedule.copy(endMin = m))
                        }
                    }
                }
                if (mode != "manual") {
                    Mono("TRASCINA LE MANIGLIE · PASSO 15 MIN", size = 9.sp, spacing = 0.1.em, align = TextAlign.Center)
                }
            }
            if (mode == "cont") {
                SettingRow("Secondo spezzamento", "Divide in sessioni Notte e Giorno") {
                    LfhSwitch(s.continuous.split2Enabled) {
                        set { it.copy(continuous = it.continuous.copy(split2Enabled = !it.continuous.split2Enabled)) }
                    }
                }
                Mono(
                    "PREMI REC UNA VOLTA: LA SESSIONE SI SPEZZA DA SOLA · TIENI IL DISPOSITIVO IN CARICA",
                    Modifier.padding(bottom = 4.dp), size = 9.sp, spacing = 0.06.em, lineHeight = 13.sp,
                )
            }
            if (mode == "night" && !canExact) {
                SettingRow("Sveglie esatte", "Serve il permesso per partire all'orario", subColor = Lfh.OrangeInk) {
                    RowCta("CONSENTI", accent = true) {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }
        }

        // ── 02 eventi ──────────────────────────────────────────────────────
        Module("ev", 2, "EVENTI", "APRE ${e.minOnS}S · CHIUDE ${e.minOffS}S · ISTERESI ${"%.1f".format(e.hystDb)} DB") {
            SliderRow("APERTURA · SOPRA SOGLIA PER", e.minOnS.toDouble(), 1.0, maxOf(120.0, e.minOnS.toDouble()), 1.0, { "${it.toInt()} s" }) { v ->
                setE { it.copy(minOnS = v.toInt()) }
            }
            SliderRow("CHIUSURA · SOTTO SOGLIA PER", e.minOffS.toDouble(), 1.0, maxOf(120.0, e.minOffS.toDouble()), 1.0, { "${it.toInt()} s" }) { v ->
                setE { it.copy(minOffS = v.toInt()) }
            }
            SliderRow("ISTERESI", e.hystDb, 0.0, maxOf(12.0, e.hystDb), 0.5, { "%.1f dB".format(it) }) { v ->
                setE { it.copy(hystDb = v) }
            }
            SettingRow("Eventi pulsanti", "Raffiche brevi e irregolari rilevate a parte (≥3 in 90 s)") {
                LfhSwitch(e.pulseEnabled) { setE { it.copy(pulseEnabled = !it.pulseEnabled) } }
            }
        }

        // ── 03 sensori e analisi ───────────────────────────────────────────
        Module(
            "sens", 3, "SENSORI E ANALISI",
            "FFT ${e.fftSize / 1024}K · ${"%.1f".format(48000.0 / e.fftSize)} HZ/BIN" +
                (if (e.vib.enabled) " · VIB ON" else "") + (if (s.calib.enabled) " · SPL" else ""),
        ) {
            Mono("RISOLUZIONE FFT", Modifier.padding(top = 8.dp, bottom = 6.dp), size = 9.sp, weight = FontWeight.SemiBold)
            Segmented(
                listOf(16384 to "16K · 2.9 HZ", 32768 to "32K · 1.5 HZ"),
                e.fftSize,
                { v -> setE { it.copy(fftSize = v) } },
                Modifier.fillMaxWidth(),
            )
            SliderRow("SPETTRO LIVE FINO A", s.specXMax.toDouble(), 100.0, maxOf(1000.0, s.specXMax.toDouble()), 50.0, { "${it.toInt()} Hz" }) { v ->
                set { it.copy(specXMax = v.toInt()) }
            }
            SettingRow("Canale vibrazioni", "Accelerometro, telefono appoggiato · dB rel 1 g") {
                LfhSwitch(e.vib.enabled) { setE { it.copy(vib = it.vib.copy(enabled = !it.vib.enabled)) } }
            }
            if (e.vib.enabled) {
                SliderRow("SOGLIA VIBRAZIONI", e.vib.thr, -120.0, -20.0, 1.0, { "${it.toInt()} dB" }) { v ->
                    setE { it.copy(vib = it.vib.copy(thr = v)) }
                }
            }
            SettingRow("Stima dB SPL", "dBFS + offset · stima, non fonometria") {
                LfhSwitch(s.calib.enabled) { set { it.copy(calib = it.calib.copy(enabled = !it.calib.enabled)) } }
            }
            if (s.calib.enabled) {
                SliderRow(
                    "OFFSET", s.calib.offsetDb, 60.0, 180.0, 1.0, { "+${it.toInt()} dB" },
                    sub = "Regola su un suono costante con un fonometro (anche un'app)",
                ) { v -> set { it.copy(calib = it.calib.copy(offsetDb = v)) } }
            }
        }

        // ── 04 clip audio ──────────────────────────────────────────────────
        Module("clip", 4, "CLIP AUDIO", if (e.clipsEnabled) "${e.clipSeconds} S · MAX ${e.clipsMax} PER NOTTE" else "DISATTIVATE") {
            SettingRow("Registra clip sugli eventi", "WAV all'inizio di ogni evento", divider = false) {
                LfhSwitch(e.clipsEnabled) { setE { it.copy(clipsEnabled = !it.clipsEnabled) } }
            }
            if (e.clipsEnabled) {
                SliderRow("DURATA", e.clipSeconds.toDouble(), 5.0, maxOf(60.0, e.clipSeconds.toDouble()), 5.0, { "${it.toInt()} s" }) { v ->
                    setE { it.copy(clipSeconds = v.toInt()) }
                }
                SliderRow("MASSIMO PER NOTTE", e.clipsMax.toDouble(), 0.0, maxOf(50.0, e.clipsMax.toDouble()), 1.0, { "${it.toInt()}" }) { v ->
                    setE { it.copy(clipsMax = v.toInt()) }
                }
            }
        }

        // ── 05 sistema ─────────────────────────────────────────────────────
        val updAvail = upd.release != null || upd.apk != null
        Module(
            "sys", 5, "SISTEMA",
            (if (s.lan.enabled) "PC ON" else "PC OFF") + " · " + (if (exempt) "BATTERIA OK" else "BATTERIA DA ESENTARE") +
                " · " + (if (updAvail) "AGGIORNAMENTO" else "V${installed.uppercase()}"),
            warn = !exempt || updAvail,
        ) {
            val pct = Regex("(\\d+)%").find(upd.message)?.groupValues?.get(1)?.toIntOrNull()
            when {
                upd.busy && upd.release != null -> {
                    SettingRow("Download ${upd.release?.tag}", "Scarico l'APK e verifico la firma… ${pct ?: 0}%", divider = false) {
                        RowCta("ANNULLA") { AppUpdater.cancel() }
                    }
                    Progress((pct ?: 0) / 100f)
                }
                upd.busy -> SettingRow("Aggiornamenti", upd.message.ifBlank { "Ricerca…" }, divider = false)
                upd.apk != null -> SettingRow("Pronto da installare", "${upd.release?.tag.orEmpty()} · conferma nella schermata Android", divider = false) {
                    RowCta("INSTALLA", accent = true) { install() }
                }
                upd.release != null -> SettingRow(
                    "Disponibile ${upd.release?.tag}",
                    if (bus.running) "Termina prima la registrazione" else "Release stabile su GitHub",
                    divider = false,
                    subColor = if (bus.running) Lfh.OrangeInk else Lfh.InkDim,
                ) {
                    RowCta(if (bus.running) "TERMINA REC" else "AGGIORNA", accent = !bus.running) {
                        if (bus.running) shell.toast("Termina prima la registrazione") else install()
                    }
                }
                else -> SettingRow(
                    "Versione $installed",
                    upd.message.ifBlank { "Release installata" },
                    divider = false,
                ) { RowCta("CONTROLLA") { AppUpdater.check(ctx) } }
            }
            SettingRow("Monitor dal PC", "Consultabile anche dopo lo stop · disattiva per chiudere il server") {
                LfhSwitch(s.lan.enabled) {
                    set {
                        val token = it.lan.token.ifBlank { randomToken() }
                        it.copy(lan = it.lan.copy(enabled = !it.lan.enabled, token = token))
                    }
                }
            }
            if (s.lan.enabled) {
                val url = bus.lanUrl
                SettingRow(
                    url?.removePrefix("http://")?.substringBefore("/") ?: "Wi-Fi non connesso",
                    if (url == null) "Collega il telefono alla rete di casa"
                    else "Server attivo anche con REC fermo · accesso con token",
                ) {
                    if (url != null) RowCta("COPIA") {
                        copyText(ctx, url)
                        shell.toast("URL copiato negli appunti")
                    }
                }
            }
            if (exempt) {
                SettingRow("Esenzione batteria", "Attiva: il servizio non verrà chiuso di notte")
            } else {
                SettingRow("Esenzione batteria", "Non attiva: alcuni telefoni chiudono il servizio di notte", subColor = Lfh.OrangeInk) {
                    RowCta("RICHIEDI", accent = true) {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }
        }

        Mono(
            "BANDE E SOGLIE SI REGOLANO NEL MONITOR · LIVELLI IN DBFS, MISURA INDICATIVA",
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp),
            size = 9.sp, spacing = 0.08.em, align = TextAlign.Center, lineHeight = 14.sp,
        )
    }
}

@Composable
private fun Progress(frac: Float) {
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Black.copy(alpha = 0.1f))) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(4.dp).background(Lfh.Orange))
    }
}

/**
 * Flusso di aggiornamento: un tocco esplicito scarica, verifica e apre
 * l'installer di Android (con il permesso "origini sconosciute" se manca).
 */
@Composable
private fun rememberInstaller(): () -> Unit {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    var requested by remember { mutableStateOf(false) }
    var installAfterDownload by remember { mutableStateOf(false) }
    fun launchInstaller() {
        if (MonitorBus.state.value.running) {
            shell.toast("Termina prima la registrazione")
            return
        }
        val file = AppUpdater.state.value.apk ?: return
        runCatching {
            AppUpdater.validateApk(ctx, file)
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            ctx.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }.onFailure { shell.toast("Installazione non avviata: ${it.message}") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (requested && ctx.packageManager.canRequestPackageInstalls()) launchInstaller()
        else shell.toast("Autorizza questa app a installare aggiornamenti, poi riprova.")
        requested = false
    }
    fun installOrAsk() {
        if (ctx.packageManager.canRequestPackageInstalls()) {
            launchInstaller()
        } else {
            requested = true
            permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
        }
    }
    val st by AppUpdater.state.collectAsState()
    LaunchedEffect(st.apk, st.busy) {
        if (installAfterDownload && !st.busy) {
            installAfterDownload = false
            if (st.apk != null) installOrAsk()
        }
    }
    return {
        val cur = AppUpdater.state.value
        when {
            MonitorBus.state.value.running -> shell.toast("Termina prima la registrazione")
            cur.apk == null && cur.release != null -> {
                installAfterDownload = true
                AppUpdater.download(ctx)
            }
            cur.apk != null -> installOrAsk()
        }
    }
}

/** Modulo a fisarmonica numerato del Setup. */
@Composable
private fun Module(
    id: String,
    n: Int,
    title: String,
    summary: String,
    warn: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shell = LocalShell.current
    val open = shell.setupOpen == id
    val bg by animateColorAsState(if (open) Lfh.CardOpen else Lfh.Card, label = "modBg")
    val rot by animateFloatAsState(if (open) 45f else 0f, spring(dampingRatio = 0.5f, stiffness = 400f), label = "modRot")
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, Color.Black.copy(alpha = 0.07f), shape)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .press(Hap.TAP, shift = 0.dp) { shell.setupOpen = if (open) null else id }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Dot("%02d".format(n), Modifier.width(24.dp), size = 18.sp, color = Lfh.Orange)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Mono(title, size = 11.sp, color = Lfh.Ink, weight = FontWeight.SemiBold, maxLines = 1)
                Mono(summary, size = 10.sp, spacing = 0.sp, maxLines = 1)
            }
            if (warn) Led(true)
            Box(
                Modifier
                    .size(28.dp)
                    .graphicsLayer { rotationZ = rot }
                    .clip(CircleShape)
                    .background(Lfh.Key)
                    .border(1.dp, Color.Black.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Sans("+", size = 13.sp, weight = FontWeight.Medium) }
        }
        Expand(open) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) { content() }
        }
    }
}

// ── Quadrante orario ─────────────────────────────────────────────────────

/**
 * Quadrante 24 h: due maniglie trascinabili a passi di 15 minuti.
 * Notturno: ON/OFF. Continuo: 1 (inizio Notte) e 2 (inizio Giorno).
 * Il valore si salva al rilascio; durante il trascinamento è locale.
 */
@Composable
private fun ScheduleDial(mode: String, a: Int, b: Int?, onSet: (which: Int, minute: Int) -> Unit) {
    val view = LocalView.current
    var la by remember(a) { mutableIntStateOf(a) }
    var lb by remember(b) { mutableStateOf(b) }
    val cbSet by rememberUpdatedState(onSet)
    val curA by rememberUpdatedState(la)
    val curB by rememberUpdatedState(lb)
    val nowMin = remember {
        val c = java.util.Calendar.getInstance()
        c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }
    Canvas(
        Modifier
            .size(236.dp)
            .pointerInput(mode) {
                if (mode == "manual") return@pointerInput
                fun pick(o: Offset): Int {
                    val x = o.x - size.width / 2f
                    val y = o.y - size.height / 2f
                    var ang = atan2(y, x) + PI.toFloat() / 2
                    if (ang < 0) ang += (2 * PI).toFloat()
                    return ((ang / (2 * PI).toFloat() * 1440 / 15).roundToInt() * 15) % 1440
                }
                fun dist(x: Int?, m: Int): Int = if (x == null) Int.MAX_VALUE else minOf(abs(x - m), 1440 - abs(x - m))
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val m0 = pick(down.position)
                    val which = if (dist(curA, m0) <= dist(curB, m0)) 0 else 1
                    fun put(m: Int) {
                        val prev = if (which == 0) curA else curB
                        if (prev == m) return
                        Haptics.tick(view)
                        if (which == 0) la = m else lb = m
                    }
                    put(m0)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        put(pick(ch.position))
                    }
                    val v = if (which == 0) curA else curB
                    if (v != null) cbSet(which, v)
                }
            },
    ) { drawDial(mode, la, lb, nowMin) }
}

private fun DrawScope.drawDial(mode: String, a: Int, b: Int?, nowMin: Int) {
    val cx = size.width / 2
    val cy = size.height / 2
    val r = size.width / 2 - 26.dp.toPx()
    val ring = 16.dp.toPx()
    fun ang(m: Int) = m / 1440f * 360f - 90f
    fun pt(m: Int, rr: Float): Offset {
        val t = Math.toRadians(ang(m).toDouble())
        return Offset(cx + cos(t).toFloat() * rr, cy + sin(t).toFloat() * rr)
    }
    val tl = Offset(cx - r, cy - r)
    val sz = Size(2 * r, 2 * r)
    drawCircle(Color.Black.copy(alpha = 0.08f), r, Offset(cx, cy), style = Stroke(ring))
    for (h in 0 until 24) {
        val major = h % 6 == 0
        drawLine(
            if (major) Lfh.Ink else Color.Black.copy(alpha = 0.25f),
            pt(h * 60, r + 12.dp.toPx()), pt(h * 60, r + (if (major) 20 else 16).dp.toPx()),
            strokeWidth = (if (major) 1.5f else 1f).dp.toPx(),
        )
    }
    val lab = 9.sp.toPx()
    for ((t, m) in listOf("00" to 0, "06" to 360, "12" to 720, "18" to 1080)) {
        val p = pt(m, r - 22.dp.toPx())
        monoText(t, p.x, p.y + lab / 3, Lfh.InkDim, lab, Paint.Align.CENTER)
    }
    drawCircle(Lfh.Ink, 2.5.dp.toPx(), pt(nowMin, r + 14.dp.toPx()))

    fun arc(m0: Int, m1: Int, col: Color) {
        var sweep = (m1 - m0 + 1440) % 1440 / 1440f * 360f
        if (sweep == 0f) sweep = 360f
        drawArc(col, ang(m0), sweep, false, tl, sz, style = Stroke(ring, cap = StrokeCap.Butt))
    }
    fun handle(m: Int, label: String) {
        val p = pt(m, r)
        val hr = 13.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.18f), hr + 1f, p + Offset(0f, 1.5f.dp.toPx()))
        drawCircle(Lfh.Key, hr, p)
        drawCircle(Color.Black.copy(alpha = 0.25f), hr, p, style = Stroke(1.dp.toPx()))
        monoText(label, p.x, p.y + 3.dp.toPx(), Lfh.Ink, 8.sp.toPx(), Paint.Align.CENTER)
    }
    val lines: List<String>
    val small: String
    when {
        mode == "night" && b != null -> {
            arc(a, b, Lfh.Orange)
            handle(a, "ON")
            handle(b, "OFF")
            lines = listOf(fmtHm(a), fmtHm(b))
            small = fmtDur(((b - a + 1440) % 1440) * 60L).uppercase() + " OGNI NOTTE"
        }
        mode == "cont" && b != null -> {
            arc(a, b, Lfh.Orange)
            arc(b, a, Lfh.Amber)
            handle(a, "1")
            handle(b, "2")
            lines = listOf(fmtHm(a), fmtHm(b))
            small = "NOTTE ARANCIO · GIORNO GIALLO"
        }
        mode == "cont" -> {
            arc(a, a, Lfh.Orange)
            handle(a, "1")
            lines = listOf(fmtHm(a))
            small = "UNA SESSIONE OGNI 24H"
        }
        else -> {
            lines = listOf("MANUALE")
            small = "AVVIA E FERMA CON REC"
        }
    }
    val fs = (if (lines.size > 1) 20 else 18).sp.toPx()
    lines.forEachIndexed { i, l ->
        val y = cy - 4.dp.toPx() + (i - (lines.size - 1) / 2f) * (fs + 2.dp.toPx()) + fs / 3
        monoText(l, cx, y, Lfh.Ink, fs, Paint.Align.CENTER, dot = true)
    }
    monoText(small, cx, cy + (if (lines.size > 1) 30 else 18).dp.toPx(), Lfh.InkDim, 7.sp.toPx(), Paint.Align.CENTER)
}
