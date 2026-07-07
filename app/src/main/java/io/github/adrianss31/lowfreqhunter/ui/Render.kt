package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import io.github.adrianss31.lowfreqhunter.engine.BandCfg
import io.github.adrianss31.lowfreqhunter.engine.NightEngine
import kotlin.math.roundToInt

/** Rendering condiviso di spettro e waterfall (port di js/ui.js e js/live.js). */
object Render {

    // colormap tipo "inferno" — stessi stop della PWA
    private val stops = arrayOf(
        intArrayOf(5, 10, 30), intArrayOf(30, 20, 90), intArrayOf(120, 30, 130),
        intArrayOf(210, 60, 120), intArrayOf(255, 140, 50), intArrayOf(255, 220, 100),
        intArrayOf(255, 255, 235),
    )

    fun wfColor(v: Float): Color {
        val c = v.coerceIn(0f, 1f) * (stops.size - 1)
        val i = c.toInt().coerceAtMost(stops.size - 2)
        val f = c - i
        val a = stops[i]
        val b = stops[i + 1]
        return Color(
            (a[0] + (b[0] - a[0]) * f).roundToInt(),
            (a[1] + (b[1] - a[1]) * f).roundToInt(),
            (a[2] + (b[2] - a[2]) * f).roundToInt(),
        )
    }

    private val textPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(120, 255, 255, 255)
        textSize = 24f
        typeface = android.graphics.Typeface.MONOSPACE
        isAntiAlias = true
    }

    fun DrawScope.label(text: String, x: Float, y: Float, color: Color? = null) {
        val p = android.graphics.Paint(textPaint)
        if (color != null) {
            p.color = android.graphics.Color.argb(
                200,
                (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(),
            )
        }
        drawContext.canvas.nativeCanvas.drawText(text, x, y, p)
    }

    /** Spettro live: griglia, bande evidenziate con segmento-soglia, curva. */
    fun DrawScope.drawSpectrum(
        spec: FloatArray,
        binHz: Double,
        xMaxHz: Double,
        bands: List<BandCfg>,
        bandColor: (String) -> Color,
    ) {
        val w = size.width
        val h = size.height
        val dbMin = -120.0
        val dbMax = 0.0
        fun xOf(f: Double) = (f / xMaxHz * w).toFloat()
        fun yOf(db: Double) = (h - (db.coerceIn(dbMin, dbMax) - dbMin) / (dbMax - dbMin) * h).toFloat()

        // griglia dB
        var db = -100.0
        while (db <= 0.0) {
            drawLine(Lfh.Border.copy(alpha = 0.4f), Offset(0f, yOf(db)), Offset(w, yOf(db)))
            label("${db.toInt()}", 6f, yOf(db) - 6f)
            db += 20.0
        }
        // griglia Hz
        val fStep = if (xMaxHz <= 250) 50.0 else if (xMaxHz <= 500) 100.0 else 200.0
        var f = fStep
        while (f < xMaxHz) {
            drawLine(Lfh.Border.copy(alpha = 0.4f), Offset(xOf(f), 0f), Offset(xOf(f), h))
            label("${f.toInt()}", xOf(f) + 4f, h - 8f)
            f += fStep
        }
        // bande evidenziate + soglia
        for (b in bands) {
            val c = bandColor(b.id)
            val x0 = xOf(b.lo)
            val x1 = xOf(b.hi)
            if (x0 > w) continue
            drawRect(c.copy(alpha = 0.10f), Offset(x0, 0f), Size(maxOf(x1 - x0, 2f), h))
            drawLine(c, Offset(x0, yOf(b.thr)), Offset(minOf(x1, w), yOf(b.thr)), strokeWidth = 4f)
            label(b.id, x0 + 4f, 26f, c)
        }
        // curva spettro
        val maxBin = minOf((xMaxHz / binHz).toInt(), spec.size - 1)
        if (maxBin > 1) {
            val path = Path()
            path.moveTo(0f, yOf(spec[0].toDouble()))
            for (i in 1..maxBin) {
                path.lineTo(xOf(i * binHz), yOf(spec[i].toDouble()))
            }
            drawPath(path, Lfh.Accent, style = Stroke(width = 3f))
        }
    }

    /**
     * Waterfall da slice quantizzate (byte 0..255). Range dinamico stirato
     * sui dati come nella PWA. [guides]: (Hz, colore) linee orizzontali.
     */
    fun DrawScope.drawWaterfallSlices(
        slices: List<Pair<Long, ByteArray>>,
        guides: List<Pair<Double, Color>>,
    ) {
        val w = size.width
        val h = size.height
        drawRect(Color.Black, Offset.Zero, size)
        if (slices.isNotEmpty()) {
            var lo = 255
            var hi = 0
            for ((_, bins) in slices) {
                for (b in bins) {
                    val v = b.toInt() and 0xFF
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
            }
            if (hi - lo < 20) hi = lo + 20
            val nBins = slices[0].second.size
            val cols = maxOf(slices.size, 60)
            val colW = w / cols
            val rowH = h / nBins
            slices.forEachIndexed { xi, (_, bins) ->
                val x = xi * colW
                for (b in 0 until nBins) {
                    val v = ((bins[b].toInt() and 0xFF) - lo).toFloat() / (hi - lo)
                    drawRect(
                        wfColor(v),
                        Offset(x, h - (b + 1) * rowH),
                        Size(colW + 1f, rowH + 1f),
                    )
                }
            }
        }
        for ((hz, color) in guides) {
            val y = (h - (hz - NightEngine.WF_FMIN) / (NightEngine.WF_FMAX - NightEngine.WF_FMIN) * h).toFloat()
            if (y < 0 || y > h) continue
            drawLine(color.copy(alpha = 0.55f), Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
            label("${hz.toInt()}", 4f, y - 4f, color)
        }
    }

    /** Waterfall live scorrevole: colonne = livelli recenti (64 bin ciascuna). */
    fun DrawScope.drawWaterfallColumns(
        columns: List<FloatArray>, // dB per bin, più recente in coda
        maxColumns: Int,
        guides: List<Pair<Double, Color>>,
    ) {
        val w = size.width
        val h = size.height
        drawRect(Color.Black, Offset.Zero, size)
        if (columns.isNotEmpty()) {
            val nBins = columns[0].size
            val colW = w / maxColumns
            val rowH = h / nBins
            val startX = w - columns.size * colW
            columns.forEachIndexed { xi, col ->
                val x = startX + xi * colW
                for (b in 0 until nBins) {
                    val v = ((col[b] + 100f) / 70f)
                    drawRect(wfColor(v), Offset(x, h - (b + 1) * rowH), Size(colW + 1f, rowH + 1f))
                }
            }
        }
        for ((hz, color) in guides) {
            val y = (h - (hz - NightEngine.WF_FMIN) / (NightEngine.WF_FMAX - NightEngine.WF_FMIN) * h).toFloat()
            if (y < 0 || y > h) continue
            drawLine(color.copy(alpha = 0.55f), Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
        }
    }
}
