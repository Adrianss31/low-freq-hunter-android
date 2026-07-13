package io.github.adrianss31.lowfreqhunter.engine

import io.github.adrianss31.lowfreqhunter.dsp.Bands
import io.github.adrianss31.lowfreqhunter.dsp.MovingMedian
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Motore della sessione di monitoraggio: port 1:1 della logica di js/night.js.
 *
 * Riceve spettri (~4/s) via [processSpectrum], aggrega al secondo (media delle
 * potenze), fa avanzare le macchine a stati per banda, accumula le slice del
 * waterfall (una ogni 30 s, quantizzata a byte) e rileva i gap di
 * monitoraggio (>5 s senza analisi) — che vengono registrati come eventi
 * "gap": per un dato che vuole essere una prova, sapere quando NON si stava
 * misurando conta quanto la misura stessa.
 *
 * Kotlin puro (nessuna dipendenza Android): testabile su JVM con clock finto.
 */
class NightEngine(
    @Volatile var cfg: EngineCfg,
    private val sink: Sink,
) {
    interface Sink {
        fun onSample(s: SampleData)
        fun onEvent(e: EventData)
        fun onSlice(t: Long, bins: ByteArray)
        fun onEventStart(band: String, startT: Long) {}
    }

    companion object {
        const val WF_FMIN = 20.0
        const val WF_FMAX = 200.0
        const val WF_NBINS = 64
        const val Q_MIN = -110.0
        const val Q_MAX = -20.0
        const val SLICE_S = 30L
        const val GAP_S = 5L
        const val REF_LO = 20.0
        const val REF_HI = 500.0
    }

    /** Aggiornati dall'esterno; entrano nei campioni e nel canale V. */
    @Volatile var batteryPct: Int? = null
    @Volatile var vibDb: Double? = null

    private val sms = HashMap<String, EventStateMachine>()

    // Rilevatori di pulsazioni (raffiche brevi, sotto il minOn della SM):
    // lavorano sui livelli istantanei a rate spettro, non sulle medie al secondo.
    private val pulses = HashMap<String, PulseDetector>()
    private var lastTickMs = 0L

    // accumulatore del secondo corrente
    private var curSec = -1L
    private val secPower = HashMap<String, Double>()
    private var secRefPower = 0.0
    private var secN = 0
    private var lastDomHz = 0.0

    // dominante registrata nei campioni: mediana degli ultimi 5 secondi,
    // il valore istantaneo saltava tra bin (e tra fondamentale e armonica)
    private val domMedian = MovingMedian(5)

    // accumulatore slice waterfall
    private val sliceAcc = DoubleArray(WF_NBINS)
    private var sliceCount = 0
    private var lastSliceT = 0L

    var started = false
        private set

    fun start(nowMs: Long) {
        sms.clear()
        pulses.clear()
        for (b in cfg.enabledBands()) sms[b.id] = EventStateMachine(b.id)
        if (cfg.vib.enabled) sms[Channels.VIB] = EventStateMachine(Channels.VIB)
        lastTickMs = 0L
        curSec = -1L
        secPower.clear()
        secRefPower = 0.0
        secN = 0
        sliceAcc.fill(0.0)
        sliceCount = 0
        lastSliceT = nowMs / 1000
        domMedian.reset()
        started = true
    }

    /** Stato per la UI/notifica. */
    fun activeSince(band: String): Long? = sms[band]?.activeSince
    fun isActive(band: String): Boolean = sms[band]?.isActive == true
    fun smState(band: String): EventStateMachine.State? = sms[band]?.state

    fun processSpectrum(spec: FloatArray, binHz: Double, nowMs: Long) {
        if (!started) return
        val nowS = nowMs / 1000

        // Gap di monitoraggio
        if (lastTickMs != 0L && nowMs - lastTickMs > GAP_S * 1000) {
            handleGap(lastTickMs / 1000, nowS)
            curSec = -1L
            secPower.clear()
            secRefPower = 0.0
            secN = 0
        }
        lastTickMs = nowMs

        // Livelli istantanei
        val lv = HashMap<String, Double>()
        for (b in cfg.enabledBands()) {
            lv[b.id] = Bands.bandDb(spec, binHz, b.lo, b.hi)
        }
        val ref = Bands.bandDb(spec, binHz, REF_LO, REF_HI)
        lastDomHz = Bands.dominantHz(spec, binHz, WF_FMIN, WF_FMAX).first

        accumulateSlice(spec, binHz)

        if (cfg.pulseEnabled) {
            val tS = nowMs / 1000.0
            for (b in cfg.enabledBands()) {
                val level = lv[b.id] ?: continue
                val pd = pulses.getOrPut(b.id) { PulseDetector(b.id, cfg.minOnS.toDouble()) }
                pd.step(level, b.thr, cfg.hystDb, tS)?.let { sink.onEvent(it) }
            }
        }

        if (curSec == -1L) curSec = nowS
        if (nowS == curSec) {
            for ((k, v) in lv) secPower[k] = (secPower[k] ?: 0.0) + 10.0.pow(v / 10.0)
            secRefPower += 10.0.pow(ref / 10.0)
            secN++
            return
        }

        // Secondo concluso: aggrega, avanza le SM, emetti campione
        if (secN > 0) {
            finalizeSecond(curSec)
        }
        curSec = nowS
        secPower.clear()
        for ((k, v) in lv) secPower[k] = 10.0.pow(v / 10.0)
        secRefPower = 10.0.pow(ref / 10.0)
        secN = 1
    }

    private fun finalizeSecond(t: Long) {
        val avg = HashMap<String, Double>()
        for ((k, p) in secPower) {
            avg[k] = (10.0 * log10(p / secN + 1e-12) * 100).roundToInt() / 100.0
        }
        val avgRef = 10.0 * log10(secRefPower / secN + 1e-12)

        // Macchine a stati bande audio
        for ((k, level) in avg) {
            val band = cfg.band(k) ?: continue
            val sm = sms.getOrPut(k) { EventStateMachine(k) } // banda attivata a sessione in corso
            sm.step(level, band.thr, cfg.hystDb, cfg.minOnS, cfg.minOffS, t, sink::onEventStart)
                ?.let { sink.onEvent(it) }
        }

        // Canale V (accelerometro): livello fornito dall'esterno
        val vib = if (cfg.vib.enabled) vibDb else null
        if (cfg.vib.enabled && vib != null) {
            val sm = sms.getOrPut(Channels.VIB) { EventStateMachine(Channels.VIB) }
            sm.step(vib, cfg.vib.thr, cfg.hystDb, cfg.minOnS, cfg.minOffS, t, sink::onEventStart)
                ?.let { sink.onEvent(it) }
        }

        val domMed = domMedian.push(lastDomHz)
        sink.onSample(
            SampleData(
                t = t,
                lv = avg,
                ref = (avgRef * 100).roundToInt() / 100.0,
                domHz = (domMed * 10).roundToInt() / 10.0,
                vibDb = vib?.let { (it * 100).roundToInt() / 100.0 },
                battPct = batteryPct,
            )
        )

        if (t - lastSliceT >= SLICE_S) {
            emitSlice(t)
            lastSliceT = t
        }
    }

    private fun accumulateSlice(spec: FloatArray, binHz: Double) {
        val range = WF_FMAX - WF_FMIN
        for (b in 0 until WF_NBINS) {
            val fL = WF_FMIN + (b.toDouble() / WF_NBINS) * range
            val fH = WF_FMIN + ((b + 1).toDouble() / WF_NBINS) * range
            val i0 = max(0, (fL / binHz).roundToInt())
            val i1 = min(spec.size - 1, (fH / binHz).roundToInt())
            var p = 0.0
            var n = 0
            for (i in i0..i1) {
                p += 10.0.pow(spec[i] / 10.0)
                n++
            }
            if (n > 0) sliceAcc[b] += p / n
        }
        sliceCount++
    }

    private fun emitSlice(t: Long) {
        if (sliceCount == 0) return
        val bins = ByteArray(WF_NBINS)
        for (b in 0 until WF_NBINS) {
            val db = 10.0 * log10(sliceAcc[b] / sliceCount + 1e-12)
            val q = ((db - Q_MIN) / (Q_MAX - Q_MIN) * 255).roundToInt().coerceIn(0, 255)
            bins[b] = q.toByte()
        }
        sink.onSlice(t, bins)
        sliceAcc.fill(0.0)
        sliceCount = 0
    }

    private fun handleGap(fromS: Long, toS: Long) {
        // Durante il buco non sappiamo nulla: chiudi gli eventi aperti
        // all'inizio del gap, azzera le SM e registra il gap stesso.
        for (sm in sms.values) {
            sm.forceClose(fromS)?.let { sink.onEvent(it) }
        }
        for (pd in pulses.values) {
            pd.flush()?.let { sink.onEvent(it) }
        }
        sink.onEvent(EventData(Channels.GAP, fromS, toS, toS - fromS, null, null))
    }

    /** Ferma la sessione: chiude eventi aperti ed emette l'ultima slice. */
    fun stop(nowMs: Long) {
        if (!started) return
        val endS = nowMs / 1000
        if (secN > 0 && curSec != -1L) finalizeSecond(curSec)
        for (sm in sms.values) {
            sm.forceClose(endS)?.let { sink.onEvent(it) }
        }
        for (pd in pulses.values) {
            pd.flush()?.let { sink.onEvent(it) }
        }
        emitSlice(endS)
        started = false
    }
}
