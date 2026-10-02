package io.github.adrianss31.lowfreqhunter.ui

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import io.github.adrianss31.lowfreqhunter.engine.NightEngine
import io.github.adrianss31.lowfreqhunter.engine.Palette
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Utilità di disegno condivise (colormap, etichette su Canvas, colonne waterfall). */
object Render {

    /** Colormap "inferno" del waterfall (stessi stop di report e dashboard). */
    fun wfColor(v: Float): Color = Color(Palette.wfColorInt(v))

    /** Colore di una cella di heatmap per un livello [overDb] rispetto alla soglia. */
    fun heatCell(overDb: Float?): Color = when {
        overDb == null || overDb.isNaN() -> Color(0xFF191816)
        overDb < -12f -> Color(0xFF1B1A26)
        else -> wfColor(0.3f + 0.7f * ((overDb + 12f) / 22f).coerceIn(0f, 1f))
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Etichetta in Geist Mono su Canvas; [align] come Paint.Align. */
    fun DrawScope.monoText(
        text: String,
        x: Float,
        y: Float,
        color: Color,
        sizePx: Float,
        align: Paint.Align = Paint.Align.LEFT,
        dot: Boolean = false,
    ) {
        paint.typeface = if (dot) Typefaces.dot else Typefaces.mono
        paint.textSize = sizePx
        paint.color = color.toArgb()
        paint.textAlign = align
        drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
    }

    fun DrawScope.textWidth(text: String, sizePx: Float): Float {
        paint.typeface = Typefaces.mono
        paint.textSize = sizePx
        return paint.measureText(text)
    }

    /** Colonna waterfall (dB per bin, 20–200 Hz su [NightEngine.WF_NBINS] bin) da uno spettro. */
    fun wfColumn(spec: FloatArray, binHz: Double): FloatArray {
        val out = FloatArray(NightEngine.WF_NBINS)
        val range = NightEngine.WF_FMAX - NightEngine.WF_FMIN
        for (b in 0 until NightEngine.WF_NBINS) {
            val fL = NightEngine.WF_FMIN + b / NightEngine.WF_NBINS.toDouble() * range
            val fH = NightEngine.WF_FMIN + (b + 1) / NightEngine.WF_NBINS.toDouble() * range
            val i0 = maxOf(0, (fL / binHz).roundToInt())
            val i1 = minOf(spec.size - 1, (fH / binHz).roundToInt())
            var p = 0.0
            var n = 0
            for (i in i0..i1) {
                p += 10.0.pow(spec[i] / 10.0)
                n++
            }
            out[b] = if (n > 0) (10.0 * log10(p / n + 1e-12)).toFloat() else -120f
        }
        return out
    }
}
