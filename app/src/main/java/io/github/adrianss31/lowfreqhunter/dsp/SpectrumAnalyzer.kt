package io.github.adrianss31.lowfreqhunter.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/**
 * FFT reale radix-2 con finestra di Hann e smoothing esponenziale per bin
 * (equivalente dello smoothingTimeConstant di AnalyserNode nella PWA).
 *
 * Convenzione dBFS: una sinusoide a fondo scala (ampiezza 1.0) centrata su un
 * bin legge ~0 dBFS — normalizzazione per N, per lo spettro unilatero (x2) e
 * per il guadagno coerente della finestra di Hann (0.5).
 */
class SpectrumAnalyzer(val fftSize: Int, val sampleRate: Int) {
    init {
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) { "fftSize deve essere potenza di 2" }
    }

    val binCount = fftSize / 2
    val binHz = sampleRate.toDouble() / fftSize

    @Volatile
    var smoothing = 0.3

    private val window = DoubleArray(fftSize) { 0.5 * (1.0 - cos(2.0 * PI * it / (fftSize - 1))) }
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val smoothedMag = DoubleArray(binCount)
    private val out = FloatArray(binCount)
    private val norm = 2.0 / (fftSize * 0.5)

    /**
     * Elabora gli ultimi [fftSize] campioni float in [-1,1] e ritorna lo
     * spettro smussato in dBFS (l'array è riusato: copiare se serve tenerlo).
     */
    fun process(samples: FloatArray, offset: Int = 0): FloatArray {
        require(samples.size - offset >= fftSize) { "servono almeno fftSize campioni" }
        for (i in 0 until fftSize) {
            re[i] = samples[offset + i] * window[i]
            im[i] = 0.0
        }
        fft(re, im)
        val s = smoothing
        for (k in 0 until binCount) {
            val mag = hypot(re[k], im[k]) * norm
            smoothedMag[k] = s * smoothedMag[k] + (1.0 - s) * mag
            out[k] = (20.0 * log10(smoothedMag[k] + 1e-12)).toFloat()
        }
        return out
    }

    fun reset() {
        smoothedMag.fill(0.0)
    }

    /** Cooley-Tukey iterativa in-place. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // bit reversal
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var m = n shr 1
            while (j >= m && m > 0) {
                j -= m
                m = m shr 1
            }
            j += m
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val i1 = i + k
                    val i2 = i + k + half
                    val bRe = re[i2] * curRe - im[i2] * curIm
                    val bIm = re[i2] * curIm + im[i2] * curRe
                    re[i2] = re[i1] - bRe
                    im[i2] = im[i1] - bIm
                    re[i1] += bRe
                    im[i1] += bIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
