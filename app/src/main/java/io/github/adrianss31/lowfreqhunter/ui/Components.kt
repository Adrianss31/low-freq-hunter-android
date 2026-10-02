package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToLong

// ── Testi ────────────────────────────────────────────────────────────────

/** Serigrafia monospaziata: etichette, unità, stati. */
@Composable
fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 10.sp,
    color: Color = Lfh.InkDim,
    weight: FontWeight = FontWeight.Medium,
    spacing: TextUnit = 0.12.em,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text, modifier,
        color = color, fontSize = size, fontFamily = GeistMono, fontWeight = weight,
        letterSpacing = spacing, textAlign = align, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, lineHeight = lineHeight,
    )
}

/** Testo corrente in Geist. */
@Composable
fun Sans(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 13.sp,
    color: Color = Lfh.Ink,
    weight: FontWeight = FontWeight.Normal,
    spacing: TextUnit = 0.sp,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text, modifier,
        color = color, fontSize = size, fontFamily = Geist, fontWeight = weight,
        letterSpacing = spacing, textAlign = align, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, lineHeight = lineHeight,
    )
}

/** Numeri grandi in dot-matrix. */
@Composable
fun Dot(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 20.sp,
    color: Color = Lfh.Ink,
    lineHeight: TextUnit = TextUnit.Unspecified,
    align: TextAlign? = null,
) {
    Text(
        text, modifier,
        color = color, fontSize = size, fontFamily = DotFont, fontWeight = FontWeight.Bold,
        lineHeight = lineHeight, textAlign = align, maxLines = 1,
    )
}

// ── Interazione ──────────────────────────────────────────────────────────

enum class Hap { NONE, TICK, TAP, HEAVY }

fun Hap.fire(view: android.view.View) {
    when (this) {
        Hap.TICK -> Haptics.tick(view)
        Hap.TAP -> Haptics.tap(view)
        Hap.HEAVY -> Haptics.heavy(view)
        Hap.NONE -> {}
    }
}

/**
 * Click da tasto fisico: niente ripple, il tasto scende di [shift] (o si
 * rimpicciolisce di [scale]) finché è premuto, e dà un feedback aptico.
 */
fun Modifier.press(
    hap: Hap = Hap.TAP,
    enabled: Boolean = true,
    shift: Dp = 1.dp,
    scale: Float = 1f,
    onClick: () -> Unit,
): Modifier = composed {
    val view = LocalView.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shiftPx = with(LocalDensity.current) { shift.toPx() }
    Modifier
        .graphicsLayer {
            if (pressed) {
                translationY = shiftPx
                scaleX = scale
                scaleY = scale
            }
        }
        .clickable(interactionSource = src, indication = null, enabled = enabled) {
            hap.fire(view)
            onClick()
        }
}

// ── Superfici ────────────────────────────────────────────────────────────

/** Tasto chiaro in rilievo: bordo sottile e ombra interna sul lato basso. */
fun Modifier.keyFace(
    radius: Dp,
    bg: Color = Lfh.Key,
    depth: Dp = 2.dp,
    edge: Float = 0.14f,
    inset: Float = 0.12f,
): Modifier = drawWithCache {
    val r = CornerRadius(radius.toPx())
    val clip = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), r)) }
    val d = depth.toPx()
    val sw = 1.dp.toPx()
    onDrawBehind {
        drawRoundRect(bg, cornerRadius = r)
        if (d > 0f) {
            clipPath(clip) {
                drawRect(Color.Black.copy(alpha = inset), Offset(0f, size.height - d), Size(size.width, d))
            }
        }
        if (edge > 0f) {
            drawRoundRect(
                Color.Black.copy(alpha = edge),
                topLeft = Offset(sw / 2, sw / 2),
                size = Size(size.width - sw, size.height - sw),
                cornerRadius = r,
                style = Stroke(sw),
            )
        }
    }
}

/** Tasto premuto/selezionato: scuro, incassato, ombra interna in alto. */
fun Modifier.sunkFace(radius: Dp, bg: Color = Lfh.Ink): Modifier = drawWithCache {
    val r = CornerRadius(radius.toPx())
    val clip = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), r)) }
    val sh = 5.dp.toPx()
    val shade = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), 0f, sh)
    val sw = 1.dp.toPx()
    onDrawBehind {
        drawRoundRect(bg, cornerRadius = r)
        clipPath(clip) { drawRect(shade, Offset.Zero, Size(size.width, sh)) }
        drawRoundRect(
            Color.Black, topLeft = Offset(sw / 2, sw / 2),
            size = Size(size.width - sw, size.height - sw), cornerRadius = r, style = Stroke(sw),
        )
    }
}

/** Tasto: superficie in rilievo (o incassata se [sunk]) + click fisico. */
@Composable
fun Key(
    modifier: Modifier = Modifier,
    bg: Color = Lfh.Key,
    radius: Dp = 9.dp,
    sunk: Boolean = false,
    hap: Hap = Hap.TAP,
    enabled: Boolean = true,
    depth: Dp = 2.dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .press(hap, enabled, onClick = onClick)
            .then(if (sunk) Modifier.sunkFace(radius, bg) else Modifier.keyFace(radius, bg, depth)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Tasto con sola etichetta monospaziata. */
@Composable
fun TextKey(
    label: String,
    modifier: Modifier = Modifier,
    bg: Color = Lfh.Key,
    fg: Color = Lfh.Ink,
    radius: Dp = 9.dp,
    hap: Hap = Hap.TAP,
    size: TextUnit = 10.sp,
    onClick: () -> Unit,
) {
    Key(modifier, bg = bg, radius = radius, hap = hap, onClick = onClick) {
        Mono(label, size = size, color = fg, weight = FontWeight.SemiBold, spacing = 0.1.em, maxLines = 1)
    }
}

/** Tasto "pericoloso" a contorno arancio scuro (elimina…). */
@Composable
fun DangerKey(label: String, modifier: Modifier = Modifier, height: Dp = 44.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .border(1.dp, Lfh.OrangeInk.copy(alpha = 0.4f), shape)
            .press(Hap.TAP, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Mono(label, size = 10.sp, color = Lfh.OrangeInk, weight = FontWeight.SemiBold, spacing = 0.12.em)
    }
}

/** Display scuro incassato nel corpo chiaro. */
@Composable
fun DarkPanel(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    pad: Dp = 12.dp,
    gap: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(Lfh.Panel)
            .padding(pad),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Scheda chiara piatta. */
@Composable
fun LightCard(
    modifier: Modifier = Modifier,
    bg: Color = Lfh.Card,
    radius: Dp = 14.dp,
    padH: Dp = 12.dp,
    padV: Dp = 10.dp,
    gap: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, Color.Black.copy(alpha = 0.07f), shape)
            .padding(horizontal = padH, vertical = padV),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** Sezione che si apre/chiude scorrendo in verticale. */
@Composable
fun Expand(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(420, easing = FastOutSlowInEasing)) + fadeIn(tween(300)),
        exit = shrinkVertically(tween(360, easing = FastOutSlowInEasing)) + fadeOut(tween(200)),
    ) { content() }
}

// ── Controlli ────────────────────────────────────────────────────────────

/** Interruttore a levetta: binario scuro, pomello arancio quando acceso. */
@Composable
fun LfhSwitch(on: Boolean, onToggle: () -> Unit) {
    val x by animateDpAsState(if (on) 22.dp else 0.dp, spring(dampingRatio = 0.55f, stiffness = 500f), label = "sw")
    val trk by animateColorAsState(if (on) Lfh.Ink else Color.Black.copy(alpha = 0.14f), label = "swTrk")
    val knob by animateColorAsState(if (on) Lfh.Orange else Lfh.Key, label = "swKnob")
    Box(
        Modifier
            .size(52.dp, 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(trk)
            .press(Hap.TAP, shift = 0.dp, onClick = onToggle),
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .offset(x = x)
                .size(24.dp)
                .clip(CircleShape)
                .background(knob)
                .border(1.dp, Color.Black.copy(alpha = 0.12f), CircleShape),
        )
    }
}

/** Selettore a segmenti incassato. */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
    track: Color = Lfh.Track2,
    radius: Dp = 9.dp,
    cell: @Composable (value: T, label: String, fg: Color) -> Unit = { _, label, fg ->
        Mono(label, size = 10.sp, color = fg, weight = FontWeight.SemiBold, spacing = 0.08.em, maxLines = 1)
    },
) {
    Row(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(track)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((v, l) in options) {
            val on = v == selected
            val bg by animateColorAsState(if (on) Lfh.Ink else Color.Transparent, label = "segBg")
            val fg by animateColorAsState(if (on) Lfh.Paper else Lfh.Ink, label = "segFg")
            Box(
                Modifier
                    .weight(1f)
                    .height(height)
                    .clip(RoundedCornerShape(radius - 2.dp))
                    .background(bg)
                    .press(Hap.TICK, shift = 0.dp) { onPick(v) },
                contentAlignment = Alignment.Center,
            ) { cell(v, l, fg) }
        }
    }
}

private fun snap(v: Double, min: Double, max: Double, step: Double): Double {
    val s = kotlin.math.round((v - min) / step) * step + min
    return ((s * 1000).roundToLong() / 1000.0).coerceIn(min, max)
}

/**
 * Cursore lineare: binario incassato, riempimento scuro, pomello chiaro con
 * punto arancio. Trascinamento solo orizzontale (lo scroll verticale della
 * pagina resta libero); tocco = salto al valore. Tick aptico a ogni passo.
 */
@Composable
fun LfhSlider(
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier,
    onEnd: () -> Unit = {},
) {
    val view = LocalView.current
    val cbValue by rememberUpdatedState(onValue)
    val cbEnd by rememberUpdatedState(onEnd)
    val cur by rememberUpdatedState(value)
    val frac by animateFloatAsState(
        ((value.coerceIn(min, max) - min) / (max - min)).toFloat(), tween(140), label = "slider",
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(min, max, step) {
                val pad = 13.dp.toPx()
                fun at(x: Float): Double {
                    val p = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                    return snap(min + p * (max - min), min, max, step)
                }
                detectTapGestures { o ->
                    val v = at(o.x)
                    if (v != cur) {
                        Haptics.tick(view)
                        cbValue(v)
                    }
                    cbEnd()
                }
            }
            .pointerInput(min, max, step) {
                val pad = 13.dp.toPx()
                fun at(x: Float): Double {
                    val p = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                    return snap(min + p * (max - min), min, max, step)
                }
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        val v = at(o.x)
                        if (v != cur) {
                            Haptics.tick(view)
                            cbValue(v)
                        }
                    },
                    onDragEnd = { cbEnd() },
                    onDragCancel = { cbEnd() },
                ) { ch, _ ->
                    ch.consume()
                    val v = at(ch.position.x)
                    if (v != cur) {
                        Haptics.tick(view)
                        cbValue(v)
                    }
                }
            },
    ) {
        val pad = 13.dp.toPx()
        val cy = size.height / 2
        val th = 6.dp.toPx()
        val r = CornerRadius(th / 2)
        drawRoundRect(Color.Black.copy(alpha = 0.10f), Offset(0f, cy - th / 2), Size(size.width, th), r)
        val kx = pad + frac * (size.width - 2 * pad)
        drawRoundRect(Lfh.Ink, Offset(0f, cy - th / 2), Size(kx, th), r)
        val kr = 13.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.16f), kr + 1.5f, Offset(kx, cy + 1.5f))
        drawCircle(Lfh.Key, kr, Offset(kx, cy))
        drawCircle(Color.Black.copy(alpha = 0.2f), kr, Offset(kx, cy), style = Stroke(1.dp.toPx()))
        drawCircle(Lfh.Orange, 3.dp.toPx(), Offset(kx, cy))
    }
}

/**
 * Riga "etichetta · valore" + cursore. Il valore mostrato segue il dito;
 * [onCommit] salva al rilascio (niente scritture a raffica su disco).
 */
@Composable
fun SliderRow(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    fmt: (Double) -> String,
    sub: String? = null,
    onCommit: (Double) -> Unit,
) {
    var local by remember(value) { mutableDoubleStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono(label, Modifier.weight(1f), size = 9.sp, weight = FontWeight.SemiBold)
            Dot(fmt(local), size = 17.sp)
        }
        LfhSlider(local, min, max, step, onValue = { local = it }, onEnd = { if (local != value) onCommit(local) })
        if (sub != null) Mono(sub, size = 9.sp, spacing = 0.sp)
    }
}

/** Tasto quadrato −/+ per i passi fini. */
@Composable
fun StepKey(symbol: String, onClick: () -> Unit) {
    Key(Modifier.size(38.dp), hap = Hap.TICK, onClick = onClick) {
        Sans(symbol, size = 18.sp)
    }
}

/**
 * Riga di regolazione con −/+ e cursore, aggiornata dal vivo (per la banda:
 * lo spettro mostra subito la nuova posizione).
 */
@Composable
fun StepSliderRow(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    fmt: (Double) -> String,
    onValue: (Double) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mono(label, Modifier.weight(1f), size = 9.sp, weight = FontWeight.SemiBold)
            StepKey("−") { onValue(snap(value - step, min, max, step)) }
            Dot(fmt(value), Modifier.widthIn(min = 78.dp), size = 18.sp, align = TextAlign.Center)
            StepKey("+") { onValue(snap(value + step, min, max, step)) }
        }
        LfhSlider(value, min, max, step, onValue = onValue)
    }
}

/** Spia: arancio con alone se accesa. */
@Composable
fun Led(on: Boolean, size: Dp = 7.dp) {
    Canvas(Modifier.size(size)) {
        if (on) drawCircle(Lfh.Orange.copy(alpha = 0.30f), radius = this.size.minDimension)
        drawCircle(if (on) Lfh.Orange else Color.Black.copy(alpha = 0.16f))
    }
}

/** Riga di impostazione: titolo + sottotitolo a sinistra, controllo a destra. */
@Composable
fun SettingRow(
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    subColor: Color = Lfh.InkDim,
    divider: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.08f)))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Sans(title, size = 13.sp, weight = FontWeight.Medium)
                Mono(sub, size = 9.sp, color = subColor, spacing = 0.sp, lineHeight = 13.sp)
            }
            trailing()
        }
    }
}

/** Tasto d'azione compatto per le righe di impostazione. */
@Composable
fun RowCta(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Key(
        Modifier.height(40.dp),
        bg = if (accent) Lfh.Orange else Lfh.Ink,
        radius = 8.dp,
        onClick = onClick,
    ) {
        Mono(
            label, Modifier.padding(horizontal = 12.dp), size = 10.sp,
            color = if (accent) Lfh.Ink else Lfh.Paper, weight = FontWeight.SemiBold, spacing = 0.1.em, maxLines = 1,
        )
    }
}

/** Campo di testo su una riga, scuro (dentro i display) o chiaro (nei fogli). */
@Composable
fun FieldBox(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    height: Dp = 46.dp,
) {
    val shape = RoundedCornerShape(9.dp)
    val fg = if (dark) Lfh.Paper else Lfh.Ink
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = { onValue(it.take(2000)) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Geist, fontSize = 14.sp, color = fg),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Lfh.Orange),
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(if (dark) Color(0xFF1F1E1B) else Lfh.Field)
            .then(if (dark) Modifier else Modifier.border(1.dp, Color.Black.copy(alpha = 0.10f), shape)),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Sans(placeholder, size = 14.sp, color = if (dark) Lfh.PaperFaint else Lfh.InkFaint, maxLines = 1)
                }
                inner()
            }
        },
    )
}
