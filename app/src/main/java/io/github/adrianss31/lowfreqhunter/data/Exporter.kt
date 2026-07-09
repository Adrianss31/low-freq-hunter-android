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
                sb.append("$n,${ev.band},${bc?.center?.toInt() ?: ""},${bc?.width?.toInt() ?: ""},${thr ?: ""},")
                sb.append("${isoUtc(ev.startT)},${isoUtc(ev.endT)},${ev.durationS},")
                sb.append("${ev.peakDb?.let { "%.2f".format(Locale.US, it) } ?: ""},${ev.avgDb?.let { "%.2f".format(Locale.US, it) } ?: ""}\n")
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
            for (id in bandIds) sb.append(",${lv[id]?.let { "%.2f".format(Locale.US, it) } ?: ""}")
            sb.append(",${"%.2f".format(Locale.US, s.ref)},${s.domHz}")
            sb.append(",${s.vibDb?.let { "%.2f".format(Locale.US, it) } ?: ""},${s.battPct ?: ""}\n")
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
        val w = 1400
        val evRows = minOf(b.events.size + b.gaps.size, 28)
        val h = 560 + evRows * 22 + 60
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val title = paint(Color.rgb(17, 17, 17), 26f, bold = true)
        val body = paint(Color.rgb(51, 51, 51), 15f)
        val small = paint(Color.rgb(136, 136, 136), 13f)
        val mono = paint(Color.rgb(34, 34, 34), 14f, mono = true)

        c.drawText("Low-Freq Hunter — Report sessione", 30f, 44f, title)
        val s = b.session
        val endStr = s.endedAt?.let { "${fmtDate(it)} ${fmtClock(it)}" } ?: "—"
        val durStr = s.endedAt?.let { fmtDur((it - s.startedAt) / 1000) } ?: "—"
        val bandsStr = b.cfg.enabledBands().joinToString("   ·   ") {
            "${it.center.toInt()} Hz ±${it.width.toInt()} @ ${it.thr.toInt()} dBFS"
        } + if (b.cfg.vib.enabled) "   ·   Vibraz.: accel @ ${b.cfg.vib.thr.toInt()} dB(g)" else ""
        val lines = listOf(
            "Sessione: ${s.label}     Inizio: ${fmtDate(s.startedAt)} ${fmtClock(s.startedAt)}     Fine: $endStr     Durata: $durStr",
            "Canali: $bandsStr",
            "Trigger: ≥${b.cfg.minOnS} s sopra soglia per aprire, ≥${b.cfg.minOffS} s sotto per chiudere (isteresi ${b.cfg.hystDb.toInt()} dB)",
            "FFT ${b.cfg.fftSize} @ ${s.sampleRate} Hz (≈${"%.2f".format(s.binHz)} Hz/bin) · Sorgente ${s.audioSource} · dBFS non calibrati in dB SPL" +
                if (s.recovered) " · SESSIONE RECUPERATA (interruzione)" else "",
        )
        lines.forEachIndexed { i, l -> c.drawText(l, 30f, 76f + i * 22f, body) }

        // spettrogramma
        var y = 180f
        c.drawText("Spettrogramma 20–200 Hz (tutta la sessione)", 30f, y - 8f, paint(Color.rgb(17, 17, 17), 16f, bold = true))
        drawWaterfall(c, 30f, y, w - 60f, 150f, b)
        y += 185f

        // timeline
        c.drawText(
            "Livelli nel tempo (dBFS) — regioni colorate = eventi, grigie = gap, ▲ = marker",
            30f, y - 8f, paint(Color.rgb(17, 17, 17), 16f, bold = true),
        )
        drawTimeline(c, 30f, y, w - 60f, 140f, b)
        y += 175f

        // tabella eventi
        c.drawText(
            "Eventi (${b.events.size})" + if (b.markers.isNotEmpty()) " · Marker: ${b.markers.size}" else "",
            30f, y - 8f, paint(Color.rgb(17, 17, 17), 16f, bold = true),
        )
        c.drawText("#    canale  inizio      fine        durata      picco        media", 30f, y + 16f, small)
        val rows = (b.events + b.gaps).sortedBy { it.startT }.take(28)
        var n = 0
        rows.forEachIndexed { i, ev ->
            val ry = y + 40f + i * 22f
            if (ev.band == Channels.GAP) {
                c.drawText(
                    "—    GAP     ${fmtClock(ev.startT * 1000)}    ${fmtClock(ev.endT * 1000)}    ${fmtDur(ev.durationS).padEnd(10)}  monitoraggio interrotto",
                    30f, ry, paint(Color.rgb(153, 153, 153), 14f, mono = true),
                )
            } else {
                n++
                c.drawText(
                    "${n.toString().padEnd(4)} ${b.cfg.channelLabel(ev.band).padEnd(8)} ${fmtClock(ev.startT * 1000)}    ${fmtClock(ev.endT * 1000)}    ${
                        fmtDur(ev.durationS).padEnd(10)
                    }  ${(ev.peakDb?.let { "%.1f".format(it) } ?: "—").padEnd(11)} ${ev.avgDb?.let { "%.1f".format(it) } ?: "—"}",
                    30f, ry, mono,
                )
                val p = Paint().apply { color = Palette.bandColorInt(ev.band) }
                c.drawRect(47f, ry - 9f, 55f, ry - 1f, p)
            }
        }
        if (b.events.size + b.gaps.size > 28) {
            c.drawText("… e altri ${b.events.size + b.gaps.size - 28} (vedi CSV)", 30f, y + 40f + 28 * 22f, small)
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

    private fun drawWaterfall(c: Canvas, x0: Float, y0: Float, w: Float, h: Float, b: SessionBundle) {
        val bg = Paint().apply { color = Color.BLACK }
        c.drawRect(x0, y0, x0 + w, y0 + h, bg)
        if (b.slices.isNotEmpty()) {
            var lo = 255
            var hi = 0
            for (sl in b.slices) for (v in sl.bins) {
                val q = v.toInt() and 0xFF
                if (q < lo) lo = q
                if (q > hi) hi = q
            }
            if (hi - lo < 20) hi = lo + 20
            val nBins = b.slices[0].bins.size
            val cols = maxOf(b.slices.size, 60)
            val colW = w / cols
            val rowH = h / nBins
            val p = Paint()
            b.slices.forEachIndexed { xi, sl ->
                val x = x0 + xi * colW
                for (bin in 0 until nBins) {
                    val v = ((sl.bins[bin].toInt() and 0xFF) - lo).toFloat() / (hi - lo)
                    p.color = Palette.wfColorInt(v)
                    c.drawRect(x, y0 + h - (bin + 1) * rowH, x + colW + 1f, y0 + h - bin * rowH + 1f, p)
                }
            }
        }
        // guide bande
        for (band in b.cfg.enabledBands()) {
            val y = y0 + h - ((band.center - NightEngine.WF_FMIN) / (NightEngine.WF_FMAX - NightEngine.WF_FMIN) * h).toFloat()
            if (y < y0 || y > y0 + h) continue
            val p = Paint().apply {
                color = Palette.bandColorInt(band.id)
                strokeWidth = 2f
                pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
                style = Paint.Style.STROKE
            }
            c.drawLine(x0, y, x0 + w, y, p)
            c.drawText("${band.center.toInt()}Hz", x0 + 6f, y - 4f, paint(Palette.bandColorInt(band.id), 12f, mono = true))
        }
        val border = Paint().apply { color = Color.rgb(200, 200, 200); style = Paint.Style.STROKE }
        c.drawRect(x0, y0, x0 + w, y0 + h, border)
        // orari
        c.drawText(fmtClock(b.session.startedAt), x0 + 4f, y0 + h - 6f, paint(Color.WHITE, 12f, mono = true))
        val endLbl = fmtClock(b.session.endedAt ?: System.currentTimeMillis())
        val lp = paint(Color.WHITE, 12f, mono = true)
        c.drawText(endLbl, x0 + w - lp.measureText(endLbl) - 4f, y0 + h - 6f, lp)
    }

    private fun drawTimeline(c: Canvas, x0: Float, y0: Float, w: Float, h: Float, b: SessionBundle) {
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

        for (g in b.gaps) {
            val p = Paint().apply { color = Color.argb(28, 0, 0, 0) }
            c.drawRect(xOf(g.startT), y0, maxOf(xOf(g.endT), xOf(g.startT) + 2f), y0 + h, p)
        }
        for (ev in b.events) {
            val base = Palette.bandColorInt(ev.band)
            val p = Paint().apply { color = (base and 0x00FFFFFF) or (0x30 shl 24) }
            c.drawRect(xOf(ev.startT), y0, maxOf(xOf(ev.endT), xOf(ev.startT) + 2f), y0 + h, p)
        }

        val gridP = Paint().apply { color = Color.rgb(229, 229, 229); strokeWidth = 1f }
        var db = -100.0
        while (db <= -20.0) {
            c.drawLine(x0, yOf(db), x0 + w, yOf(db), gridP)
            c.drawText("${db.toInt()}", x0 + 4f, yOf(db) - 3f, paint(Color.rgb(150, 150, 150), 11f, mono = true))
            db += 20.0
        }

        // soglie tratteggiate
        for (band in b.cfg.enabledBands()) {
            val p = Paint().apply {
                color = Palette.bandColorInt(band.id)
                strokeWidth = 2f
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
            }
            c.drawLine(x0, yOf(band.thr), x0 + w, yOf(band.thr), p)
        }

        // curve: ref grigia + bande + V
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

        // marker
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

        // orari
        for (i in 0..6) {
            val t = tMin + ((tMax - tMin) * i / 6.0).toLong()
            val lbl = fmtClock(t * 1000)
            val lp = paint(Color.rgb(120, 120, 120), 11f, mono = true)
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
