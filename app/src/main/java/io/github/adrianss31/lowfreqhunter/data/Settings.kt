package io.github.adrianss31.lowfreqhunter.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Programmazione oraria del log notturno. Minuti dalla mezzanotte. */
@Serializable
data class ScheduleCfg(
    val enabled: Boolean = false,
    val startMin: Int = 23 * 60,   // 23:00
    val endMin: Int = 7 * 60,      // 07:00
)

/**
 * Registrazione continua: la sessione non finisce mai da sola — a un'ora
 * fissa del giorno viene chiusa e riaperta subito, senza fermare la cattura.
 * Ogni riga della heatmap di ricorrenza diventa così un giorno completo.
 * Pensata per un dispositivo dedicato (es. tablet in carica).
 */
@Serializable
data class ContinuousCfg(
    val enabled: Boolean = false,
    val splitMin: Int = 0,         // primo spezzamento (inizio sessione "Notte")
    val split2Enabled: Boolean = false,
    val split2Min: Int = 7 * 60,   // secondo spezzamento (inizio sessione "Giorno")
) {
    /** Orari di spezzamento attivi (minuti dalla mezzanotte). */
    fun splitMins(): List<Int> =
        if (split2Enabled) listOf(splitMin, split2Min) else listOf(splitMin)

    /**
     * Etichetta della sessione che copre il minuto [minOfDay]: con due
     * spezzamenti la fascia da splitMin a split2Min è la "Notte" (es. 21→7),
     * l'altra il "Giorno". Con uno solo resta tutto "Notte".
     */
    fun labelPrefixAt(minOfDay: Int): String {
        if (!split2Enabled || splitMin == split2Min) return "Notte"
        val inNight = if (splitMin <= split2Min) {
            minOfDay >= splitMin && minOfDay < split2Min
        } else {
            minOfDay >= splitMin || minOfDay < split2Min
        }
        return if (inNight) "Notte" else "Giorno"
    }
}

/**
 * Inizio (epoch ms) della finestra di registrazione corrente: l'ultimo
 * confine passato tra gli spezzamenti della registrazione continua (se
 * attiva), oppure gli orari della programmazione (se attiva), oppure la
 * mezzanotte. Una registrazione avviata nella stessa finestra di una
 * sessione esistente PROSEGUE quella sessione invece di crearne una nuova.
 */
fun AppSettings.windowStartMs(
    now: Long = System.currentTimeMillis(),
    tz: java.util.TimeZone = java.util.TimeZone.getDefault(),
): Long {
    val mins = when {
        continuous.enabled -> continuous.splitMins()
        schedule.enabled -> listOf(schedule.startMin, schedule.endMin)
        else -> listOf(0)
    }
    return mins.maxOf { lastOccurrenceMs(it, now, tz) }
}

/** Ultima occorrenza (epoch ms) dell'orario [minOfDay] non successiva a [now]. */
private fun lastOccurrenceMs(minOfDay: Int, now: Long, tz: java.util.TimeZone): Long {
    val cal = java.util.Calendar.getInstance(tz).apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, minOfDay / 60)
        set(java.util.Calendar.MINUTE, minOfDay % 60)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis > now) cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
    return cal.timeInMillis
}

/** Server LAN: dashboard consultabile dal PC mentre il telefono registra. */
@Serializable
data class LanCfg(
    val enabled: Boolean = false,
    val port: Int = 8765,
    val token: String = "",        // generato al primo enable; vuoto = nessun accesso
)

/**
 * Stima dB SPL: offset additivo sui dBFS, tarato dall'utente confrontando un
 * suono costante con un fonometro (anche un'app). Resta una stima — i report
 * mantengono il disclaimer — ma rende i livelli leggibili a terzi.
 * Default 120: la CDD Android raccomanda −26 dBFS @ 94 dB SPL.
 */
@Serializable
data class CalibCfg(
    val enabled: Boolean = false,
    val offsetDb: Double = 120.0,
)

@Serializable
data class AppSettings(
    val engine: EngineCfg = EngineCfg(),
    val specXMax: Int = 250,       // asse X spettro live (Hz)
    val sonify: Boolean = false,
    val schedule: ScheduleCfg = ScheduleCfg(),
    val continuous: ContinuousCfg = ContinuousCfg(),
    val lan: LanCfg = LanCfg(),
    val calib: CalibCfg = CalibCfg(),
)

private val Context.dataStore by preferencesDataStore(name = "lfh-settings")
private val KEY = stringPreferencesKey("cfg")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class SettingsRepo(private val context: Context) {

    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        decode(prefs[KEY])
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY] = json.encodeToString(transform(decode(prefs[KEY])))
        }
    }

    private fun decode(raw: String?): AppSettings =
        if (raw.isNullOrBlank()) AppSettings()
        else runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())

    companion object {
        @Volatile private var instance: SettingsRepo? = null

        fun get(context: Context): SettingsRepo =
            instance ?: synchronized(this) {
                instance ?: SettingsRepo(context.applicationContext).also { instance = it }
            }
    }
}
