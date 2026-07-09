package io.github.adrianss31.lowfreqhunter.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.engine.NightEngine
import io.github.adrianss31.lowfreqhunter.engine.Palette
import io.github.adrianss31.lowfreqhunter.ui.fmtClock
import io.github.adrianss31.lowfreqhunter.ui.fmtDate
import io.github.adrianss31.lowfreqhunter.ui.fmtDur
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Tutti i dati di una sessione, pronti per il rendering e gli export. */
class SessionBundle(
    val session: SessionEntity,
    val cfg: EngineCfg,
    val samples: List<SampleEntity>,
    val events: List<EventEntity>,
    val gaps: List<EventEntity>,
    val markers: List<MarkerEntity>,
    val slices: List<SliceEntity>,
    val clips: List<ClipEntity>,
) {
    val levels: List<Map<String, Double>> by lazy {
        samples.map { runCatching { jsonLenient.decodeFromString<Map<String, Double>>(it.lvJson) }.getOrDefault(emptyMap()) }
    }

    /** Canali con corsia in timeline: bande attive nella sessione + V se presente. */
    val channels: List<String> by lazy {
        cfg.enabledBands().map { it.id } + if (cfg.vib.enabled) listOf(Channels.VIB) else emptyList()
    }

    companion object {
        val jsonLenient = Json { ignoreUnknownKeys = true }

        suspend fun load(dao: LfhDao, id: String): SessionBundle? {
            val session = dao.session(id) ?: return null
            val cfg = runCatching { jsonLenient.decodeFromString<EngineCfg>(session.cfgJson) }
                .getOrDefault(EngineCfg())
            val allEvents = dao.events(id)
            return SessionBundle(
                session = session,
                cfg = cfg,
                samples = dao.samples(id),
                events = allEvents.filter { it.band != Channels.GAP },
                gaps = allEvents.filter { it.band == Channels.GAP },
                markers = dao.markers(id),
                slices = dao.slices(id),
                clips = dao.clips(id),
            )
        }
    }
}

object Exporter {

    private fun isoUtc(epochS: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(epochS * 1000))
    }

    fun baseName(b: SessionBundle): String =
        b.session.label.replace(Regex("\\W+"), "_")

    // ── CSV ─────────────────────────────────────────────────────────────────
    fun eventsCsv(b: SessionBundle): String {
        val sb = StringBuilder("index,band,center_hz,width_hz,threshold_dbfs,start_iso,end_iso,duration_s,peak_db,avg_db\n")
        var n = 0
        for (ev in (b.events + b.gaps).sortedBy { it.startT }) {
            if (ev.band == Channels.GAP) {
                sb.append(",GAP,,,,${isoUtc(ev.startT)},${isoUtc(ev.endT)},${ev.durationS},,\n")
            } else {
                n++
                val bc = b.cfg.band(ev.band)
                val thr = if (ev.band == Channels.VIB) b.cfg.vib.thr else bc?.thr
                sb.append("$n,${ev.band},${bc?.center?.toInt() ?: \"\"},${bc?.width?.toInt() ?: \"\"},${thr ?: \"\"},")
                sb.append("${isoUtc(ev.startT)},${isoUtc(ev.endT)},${ev.durationS},")
                sb.append("${ev.peakDb?.let { \"%.2f\".format(Locale.US, it) } ?: \"\"},${ev.avgDb?.let { \"%.2f\".format(Locale.US, it) } ?: \"\"}\n")
            }
        }
        return sb.toString()
    }

    /** CSV aggregato per banda: 1 riga per banda con statistiche aggregate */
    fun bandsSummaryCsv(b: SessionBundle): String {
        val sb = StringBuilder("band,center_hz,width_hz,threshold_dbfs,activations,total_duration_s,peak_db,avg_db,first_activation,last_activation\n")
        for (bandCfg in b.cfg.enabledBands()) {
            val bandEvents = b.events.filter { it.band == bandCfg.id }
            if (bandEvents.isEmpty()) continue
            val totalDuration = bandEvents.sumOf { it.durationS }
            val activationCount = bandEvents.size
            val peakDb = bandEvents.mapNotNull { it.peakDb }.maxOrNull()
            val avgDb = bandEvents.mapNotNull { it.avgDb }.average().let { if (it.isNaN()) null else it }
            val first = bandEvents.minByOrNull { it.startT }
            val last = bandEvents.maxByOrNull { it.startT }
            sb.append("${bandCfg.id},${bandCfg.center.toInt()},${bandCfg.width.toInt()},${bandCfg.thr},")
            sb.append("$activationCount,$totalDuration,")
            sb.append("${peakDb?.let { \"%.2f\".format(Locale.US, it) } ?: \"\"},")
            sb.append("${avgDb?.let { \"%.2f\".format(Locale.US, it) } ?: \"\"},")
            sb.append("${first?.let { isoUtc(it.startT) } ?: \"\"},")
            sb.append("${last?.let { isoUtc(it.endT) } ?: \"\"}\n")
        }
        // Canale V se abilitato
        if (b.cfg.vib.enabled) {
            val vibEvents = b.events.filter { it.band == Channels.VIB }
            if (vibEvents.isNotEmpty()) {
                val totalDuration = vibEvents.sumOf { it.durationS }
                val activationCount = vibEvents.size
                val peakDb = vibEvents.mapNotNull { it.peakDb }.maxOrNull()
                val avgDb = vibEvents.mapNotNull { it.avgDb }.average().let { if (it.isNaN()) null else it }
                val first = vibEvents.minByOrNull { it.startT }
                val last = vibEvents.maxByOrNull { it.startT }
                sb.append("V,0,0,${b.cfg.vib.thr},")
                sb.append("$activationCount,$totalDuration,")
                sb.append("${peakDb?.let { \"%.2f\".format(Locale.US, it) } ?: \"\"},")
                sb.append("${avgDb?.let { \"%.2f\".format(Locale.US, it) } ?: \"\"},")
                sb.append("${first?.let { isoUtc(it.startT) } ?: \"\"},")
                sb.append("${last?.let { isoUtc(it.endT) } ?: \"\"}\n")
            }
        }
        return sb.toString()
    }

    fun samplesCsv(b: SessionBundle): String {
        val bandIds = b.cfg.bands.map { it.id }
        val sb = StringBuilder("t_iso,t_epoch_s")
        for (id in bandIds) sb.append(",band_${id}_dbfs")
        sb.append(",broadband_20_500_dbfs,dominant_hz,vib_db_rel_g,battery_pct\n")
        b.samples.forEachIndexed { i, s ->
            sb.append("${isoUtc(s.t)},${s.t}")
            val lv = b.levels[i]
            for (id in bandIds) sb.append(",${lv[id]?.let { \"%.2f\".format(Locale.US, it) } ?: \"\"}")
            sb.append(",${"%.2f".format(Locale.US, s.ref)},${s.domHz}")
            sb.append(",${s.vibDb?.let { \"%.2f\".format(Locale.US, it) } ?: \"\"},${s.battPct ?: \"\"}\n")
        }
        return sb.toString()
    }

    // ── JSON ────────────────────────────────────────────────────────────────
    fun json(b: SessionBundle): String {
        fun eventJson(e: EventEntity): JsonObject = buildJsonObject {
            put("band", e.band)
            put("start_iso", isoUtc(e.startT))
            put("end_iso", isoUtc(e.endT))
            put("duration_s", e.durationS)
            e.peakDb?.let { put("peak_db", it) }
            e.avgDb?.let { put("avg_db", it) }
        }
        val root = buildJsonObject {
            put("app", "low-freq-hunter-android")
            put("format", 1)
            put("exported_at", isoUtc(System.currentTimeMillis() / 1000))
            put("note", "Livelli audio in dBFS (non calibrati in dB SPL); canale V in dB rel 1 g.")
            put("session", buildJsonObject {
                put("label", b.session.label)
                put("started_at", isoUtc(b.session.startedAt / 1000))
                b.session.endedAt?.let { put("ended_at", isoUtc(it / 1000)) }
                put("sample_rate", b.session.sampleRate)
                put("bin_hz", b.session.binHz)
                put("audio_source", b.session.audioSource)
                put("recovered", b.session.recovered)
                put("cfg", SessionBundle.jsonLenient.parseToJsonElement(b.session.cfgJson))
            })
            put("events", buildJsonArray { for (e in b.events) add(eventJson(e)) })
            put("gaps", buildJsonArray { for (e in b.gaps) add(eventJson(e)) })
            put("markers", buildJsonArray {
                for (m in b.markers) add(buildJsonObject { put("t_iso", isoUtc(m.t)); put("origin", m.origin) })
            })
            put("samples", buildJsonArray {
                b.samples.forEachIndexed { i, s ->
                    add(buildJsonObject {
                        put("t", s.t)
                        for ((k, v) in b.levels[i]) put(k, v)
                        put("ref", s.ref)
                        put("dom_hz", s.domHz)
                        s.vibDb?.let { put("v", it) }
                        s.battPct?.let { put("batt", it) }
                    })
                }
            })
            put("clips_count", b.clips.size)
        }
        return root.toString()
    }

    // ── Report PNG ──────────────────────────────────────────────────────────
    fun reportPng(b: SessionBundle): ByteArray {
        // Riduci dimensione bitmap per performance: max 1200px larghezza, downsample spectrogram
        val w = 1200
        val maxEventRows = 40
        val evRows = minOf(b.events.size + b.gaps.size, maxEventRows)
        val h = 500 + evRows * 20 + 60
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val title = paint(Color.rgb(17, 17, 17), 24f, bold = true)
        val body = paint(Color.rgb(51, 51, 51), 14f)
        val small = paint(Color.rgb(136, 136, 136), 12f)
        val mono = paint(Color.rgb(34, 34, 34), 13f, mono = true)

        c.drawText("Low-Freq Hunter — Report sessione", 30f, 40f, title)
        val s = b.session
        val endStr = s.endedAt?.let { "${fmtDate(it)} ${fmtClock(it)}" } ?: "—"
        val durStr = s.endedAt?.let { fmtDur((it - s.startedAt) / 1000) } ?: "—"
        val bandsStr = b.cfg.enabledBands().joinToString("   ·   ") {
            "${it.id}: ${it.center.toInt()}±${it.width.toInt()} Hz @ ${it.thr.toInt()} dBFS"
        } + if (b.cfg.vib.enabled) "   ·   V: accel @ ${b.cfg.vib.thr.toInt()} dB(g)" else ""
        val lines = listOf(
            "Sessione: ${s.label}     Inizio: ${fmtDate(s.startedAt)} ${fmtClock(s.startedAt)}     Fine: $endStr     Durata: $durStr",
            "Canali: $bandsStr",
            "Trigger: ≥${b.cfg.minOnS} s sopra soglia per aprire, ≥${b.cfg.minOffS} s sotto per chiudere (isteresi ${b.cfg.hystDb.toInt()} dB)",
            "FFT ${b.cfg.fftSize} @ ${s.sampleRate} Hz (≈${"%.2f".format(s.binHz)} Hz/bin) · Sorgente ${s.audioSource} · dBFS non calibrati in dB SPL" +
                if (s.recovered) " · SESSIONE RECUPERATA (interruzione)" else "",
        )
        lines.forEachIndexed { i, l -> c.drawText(l, 30f, 70f + i * 20f, body) }

        // spettrogramma - downsampled per performance
        var y = 150f
        c.drawText("Spettrogramma 20–200 Hz (tutta la sessione, downsampled)", 30f, y - 8f, paint(Color.rgb(17, 17, 17), 14f, bold = true))
        drawWaterfallOptimized(c, 30f, y, w - 60f, 120f, b)
        y += 155f

        // timeline
        c.drawText(
            "Livelli nel tempo (dBFS) — regioni colorate = eventi, grigie = gap, ▲ = marker",
            30f, y - 8f, paint(Color.rgb(17, 17, 17), 14f, bold = true),
        )
        drawTimelineOptimized(c, 30f, y, w - 60f, 120f, b)
        y += 150f

        // tabella eventi per-banda (summary)
        c.drawText(
            "Bande (${b.cfg.enabledBands().size})" + if (b.markers.isNotEmpty()) " · Marker: ${b.markers.size}" else "",
            30f, y - 8f, paint(Color.rgb(17, 17, 17), 14f, bold = true),
        )
        c.drawText("banda    freq     attivazioni  durata_tot  picco    prima_attivazione    ultima_attivazione", 30f, y + 14f, small)
        var row = 0
        for (bandCfg in b.cfg.enabledBands()) {
            val bandEvents = b.events.filter { it.band == bandCfg.id }
            if (bandEvents.isEmpty()) continue
            val totalDuration = bandEvents.sumOf { it.durationS }
            val activationCount = bandEvents.size
            val peakDb = bandEvents.mapNotNull { it.peakDb }.maxOrNull()
            val first = bandEvents.minByOrNull { it.startT }
            val last = bandEvents.maxByOrNull { it.startT }
            val ry = y + 34f + row * 20f
            c.drawText(
                "${bandCfg.id.padEnd(6)} ${bandCfg.freqShort.padEnd(10)} ${activationCount.toString().padEnd(12)} ${fmtDur(totalDuration).padEnd(10)}  ${peakDb?.let { "%.1f".format(it) }.padEnd(7) ?: \"—.padEnd(7)\"}  ${first?.let { fmtClock(it.startT * 1000) } ?: \"—\"}    ${last?.let { fmtClock(it.endT * 1000) } ?: \"—\"}",
                30f, ry, mono,
            )
            // quadratino colore
            val p = Paint().apply { color = Palette.bandColorInt(bandCfg.id) }
            c.drawRect(40f, ry - 10f, 50f, ry - 1f, p)
            row++
            if (row >= maxEventRows) break
        }
        // Canale V
        if (b.cfg.vib.enabled) {
            val vibEvents = b.events.filter { it.band == Channels.VIB }
            if (vibEvents.isNotEmpty()) {
                val totalDuration = vibEvents.sumOf { it.durationS }
                val activationCount = vibEvents.size
                val peakDb = vibEvents.mapNotNull { it.peakDb }.maxOrNull()
                val first = vibEvents.minByOrNull { it.startT }
                val last = vibEvents.maxByOrNull { it.startT }
                val ry = y + 34f + row * 20f
                c.drawText(
                    "V      vibra    ${activationCount.toString().padEnd(12)} ${fmtDur(totalDuration).padEnd(10)}  ${peakDb?.let { "%.1f".format(it) }.padEnd(7) ?: \"—.padEnd(7)\"}  ${first?.let { fmtClock(it.startT * 1000) } ?: \"—\"}    ${last?.let { fmtClock(it.endT * 1000) } ?: \"—\"}",
                    30f, ry, mono,
                )
                val p = Paint().apply { color = Palette.VIB }
                c.drawRect(40f, ry - 10f, 50f, ry - 1f, p)
                row++
            }
        }

        // Gap
        if (b.gaps.isNotEmpty()) {
            c.drawText("Gap monitoraggio (${b.gaps.size})", 30f, y + 34f + row * 20f + 8f, paint(Color.rgb(17, 17, 17), 14f, bold = true))
            var gapRow = 0
            for (gap in b.gaps.sortedBy { it.startT }.take(maxEventRows - row)) {
                val ry = y + 34f + (row + gapRow + 1) * 20f + 8f
                c.drawText(
                    "—    GAP     ${fmtClock(gap.startT * 1000)}    ${fmtClock(gap.endT * 1000)}    ${fmtDur(gap.durationS).padEnd(10)}  monitoraggio interrotto",
                    30f, ry, paint(Color.rgb(153, 153, 153), 13f, mono = true),
                )
                gapRow++
            }
        }

        c.drawText(
            "Generato il ${fmtDate(System.currentTimeMillis())} ${fmtClock(System.currentTimeMillis())} — misura indicativa, non fonometria certificata",
            30f, h - 20f, small,
        )

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun paint(color: Int, size: Float, bold: Boolean = false, mono: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
        }

    /** Spettrogramma ottimizzato: downsample colonne per performance */
    private fun drawWaterfallOptimized(c: Canvas, x0: Float, y0: Float, w: Float, h: Float, b: SessionBundle) {
        val bg = Paint().apply { color = Color.BLACK }
        c.drawRect(x0, y0, x0 + w, y0 + h, bg)
        if (b.slices.isEmpty()) return

        // Calcola range dinamico
        var lo = 255
        var hi = 0
        for (sl in b.slices) for (v in sl.bins) {
            val q = v.toInt() and 0xFF
            if (q < lo) lo = q
            if (q > hi) hi = q
        }
        if (hi - lo < 20) hi = lo + 20

        val nBins = b.slices[0].bins.size
        // Downsample: max 400 colonne per performance
        val maxCols = 400
        val cols = minOf(b.slices.size, maxCols)
        val colW = w / cols
        val rowH = h / nBins

        // Pre-calcola indici slice da usare (uniformemente distribuiti)
        val step = if (b.slices.size > cols) b.slices.size.toDouble() / cols else 1.0
        val p = Paint()
        
        for (colIdx in 0 until cols) {
            val sliceIdx = (colIdx * step).toInt().coerceAtMost(b.slices.size - 1)
            val sl = b.slices[sliceIdx]
            val x = x0 + colIdx * colW
            for (bin in 0 until nBins) {
                val v = ((sl.bins[bin].toInt() and 0xFF) - lo).toFloat() / (hi - lo)
                p.color = Palette.wfColorInt(v)
                c.drawRect(x, y0 + h - (bin + 1) * rowH, x + colW + 1f, y0 + h - bin * rowH + 1f, p)
            }
        }

        // guide bande
        for (band in b.cfg.enabledBands()) {
            val yy = y0 + h - ((band.center - NightEngine.WF_FMIN) / (NightEngine.WF_FMAX - NightEngine.WF_FMIN) * h).toFloat()
            if (yy < y0 || yy > y0 + h) continue
            val gp = Paint().apply {
                color = Palette.bandColorInt(band.id)
                strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
                style = Paint.Style.STROKE
            }
            c.drawLine(x0, yy, x0 + w, yy, gp)
            c.drawText("${band.center.toInt()}Hz", x0 + 6f, yy - 4f, paint(Palette.bandColorInt(band.id), 11f, mono = true))
        }
        val border = Paint().apply { color = Color.rgb(200, 200, 200); style = Paint.Style.STROKE }
        c.drawRect(x0, y0, x0 + w, y0 + h, border)
        // orari
        c.drawText(fmtClock(b.session.startedAt), x0 + 4f, y0 + h - 6f, paint(Color.WHITE, 11f, mono = true))
        val endLbl = fmtClock(b.session.endedAt ?: System.currentTimeMillis())
        val lp = paint(Color.WHITE, 11f, mono = true)
        c.drawText(endLbl, x0 + w - lp.measureText(endLbl) - 4f, y0 + h - 6f, lp)
    }

    /** Timeline ottimizzata: stride campioni, meno path operations */
    private fun drawTimelineOptimized(c: Canvas, x0: Float, y0: Float, w: Float, h: Float, b: SessionBundle) {
        val bg = Paint().apply { color = Color.rgb(250, 250, 250) }
        c.drawRect(x0, y0, x0 + w, y0 + h, bg)
        val border = Paint().apply { color = Color.rgb(200, 200, 200); style = Paint.Style.STROKE }
        c.drawRect(x0, y0, x0 + w, y0 + h, border)
        if (b.samples.size < 2) return

        val tMin = b.samples.first().t
        val tMax = b.samples.last().t
        val span = maxOf(1L, tMax - tMin).toFloat()
        val dbMin = -110.0
        val dbMax = -10.0
        fun xOf(t: Long) = x0 + (t - tMin) / span * w
        fun yOf(db: Double) = (y0 + h - (db.coerceIn(dbMin, dbMax) - dbMin) / (dbMax - dbMin) * h).toFloat()

        // Gap
        for (g in b.gaps) {
            val p = Paint().apply { color = Color.argb(28, 0, 0, 0) }
            c.drawRect(xOf(g.startT), y0, maxOf(xOf(g.endT), xOf(g.startT) + 2f), y0 + h, p)
        }
        // Eventi come rettangoli sottili per banda
        for (band in b.cfg.enabledBands()) {
            val bandEvents = b.events.filter { it.band == band.id }
            val base = Palette.bandColorInt(band.id)
            val p = Paint().apply { color = (base and 0x00FFFFFF) or (0x30 shl 24) }
            for (ev in bandEvents) {
                c.drawRect(xOf(ev.startT), y0, maxOf(xOf(ev.endT), xOf(ev.startT) + 2f), y0 + h, p)
            }
        }

        // Griglia dB
        val gridP = Paint().apply { color = Color.rgb(229, 229, 229); strokeWidth = 1f }
        var db = -100.0
        while (db <= -20.0) {
            c.drawLine(x0, yOf(db), x0 + w, yOf(db), gridP)
            c.drawText("${db.toInt()}", x0 + 4f, yOf(db) - 3f, paint(Color.rgb(150, 150, 150), 10f, mono = true))
            db += 20.0
        }

        // Soglie tratteggiate
        for (band in b.cfg.enabledBands()) {
            val p = Paint().apply {
                color = Palette.bandColorInt(band.id)
                strokeWidth = 1.5f
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
            }
            c.drawLine(x0, yOf(band.thr), x0 + w, yOf(band.thr), p)
        }

        // Curve: downsample pesante per performance
        val stride = maxOf(1, b.samples.size / (w.toInt() * 2))
        fun plot(color: Int, width: Float, getter: (Int) -> Double?) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = width
                style = Paint.Style.STROKE
            }
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
            c.drawPath(path, p)
        }
        plot(Color.rgb(187, 187, 187), 1.5f) { b.samples[it].ref }
        for (band in b.cfg.enabledBands()) {
            plot(Palette.bandColorInt(band.id), 2f) { b.levels[it][band.id] }
        }
        if (b.cfg.vib.enabled) {
            plot(Color.rgb(90, 90, 100), 2f) { b.samples[it].vibDb }
        }

        // Marker
        for (m in b.markers) {
            val x = xOf(m.t)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(212, 0, 0) }
            val path = Path()
            path.moveTo(x, y0)
            path.lineTo(x - 6f, y0 + 12f)
            path.lineTo(x + 6f, y0 + 12f)
            path.close()
            c.drawPath(path, p)
        }

        // Orari
        for (i in 0..4) {
            val t = tMin + ((tMax - tMin) * i / 4.0).toLong()
            val lbl = fmtClock(t * 1000)
            val lp = paint(Color.rgb(120, 120, 120), 10f, mono = true)
            val x = minOf(xOf(t) + 2f, x0 + w - lp.measureText(lbl) - 2f)
            c.drawText(lbl, x, y0 + h - 5f, lp)
        }
    }

    // ── Salvataggio e condivisione ──────────────────────────────────────────

    /** Salva in Documents/LowFreqHunter via MediaStore. Ritorna l'uri o null. */
    fun saveToDocuments(context: Context, filename: String, mime: String, bytes: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/LowFreqHunter")
        }
        val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        return uri
    }

    /** Condivisione via share sheet (file temporaneo in cache + FileProvider). */
    fun share(context: Context, filename: String, mime: String, bytes: ByteArray) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, filename)
        file.writeBytes(bytes)
        shareFile(context, file, mime)
    }

    fun shareFile(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}