package io.github.adrianss31.lowfreqhunter.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Riprogramma gli allarmi quando gli epoch calcolati non valgono più: dopo un
 * riavvio, ma anche a cambio ora/fuso (ora legale inclusa — che scatta proprio
 * di notte, mentre l'avvio programmato aspetterebbe l'orario vecchio).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                val settings = runBlocking { SettingsRepo.get(context).flow.first() }
                ScheduleManager.apply(context, settings.schedule)
            }
        }
    }
}
