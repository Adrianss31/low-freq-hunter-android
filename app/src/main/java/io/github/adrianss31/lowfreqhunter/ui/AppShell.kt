package io.github.adrianss31.lowfreqhunter.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.server.LanServer
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import io.github.adrianss31.lowfreqhunter.service.MonitorService
import io.github.adrianss31.lowfreqhunter.update.AppUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab(val label: String) {
    MONITOR("MONITOR"), ARCHIVE("ARCHIVIO"), MAP("MAPPA"), SETUP("SETUP"),
}

/** Fogli che salgono dal basso sopra la scheda corrente. */
sealed interface Sheet {
    data object Context : Sheet
    data class Session(val id: String) : Sheet
    /** [monthKey] = anno·12 + mese del mese visibile in archivio (preset "mese"). */
    data class Dossier(val monthKey: Int) : Sheet
}

class ConfirmReq(val title: String, val body: String, val yes: String, val onYes: () -> Unit)

/**
 * Stato della shell: scheda, fogli, toast, conferme — più la poca memoria
 * delle schede che deve sopravvivere al cambio (banda selezionata, mese
 * dell'archivio, rilievo aperto).
 */
class Shell {
    var tab by mutableStateOf(Tab.MONITOR)
    var dir by mutableIntStateOf(1)
    var setupOpen by mutableStateOf<String?>("prog")
    var sheet by mutableStateOf<Sheet?>(null)
    var toastMsg by mutableStateOf("")
    var toastSeq by mutableIntStateOf(0)
    var confirm by mutableStateOf<ConfirmReq?>(null)

    var selBand by mutableStateOf<String?>(null)
    var bandEdit by mutableStateOf(false)
    var archMonth by mutableStateOf<Int?>(null)
    var bandFilter by mutableStateOf<String?>(null)
    var mapSel by mutableStateOf<String?>(null)

    fun go(t: Tab) {
        if (t == tab) return
        dir = if (t.ordinal > tab.ordinal) 1 else -1
        tab = t
        bandEdit = false
    }

    fun toast(msg: String) {
        toastMsg = msg
        toastSeq++
    }

    fun ask(title: String, body: String, yes: String = "ELIMINA", onYes: () -> Unit) {
        confirm = ConfirmReq(title, body, yes, onYes)
    }
}

val LocalShell = staticCompositionLocalOf<Shell> { error("Shell mancante") }

/** Alterna true/false ogni [periodMs] finché [active]; true quando fermo. */
@Composable
fun rememberBlink(active: Boolean, periodMs: Long = 500): Boolean {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(active) {
        on = true
        while (active) {
            delay(periodMs)
            on = !on
        }
    }
    return on
}

/** Spazio da lasciare in fondo alle schede: la capsula galleggia sopra. */
@Composable
fun dockClearance() = 124.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
fun AppShell() {
    val shell = remember { Shell() }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { if (AppUpdater.state.value.message.isEmpty()) AppUpdater.check(ctx) }

    CompositionLocalProvider(LocalShell provides shell) {
        BackHandler(enabled = shell.sheet != null || shell.confirm != null) {
            if (shell.confirm != null) shell.confirm = null else shell.sheet = null
        }
        BackHandler(enabled = shell.sheet == null && shell.confirm == null && shell.tab != Tab.MONITOR) {
            shell.go(Tab.MONITOR)
        }
        Box(Modifier.fillMaxSize().background(Lfh.Bg)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Header()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val off = with(LocalDensity.current) { 28.dp.roundToPx() }
                    AnimatedContent(
                        targetState = shell.tab,
                        transitionSpec = {
                            val d = shell.dir
                            (slideInHorizontally(tween(450, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) { off * d } +
                                fadeIn(tween(320))) togetherWith fadeOut(tween(120))
                        },
                        label = "tab",
                    ) { t ->
                        when (t) {
                            Tab.MONITOR -> MonitorScreen()
                            Tab.ARCHIVE -> ArchiveScreen()
                            Tab.MAP -> MapScreen()
                            Tab.SETUP -> SetupScreen()
                        }
                    }
                }
            }
            // il contenuto sfuma sotto la capsula
            val navB = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(120.dp + navB)
                    .background(
                        Brush.verticalGradient(
                            0f to Lfh.Bg.copy(alpha = 0f), 0.7f to Lfh.Bg, 1f to Lfh.Bg,
                        ),
                    ),
            )
            Dock(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp),
            )
            ToastHost(shell)
            SheetHost(shell)
            ConfirmHost(shell)
        }
    }
}

// ── Testata: marchio, modo, spie ─────────────────────────────────────────

@Composable
private fun Header() {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val bus by MonitorBus.state.collectAsState()
    val settings by SettingsRepo.get(ctx).flow.collectAsState(initial = null)
    val upd by AppUpdater.state.collectAsState()
    val rec = bus.running && bus.mode == "rec"
    val blink = rememberBlink(rec)
    val prog = settings?.let { it.continuous.enabled || it.schedule.enabled } ?: false
    val lan = settings?.lan?.enabled ?: false
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Sans("LFH", Modifier.alignByBaseline(), size = 19.sp, weight = FontWeight.ExtraBold, spacing = (-0.03).em)
            Spacer(Modifier.width(8.dp))
            Mono(shell.tab.label, Modifier.alignByBaseline(), size = 9.sp, spacing = 0.14.em, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LedLabel("REC", rec && blink) { shell.go(Tab.MONITOR) }
            LedLabel("PROG", prog) {
                shell.setupOpen = "prog"
                shell.go(Tab.SETUP)
            }
            LedLabel("PC", lan) {
                val s = settings
                if (s == null || !s.lan.enabled) {
                    shell.toast("Dashboard PC disattivata · Setup → Sistema")
                } else {
                    val url = bus.lanUrl ?: LanServer.deviceIp()?.let { "http://$it:${s.lan.port}/?k=${s.lan.token}" }
                    if (url == null) {
                        shell.toast("Telefono non connesso al Wi-Fi")
                    } else {
                        copyText(ctx, url)
                        shell.toast(
                            "Dashboard: $url · copiato" + if (!bus.running) "\nIl server parte insieme al monitoraggio." else "",
                        )
                    }
                }
            }
            if (upd.release != null || upd.apk != null) {
                Box(
                    Modifier
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Lfh.Ink)
                        .press(Hap.TAP, shift = 0.dp) {
                            shell.setupOpen = "sys"
                            shell.go(Tab.SETUP)
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Mono("UPD", size = 9.sp, color = Lfh.Orange, weight = FontWeight.SemiBold, spacing = 0.1.em) }
            }
        }
    }
}

@Composable
private fun LedLabel(k: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.press(Hap.TICK, shift = 0.dp, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Led(on)
        Mono(k, size = 9.sp, spacing = 0.12.em)
    }
}

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("lfh", text))
}

// ── Capsula di navigazione + REC ─────────────────────────────────────────

@Composable
private fun Dock(modifier: Modifier) {
    val shell = LocalShell.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val pill = RoundedCornerShape(24.dp)
        Box(
            Modifier
                .shadow(16.dp, pill, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(pill)
                .background(Lfh.Ink)
                .padding(5.dp),
        ) {
            val x by animateDpAsState(
                (shell.tab.ordinal * 64).dp,
                spring(dampingRatio = 0.62f, stiffness = 380f),
                label = "thumb",
            )
            Box(
                Modifier
                    .offset(x = x)
                    .size(60.dp, 54.dp)
                    .keyFace(19.dp, Lfh.Key, edge = 0f, inset = 0.10f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in Tab.entries) NavItem(t, t == shell.tab) { shell.go(t) }
            }
        }
        RecButton()
    }
}

@Composable
private fun NavItem(tab: Tab, on: Boolean, onClick: () -> Unit) {
    val fg by animateColorAsState(if (on) Lfh.Ink else Lfh.Paper.copy(alpha = 0.7f), label = "navFg")
    Column(
        Modifier
            .size(60.dp, 54.dp)
            .press(Hap.TAP, shift = 0.dp, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NavIcon(tab, on)
        Spacer(Modifier.height(6.dp))
        Mono(tab.label, size = 8.sp, color = fg, weight = FontWeight.SemiBold, spacing = 0.06.em, maxLines = 1)
    }
}

@Composable
private fun RecButton() {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val bus by MonitorBus.state.collectAsState()
    val rec = bus.running && bus.mode == "rec"
    val bg by animateColorAsState(if (rec) Lfh.Orange else Lfh.Key, tween(350), label = "recBg")
    val dotC by animateColorAsState(if (rec) Lfh.Ink else Lfh.Orange, tween(350), label = "recDot")
    val dot by animateDpAsState(if (rec) 16.dp else 24.dp, spring(dampingRatio = 0.5f, stiffness = 420f), label = "recSz")
    val corner by animateDpAsState(if (rec) 3.dp else 12.dp, spring(dampingRatio = 0.5f, stiffness = 420f), label = "recR")
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        if (rec) {
            val inf = rememberInfiniteTransition(label = "pulse")
            val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulseP")
            Box(
                Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        val s = 1f + 0.55f * p
                        scaleX = s
                        scaleY = s
                        alpha = 0.55f * (1f - p)
                    }
                    .border(2.dp, Lfh.Orange, CircleShape),
            )
        }
        Box(
            Modifier
                .size(64.dp)
                .shadow(14.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, Color.Black.copy(alpha = 0.18f), CircleShape)
                .press(Hap.HEAVY, shift = 0.dp, scale = 0.93f) {
                    toggleRec(ctx, shell) { scope.launch { delay(800); MonitorService.start(ctx) } }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(dot).clip(RoundedCornerShape(corner)).background(dotC))
        }
    }
}

/** REC: avvia o ferma la registrazione; dal solo-ascolto (widget) passa a REC. */
private fun toggleRec(ctx: Context, shell: Shell, restartLater: () -> Unit) {
    val st = MonitorBus.state.value
    if (st.running && st.mode == "rec") {
        MonitorService.stop(ctx)
        shell.toast("Sessione salvata in Archivio · ${st.eventsCount} eventi")
        return
    }
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        shell.toast("Serve il permesso del microfono: concedilo dalle impostazioni dell'app.")
        return
    }
    if (st.running) {
        // il widget stava solo ascoltando: chiudi e riparti in registrazione
        MonitorService.stop(ctx)
        restartLater()
    } else {
        MonitorService.start(ctx)
    }
    shell.toast("Registrazione avviata · continua a schermo spento")
}

// ── Toast, fogli, conferme ───────────────────────────────────────────────

@Composable
private fun BoxScope.ToastHost(shell: Shell) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(shell.toastSeq) {
        if (shell.toastSeq == 0) return@LaunchedEffect
        visible = true
        delay(2600)
        visible = false
    }
    AnimatedVisibility(
        visible,
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 100.dp),
        enter = fadeIn(tween(250)) + slideInVertically(tween(320)) { it / 3 } + scaleIn(tween(320), initialScale = 0.96f),
        exit = fadeOut(tween(260)) + slideOutVertically(tween(260)) { it / 5 },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Lfh.Ink)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) { Sans(shell.toastMsg, size = 12.sp, color = Lfh.Paper, lineHeight = 17.sp) }
    }
}

@Composable
private fun BoxScope.SheetHost(shell: Shell) {
    // il contenuto resta visibile durante l'animazione di chiusura
    val last = remember { arrayOfNulls<Sheet>(1) }
    shell.sheet?.let { last[0] = it }
    val open = shell.sheet != null
    AnimatedVisibility(open, enter = fadeIn(tween(300)), exit = fadeOut(tween(280))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x80141311))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    shell.sheet = null
                },
        )
    }
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    AnimatedVisibility(
        open,
        Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(500, easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1.02f))) { it },
        exit = slideOutVertically(tween(280, easing = CubicBezierEasing(0.5f, 0f, 0.75f, 0f))) { it },
    ) {
        val s = last[0] ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        val base = Modifier
            .fillMaxWidth()
            .shadow(20.dp, shape)
            .clip(shape)
            .background(Lfh.Card)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        Column(
            if (s is Sheet.Session) base.fillMaxHeight(0.92f) else base.heightIn(max = screenH * 0.86f),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Black.copy(alpha = 0.2f)))
            }
            Column(Modifier.weight(1f, fill = false).navigationBarsPadding().imePadding()) {
                when (s) {
                    Sheet.Context -> ContextSheet()
                    is Sheet.Session -> SessionSheet(s.id)
                    is Sheet.Dossier -> DossierSheet(s.monthKey)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ConfirmHost(shell: Shell) {
    val last = remember { arrayOfNulls<ConfirmReq>(1) }
    shell.confirm?.let { last[0] = it }
    val open = shell.confirm != null
    AnimatedVisibility(open, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x8C141311))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    shell.confirm = null
                },
        )
    }
    AnimatedVisibility(
        open,
        Modifier.align(Alignment.Center).padding(24.dp),
        enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = 500f), initialScale = 0.6f) + fadeIn(tween(200)),
        exit = scaleOut(tween(150), targetScale = 0.9f) + fadeOut(tween(150)),
    ) {
        val c = last[0] ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Lfh.Card)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Sans(c.title, size = 18.sp, weight = FontWeight.Bold, spacing = (-0.02).em)
            Sans(c.body, size = 13.sp, color = Lfh.InkDim, lineHeight = 19.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextKey("ANNULLA", Modifier.weight(1f).height(50.dp), radius = 10.dp) { shell.confirm = null }
                TextKey(c.yes, Modifier.weight(1f).height(50.dp), bg = Lfh.Orange, radius = 10.dp, hap = Hap.HEAVY) {
                    shell.confirm = null
                    c.onYes()
                }
            }
        }
    }
}
