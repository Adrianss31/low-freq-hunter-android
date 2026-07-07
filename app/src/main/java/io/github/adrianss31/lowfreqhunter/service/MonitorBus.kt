package io.github.adrianss31.lowfreqhunter.service

import io.github.adrianss31.lowfreqhunter.engine.EventData
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Stato condiviso tra il servizio di monitoraggio e la UI. Il servizio
 * pubblica, le schermate osservano: nessun binding, un solo processo.
 */
object MonitorBus {

    data class State(
        val running: Boolean = false,
        val sessionId: String? = null,
        val startedAt: Long = 0,
        val eventsCount: Int = 0,
        val activeBands: Map<String, Long> = emptyMap(),  // banda → attiva da (epoch s)
        val levels: Map<String, Double> = emptyMap(),     // livelli istantanei per banda
        val vibDb: Double? = null,
        val ref: Double = -120.0,
        val domHz: Double = 0.0,
        val audioSource: String = "",
        val batteryPct: Int? = null,
    )

    class SpectrumFrame(val spec: FloatArray, val binHz: Double, val t: Long)

    val state = MutableStateFlow(State())
    val spectrum = MutableStateFlow<SpectrumFrame?>(null)

    /** Dati della sessione in corso, per i pannelli della schermata Notte. */
    val slices = MutableStateFlow<List<Pair<Long, ByteArray>>>(emptyList())
    val events = MutableStateFlow<List<EventData>>(emptyList())
    val markers = MutableStateFlow<List<Long>>(emptyList())

    fun resetSession() {
        slices.value = emptyList()
        events.value = emptyList()
        markers.value = emptyList()
    }
}
