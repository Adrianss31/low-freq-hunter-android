package io.github.adrianss31.lowfreqhunter.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import io.github.adrianss31.lowfreqhunter.data.SessionBundle
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.NightEngine
import io.github.adrianss31.lowfreqhunter.engine.Palette

/**
 * Rende timeline e spettrogramma di una sessione come Bitmap statici.
 *
 * Motivo: con una notte intera (decine di migliaia di campioni, oltre mille
 * slice) ridisegnare su un Canvas Compose a ogni frame di scroll bloccava
 * la pagina. Qui si disegna UNA volta, fuori dal main thread, e la UI mostra
 * solo l'immagine — lo scroll diventa istantaneo. La risoluzione ridotta va
 * benissimo per una panoramica della notte.
 */
object BitmapRender {

    private const val BG = 0xFF0A0A0C.toInt()
    private const val BORDER = 0xFF2A2A30.toInt()
    private const val TEXT_DIM = 0xFF8A8A96.toInt()

    private fun paint(color: Int, size: Float, mono: Boolean = true) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
        }

    /**
     * Timeline: corsie evento per canale, curve dei livelli, soglie, gap,
     * marker. Range dB ristretto a [dbLo, dbHi] (default −80…−50: la zona
     * dove vivono soglie e segnali; il vecchio −110…−10 schiacciava tutto).
     * [only] limita curve e soglie a un solo canale (null = tutti).
     * [tLo]/[tHi] limitano la finestra temporale (zoom); null = tutta la sessione.
     */
    fun timeline(
        b: SessionBundle,
        wPx: Int,
        hPx: Int,
        d: Float,
        dbLo: Double = -80.0,
        dbHi: Double = -50.0,
        only: String? = null,
        tLo: Long? = null,
        tHi: Long? = null,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val w = wPx.toFloat()
        val h = hPx.toFloat()

        val channels = b.channels
        if (b.samples.size < 2) return bmp

        val laneH = 16f * d
        val plotY0 = laneH * channels.size + 6 * d
        val plotH = h - plotY0 - 22 * d
        val tMin = tLo ?: b.samples.first().t
        val tMax = tHi ?: b.samples.last().t
        val span = maxOf(1L, tMax - tMin).toFloat()
        // indici dei campioni visibili (lo stride va calcolato su questi)
        var i0 = b.samples.binarySearchBy(tMin) { it.t }.let { if (it < 0) -it - 1 else it }
        var i1 = b.samples.binarySearchBy(tMax) { it.t }.let { if (it < 0) -it - 2 else it }
        i0 = i0.coerceIn(0, b.samples.size - 1)
        i1 = i1.coerceIn(i0, b.samples.size - 1)
        val dbMin = dbLo
        val dbMax = dbHi
        fun xOf(t: Long) = (t - tMin) / span * w
        fun yOf(db: Double) = (plotY0 + plotH - (db.coerceIn(dbMin, dbMax) - dbMin) / (dbMax - dbMin) * plotH).toFloat()
        fun chColor(id: String) = Palette.bandColorInt(id)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // gap su tutta l'altezza
        fill.color = 0x14FFFFFF
        for (g in b.gaps) {
            c.drawRect(xOf(g.startT), 0f, maxOf(xOf(g.endT), xOf(g.startT) + 2f), h, fill)
        }

        // corsie evento, una per canale (etichetta = frequenza)
        val laneLabel = paint(TEXT_DIM, 9 * d)
        channels.forEachIndexed { i, ch ->
            val y = i * laneH + 2 * d
            fill.color = 0x0AFFFFFF
            c.drawRect(0f, y, w, y + laneH - 4 * d, fill)
            fill.color = chColor(ch)
            for (ev in b.events.filter { it.band == ch }) {
                c.drawRect(xOf(ev.startT), y, maxOf(xOf(ev.endT), xOf(ev.startT) + 3f), y + laneH - 4 * d, fill)
            }
            laneLabel.color = chColor(ch)
            c.drawText(b.cfg.channelLabel(ch), 4 * d, y + laneH - 6 * d, laneLabel)
        }

        // griglia dB
        val grid = Paint().apply { color = BORDER; strokeWidth = 1f }
        val gridTxt = paint(0x66FFFFFF, 8 * d)
        var db = dbLo
        while (db <= dbHi) {
            c.drawLine(0f, yOf(db), w, yOf(db), grid)
            c.drawText("${db.toInt()}", 4 * d, yOf(db) - 3 * d, gridTxt)
            db += 10.0
        }

        // soglie tratteggiate
        for (band in b.cfg.enabledBands()) {
            if (only != null && band.id != only) continue
            val p = Paint().apply {
                color = Palette.bandColorInt(band.id)
                strokeWidth = 1.5f * d
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
            }
            c.drawLine(0f, yOf(band.thr), w, yOf(band.thr), p)
        }

        // curve: ref grigia + bande + vibrazioni
        val stride = maxOf(1, (i1 - i0 + 1) / (wPx * 2))
        fun plot(color: Int, width: Float, getter: (Int) -> Double?) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = width
                style = Paint.Style.STROKE
            }
            val path = Path()
            var started = false
            var i = i0
            while (i <= i1) {
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
            c.drawPath(path, p)
        }
        if (only == null) plot(0x40FFFFFF, 1.5f * d) { b.samples[it].ref }
        for (band in b.cfg.enabledBands()) {
            if (only != null && band.id != only) continue
            plot(Palette.bandColorInt(band.id), 2f * d) { b.levels[it][band.id] }
        }
        if (b.cfg.vib.enabled && (only == null || only == Channels.VIB)) {
            plot(Palette.VIB, 2f * d) { b.samples[it].vibDb }
        }

        // marker
        for (m in b.markers) {
            val x = xOf(m.t)
            val path = Path()
            path.moveTo(x, 0f)
            path.lineTo(x - 6 * d, 12 * d)
            path.lineTo(x + 6 * d, 12 * d)
            path.close()
            fill.color = Color.WHITE
            c.drawPath(path, fill)
        }

        // asse tempi
        val axis = paint(0x66FFFFFF, 9 * d)
        for (i in 0..4) {
            val t = tMin + ((tMax - tMin) * i / 4.0).toLong()
            val lbl = fmtClock(t * 1000)
            val x = minOf(xOf(t) + 2 * d, w - axis.measureText(lbl) - 2 * d)
            c.drawText(lbl, x, h - 5 * d, axis)
        }
        return bmp
    }

    /**
     * Spettrogramma: slice quantizzate → colonne. Scala delle frequenze nel
     * gutter sinistro, FUORI dal grafico (le guide interne coprivano i dati).
     * [slices] permette di passare un sottoinsieme (zoom); [minCols] deve
     * combaciare con la geometria usata dallo scrubbing nella UI.
     */
    fun waterfall(
        b: SessionBundle,
        wPx: Int,
        hPx: Int,
        d: Float,
        slices: List<io.github.adrianss31.lowfreqhunter.data.SliceEntity> = b.slices,
        minCols: Int = 60,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val gutter = 30 * d
        val plotW = w - gutter
        val bgP = Paint().apply { color = Color.BLACK }
        c.drawRect(gutter, 0f, w, h, bgP)
        if (slices.isNotEmpty()) {
            var lo = 255
            var hi = 0
            for (sl in slices) for (v in sl.bins) {
                val q = v.toInt() and 0xFF
                if (q < lo) lo = q
                if (q > hi) hi = q
            }
            if (hi - lo < 20) hi = lo + 20
            val nBins = slices[0].bins.size
            val cols = maxOf(slices.size, minCols)
            val colW = plotW / cols
            val rowH = h / nBins
            val p = Paint()
            slices.forEachIndexed { xi, sl ->
                val x = gutter + xi * colW
                for (bin in 0 until nBins) {
                    val v = ((sl.bins[bin].toInt() and 0xFF) - lo).toFloat() / (hi - lo)
                    p.color = Palette.wfColorInt(v)
                    c.drawRect(x, h - (bin + 1) * rowH, x + colW + 1f, h - bin * rowH + 1f, p)
                }
            }
        }
        // scala nel gutter: etichetta + tick, niente linee nel plot
        val tick = Paint().apply { strokeWidth = 2f }
        for (band in b.cfg.enabledBands()) {
            val y = h - ((band.center - NightEngine.WF_FMIN) / (NightEngine.WF_FMAX - NightEngine.WF_FMIN) * h).toFloat()
            if (y < 0 || y > h) continue
            val col = Palette.bandColorInt(band.id)
            tick.color = col
            c.drawLine(gutter - 5 * d, y, gutter - 1 * d, y, tick)
            val lp = paint(col, 9 * d)
            c.drawText("${band.center.toInt()}", gutter - 7 * d - lp.measureText("${band.center.toInt()}"), y + 3 * d, lp)
        }
        return bmp
    }
}
