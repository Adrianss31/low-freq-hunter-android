package io.github.adrianss31.lowfreqhunter

import io.github.adrianss31.lowfreqhunter.engine.*
import io.github.adrianss31.lowfreqhunter.data.*
import io.github.adrianss31.lowfreqhunter.update.AppUpdater
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.log10

class ReliabilityTest {
    @Test fun initialPeakAndBriefDipsAreIncludedButFinalTailIsExcluded() {
        val sm = EventStateMachine("A")
        val levels = listOf(-20.0, -40.0, -40.0, -60.0, -30.0, -60.0, -60.0, -60.0)
        var event: EventData? = null
        levels.forEachIndexed { t, db -> sm.step(db, -55.0, 3.0, 2, 2, t.toLong())?.let { event = it } }
        assertNotNull(event)
        assertEquals(0L, event!!.startT)
        assertEquals(5L, event!!.endT)
        assertEquals(-20.0, event!!.peakDb!!, 0.001)
        assertEquals(10 * log10((0.01 + .0001 + .0001 + .000001 + .001) / 5), event!!.avgDb!!, 1e-6)
    }

    @Test fun missingSamplesDoNotBecomeSilenceAndMeanUsesPower() {
        assertNull(DossierStats.summarize(emptyList(), -55.0))
        val s = DossierStats.summarize(listOf(-20.0, -40.0), -30.0)!!
        assertEquals(2, s.count); assertEquals(1, s.above)
        assertEquals(10 * log10(.00505), s.meanDb, 1e-8)
        assertEquals(-20.0, s.maxDb, 0.0)
    }

    @Test fun invalidApiSettingsAreRejectedBeforePersistence() {
        AppSettings().validate()
        listOf(
            AppSettings(engine = EngineCfg(fftSize = 8192)),
            AppSettings(engine = EngineCfg(smoothNight = Double.NaN)),
            AppSettings(engine = EngineCfg(clipSeconds = -1)),
            AppSettings(schedule = ScheduleCfg(startMin = 1440)),
        ).forEach { assertTrue(runCatching { it.validate() }.isFailure) }
    }

    @Test fun stableVersionComparisonIsNumericAndRejectsPrereleases() {
        assertTrue(AppUpdater.isNewerTag("v0.14.0", "v0.9.0"))
        assertFalse(AppUpdater.isNewerTag("v0.13.0", "v0.13.0"))
        assertFalse(AppUpdater.isNewerTag("v0.9.0", "v0.13.0"))
        assertFalse(AppUpdater.isNewerTag("v1.0.2-exp", "v0.13.0"))
    }

    @Test fun stopAfterMicrophoneStallRecordsTheTrailingGap() {
        val events = mutableListOf<EventData>()
        val engine = NightEngine(EngineCfg(pulseEnabled = false), object : NightEngine.Sink {
            override fun onSample(s: SampleData) {}
            override fun onSlice(t: Long, bins: ByteArray) {}
            override fun onEvent(e: EventData) { events.add(e) }
        })
        engine.start(1000)
        engine.processSpectrum(FloatArray(8192) { -120f }, 48000.0 / 16384, 1000)
        engine.stop(15000)
        val gap = events.single { it.band == Channels.GAP }
        assertEquals(1L, gap.startT); assertEquals(15L, gap.endT)
    }
}
