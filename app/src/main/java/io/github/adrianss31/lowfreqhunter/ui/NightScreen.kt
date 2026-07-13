package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import io.github.adrianss31.lowfreqhunter.service.MonitorService
import io.github.adrianss31.lowfreqhunter.ui.Render.drawWaterfallSlices
import kotlinx.coroutines.delay

@Composable
fun NightScreen() {
    val ctx = LocalContext.current
    val settings by SettingsRepo.get(ctx).flow.collectAsState(initial = AppSettings())
    val bus by MonitorBus.state.collectAsState()
    val slices by MonitorBus.slices.collectAsState()
    val events by MonitorBus.events.collectAsState()
    val markers by MonitorBus.markers.collectAsState()

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val enabled = settings.engine.enabledBands()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // testata: orologio + REC
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DotValue(fmtClock(nowMs), color = Lfh.Text, size = 38)
                if (bus.running && bus.mode == "listen") {
                    CapsLabel("SOLO ASCOLTO da ${fmtDur((nowMs - bus.startedAt) / 1000)} · non salvata", color = Lfh.Amber)
                } else if (bus.running) {
                    CapsLabel("REC da ${fmtDur((nowMs - bus.startedAt) / 1000)} · ${bus.audioSource}", color = Lfh.Rec)
                } else {
                    CapsLabel("log notturno", color = Lfh.TextFaint)
                }
            }
            RecButton(recording = bus.running) {
                if (bus.running) MonitorService.stop(ctx) else MonitorService.start(ctx)
            }
        }

        if (!bus.running) {
            Panel(Modifier.fillMaxWidth()) {
                Text(
                    "Premi REC: il monitoraggio continua anche a schermo spento,\ncon una notifica che mostra lo stato.",
                    color = Lfh.TextDim, fontSize = 13.sp, lineHeight = 20.sp,
                )
                if (settings.schedule.enabled) {
                    Spacer(Modifier.height(8.dp))
                    CapsLabel(
                        "Programmato ogni notte ${fmtHm(settings.schedule.startMin)}–${fmtHm(settings.schedule.endMin)}",
                        color = Lfh.Accent,
                    )
                }
            }
            return@Column
        }

        // dashboard PC (server LAN attivo)
        bus.lanUrl?.let { url ->
            Panel(Modifier.fillMaxWidth()) {
                CapsLabel("Monitor dal PC — apri nel browser:")
                Spacer(Modifier.height(4.dp))
                Text(url, color = Lfh.Accent, fontSize = 13.sp, fontFamily = MonoFont)
            }
        }

        // waterfall della sessione
        Panel(Modifier.fillMaxWidth()) {
            CapsLabel("Spettrogramma · 20–200 Hz · tutta la sessione")
            Spacer(Modifier.height(6.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                drawWaterfallSlices(slices, enabled.map { Pair(it.center, Lfh.bandColor(it.id)) })
            }
        }

        // strisce presenza
        Panel(Modifier.fillMaxWidth()) {
            val evCount = events.count { it.band != Channels.GAP }
            CapsLabel("Presenza · $evCount eventi · ${markers.size} marker")
            Spacer(Modifier.height(8.dp))
            val t0 = bus.startedAt / 1000
            val span = maxOf(nowMs / 1000 - t0, 1L)
            val channels = enabled.map { it.id } + if (settings.engine.vib.enabled) listOf(Channels.VIB) else emptyList()
            for (ch in channels) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    CapsLabel(settings.engine.channelLabel(ch), Modifier.width(52.dp))
                    Canvas(
                        Modifier
                            .weight(1f)
                            .height(12.dp)
                            .background(Lfh.Surface2)
                    ) {
                        val color = if (ch == Channels.VIB) Lfh.VibColor else Lfh.bandColor(ch)
                        val bars = events.filter { it.band == ch }.map { Pair(it.startT, it.endT) } +
                            (bus.activeBands[ch]?.let { listOf(Pair(it, nowMs / 1000)) } ?: emptyList())
                        for ((s, e) in bars) {
                            val x0 = (s - t0).toFloat() / span * size.width
                            val x1 = (e - t0).toFloat() / span * size.width
                            drawRect(color, Offset(x0, 0f), Size(maxOf(x1 - x0, 3f), size.height))
                        }
                        if (ch == channels.first()) {
                            for (m in markers) {
                                val x = (m - t0).toFloat() / span * size.width
                                drawRect(androidx.compose.ui.graphics.Color.White, Offset(x, 0f), Size(2f, size.height))
                            }
                        }
                    }
                }
            }
        }

        // segnale adesso
        Panel(Modifier.fillMaxWidth()) {
            CapsLabel("Segnale adesso")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                DotValue("%.1f".format(bus.domHz), color = Lfh.Accent, size = 32)
                Spacer(Modifier.width(6.dp))
                CapsLabel("Hz dominante (mediana ~3 s)", color = Lfh.TextFaint)
                Spacer(Modifier.weight(1f))
                if (bus.activeBands.isNotEmpty()) {
                    val since = bus.activeBands.values.min()
                    CapsLabel("● presente da ${fmtDur(nowMs / 1000 - since)}", color = Lfh.Rec)
                } else {
                    CapsLabel("silenzio", color = Lfh.TextFaint)
                }
            }
            Spacer(Modifier.height(8.dp))
            for (b in enabled) {
                val lvl = bus.levels[b.id] ?: -120.0
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    CapsLabel(b.label, Modifier.width(52.dp))
                    SegMeter(
                        frac = ((lvl + 120.0) / 120.0).toFloat(),
                        color = Lfh.bandColor(b.id),
                        thrFrac = ((b.thr + 120.0) / 120.0).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    // evento in corso: il dB resta leggibile, il ● rosso e la
                    // durata sotto segnalano che si è sopra soglia
                    ActiveDbValue(lvl = fmtDb(lvl), activeSince = bus.activeBands[b.id], nowMs = nowMs)
                }
            }
            fmtSpl(bus.levels.values.maxOrNull(), settings.calib)?.let {
                Spacer(Modifier.height(4.dp))
                CapsLabel("banda più forte $it (stima)", color = Lfh.TextFaint)
            }
            if (settings.engine.vib.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    CapsLabel("Vibraz.", Modifier.width(52.dp))
                    SegMeter(
                        frac = (((bus.vibDb ?: -120.0) + 120.0) / 120.0).toFloat(),
                        color = Lfh.VibColor,
                        thrFrac = ((settings.engine.vib.thr + 120.0) / 120.0).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    ActiveDbValue(lvl = fmtDb(bus.vibDb), activeSince = bus.activeBands[Channels.VIB], nowMs = nowMs)
                }
            }
        }

        if (bus.mode == "rec") {
            HwButton("▲ lo sento adesso (marker)", Modifier.fillMaxWidth(), color = Lfh.Amber, heavy = true) {
                MonitorService.instance?.addMarker("button")
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            CapsLabel(
                "Puoi spegnere lo schermo: la registrazione continua." +
                    (bus.batteryPct?.let { " · Batteria $it%" } ?: ""),
                color = Lfh.TextFaint,
            )
        }
    }
}

/** Valore dB di una banda: sempre leggibile; con evento in corso diventa rosso
 *  col ● e sotto compare da quanto la banda è sopra soglia. */
@Composable
private fun ActiveDbValue(lvl: String, activeSince: Long?, nowMs: Long) {
    Column(Modifier.width(60.dp), horizontalAlignment = Alignment.End) {
        Text(
            if (activeSince != null) "● $lvl" else lvl,
            color = if (activeSince != null) Lfh.Rec else Lfh.TextDim,
            fontSize = 10.sp, fontFamily = MonoFont,
        )
        if (activeSince != null) {
            Text(
                fmtDur(nowMs / 1000 - activeSince),
                color = Lfh.Rec, fontSize = 8.sp, fontFamily = MonoFont,
            )
        }
    }
}
