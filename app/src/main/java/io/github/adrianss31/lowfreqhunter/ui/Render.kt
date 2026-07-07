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

    /**
     * Timeline sessione (tema scuro): corsie evento per canale in alto,
     * curve dei livelli sotto, soglie tratteggiate, gap grigi, marker bianchi.
     */
    fun DrawScope.drawTimelineDark(b: io.github.adrianss31.lowfreqhunter.data.SessionBundle) {
        val w = size.width
        val h = size.height
        drawRect(Lfh.Bg, Offset.Zero, size)
        if (b.samples.size < 2) return

        val channels = b.channels
        val laneH = 22f
        val plotY0 = laneH * channels.size + 8f
        val plotH = h - plotY0 - 30f
        val tMin = b.samples.first().t
        val tMax = b.samples.last().t
        val span = maxOf(1L, tMax - tMin).toFloat()
        val dbMin = -110.0
        val dbMax = -10.0
        fun xOf(t: Long) = (t - tMin) / span * w
        fun yOf(db: Double) = (plotY0 + plotH - (db.coerceIn(dbMin, dbMax) - dbMin) / (dbMax - dbMin) * plotH).toFloat()
        fun chColor(ch: String) = if (ch == io.github.adrianss31.lowfreqhunter.engine.Channels.VIB) Lfh.VibColor else Lfh.bandColor(ch)

        // gap su tutta l'altezza
        for (g in b.gaps) {
            drawRect(
                Color.White.copy(alpha = 0.08f),
                Offset(xOf(g.startT), 0f),
                Size(maxOf(xOf(g.endT) - xOf(g.startT), 2f), h),
            )
        }
        // corsie evento
        channels.forEachIndexed { i, ch ->
            val y = i * laneH + 2f
            drawRect(Color.White.copy(alpha = 0.04f), Offset(0f, y), Size(w, laneH - 4f))
            label(ch, 4f, y + laneH - 8f, chColor(ch))
            for (ev in b.events.filter { it.band == ch }) {
                drawRect(
                    chColor(ch),
                    Offset(xOf(ev.startT), y),
                    Size(maxOf(xOf(ev.endT) - xOf(ev.startT), 3f), laneH - 4f),
                )
            }
        }
        // griglia dB
        var db = -100.0
        while (db <= -20.0) {
            drawLine(Lfh.Border.copy(alpha = 0.4f), Offset(0f, yOf(db)), Offset(w, yOf(db)))
            label("${db.toInt()}", 4f, yOf(db) - 4f)
            db += 20.0
        }
        // soglie tratteggiate
        for (band in b.cfg.enabledBands()) {
            val y = yOf(band.thr)
            var x = 0f
            while (x < w) {
                drawLine(Lfh.bandColor(band.id).copy(alpha = 0.5f), Offset(x, y), Offset(minOf(x + 8f, w), y))
                x += 16f
            }
        }
        // curve
        val stride = maxOf(1, b.samples.size / (w.toInt() * 2).coerceAtLeast(1))
        fun plot(color: Color, width: Float, getter: (Int) -> Double?) {
            val path = Path()
            var started = false
            var i = 0
            while (i < b.samples.size) {
                val v = getter(i)
                if (v == null || !v.isFinite()) {
                    started = false
                } else {
                    val px = xOf(b.samples[i].t)
                    val py = yOf(v)
                    if (started) path.lineTo(px, py) else path.moveTo(px, py)
                    started = true
                }
                i += stride
            }
            drawPath(path, color, style = Stroke(width = width))
        }
        plot(Color.White.copy(alpha = 0.25f), 2f) { b.samples[it].ref }
        for (band in b.cfg.enabledBands()) {
            plot(Lfh.bandColor(band.id), 2.5f) { b.levels[it][band.id] }
        }
        if (b.cfg.vib.enabled) {
            plot(Lfh.VibColor, 2.5f) { b.samples[it].vibDb }
        }
        // marker
        for (m in b.markers) {
            val x = xOf(m.t)
            val path = Path()
            path.moveTo(x, 0f)
            path.lineTo(x - 7f, 14f)
            path.lineTo(x + 7f, 14f)
            path.close()
            drawPath(path, Color.White)
        }
        // orari
        for (i in 0..4) {
            val t = tMin + ((tMax - tMin) * i / 4.0).toLong()
            label(fmtClock(t * 1000), minOf(xOf(t) + 2f, w - 100f), h - 6f)
        }
    /**
     * Waterfall live scorrevole: colonne = livelli recenti (64 bin ciascuna).
     * Le linee-frequenza (guides) NON sono disegnate dentro la waterfall (coprirebbero
     * i dati): stanno in un asse a sinistra, fuori dall'area colorata.
     */
    fun DrawScope.drawWaterfallColumns(
        columns: List<FloatArray>, // dB per bin, più recente in coda
        maxColumns: Int,
        guides: List<Pair<Double, Color>>,
        axisW: Float = 46f, // larghezza asse frequenze a sinistra (fuori dal grafico)
    ) {
        val w = size.width
        val h = size.height
        val plotW = (w - axisW).coerceAtLeast(1f)
        // area grafico (a destra dell'asse)
        drawRect(Color.Black, Offset(axisW, 0f), Size(plotW, h))
        if (columns.isNotEmpty()) {
            val nBins = columns[0].size
            val colW = plotW / maxColumns
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
        // asse frequenze esterno: linee + etichette nella striscia a sinistra
        for ((hz, color) in guides) {
            val y = (h - (hz - NightEngine.WF_FMIN) / (NightEngine.WF_FMAX - NightEngine.WF_FMIN) * h).toFloat()
            if (y < 0 || y > h) continue
            // linea orizzontale nell'asse (fuori dal grafico colorato)
            drawLine(color.copy(alpha = 0.85f), Offset(0f, y), Offset(axisW, y), strokeWidth = 1.5f)
            // breve tacca che entra nel grafico per ancorare visivamente la frequenza
            drawLine(color.copy(alpha = 0.35f), Offset(axisW, y), Offset(axisW + 6f, y), strokeWidth = 1.5f)
            // etichetta Hz (allineata a destra nell'asse)
            val t = "${hz.toInt()}"
            label(t, axisW - 6f - t.length * 13f, y - 4f, color)
        }
    }
}
