package io.github.adrianss31.lowfreqhunter

import io.github.adrianss31.lowfreqhunter.engine.BandCfg
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.engine.EventData
import io.github.adrianss31.lowfreqhunter.engine.NightEngine
import io.github.adrianss31.lowfreqhunter.engine.SampleData
import io.github.adrianss31.lowfreqhunter.engine.VibCfg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class NightEngineTest {

    private val binHz = 48000.0 / 16384
    private val binCount = 8192

    private class CollectSink : NightEngine.Sink {
        val samples = ArrayList<SampleData>()
        val events = ArrayList<EventData>()
        val slices = ArrayList<Pair<Long, ByteArray>>()
        val starts = ArrayList<Pair<String, Long>>()
        override fun onSample(s: SampleData) { samples.add(s) }
        override fun onEvent(e: EventData) { events.add(e) }
        override fun onSlice(t: Long, bins: ByteArray) { slices.add(Pair(t, bins)) }
        override fun onEventStart(band: String, startT: Long) { starts.add(Pair(band, startT)) }
    }

    /** Spettro sintetico: fondo −120, picchi (freqHz → dB) su singolo bin. */
    private fun spec(vararg peaks: Pair<Double, Double>): FloatArray {
        val s = FloatArray(binCount) { -120f }
        for ((hz, db) in peaks) {
            s[(hz / binHz).roundToInt()] = db.toFloat()
        }
        return s
    }

    private fun cfg() = EngineCfg(
        bands = listOf(
            BandCfg("A", center = 50.0, width = 5.0, thr = -55.0),
            BandCfg("C", center = 62.0, width = 8.0, thr = -50.0),
        ),
        minOnS = 2, minOffS = 2, hystDb = 3.0,
    )

    @Test
    fun tonoApreEChiudeEventoSullaBandaGiusta() {
        val sink = CollectSink()
        val engine = NightEngine(cfg(), sink)
        engine.start(0)

        // 10 s di tono a 62 Hz @ −30 dB, 4 spettri al secondo
        var ms = 0L
        while (ms < 10_000) {
            engine.processSpectrum(spec(62.0 to -30.0), binHz, ms)
            ms += 250
        }
        // 15 s di silenzio
        while (ms < 25_000) {
            engine.processSpectrum(spec(), binHz, ms)
            ms += 250
        }
        engine.stop(ms)

        // evento solo sulla banda C
        val evC = sink.events.filter { it.band == "C" }
        val evA = sink.events.filter { it.band == "A" }
        val gaps = sink.events.filter { it.band == Channels.GAP }
        assertEquals("un solo evento C: ${sink.events}", 1, evC.size)
        assertEquals(0, evA.size)
        assertEquals(0, gaps.size)

        val ev = evC[0]
        assertTrue("startT=${ev.startT}", ev.startT in 0..1)
        assertTrue("endT=${ev.endT}", ev.endT in 9..12)
        assertEquals(-30.0, ev.peakDb!!, 0.5)
        assertEquals("onEventStart chiamata", listOf("C"), sink.starts.map { it.first })

        // campioni ~1/s con livelli giusti durante il tono
        assertTrue("campioni: ${sink.samples.size}", sink.samples.size in 20..26)
        val during = sink.samples.first { it.t == 5L }
        assertEquals(-30.0, during.lv["C"]!!, 1.0)
        assertTrue("A silente: ${during.lv["A"]}", during.lv["A"]!! < -100.0)
        assertEquals(62.0, during.domHz, binHz)
        // ref copre 20–500: col tono presente è ~−30
        assertEquals(-30.0, during.ref, 1.0)
    }

    @Test
    fun sliceOgni30SecondiConFirmaSpettrale() {
        val sink = CollectSink()
        val engine = NightEngine(cfg(), sink)
        engine.start(0)
        var ms = 0L
        while (ms < 65_000) {
            engine.processSpectrum(spec(62.0 to -30.0), binHz, ms)
            ms += 250
        }
        engine.stop(ms)
        assertTrue("slices: ${sink.slices.size}", sink.slices.size in 2..4)
        val bins = sink.slices[0].second
        assertEquals(NightEngine.WF_NBINS, bins.size)
        // bin del waterfall che contiene 62 Hz: (62−20)/180*64 ≈ 14
        val hot = bins[14].toInt() and 0xFF
        val cold = bins[50].toInt() and 0xFF
        assertTrue("hot=$hot cold=$cold", hot > cold + 40)
    }

    @Test
    fun bucoDiMonitoraggioRegistratoComeGap() {
        val sink = CollectSink()
        val engine = NightEngine(cfg(), sink)
        engine.start(0)
        var ms = 0L
        while (ms <= 3_750) {
            engine.processSpectrum(spec(), binHz, ms)
            ms += 250
        }
        // salto di 12 s (es. processo congelato)
        ms = 16_000
        while (ms < 20_000) {
            engine.processSpectrum(spec(), binHz, ms)
            ms += 250
        }
        engine.stop(ms)
        val gaps = sink.events.filter { it.band == Channels.GAP }
        assertEquals(1, gaps.size)
        assertEquals(3L, gaps[0].startT)
        assertEquals(16L, gaps[0].endT)
    }

    @Test
    fun canaleVibrazioniIndipendente() {
        val sink = CollectSink()
        val engine = NightEngine(
            cfg().copy(vib = VibCfg(enabled = true, thr = -40.0)),
            sink,
        )
        engine.start(0)
        engine.vibDb = -20.0
        var ms = 0L
        while (ms < 8_000) {
            engine.processSpectrum(spec(), binHz, ms)
            ms += 250
        }
        engine.vibDb = -80.0
        while (ms < 16_000) {
            engine.processSpectrum(spec(), binHz, ms)
            ms += 250
        }
        engine.stop(ms)

        val evV = sink.events.filter { it.band == Channels.VIB }
        assertEquals("eventi V: ${sink.events}", 1, evV.size)
        assertEquals(-20.0, evV[0].peakDb!!, 0.5)
        // i campioni portano il livello V
        assertEquals(-20.0, sink.samples.first { it.t == 4L }.vibDb!!, 0.5)
        assertEquals(-80.0, sink.samples.first { it.t == 12L }.vibDb!!, 0.5)
    }
}
