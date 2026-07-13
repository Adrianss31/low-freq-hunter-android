package io.github.adrianss31.lowfreqhunter.data

import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.engine.EventData
import io.github.adrianss31.lowfreqhunter.engine.NightEngine
import io.github.adrianss31.lowfreqhunter.engine.SampleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Sink del NightEngine che persiste su Room: campioni bufferizzati (flush ogni
 * 10 s o 20 campioni, come la PWA), eventi/slice subito. Le scritture passano
 * per un Channel consumato in ordine su un'unica coroutine.
 */
class SessionRecorder(
    private val dao: LfhDao,
    private val scope: CoroutineScope,
    cfg: EngineCfg,
    sampleRate: Int,
    binHz: Double,
    audioSource: String,
) : NightEngine.Sink {

    val sessionId: String = UUID.randomUUID().toString()
    val startedAt: Long = System.currentTimeMillis()

    private val json = Json { encodeDefaults = true }
    private val writes = Channel<suspend () -> Unit>(capacity = Channel.UNLIMITED)
    private val sampleBuf = ArrayList<SampleEntity>()
    private var lastFlushMs = System.currentTimeMillis()

    private var session = SessionEntity(
        id = sessionId,
        label = "Notte " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(startedAt)),
        startedAt = startedAt,
        endedAt = null,
        lastT = startedAt / 1000,
        cfgJson = json.encodeToString(cfg),
        sampleRate = sampleRate,
        binHz = binHz,
        audioSource = audioSource,
    )

    private val _eventsCount = MutableStateFlow(0)
    val eventsCount: StateFlow<Int> = _eventsCount
    private val _lastEvent = MutableStateFlow<EventData?>(null)
    val lastEvent: StateFlow<EventData?> = _lastEvent

    init {
        scope.launch {
            for (op in writes) {
                runCatching { op() }
            }
        }
        enqueue { dao.upsertSession(session) }
    }

    private fun enqueue(op: suspend () -> Unit) {
        writes.trySend(op)
    }

    override fun onSample(s: SampleData) {
        val entity = SampleEntity(
            sessionId = sessionId,
            t = s.t,
            lvJson = json.encodeToString(s.lv),
            ref = s.ref,
            domHz = s.domHz,
            vibDb = s.vibDb,
            battPct = s.battPct,
        )
        session = session.copy(lastT = s.t)
        synchronized(sampleBuf) { sampleBuf.add(entity) }
        val now = System.currentTimeMillis()
        if (now - lastFlushMs > 10_000 || sampleBuf.size >= 20) {
            lastFlushMs = now
            flush()
        }
    }

    override fun onEvent(e: EventData) {
        if (e.band != Channels.GAP) {
            _eventsCount.value += 1
            _lastEvent.value = e
        }
        val entity = EventEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            band = e.band,
            startT = e.startT,
            endT = e.endT,
            durationS = e.durationS,
            peakDb = e.peakDb,
            avgDb = e.avgDb,
            kind = e.kind,
        )
        val snapshot = session.copy(eventsCount = _eventsCount.value)
        session = snapshot
        enqueue {
            dao.insertEvent(entity)
            dao.upsertSession(snapshot)
        }
    }

    override fun onSlice(t: Long, bins: ByteArray) {
        enqueue { dao.insertSlice(SliceEntity(sessionId, t, bins)) }
    }

    fun addMarker(origin: String): Long {
        val t = System.currentTimeMillis() / 1000
        val snapshot = session.copy(markersCount = session.markersCount + 1)
        session = snapshot
        enqueue {
            dao.insertMarker(MarkerEntity(UUID.randomUUID().toString(), sessionId, t, origin))
            dao.upsertSession(snapshot)
        }
        return t
    }

    fun addClip(band: String, t: Long, path: String, mime: String) {
        enqueue { dao.insertClip(ClipEntity(UUID.randomUUID().toString(), sessionId, band, t, path, mime)) }
    }

    fun flush() {
        val batch: List<SampleEntity>
        synchronized(sampleBuf) {
            batch = ArrayList(sampleBuf)
            sampleBuf.clear()
        }
        val snapshot = session
        enqueue {
            if (batch.isNotEmpty()) dao.insertSamples(batch)
            dao.upsertSession(snapshot)
        }
    }

    fun close() {
        session = session.copy(endedAt = System.currentTimeMillis(), eventsCount = _eventsCount.value)
        flush()
        writes.close()
    }
}

/** Sessioni rimaste aperte (crash/batteria): chiuse all'orario dell'ultimo campione. */
suspend fun recoverInterrupted(dao: LfhDao): Int {
    val open = dao.openSessions()
    for (s in open) {
        dao.upsertSession(s.copy(endedAt = s.lastT * 1000, recovered = true))
    }
    return open.size
}

suspend fun deleteSessionData(dao: LfhDao, id: String) {
    dao.deleteSamples(id)
    dao.deleteEvents(id)
    dao.deleteSlices(id)
    dao.deleteMarkers(id)
    dao.deleteClips(id)
    dao.deleteSession(id)
}
