package io.github.adrianss31.lowfreqhunter

import io.github.adrianss31.lowfreqhunter.dsp.ControfaseEngine
import io.github.adrianss31.lowfreqhunter.dsp.TonePhaseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ControfaseEngineTest {

    private fun runSeconds(engine: ControfaseEngine, seconds: Double) {
        val frame = 512
        val out = FloatArray(frame)
        val totalSamples = (seconds * engine.sampleRate).toInt()
        var done = 0
        while (done < totalSamples) {
            val n = minOf(frame, totalSamples - done)
            engine.processBlock(null, n, out)
            done += n
        }
    }

    @Test
    fun scansioneTrovaPicchiSenzaAggiungereToni() {
        val engine = ControfaseEngine()
        engine.setParams(simMode = true)
        engine.setRunning(true, "SIMULAZIONE")
        engine.startScan(4.0)
        runSeconds(engine, 5.0)

        val snap = engine.snapshot()
        assertTrue("nessun tono aggiunto in automatico", snap.channels.isEmpty())
        assertTrue("almeno 2 picchi trovati: ${snap.peaks}", snap.peaks.size >= 2)
        assertTrue("100 Hz trovato", snap.peaks.any { abs(it.freq - 100.037) < 1.0 })
        assertTrue("64 Hz trovato", snap.peaks.any { abs(it.freq - 64.21) < 1.0 })
    }

    @Test
    fun calibrazioneECancellazioneConvergono() {
        val engine = ControfaseEngine()
        engine.setParams(simMode = true)
        engine.setRunning(true, "SIMULAZIONE")
        val id = engine.addChannel(100.25)
        assertNotNull(id)

        engine.startSelected()
        runSeconds(engine, 55.0)

        val snap = engine.snapshot()
        val ch = snap.channels.single()
        assertEquals(TonePhaseState.LOCKED, ch.state)
        val red = ch.reductionDb ?: 0.0
        assertTrue("riduzione attesa > 30 dB, ottenuta $red", red > 30.0)
    }

    @Test
    fun immuniARafficaRumoreBandaLarga() {
        val engine = ControfaseEngine()
        engine.setParams(simMode = true)
        engine.setRunning(true, "SIMULAZIONE")
        engine.addChannel(100.2)
        engine.startSelected()
        runSeconds(engine, 55.0)

        assertEquals(TonePhaseState.LOCKED, engine.snapshot().channels.single().state)

        // Raffica di rumore a banda larga +26 dB (es. tastiera / voce) per 8 s
        engine.setSimNoiseDb(-36.0)
        runSeconds(engine, 8.0)
        val stateDuring = engine.snapshot().channels.single().state
        assertEquals("non deve sbloccarsi durante il rumore esterno", TonePhaseState.LOCKED, stateDuring)

        // Torna silenzio
        engine.setSimNoiseDb(-62.0)
        runSeconds(engine, 6.0)
        val after = engine.snapshot().channels.single()
        assertEquals(TonePhaseState.LOCKED, after.state)
        assertTrue("riduzione mantenuta > 30 dB, ottenuta ${after.reductionDb}", (after.reductionDb ?: 0.0) > 30.0)
    }

    @Test
    fun tettoTotaleMultiTono() {
        val engine = ControfaseEngine()
        engine.setParams(maxAmpDb = 0.0)
        val ids = listOf(60.0, 90.0, 120.0, 150.0).mapNotNull { engine.addChannel(it) }
        assertEquals(4, ids.size)
        for (id in ids) engine.setManualW(id, 0.0, 0.0)
        engine.setOutput(true)

        val out = FloatArray(512)
        engine.processBlock(FloatArray(512), 512, out)
        val peak = out.maxOf { abs(it) }
        assertTrue("uscita protetta dal clipping: peak=$peak", peak <= 0.99f)
    }

    @Test
    fun resisteADerivaClockEAltaLatenza() {
        val engine = ControfaseEngine()
        engine.setParams(simMode = true)
        engine.setSimPath(delayMs = 150.0, clockSkewPpm = 30.0)
        engine.setRunning(true, "SIMULAZIONE")
        engine.addChannel(100.2)
        engine.startSelected()

        // 6 minuti con skew di 30 ppm (~390° di rotazione completa di S) e 150 ms di latenza
        runSeconds(engine, 360.0)

        val ch = engine.snapshot().channels.single()
        assertTrue("cancellazione attiva senza bloccarsi: state=${ch.state}", ch.state != TonePhaseState.FAILED)
        val red = ch.reductionDb ?: 0.0
        assertTrue("riduzione attesa > 20 dB nonostante deriva clock, ottenuta $red", red > 20.0)
    }

    @Test
    fun recuperaDaSaltoDiFaseImprovviso() {
        val engine = ControfaseEngine()
        engine.setParams(simMode = true)
        engine.setSimPath(delayMs = 120.0, clockSkewPpm = 0.0)
        engine.setRunning(true, "SIMULAZIONE")
        engine.addChannel(100.2)
        engine.startSelected()
        runSeconds(engine, 55.0)
        assertEquals(TonePhaseState.LOCKED, engine.snapshot().channels.single().state)

        // Salto di fase improvviso di ~140° a 100 Hz (3,9 ms) -> ricalibrazione automatica e recupero
        engine.shiftSimDelayMs(3.9)
        runSeconds(engine, 60.0)

        val ch = engine.snapshot().channels.single()
        val red = ch.reductionDb ?: 0.0
        assertTrue("riduzione attesa > 25 dB dopo recupero, ottenuta $red", red > 25.0)
    }
}
