package io.github.adrianss31.lowfreqhunter.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import io.github.adrianss31.lowfreqhunter.MainActivity
import io.github.adrianss31.lowfreqhunter.R
import io.github.adrianss31.lowfreqhunter.engine.Palette
import kotlin.math.cos
import kotlin.math.sin

/**
 * Widget home 2x1 stile Teenage Engineering per il LIVE (non il log notturno).
 *  - SX: disco che ruota solo quando il Live è attivo (notch radiale per vedere
 *    la rotazione).
 *  - DX: LED binari per banda (acceso = sopra soglia, colore banda vero + alone),
 *    con il valore dBFS sotto.
 *  - Tap sul disco: avvia/ferma il Live (via MainActivity + ACTION_WIDGET_LIVE_TOGGLE).
 *  - Tema scuro/chiaro ereditato dal sistema (uiMode al momento del draw).
 *
 * Il disegno è una bitmap generata via Canvas (come Exporter), così il look è
 * identico a quello dell'app e compila senza dipendenze extra.
 */
class LiveWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) draw(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            for (id in mgr.getAppWidgetIds(ComponentName(context, LiveWidget::class.java))) {
                draw(context, mgr, id)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "io.github.adrianss31.lowfreqhunter.WIDGET_REFRESH"

        /** Ridisegna e pusha tutte le istanze del widget (chiamato dal controller). */
        fun push(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, LiveWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) {
                try {
                    draw(ctx, mgr, id)
                } catch (e: Exception) {
                    Log.w("LiveWidget", "push fallito per $id", e)
                }
            }
        }

        private fun isDark(ctx: Context): Boolean {
            val uiMode = ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        private fun draw(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val dark = isDark(ctx)
            val st = LiveWidgetController.state.value

            val W = 480
            val H = 240
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val bg = if (dark) Color.rgb(10, 10, 12) else Color.rgb(234, 234, 238)
            c.drawColor(bg)
            val border = if (dark) Color.rgb(42, 42, 48) else Color.rgb(200, 200, 208)
            val text = if (dark) Color.rgb(232, 232, 236) else Color.rgb(28, 28, 34)
            val textDim = if (dark) Color.rgb(138, 138, 150) else Color.rgb(120, 120, 130)
            val ring = if (dark) Color.rgb(232, 232, 236) else Color.rgb(40, 40, 48)
            val ringFaint = if (dark) Color.rgb(60, 60, 68) else Color.rgb(190, 190, 200)

            // bordo pannello
            val bp = Paint().apply { color = border; style = Paint.Style.STROKE; strokeWidth = 2f }
            c.drawRoundRect(RectF(2f, 2f, W - 2f, H - 2f), 22f, 22f, bp)

            // ── Disco a sinistra (centro ~ 92,120, r 76) ──
            val cx = 96f
            val cy = 122f
            val r = 74f
            val discBg = if (dark) Color.rgb(18, 18, 20) else Color.rgb(247, 247, 250)
            c.drawCircle(cx, cy, r, Paint().apply { color = discBg })
            // cerchi concentrici
            val rings = intArrayOf(74, 58, 42, 26)
            rings.forEachIndexed { i, rr ->
                val p = Paint().apply {
                    color = if (i == 0) ring else ringFaint
                    style = Paint.Style.STROKE
                    strokeWidth = if (i == 0) 3f else 1.5f
                }
                c.drawCircle(cx, cy, rr.toFloat(), p)
            }
            // notch radiale (ruota se attivo)
            val ang = if (st.running) {
                (System.currentTimeMillis() % 1200) / 1200f * 2 * Math.PI.toFloat()
            } else 0f
            val nx = cx + cos(ang) * (r - 6)
            val ny = cy + sin(ang) * (r - 6)
            val hub = Paint().apply { color = ring; isAntiAlias = true }
            c.drawCircle(cx, cy, 7f, hub)
            val notch = Paint().apply { color = if (st.running) Color.rgb(0, 212, 170) else ringFaint; strokeWidth = 4f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
            c.drawLine(cx, cy, nx, ny, notch)
            // label LIVE sopra il disco
            c.drawText("LIVE", cx - 22f, 36f, labelPaint(text))
            if (st.running) {
                // pallino REC che pulsa
                val pulse = (System.currentTimeMillis() % 1000) / 1000f
                val recA = (200 + 55 * sin(pulse * 2 * Math.PI)).toInt().coerceIn(120, 255)
                c.drawCircle(cx + 30f, 30f, 5f, Paint().apply { color = Color.argb(recA, 255, 59, 48) })
            }

            // ── LED bande a destra ──
            val ledR = 11f
            val colX0 = 210f
            val colGap = 66f
            val ledY = 92f
            val bands = st.bands.ifEmpty {
                // fallback: mostra le lettere anche se non ancora campionate
                listOf("A", "B", "C", "D").map {
                    LiveWidgetController.BandView(it, 0.0, 0.0, null, false)
                }
            }
            bands.take(4).forEachIndexed { i, b ->
                val lx = colX0 + i * colGap
                val colInt = Palette.bandColorInt(b.id)
                if (b.over) {
                    // alone
                    val halo = Paint().apply { color = Color.argb(70, (colInt shr 16) and 0xFF, (colInt shr 8) and 0xFF, colInt and 0xFF); isAntiAlias = true }
                    c.drawCircle(lx, ledY, ledR + 12f, halo)
                    c.drawCircle(lx, ledY, ledR, Paint().apply { color = colInt; isAntiAlias = true })
                } else {
                    val off = if (dark) Color.rgb(34, 34, 40) else Color.rgb(210, 210, 218)
                    c.drawCircle(lx, ledY, ledR, Paint().apply { color = off; isAntiAlias = true })
                    // bordo tinta banda
                    c.drawCircle(lx, ledY, ledR, Paint().apply { color = colInt; style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true })
                }
                // lettera banda
                c.drawText(b.id, lx - 5f, ledY + ledR + 18f, monoPaint(text, 16f))
                // valore dBFS
                val dv = b.db?.let { "%.0f".format(it) } ?: "—"
                c.drawText(dv, lx - 12f, ledY + ledR + 38f, monoPaint(textDim, 13f))
            }

            // sorgente in basso
            val src = if (st.running) "Sorgente ${st.source}" else "tap disco → avvia"
            c.drawText(src, 96f, H - 14f, monoPaint(textDim, 13f))

            // RemoteViews
            val rv = RemoteViews(ctx.packageName, R.layout.widget_live)
            rv.setImageViewBitmap(R.id.widget_image, bmp)
            // tap sul disco → toggle live
            val tap = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_WIDGET_LIVE_TOGGLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0,
            )
            rv.setOnClickPendingIntent(R.id.widget_image, tap)
            mgr.updateAppWidget(id, rv)
        }

        private fun labelPaint(color: Int) = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = 16f; isFakeBoldText = true
        }
        private fun monoPaint(color: Int, size: Float) = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = android.graphics.Typeface.MONOSPACE
        }
    }
}
