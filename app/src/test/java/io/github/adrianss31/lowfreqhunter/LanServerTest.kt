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
}
