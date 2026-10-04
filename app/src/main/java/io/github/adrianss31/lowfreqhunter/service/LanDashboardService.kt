package io.github.adrianss31.lowfreqhunter.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.adrianss31.lowfreqhunter.MainActivity
import io.github.adrianss31.lowfreqhunter.data.*
import io.github.adrianss31.lowfreqhunter.server.LanServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Optional LAN access has its own lifetime and never opens the microphone. */
class LanDashboardService : Service() {
    companion object {
        private const val CHANNEL="lan-dashboard"
        private const val NOTIFICATION=2
        private const val LOCK_MS=10*60*1000L
        fun sync(ctx: Context) { ContextCompat.startForegroundService(ctx,Intent(ctx,LanDashboardService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx,LanDashboardService::class.java)) }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var server: LanServer?=null
    private var bound: LanCfg?=null
    @Volatile private var current=AppSettings()
    private var observing=false
    private var wifi: android.net.wifi.WifiManager.WifiLock?=null
    private var wake: PowerManager.WakeLock?=null

    override fun onBind(intent: Intent?): IBinder?=null
    override fun onCreate() {
        super.onCreate()
        val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL,"Monitor dal PC",NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val notification=notification("Avvio della dashboard in rete locale")
        if(Build.VERSION.SDK_INT>=34) startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION,notification)
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(!observing) {
            observing=true
            scope.launch { SettingsRepo.get(this@LanDashboardService).flow.collect { configure(it) } }
            scope.launch {
                while(isActive) {
                    delay(5*60*1000L)
                    synchronized(this@LanDashboardService) { if(server!=null) wake?.acquire(LOCK_MS) }
                }
            }
        }
        return START_STICKY
    }
    @Synchronized internal fun configure(settings: AppSettings) {
        current=settings
        if(!settings.lan.enabled || settings.lan.token.isBlank()) {
            closeServer();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return
        }
        if(bound==settings.lan && server!=null) { updateUrl();return }
        closeServer()
        runCatching {
            val srv=LanServer(this,LfhDb.get(this).dao(),settings.lan.token,settings.lan.port,
                { MonitorService.instance?.engineConfig() ?: current.engine }, { current.calib })
            srv.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT,true)
            server=srv;bound=settings.lan
            val wm=applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            wifi=wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"lfh:dashboard").also { it.setReferenceCounted(false);it.acquire() }
            wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"lfh:dashboard").also {
                it.setReferenceCounted(false);it.acquire(LOCK_MS)
            }
            MonitorBus.lanError.value=null
            updateUrl()
        }.onFailure {
            closeServer();MonitorBus.lanError.value="Dashboard PC non avviata: ${it.message}"
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        }
    }
    private fun updateUrl() {
        val url=bound?.let { b -> LanServer.deviceIp()?.let { "http://$it:${b.port}/?k=${b.token}" } }
        MonitorBus.state.update { it.copy(lanUrl=url) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION,
            notification(if(url!=null) "Disponibile anche con REC fermo · porta ${bound?.port}" else "Collega il telefono alla rete di casa"))
    }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this,CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Low-Freq Hunter · Monitor dal PC")
        .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()
    @Synchronized private fun closeServer() {
        server?.stop();server=null;bound=null
        wifi?.let { if(it.isHeld) it.release() };wifi=null
        wake?.let { if(it.isHeld) it.release() };wake=null
        MonitorBus.state.update { it.copy(lanUrl=null) }
    }
    override fun onDestroy() { closeServer();scope.cancel();super.onDestroy() }
}
