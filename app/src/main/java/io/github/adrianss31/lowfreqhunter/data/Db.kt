package io.github.adrianss31.lowfreqhunter.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val label: String,
    val startedAt: Long,          // epoch ms
    val endedAt: Long?,           // epoch ms, null = in corso
    val lastT: Long,              // epoch s dell'ultimo campione salvato
    val cfgJson: String,          // snapshot EngineCfg della sessione (prova dei parametri)
    val sampleRate: Int,
    val binHz: Double,
    val audioSource: String,      // UNPROCESSED / VOICE_RECOGNITION / MIC
    val eventsCount: Int = 0,
    val markersCount: Int = 0,
    val recovered: Boolean = false,
)

@Entity(tableName = "samples", primaryKeys = ["sessionId", "t"])
data class SampleEntity(
    @ColumnInfo(index = true) val sessionId: String,
    val t: Long,                  // epoch s
    val lvJson: String,           // Map<bandId, dBFS>
    val ref: Double,              // broadband 20–500 Hz
    val domHz: Double,
    val vibDb: Double?,           // canale V, dB rel 1 g
    val battPct: Int?,
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(index = true) val sessionId: String,
    val band: String,             // lettera banda, "V" o "gap"
    val startT: Long,
    val endT: Long,
    val durationS: Long,
    val peakDb: Double?,
    val avgDb: Double?,
)

@Entity(tableName = "slices", primaryKeys = ["sessionId", "t"])
data class SliceEntity(
    @ColumnInfo(index = true) val sessionId: String,
    val t: Long,
    val bins: ByteArray,          // 64 bin 20–200 Hz quantizzati −110…−20 dB → 0…255
)

@Entity(tableName = "markers")
data class MarkerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(index = true) val sessionId: String,
    val t: Long,
    val origin: String,           // "button" | "notification"
)

@Entity(tableName = "clips")
data class ClipEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(index = true) val sessionId: String,
    val band: String,
    val t: Long,
    val path: String,             // file .m4a nella dir dell'app
    val mime: String,
)

@Dao
interface LfhDao {
    // sessioni
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(s: SessionEntity)

    @Update
    suspend fun updateSession(s: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun sessionsFlow(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL")
    suspend fun openSessions(): List<SessionEntity>

    // dati per sessione
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<SampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(e: EventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlice(s: SliceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarker(m: MarkerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClip(c: ClipEntity)

    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY t")
    suspend fun samples(id: String): List<SampleEntity>

    @Query("SELECT * FROM events WHERE sessionId = :id ORDER BY startT")
    suspend fun events(id: String): List<EventEntity>

    @Query("SELECT * FROM slices WHERE sessionId = :id ORDER BY t")
    suspend fun slices(id: String): List<SliceEntity>

    @Query("SELECT * FROM markers WHERE sessionId = :id ORDER BY t")
    suspend fun markers(id: String): List<MarkerEntity>

    @Query("SELECT * FROM clips WHERE sessionId = :id ORDER BY t")
    suspend fun clips(id: String): List<ClipEntity>

    // cancellazione sessione completa
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM samples WHERE sessionId = :id")
    suspend fun deleteSamples(id: String)

    @Query("DELETE FROM events WHERE sessionId = :id")
    suspend fun deleteEvents(id: String)

    @Query("DELETE FROM slices WHERE sessionId = :id")
    suspend fun deleteSlices(id: String)

    @Query("DELETE FROM markers WHERE sessionId = :id")
    suspend fun deleteMarkers(id: String)

    @Query("DELETE FROM clips WHERE sessionId = :id")
    suspend fun deleteClips(id: String)
}

@Database(
    entities = [
        SessionEntity::class, SampleEntity::class, EventEntity::class,
        SliceEntity::class, MarkerEntity::class, ClipEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LfhDb : RoomDatabase() {
    abstract fun dao(): LfhDao

    companion object {
        @Volatile private var instance: LfhDb? = null

        fun get(context: Context): LfhDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, LfhDb::class.java, "lfh.db")
                    .build()
                    .also { instance = it }
            }
    }
}
