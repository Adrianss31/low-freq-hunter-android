package io.github.adrianss31.lowfreqhunter.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.adrianss31.lowfreqhunter.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service full-duplex per Controfase: legge PCM grezzo (UNPROCESSED)
 * e riproduce la controfase a bassa latenza sullo stesso thread audio urgente,
 * rimanendo attivo anche a schermo spento.
 */
class ControfaseService : Service() {

    companion object {
        const val ACTION_SCAN = "io.github.adrianss31.lowfreqhunter.CONTROFASE_SCAN"
        const val ACTION_START_SELECTED = "io.github.adrianss31.lowfreqhunter.CONTROFASE_START"
        const val ACTION_STOP = "io.github.adrianss31.lowfreqhunter.CONTROFASE_STOP"
        private const val CHANNEL_ID = "controfase"
        private const val NOTIF_ID = 2
        private const val SAMPLE_RATE = 48000
        private const val FRAME_SAMPLES = 512 // ~10.67 ms per frame
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L

        @Volatile
        var instance: ControfaseService? = null
            private set

        fun startScan(ctx: Context) {
            val i = Intent(ctx, ControfaseService::class.java).setAction(ACTION_SCAN)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun startSelected(ctx: Context) {
            val i = Intent(ctx, ControfaseService::class.java).setAction(ACTION_START_SELECTED)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, ControfaseService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false
    private var audioThread: Thread? = null
    @Volatile private var activeRecord: AudioRecord? = null
    @Volatile private var activeTrack: AudioTrack? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCAN -> startWithAction { ControfaseBus.engine.startScan(6.0) }
            ACTION_START_SELECTED -> startWithAction { ControfaseBus.engine.startSelected() }
            ACTION_STOP -> stopEngine()
            null -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startWithAction(onReady: () -> Unit) {
        val sim = ControfaseBus.engine.snapshot().simMode
        if (!sim && MonitorBus.state.value.running) {
            ControfaseBus.error.value = "Ferma prima la registrazione/ascolto nella scheda Monitor."
            stopSelf()
            return
        }
        if (!sim && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ControfaseBus.error.value = "Permesso microfono mancante."
            stopSelf()
            return
        }
        ControfaseBus.error.value = null
        if (!running) {
            startAudioLoop(sim)
        } else {
            applyPreferredDevices()
        }
        onReady()
    }

    private fun startAudioLoop(sim: Boolean) {
        running = true
        instance = this
        createChannel()
        val notif = buildNotification("In ascolto…")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            }
            else -> startForeground(NOTIF_ID, notif)
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lfh:controfase").also {
            it.setReferenceCounted(false)
            it.acquire(WAKELOCK_TIMEOUT_MS)
        }

        ControfaseBus.refreshDevices(this)
        val engine = ControfaseBus.engine
        engine.resetAudioBuffers()

        if (sim) {
            engine.setRunning(true, "SIMULAZIONE")
            audioThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val outBuf = FloatArray(FRAME_SAMPLES)
                while (running) {
                    engine.processBlock(null, FRAME_SAMPLES, outBuf)
                    runCatching { Thread.sleep(10) }
                }
            }, "ControfaseSim").apply { start() }
        } else {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val unprocessed = "true" == am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            val src = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val srcName = if (unprocessed) "UNPROCESSED" else "VOICE_RECOGNITION"

            val rec = createRecord(src)
            val track = createTrack()
            if (rec == null || track == null) {
                rec?.release()
                track?.release()
                ControfaseBus.error.value = "Hardware audio non disponibile."
                stopEngine()
                return
            }
            activeRecord = rec
            activeTrack = track
            applyPreferredDevices()

            // Pre-riempi 2 frame silenziosi per evitare underrun all'avvio
            val silence = FloatArray(FRAME_SAMPLES * 2)
            track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
            val startedOk = runCatching {
                track.play()
                rec.startRecording()
                rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)

            if (!startedOk) {
                runCatching { rec.stop(); rec.release() }
                runCatching { track.stop(); track.release() }
                activeRecord = null
                activeTrack = null
                ControfaseBus.error.value = "Impossibile avviare microfono o altoparlante."
                stopEngine()
                return
            }

            engine.setRunning(true, srcName)
            audioThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val inBuf = FloatArray(FRAME_SAMPLES)
                val outBuf = FloatArray(FRAME_SAMPLES)
                while (running) {
                    val n = runCatching { rec.read(inBuf, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING) }.getOrDefault(-1)
                    if (!running) break
                    if (n <= 0) {
                        ControfaseBus.error.value = "Lettura microfono interrotta ($n)."
                        break
                    }
                    engine.processBlock(inBuf, n, outBuf)
                    val w = runCatching { track.write(outBuf, 0, n, AudioTrack.WRITE_BLOCKING) }.getOrDefault(-1)
                    if (w < 0 && running) {
                        ControfaseBus.error.value = "Uscita audio interrotta ($w)."
                        break
                    }
                }
                runCatching { rec.stop(); rec.release() }
                runCatching { track.stop(); track.release() }
                activeRecord = null
                activeTrack = null
                if (running) {
                    scope.launch { stopEngine() }
                }
            }, "ControfaseAudio").apply { start() }
        }

        scope.launch {
            while (running) {
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                updateNotification()
                delay(2_000)
            }
        }
    }

    fun applyPreferredDevices() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val inId = ControfaseBus.selectedInputId.value
        val outId = ControfaseBus.selectedOutputId.value
        val rec = activeRecord
        val trk = activeTrack
        if (rec != null) {
            val dev: AudioDeviceInfo? = inId?.let { id ->
                am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
            }
            runCatching { rec.setPreferredDevice(dev) }
        }
        if (trk != null) {
            val dev: AudioDeviceInfo? = outId?.let { id ->
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
            }
            runCatching { trk.setPreferredDevice(dev) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(source: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuf <= 0) return null
        val bytes = maxOf(minBuf, FRAME_SAMPLES * 4 * 6)
        val rec = runCatching {
            AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                bytes,
            )
        }.getOrNull() ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
    }

    private fun createTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuf <= 0) return null
        val bytes = maxOf(minBuf, FRAME_SAMPLES * 4 * 4)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = runCatching { builder.build() }.getOrNull() ?: return null
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }
        return track
    }

    private fun stopEngine() {
        running = false
        if (instance === this) instance = null
        ControfaseBus.engine.setRunning(false)
        runCatching { audioThread?.join(500) }
        audioThread = null
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Controfase", NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) },
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, ControfaseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Controfase")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .addAction(0, "Ferma", stopPi)
            .build()
    }

    private fun updateNotification() {
        val snap = ControfaseBus.snapshot.value
        val active = snap.channels.filter { it.enabled && it.reductionDb != null }
        val text = when {
            snap.guideText != null -> snap.guideText
            active.isNotEmpty() -> active.joinToString(" · ") {
                "%.1f Hz (−%.0f dB)".format(it.freq, it.reductionDb ?: 0.0)
            }
            else -> snap.headline
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        ControfaseBus.engine.setRunning(false)
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        scope.cancel()
        super.onDestroy()
    }
}
