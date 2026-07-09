package io.github.adrianss31.lowfreqhunter.ui

import android.graphics.Bitmap
import io.github.adrianss31.lowfreqhunter.engine.Idw
import io.github.adrianss31.lowfreqhunter.engine.Palette

/**
 * Heatmap della casa: griglia IDW → bitmap con la colormap inferno dell'app.
 * Il range colore è stirato sul min/max dei punti (con un minimo di 6 dB di
 * span per non colorare a caso il rumore di fondo).
 */
object HeatmapRender {

    data class Result(val bitmap: Bitmap, val minDb: Double, val maxDb: Double)

    private const val GRID_W = 72
    private const val GRID_H = 54

    /** [alpha] 0..255 dell'overlay. Ritorna null senza punti validi. */
    fun render(points: List<Idw.Point>, outW: Int, outH: Int, alpha: Int = 185): Result? {
        if (points.isEmpty() || outW <= 0 || outH <= 0) return null
        val grid = Idw.grid(points, GRID_W, GRID_H)
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (p in points) {
            if (p.value < lo) lo = p.value
            if (p.value > hi) hi = p.value
        }
        if (hi - lo < 6.0) {
            val mid = (hi + lo) / 2
            lo = mid - 3.0
            hi = mid + 3.0
        }
        val small = Bitmap.createBitmap(GRID_W, GRID_H, Bitmap.Config.ARGB_8888)
        val px = IntArray(GRID_W * GRID_H)
        for (i in px.indices) {
            val v = grid[i]
            px[i] = if (v.isNaN()) {
                0
            } else {
                val norm = ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()
                (Palette.wfColorInt(norm) and 0x00FFFFFF) or (alpha shl 24)
            }
        }
        small.setPixels(px, 0, GRID_W, 0, 0, GRID_W, GRID_H)
        val out = Bitmap.createScaledBitmap(small, outW, outH, true)
        small.recycle()
        return Result(out, lo, hi)
    }
}
