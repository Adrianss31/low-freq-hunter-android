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

/** Rilievo: mappa della casa con punti di misura (heatmap per frequenza). */
@Entity(tableName = "surveys")
data class SurveyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,          // epoch ms
    val imagePath: String?,       // piantina in filesDir/surveys/, null = griglia
    val cfgJson: String,          // snapshot bande al momento della creazione
)

@Entity(tableName = "survey_points")
data class SurveyPointEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(index = true) val surveyId: String,
    val x: Float,                 // coordinate normalizzate 0..1 sulla mappa
    val y: Float,
    val levelsJson: String,       // Map<bandId, dBFS medio del dwell>
    val vibDb: Double?,           // canale vibrazioni medio, se attivo
    val t: Long,                  // epoch s della misura
    val dwellS: Int,              // durata della misura
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

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    suspend fun sessionsList(): List<SessionEntity>

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

    // letture incrementali per il server LAN (polling con ?since=)
    @Query("SELECT * FROM samples WHERE sessionId = :id AND t > :sinceT ORDER BY t")
    suspend fun samplesSince(id: String, sinceT: Long): List<SampleEntity>

    @Query("SELECT * FROM events WHERE sessionId = :id AND endT > :sinceT ORDER BY startT")
    suspend fun eventsSince(id: String, sinceT: Long): List<EventEntity>

    @Query("SELECT * FROM slices WHERE sessionId = :id AND t > :sinceT ORDER BY t")
    suspend fun slicesSince(id: String, sinceT: Long): List<SliceEntity>

    @Query("SELECT * FROM markers WHERE sessionId = :id AND t > :sinceT ORDER BY t")
    suspend fun markersSince(id: String, sinceT: Long): List<MarkerEntity>

    // rilievi (mappa casa)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSurvey(s: SurveyEntity)

    @Query("SELECT * FROM surveys ORDER BY createdAt DESC")
    fun surveysFlow(): Flow<List<SurveyEntity>>

    @Query("SELECT * FROM surveys WHERE id = :id")
    suspend fun survey(id: String): SurveyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSurveyPoint(p: SurveyPointEntity)

    @Query("SELECT * FROM survey_points WHERE surveyId = :id ORDER BY t")
    fun surveyPointsFlow(id: String): Flow<List<SurveyPointEntity>>

    @Query("DELETE FROM survey_points WHERE id = :pointId")
    suspend fun deleteSurveyPoint(pointId: String)

    @Query("DELETE FROM survey_points WHERE surveyId = :id")
    suspend fun deleteSurveyPoints(id: String)

    @Query("DELETE FROM surveys WHERE id = :id")
    suspend fun deleteSurvey(id: String)

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
        SurveyEntity::class, SurveyPointEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class LfhDb : RoomDatabase() {
    abstract fun dao(): LfhDao

    companion object {
        // v1 → v2: tabelle dei rilievi (mappa casa). Migrazione additiva:
        // le sessioni registrate non si toccano.
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `surveys` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`imagePath` TEXT, `cfgJson` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `survey_points` (" +
                        "`id` TEXT NOT NULL, `surveyId` TEXT NOT NULL, `x` REAL NOT NULL, `y` REAL NOT NULL, " +
                        "`levelsJson` TEXT NOT NULL, `vibDb` REAL, `t` INTEGER NOT NULL, `dwellS` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_survey_points_surveyId` ON `survey_points` (`surveyId`)")
            }
        }

        @Volatile private var instance: LfhDb? = null

        fun get(context: Context): LfhDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, LfhDb::class.java, "lfh.db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
