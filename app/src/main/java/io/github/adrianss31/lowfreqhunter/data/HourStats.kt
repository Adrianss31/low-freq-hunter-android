package io.github.adrianss31.lowfreqhunter.data

import android.content.Context
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.TimeZone

/**
 * Aggregato orario di una sessione per il calendario dell'Archivio: per ogni
 * ora locale, il MASSIMO di (livello − soglia) per canale, anche sotto
 * soglia. Le soglie sono quelle della cfg salvata con la sessione.
 *
 * Calcolarlo vuol dire leggere un campione al secondo (≈43 000 per una notte):
 * per questo finisce in una piccola cache su file, aggiornata in modo
 * incrementale coi soli campioni successivi a [Grid.upToT].
 */
object HourStats {

    class Grid(
        val upToT: Long,
        /** inizio dell'ora locale (epoch s) → canale → max(livello − soglia) dB */
        val hours: Map<Long, Map<String, Float>>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(ctx: Context) = File(ctx.filesDir, "hourstats").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.txt")

    fun delete(ctx: Context, id: String) {
        runCatching { file(ctx, id).delete() }
    }

    /** Inizio (epoch s) dell'ora locale che contiene [t]. */
    fun hourStart(t: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val off = tz.getOffset(t * 1000) / 1000
        return t - (t + off).mod(3600L)
    }

    suspend fun load(ctx: Context, dao: LfhDao, s: SessionEntity): Grid = withContext(Dispatchers.IO) {
        val f = file(ctx, s.id)
        val cached = runCatching { read(f) }.getOrNull()
        if (cached != null && s.lastT <= cached.upToT) return@withContext cached

        val cfg = runCatching { json.decodeFromString<EngineCfg>(s.cfgJson) }.getOrDefault(EngineCfg())
        val thr = HashMap<String, Double>()
        cfg.bands.forEach { thr[it.id] = it.thr }
        thr[Channels.VIB] = cfg.vib.thr

        val acc = HashMap<Long, HashMap<String, Float>>()
        cached?.hours?.forEach { (h, m) -> acc[h] = HashMap(m) }
        val samples = if (cached == null) dao.samples(s.id) else dao.samplesSince(s.id, cached.upToT)
        var last = cached?.upToT ?: 0L
        val tz = TimeZone.getDefault()
        for (smp in samples) {
            val m = acc.getOrPut(hourStart(smp.t, tz)) { HashMap() }
            parseLevels(smp.lvJson) { ch, db ->
                val t = thr[ch] ?: return@parseLevels
                val over = (db - t).toFloat()
                val prev = m[ch]
                if (prev == null || over > prev) m[ch] = over
            }
            smp.vibDb?.let { v ->
                val over = (v - (thr[Channels.VIB] ?: -55.0)).toFloat()
                val prev = m[Channels.VIB]
                if (prev == null || over > prev) m[Channels.VIB] = over
            }
            if (smp.t > last) last = smp.t
        }
        val grid = Grid(maxOf(last, cached?.upToT ?: 0L), acc)
        runCatching { write(f, grid) }
        grid
    }

    /**
     * Parser minimale per lvJson ({"A":-62.3,"B":-70.1}): con decine di
     * migliaia di righe la deserializzazione generica pesa troppo.
     */
    inline fun parseLevels(s: String, f: (String, Double) -> Unit) {
        val n = s.length
        var i = 0
        while (i < n) {
            val q = s.indexOf('"', i)
            if (q < 0) break
            val q2 = s.indexOf('"', q + 1)
            if (q2 < 0) break
            val key = s.substring(q + 1, q2)
            var j = s.indexOf(':', q2)
            if (j < 0) break
            j++
            var k = j
            while (k < n && s[k] != ',' && s[k] != '}') k++
            s.substring(j, k).trim().toDoubleOrNull()?.let { if (it.isFinite()) f(key, it) }
            i = k + 1
        }
    }

    private fun read(f: File): Grid? {
        if (!f.exists()) return null
        val lines = f.readLines()
        val head = lines.firstOrNull()?.split(' ') ?: return null
        if (head.size != 2 || head[0] != "v1") return null
        val upTo = head[1].toLongOrNull() ?: return null
        val hours = HashMap<Long, Map<String, Float>>()
        for (l in lines.drop(1)) {
            if (l.isBlank()) continue
            val parts = l.split(' ')
            val h = parts[0].toLongOrNull() ?: continue
            val m = HashMap<String, Float>()
            for (p in parts.drop(1)) {
                val eq = p.indexOf('=')
                if (eq <= 0) continue
                p.substring(eq + 1).toFloatOrNull()?.let { m[p.substring(0, eq)] = it }
            }
            hours[h] = m
        }
        return Grid(upTo, hours)
    }

    private fun write(f: File, g: Grid) {
        val sb = StringBuilder("v1 ${g.upToT}\n")
        for ((h, m) in g.hours.toSortedMap()) {
            sb.append(h)
            for ((ch, v) in m) sb.append(' ').append(ch).append('=').append("%.1f".format(java.util.Locale.US, v))
            sb.append('\n')
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
