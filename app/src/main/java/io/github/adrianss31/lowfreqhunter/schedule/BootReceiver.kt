package io.github.adrianss31.lowfreqhunter.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Dopo un riavvio gli allarmi vanno riprogrammati. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = runBlocking { SettingsRepo.get(context).flow.first() }
        ScheduleManager.apply(context, settings.schedule)
    }
}
