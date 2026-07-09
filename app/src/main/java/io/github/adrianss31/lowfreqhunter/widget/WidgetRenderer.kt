package io.github.adrianss31.lowfreqhunter.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import io.github.adrianss31.lowfreqhunter.R
import kotlin.math.min

/**
 * Disegna la faccia del widget come bitmap: pannello da strumento con tasto
 * REC, VU meter a segmenti per canale (tacca bianca = soglia, cornice =
 * evento confermato) e display dot-matrix con Hz dominante, cronometro ed
 * eventi. Il rendering al pixel dà l'estetica dell'app senza i limiti dei
 * widget Android standard.
 */
object WidgetRenderer {

    data class Ch(
        val id: String,
        val label: String,      // etichetta visibile: frequenza o "VIB"
        val color: Int,
        val levelFrac: Float,   // 0..1
        val thrFrac: Float,     // 0..1
        val active: Boolean,    // evento confermato in corso
    )

    data class Face(
        val running: Boolean,
        val listen: Boolean,
        val chronoS: Long,
        val events: Int,
        val domHz: Double?,
        val channels: List<Ch>,
    )

    private const val BG = 0xFF0A0A0C.toInt()
    private const val SURFACE = 0xFF121214.toInt()
    private const val SURFACE2 = 0xFF1A1A1E.toInt()
    private const val BORDER = 0xFF2A2A30.toInt()
    private const val DOTS = 0xFF17171C.toInt()
    private const val TEXT = 0xFFE8E8EC.toInt()
    private const val DIM = 0xFF8A8A96.toInt()
    private const val FAINT = 0xFF4A4A54.toInt()
    private const val REC = 0xFFFF3B30.toInt()
    private const val ACCENT = 0xFF00D4AA.toInt()
    private const val AMBER = 0xFFFFAA00.toInt()

    fun render(context: Context, wPx: Int, hPx: Int, face: Face): Bitmap {
        val w = wPx.coerceIn(120, 1400)
        val h = hPx.coerceIn(60, 800)
        val d = context.resources.displayMetrics.density
        val doto = runCatching {
            Typeface.create(ResourcesCompat.getFont(context, R.font.doto), Typeface.BOLD)
        }.getOrNull() ?: Typeface.MONOSPACE

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        fun text(size: Float, color: Int, tf: Typeface = Typeface.MONOSPACE, spacing: Float = 0.1f) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                typeface = tf
                letterSpacing = spacing
            }

        // pannello
        val panel = RectF(1f, 1f, w - 1f, h - 1f)
        fill.color = BG
        c.drawRoundRect(panel, 22 * d, 22 * d, fill)
        stroke.color = BORDER
        stroke.strokeWidth = 1.5f * d
        c.drawRoundRect(panel, 22 * d, 22 * d, stroke)

        // griglia a puntini
        fill.color = DOTS
        val step = 9 * d
        var gy = 14 * d
        while (gy < h - 14 * d) {
            var gx = 14 * d
            while (gx < w - 14 * d) {
                c.drawCircle(gx, gy, 1.1f * d, fill)
                gx += step
            }
            gy += step
        }

        // viti agli angoli
        val screwIn = 13 * d
        stroke.strokeWidth = 1f * d
        for ((sx, sy) in listOf(
            Pair(screwIn, screwIn), Pair(w - screwIn, screwIn),
            Pair(screwIn, h - screwIn), Pair(w - screwIn, h - screwIn),
        )) {
            fill.color = SURFACE
            c.drawCircle(sx, sy, 3.5f * d, fill)
            stroke.color = BORDER
            c.drawCircle(sx, sy, 3.5f * d, stroke)
            c.drawLine(sx - 2.2f * d, sy - 2.2f * d, sx + 2.2f * d, sy + 2.2f * d, stroke)
        }

        val compact = h < 100 * d

        // ── zona REC (sinistra) ──────────────────────────────────────────────
        val recZone = min(0.28f * w, 110 * d)
        val keyCx = recZone / 2 + 6 * d
        val keyCy = if (compact) h / 2f else h * 0.42f
        val keyR = min(min(30 * d, h * 0.26f), recZone * 0.34f)
        fill.color = SURFACE
        c.drawCircle(keyCx, keyCy, keyR, fill)
        stroke.color = if (face.running) REC else BORDER
        stroke.strokeWidth = 2.2f * d
        c.drawCircle(keyCx, keyCy, keyR, stroke)
        stroke.color = BORDER
        stroke.strokeWidth = 1f * d
        c.drawCircle(keyCx, keyCy, keyR * 0.78f, stroke)
        fill.color = REC
        if (face.running) {
            val s = keyR * 0.42f
            c.drawRect(keyCx - s, keyCy - s, keyCx + s, keyCy + s, fill)
        } else {
            c.drawCircle(keyCx, keyCy, keyR * 0.34f, fill)
        }
        if (!compact) {
            val lp = text(8.5f * d, FAINT, spacing = 0.25f)
            c.drawText("01 · LIVE", keyCx - lp.measureText("01 · LIVE") / 2, keyCy + keyR + 14 * d, lp)
            val stTxt = if (face.running) "IN ASCOLTO" else "PRONTO"
            val sp = text(9 * d, if (face.running) REC else DIM, spacing = 0.2f)
            c.drawText(stTxt, keyCx - sp.measureText(stTxt) / 2, keyCy + keyR + 27 * d, sp)
        }

        stroke.color = BORDER
        stroke.strokeWidth = 1f * d
        c.drawLine(recZone + 12 * d, 12 * d, recZone + 12 * d, h - 12 * d, stroke)

        // ── zona display (destra) ────────────────────────────────────────────
        val wantReadout = w >= 260 * d
        val readoutW = if (wantReadout) min(0.30f * w, 150 * d) else 0f
        val readoutX = w - readoutW - 10 * d
        if (wantReadout) {
            stroke.color = BORDER
            c.drawLine(readoutX - 8 * d, 12 * d, readoutX - 8 * d, h - 12 * d, stroke)

            val domStr = face.domHz?.let { "%.1f".format(it) } ?: "—"
            if (compact) {
                val hp = text(20 * d, ACCENT, doto, 0.05f)
                c.drawText(domStr, readoutX, h * 0.45f, hp)
                val lp = text(8 * d, FAINT, spacing = 0.25f)
                c.drawText("HZ DOM", readoutX, h * 0.45f + 12 * d, lp)
                val info = if (face.running) fmtChrono(face.chronoS) + " · EV " + face.events else "· · ·"
                val ip = text(9.5f * d, if (face.running) TEXT else FAINT)
                c.drawText(info, readoutX, h * 0.82f, ip)
            } else {
                // riquadro Hz
                fill.color = 0xFF0E0E10.toInt()
                val box = RectF(readoutX, 14 * d, w - 14 * d, 14 * d + h * 0.36f)
                c.drawRect(box, fill)
                stroke.color = BORDER
                c.drawRect(box, stroke)
                val hp = text(min(24 * d, box.height() * 0.62f), ACCENT, doto, 0.05f)
                c.drawText(domStr, box.left + 8 * d, box.bottom - box.height() * 0.38f, hp)
                val lp = text(8 * d, FAINT, spacing = 0.3f)
                c.drawText("HZ DOMINANTE", box.left + 8 * d, box.bottom - 6 * d, lp)

                val cp = text(17 * d, if (face.running) TEXT else FAINT, doto, 0.05f)
                c.drawText(if (face.running) fmtChrono(face.chronoS) else "--:--", readoutX + 8 * d, h * 0.66f, cp)
                val cl = text(8 * d, FAINT, spacing = 0.3f)
                c.drawText(if (face.listen) "SOLO ASCOLTO" else "REGISTRAZIONE", readoutX + 8 * d, h * 0.66f + 12 * d, cl)

                val ep = text(13 * d, if (face.events > 0) AMBER else FAINT, doto, 0.1f)
                c.drawText("EV ${face.events}", readoutX + 8 * d, h * 0.90f, ep)
            }
        }

        // ── VU meter a segmenti (centro) ─────────────────────────────────────
        val towersX0 = recZone + 24 * d
        val towersX1 = (if (wantReadout) readoutX - 16 * d else w - 16 * d)
        val n = face.channels.size
        if (n > 0 && towersX1 - towersX0 > 40 * d) {
            val slot = (towersX1 - towersX0) / n
            val tw = min(24 * d, slot * 0.55f)
            val labelZone = if (compact) 14 * d else 34 * d
            val top = 16 * d
            val bottom = h - 14 * d - labelZone
            val nSeg = if (compact) 8 else 12
            val segGap = 2.5f * d
            val segH = ((bottom - top) - segGap * (nSeg - 1)) / nSeg

            face.channels.forEachIndexed { i, ch ->
                val cx = towersX0 + slot * i + slot / 2
                val x0 = cx - tw / 2
                val lit = (ch.levelFrac * nSeg).toInt().coerceIn(0, nSeg)
                for (s in 0 until nSeg) {
                    val y1 = bottom - s * (segH + segGap)
                    fill.color = if (s < lit) ch.color else SURFACE2
                    c.drawRect(x0, y1 - segH, x0 + tw, y1, fill)
                }
                // tacca soglia
                val thrY = bottom - (ch.thrFrac * (bottom - top))
                fill.color = TEXT
                c.drawRect(x0 - 4 * d, thrY - 0.9f * d, x0 + tw + 4 * d, thrY + 0.9f * d, fill)
                // cornice evento confermato
                if (ch.active) {
                    stroke.color = ch.color
                    stroke.strokeWidth = 1.6f * d
                    c.drawRoundRect(
                        RectF(x0 - 5 * d, top - 6 * d, x0 + tw + 5 * d, bottom + 5 * d),
                        4 * d, 4 * d, stroke,
                    )
                }
                // etichetta (frequenza) + dB
                val above = ch.levelFrac >= ch.thrFrac
                val letterP = text(if (compact) 9 * d else 11 * d, if (above || ch.active) ch.color else DIM, doto, 0.05f)
                c.drawText(ch.label, cx - letterP.measureText(ch.label) / 2, bottom + (if (compact) 12 * d else 16 * d), letterP)
                if (!compact) {
                    val db = (-120 + 120 * ch.levelFrac).toInt()
                    val dbP = text(8.5f * d, if (above) ch.color else FAINT)
                    val dbS = "$db"
                    c.drawText(dbS, cx - dbP.measureText(dbS) / 2, bottom + 28 * d, dbP)
                }
            }
        }

        // serigrafia
        if (!compact && h >= 130 * d) {
            val sp = text(7.5f * d, 0xFF3A3A44.toInt(), spacing = 0.4f)
            val s = "LOW-FREQ HUNTER"
            c.drawText(s, (recZone + 24 * d), h - 8 * d, sp)
        }

        return bmp
    }

    private fun fmtChrono(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%02d:%02d".format(m, s % 60)
    }
}
