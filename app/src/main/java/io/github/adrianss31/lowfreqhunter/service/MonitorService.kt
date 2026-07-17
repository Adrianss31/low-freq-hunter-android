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
import io.github.adrianss31.lowfreqhunter.dsp.Ema
import io.github.adrianss31.lowfreqhunter.dsp.MovingMedian
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.engine.EventData
import io.github.adrianss31.lowfreqhunter.engine.NightEngine
import io.github.adrianss31.lowfreqhunter.engine.SampleData
import io.github.adrianss31.lowfreqhunter.sensor.VibrationEngine
import io.github.adrianss31.lowfreqhunter.server.LanServer
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
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
        /** Costante di tempo (s) dello smoothing dei valori mostrati in UI. */
        private const val UI_TAU_S = 1.2

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

    // motore e recorder vengono sostituiti a caldo dal rollover della
    // registrazione continua: letti anche dal thread di cattura
    @Volatile private var engine: NightEngine? = null
    @Volatile private var recorder: SessionRecorder? = null
    private var vib: VibrationEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cfg: EngineCfg = EngineCfg()
    private var contCfg: io.github.adrianss31.lowfreqhunter.data.ContinuousCfg =
        io.github.adrianss31.lowfreqhunter.data.ContinuousCfg()
    private var nextSplitMs = 0L
    private var running = false
    private var listenMode = false
    private var evCount = 0
    private var clipCount = 0
    private var clipWriter: ClipWriter? = null
    private var lanServer: LanServer? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    // smussatori dei soli valori pubblicati alla UI/notifica/LAN: il motore
    // eventi e i campioni registrati continuano a usare i valori grezzi
    private val uiLevels = HashMap<String, Ema>()
    private val uiRef = Ema(UI_TAU_S)
    private val uiVib = Ema(UI_TAU_S)
    private val uiDom = MovingMedian(12) // ~3 s a 4 spettri/s

    // il sink legge [recorder] dal campo: il rollover della registrazione
    // continua lo sostituisce a caldo senza toccare motore e cattura
    private val sink = object : NightEngine.Sink {
        override fun onSample(s: SampleData) {
            recorder?.onSample(s)
        }

        override fun onEvent(e: EventData) {
            if (e.band != Channels.GAP) evCount++
            recorder?.onEvent(e)
            MonitorBus.events.value = MonitorBus.events.value + e
            updateNotification()
        }

        override fun onSlice(t: Long, bins: ByteArray) {
            recorder?.onSlice(t, bins)
            MonitorBus.slices.value = MonitorBus.slices.value + Pair(t, bins)
        }

        override fun onEventStart(band: String, startT: Long) {
            maybeStartClip(band, startT)
            updateNotification()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring(listenOnly = false)
            ACTION_START_LISTEN -> startMonitoring(listenOnly = true)
            ACTION_STOP -> stopMonitoring()
            // REDELIVER: dopo un kill il sistema riconsegna l'ultimo intent,
            // quindi si riparte nella stessa modalità (rec O solo ascolto —
            // con STICKY l'intent tornava null e un "solo ascolto" ucciso
            // ripartiva come registrazione non richiesta). La sessione
            // interrotta viene chiusa dal recupero in App.onCreate.
            null -> if (!running) startMonitoring(listenOnly = false)
        }
        return START_REDELIVER_INTENT
    }

    private fun startMonitoring(listenOnly: Boolean) {
        if (running) return
        running = true
        listenMode = listenOnly
        evCount = 0
        instance = this
        uiLevels.clear()
        uiRef.reset()
        uiVib.reset()
        uiDom.reset()

        val settings = runBlocking { SettingsRepo.get(this@MonitorService).flow.first() }
        cfg = settings.engine
        contCfg = settings.continuous
        nextSplitMs = if (contCfg.enabled && !listenOnly) nextSplitAfter(System.currentTimeMillis()) else 0L

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
            // timeout breve rinnovato dal watchdog: nessun limite implicito
            // alla durata della sessione (prima 12 h fisse, poi buchi silenziosi)
            it.acquire(WAKELOCK_TIMEOUT_MS)
        }

        val dao = LfhDb.get(this).dao()
        val cap = CaptureEngine(this, cfg.fftSize, cfg.smoothNight)
        capture = cap
        val rec = if (listenOnly) null else SessionRecorder(dao, scope, cfg, cap.actualSampleRate, cap.binHz, cap.sourceName, sessionLabelPrefix())
        recorder = rec

        // server LAN per il monitoraggio dal PC (se abilitato nel Setup)
        var lanUrl: String? = null
        if (settings.lan.enabled && settings.lan.token.isNotBlank()) {
            runCatching {
                val srv = LanServer(this, dao, settings.lan.token, settings.lan.port, { cfg }, { settings.calib })
                srv.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                lanServer = srv
                // a schermo spento Android addormenta il Wi-Fi: il PC vedrebbe
                // buchi anche con la registrazione (locale) perfettamente viva
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lfh:lan").also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
                lanUrl = LanServer.deviceIp()?.let { ip ->
                    "http://$ip:${settings.lan.port}/?k=${settings.lan.token}"
                }
            }
        }

        MonitorBus.resetSession()
        MonitorBus.state.value = MonitorBus.State(
            running = true,
            mode = if (listenOnly) "listen" else "rec",
            sessionId = rec?.sessionId,
            startedAt = rec?.startedAt ?: System.currentTimeMillis(),
            audioSource = cap.sourceName,
            lanUrl = lanUrl,
        )

        val eng = NightEngine(cfg, sink)
        engine = eng
        eng.batteryPct = readBattery()
        eng.start(System.currentTimeMillis())

        if (!startCapture(cap)) {
            stopMonitoring()
            return
        }

        if (cfg.vib.enabled) {
            val v = VibrationEngine(this)
            if (v.start({ db -> engine?.vibDb = db })) vib = v
        }

        // batteria nel motore + refresh notifica + watchdog cattura: se il
        // microfono non consegna dati da >20 s (recovery interno fallito),
        // butta via il CaptureEngine e ripartine uno nuovo. Il buco resta
        // documentato come gap dal NightEngine.
        scope.launch {
            while (running) {
                engine?.batteryPct = readBattery()
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS) // rinnovo (ref counting off)
                if (nextSplitMs > 0 && System.currentTimeMillis() >= nextSplitMs) rollover()
                val c = capture
                if (c != null && System.currentTimeMillis() - c.lastDataMs > 20_000) {
                    c.stop()
                    val fresh = CaptureEngine(this@MonitorService, cfg.fftSize, cfg.smoothNight)
                    capture = fresh
                    startCapture(fresh)
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

    private fun startCapture(cap: CaptureEngine): Boolean =
        cap.start(scope) { spec, binHz, nowMs ->
            // dal campo, non catturato: il rollover sostituisce il motore
            engine?.let { eng ->
                eng.processSpectrum(spec, binHz, nowMs)
                publishLive(spec, binHz, nowMs, eng)
            }
        }

    /**
     * Rollover della registrazione continua: chiude sessione e motore e ne
     * apre di nuovi nello stesso istante, senza fermare la cattura. Gli
     * eventi ancora aperti vengono finalizzati nella sessione che si chiude.
     */
    private fun rollover() {
        val cap = capture ?: return
        val now = System.currentTimeMillis()
        nextSplitMs = nextSplitAfter(now)
        capture?.pcmTap = null
        clipWriter?.let { runCatching { it.close() } }
        clipWriter = null
        engine?.stop(now)
        recorder?.close()

        evCount = 0
        clipCount = 0
        uiDom.reset()
        val dao = LfhDb.get(this).dao()
        val rec = SessionRecorder(dao, scope, cfg, cap.actualSampleRate, cap.binHz, cap.sourceName, sessionLabelPrefix())
        recorder = rec
        MonitorBus.resetSession()
        MonitorBus.state.value = MonitorBus.state.value.copy(
            sessionId = rec.sessionId,
            startedAt = rec.startedAt,
            eventsCount = 0,
            activeBands = emptyMap(),
        )
        val eng = NightEngine(cfg, sink)
        eng.batteryPct = readBattery()
        eng.start(now)
        engine = eng
        updateNotification()
    }

    /** Prossimo spezzamento dopo [nowMs]: il più vicino tra quelli attivi. */
    private fun nextSplitAfter(nowMs: Long): Long =
        contCfg.splitMins().minOf { min ->
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(java.util.Calendar.HOUR_OF_DAY, min / 60)
                set(java.util.Calendar.MINUTE, min % 60)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= nowMs) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            cal.timeInMillis
        }

    /** Etichetta della nuova sessione (Notte/Giorno) con due spezzamenti. */
    private fun sessionLabelPrefix(): String {
        if (!contCfg.enabled) return "Notte"
        val cal = java.util.Calendar.getInstance()
        val minOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return contCfg.labelPrefixAt(minOfDay)
    }

    /** Stato istantaneo per la UI (schermate Live e Notte), smussato: i
     *  numeri e i meter respirano invece di saltellare a ogni spettro. */
    private fun publishLive(spec: FloatArray, binHz: Double, nowMs: Long, eng: NightEngine) {
        val levels = HashMap<String, Double>()
        val active = HashMap<String, Long>()
        for (b in cfg.enabledBands()) {
            val raw = Bands.bandDb(spec, binHz, b.lo, b.hi)
            levels[b.id] = uiLevels.getOrPut(b.id) { Ema(UI_TAU_S) }.push(raw, nowMs)
            eng.activeSince(b.id)?.let { active[b.id] = it }
        }
        if (cfg.vib.enabled) {
            eng.activeSince(Channels.VIB)?.let { active[Channels.VIB] = it }
        }
        val dom = uiDom.push(Bands.dominantHz(spec, binHz, NightEngine.WF_FMIN, NightEngine.WF_FMAX).first)
        val prev = MonitorBus.state.value
        MonitorBus.state.value = prev.copy(
            eventsCount = evCount,
            activeBands = active,
            levels = levels,
            vibDb = eng.vibDb?.let { uiVib.push(it, nowMs) },
            ref = uiRef.push(Bands.bandDb(spec, binHz, NightEngine.REF_LO, NightEngine.REF_HI), nowMs),
            domHz = dom,
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
        lanServer?.let { runCatching { it.stop() } }
        lanServer = null
        wifiLock?.release()
        wifiLock = null
        engine?.stop(System.currentTimeMillis())
        clipWriter?.let { runCatching { it.close() } }
        clipWriter = null
        recorder?.close()
        MonitorBus.state.value = MonitorBus.state.value.copy(running = false, mode = "", activeBands = emptyMap(), lanUrl = null)
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
        st.lanUrl?.let { lines.append("\nPC: $it") }
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
