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
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_START = "io.github.adrianss31.lowfreqhunter.START"
        const val ACTION_START_LISTEN = "io.github.adrianss31.lowfreqhunter.START_LISTEN"
        const val ACTION_STOP = "io.github.adrianss31.lowfreqhunter.STOP"
        private const val CHANNEL_ID = "monitor"
        private const val NOTIF_ID = 1

        @Volatile
        var instance: MonitorService? = null
            private set

        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, MonitorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        /** Solo ascolto (dal widget): motore attivo, nessuna sessione salvata. */
        fun startListen(ctx: android.content.Context) {
            val i = Intent(ctx, MonitorService::class.java).setAction(ACTION_START_LISTEN)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: android.content.Context) {
            val i = Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var capture: CaptureEngine? = null
    private var engine: NightEngine? = null
    private var recorder: SessionRecorder? = null
    private var vib: VibrationEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cfg: EngineCfg = EngineCfg()
    private var running = false
    private var listenMode = false
    private var evCount = 0
    private var clipCount = 0
    private var clipWriter: ClipWriter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring(listenOnly = false)
            ACTION_START_LISTEN -> startMonitoring(listenOnly = true)
            ACTION_STOP -> stopMonitoring()
            // intent == null: il sistema ci ha uccisi e riavviati (STICKY).
            // Riprendi a registrare: la sessione interrotta viene chiusa dal
            // recupero in App.onCreate, qui ne parte una nuova.
            null -> if (!running) startMonitoring(listenOnly = false)
        }
        return START_STICKY
    }

    private fun startMonitoring(listenOnly: Boolean) {
        if (running) return
        running = true
        listenMode = listenOnly
        evCount = 0
        instance = this

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
        }

        val dao = LfhDb.get(this).dao()
        val cap = CaptureEngine(this, cfg.fftSize, cfg.smoothNight)
        capture = cap
        val rec = if (listenOnly) null else SessionRecorder(dao, scope, cfg, cap.actualSampleRate, cap.binHz, cap.sourceName)
        recorder = rec

        MonitorBus.resetSession()
        MonitorBus.state.value = MonitorBus.State(
            running = true,
            mode = if (listenOnly) "listen" else "rec",
            sessionId = rec?.sessionId,
            startedAt = rec?.startedAt ?: System.currentTimeMillis(),
            audioSource = cap.sourceName,
        )

        val sink = object : NightEngine.Sink {
            override fun onSample(s: SampleData) {
                rec?.onSample(s)
            }

            override fun onEvent(e: EventData) {
                if (e.band != Channels.GAP) evCount++
                rec?.onEvent(e)
                MonitorBus.events.value = MonitorBus.events.value + e
                updateNotification()
            }

            override fun onSlice(t: Long, bins: ByteArray) {
                rec?.onSlice(t, bins)
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

        if (!startCapture(cap, eng)) {
            stopMonitoring()
            return
        }

        if (cfg.vib.enabled) {
            val v = VibrationEngine(this)
            if (v.start({ db -> eng.vibDb = db })) vib = v
        }

        // batteria nel motore + refresh notifica + watchdog cattura: se il
        // microfono non consegna dati da >20 s (recovery interno fallito),
        // butta via il CaptureEngine e ripartine uno nuovo. Il buco resta
        // documentato come gap dal NightEngine.
        scope.launch {
            while (running) {
                eng.batteryPct = readBattery()
                val c = capture
                if (c != null && System.currentTimeMillis() - c.lastDataMs > 20_000) {
                    c.stop()
                    val fresh = CaptureEngine(this@MonitorService, cfg.fftSize, cfg.smoothNight)
                    capture = fresh
                    startCapture(fresh, eng)
                }
                updateNotification()
                delay(60_000)
            }
        }

        // aggiornamento widget ~1 Hz (solo a schermo acceso)
        scope.launch {
            val pm2 = getSystemService(POWER_SERVICE) as PowerManager
            io.github.adrianss31.lowfreqhunter.widget.WidgetUpdater.updateAll(this@MonitorService)
            while (running) {
                delay(1000)
                if (pm2.isInteractive) {
                    io.github.adrianss31.lowfreqhunter.widget.WidgetUpdater.updateAll(this@MonitorService)
                }
            }
        }
    }

    private fun startCapture(cap: CaptureEngine, eng: NightEngine): Boolean =
        cap.start(scope) { spec, binHz, nowMs ->
            eng.processSpectrum(spec, binHz, nowMs)
            publishLive(spec, binHz, nowMs, eng)
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
            eventsCount = evCount,
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
        MonitorBus.state.value = MonitorBus.state.value.copy(running = false, mode = "", activeBands = emptyMap())
        wakeLock?.release()
        wakeLock = null
        instance = null
        scope.cancel()
        io.github.adrianss31.lowfreqhunter.widget.WidgetUpdater.updateAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        val evs = MonitorBus.events.value.filter { it.band != Channels.GAP }
        if (evs.isEmpty()) {
            lines.append("Nessun evento sopra soglia.")
        } else {
            val perBand = evs.groupBy { it.band }.entries.joinToString("  ") { "${cfg.channelLabel(it.key)}:${it.value.size}" }
            lines.append("Eventi: ${evs.size}  ($perBand)")
        }
        val nowS = System.currentTimeMillis() / 1000
        for ((band, since) in st.activeBands) {
            lines.append("\n● ${cfg.channelLabel(band)} sopra soglia da ${fmtDur(nowS - since)}")
        }
        val top = st.levels.maxByOrNull { it.value }
        if (top != null) {
            lines.append("\nLivello ${cfg.channelLabel(top.key)}: ${"%.1f".format(top.value)} dBFS")
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
        val modeLabel = if (listenMode) "LIVE (solo ascolto)" else "REC"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("$modeLabel · Low-Freq Hunter · dalle ${fmtClockShort(startedAt)}")
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
