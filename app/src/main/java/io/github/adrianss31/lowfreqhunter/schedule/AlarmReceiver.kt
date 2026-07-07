package io.github.adrianss31.lowfreqhunter.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.service.MonitorService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ScheduleManager.ACTION_ALARM_START -> {
                // Il servizio microphone deve partire con l'app in primo piano:
                // notifica full-screen che lancia l'activity-trampolino
                // (accende lo schermo, avvia il servizio, si chiude).
                postStartNotification(context)
            }
            ScheduleManager.ACTION_ALARM_STOP -> {
                MonitorService.stop(context)
            }
        }
        // riprogramma per la prossima notte
        val settings = runBlocking { SettingsRepo.get(context).flow.first() }
        ScheduleManager.apply(context, settings.schedule)
    }

    private fun postStartNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                "schedule", "Avvio programmato", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Avvia il log notturno all'orario impostato" },
        )
        val fullScreen = PendingIntent.getActivity(
            context, 103,
            Intent(context, ScheduleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, "schedule")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Low-Freq Hunter")
            .setContentText("Avvio del log notturno programmato — tocca se non parte da solo")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .build()
        nm.notify(2, notif)
    }
}
