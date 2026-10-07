package io.github.adrianss31.lowfreqhunter

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fi.iki.elonen.NanoHTTPD
import io.github.adrianss31.lowfreqhunter.data.LfhDb
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.server.LanServer
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.runBlocking
import io.github.adrianss31.lowfreqhunter.data.*
import java.time.LocalDate
import io.github.adrianss31.lowfreqhunter.server.NightWindow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class LanServerTest {
    private fun request(path: String, p: Map<String,String> = mapOf("k" to "test-key")): NanoHTTPD.IHTTPSession =
        Proxy.newProxyInstance(javaClass.classLoader,arrayOf(NanoHTTPD.IHTTPSession::class.java)) { _,method,_ ->
            when(method.name) { "getUri"->path; "getParms"->p; "getMethod"->NanoHTTPD.Method.GET; "getHeaders"->emptyMap<String,String>(); else->null }
        } as NanoHTTPD.IHTTPSession
    @Test fun nightEndpointsValidateTokenAndRanges() {
        val ctx=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(ctx,LfhDb::class.java).build()
        try {
            val server=LanServer(ctx,db.dao(),"test-key",0,{EngineCfg()})
            assertEquals(401,server.serve(request("/api/nights",emptyMap())).status.requestStatus)
            assertEquals(400,server.serve(request("/api/night",mapOf("k" to "test-key","date" to "bad"))).status.requestStatus)
            assertEquals(400,server.serve(request("/api/night/levels",mapOf("k" to "test-key","date" to "2026-10-03","from" to "2","to" to "1","cols" to "9000"))).status.requestStatus)
            val response=server.serve(request("/api/nights",mapOf("k" to "test-key","anchor" to "2026-10-03","count" to "2")))
            assertEquals(200,response.status.requestStatus)
            assertEquals(2,Json.parseToJsonElement(response.data.bufferedReader().readText()).jsonArray.size)
        } finally { db.close() }
    }
    @Test fun httpResponseTimeDoesNotPretendMicrophoneIsFresh() {
        val ctx=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(ctx,LfhDb::class.java).build()
        try {
            MonitorBus.spectrum.value=MonitorBus.SpectrumFrame(floatArrayOf(-80f),1.0,123000)
            val response=LanServer(ctx,db.dao(),"test-key",0,{EngineCfg()}).serve(request("/api/state"))
            val data=Json.parseToJsonElement(response.data.bufferedReader().readText()).jsonObject
            assertEquals(123000L,data["lastDataAt"]!!.jsonPrimitive.long)
            assertTrue(data["now"]!!.jsonPrimitive.long>123000L)
            assertNotNull(data["timezone"])
        } finally { MonitorBus.spectrum.value=null;db.close() }
    }
    @Test fun nightCsvClipsEventsKeepsSessionsAndExportsEmptyNight() = runBlocking {
        val ctx=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(ctx,LfhDb::class.java).build()
        try {
            val w=NightWindow.forDate(LocalDate.parse("2026-10-03"))
            for(id in listOf("first","second")) {
                db.dao().upsertSession(SessionEntity(id,"",(w.from-100)*1000,(w.to+100)*1000,w.to,Json.encodeToString(EngineCfg()),48000,1.46,"MIC"))
                db.dao().insertEvent(EventEntity(id=id+"-event",sessionId=id,band="A",startT=w.from-10,endT=w.from+20,durationS=30,peakDb=-50.0,avgDb=-55.0))
            }
            db.dao().insertEvent(EventEntity(id="gap",sessionId="first",band="gap",startT=w.from+40,endT=w.from+45,durationS=5,peakDb=null,avgDb=null))
            val server=LanServer(ctx,db.dao(),"test-key",0,{EngineCfg()})
            val r=server.serve(request("/api/night/eventi.csv",mapOf("k" to "test-key","date" to w.date.toString())))
            assertEquals(200,r.status.requestStatus);assertTrue(r.mimeType.startsWith("text/csv"))
            val csv=r.data.bufferedReader().readText()
            assertTrue(csv.contains("first"));assertTrue(csv.contains("second"));assertTrue(csv.contains("gap"))
            assertTrue(csv.contains(w.from.toString()));assertFalse(csv.contains((w.from-10).toString()))
            val empty=server.serve(request("/api/night/eventi.csv",mapOf("k" to "test-key","date" to "2026-09-01")))
            assertEquals(1,empty.data.bufferedReader().readLines().size)
        } finally {db.close()}
        Unit
    }
    @Test fun sessionReportIsRealPngAndRequiresToken() = runBlocking {
        val ctx=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(ctx,LfhDb::class.java).build()
        try {
            db.dao().upsertSession(SessionEntity("report","Test",100000,130000,129,Json.encodeToString(EngineCfg()),48000,1.46,"MIC"))
            val server=LanServer(ctx,db.dao(),"test-key",0,{EngineCfg()})
            assertEquals(401,server.serve(request("/api/session/report/report.png",emptyMap())).status.requestStatus)
            val r=server.serve(request("/api/session/report/report.png"))
            assertEquals(200,r.status.requestStatus);assertEquals("image/png",r.mimeType)
            assertArrayEquals(byteArrayOf(-119,80,78,71,13,10,26,10),r.data.readBytes().take(8).toByteArray())
        } finally {db.close()}
        Unit
    }
    @Test fun daytimeIncludesNoonAndClipsExportsAtNineAndTwentyOne() = runBlocking {
        val ctx=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(ctx,LfhDb::class.java).build()
        try {
            val date=LocalDate.parse("2026-10-03")
            val start=date.atTime(9,0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
            val end=date.atTime(21,0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
            val noon=start+3*3600
            db.dao().upsertSession(SessionEntity("daytime","Day",(start-100)*1000,(end+100)*1000,end,Json.encodeToString(EngineCfg()),48000,1.46,"MIC"))
            db.dao().insertSamples(listOf(SampleEntity("daytime",noon,"{\"A\":-40}",-60.0,62.0,null,80)))
            db.dao().insertSlice(SliceEntity("daytime",noon+30,ByteArray(64)))
            db.dao().insertEvent(EventEntity("all-day","daytime","A",start-10,end+10,end-start+20,-40.0,-50.0))
            val server=LanServer(ctx,db.dao(),"test-key",0,{EngineCfg()})
            val args=mapOf("k" to "test-key","date" to date.toString(),"period" to "day")
            val r=server.serve(request("/api/night",args))
            assertEquals(200,r.status.requestStatus)
            val day=Json.parseToJsonElement(r.data.bufferedReader().readText()).jsonObject
            assertTrue("Noon sample must be visible",day["summary"]!!.jsonObject["recorded"]!!.jsonPrimitive.boolean)
            assertEquals(start,day["from"]!!.jsonPrimitive.long);assertEquals(end,day["to"]!!.jsonPrimitive.long)
            assertEquals(1,day["slices"]!!.jsonArray.size)
            val event=day["events"]!!.jsonArray.single().jsonObject
            assertEquals(start,event["startT"]!!.jsonPrimitive.long);assertEquals(end,event["endT"]!!.jsonPrimitive.long)
            val levels=server.serve(request("/api/night/levels",args))
            assertEquals(200,levels.status.requestStatus)
            assertEquals(1,Json.parseToJsonElement(levels.data.bufferedReader().readText()).jsonObject["points"]!!.jsonArray.size)
            val csv=server.serve(request("/api/night/eventi.csv",args)).data.bufferedReader().readText()
            assertTrue(csv.contains("\"$start\",\"$end\""));assertFalse(csv.contains((start-10).toString()))
            val list=server.serve(request("/api/nights",args+mapOf("anchor" to date.toString(),"count" to "2")))
            val summaries=Json.parseToJsonElement(list.data.bufferedReader().readText()).jsonArray
            assertTrue(summaries[0].jsonObject["recorded"]!!.jsonPrimitive.boolean)
            assertFalse(summaries[1].jsonObject["recorded"]!!.jsonPrimitive.boolean)
            // Legacy callers still get a night, and therefore no noon measurements.
            val legacy=server.serve(request("/api/night",args-"period"))
            assertFalse(Json.parseToJsonElement(legacy.data.bufferedReader().readText()).jsonObject["summary"]!!.jsonObject["recorded"]!!.jsonPrimitive.boolean)
        } finally {db.close()}
        Unit
    }
    @Test fun dayWindowUsesPhoneTimezoneAcrossClockChangesAndRejectsUnknownPeriod() {
        val ctx=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(ctx,LfhDb::class.java).build()
        val previous=java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Rome"))
            val server=LanServer(ctx,db.dao(),"test-key",0,{EngineCfg()})
            for((date,hours) in listOf("2026-03-29" to 12,"2026-10-25" to 12)) {
                val r=server.serve(request("/api/night",mapOf("k" to "test-key","date" to date,"period" to "day")))
                val day=Json.parseToJsonElement(r.data.bufferedReader().readText()).jsonObject
                assertEquals(hours*3600L,day["to"]!!.jsonPrimitive.long-day["from"]!!.jsonPrimitive.long)
                assertEquals("Europe/Rome",day["timezone"]!!.jsonPrimitive.content)
            }
            assertEquals(400,server.serve(request("/api/night",mapOf("k" to "test-key","period" to "invalid"))).status.requestStatus)
            assertEquals(401,server.serve(request("/api/night",mapOf("period" to "day"))).status.requestStatus)
        } finally {java.util.TimeZone.setDefault(previous);db.close()}
    }

}
