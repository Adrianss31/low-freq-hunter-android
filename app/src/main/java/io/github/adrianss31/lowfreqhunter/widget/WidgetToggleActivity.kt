package io.github.adrianss31.lowfreqhunter.widget

import android.app.Activity
import android.os.Bundle
import io.github.adrianss31.lowfreqhunter.service.MonitorService
import io.github.adrianss31.lowfreqhunter.service.MonitorBus

/**
 * Trampolino del tasto REC del widget: porta l'app in primo piano per un
 * istante (requisito per l'accesso al microfono del foreground service) e
 * avvia il solo-ascolto, o ferma quello che è in corso.
 */
class WidgetToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MonitorBus.state.value.running) {
            MonitorService.stop(this)
        } else {
            MonitorService.startListen(this)
        }
        finish()
    }
}
