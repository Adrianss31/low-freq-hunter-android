package io.github.adrianss31.lowfreqhunter.engine

import kotlinx.serialization.Serializable

/** Banda di frequenza monitorata (port del modello dinamico della PWA). */
@Serializable
data class BandCfg(
    val id: String,
    val enabled: Boolean = true,
    val center: Double,
    val width: Double,
    val thr: Double,
) {
    val lo: Double get() = maxOf(1.0, center - width)
    val hi: Double get() = center + width
    /** Etichetta breve: "A · 50 Hz ±5" */
    val label: String get() = "$id · ${center.toInt()} Hz ±${width.toInt()}"
    /** Etichetta frequenza completa per UI/export: "50 Hz (45–55 Hz)" */
    val freqLabel: String get() = "${center.toInt()} Hz (${lo.toInt()}–${hi.toInt()} Hz)"
    /** Etichetta compatta: "50 Hz" */
    val freqShort: String get() = "${center.toInt()} Hz"
}

/** Canale vibrazioni da accelerometro ("V"). Livelli in dB rel 1 g. */
@Serializable
data class VibCfg(
    val enabled: Boolean = false,
    val thr: Double = -55.0,
)

@Serializable
data class EngineCfg(
    val bands: List<BandCfg> = listOf(
        BandCfg("A", center = 50.0, width = 5.0, thr = -55.0),
        BandCfg("B", center = 100.0, width = 5.0, thr = -55.0),
    ),
    val minOnS: Int = 10,
    val minOffS: Int = 15,
    val hystDb: Double = 3.0,
    val fftSize: Int = 32768,
    val smoothNight: Double = 0.3,
    val smoothLive: Double = 0.5,
    val vib: VibCfg = VibCfg(),
    val clipsEnabled: Boolean = false,
    val clipSeconds: Int = 20,
    val clipsMax: Int = 12,
) {
    fun band(id: String): BandCfg? = bands.find { it.id == id }
    fun enabledBands(): List<BandCfg> = bands.filter { it.enabled }
}

/** Id speciali usati nel campo band degli eventi. */
object Channels {
    const val VIB = "V"
    const val GAP = "gap"
}

data class SampleData(
    val t: Long,                    // epoch secondi
    val lv: Map<String, Double>,    // livello per banda (dBFS)
    val ref: Double,                // broadband 20–500 Hz (dBFS)
    val domHz: Double,              // frequenza dominante 20–200 Hz
    val vibDb: Double?,             // canale V (dB rel 1 g), null se disattivo
    val battPct: Int?,              // livello batteria
)

data class EventData(
    val band: String,               // lettera banda, "V" o "gap"
    val startT: Long,
    val endT: Long,
    val durationS: Long,
    val peakDb: Double?,
    val avgDb: Double?,
)
