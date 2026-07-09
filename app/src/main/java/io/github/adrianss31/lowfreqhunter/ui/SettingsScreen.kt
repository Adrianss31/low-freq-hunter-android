package io.github.adrianss31.lowfreqhunter.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.engine.BandCfg
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import kotlinx.coroutines.launch

private const val MAX_BANDS = 8

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepo.get(ctx) }
    val settings by repo.flow.collectAsState(initial = AppSettings())
    val bus by MonitorBus.state.collectAsState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    fun upd(transform: (AppSettings) -> AppSettings) {
        scope.launch { repo.update(transform) }
    }

    fun updEngine(transform: (EngineCfg) -> EngineCfg) {
        upd { it.copy(engine = transform(it.engine)) }
    }

    fun updBand(id: String, transform: (BandCfg) -> BandCfg) {
        updEngine { e -> e.copy(bands = e.bands.map { if (it.id == id) transform(it) else it }) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (bus.running) {
            Panel(Modifier.fillMaxWidth()) {
                CapsLabel("⚠ log in corso: le modifiche valgono dalla prossima sessione", color = Lfh.Amber)
            }
        }

        CapsLabel("Bande di frequenza e soglie", color = Lfh.Text)

        for (band in settings.engine.bands) {
            BandCard(
                band = band,
                canDelete = settings.engine.bands.size > 1,
                onChange = { updBand(band.id, it) },
                onDelete = {
                    updEngine { e -> e.copy(bands = e.bands.filter { it.id != band.id }) }
                },
            )
        }

        if (settings.engine.bands.size < MAX_BANDS) {
            HwButton("＋ aggiungi banda", Modifier.fillMaxWidth()) {
                updEngine { e ->
                    val used = e.bands.map { it.id }.toSet()
                    val id = ('A'..'Z').map { it.toString() }.firstOrNull { it !in used && it != "V" }
                        ?: return@updEngine e
                    e.copy(bands = e.bands + BandCfg(id, center = 150.0, width = 10.0, thr = -55.0))
                }
            }
        }

        // canale V
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Lfh.VibColor))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("V · vibrazioni (accelerometro)", color = Lfh.Text, fontSize = 14.sp, fontFamily = MonoFont)
                    CapsLabel("il telefono appoggiato misura la vibrazione strutturale", color = Lfh.TextFaint)
                }
                HwSwitch(settings.engine.vib.enabled) {
                    updEngine { it.copy(vib = it.vib.copy(enabled = !it.vib.enabled)) }
                }
            }
            if (settings.engine.vib.enabled) {
                Spacer(Modifier.height(8.dp))
                ThrSlider(
                    label = "Soglia trigger V",
                    value = settings.engine.vib.thr,
                    unit = "dB(g)",
                    color = Lfh.VibColor,
                ) { updEngine { e -> e.copy(vib = e.vib.copy(thr = it)) } }
            }
        }

        // durate minime
        CapsLabel("Durata minima eventi", color = Lfh.Text)
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("ON dopo", color = Lfh.Text, fontSize = 13.sp)
                    CapsLabel("secondi sopra soglia per aprire", color = Lfh.TextFaint)
                }
                Stepper(
                    "${settings.engine.minOnS}", unit = "s",
                    onMinus = { updEngine { it.copy(minOnS = (it.minOnS - 1).coerceAtLeast(1)) } },
                    onPlus = { updEngine { it.copy(minOnS = (it.minOnS + 1).coerceAtMost(120)) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("OFF dopo", color = Lfh.Text, fontSize = 13.sp)
                    CapsLabel("secondi sotto soglia per chiudere", color = Lfh.TextFaint)
                }
                Stepper(
                    "${settings.engine.minOffS}", unit = "s",
                    onMinus = { updEngine { it.copy(minOffS = (it.minOffS - 1).coerceAtLeast(1)) } },
                    onPlus = { updEngine { it.copy(minOffS = (it.minOffS + 1).coerceAtMost(300)) } },
                )
            }
        }

        // programmazione oraria
        CapsLabel("Programmazione notturna", color = Lfh.Text)
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Avvio automatico ogni notte", color = Lfh.Text, fontSize = 14.sp)
                    CapsLabel("parte e si ferma da solo, anche ad app chiusa", color = Lfh.TextFaint)
                }
                HwSwitch(settings.schedule.enabled) {
                    upd { it.copy(schedule = it.schedule.copy(enabled = !it.schedule.enabled)) }
                }
            }
            if (settings.schedule.enabled) {
                var alarmRefresh by remember { mutableIntStateOf(0) }
                val am = ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                val canExact = remember(alarmRefresh) {
                    android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(3000)
                        alarmRefresh++
                    }
                }
                if (!canExact) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CapsLabel("Serve il permesso \"sveglie esatte\"", Modifier.weight(1f), color = Lfh.Amber)
                        HwButton("consenti") {
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
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    CapsLabel("Inizio", Modifier.weight(1f))
                    Stepper(
                        fmtHm(settings.schedule.startMin),
                        onMinus = { upd { it.copy(schedule = it.schedule.copy(startMin = (it.schedule.startMin - 15 + 1440) % 1440)) } },
                        onPlus = { upd { it.copy(schedule = it.schedule.copy(startMin = (it.schedule.startMin + 15) % 1440)) } },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    CapsLabel("Fine", Modifier.weight(1f))
                    Stepper(
                        fmtHm(settings.schedule.endMin),
                        onMinus = { upd { it.copy(schedule = it.schedule.copy(endMin = (it.schedule.endMin - 15 + 1440) % 1440)) } },
                        onPlus = { upd { it.copy(schedule = it.schedule.copy(endMin = (it.schedule.endMin + 15) % 1440)) } },
                    )
                }
            }
        }

        // batteria
        CapsLabel("Batteria", color = Lfh.Text)
        Panel(Modifier.fillMaxWidth()) {
            var refresh by remember { mutableIntStateOf(0) }
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            val exempt = remember(refresh) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(3000)
                    refresh++
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Esenzione ottimizzazioni", color = Lfh.Text, fontSize = 14.sp)
                    CapsLabel(
                        if (exempt) "attiva ✓ — il sistema non fermerà il log" else "non attiva — il log notturno può essere interrotto",
                        color = if (exempt) Lfh.Accent else Lfh.Amber,
                    )
                }
                if (!exempt) {
                    HwButton("richiedi") {
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

        // analisi
        CapsLabel("Analisi audio", color = Lfh.Text)
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Risoluzione FFT", color = Lfh.Text, fontSize = 13.sp)
                    CapsLabel("${"%.1f".format(48000.0 / settings.engine.fftSize)} Hz/bin", color = Lfh.TextFaint)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sz in listOf(16384, 32768)) {
                        val sel = settings.engine.fftSize == sz
                        HwButton(
                            if (sz == 32768) "alta" else "media",
                            color = if (sel) Lfh.Bg else Lfh.TextDim,
                            borderColor = if (sel) Lfh.Accent else Lfh.Border,
                            bg = if (sel) Lfh.Accent else Lfh.Surface,
                        ) { updEngine { it.copy(fftSize = sz) } }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                CapsLabel("Spettro live max", Modifier.weight(1f))
                Stepper(
                    "${settings.specXMax}", unit = "Hz",
                    onMinus = { upd { it.copy(specXMax = (it.specXMax - 50).coerceAtLeast(100)) } },
                    onPlus = { upd { it.copy(specXMax = (it.specXMax + 50).coerceAtMost(2000)) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Clip audio sugli eventi", color = Lfh.Text, fontSize = 13.sp)
                    CapsLabel("WAV di ${settings.engine.clipSeconds} s all'inizio di ogni evento (max ${settings.engine.clipsMax}/notte)", color = Lfh.TextFaint)
                }
                HwSwitch(settings.engine.clipsEnabled) {
                    updEngine { it.copy(clipsEnabled = !it.clipsEnabled) }
                }
            }
        }

        CapsLabel(
            "Isteresi ${settings.engine.hystDb.toInt()} dB · slice waterfall 30 s · gap oltre 5 s",
            color = Lfh.TextFaint,
        )
        
        // Vista eventi
        CapsLabel("Visualizzazione eventi", color = Lfh.Text)
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Modalità lista eventi", color = Lfh.Text, fontSize = 14.sp)
                    CapsLabel("Per-banda = 1 riga per frequenza con attivazioni; Per-evento = elenco cronologico", color = Lfh.TextFaint)
                }
                HwSwitch(settings.eventsViewPerBand) {
                    upd { it.copy(eventsViewPerBand = !it.eventsViewPerBand) }
                }
            }
        }
    }
}

@Composable
private fun BandCard(
    band: BandCfg,
    canDelete: Boolean,
    onChange: ((BandCfg) -> BandCfg) -> Unit,
    onDelete: () -> Unit,
) {
    val color = Lfh.bandColor(band.id)
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(band.id, color = color, fontSize = 17.sp, fontFamily = DotFont)
            Spacer(Modifier.width(10.dp))
            CapsLabel(band.label, Modifier.weight(1f), color = Lfh.TextDim)
            HwSwitch(band.enabled) { onChange { it.copy(enabled = !it.enabled) } }
            if (canDelete) {
                Spacer(Modifier.width(8.dp))
                HwButton("✕", color = Lfh.Rec) { onDelete() }
            }
        }
        if (band.enabled) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                CapsLabel("Centro", Modifier.weight(1f))
                Stepper(
                    "${band.center.toInt()}", unit = "Hz",
                    onMinus = { onChange { it.copy(center = (it.center - 1).coerceAtLeast(10.0)) } },
                    onPlus = { onChange { it.copy(center = (it.center + 1).coerceAtMost(2000.0)) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                CapsLabel("Larghezza ±", Modifier.weight(1f))
                Stepper(
                    "${band.width.toInt()}", unit = "Hz",
                    onMinus = { onChange { it.copy(width = (it.width - 1).coerceAtLeast(1.0)) } },
                    onPlus = { onChange { it.copy(width = (it.width + 1).coerceAtMost(200.0)) } },
                )
            }
            Spacer(Modifier.height(4.dp))
            ThrSlider("Soglia trigger", band.thr, "dBFS", color) { v -> onChange { it.copy(thr = v) } }
        }
    }
}

@Composable
private fun ThrSlider(
    label: String,
    value: Double,
    unit: String,
    color: androidx.compose.ui.graphics.Color,
    onValue: (Double) -> Unit,
) {
    val view = LocalView.current
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CapsLabel(label, Modifier.weight(1f))
        Text("${local.toInt()} $unit", color = color, fontSize = 16.sp, fontFamily = DotFont)
    }
    Slider(
        value = local,
        onValueChange = {
            if (it.toInt() != local.toInt()) Haptics.tick(view)
            local = it
        },
        onValueChangeFinished = { onValue(local.toInt().toDouble()) },
        valueRange = -100f..-10f,
        colors = SliderDefaults.colors(
            thumbColor = color,
            activeTrackColor = color,
            inactiveTrackColor = Lfh.Surface2,
        ),
    )
    Row(Modifier.fillMaxWidth()) {
        CapsLabel("← più sensibile", Modifier.weight(1f), color = Lfh.TextFaint)
        CapsLabel("meno sensibile →", color = Lfh.TextFaint)
    }
}
