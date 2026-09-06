package io.github.adrianss31.lowfreqhunter.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.adrianss31.lowfreqhunter.dsp.SpectrumAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cattura microfono con AudioRecord e produce spettri dBFS ~4/s.
 *
 * Il punto centrale dell'app nativa: la sorgente UNPROCESSED bypassa il
 * filtro passa-alto/AGC che Android applica alla catena "voice" usata dal
 * browser — quello che mangiava ~45 dB a 50–100 Hz nella PWA.
 */
class CaptureEngine(
    context: Context,
    private val fftSize: Int,
    smoothing: Double,
    private val sampleRate: Int = 48000,
) {
    /** Tap opzionale sul PCM grezzo (per le clip WAV degli eventi). */
    @Volatile
    var pcmTap: ((FloatArray, Int) -> Unit)? = null

    @Volatile var onError: ((String) -> Unit)? = null

    /** Ultimo istante (epoch ms) in cui il microfono ha consegnato dati:
     *  il watchdog del servizio lo usa per rilevare una cattura in stallo. */
    @Volatile
    var lastDataMs: Long = System.currentTimeMillis()
        private set

    val sourceName: String
    private val audioSource: Int

    init {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = "true" == am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        if (unprocessed) {
            audioSource = MediaRecorder.AudioSource.UNPROCESSED
            sourceName = "UNPROCESSED"
        } else {
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            sourceName = "VOICE_RECOGNITION"
        }
    }

    private val analyzer = SpectrumAnalyzer(fftSize, sampleRate).also { it.smoothing = smoothing }
    val binHz: Double get() = analyzer.binHz
    val actualSampleRate: Int get() = sampleRate

    @Volatile
    private var record: AudioRecord? = null
    private var job: Job? = null

    // stop() può arrivare da un altro thread mentre il loop di lettura sta
    // ricreando l'AudioRecord dopo un errore: il lock serializza lo scambio
    // del riferimento, `stopped` impedisce di installarne uno nuovo dopo lo stop
    private val lock = Any()

    @Volatile
    private var stopped = false

    var smoothing: Double
        get() = analyzer.smoothing
        set(v) { analyzer.smoothing = v }

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord? {
        val hop = sampleRate / 4 // 250 ms
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuf <= 0) return null
        val rec = try {
            AudioRecord(
                audioSource, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(minBuf, hop * 4 * 2),
            )
        } catch (e: Exception) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
    }

    /**
     * Avvia la cattura; [onSpectrum] è chiamata sul thread di lettura con lo
     * spettro corrente (array riusato — non conservare il riferimento).
     * Ritorna false se il microfono non è disponibile.
     */
    fun start(scope: CoroutineScope, onSpectrum: (spec: FloatArray, binHz: Double, nowMs: Long) -> Unit): Boolean {
        if (job != null) return true
        val hop = sampleRate / 4 // 250 ms
        val rec = createRecord() ?: return false
        stopped = false
        record = rec
        if (runCatching { rec.startRecording(); rec.recordingState == AudioRecord.RECORDSTATE_RECORDING }.getOrDefault(false).not()) {
            rec.release()
            record = null
            return false
        }
        analyzer.reset()
        lastDataMs = System.currentTimeMillis()

        job = scope.launch(Dispatchers.Default) {
            val ring = FloatArray(fftSize)
            val chunk = FloatArray(hop)
            var failures = 0
            var filled = 0
            while (!stopped) {
                val cur = record
                if (cur == null) {
                    delay(1000)
                    val fresh = createRecord() ?: continue
                    synchronized(lock) {
                        if (stopped) fresh.release() else {
                            record = fresh
                            runCatching { fresh.startRecording() }
                        }
                    }
                    continue
                }
                val n = runCatching { cur.read(chunk, 0, hop, AudioRecord.READ_BLOCKING) }.getOrDefault(-1)
                if (stopped) break
                if (n <= 0) {
                    // Errore di lettura (mic conteso, driver in errore): non
                    // morire in silenzio con la notifica ancora su REC —
                    // ricrea l'AudioRecord e riprova, con pausa crescente.
                    failures++
                    ring.fill(0f)
                    filled = 0
                    analyzer.reset()
                    synchronized(lock) { if (record === cur) record = null }
                    runCatching { cur.stop() }
                    runCatching { cur.release() }
                    delay(minOf(failures, 10) * 1000L)
                    if (stopped) break
                    val fresh = createRecord() ?: continue
                    val installed = synchronized(lock) {
                        if (stopped) false else { record = fresh; true }
                    }
                    if (!installed) {
                        fresh.release()
                        break
                    }
                    runCatching { fresh.startRecording() }
                    continue
                }
                failures = 0
                lastDataMs = System.currentTimeMillis()
                runCatching { pcmTap?.invoke(chunk, n) }.onFailure {
                    pcmTap = null
                    onError?.invoke("Scrittura clip non riuscita: ${it.message}")
                }
                // scorri il ring e accoda il nuovo blocco
                val keep = minOf(n, fftSize)
                System.arraycopy(ring, keep, ring, 0, fftSize - keep)
                System.arraycopy(chunk, n - keep, ring, fftSize - keep, keep)
                filled = minOf(fftSize, filled + keep)
                if (filled < fftSize || stopped) continue
                val spec = analyzer.process(ring)
                runCatching { onSpectrum(spec, analyzer.binHz, System.currentTimeMillis()) }.onFailure {
                    onError?.invoke("Analisi interrotta: ${it.message}")
                    stopped = true
                }
            }
        }
        return true
    }

    fun stop() {
        val rec: AudioRecord?
        synchronized(lock) {
            stopped = true
            rec = record
            record = null
        }
        job?.cancel()
        job = null
        if (rec != null) {
            runCatching { rec.stop() }
            rec.release()
        }
        pcmTap = null
    }
}
