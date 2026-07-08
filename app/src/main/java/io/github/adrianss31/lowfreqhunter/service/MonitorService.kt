package io.github.adrianss31.lowfreqhunter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.adrianss31.lowfreqhunter.MainActivity
import io.github.adrianss31.lowfreqhunter.audio.CaptureEngine
import io.github.adrianss31.lowfreqhunter.audio.ClipWriter
import io.github.adrianss31.lowfreqhunter.data.LfhDb
import io.github.adrianss31.lowfreqhunter.data.SessionRecorder
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.dsp.Bands
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.engine.EventData
import io.github.adrianss31.lowfreqhunter.engine.NightEngine
import io.github.adrianss31.lowfreqhunter.engine.SampleData
import io.github.adrianss31.lowfreqhunter.sensor.VibrationEngine
import io.github.adrianss31.lowfreqhunter.ui.fmtClockShort
import io.github.adrianss31.lowfreqhunter.ui.fmtDur
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Foreground service del log notturno: cattura, analizza e salva anche a
 * schermo spento, con notifica persistente informativa.
 *
 * Stabilità (branch hf-stability-watchdog):
 *  - START_STICKY + riavvio automatico se il processo viene ammazzato dal
 *    sistema (altrimenti la sessione restava "aperta" e veniva solo recuperata
 *    in app, perdendo i dati dall'ora del kill in poi).
 *  - Watchdog sulla cattura: se non arrivano spettri per N ms il microfono è
 *    morto in silenzio (Deep Doze / AudioRecord ERROR) e la cattura viene
 *    riavviata senza chiudere la sessione (registra solo un GAP nel motore).
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_START = "io.github.adrianss31.lowfreqhunter.START"
        const val ACTION_STOP = "io.github.adrianss31.lowfreqhunter.STOP"
        private const val CHANNEL_ID = "monitor"
        private const val NOTIF_ID = 1
        private const val TAG = "MonitorService"

        /** Se non arrivano spettri per questo tempo, la cattura è considerata morta. */
        private const val WATCHDOG_TIMEOUT_MS = 15_000L

        /** Gap minimo tra riavvii automatici: evita loop se il microfono non si riapre. */
        private const val AUTO_RESTART_MIN_GAP_MS = 30_000L

        @Volatile
        var instance: MonitorService? = null
            private set

        /** Epoch ms dell'ultimo tentativo di riavvio automatico (anti-loop). */
        @Volatile
        private var lastAutoRestartMs = 0L

        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, MonitorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: android.content.Context) {
            val i = Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }

    private var scopeJob: Job = SupervisorJob()
    private var scope: CoroutineScope = CoroutineScope(scopeJob + Dispatchers.Default)
    private var capture: CaptureEngine? = null
    private var engine: NightEngine? = null
    private var recorder: SessionRecorder? = null
    private var vib: VibrationEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cfg: EngineCfg = EngineCfg()
    private var running = false
    private var clipCount = 0
    private var clipWriter: ClipWriter? = null

    /** Epoch ms dell'ultimo spettro ricevuto (usato dal watchdog). */
    @Volatile
    private var lastSpectrumMs = 0L

    /** Nota transitoria mostrata nella notifica durante un recupero. */
    @Volatile
    private var recoveryNote: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
            null -> {
                // Processo ricreato dal sistema (START_STICKY dopo un kill): riprendi
                // il monitoraggio se eravamo attivi, ma evita un loop se il microfono
                // non si riapre subito (es. un'altra app lo trattiene).
                if (!running) {
                    val now = System.currentTimeMillis()
                    if (now - lastAutoRestartMs >= AUTO_RESTART_MIN_GAP_MS) {
                        lastAutoRestartMs = now
                        Log.w(TAG, "Riavvio automatico dopo kill di sistema")
                        startMonitoring()
                    } else {
                        Log.w(TAG, "Rinuncia a riavvio automatico (troppo frequente)")
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (running) return
        running = true
        instance = this

        // Scope pulito: se il precedente è stato cancellato da stopMonitoring()
        // (stesso processo), ricreane uno attivo per le nuove coroutine.
        if (!scopeJob.isActive) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob + Dispatchers.Default)
        }

        val settings = runBlocking { SettingsRepo.get(this@MonitorService).flow.first() }
        cfg = settings.engine

        createChannel()
        val notif = buildNotification("In avvio…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lfh:monitor").also {
            it.setReferenceCounted(false)
            it.acquire(12 * 60 * 60 * 1000L) // massimo 12 h di sessione
            Log.i(TAG, "WakeLock acquisito (max 12h)")
        }

        val dao = LfhDb.get(this).dao()
        val cap = CaptureEngine(this, cfg.fftSize, cfg.smoothNight)
        capture = cap
        val rec = SessionRecorder(dao, scope, cfg, cap.actualSampleRate, cap.binHz, cap.sourceName)
        recorder = rec

        MonitorBus.resetSession()
        MonitorBus.state.value = MonitorBus.State(
            running = true,
            sessionId = rec.sessionId,
            startedAt = rec.startedAt,
            audioSource = cap.sourceName,
        )

        val sink = object : NightEngine.Sink {
            override fun onSample(s: SampleData) {
                rec.onSample(s)
            }

            override fun onEvent(e: EventData) {
                rec.onEvent(e)
                MonitorBus.events.value = MonitorBus.events.value + e
                updateNotification()
            }

            override fun onSlice(t: Long, bins: ByteArray) {
                rec.onSlice(t, bins)
                MonitorBus.slices.value = MonitorBus.slices.value + Pair(t, bins)
            }

            override fun onEventStart(band: String, startT: Long) {
                maybeStartClip(band, startT)
                updateNotification()
            }
        }

        val eng = NightEngine(cfg, sink)
        engine = eng
        eng.batteryPct = readBattery()
        eng.start(System.currentTimeMillis())

        lastSpectrumMs = System.currentTimeMillis()
        val started = cap.start(scope) { spec, binHz, nowMs ->
            lastSpectrumMs = nowMs
            eng.processSpectrum(spec, binHz, nowMs)
            publishLive(spec, binHz, nowMs, eng)
        }
        if (!started) {
            Log.e(TAG, "Cattura non avviata (microfono non disponibile)")
            stopMonitoring()
            return
        }

        if (cfg.vib.enabled) {
            val v = VibrationEngine(this)
            if (v.start({ db -> eng.vibDb = db })) vib = v
        }

        // batteria nel motore + refresh periodico della notifica
        scope.launch {
            while (running) {
                eng.batteryPct = readBattery()
                updateNotification()
                delay(60_000)
            }
        }

        // Watchdog: se non arrivano spettri per WATCHDOG_TIMEOUT_MS, il microfono
        // è morto in silenzio (Deep Doze / AudioRecord ERROR_DEAD_OBJECT) — riavvia
        // la cattura senza fermare la sessione (il motore registra solo un GAP).
        scope.launch {
            while (running) {
                delay(5_000)
                if (!running) break
                val stale = System.currentTimeMillis() - lastSpectrumMs
                if (lastSpectrumMs != 0L && stale > WATCHDOG_TIMEOUT_MS) {
                    Log.w(TAG, "Watchdog: nessuno spettro da ${stale}ms, riavvio cattura")
                    recoveryNote = "Recupero microfono…"
                    updateNotification()
                    restartCapture(eng)
                    recoveryNote = null
                    updateNotification()
                }
            }
        }
    }

    /** Stato istantaneo per la UI (schermate Live e Notte). */
    private fun publishLive(spec: FloatArray, binHz: Double, nowMs: Long, eng: NightEngine) {
        val levels = HashMap<String, Double>()
        val active = HashMap<String, Long>()
        for (b in cfg.enabledBands()) {
            levels[b.id] = Bands.bandDb(spec, binHz, b.lo, b.hi)
            eng.activeSince(b.id)?.let { active[b.id] = it }
        }
        if (cfg.vib.enabled) {
            eng.activeSince(Channels.VIB)?.let { active[Channels.VIB] = it }
        }
        val dom = Bands.dominantHz(spec, binHz, NightEngine.WF_FMIN, NightEngine.WF_FMAX)
        val prev = MonitorBus.state.value
        MonitorBus.state.value = prev.copy(
            eventsCount = recorder?.eventsCount?.value ?: 0,
            activeBands = active,
            levels = levels,
            vibDb = eng.vibDb,
            ref = Bands.bandDb(spec, binHz, NightEngine.REF_LO, NightEngine.REF_HI),
            domHz = dom.first,
            batteryPct = eng.batteryPct,
        )
        // spettro per la UI: copia dei bin fino a 2 kHz
        val maxBins = minOf(spec.size, (2000.0 / binHz).toInt())
        MonitorBus.spectrum.value = MonitorBus.SpectrumFrame(spec.copyOf(maxBins), binHz, nowMs)
    }

    // ── Clip WAV sugli eventi ───────────────────────────────────────────────
    private fun maybeStartClip(band: String, startT: Long) {
        if (!cfg.clipsEnabled || band == Channels.VIB) return
        if (clipWriter != null || clipCount >= cfg.clipsMax) return
        val rec = recorder ?: return
        val cap = capture ?: return
        val dir = File(filesDir, "clips").apply { mkdirs() }
        val file = File(dir, "${rec.sessionId}_${band}_$startT.wav")
        val writer = ClipWriter(file, cap.actualSampleRate, cfg.clipSeconds)
        runCatching { writer.open() }.onFailure { return }
        clipWriter = writer
        clipCount++
        cap.pcmTap = { pcm, n -> writer.append(pcm, n) }
        scope.launch {
            delay(cfg.clipSeconds * 1000L)
            cap.pcmTap = null
            runCatching { writer.close() }
            if (writer === clipWriter) clipWriter = null
            rec.addClip(band, startT, file.absolutePath, "audio/wav")
        }
    }

    fun addMarker(origin: String) {
        val t = recorder?.addMarker(origin) ?: return
        MonitorBus.markers.value = MonitorBus.markers.value + t
    }

    private fun stopMonitoring() {
        if (!running) {
            stopSelf()
            return
        }
        running = false
        capture?.stop()
        vib?.stop()
        engine?.stop(System.currentTimeMillis())
        clipWriter?.let { runCatching { it.close() } }
        clipWriter = null
        recorder?.close()
        MonitorBus.state.value = MonitorBus.state.value.copy(running = false, activeBands = emptyMap())
        wakeLock?.release()
        wakeLock = null
        instance = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Riavvia solo la cattura audio conservando sessione e motore (fix watchdog). */
    private fun restartCapture(eng: NightEngine) {
        val old = capture
        capture = null
        runCatching { old?.stop() }
        val cap = CaptureEngine(this, cfg.fftSize, cfg.smoothNight)
        capture = cap
        lastSpectrumMs = System.currentTimeMillis()
        val started = cap.start(scope) { spec, binHz, nowMs ->
            lastSpectrumMs = nowMs
            eng.processSpectrum(spec, binHz, nowMs)
            publishLive(spec, binHz, nowMs, eng)
        }
        if (!started) {
            Log.e(TAG, "restartCapture fallito: microfono non disponibile dopo riavvio")
        } else {
            Log.i(TAG, "Cattura riavviata con successo")
        }
    }

    override fun onDestroy() {
        if (running) stopMonitoring()
        super.onDestroy()
    }

    // ── Notifica persistente informativa ────────────────────────────────────
    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID, "Monitoraggio in corso", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Stato del log notturno"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun notificationText(): String {
        val st = MonitorBus.state.value
        val lines = StringBuilder()
        recoveryNote?.let { lines.append("$it\n") }
        val evs = MonitorBus.events.value.filter { it.band != Channels.GAP }
        if (evs.isEmpty()) {
            lines.append("Nessun evento sopra soglia.")
        } else {
            val perBand = evs.groupBy { it.band }.entries.joinToString("  ") { "${it.key}:${it.value.size}" }
            lines.append("Eventi: ${evs.size}  ($perBand)")
        }
        val nowS = System.currentTimeMillis() / 1000
        for ((band, since) in st.activeBands) {
            lines.append("\n● $band sopra soglia da ${fmtDur(nowS - since)}")
        }
        val top = st.levels.maxByOrNull { it.value }
        if (top != null) {
            lines.append("\nLivello ${top.key}: ${"%.1f".format(top.value)} dBFS")
        }
        lines.append("\nSorgente ${st.audioSource}")
        st.batteryPct?.let { lines.append(" · Batteria $it%") }
        return lines.toString()
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val startedAt = MonitorBus.state.value.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("REC · Low-Freq Hunter · dalle ${fmtClockShort(startedAt)}")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(startedAt)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(notificationText()))
    }

    private fun readBattery(): Int? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }
}
