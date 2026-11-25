package io.github.adrianss31.lowfreqhunter.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class Cx(val re: Double, val im: Double) {
    operator fun plus(b: Cx) = Cx(re + b.re, im + b.im)
    operator fun minus(b: Cx) = Cx(re - b.re, im - b.im)
    operator fun times(b: Cx) = Cx(re * b.re - im * b.im, re * b.im + im * b.re)
    operator fun times(k: Double) = Cx(re * k, im * k)
    operator fun div(b: Cx): Cx {
        val d = b.mag2()
        return if (d > 0.0) Cx((re * b.re + im * b.im) / d, (im * b.re - re * b.im) / d) else ZERO
    }
    fun conj() = Cx(re, -im)
    fun mag() = hypot(re, im)
    fun mag2() = re * re + im * im
    fun ang() = atan2(im, re)

    companion object {
        val ZERO = Cx(0.0, 0.0)
        fun polar(r: Double, theta: Double) = Cx(r * cos(theta), r * sin(theta))
    }
}

enum class TonePhaseState {
    NEW, CALIBRATING, READY, ADAPTING, LOCKED, FAILED
}

data class PeakCandidate(
    val freq: Double,
    val levelDb: Double,
    val promDb: Double,
)

data class ToneSnapshot(
    val id: Int,
    val freq: Double,
    val fint: Double,
    val calFreq: Double,
    val enabled: Boolean,
    val state: TonePhaseState,
    val noisy: Boolean,
    val levelDb: Double?,
    val baselineDb: Double?,
    val reductionDb: Double?,
    val outDb: Double?,
    val phaseDeg: Double,
    val pathDb: Double?,
    val snrDb: Double?,
    val kGain: Double,
    val atLimit: Boolean,
    val weakLowEnd: Boolean,
)

data class ControfaseSnapshot(
    val running: Boolean = false,
    val simMode: Boolean = false,
    val outputOn: Boolean = false,
    val autoOn: Boolean = false,
    val scanning: Boolean = false,
    val calibrating: Boolean = false,
    val guideStep: Int? = null,
    val guideText: String? = null,
    val headline: String = "Pronto a cercare i ronzi",
    val sub: String = "Premi Cerca i toni per analizzare lo spettro 20–500 Hz.",
    val message: String = "",
    val audioSource: String = "",
    val totalDb: Double? = null,
    val react: Int = 40,
    val maxAmpDb: Double = -6.0,
    val testDb: Double = -26.0,
    val minPromDb: Double = 10.0,
    val channels: List<ToneSnapshot> = emptyList(),
    val peaks: List<PeakCandidate> = emptyList(),
    val specBefore: FloatArray? = null,
    val specNow: FloatArray? = null,
    val specBinHz: Double = 48000.0 / 32768.0,
)

/**
 * Motore DSP completo di Controfase in Kotlin puro:
 * - oscillatori ricorsivi + lock-in Hann (blocchi 0,25 s) + sonde rumore a ±8 Hz;
 * - gate anti-rumore a banda larga (tastiera, voce, urti) tramite stima asimmetrica quietN2;
 * - acquisizione separata FLL → PLL conservativo;
 * - calibrazione coerente con compensazione rotazione blocco-blocco;
 * - cadenza assestata a 0,5 s con sonda pilota in quadratura (≤5%) e differenza seconda NLMS
 *   per aggiornamento continuo di S (fase libera, ampiezza ±6 dB) e inseguimento lento di W;
 * - scansione spettro 20–500 Hz senza aggiunta automatica dei toni (scelta manuale utente).
 */
class ControfaseEngine(
    val sampleRate: Int = 48000,
    val fftSize: Int = 32768,
) {
    companion object {
        const val PROBE_HZ = 8.0
        const val T_BLOCK = 0.25
        const val T_CTRL = 2.0 * T_BLOCK
        const val MAX_CH = 6
        const val MIN_SPACING = 6.0
        const val K_CAP = 0.35
        const val K_MIN = 0.025
        const val K_HOLD_BASE = 0.016
        const val K_HOLD_SPAN = 0.028
        const val MAX_TOTAL_AMP = 0.90
        const val DEPTH = 1e-3
        const val CAL_SETTLE = 2
        const val CAL_MEASURE = 8
        private const val TWO_PI = 2.0 * PI
    }

    private data class ProbeSample(val t: Double, val ph: Double, val m: Cx, val u: Cx)

    private class Osc(freq: Double, private val fs: Double) {
        var c = 1.0
        var s = 0.0
        var cd = 1.0
        var sd = 0.0
        var w = 0.0

        init {
            retune(freq)
        }

        fun retune(freq: Double) {
            w = 2.0 * PI * freq / fs
            cd = cos(w)
            sd = sin(w)
        }

        fun step() {
            val t = c * cd - s * sd
            s = s * cd + c * sd
            c = t
        }

        fun normalize() {
            val g = 1.0 / hypot(c, s).coerceAtLeast(1e-12)
            c *= g
            s *= g
        }
    }

    private class Channel(
        val id: Int,
        freqInit: Double,
        fs: Double,
    ) {
        var freq = freqInit
        var fint = freqInit
        var calFreq = freqInit
        var enabled = true
        var state = TonePhaseState.NEW

        var w = Cx.ZERO
        var calW: Cx? = null
        var sPath: Cx? = null
        var sCal: Cx? = null
        var baselineDb: Double? = null

        // Stato worklet campione per campione
        val o = Osc(freqInit, fs)
        val op = Osc(freqInit + PROBE_HZ, fs)
        val om = Osc(freqInit - PROBE_HZ, fs)
        var wr = 0.0
        var wi = 0.0
        var tr = 0.0
        var ti = 0.0
        var aR = 0.0
        var aI = 0.0
        var pR = 0.0
        var pI = 0.0
        var mR = 0.0
        var mI = 0.0
        var ph = 0.0
        var cyc: Long = 0L
        var phMid = 0.0

        // Stato blocco (0.25 s)
        var sigma2: Double? = null
        var quietN2: Double? = null
        var lastN2 = 0.0
        var prevBurst = false
        var noisy = false
        var noisyStreak = 0
        var levelDb: Double? = null
        var lastM = Cx.ZERO
        var weff = Cx.ZERO
        var weffPrev = Cx.ZERO
        var ctrlTick = 0
        var pilotSign = 1.0
        var pilotW = Cx.ZERO
        val probeHist = ArrayList<ProbeSample>(4)
        var prevDh: Cx? = null
        var phRef: Double? = null
        var pllGrace = 0
        var acqOk = 0
        var snrDb: Double? = null
        var rs: Double? = null
        var best: Double? = null
        var stable = 0
        val resid = ArrayList<Double>(84)
        var lockRef: Double? = null
        var divergeN = 0
        var limitN = 0
        var kGain = 0.0
    }

    private class CalSeq(
        val chs: List<Channel>,
        var phase: Int = 0, // 0=off1, 1=on, 2=off2
        var count: Int = 0,
    ) {
        val accOff1 = Array(chs.size) { ArrayList<Cx>() }
        val accOn = Array(chs.size) { ArrayList<Cx>() }
        val accOff2 = Array(chs.size) { ArrayList<Cx>() }
        val noise = Array(chs.size) { ArrayList<Double>() }
    }

    private class SimDist(
        val f0: Double,
        var f: Double,
        var a: Double,
        var ph: Double,
        val wander: Double,
    )

    private class SimEnv(fs: Double) {
        val gain = 0.6
        var noise = 10.0.pow(-62.0 / 20.0)
        val bufSize = 65536
        val buf = DoubleArray(bufSize)
        var wrPos = 0
        var delaySamples = max(1.0, 0.031 * fs)
        var clockSkewPpm = 0.0
        val dists = listOf(
            SimDist(100.037, 100.037, 10.0.pow(-34.0 / 20.0), 1.1, 0.0005),
            SimDist(64.21, 64.21, 10.0.pow(-40.0 / 20.0), 0.3, 0.0005),
        )
        var rng = Random(12345)

        fun gauss(): Double {
            var u = 0.0
            while (u == 0.0) u = rng.nextDouble()
            val v = rng.nextDouble()
            return sqrt(-2.0 * ln(u)) * cos(2.0 * PI * v)
        }

        fun quantum(n: Int, fs: Double) {
            val dt = n.toDouble() / fs
            delaySamples = (delaySamples + clockSkewPpm * 1e-6 * fs * dt)
                .coerceIn(1.0, (bufSize - 4).toDouble())
            for (d in dists) {
                d.f += -0.02 * (d.f - d.f0) * dt + d.wander * sqrt(dt) * gauss()
            }
        }

        fun sample(y: Double, fs: Double): Double {
            var rd = wrPos - delaySamples
            while (rd < 0.0) rd += bufSize
            val i0 = rd.toInt() % bufSize
            val frac = rd - floor(rd)
            val yd = buf[i0] * (1.0 - frac) + buf[(i0 + 1) % bufSize] * frac
            buf[wrPos] = y
            wrPos = (wrPos + 1) % bufSize
            var x = gain * yd
            for (d in dists) {
                d.ph += 2.0 * PI * d.f / fs
                if (d.ph > 2.0 * PI) d.ph -= 2.0 * PI
                x += d.a * cos(d.ph)
            }
            x += noise * gauss()
            return x
        }
    }

    private val lock = Any()
    private val fs = sampleRate.toDouble()
    private val blockLen = (sampleRate * T_BLOCK).roundToInt()
    private val halfBlock = blockLen / 2
    private val win = DoubleArray(blockLen) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / blockLen) }
    private val winSum = win.sum()
    private val smooth = 1.0 - exp(-1.0 / (0.05 * fs))

    private val analyzer = SpectrumAnalyzer(fftSize, sampleRate).apply { smoothing = 0.0 }
    private val fftRing = FloatArray(fftSize)
    private var fftRingPos = 0
    private var fftFilled = 0
    private var fftCounter = 0

    private var idx = 0
    private var accSq = 0.0
    private var sampleCount: Long = 0L
    private var tMid = 0.0
    private var nextId = 1

    private val channels = ArrayList<Channel>()
    private var calSeq: CalSeq? = null

    private var scanNeed = 0
    private var scanCount = 0
    private var scanSum: DoubleArray? = null
    private var scanOnDone: ((List<PeakCandidate>) -> Unit)? = null

    private var waitBlocksRemaining = 0
    private var waitOnDone: (() -> Unit)? = null
    private var pendingGuideRun = false
    private var pendingRecalChannelId: Int? = null
    private val recalLogMs = ArrayList<Long>()

    private var simEnv = SimEnv(fs)

    // Parametri e stato esposto
    private var running = false
    private var simMode = false
    private var outputOn = false
    private var autoOn = false
    private var scanning = false
    private var guideStep: Int? = null
    private var guideText: String? = null
    private var message = ""
    private var audioSource = ""
    private var totalDb: Double? = null
    private var react = 40
    private var maxAmpDb = -6.0
    private var testDb = -26.0
    private var minPromDb = 10.0
    private var peaks: List<PeakCandidate> = emptyList()
    private var specBefore: FloatArray? = null
    private var specNow: FloatArray? = null
    private val maxSpecBins = min(fftSize / 2, ceil(520.0 / analyzer.binHz).toInt() + 1)

    var onSnapshot: ((ControfaseSnapshot) -> Unit)? = null

    private fun maxAmpLin() = undb(maxAmpDb)
    private fun reactFactor() = 2.5 - 2.0 * react / 100.0

    fun snapshot(): ControfaseSnapshot = synchronized(lock) { buildSnapshotLocked() }

    fun setRunning(on: Boolean, sourceName: String = audioSource) = synchronized(lock) {
        running = on
        audioSource = sourceName
        if (!on) {
            calSeq = null
            scanning = false
            pendingGuideRun = false
            pendingRecalChannelId = null
            waitBlocksRemaining = 0
            waitOnDone = null
            guideStep = null
            guideText = null
            autoOn = false
            outputOn = false
            for (ch in channels) {
                ch.calW = null
                ch.pilotW = Cx.ZERO
                ch.probeHist.clear()
                ch.ctrlTick = 0
                if (ch.state == TonePhaseState.CALIBRATING || ch.state == TonePhaseState.ADAPTING || ch.state == TonePhaseState.LOCKED) {
                    ch.state = if (ch.sPath != null) TonePhaseState.READY else TonePhaseState.NEW
                }
                syncChannelTarget(ch)
            }
        }
        emitSnapshotLocked()
    }

    fun setParams(
        react: Int? = null,
        maxAmpDb: Double? = null,
        testDb: Double? = null,
        minPromDb: Double? = null,
        simMode: Boolean? = null,
    ) = synchronized(lock) {
        if (react != null) this.react = react.coerceIn(0, 100)
        if (maxAmpDb != null) this.maxAmpDb = maxAmpDb.coerceIn(-40.0, 0.0)
        if (testDb != null) this.testDb = testDb.coerceIn(-50.0, -6.0)
        if (minPromDb != null) this.minPromDb = minPromDb.coerceIn(6.0, 20.0)
        if (simMode != null && simMode != this.simMode) {
            this.simMode = simMode
            if (simMode) simEnv = SimEnv(fs)
        }
        emitSnapshotLocked()
    }

    fun setSimNoiseDb(noiseDb: Double) = synchronized(lock) {
        simEnv.noise = undb(noiseDb)
    }

    fun setSimPath(delayMs: Double? = null, clockSkewPpm: Double? = null) = synchronized(lock) {
        if (delayMs != null) {
            simEnv.delaySamples = (delayMs * 0.001 * fs).coerceIn(1.0, (simEnv.bufSize - 4).toDouble())
        }
        if (clockSkewPpm != null) {
            simEnv.clockSkewPpm = clockSkewPpm
        }
    }

    fun shiftSimDelayMs(deltaMs: Double) = synchronized(lock) {
        simEnv.delaySamples = (simEnv.delaySamples + deltaMs * 0.001 * fs)
            .coerceIn(1.0, (simEnv.bufSize - 4).toDouble())
    }

    fun resetAudioBuffers() = synchronized(lock) {
        idx = 0
        accSq = 0.0
        fftRing.fill(0f)
        fftRingPos = 0
        fftFilled = 0
        fftCounter = 0
        analyzer.reset()
        for (ch in channels) {
            ch.aR = 0.0; ch.aI = 0.0
            ch.pR = 0.0; ch.pI = 0.0
            ch.mR = 0.0; ch.mI = 0.0
            ch.probeHist.clear()
            ch.ctrlTick = 0
        }
    }

    fun addChannel(freq: Double): Int? = synchronized(lock) {
        if (channels.size >= MAX_CH) {
            message = "Massimo $MAX_CH toni."
            emitSnapshotLocked()
            return null
        }
        val f = freq.coerceIn(15.0, 1000.0)
        val near = channels.firstOrNull { abs(it.freq - f) < MIN_SPACING }
        if (near != null) {
            message = "C'è già un tono a %.2f Hz: servono almeno %.0f Hz di distanza.".format(near.freq, MIN_SPACING)
            emitSnapshotLocked()
            return null
        }
        val rounded = round(f * 1000.0) / 1000.0
        val ch = Channel(nextId++, rounded, fs)
        channels.add(ch)
        syncChannelTarget(ch)
        emitSnapshotLocked()
        return ch.id
    }

    fun removeChannel(id: Int) = synchronized(lock) {
        channels.removeAll { it.id == id }
        if (channels.none { it.enabled }) {
            autoOn = false
            outputOn = false
        }
        emitSnapshotLocked()
    }

    fun setChannelEnabled(id: Int, enabled: Boolean) = synchronized(lock) {
        val ch = channels.firstOrNull { it.id == id } ?: return
        ch.enabled = enabled
        if (!enabled) {
            ch.pilotW = Cx.ZERO
            ch.probeHist.clear()
            ch.ctrlTick = 0
            if (ch.state != TonePhaseState.NEW) ch.state = if (ch.sPath != null) TonePhaseState.READY else TonePhaseState.NEW
        } else if (autoOn && ch.sPath != null) {
            startAdaptingLocked(ch)
        }
        syncChannelTarget(ch)
        emitSnapshotLocked()
    }

    fun setChannelFreq(id: Int, freq: Double) = synchronized(lock) {
        val ch = channels.firstOrNull { it.id == id } ?: return
        val f = freq.coerceIn(15.0, 1000.0)
        ch.fint = f
        retuneChannel(ch, f)
        ch.phRef = null
        ch.prevDh = null
        ch.acqOk = 0
        if (ch.sPath != null && abs(f - ch.calFreq) > 1.0) {
            ch.sPath = null
            ch.state = TonePhaseState.NEW
            message = "Frequenza cambiata oltre 1 Hz: ricalibra questo tono."
        }
        emitSnapshotLocked()
    }

    fun setManualW(id: Int, ampDb: Double, phaseDeg: Double) = synchronized(lock) {
        val ch = channels.firstOrNull { it.id == id } ?: return
        val amp = undb(ampDb.coerceIn(-60.0, 0.0)).coerceAtMost(maxAmpLin())
        val rad = phaseDeg * PI / 180.0
        ch.pilotW = Cx.ZERO
        ch.probeHist.clear()
        ch.w = clampWLocked(ch, Cx.polar(amp, rad))
        syncChannelTarget(ch)
        emitSnapshotLocked()
    }

    fun startScan(seconds: Double = 6.0) = synchronized(lock) {
        if (calSeq != null || pendingGuideRun) return
        scanning = true
        guideStep = null
        guideText = "Ascolto lo spettro (${seconds.toInt()} s)…"
        scanNeed = max(1, (seconds / T_BLOCK).roundToInt())
        scanCount = 0
        scanSum = DoubleArray(maxSpecBins)
        scanOnDone = { found ->
            scanning = false
            guideText = null
            message = if (found.isNotEmpty()) {
                "Trovati ${found.size} toni: spunta solo quelli che vuoi cancellare, poi premi Calibra e cancella."
            } else {
                "Nessun tono evidente. Abbassa la sensibilità rilevamento o aggiungi un tono a mano."
            }
        }
        emitSnapshotLocked()
    }

    fun startSelected() = synchronized(lock) {
        if (calSeq != null || pendingGuideRun) return
        val sel = channels.filter { it.enabled }
        if (sel.isEmpty()) {
            startScan(6.0)
            return
        }
        if (sel.all { it.sPath != null } && !autoOn) {
            setAutoLocked(true)
            emitSnapshotLocked()
            return
        }
        pendingGuideRun = true
        setAutoLocked(false)
        setOutputLocked(false)
        guideStep = 1
        guideText = "Misuro la frequenza esatta di ${sel.joinToString(", ") { "%.1f Hz".format(it.freq) }} (12 s)…"
        for (ch in sel) {
            ch.phRef = null
            ch.prevDh = null
            ch.acqOk = 0
            ch.fint = ch.freq
            ch.calFreq = ch.freq
            ch.sPath = null
            ch.state = TonePhaseState.NEW
            syncChannelTarget(ch)
        }
        waitBlocksRemaining = 48
        waitOnDone = {
            val chs = channels.filter { it.enabled }
            if (chs.isEmpty()) {
                pendingGuideRun = false
                guideStep = null
                guideText = null
                message = "Seleziona almeno un tono dallo spettro o aggiungilo a mano."
            } else {
                guideStep = 2
                guideText = "Un breve tono di prova per ogni ronzio (8 s)…"
                startCalibrationLocked(chs) { ok ->
                    pendingGuideRun = false
                    guideStep = null
                    guideText = null
                    if (ok && channels.any { it.enabled && it.sPath != null }) {
                        setAutoLocked(true)
                        val activeNames = channels.filter { it.enabled && it.sPath != null }
                            .joinToString(", ") { "%.2f Hz".format(it.freq) }
                        message = "Cancellazione avviata su $activeNames."
                    } else {
                        message = "Calibrazione non riuscita: alza il tono di prova o il volume e riprova."
                    }
                }
            }
        }
        emitSnapshotLocked()
    }

    fun calibrateChannels(channelId: Int? = null) = synchronized(lock) {
        if (calSeq != null) return
        val targets = channels.filter { it.enabled && (channelId == null || it.id == channelId) }
        if (targets.isEmpty()) return
        val wasAuto = autoOn
        startCalibrationLocked(targets) {
            if (wasAuto) {
                for (ch in targets) if (ch.enabled && ch.sPath != null) startAdaptingLocked(ch)
            }
        }
        emitSnapshotLocked()
    }

    fun setAuto(on: Boolean) = synchronized(lock) {
        setAutoLocked(on)
        emitSnapshotLocked()
    }

    fun setOutput(on: Boolean) = synchronized(lock) {
        setOutputLocked(on)
        emitSnapshotLocked()
    }

    fun stopCancellation() = synchronized(lock) {
        calSeq = null
        pendingGuideRun = false
        pendingRecalChannelId = null
        waitBlocksRemaining = 0
        waitOnDone = null
        guideStep = null
        guideText = null
        setAutoLocked(false)
        setOutputLocked(false)
        message = "Cancellazione ferma: i toni sono spenti."
        emitSnapshotLocked()
    }

    /**
     * Elabora [count] campioni audio dal microfono (o dalla simulazione interna se [simMode] è attivo),
     * scrivendo l'uscita in controfase in [output] (se non nullo).
     */
    fun processBlock(input: FloatArray?, count: Int, output: FloatArray? = null) {
        synchronized(lock) {
            if (simMode) simEnv.quantum(count, fs)
            for (i in 0 until count) {
                var y = 0.0
                for (k in 0 until channels.size) {
                    val ch = channels[k]
                    ch.wr += (ch.tr - ch.wr) * smooth
                    ch.wi += (ch.ti - ch.wi) * smooth
                    y += ch.wr * ch.o.c - ch.wi * ch.o.s
                }
                val yClip = y.coerceIn(-0.99, 0.99)
                if (output != null) output[i] = yClip.toFloat()

                val x = if (simMode) simEnv.sample(yClip, fs) else (input?.get(i)?.toDouble() ?: 0.0)

                // Ring circolare FFT (O(1) per campione; linearizzato solo 4 volte/s nel flush)
                fftRing[fftRingPos] = x.toFloat()
                fftRingPos = (fftRingPos + 1) % fftSize
                if (fftFilled < fftSize) fftFilled++
                fftCounter++

                val wVal = win[idx]
                val wx = x * wVal
                val mid = idx == halfBlock
                if (mid) tMid = sampleCount.toDouble() / fs
                accSq += x * x
                for (k in 0 until channels.size) {
                    val ch = channels[k]
                    ch.aR += wx * ch.o.c
                    ch.aI -= wx * ch.o.s
                    ch.pR += wx * ch.op.c
                    ch.pI -= wx * ch.op.s
                    ch.mR += wx * ch.om.c
                    ch.mI -= wx * ch.om.s
                    if (mid) ch.phMid = ch.cyc.toDouble() * TWO_PI + ch.ph
                    ch.ph += ch.o.w
                    if (ch.ph >= TWO_PI) {
                        ch.ph -= TWO_PI
                        ch.cyc++
                    }
                    ch.o.step()
                    ch.op.step()
                    ch.om.step()
                }
                sampleCount++
                if (++idx >= blockLen) {
                    flushBlockLocked()
                }
            }
        }
    }

    private fun flushBlockLocked() {
        val kScale = 2.0 / winSum
        totalDb = db(sqrt(accSq / blockLen))
        idx = 0
        accSq = 0.0

        // Aggiorna spettro live da FFT se il ring è pronto (o parzialmente riempito all'avvio)
        if (fftCounter >= blockLen) {
            fftCounter = 0
            val padded = FloatArray(fftSize)
            if (fftFilled == fftSize) {
                val tail = fftSize - fftRingPos
                System.arraycopy(fftRing, fftRingPos, padded, 0, tail)
                if (fftRingPos > 0) System.arraycopy(fftRing, 0, padded, tail, fftRingPos)
            } else if (fftFilled > 0) {
                System.arraycopy(fftRing, 0, padded, fftSize - fftFilled, fftFilled)
            }
            val rawSpec = analyzer.process(padded)
            val bins = min(maxSpecBins, rawSpec.size)
            val prev = specNow
            val cur = FloatArray(bins) { b ->
                val v = if (rawSpec[b].isFinite()) rawSpec[b] else -140f
                if (prev != null && b < prev.size) prev[b] + 0.35f * (v - prev[b]) else v
            }
            specNow = cur
            if (scanning) {
                val sum = scanSum ?: DoubleArray(bins).also { scanSum = it }
                for (b in 0 until min(bins, sum.size)) {
                    val v = if (rawSpec[b].isFinite()) rawSpec[b].toDouble() else -140.0
                    sum[b] += 10.0.pow(v / 10.0)
                }
                if (++scanCount >= scanNeed) {
                    val avgDb = DoubleArray(sum.size) { b -> 10.0 * log10(sum[b] / scanCount + 1e-20) }
                    val found = detectPeaks(avgDb, analyzer.binHz, minPromDb)
                    peaks = found
                    if (!outputOn) {
                        specBefore = FloatArray(avgDb.size) { b -> avgDb[b].toFloat() }
                    }
                    scanSum = null
                    val cb = scanOnDone
                    scanOnDone = null
                    cb?.invoke(found)
                }
            }
        }

        for (ch in channels) {
            val M = Cx(ch.aR * kScale, ch.aI * kScale)
            val pr = ch.pR * kScale
            val pi = ch.pI * kScale
            val mr = ch.mR * kScale
            val mi = ch.mI * kScale
            ch.aR = 0.0; ch.aI = 0.0
            ch.pR = 0.0; ch.pI = 0.0
            ch.mR = 0.0; ch.mI = 0.0
            ch.o.normalize()
            ch.op.normalize()
            ch.om.normalize()

            val others = channels.filter { it !== ch && it.enabled }.map { it.freq }
            val pOk = others.all { abs(it - (ch.freq + PROBE_HZ)) > 7.5 }
            val mOk = others.all { abs(it - (ch.freq - PROBE_HZ)) > 7.5 }
            val pp = pr * pr + pi * pi
            val mm = mr * mr + mi * mi
            val n2 = when {
                pOk && mOk -> (pp + mm) / 2.0
                pOk -> pp
                mOk -> mm
                else -> min(pp, mm)
            }
            ch.lastN2 = n2
            val q0 = ch.quietN2
            ch.quietN2 = if (q0 == null) n2 else q0 + (if (n2 < q0) 0.05 else 0.01) * (n2 - q0)
            val qFloor = max(ch.quietN2 ?: 0.0, 1e-16)
            val softBurst = n2 > 3.5 * qFloor
            val burst = n2 > 6.5 * qFloor || (softBurst && ch.prevBurst)
            ch.prevBurst = softBurst
            val sRef = max(ch.sigma2 ?: 0.0, qFloor)
            val m2 = M.mag2()
            // Un picco a banda stretta (tono che diverge) non è rumore esterno a banda larga
            val isNarrowband = m2 > 16.0 * max(n2, 1e-16)
            ch.noisy = calSeq == null && !isNarrowband && (burst || (n2 > 2.2 * qFloor && m2 > 3.0 * sRef && m2 < 3.5 * max(n2, 1e-16)))
            ch.noisyStreak = if (ch.noisy) ch.noisyStreak + 1 else 0

            if (!ch.noisy) {
                val s0 = ch.sigma2
                ch.sigma2 = if (s0 == null) n2 else s0 + 0.08 * (n2 - s0)
                val lvl = db(sqrt(m2))
                val l0 = ch.levelDb
                ch.levelDb = if (l0 == null) lvl else l0 + 0.3 * (lvl - l0)
            }
            ch.lastM = M
            ch.weffPrev = ch.weff
            ch.weff = effectiveW(ch)
        }

        val cal = calSeq
        if (cal != null) {
            calTickLocked(cal)
        } else {
            for (ch in channels.toList()) {
                stepChannelLocked(ch)
            }
        }

        if (waitBlocksRemaining > 0) {
            waitBlocksRemaining--
            if (waitBlocksRemaining == 0) {
                val cb = waitOnDone
                waitOnDone = null
                cb?.invoke()
            }
        }

        emitSnapshotLocked()
    }

    private fun stepChannelLocked(ch: Channel) {
        val M = ch.lastM
        val s2 = max(ch.sigma2 ?: 0.0, 1e-16)
        val weff = ch.weff
        val known = ch.sPath != null
        val silent = weff.mag2() == 0.0
        val activeCtrl = autoOn && known && ch.enabled &&
            (ch.state == TonePhaseState.ADAPTING || ch.state == TonePhaseState.LOCKED)

        var isSettle = false
        if (activeCtrl) {
            if (ch.noisy) {
                ch.probeHist.clear()
                if (ch.divergeN > 0) ch.divergeN--
                if (abs(ch.freq - ch.fint) > 1e-9) retuneChannel(ch, ch.fint)
                return
            }
            ch.ctrlTick = (ch.ctrlTick + 1) and 1
            isSettle = ch.ctrlTick == 1
        }

        // 1. Tracciamento continuo di S sui blocchi assestati tramite differenza seconda NLMS in frame uniforme
        if (activeCtrl && !isSettle) {
            if (ch.probeHist.isNotEmpty() && abs(tMid - ch.probeHist.last().t - T_CTRL) > 0.15) {
                ch.probeHist.clear()
            }
            ch.probeHist.add(ProbeSample(tMid, ch.phMid, M, weff))
            if (ch.probeHist.size > 3) ch.probeHist.removeAt(0)
            if (ch.probeHist.size == 3 && ch.phRef != null) {
                val h0 = ch.probeHist[0]
                val h1 = ch.probeHist[1]
                val h2 = ch.probeHist[2]
                val dt02 = h2.t - h0.t
                val frac = if (dt02 > 0.0) (h1.t - h0.t) / dt02 else 0.5
                val dPh = wrapPi(h1.ph - (h0.ph + frac * (h2.ph - h0.ph)))
                val rot = Cx.polar(1.0, dPh)
                val dM = (h0.m * (1.0 - frac) + h2.m * frac) - (h1.m * rot)
                val dU = (h0.u * (1.0 - frac) + h2.u * frac) - (h1.u * rot)
                val dU2 = dU.mag2()
                val sCur = ch.sPath!!
                val sMag2 = max(sCur.mag2(), 1e-12)
                val sigPow = dU2 * sMag2
                val nEff = max(s2, 0.25 * (ch.rs ?: 0.0))
                if (sigPow > 0.35 * nEff && abs(dPh) < 0.35) {
                    val eS = dM - (sCur * dU)
                    if (eS.mag2() < 16.0 * sigPow + 36.0 * nEff) {
                        val mu = if (ch.state == TonePhaseState.ADAPTING) 0.20 else 0.10
                        val reg = 2.5 * nEff / sMag2
                        val sNew = sCur + (eS * dU.conj()) * (mu / (dU2 + reg))
                        val mNew = sNew.mag()
                        val mCal = max((ch.sCal ?: sCur).mag(), 1e-9)
                        if (mNew > 1e-12) {
                            // Limita SOLO il modulo entro ±6 dB da Scal, senza mai bloccare la rotazione di fase
                            ch.sPath = sNew * (mNew.coerceIn(mCal * 0.5, mCal * 2.0) / mNew)
                        }
                    }
                }
            }
        }

        // 2. Stima del disturbo e PLL a 0,25 s con guardia su blocco di assestamento e su disallineamento di S
        val uJumpBig = isSettle && (weff - ch.weffPrev).mag() > 0.08 * max(ch.w.mag(), 1e-6)
        val wPll = if (isSettle) (ch.weffPrev + weff) * 0.5 else weff
        val dh: Cx? = when {
            silent -> M
            known -> M - (ch.sPath!! * wPll)
            else -> null
        }

        val basePow0 = ch.baselineDb?.let { 10.0.pow(it / 10.0) } ?: 0.0
        val instN = max(ch.lastN2, max(s2, 1e-16))
        val dhOk = dh != null && !ch.noisy && !uJumpBig &&
            dh.mag2() > max(4.0 * instN, 0.01 * basePow0) &&
            (basePow0 == 0.0 || dh.mag2() < 6.0 * basePow0) &&
            ch.enabled
        if (dhOk) {
            val snr = db2(dh!!.mag2() / s2)
            ch.snrDb = ch.snrDb?.let { it + 0.1 * (snr - it) } ?: snr
            if (!known && ch.phRef == null) {
                val prev = ch.prevDh
                if (prev != null && prev.mag2() > 4.0 * s2) {
                    val dphi = (dh * prev.conj()).ang()
                    ch.fint = (ch.fint + 0.4 * dphi / (TWO_PI * T_BLOCK))
                        .coerceIn(ch.calFreq - 4.0, ch.calFreq + 4.0)
                    ch.acqOk = if (abs(dphi) < 0.12) ch.acqOk + 1 else 0
                    if (ch.acqOk >= 2) ch.phRef = dh.ang()
                }
                retuneChannel(ch, ch.fint)
            } else {
                if (ch.phRef == null) ch.phRef = dh.ang()
                val e = (dh * Cx.polar(1.0, -ch.phRef!!)).ang()
                val kp = (0.10 + 0.20 * ((ch.snrDb ?: 10.0) - 10.0) / 20.0).coerceIn(0.10, 0.30)
                val ki = if (ch.pllGrace > 0) 0.0 else kp * kp / 4.0
                ch.fint = (ch.fint + ki * e / (TWO_PI * T_BLOCK))
                    .coerceIn(ch.calFreq - 4.0, ch.calFreq + 4.0)
                retuneChannel(ch, ch.fint + kp * e / (TWO_PI * T_BLOCK))
            }
        } else if (abs(ch.freq - ch.fint) > 1e-9) {
            retuneChannel(ch, ch.fint)
        }
        ch.prevDh = dh
        if (ch.pllGrace > 0) ch.pllGrace--

        if (!activeCtrl || isSettle) {
            if (!activeCtrl) ch.probeHist.clear()
            return
        }

        // 3. Residuo pulito (sottratta la sonda pilota) e aggiornamento W
        val mc = M - (ch.sPath!! * ch.pilotW)
        val mc2 = mc.mag2()
        ch.resid.add(mc2)
        if (ch.resid.size > 80) ch.resid.removeAt(0)

        ch.rs = ch.rs?.let { it + 0.35 * (mc2 - it) } ?: mc2
        ch.best = ch.best?.let { min(it * 1.001, ch.rs!!) } ?: ch.rs
        val basePow = ch.baselineDb?.let { 10.0.pow(it / 10.0) } ?: 0.0
        val floor = max(ch.best!!, max(2.0 * s2, basePow * DEPTH))

        fun nextPilot(): Cx {
            ch.pilotSign = -ch.pilotSign
            val wMag = ch.w.mag()
            val sMag = max(ch.sPath!!.mag(), 1e-9)
            if (wMag < 1e-6) return Cx.ZERO
            val nEff = max(s2, 0.25 * (ch.rs ?: 0.0))
            val targetMic = (if (ch.state == TonePhaseState.ADAPTING || mc2 > 4.0 * s2) 1.8 else 1.1) * sqrt(nEff)
            val eps = (targetMic / (wMag * sMag)).coerceIn(0.012, 0.05)
            return Cx(-ch.pilotSign * eps * ch.w.im, ch.pilotSign * eps * ch.w.re)
        }

        if (ch.state == TonePhaseState.LOCKED) {
            if (ch.pllGrace > 0) {
                ch.pilotW = nextPilot()
                syncChannelTarget(ch)
                return
            }
            val recent = if (ch.resid.size >= 4) ch.resid.takeLast(4).average() else ch.resid.average()
            if (recent > 2.5 * max(ch.lockRef ?: 0.0, max(2.0 * s2, basePow * DEPTH))) {
                ch.state = TonePhaseState.ADAPTING
                ch.kGain = K_CAP
                ch.stable = 0
                ch.divergeN = 0
                ch.rs = recent
                ch.best = min(ch.best ?: recent, ch.lockRef ?: recent)
                ch.pilotW = nextPilot()
                syncChannelTarget(ch)
                message = "Tono %.2f Hz: il ronzio è cambiato, riaggancio.".format(ch.freq)
                return
            }
            val kBase = K_HOLD_BASE + K_HOLD_SPAN * (react / 100.0)
            val excess = ((ch.rs!! - s2) / max(ch.rs!!, max(s2, 1e-18))).coerceIn(0.15, 1.0)
            val k = kBase * excess
            ch.w = clampWLocked(ch, ch.w - ((mc / ch.sPath!!) * k))
            ch.pilotW = nextPilot()
            syncChannelTarget(ch)
            return
        }

        ch.kGain = if (ch.rs!! > 4.0 * floor) K_CAP else max(K_MIN, ch.kGain * 0.90)
        val k = ch.kGain
        ch.w = clampWLocked(ch, ch.w - ((mc / ch.sPath!!) * k))
        ch.pilotW = nextPilot()
        syncChannelTarget(ch)

        ch.stable = if (k <= K_MIN * 1.01 && ch.rs!! <= 2.5 * floor) ch.stable + 1 else 0
        if (ch.stable >= (12.0 * reactFactor()).roundToInt()) {
            ch.state = TonePhaseState.LOCKED
            ch.lockRef = max(ch.rs!!, ch.best!!)
        }

        val lvl = ch.levelDb
        val bDb = ch.baselineDb
        ch.divergeN = if (bDb != null && lvl != null && lvl > bDb + 6.0) ch.divergeN + 1 else 0
        if (ch.divergeN > 16) {
            ch.w = Cx.ZERO
            ch.pilotW = Cx.ZERO
            ch.probeHist.clear()
            ch.ctrlTick = 0
            ch.state = TonePhaseState.FAILED
            ch.divergeN = 0
            syncChannelTarget(ch)
            triggerAutoRecalibrateLocked(ch)
        }
    }

    private fun triggerAutoRecalibrateLocked(ch: Channel) {
        val now = System.currentTimeMillis()
        recalLogMs.removeAll { it <= now - 600_000L }
        if (recalLogMs.size >= 3) {
            message = "Tono %.2f Hz: il livello saliva, ricalibrazioni esaurite. Controlla microfono e altoparlante.".format(ch.freq)
            return
        }
        recalLogMs.add(now)
        message = "Tono %.2f Hz: il livello saliva, riaggancio e ricalibro da solo.".format(ch.freq)
        if (calSeq != null || pendingRecalChannelId != null) return
        pendingRecalChannelId = ch.id
        ch.sPath = null
        ch.phRef = null
        ch.prevDh = null
        ch.acqOk = 0
        ch.pilotW = Cx.ZERO
        ch.probeHist.clear()
        ch.ctrlTick = 0
        ch.fint = ch.calFreq
        ch.state = TonePhaseState.NEW
        retuneChannel(ch, ch.calFreq)
        waitBlocksRemaining = 16
        waitOnDone = {
            val target = channels.firstOrNull { it.id == pendingRecalChannelId && it.enabled }
            pendingRecalChannelId = null
            if (target != null && calSeq == null) {
                startCalibrationLocked(listOf(target)) { ok ->
                    if (ok && target.sPath != null && autoOn) startAdaptingLocked(target)
                }
            }
        }
    }

    private var calDoneCb: ((Boolean) -> Unit)? = null

    private fun startCalibrationLocked(chs: List<Channel>, onDone: (Boolean) -> Unit) {
        calDoneCb = onDone
        calSeq = CalSeq(chs)
        for (ch in chs) {
            ch.state = TonePhaseState.CALIBRATING
            ch.calW = Cx.ZERO
            ch.pilotW = Cx.ZERO
            ch.probeHist.clear()
            ch.ctrlTick = 0
            retuneChannel(ch, ch.fint)
            syncChannelTarget(ch)
        }
    }

    private fun calTickLocked(cal: CalSeq) {
        cal.count++
        if (cal.count > CAL_SETTLE) {
            cal.chs.forEachIndexed { idxCh, ch ->
                when (cal.phase) {
                    0 -> { cal.accOff1[idxCh].add(ch.lastM); cal.noise[idxCh].add(ch.sigma2 ?: 0.0) }
                    1 -> cal.accOn[idxCh].add(ch.lastM)
                    else -> { cal.accOff2[idxCh].add(ch.lastM); cal.noise[idxCh].add(ch.sigma2 ?: 0.0) }
                }
            }
        }
        if (cal.count < CAL_SETTLE + CAL_MEASURE) return
        cal.phase++
        cal.count = 0
        if (cal.phase >= 3) {
            finishCalLocked(cal)
            return
        }
        val test = Cx(undb(testDb), 0.0)
        for (ch in cal.chs) {
            ch.calW = if (cal.phase == 1) test else Cx.ZERO
            syncChannelTarget(ch)
        }
    }

    private fun cmean(list: List<Cx>): Cx {
        if (list.isEmpty()) return Cx.ZERO
        var sr = 0.0
        var si = 0.0
        for (c in list) { sr += c.re; si += c.im }
        return Cx(sr / list.size, si / list.size)
    }

    private fun finishCalLocked(cal: CalSeq) {
        calSeq = null
        val test = Cx(undb(testDb), 0.0)
        var okAll = true
        cal.chs.forEachIndexed { idxCh, ch ->
            ch.calW = null
            val off1 = cmean(cal.accOff1[idxCh])
            val on = cmean(cal.accOn[idxCh])
            val off2 = cmean(cal.accOff2[idxCh])
            val nMean = cal.noise[idxCh].takeIf { it.isNotEmpty() }?.average() ?: 1e-18
            val noise = sqrt(max(nMean, 1e-18) / CAL_MEASURE)

            var rotSum = Cx.ZERO
            for (arr in listOf(cal.accOff1[idxCh], cal.accOff2[idxCh])) {
                for (k in 1 until arr.size) rotSum += arr[k] * arr[k - 1].conj()
            }
            val deltaBlk = if (rotSum.mag2() > 0.0) rotSum.ang() else 0.0
            val shift = (CAL_SETTLE + CAL_MEASURE).toDouble()
            val dist = if (off1.mag() > 3.0 * noise && off2.mag() > 3.0 * noise) {
                ((off1 * Cx.polar(1.0, shift * deltaBlk)) + (off2 * Cx.polar(1.0, -shift * deltaBlk))) * 0.5
            } else {
                (off1 + off2) * 0.5
            }
            val delta = on - dist
            if (!(delta.mag() > 4.0 * noise && delta.mag() > 1e-7)) {
                ch.state = TonePhaseState.FAILED
                ch.sPath = null
                okAll = false
                syncChannelTarget(ch)
                return@forEachIndexed
            }
            ch.sPath = delta / test
            ch.sCal = ch.sPath
            ch.calFreq = ch.freq
            ch.fint = (ch.freq + deltaBlk / (2.0 * PI * T_BLOCK)).coerceIn(15.0, 1000.0)
            retuneChannel(ch, ch.fint)
            ch.baselineDb = db(dist.mag())
            val distEnd = dist * Cx.polar(1.0, (shift + (CAL_MEASURE - 1) / 2.0 + 1.0) * deltaBlk)
            ch.w = clampWLocked(ch, (distEnd / ch.sPath!!) * -1.0)
            ch.phRef = null
            ch.prevDh = null
            ch.acqOk = 0
            ch.pilotW = Cx.ZERO
            ch.probeHist.clear()
            ch.ctrlTick = 0
            ch.state = TonePhaseState.READY
            syncChannelTarget(ch)
        }
        message = if (okAll) {
            "Calibrazione riuscita."
        } else {
            "Tono di prova troppo debole al microfono: alza il tono di prova o il volume e ricalibra."
        }
        val cb = calDoneCb
        calDoneCb = null
        cb?.invoke(okAll)
    }

    private fun startAdaptingLocked(ch: Channel) {
        ch.state = TonePhaseState.ADAPTING
        ch.stable = 0
        ch.resid.clear()
        ch.ctrlTick = 0
        ch.pilotSign = 1.0
        ch.pilotW = Cx.ZERO
        ch.probeHist.clear()
        ch.divergeN = 0
        ch.kGain = K_CAP
        ch.rs = null
        ch.best = null
        if (ch.sCal == null) ch.sCal = ch.sPath
    }

    private fun setAutoLocked(on: Boolean) {
        if (on) {
            val ready = channels.filter { it.enabled && it.sPath != null }
            if (ready.isEmpty()) {
                autoOn = false
                message = "Prima serve almeno un tono calibrato."
                return
            }
            autoOn = true
            for (ch in ready) startAdaptingLocked(ch)
            setOutputLocked(true)
            message = "Cancellazione automatica attiva."
        } else {
            autoOn = false
            for (ch in channels) {
                ch.pilotW = Cx.ZERO
                ch.probeHist.clear()
                ch.ctrlTick = 0
                if (ch.state == TonePhaseState.ADAPTING || ch.state == TonePhaseState.LOCKED) {
                    ch.state = if (ch.sPath != null) TonePhaseState.READY else TonePhaseState.NEW
                }
                syncChannelTarget(ch)
            }
        }
    }

    private fun setOutputLocked(on: Boolean) {
        outputOn = on
        if (!on && autoOn) setAutoLocked(false)
        for (ch in channels) syncChannelTarget(ch)
    }

    private fun effectiveW(ch: Channel): Cx {
        ch.calW?.let { return it }
        if (!outputOn || !ch.enabled) return Cx.ZERO
        return if (ch.pilotW.mag2() > 0.0) ch.w + ch.pilotW else ch.w
    }

    private fun syncChannelTarget(ch: Channel) {
        val w = effectiveW(ch)
        ch.tr = w.re
        ch.ti = w.im
    }

    private fun retuneChannel(ch: Channel, freq: Double) {
        val f = freq.coerceIn(15.0, 1000.0)
        ch.freq = f
        ch.o.retune(f)
        ch.op.retune(f + PROBE_HZ)
        ch.om.retune(f - PROBE_HZ)
    }

    private fun clampWLocked(ch: Channel, wIn: Cx): Cx {
        var w = wIn
        val m = w.mag()
        val cap = maxAmpLin()
        if (m > cap) {
            w *= (cap / m)
            ch.limitN++
        } else {
            ch.limitN = 0
        }
        var others = 0.0
        for (c in channels) {
            if (c !== ch && c.enabled && c.calW == null) others += c.w.mag()
        }
        val avail = max(0.05, MAX_TOTAL_AMP - others)
        val m2 = w.mag()
        if (m2 > avail) w *= (avail / m2)
        return w
    }

    fun detectPeaks(specDb: DoubleArray, binHz: Double, minProm: Double = minPromDb): List<PeakCandidate> {
        if (specDb.size < 6 || binHz <= 0.0) return emptyList()
        val k0 = max(3, ceil(20.0 / binHz).toInt())
        val k1 = min(specDb.size - 3, floor(500.0 / binHz).toInt())
        val w = max(4, (12.0 / binHz).roundToInt())
        val raw = ArrayList<PeakCandidate>()
        for (k in k0..k1) {
            val b = specDb[k]
            if (!(b > specDb[k - 1] && b >= specDb[k + 1] && b > specDb[k - 2] && b >= specDb[k + 2])) continue
            val ref = ArrayList<Double>()
            for (j in max(0, k - w)..min(specDb.size - 1, k + w)) {
                if (abs(j - k) > 3) ref.add(specDb[j])
            }
            if (ref.isEmpty()) continue
            ref.sort()
            val prom = b - ref[ref.size / 2]
            if (prom < minProm) continue
            val a = specDb[k - 1]
            val c = specDb[k + 1]
            val den = a - 2.0 * b + c
            val delta = if (den != 0.0) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
            raw.add(PeakCandidate((k + delta) * binHz, b, prom))
        }
        raw.sortByDescending { it.promDb }
        val merged = ArrayList<PeakCandidate>()
        for (p in raw) {
            if (merged.none { abs(it.freq - p.freq) < 3.0 }) merged.add(p)
            if (merged.size >= 8) break
        }
        return merged
    }

    private fun buildSnapshotLocked(): ControfaseSnapshot {
        val enabled = channels.filter { it.enabled }
        val active = enabled.filter { it.sPath != null }
        val locked = active.count { it.state == TonePhaseState.LOCKED }
        val allStable = autoOn && active.isNotEmpty() && locked == active.size
        val (hl, subTxt) = when {
            guideText != null -> guideText!!.removeSuffix("…") to "Resta in silenzio per qualche secondo."
            calSeq != null -> "Calibrazione in corso" to "La cassa emette un breve tono di prova."
            allStable -> "Silenzio attivo" to "${active.size} ${if (active.size == 1) "ronzio cancellato" else "ronzi cancellati"}."
            autoOn -> "In regolazione" to "$locked di ${active.size} toni stabili."
            outputOn && active.isNotEmpty() -> "Toni accesi a mano" to "Controllo automatico spento."
            active.isNotEmpty() && enabled.all { it.sPath != null } -> "Cancellazione ferma" to "Premi Avvia cancellazione o Ricalibra."
            enabled.isNotEmpty() -> "Toni pronti (${enabled.size})" to "Premi Calibra e cancella per avviare."
            peaks.isNotEmpty() -> "Scegli i toni" to "Spunta nello spettro solo i ronzi che vuoi cancellare."
            else -> "Pronto a cercare i ronzi" to "Premi Cerca i toni per ascoltare lo spettro 20–500 Hz."
        }
        val toneSnaps = channels.map { ch ->
            val weff = effectiveW(ch)
            val red = if (ch.baselineDb != null && ch.levelDb != null && outputOn && ch.enabled && ch.w.mag() > 0.0) {
                ch.baselineDb!! - ch.levelDb!!
            } else null
            var phDeg = ch.w.ang() * 180.0 / PI
            if (phDeg < 0.0) phDeg += 360.0
            ToneSnapshot(
                id = ch.id,
                freq = ch.freq,
                fint = ch.fint,
                calFreq = ch.calFreq,
                enabled = ch.enabled,
                state = ch.state,
                noisy = ch.noisyStreak >= 2,
                levelDb = ch.levelDb,
                baselineDb = ch.baselineDb,
                reductionDb = red,
                outDb = if (weff.mag() > 0.0) db(weff.mag()) else null,
                phaseDeg = phDeg,
                pathDb = ch.sPath?.let { db(it.mag()) },
                snrDb = ch.snrDb,
                kGain = ch.kGain,
                atLimit = ch.limitN >= 8 && autoOn,
                weakLowEnd = ch.sPath != null && ch.sPath!!.mag() < 0.02 && ch.freq < 80.0,
            )
        }
        return ControfaseSnapshot(
            running = running,
            simMode = simMode,
            outputOn = outputOn,
            autoOn = autoOn,
            scanning = scanning,
            calibrating = calSeq != null,
            guideStep = guideStep,
            guideText = guideText,
            headline = hl,
            sub = subTxt,
            message = message,
            audioSource = audioSource,
            totalDb = totalDb,
            react = react,
            maxAmpDb = maxAmpDb,
            testDb = testDb,
            minPromDb = minPromDb,
            channels = toneSnaps,
            peaks = peaks,
            specBefore = specBefore?.copyOf(),
            specNow = specNow?.copyOf(),
            specBinHz = analyzer.binHz,
        )
    }

    private fun emitSnapshotLocked() {
        onSnapshot?.invoke(buildSnapshotLocked())
    }

    private fun wrapPi(x: Double) = x - TWO_PI * round(x / TWO_PI)
    private fun db(x: Double) = 20.0 * log10(max(x, 1e-9))
    private fun db2(p: Double) = 10.0 * log10(max(p, 1e-18))
    private fun undb(d: Double) = 10.0.pow(d / 20.0)
}
