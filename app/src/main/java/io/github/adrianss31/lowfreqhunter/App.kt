package io.github.adrianss31.lowfreqhunter

import android.app.Application
import io.github.adrianss31.lowfreqhunter.data.LfhDb
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.data.recoverInterrupted
import io.github.adrianss31.lowfreqhunter.schedule.ScheduleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class App : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Sessioni rimaste aperte (crash/batteria): chiuse all'ultimo campione
        scope.launch {
            runCatching { recoverInterrupted(LfhDb.get(this@App).dao()) }
        }

        // Ogni modifica alla programmazione riprogramma gli allarmi
        scope.launch {
            SettingsRepo.get(this@App).flow
                .map { it.schedule }
                .distinctUntilChanged()
                .collect { ScheduleManager.apply(this@App, it) }
        }
    }
}
