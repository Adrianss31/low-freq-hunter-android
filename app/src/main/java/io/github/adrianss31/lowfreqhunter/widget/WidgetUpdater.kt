package io.github.adrianss31.lowfreqhunter.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.adrianss31.lowfreqhunter.MainActivity
import io.github.adrianss31.lowfreqhunter.R
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.Palette
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Costruisce lo stato della faccia dal bus e aggiorna tutte le istanze del widget. */
object WidgetUpdater {

    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, LfhWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val face = buildFace(context)
        val d = context.resources.displayMetrics.density
        for (id in ids) {
            val opts = mgr.getAppWidgetOptions(id)
            val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
            val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 } ?: 140
            val bmp = WidgetRenderer.render(context, (wDp * d).toInt(), (hDp * d).toInt(), face)

            val rv = RemoteViews(context.packageName, R.layout.widget_lfh)
            rv.setImageViewBitmap(R.id.widget_face, bmp)
            rv.setOnClickPendingIntent(
                R.id.widget_zone_rec,
                PendingIntent.getActivity(
                    context, 200,
                    Intent(context, WidgetToggleActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            rv.setOnClickPendingIntent(
                R.id.widget_zone_open,
                PendingIntent.getActivity(
                    context, 201,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            mgr.updateAppWidget(id, rv)
        }
    }

    private fun buildFace(context: Context): WidgetRenderer.Face {
        val settings = runBlocking { SettingsRepo.get(context).flow.first() }
        val st = MonitorBus.state.value
        val channels = ArrayList<WidgetRenderer.Ch>()
        for (b in settings.engine.enabledBands()) {
            val lv = if (st.running) st.levels[b.id] ?: -120.0 else -120.0
            channels.add(
                WidgetRenderer.Ch(
                    id = b.id,
                    color = Palette.bandColorInt(b.id),
                    levelFrac = (((lv + 120.0) / 120.0).coerceIn(0.0, 1.0)).toFloat(),
                    thrFrac = (((b.thr + 120.0) / 120.0).coerceIn(0.0, 1.0)).toFloat(),
                    active = st.running && st.activeBands.containsKey(b.id),
                ),
            )
        }
        if (settings.engine.vib.enabled) {
            val lv = if (st.running) st.vibDb ?: -120.0 else -120.0
            channels.add(
                WidgetRenderer.Ch(
                    id = Channels.VIB,
                    color = Palette.VIB,
                    levelFrac = (((lv + 120.0) / 120.0).coerceIn(0.0, 1.0)).toFloat(),
                    thrFrac = (((settings.engine.vib.thr + 120.0) / 120.0).coerceIn(0.0, 1.0)).toFloat(),
                    active = st.running && st.activeBands.containsKey(Channels.VIB),
                ),
            )
        }
        return WidgetRenderer.Face(
            running = st.running,
            listen = st.mode == "listen",
            chronoS = if (st.running) (System.currentTimeMillis() - st.startedAt) / 1000 else 0,
            events = if (st.running) st.eventsCount else 0,
            domHz = if (st.running) st.domHz else null,
            channels = channels,
        )
    }
}
