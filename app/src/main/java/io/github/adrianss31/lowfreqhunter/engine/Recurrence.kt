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
        val coverS: FloatArray,   // secondi monitorati per fascia
        val activeS: FloatArray,  // secondi sopra soglia per fascia
    )

    /**
     * Distribuisce l'intervallo [aS, bS) (epoch secondi) sulle fasce orarie
     * del giorno locale, sommando i secondi in [acc]. Oltre le 24 h satura:
     * ogni fascia riceve al massimo un giro completo.
     */
    fun addInterval(acc: FloatArray, aS: Long, bS: Long, tz: TimeZone = TimeZone.getDefault()) {
        var t = aS
        val end = minOf(bS, aS + 86400)
        while (t < end) {
            val sod = ((t * 1000 + tz.getOffset(t * 1000)) / 1000).mod(86400L)
            val bucket = (sod / BUCKET_S).toInt().coerceIn(0, BUCKETS - 1)
            val next = t + (BUCKET_S - sod % BUCKET_S)
            val chunk = minOf(next, end) - t
            acc[bucket] += chunk.toFloat()
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
    ): Night {
        val cover = FloatArray(BUCKETS)
        val active = FloatArray(BUCKETS)
        addInterval(cover, startS, endS, tz)
        for (e in events) {
            if (e.band == Channels.GAP) {
                val g = FloatArray(BUCKETS)
                addInterval(g, e.startT, e.endT, tz)
                for (i in g.indices) cover[i] = (cover[i] - g[i]).coerceAtLeast(0f)
            } else {
                addInterval(active, e.startT, e.endT, tz)
            }
        }
        return Night(label, cover, active)
    }
}
