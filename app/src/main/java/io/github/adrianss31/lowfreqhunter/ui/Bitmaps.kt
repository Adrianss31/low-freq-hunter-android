package io.github.adrianss31.lowfreqhunter.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.adrianss31.lowfreqhunter.engine.Palette

/**
 * Spettrogrammi come bitmap di 1 pixel per cella (colonne × bin): disegnati
 * poi stirati con filtro "nearest", costano un drawImage invece di decine di
 * migliaia di rettangoli per frame.
 */
object WfBitmaps {

    private const val BG = 0xFF05060F.toInt()

    /**
     * Da slice quantizzate (0..255): range dinamico stirato sui dati, come
     * nella PWA. Oltre [maxCols] colonne si accorpano prendendo il massimo
     * (un picco breve non sparisce nella panoramica di una notte).
     */
    fun fromSlices(slices: List<ByteArray>, minCols: Int = 1, maxCols: Int = 1200): ImageBitmap? {
        if (slices.isEmpty()) return null
        val nBins = slices[0].size
        if (nBins == 0) return null
        var lo = 255
        var hi = 0
        for (s in slices) for (b in s) {
            val v = b.toInt() and 0xFF
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (hi - lo < 20) hi = lo + 20
        val cols = slices.size.coerceAtMost(maxCols)
        val outCols = maxOf(cols, minCols)
        val px = IntArray(outCols * nBins) { BG }
        val x0 = outCols - cols
        for (c in 0 until cols) {
            val a = c * slices.size / cols
            val z = maxOf(a + 1, (c + 1) * slices.size / cols)
            for (bin in 0 until nBins) {
                var m = 0
                for (i in a until z) {
                    val s = slices[i]
                    if (bin < s.size) m = maxOf(m, s[bin].toInt() and 0xFF)
                }
                val v = (m - lo).toFloat() / (hi - lo)
                px[(nBins - 1 - bin) * outCols + x0 + c] = Palette.wfColorInt(v)
            }
        }
        val bmp = Bitmap.createBitmap(outCols, nBins, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, outCols, 0, 0, outCols, nBins)
        return bmp.asImageBitmap()
    }

    /** Da colonne live in dB (più recente in coda), allineate a destra su [nCols]. */
    fun fromColumns(columns: List<FloatArray>, nCols: Int): ImageBitmap? {
        if (columns.isEmpty()) return null
        val nBins = columns[0].size
        val px = IntArray(nCols * nBins) { BG }
        val shown = columns.takeLast(nCols)
        val x0 = nCols - shown.size
        shown.forEachIndexed { c, col ->
            for (bin in 0 until minOf(nBins, col.size)) {
                px[(nBins - 1 - bin) * nCols + x0 + c] = Palette.wfColorInt((col[bin] + 100f) / 70f)
            }
        }
        val bmp = Bitmap.createBitmap(nCols, nBins, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, nCols, 0, 0, nCols, nBins)
        return bmp.asImageBitmap()
    }
}
