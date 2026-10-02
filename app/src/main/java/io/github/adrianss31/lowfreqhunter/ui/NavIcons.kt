package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private val EaseDraw = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val EaseSpin = CubicBezierEasing(0.5f, 0f, 0.2f, 1f)
private val EaseSlide = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)

/** Frazione 0..1 di una sotto-animazione che parte a [startMs] e dura [durMs]. */
private fun win(tMs: Float, startMs: Float, durMs: Float): Float =
    ((tMs - startMs) / durMs).coerceIn(0f, 1f)

/** Curva d'onda dell'icona Monitor (dal path SVG del prototipo, S espansi). */
private val wavePath = Path().apply {
    moveTo(2f, 14f)
    cubicTo(5f, 14f, 6f, 13f, 7.2f, 10f)
    cubicTo(8.4f, 7f, 9f, 2.5f, 10.6f, 2.5f)
    cubicTo(12.2f, 2.5f, 12.4f, 11f, 13.8f, 12f)
    cubicTo(15.2f, 13f, 16f, 8f, 17.2f, 8f)
    cubicTo(18.4f, 8f, 19f, 13f, 20f, 14f)
}

/**
 * Icone della capsula di navigazione, disegnate in un viewBox 22×18 e
 * animate quando la scheda diventa attiva (onda che si disegna, lancette
 * che riavvolgono, goccia che cade sulla pianta, cursori che scorrono).
 */
@Composable
fun NavIcon(tab: Tab, on: Boolean) {
    val t = remember { Animatable(2000f) }
    LaunchedEffect(on) {
        if (on) {
            t.snapTo(0f)
            t.animateTo(2000f, tween(2000, easing = LinearEasing))
        }
    }
    val ink by animateColorAsState(if (on) Lfh.Ink else Lfh.Paper.copy(alpha = 0.72f), label = "icInk")
    val acc by animateColorAsState(if (on) Lfh.Orange else Lfh.Paper.copy(alpha = 0.72f), label = "icAcc")
    val fill = if (on) Lfh.Key else Lfh.Ink
    Canvas(
        Modifier
            .size(22.dp, 18.dp)
            .graphicsLayer {
                if (on) {
                    translationY = -1.dp.toPx()
                    scaleX = 1.08f
                    scaleY = 1.08f
                }
            },
    ) {
        val s = size.width / 22f
        val ms = t.value
        scale(s, s, pivot = Offset.Zero) {
            val st = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (tab) {
                Tab.MONITOR -> monitorIcon(ms, on, ink, acc, st)
                Tab.ARCHIVE -> archiveIcon(ms, on, ink, acc, st)
                Tab.MAP -> mapIcon(ms, on, ink, acc, st)
                Tab.SETUP -> setupIcon(ms, on, ink, acc, fill, st)
            }
        }
    }
}

private fun DrawScope.monitorIcon(ms: Float, on: Boolean, ink: Color, acc: Color, st: Stroke) {
    drawLine(ink.copy(alpha = ink.alpha * 0.45f), Offset(2f, 15.5f), Offset(20f, 15.5f), 1.6f, StrokeCap.Round)
    val draw = if (on) EaseDraw.transform(win(ms, 0f, 700f)) else 1f
    if (draw >= 1f) {
        drawPath(wavePath, ink, style = st)
    } else if (draw > 0f) {
        val pm = PathMeasure()
        pm.setPath(wavePath, false)
        val seg = Path()
        pm.getSegment(0f, pm.length * draw, seg, true)
        drawPath(seg, ink, style = st)
    }
    // il picco "salta" fuori dopo che l'onda è disegnata
    val p = if (on) win(ms, 450f, 550f) else 1f
    if (p <= 0f) return
    val (dy, sc) = when {
        p < 0.55f -> {
            val k = p / 0.55f
            Pair(8f + (-2f - 8f) * k, 0.3f + (1.25f - 0.3f) * k)
        }
        else -> {
            val k = (p - 0.55f) / 0.45f
            Pair(-2f + 2f * k, 1.25f - 0.25f * k)
        }
    }
    drawCircle(acc, 1.8f * sc, Offset(10.6f, 2.5f + dy))
}

private fun DrawScope.archiveIcon(ms: Float, on: Boolean, ink: Color, acc: Color, st: Stroke) {
    val c = Offset(11f, 9.5f)
    val e = if (on) EaseSpin.transform(win(ms, 0f, 800f)) else 1f
    rotate(-360f * e, pivot = c) {
        drawArc(ink, 207.3f, 332.7f, false, Offset(4f, 2.5f), Size(14f, 14f), style = st)
        val head = Path().apply {
            moveTo(2.6f, 4.4f)
            lineTo(4.6f, 6.4f)
            lineTo(6.8f, 4.8f)
        }
        drawPath(head, ink, style = st)
    }
    rotate(-720f * e, pivot = c) {
        drawLine(ink, c, Offset(11f, 5.8f), 1.6f, StrokeCap.Round)
    }
    drawLine(ink, c, Offset(13.6f, 11f), 1.6f, StrokeCap.Round)
    drawCircle(acc, 1.4f, c)
}

private fun DrawScope.mapIcon(ms: Float, on: Boolean, ink: Color, acc: Color, st: Stroke) {
    val plan = Path().apply {
        moveTo(2f, 2.5f); lineTo(20f, 2.5f); lineTo(20f, 16f); lineTo(2f, 16f); close()
        moveTo(11f, 2.5f); lineTo(11f, 7f)
        moveTo(11f, 10f); lineTo(11f, 16f)
        moveTo(2f, 10f); lineTo(7f, 10f)
    }
    drawPath(plan, ink, style = st)
    val c = Offset(15.5f, 11.5f)
    if (on) {
        // due onde d'urto dopo l'impatto della goccia
        for (k in 0 until 2) {
            val q = win(ms, 350f + k * 1100f, 1100f)
            if (q in 0.001f..0.999f) {
                drawCircle(acc.copy(alpha = acc.alpha * 0.9f * (1f - q)), 2.4f * (0.4f + 2f * q), c, style = Stroke(1.2f))
            }
        }
    }
    val d = if (on) win(ms, 0f, 600f) else 1f
    val (dy, a) = when {
        d < 0.55f -> Pair(-9f + 10f * (d / 0.55f), d / 0.55f)
        d < 0.75f -> Pair(1f - 2.5f * ((d - 0.55f) / 0.2f), 1f)
        else -> Pair(-1.5f + 1.5f * ((d - 0.75f) / 0.25f), 1f)
    }
    translate(0f, dy) { drawCircle(acc.copy(alpha = acc.alpha * a), 2.4f, c) }
}

private fun DrawScope.setupIcon(ms: Float, on: Boolean, ink: Color, acc: Color, fill: Color, st: Stroke) {
    drawLine(ink, Offset(2f, 4.5f), Offset(20f, 4.5f), 1.6f, StrokeCap.Round)
    drawLine(ink, Offset(2f, 13.5f), Offset(20f, 13.5f), 1.6f, StrokeCap.Round)
    val k = if (on) EaseSlide.transform(win(ms, 0f, 1000f)) else 1f
    val dx = 8f * sin(PI * k).toFloat()
    withTransform({ translate(dx, 0f) }) {
        drawCircle(fill, 2.6f, Offset(7f, 4.5f))
        drawCircle(ink, 2.6f, Offset(7f, 4.5f), style = Stroke(1.6f))
    }
    withTransform({ translate(-dx, 0f) }) {
        drawCircle(acc, 3.4f, Offset(15f, 13.5f))
    }
}
