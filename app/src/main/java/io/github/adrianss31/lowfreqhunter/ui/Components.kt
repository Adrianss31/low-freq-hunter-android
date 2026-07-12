package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adrianss31.lowfreqhunter.R

/** Font dot-matrix per i numeri grandi (Doto, OFL). */
val DotFont = FontFamily(Font(R.font.doto, weight = FontWeight.Bold))
val MonoFont = FontFamily.Monospace

/** Etichetta MAIUSCOLA piccola, tipo serigrafia su un pannello hardware. */
@Composable
fun CapsLabel(text: String, modifier: Modifier = Modifier, color: Color = Lfh.TextDim) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = color,
        fontSize = 10.sp,
        letterSpacing = 1.5.sp,
        fontFamily = MonoFont,
    )
}

/** Pannello piatto con bordo netto 1px. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .border(1.dp, Lfh.Border)
            .background(Lfh.Surface)
            .padding(12.dp),
        content = content,
    )
}

/** Valore numerico grande in dot-matrix. */
@Composable
fun DotValue(text: String, color: Color = Lfh.Accent, size: Int = 40) {
    Text(text, color = color, fontSize = size.sp, fontFamily = DotFont, fontWeight = FontWeight.Bold)
}

/**
 * Meter a segmenti (non barra continua): [frac] 0..1 riempito, [thrFrac]
 * disegna il segmento-soglia in bianco.
 */
@Composable
fun SegMeter(
    frac: Float,
    color: Color,
    modifier: Modifier = Modifier,
    thrFrac: Float? = null,
    segments: Int = 24,
) {
    // il riempimento insegue il target con una molla: i segmenti si accendono
    // in sequenza fluida invece di saltare al nuovo valore 4 volte al secondo
    val fill by animateFloatAsState(frac.coerceIn(0f, 1f), label = "segmeter")
    Row(modifier.height(14.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
        val thrIdx = thrFrac?.let { (it * segments).toInt().coerceIn(0, segments - 1) }
        for (i in 0 until segments) {
            val on = i < (fill * segments).toInt()
            val c = when {
                i == thrIdx -> Color.White
                on -> color
                else -> Lfh.Surface2
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(14.dp)
                    .background(c)
            )
        }
    }
}

/** Pulsante piatto da pannello, con tap aptico. */
@Composable
fun HwButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Lfh.Text,
    borderColor: Color = Lfh.Border,
    bg: Color = Lfh.Surface,
    enabled: Boolean = true,
    heavy: Boolean = false,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier
            .border(1.dp, if (enabled) borderColor else Lfh.Surface2)
            .background(bg)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (heavy) Haptics.heavy(view) else Haptics.tap(view)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = if (enabled) color else Lfh.TextFaint,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            fontFamily = MonoFont,
            textAlign = TextAlign.Center,
        )
    }
}

/** Tasto REC rotondo, fisico. */
@Composable
fun RecButton(recording: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .border(2.dp, if (recording) Lfh.Rec else Lfh.Border, CircleShape)
            .background(Lfh.Surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                Haptics.heavy(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Box(Modifier.size(26.dp).background(Lfh.Rec))
        } else {
            Box(Modifier.size(30.dp).clip(CircleShape).background(Lfh.Rec))
        }
    }
}

/** Stepper −/+ a scatti con tick aptico. */
@Composable
fun Stepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    unit: String = "",
) {
    val view = LocalView.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .border(1.dp, Lfh.Border)
                .background(Lfh.Surface2)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { Haptics.tick(view); onMinus() },
            contentAlignment = Alignment.Center,
        ) { Text("−", color = Lfh.Text, fontSize = 18.sp, fontFamily = MonoFont) }
        Box(
            Modifier.width(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (unit.isEmpty()) value else "$value $unit",
                color = Lfh.Text,
                fontSize = 14.sp,
                fontFamily = MonoFont,
            )
        }
        Box(
            Modifier
                .size(36.dp)
                .border(1.dp, Lfh.Border)
                .background(Lfh.Surface2)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { Haptics.tick(view); onPlus() },
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Lfh.Text, fontSize = 18.sp, fontFamily = MonoFont) }
    }
}

/** Interruttore stile hardware. */
@Composable
fun HwSwitch(on: Boolean, onToggle: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier
            .width(46.dp)
            .height(24.dp)
            .border(1.dp, if (on) Lfh.Accent else Lfh.Border)
            .background(if (on) Lfh.Accent.copy(alpha = 0.15f) else Lfh.Surface2)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { Haptics.tap(view); onToggle() },
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .size(18.dp)
                .background(if (on) Lfh.Accent else Lfh.TextFaint)
        )
    }
}

/** Riga della barra tab in basso. */
@Composable
fun TabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Lfh.Border)
            .background(Lfh.Surface),
    ) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { Haptics.tap(view); onSelect(i) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (sel) Lfh.Accent else Lfh.Surface2)
                    )
                    Text(
                        t.uppercase(),
                        color = if (sel) Lfh.Text else Lfh.TextDim,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                        fontFamily = MonoFont,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
    }
}
