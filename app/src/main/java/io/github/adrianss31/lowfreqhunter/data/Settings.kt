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

@Serializable
data class AppSettings(
    val engine: EngineCfg = EngineCfg(),
    val specXMax: Int = 250,       // asse X spettro live (Hz)
    val sonify: Boolean = false,
    val schedule: ScheduleCfg = ScheduleCfg(),
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
