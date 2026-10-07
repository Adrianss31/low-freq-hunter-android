package io.github.adrianss31.lowfreqhunter

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.adrianss31.lowfreqhunter.data.*
import io.github.adrianss31.lowfreqhunter.engine.*
import io.github.adrianss31.lowfreqhunter.server.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NightDataTest {
    @Test fun clockChangesUseActualElapsedTime() {
        val z = ZoneId.of("Europe/Rome")
        assertEquals(13 * 3600L, NightWindow.forDate(LocalDate.parse("2026-10-24"), z).let { it.to-it.from })
        assertEquals(11 * 3600L, NightWindow.forDate(LocalDate.parse("2026-03-28"), z).let { it.to-it.from })
    }
    @Test fun dayAndNightAnchorsFollowTheirOwnStartTimes() {
        val zone=ZoneId.of("Europe/Rome")
        for((time,day,night) in listOf(
            Triple("2026-10-07T08:59:59+02:00","2026-10-06","2026-10-06"),
            Triple("2026-10-07T09:00:00+02:00","2026-10-07","2026-10-06"),
            Triple("2026-10-07T20:59:59+02:00","2026-10-07","2026-10-06"),
            Triple("2026-10-07T21:00:00+02:00","2026-10-07","2026-10-07"),
        )) {
            val now=java.time.OffsetDateTime.parse(time).toInstant().toEpochMilli()
            assertEquals(day,NightWindow.latest(now,zone,DashboardPeriod.DAY).toString())
            assertEquals(night,NightWindow.latest(now,zone,DashboardPeriod.NIGHT).toString())
        }
    }
    @Test fun overlappingBandsCountOnceAndClipToWindow() {
        assertEquals(30L, NightMath.unionDuration(listOf(90L to 120L, 110L to 130L, 125L to 140L), 100, 130))
    }
    @Test fun gapsAreRemovedFromMeasuredSliceCoverage() {
        assertEquals(listOf(100L to 110L, 115L to 130L), NightMath.subtractGaps(100, 130, listOf(110L to 115L)))
    }
    @Test fun downsamplingPreservesOneSecondPeakAndDoesNotBridgeGaps() {
        val samples = listOf(
            SampleEntity("s", 100, "{\"A\":-80}", -80.0, 50.0, null, null),
            SampleEntity("s", 101, "{\"A\":-20}", -20.0, 50.0, null, null),
            SampleEntity("s", 150, "{\"A\":-70}", -70.0, 50.0, null, null),
        )
        val out = NightMath.downsample(samples, mapOf("A" to "a"), 100, 200, 2)
        assertEquals(-20.0, out.first().jsonObject["max"]!!.jsonObject["a"]!!.jsonPrimitive.double, 0.001)
        assertTrue(out.first().jsonObject["coverage"]!!.jsonPrimitive.int <= 2)
    }
    @Test fun multipleSessionsKeepOriginalThresholdsAndMissingNightsStayMissing() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, LfhDb::class.java).build()
        try {
            val date = LocalDate.parse("2026-10-03")
            val window = NightWindow.forDate(date, ZoneId.systemDefault())
            for ((id, thr) in listOf("s1" to -55.0, "s2" to -70.0)) {
                val cfg = EngineCfg(bands = listOf(BandCfg("A", center=62.0, width=5.0, thr=thr)))
                val t = window.from + if(id=="s1") 100 else 200
                db.dao().upsertSession(SessionEntity(id, "Notte", t*1000, (t+30)*1000, t+29, Json.encodeToString(cfg),48000,1.46,"MIC"))
                db.dao().insertSamples(listOf(SampleEntity(id,t,"{\"A\":-60}",-70.0,62.0,null,80)))
            }
            val repository = NightData(ctx, db.dao())
            val night = repository.load(date)
            assertEquals(2, night["sessions"]!!.jsonArray.size)
            val thresholds = night["channels"]!!.jsonArray.map { it.jsonObject["thr"]!!.jsonPrimitive.double }.toSet()
            assertEquals(setOf(-55.0,-70.0), thresholds)
            val summaries = repository.summaries(date, 2)
            assertTrue(summaries[0]["recorded"]!!.jsonPrimitive.boolean)
            assertFalse(summaries[1]["recorded"]!!.jsonPrimitive.boolean)
        } finally { db.close() }
        Unit
    }
    @Test fun partialStopAndGapSlicesDoNotOverwritePreviousCoverage() = runBlocking {
        val ctx=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(ctx,LfhDb::class.java).build()
        try {
            val date=LocalDate.parse("2026-10-03");val w=NightWindow.forDate(date)
            db.dao().upsertSession(SessionEntity("partial","",w.from*1000,(w.from+100)*1000,w.from+99,Json.encodeToString(EngineCfg()),48000,1.46,"MIC"))
            for(t in listOf(30L,40L,45L,90L)) db.dao().insertSlice(SliceEntity("partial",w.from+t,ByteArray(64)))
            db.dao().insertEvent(EventEntity("gap","partial",Channels.GAP,w.from+45,w.from+80,35,null,null))
            val slices=NightData(ctx,db.dao()).load(date)["slices"]!!.jsonArray
            assertEquals(listOf(0L,30L,40L,80L),slices.map { it.jsonObject["startT"]!!.jsonPrimitive.long-w.from })
            assertEquals(listOf(30L,40L,45L,90L),slices.map { it.jsonObject["endT"]!!.jsonPrimitive.long-w.from })
        } finally {db.close()}
        Unit
    }
    @Test fun stalledMicrophoneDoesNotExtendOpenEventsOrCsvToWallClock() = runBlocking {
        val ctx=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(ctx,LfhDb::class.java).build()
        try {
            val date=LocalDate.parse("2026-10-03");val w=NightWindow.forDate(date)
            db.dao().upsertSession(SessionEntity("stalled","",w.from*1000,null,w.from+19,Json.encodeToString(EngineCfg()),48000,1.46,"MIC"))
            io.github.adrianss31.lowfreqhunter.service.MonitorBus.state.value=io.github.adrianss31.lowfreqhunter.service.MonitorBus.State(running=true,mode="rec",sessionId="stalled",lastDataAt=(w.from+20)*1000,activeBands=mapOf("A" to w.from+10))
            val repo=NightData(ctx,db.dao());val night=repo.load(date)
            assertEquals(w.from+20,night["events"]!!.jsonArray.single().jsonObject["endT"]!!.jsonPrimitive.long)
            assertEquals(10L,night["summary"]!!.jsonObject["noiseSeconds"]!!.jsonPrimitive.long)
            assertTrue(repo.eventsCsv(date).contains("\"${w.from+20}\""))
        } finally {io.github.adrianss31.lowfreqhunter.service.MonitorBus.state.value=io.github.adrianss31.lowfreqhunter.service.MonitorBus.State();db.close()}
        Unit
    }
}
