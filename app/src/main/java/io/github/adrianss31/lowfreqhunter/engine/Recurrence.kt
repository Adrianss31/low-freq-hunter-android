package io.github.adrianss31.lowfreqhunter.engine

import java.util.TimeZone

/**
 * Aggregazione "ricorrenza" tra notti: per ogni sessione, quanti secondi di
 * ogni fascia oraria del giorno sono stati monitorati e quanti sopra soglia.
 * Lo stesso disturbo alla stessa ora, notte dopo notte, è l'evidenza più
 * convincente di qualunque singola sessione — è questo che la heatmap mostra.
 *
 * Kotlin puro (niente Android): testabile su JVM.
 */
object Recurrence {

    /** Fasce da 30 minuti sull'arco del giorno. */
    const val BUCKETS = 48
    const val BUCKET_S = 86400 / BUCKETS

    class Night(
        val label: String,
        val coverS: FloatArray,                    // secondi monitorati per fascia
        val activeByBand: Map<String, FloatArray>, // secondi sopra soglia per fascia, per canale
        // secondi × intensità (picco sopra soglia, 0..1) per fascia, per canale
        val heatByBand: Map<String, FloatArray> = emptyMap(),
    ) {
        /** Attività della sola banda [band], o di tutte le bande sommate se null. */
        fun active(band: String? = null): FloatArray {
            if (band != null) return activeByBand[band] ?: FloatArray(BUCKETS)
            val sum = FloatArray(BUCKETS)
            for (a in activeByBand.values) for (i in a.indices) sum[i] += a[i]
            return sum
        }

        val activeS: FloatArray get() = active(null)

        /**
         * Intensità media 0..1 per fascia (picco medio sopra soglia, pesato
         * sui secondi attivi): 0 = appena sopra soglia, 1 = ≥ +15 dB.
         */
        fun heat(band: String? = null): FloatArray {
            val act = active(band)
            val sum = FloatArray(BUCKETS)
            val src = if (band != null) listOfNotNull(heatByBand[band]) else heatByBand.values
            for (h in src) for (i in h.indices) sum[i] += h[i]
            for (i in sum.indices) sum[i] = if (act[i] > 0f) (sum[i] / act[i]).coerceIn(0f, 1f) else 0f
            return sum
        }
    }

    /** Intensità 0..1 di un picco [overDb] dB sopra soglia (satura a +15). */
    fun heatOf(overDb: Double): Float = (overDb / 15.0).toFloat().coerceIn(0.06f, 1f)

    /**
     * Distribuisce l'intervallo [aS, bS) (epoch secondi) sulle fasce orarie
     * del giorno locale, sommando i secondi in [acc]. Oltre le 24 h satura:
     * ogni fascia riceve al massimo un giro completo.
     */
    fun addInterval(acc: FloatArray, aS: Long, bS: Long, tz: TimeZone = TimeZone.getDefault(), w: Float = 1f) {
        var t = aS
        val end = minOf(bS, aS + 86400)
        while (t < end) {
            val sod = ((t * 1000 + tz.getOffset(t * 1000)) / 1000).mod(86400L)
            val bucket = (sod / BUCKET_S).toInt().coerceIn(0, BUCKETS - 1)
            val next = t + (BUCKET_S - sod % BUCKET_S)
            val chunk = minOf(next, end) - t
            acc[bucket] += chunk * w
            t += chunk
        }
    }

    /**
     * Costruisce la riga di una sessione: copertura = durata meno i gap,
     * attività = eventi sopra soglia (canale V incluso).
     * [events] con band == Channels.GAP vengono sottratti dalla copertura.
     */
    fun night(
        label: String,
        startS: Long,
        endS: Long,
        events: List<EventData>,
        tz: TimeZone = TimeZone.getDefault(),
        thr: Map<String, Double> = emptyMap(),
    ): Night {
        val cover = FloatArray(BUCKETS)
        val active = HashMap<String, FloatArray>()
        val heat = HashMap<String, FloatArray>()
        addInterval(cover, startS, endS, tz)
        for (e in events) {
            if (e.band == Channels.GAP) {
                val g = FloatArray(BUCKETS)
                addInterval(g, e.startT, e.endT, tz)
                for (i in g.indices) cover[i] = (cover[i] - g[i]).coerceAtLeast(0f)
            } else {
                addInterval(active.getOrPut(e.band) { FloatArray(BUCKETS) }, e.startT, e.endT, tz)
                // intensità: picco sopra la soglia della banda (default prudente
                // se soglia o picco mancano nelle sessioni vecchie)
                val t = thr[e.band]
                val h = if (t != null && e.peakDb != null) heatOf(e.peakDb - t) else 0.2f
                addInterval(heat.getOrPut(e.band) { FloatArray(BUCKETS) }, e.startT, e.endT, tz, h)
            }
        }
        return Night(label, cover, active, heat)
    }
}
