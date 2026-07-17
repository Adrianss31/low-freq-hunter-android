package io.github.adrianss31.lowfreqhunter.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import io.github.adrianss31.lowfreqhunter.data.AppSettings
import io.github.adrianss31.lowfreqhunter.data.CalibCfg
import io.github.adrianss31.lowfreqhunter.data.EventEntity
import io.github.adrianss31.lowfreqhunter.data.Exporter
import io.github.adrianss31.lowfreqhunter.data.LfhDao
import io.github.adrianss31.lowfreqhunter.data.MarkerEntity
import io.github.adrianss31.lowfreqhunter.data.SampleEntity
import io.github.adrianss31.lowfreqhunter.data.SessionBundle
import io.github.adrianss31.lowfreqhunter.data.SessionEntity
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.data.SliceEntity
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import io.github.adrianss31.lowfreqhunter.service.MonitorService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import kotlin.math.roundToInt

/**
 * Server HTTP sulla LAN: dashboard e API JSON per monitorare dal PC la
 * sessione in corso (e sfogliare l'archivio) mentre il telefono registra.
 *
 * Vive dentro MonitorService: parte e muore col monitoraggio. Solo HTTP in
 * chiaro — rete di casa — con un token nell'URL come lucchetto minimo.
 *
 * API (tutte con ?k=<token>):
 *   GET  /                        dashboard HTML
 *   GET  /api/state               stato live (livelli, bande attive, batteria…)
 *   GET  /api/spectrum            spettro corrente fino a 500 Hz
 *   GET  /api/session?since=<t>   dati incrementali della sessione in corso
 *   GET  /api/sessions            elenco sessioni salvate
 *   GET  /api/session/<id>        dati completi di una sessione salvata
 *   GET  /api/session/<id>.json   export JSON completo (come dall'app)
 *   GET  /api/session/<id>/eventi.csv | campioni.csv
 *   POST /api/marker              marker "lo sento adesso" dal PC
 *   GET  /api/recurrence          eventi+soglie delle ultime sessioni (heatmap)
 *   GET  /api/settings            impostazioni correnti dell'app
 *   POST /api/settings            salva le impostazioni (sezione LAN esclusa)
 */
class LanServer(
    private val ctx: Context,
    private val dao: LfhDao,
    private val token: String,
    port: Int,
    private val cfgProvider: () -> EngineCfg,
    private val calibProvider: () -> CalibCfg? = { null },
) : NanoHTTPD(port) {

    companion object {
        /** IP IPv4 del telefono sulla rete locale, null se non connesso. */
        fun deviceIp(): String? = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private val b64 = Base64.getEncoder()
    private val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (token.isBlank() || session.parms["k"] != token) {
            return text(Response.Status.UNAUTHORIZED, "token mancante o errato — apri l'URL mostrato dall'app")
        }
        return try {
            when {
                uri == "/" || uri == "/index.html" -> dashboard()
                uri == "/api/state" -> json(apiState())
                uri == "/api/spectrum" -> json(apiSpectrum())
                uri == "/api/session" && session.method == Method.GET ->
                    json(apiLiveSession((session.parms["since"] ?: "0").toLongOrNull() ?: 0L))
                uri == "/api/sessions" -> json(apiSessions())
                uri == "/api/recurrence" -> json(apiRecurrence())
                uri == "/api/settings" && session.method == Method.GET -> json(apiSettingsGet())
                uri == "/api/settings" && session.method == Method.POST -> apiSettingsPost(session)
                uri == "/api/marker" && session.method == Method.POST -> apiMarker()
                uri.startsWith("/api/session/") -> storedSession(uri)
                else -> text(Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: Exception) {
            text(Response.Status.INTERNAL_ERROR, "errore: ${e.message}")
        }
    }

    // ── endpoint ────────────────────────────────────────────────────────────

    private fun apiState(): String {
        val st = MonitorBus.state.value
        val cfg = cfgProvider()
        return buildJsonObject {
            put("now", System.currentTimeMillis())
            put("running", st.running)
            put("mode", st.mode)
            put("sessionId", st.sessionId)
            put("startedAt", st.startedAt)
            put("eventsCount", st.eventsCount)
            put("audioSource", st.audioSource)
            put("batteryPct", st.batteryPct)
            put("ref", round1(st.ref))
            put("domHz", round1(st.domHz))
            st.vibDb?.let { put("vibDb", round1(it)) }
            putJsonObject("levels") {
                for ((k, v) in st.levels) put(k, round1(v))
            }
            putJsonObject("activeBands") {
                for ((k, since) in st.activeBands) put(k, since)
            }
            putJsonObject("cfg") {
                putJsonArray("bands") {
                    for (b in cfg.enabledBands()) {
                        add(
                            buildJsonObject {
                                put("id", b.id)
                                put("center", b.center)
                                put("lo", b.lo)
                                put("hi", b.hi)
                                put("thr", b.thr)
                                put("label", cfg.channelLabel(b.id))
                            },
                        )
                    }
                }
                put("vibEnabled", cfg.vib.enabled)
                put("vibThr", cfg.vib.thr)
                put("minOnS", cfg.minOnS)
                put("minOffS", cfg.minOffS)
                put("hystDb", cfg.hystDb)
                calibProvider()?.takeIf { it.enabled }?.let { put("splOffsetDb", it.offsetDb) }
            }
        }.toString()
    }

    private fun apiSpectrum(): String {
        val fr = MonitorBus.spectrum.value ?: return """{"spec":[]}"""
        val maxBins = minOf(fr.spec.size, (500.0 / fr.binHz).toInt())
        return buildJsonObject {
            put("binHz", fr.binHz)
            put("t", fr.t)
            putJsonArray("spec") {
                for (i in 0 until maxBins) add(kotlinx.serialization.json.JsonPrimitive(round1(fr.spec[i].toDouble())))
            }
        }.toString()
    }

    private fun apiLiveSession(since: Long): String {
        val st = MonitorBus.state.value
        val id = st.sessionId
            ?: return buildJsonObject {
                put("running", st.running)
                put("mode", st.mode)
            }.toString()
        val (samples, events, slices, markers) = runBlocking {
            Quad(
                dao.samplesSince(id, since),
                dao.eventsSince(id, since),
                dao.slicesSince(id, since),
                dao.markersSince(id, since),
            )
        }
        return sessionJson(
            running = st.running, sessionId = id, startedAt = st.startedAt, label = null,
            samples = samples, events = events, slices = slices, markers = markers,
        )
    }

    private fun apiSessions(): String {
        val open = MonitorBus.state.value.sessionId
        val all: List<SessionEntity> = runBlocking { dao.sessionsList() }
        return buildJsonArray {
            for (s in all) {
                add(
                    buildJsonObject {
                        put("id", s.id)
                        put("label", s.label)
                        put("startedAt", s.startedAt)
                        put("endedAt", s.endedAt)
                        put("lastT", s.lastT)
                        put("eventsCount", s.eventsCount)
                        put("markersCount", s.markersCount)
                        put("audioSource", s.audioSource)
                        put("recovered", s.recovered)
                        put("live", s.id == open)
                    },
                )
            }
        }.toString()
    }

    /** Impostazioni correnti dell'app, modificabili dalla dashboard. */
    private fun apiSettingsGet(): String {
        val settings = runBlocking { SettingsRepo.get(ctx).flow.first() }
        return jsonCodec.encodeToString(settings)
    }

    /**
     * Salva le impostazioni ricevute dalla dashboard. La sezione LAN resta
     * quella del telefono (cambiarla da remoto taglierebbe la connessione);
     * i parametri del motore valgono dalla prossima sessione.
     */
    private fun apiSettingsPost(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = body["postData"] ?: return text(Response.Status.BAD_REQUEST, "body mancante")
        val incoming = runCatching { jsonCodec.decodeFromString<AppSettings>(raw) }.getOrNull()
            ?: return text(Response.Status.BAD_REQUEST, "JSON impostazioni non valido")
        runBlocking {
            SettingsRepo.get(ctx).update { cur -> incoming.copy(lan = cur.lan) }
        }
        return json("""{"ok":true}""")
    }

    /**
     * Ricorrenze: le ultime sessioni con eventi e soglie della cfg snapshot,
     * per la heatmap notte-per-notte con intensità sulla dashboard.
     */
    private fun apiRecurrence(): String {
        val sessions = runBlocking { dao.sessionsList() }.take(21)
        return buildJsonArray {
            for (s in sessions) {
                val snap = runCatching { jsonCodec.decodeFromString<EngineCfg>(s.cfgJson) }.getOrNull()
                val events = runBlocking { dao.events(s.id) }
                add(
                    buildJsonObject {
                        put("id", s.id)
                        put("label", s.label)
                        put("startedAt", s.startedAt)
                        put("endedAt", s.endedAt ?: s.lastT * 1000)
                        put("eventsCount", s.eventsCount)
                        putJsonObject("thr") {
                            snap?.bands?.forEach { b -> put(b.id, b.thr) }
                            snap?.vib?.let { put(Channels.VIB, it.thr) }
                        }
                        putJsonArray("events") {
                            for (e in events) {
                                add(
                                    buildJsonObject {
                                        put("band", e.band)
                                        put("startT", e.startT)
                                        put("endT", e.endT)
                                        e.peakDb?.let { put("peakDb", round1(it)) }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }.toString()
    }

    private fun apiMarker(): Response {
        val svc = MonitorService.instance ?: return text(Response.Status.CONFLICT, "nessuna sessione in corso")
        svc.addMarker("pc")
        return json("""{"ok":true}""")
    }

    private fun storedSession(uri: String): Response {
        // /api/session/<id>[.json | /eventi.csv | /campioni.csv]
        val rest = uri.removePrefix("/api/session/")
        val (id, kind) = when {
            rest.endsWith(".json") -> Pair(rest.removeSuffix(".json"), "json")
            rest.endsWith("/eventi.csv") -> Pair(rest.removeSuffix("/eventi.csv"), "ecsv")
            rest.endsWith("/campioni.csv") -> Pair(rest.removeSuffix("/campioni.csv"), "scsv")
            else -> Pair(rest, "data")
        }
        if (kind == "data") {
            val s = runBlocking { dao.session(id) } ?: return text(Response.Status.NOT_FOUND, "sessione sconosciuta")
            val (samples, events, slices, markers) = runBlocking {
                Quad(dao.samples(id), dao.events(id), dao.slices(id), dao.markers(id))
            }
            return json(
                sessionJson(
                    running = false, sessionId = id, startedAt = s.startedAt, label = s.label,
                    samples = samples, events = events, slices = slices, markers = markers,
                    cfgJson = s.cfgJson, endedAt = s.endedAt,
                ),
            )
        }
        val b = runBlocking { SessionBundle.load(dao, id) } ?: return text(Response.Status.NOT_FOUND, "sessione sconosciuta")
        return when (kind) {
            "json" -> download(Exporter.json(b), "application/json", "${Exporter.baseName(b)}.json")
            "ecsv" -> download(Exporter.eventsCsv(b), "text/csv", "${Exporter.baseName(b)}_eventi.csv")
            else -> download(Exporter.samplesCsv(b), "text/csv", "${Exporter.baseName(b)}_campioni.csv")
        }
    }

    // ── costruzione JSON di sessione (live e archivio) ──────────────────────

    private fun sessionJson(
        running: Boolean,
        sessionId: String,
        startedAt: Long,
        label: String?,
        samples: List<SampleEntity>,
        events: List<EventEntity>,
        slices: List<SliceEntity>,
        markers: List<MarkerEntity>,
        cfgJson: String? = null,
        endedAt: Long? = null,
    ): String = buildJsonObject {
        put("running", running)
        put("sessionId", sessionId)
        put("startedAt", startedAt)
        label?.let { put("label", it) }
        endedAt?.let { put("endedAt", it) }
        cfgJson?.let { put("cfg", parseOrNull(it)) }
        putJsonArray("samples") {
            for (s in samples) {
                add(
                    buildJsonObject {
                        put("t", s.t)
                        put("lv", parseOrNull(s.lvJson))
                        put("ref", round1(s.ref))
                        put("dom", round1(s.domHz))
                        s.vibDb?.let { put("vib", round1(it)) }
                    },
                )
            }
        }
        putJsonArray("events") {
            for (e in events.filter { it.band != Channels.GAP }) {
                add(
                    buildJsonObject {
                        put("band", e.band)
                        put("startT", e.startT)
                        put("endT", e.endT)
                        put("durationS", e.durationS)
                        e.peakDb?.let { put("peakDb", round1(it)) }
                        e.avgDb?.let { put("avgDb", round1(it)) }
                        if (e.kind != "steady") put("kind", e.kind)
                    },
                )
            }
        }
        putJsonArray("gaps") {
            for (g in events.filter { it.band == Channels.GAP }) {
                add(
                    buildJsonObject {
                        put("startT", g.startT)
                        put("endT", g.endT)
                    },
                )
            }
        }
        putJsonArray("markers") {
            for (m in markers) add(kotlinx.serialization.json.JsonPrimitive(m.t))
        }
        putJsonArray("slices") {
            for (sl in slices) {
                add(
                    buildJsonObject {
                        put("t", sl.t)
                        put("b64", b64.encodeToString(sl.bins))
                    },
                )
            }
        }
    }.toString()

    // ── helper ──────────────────────────────────────────────────────────────

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun parseOrNull(raw: String): JsonElement =
        runCatching { Json.parseToJsonElement(raw) }.getOrDefault(buildJsonObject {})

    private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

    private fun dashboard(): Response {
        val html = ctx.assets.open("dashboard.html").readBytes().toString(Charsets.UTF_8)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun json(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)

    private fun text(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun download(body: String, mime: String, name: String): Response =
        newFixedLengthResponse(Response.Status.OK, "$mime; charset=utf-8", body).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$name\"")
        }
}
