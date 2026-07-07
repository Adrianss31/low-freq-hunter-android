package io.github.adrianss31.lowfreqhunter

import io.github.adrianss31.lowfreqhunter.dsp.Bands
import io.github.adrianss31.lowfreqhunter.dsp.SpectrumAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {

    @Test
    fun tono62HzRilevatoConLivelloCorretto() {
        val sr = 48000
        val n = 16384
        val an = SpectrumAnalyzer(n, sr)
        an.smoothing = 0.0
        val buf = FloatArray(n) { (0.5 * sin(2.0 * PI * 62.0 * it / sr)).toFloat() }
        val spec = an.process(buf)

        val (hz, db) = Bands.dominantHz(spec, an.binHz, 20.0, 200.0)
        assertEquals("frequenza dominante", 62.0, hz, an.binHz)
        // ampiezza 0.5 → −6 dBFS al picco, con scalloping Hann fino a ~−1.4 dB
        assertTrue("picco a $db dB", db > -10.0 && db < -2.0)

        // banda 62±8 piena, banda 100±5 vuota
        val c = Bands.bandDb(spec, an.binHz, 54.0, 70.0)
        val b = Bands.bandDb(spec, an.binHz, 95.0, 105.0)
        assertTrue("banda C a $c dB", c > -10.0 && c < 0.0)
        assertTrue("banda B a $b dB", b < -60.0)
    }

    @Test
    fun silenzioSottoIlPavimento() {
        val an = SpectrumAnalyzer(16384, 48000)
        an.smoothing = 0.0
        val spec = an.process(FloatArray(16384))
        val level = Bands.bandDb(spec, an.binHz, 45.0, 55.0)
        assertTrue("silenzio a $level dB", level < -100.0)
    }

    @Test
    fun dueToniDistinti() {
        val sr = 48000
        val n = 32768
        val an = SpectrumAnalyzer(n, sr)
        an.smoothing = 0.0
        val buf = FloatArray(n) {
            (0.3 * sin(2.0 * PI * 50.0 * it / sr) + 0.1 * sin(2.0 * PI * 100.0 * it / sr)).toFloat()
        }
        val spec = an.process(buf)
        val a = Bands.bandDb(spec, an.binHz, 45.0, 55.0)
        val b = Bands.bandDb(spec, an.binHz, 95.0, 105.0)
        // 0.3 → ~−10.5 dB, 0.1 → ~−20 dB: entrambi presenti, A ~9.5 dB sopra B
        assertTrue("A=$a", a > -14.0 && a < -6.0)
        assertTrue("B=$b", b > -24.0 && b < -16.0)
        assertEquals("differenza A−B", 9.5, a - b, 2.0)
    }
}
