package io.github.adrianss31.lowfreqhunter.schedule

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import io.github.adrianss31.lowfreqhunter.service.MonitorService

/**
 * Activity-trampolino dell'avvio programmato: porta l'app in primo piano per
 * un istante (requisito per l'accesso al microfono del foreground service),
 * avvia il servizio e si chiude.
 */
class ScheduleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        MonitorService.start(this)
        // rimuovi la notifica di avvio se ancora presente
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(2)
        finish()
    }
}
