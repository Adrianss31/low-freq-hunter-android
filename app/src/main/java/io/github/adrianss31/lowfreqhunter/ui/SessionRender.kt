package io.github.adrianss31.lowfreqhunter.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import io.github.adrianss31.lowfreqhunter.data.SessionBundle
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.Palette

/**
 * Timeline del foglio sessione come bitmap (disegnata una volta fuori dal
 * main thread): curve dei livelli per banda su −80…−50 dBFS (massimo per
 * pixel, così i picchi brevi restano visibili), soglie tratteggiate,
 * interruzioni in arancio, corsie degli eventi sotto, note in bianco.
 */
object SessionRender {

    private const val BG = 0xFF10100E.toInt()
    private const val PAPER = 0xFFEFECE3.toInt()
    private const val LANE = 0xFF1D1C19.toInt()
    private const val ORANGE = 0xFFFF5A1F.toInt()

    private fun alpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)

    /** Altezza (px) della zona corsie sotto il grafico. */
    fun lanesH(b: SessionBundle, d: Float) = b.channels.size * 8 * d + 4 * d

    fun timeline(
        b: SessionBundle,
        wPx: Int,
        hPx: Int,
        d: Float,
        tLo: Long,
        tHi: Long,
        dbLo: Double = -80.0,
        dbHi: Double = -50.0,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(wPx.coerceAtLeast(1), hPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val w = wPx.toFloat()
        val h = hPx.toFloat()
        val ph = h - lanesH(b, d)
        val span = maxOf(1L, tHi - tLo).toFloat()
        fun x(t: Long) = (t - tLo) / span * w
        fun y(v: Double) = (ph - (v.coerceIn(dbLo, dbHi) - dbLo) / (dbHi - dbLo) * ph).toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // griglia e soglie
        p.color = alpha(PAPER, 0.05f)
        for (v in listOf(-70.0, -60.0)) c.drawRect(0f, y(v), w, y(v) + 1f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * d
        p.pathEffect = DashPathEffect(floatArrayOf(2 * d, 3 * d), 0f)
        for (thr in b.cfg.enabledBands().map { it.thr }.distinct()) {
            if (thr < dbLo || thr > dbHi) continue
            p.color = alpha(PAPER, 0.3f)
            c.drawLine(0f, y(thr), w, y(thr), p)
        }
        p.pathEffect = null
        p.style = Paint.Style.FILL

        // interruzioni
        p.color = alpha(ORANGE, 0.25f)
        for (g in b.gaps) {
            if (g.endT < tLo || g.startT > tHi) continue
            c.drawRect(x(g.startT), 0f, maxOf(x(g.startT) + 2f, x(g.endT)), ph, p)
        }

        // curve: massimo per pixel
        val n = wPx.coerceAtLeast(1)
        val samples = b.samples
        var i0 = samples.binarySearchBy(tLo) { it.t }.let { if (it < 0) -it - 1 else it }
        i0 = i0.coerceIn(0, maxOf(0, samples.size - 1))
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * d
        p.strokeJoin = Paint.Join.ROUND
        val chans = b.cfg.enabledBands().map { it.id } + if (b.cfg.vib.enabled) listOf(Channels.VIB) else emptyList()
        for (ch in chans) {
            val mx = FloatArray(n) { Float.NaN }
            var i = i0
            while (i < samples.size && samples[i].t <= tHi) {
                val s = samples[i]
                val v = if (ch == Channels.VIB) s.vibDb else b.levels[i][ch]
                if (v != null && v.isFinite()) {
                    val px = x(s.t).toInt().coerceIn(0, n - 1)
                    val f = v.toFloat()
                    if (mx[px].isNaN() || f > mx[px]) mx[px] = f
                }
                i++
            }
            val path = Path()
            var started = false
            var lastPx = -10
            for (px in 0 until n) {
                val v = mx[px]
                if (v.isNaN()) continue
                val yy = y(v.toDouble())
                // un buco di più di qualche pixel resta un buco (niente
                // linee dritte attraverso le interruzioni)
                if (!started || px - lastPx > 4) path.moveTo(px.toFloat(), yy) else path.lineTo(px.toFloat(), yy)
                started = true
                lastPx = px
            }
            p.color = Palette.bandColorInt(ch)
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL

        // corsie degli eventi
        b.channels.forEachIndexed { li, ch ->
            val yy = ph + 6 * d + li * 8 * d
            p.color = LANE
            c.drawRect(0f, yy, w, yy + 5 * d, p)
            p.color = Palette.bandColorInt(ch)
            for (e in b.events) {
                if (e.band != ch || e.endT < tLo || e.startT > tHi) continue
                c.drawRect(x(e.startT), yy, maxOf(x(e.startT) + 2f, x(e.endT)), yy + 5 * d, p)
            }
        }

        // note e marker
        p.color = 0xFFFFFFFF.toInt()
        for (m in b.markers) {
            if (m.t < tLo || m.t > tHi) continue
            c.drawRect(x(m.t), 0f, x(m.t) + 1.5f * d, ph, p)
        }
        return bmp
    }
}
