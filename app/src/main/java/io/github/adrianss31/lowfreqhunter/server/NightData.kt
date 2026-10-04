package io.github.adrianss31.lowfreqhunter.server

import android.content.Context
import io.github.adrianss31.lowfreqhunter.data.*
import io.github.adrianss31.lowfreqhunter.engine.*
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import kotlin.math.*

typealias NightPayload = JsonObject
typealias NightSummary = JsonObject
typealias LevelPayload = JsonObject

data class NightWindow(val date: LocalDate, val from: Long, val to: Long) {
    companion object {
        fun forDate(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = NightWindow(
            date, date.atTime(21, 0).atZone(zone).toEpochSecond(),
            date.plusDays(1).atTime(9, 0).atZone(zone).toEpochSecond(),
        )
        fun latest(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): LocalDate {
            val t = java.time.Instant.ofEpochMilli(now).atZone(zone)
            return if (t.hour >= 21) t.toLocalDate() else t.toLocalDate().minusDays(1)
        }
    }
}

object NightMath {
    fun union(intervals: List<Pair<Long, Long>>, from: Long, to: Long): List<Pair<Long, Long>> {
        val result = mutableListOf<Pair<Long, Long>>()
        for ((a,b) in intervals.map { max(from,it.first) to min(to,it.second) }.filter { it.second>it.first }.sortedBy { it.first }) {
            val last = result.lastOrNull()
            if (last != null && a <= last.second) result[result.lastIndex] = last.first to max(last.second,b)
            else result.add(a to b)
        }
        return result
    }
    fun unionDuration(intervals: List<Pair<Long, Long>>, from: Long, to: Long): Long = union(intervals,from,to).sumOf { it.second-it.first }
    fun subtractGaps(from: Long, to: Long, gaps: List<Pair<Long,Long>>): List<Pair<Long,Long>> {
        var cursor = from
        val out = mutableListOf<Pair<Long,Long>>()
        for ((a,b) in union(gaps,from,to)) { if(a>cursor) out.add(cursor to a); cursor=max(cursor,b) }
        if(cursor<to) out.add(cursor to to)
        return out
    }
    /** Power-domain means and extrema, never a decimated substitute for a short peak. */
    fun downsample(samples: List<SampleEntity>, keys: Map<String,String>, from: Long, to: Long, cols: Int): List<JsonElement> {
        data class Acc(val values: MutableMap<String,MutableList<Double>> = linkedMapOf(), var count: Int = 0, var first: Long = Long.MAX_VALUE, var last: Long = 0)
        val buckets = sortedMapOf<Int,Acc>()
        val width = (to-from).toDouble()/cols.coerceAtLeast(1)
        for (s in samples) {
            if(s.t<from || s.t>=to) continue
            val index = floor((s.t-from)/width).toInt().coerceIn(0,cols-1)
            val acc=buckets.getOrPut(index){Acc()}; acc.count++; acc.first=min(acc.first,s.t); acc.last=max(acc.last,s.t)
            HourStats.parseLevels(s.lvJson) { ch,db -> keys[ch]?.let { acc.values.getOrPut(it){mutableListOf()}.add(db) } }
            acc.values.getOrPut("ref"){mutableListOf()}.add(s.ref)
            s.vibDb?.let { v -> keys[Channels.VIB]?.let { acc.values.getOrPut(it){mutableListOf()}.add(v) } }
        }
        return buckets.map { (i,a) -> buildJsonObject {
            put("t", from + ((i+.5)*width).toLong()); put("from",a.first); put("to",a.last+1); put("coverage",a.count)
            putJsonObject("lv") { for((k,v) in a.values) put(k,10*log10(v.sumOf { 10.0.pow(it/10) }/v.size)) }
            putJsonObject("max") { for((k,v) in a.values) put(k,v.max()) }
            putJsonObject("min") { for((k,v) in a.values) put(k,v.min()) }
        } }
    }
}

/** Read-only projection of existing measurements. No schema migration or invented bins. */
class NightData(private val ctx: Context, private val dao: LfhDao) {
    private val codec=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    private fun config(s: SessionEntity): EngineCfg? = runCatching { codec.decodeFromString<EngineCfg>(s.cfgJson) }.getOrNull()
    private fun key(s: SessionEntity, id: String): String {
        val cfg=config(s)
        if(id==Channels.VIB) return "V_${cfg?.vib?.thr}"
        val b=cfg?.band(id) ?: return "${s.id}_$id"
        return "${b.id}_${b.center}_${b.width}_${b.thr}"
    }
    private fun sessions(w: NightWindow): List<SessionEntity> = runBlocking { dao.sessionsInRange(w.from*1000,w.to*1000) }.sortedBy { it.startedAt }
    private fun events(s: SessionEntity,w: NightWindow): List<EventEntity> = runBlocking { dao.eventsInRange(s.id,w.from,w.to) }
    private fun gaps(ss: List<SessionEntity>,w: NightWindow): List<Pair<Long,Long>> {
        val explicit=ss.flatMap { s -> events(s,w).filter { it.band==Channels.GAP }.map { it.startT to it.endT } }.toMutableList()
        for(i in 1 until ss.size) {
            val end=ss[i-1].endedAt?.div(1000) ?: continue
            val start=ss[i].startedAt/1000
            if(start>end+1) explicit.add(end to start)
        }
        return NightMath.union(explicit,w.from,min(w.to,System.currentTimeMillis()/1000))
    }
    private fun measuredEnd(s: SessionEntity,w: NightWindow,st: MonitorBus.State): Long {
        val last=st.lastDataAt.takeIf { it>0 }?.div(1000) ?: (s.lastT+1)
        return min(min(System.currentTimeMillis()/1000,w.to),last)
    }
    private fun allEvents(ss: List<SessionEntity>,w: NightWindow): JsonArray = buildJsonArray {
        for(s in ss) {
            for(e in events(s,w).filter { it.band!=Channels.GAP }) add(buildJsonObject {
                put("id",e.id); put("sessionId",s.id); put("band",key(s,e.band)); put("bandId",e.band)
                put("startT",max(w.from,e.startT)); put("endT",min(w.to,e.endT)); put("kind",e.kind)
                e.peakDb?.takeIf { it.isFinite() }?.let { put("peakDb",it) }
            })
            val st=MonitorBus.state.value
            if(st.running && st.mode=="rec" && st.sessionId==s.id) for((id,start) in st.activeBands) {
                val end=measuredEnd(s,w,st)
                if(start<end && end>w.from) add(buildJsonObject {
                    put("id","active_${s.id}_$id"); put("sessionId",s.id); put("band",key(s,id)); put("bandId",id)
                    put("startT",max(w.from,start)); put("endT",end); put("active",true); put("kind","steady")
                    st.levels[id]?.takeIf { it.isFinite() }?.let { put("peakDb",it) }
                })
            }
        }
    }
    private fun summary(w: NightWindow,ss: List<SessionEntity>): JsonObject {
        val ev=allEvents(ss,w)
        val gap=gaps(ss,w)
        val audio=ev.map { it.jsonObject }.filter { it["bandId"]?.jsonPrimitive?.content!=Channels.VIB }
        val intervals=audio.map { it["startT"]!!.jsonPrimitive.long to it["endT"]!!.jsonPrimitive.long }
        val measuredIntervals=intervals.flatMap { NightMath.subtractGaps(it.first,it.second,gap) }
        val hours=mutableListOf<Pair<Long,Double?>>()
        var h=w.from
        while(h<w.to) { hours.add(h to null); h=min(w.to,h+3600) }
        var count=0L
        var peak: Double?=audio.mapNotNull { it["peakDb"]?.jsonPrimitive?.doubleOrNull }.maxOrNull()
        for(s in ss) {
            count += runBlocking { dao.sampleCountInRange(s.id,w.from,w.to) }
            val cfg=config(s)
            val grid=runBlocking { HourStats.load(ctx,dao,s) }
            for((t,levels) in grid.hours) {
                // Cached hourly maxima are safe only for a complete hour of the selected window.
                if(t<w.from || t>=w.to) continue
                val enabled=cfg?.enabledBands()?.map { it.id }?.toSet() ?: emptySet()
                val over=levels.filterKeys { it in enabled }.values.maxOrNull()?.toDouble() ?: continue
                val i=hours.indexOfFirst { t>=it.first && t<min(it.first+3600,w.to) }
                if(i>=0) hours[i]=hours[i].first to max(hours[i].second ?: -1e6,over)
                for((id,v) in levels) if(id in enabled) cfg?.band(id)?.let { b -> peak=max(peak ?: -1e6,v+b.thr) }
            }
        }
        return buildJsonObject {
            put("date",w.date.toString()); put("from",w.from); put("to",w.to); put("recorded",count>0)
            put("comparisonKey", ss.map { session ->
                val device = runCatching { codec.parseToJsonElement(session.deviceJson).jsonObject.filterKeys { it != "app_version" } }.getOrDefault(emptyMap())
                listOf(session.cfgJson, session.audioSource, session.contextJson, JsonObject(device).toString()).joinToString("|")
            }.distinct().sorted().joinToString("||"))
            put("coverageSeconds",count); put("noiseSeconds",NightMath.unionDuration(measuredIntervals,w.from,w.to))
            put("eventsCount",audio.size); put("gapSeconds",gap.sumOf { it.second-it.first }); put("gapCount",gap.size)
            intervals.minOfOrNull { it.first }?.let { put("noiseStart",it) }
            intervals.maxOfOrNull { it.second }?.let { put("noiseEnd",it) }
            peak?.let { put("peak",it) }
            put("live",MonitorBus.state.value.let { it.running && it.mode=="rec" && ss.any { s->s.id==it.sessionId } && System.currentTimeMillis()/1000<w.to })
            putJsonArray("sessionIds") { ss.forEach { add(JsonPrimitive(it.id)) } }
            putJsonArray("hours") { hours.forEach { (t,v)->add(buildJsonObject { put("t",t); put("value",v?.let(::JsonPrimitive) ?: JsonNull) }) } }
        }
    }
    @Synchronized fun summaries(anchor: LocalDate,count: Int): List<NightSummary> = (0 until count.coerceIn(1,16)).map { i ->
        val w=NightWindow.forDate(anchor.minusDays(i.toLong())); summary(w,sessions(w))
    }
    @Synchronized fun load(date: LocalDate): NightPayload {
        val w=NightWindow.forDate(date); val ss=sessions(w); val st=MonitorBus.state.value
        return buildJsonObject {
            put("date",date.toString()); put("from",w.from); put("to",w.to); put("timezone",ZoneId.systemDefault().id)
            put("summary",summary(w,ss)); put("events",allEvents(ss,w))
            putJsonObject("encoding") { put("fmin",20);put("fmax",200);put("bins",64);put("seconds",30);put("minDb",-110);put("maxDb",-20) }
            val channels=linkedMapOf<String,JsonObject>()
            for(s in ss) config(s)?.let { cfg ->
                for(b in cfg.enabledBands()) channels[key(s,b.id)]=buildJsonObject {
                    put("key",key(s,b.id));put("id",b.id);put("center",b.center);put("width",b.width);put("thr",b.thr);put("label",b.label);put("unit","dBFS")
                }
                if(cfg.vib.enabled) channels[key(s,Channels.VIB)]=buildJsonObject {
                    put("key",key(s,Channels.VIB));put("id",Channels.VIB);put("thr",cfg.vib.thr);put("label","Vibraz.");put("unit","dB rel 1 g")
                }
            }
            put("channels",JsonArray(channels.values.toList()))
            putJsonArray("sessions") { for(s in ss) add(buildJsonObject {
                put("id",s.id);put("startedAt",s.startedAt);put("endedAt",s.endedAt?.let(::JsonPrimitive) ?: JsonNull);put("lastT",s.lastT)
                put("cfg",runCatching { codec.parseToJsonElement(s.cfgJson) }.getOrDefault(JsonNull))
                put("live",st.running && st.mode=="rec" && st.sessionId==s.id)
            }) }
            putJsonArray("gaps") { gaps(ss,w).forEach { (a,b)->add(buildJsonObject { put("startT",a);put("endT",b) }) } }
            putJsonArray("slices") {
                for(s in ss) {
                    var previous=runBlocking { dao.sliceEndBefore(s.id,w.from) } ?: (s.startedAt/1000)
                    val sessionGaps=events(s,w).filter { it.band==Channels.GAP }
                    for(sl in runBlocking { dao.slicesInRange(s.id,w.from,w.to) }) {
                        val gapEnd=sessionGaps.filter { it.endT<=sl.t }.maxOfOrNull { it.endT } ?: 0L
                        val start=max(max(max(w.from,s.startedAt/1000),sl.t-30),max(previous,gapEnd))
                        val end=min(w.to,sl.t)
                        if(end>start) add(buildJsonObject {
                            put("sessionId",s.id);put("t",sl.t);put("startT",start);put("endT",end);put("b64",Base64.getEncoder().encodeToString(sl.bins))
                        })
                        previous=sl.t
                    }
                }
            }
            putJsonArray("markers") { for(s in ss) for(m in runBlocking { dao.markersInRange(s.id,w.from,w.to) }) add(buildJsonObject {
                put("sessionId",s.id);put("t",m.t);put("text",m.origin.removePrefix("nota: "))
            }) }
        }
    }
    /** Events are clipped to the selected night; gap rows retain their unavailable-data meaning. */
    @Synchronized fun eventsCsv(date: LocalDate): String {
        val w=NightWindow.forDate(date)
        val ss=sessions(w)
        val rows=mutableListOf<List<String>>()
        fun row(s: SessionEntity?,id: String,band: String,kind: String,a: Long,b: Long,peak: Double?,active: Boolean=false) {
            val start=max(w.from,a);val end=min(w.to,b)
            if(end<=start)return
            val cfg=s?.let(::config);val channel=cfg?.band(band)
            rows.add(listOf(date.toString(),ZoneId.systemDefault().id,s?.id.orEmpty(),id,band,kind,start.toString(),end.toString(),(end-start).toString(),
                channel?.center?.toString().orEmpty(),channel?.width?.toString().orEmpty(),
                (if(band==Channels.VIB)cfg?.vib?.thr else channel?.thr)?.toString().orEmpty(),
                if(band==Channels.GAP)"" else if(band==Channels.VIB)"dB rel 1 g" else "dBFS",peak?.takeIf { it.isFinite() }?.toString().orEmpty(),active.toString()))
        }
        for(s in ss) {
            for(e in events(s,w)) row(s,e.id,e.band,if(e.band==Channels.GAP)"gap" else e.kind,e.startT,e.endT,e.peakDb)
            val st=MonitorBus.state.value
            if(st.running&&st.mode=="rec"&&st.sessionId==s.id) for((band,start) in st.activeBands)
                row(s,"active_${s.id}_$band",band,"steady",start,measuredEnd(s,w,st),st.levels[band],true)
        }
        for(i in 1 until ss.size) {
            val end=ss[i-1].endedAt?.div(1000)?:continue
            val start=ss[i].startedAt/1000
            if(start>end+1) row(null,"between_${ss[i-1].id}_${ss[i].id}",Channels.GAP,"gap",end,start,null)
        }
        fun csv(v: String)="\""+v.replace("\"","\"\"")+"\""
        return buildString {
            append("date,timezone,sessionId,eventId,band,kind,startEpochS,endEpochS,durationS,centerHz,widthHz,threshold,unit,peak,active\n")
            rows.sortedBy { it[6].toLong() }.forEach { append(it.joinToString(",",transform=::csv));append('\n') }
        }
    }
    @Synchronized fun levels(date: LocalDate,from: Long,to: Long,cols: Int): LevelPayload {
        val w=NightWindow.forDate(date)
        require(from>=w.from && to<=w.to && to>from && cols in 1..2000) { "Intervallo o risoluzione non validi" }
        val ss=sessions(w)
        val points=ss.flatMap { s ->
            val cfg=config(s); val keys=(cfg?.enabledBands()?.map { it.id } ?: emptyList()) + if(cfg?.vib?.enabled==true) listOf(Channels.VIB) else emptyList()
            NightMath.downsample(runBlocking { dao.samplesInRange(s.id,from,to) },keys.associateWith { key(s,it) },from,to,cols)
                .map { JsonObject(it.jsonObject + ("sessionId" to JsonPrimitive(s.id))) }
        }.sortedBy { it["t"]!!.jsonPrimitive.long }
        return buildJsonObject { put("from",from);put("to",to);put("cols",cols);put("points",JsonArray(points)) }
    }
}
