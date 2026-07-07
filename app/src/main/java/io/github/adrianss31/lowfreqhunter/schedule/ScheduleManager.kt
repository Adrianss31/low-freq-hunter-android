package io.github.adrianss31.lowfreqhunter.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.adrianss31.lowfreqhunter.data.ScheduleCfg
import java.util.Calendar

/**
 * Programmazione oraria: due allarmi esatti al giorno (avvio e stop del log).
 *
 * L'avvio usa setAlarmClock — la classe di allarme più affidabile (esente da
 * Doze, icona sveglia nella status bar). Il broadcast di avvio passa da una
 * activity-trampolino: un foreground service microphone avviato con l'app in
 * background non riceverebbe audio (restrizione while-in-use di Android 11+),
 * mentre avviato da un'activity in primo piano sì.
 */
object ScheduleManager {

    const val ACTION_ALARM_START = "io.github.adrianss31.lowfreqhunter.ALARM_START"
    const val ACTION_ALARM_STOP = "io.github.adrianss31.lowfreqhunter.ALARM_STOP"

    fun apply(context: Context, schedule: ScheduleCfg) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val startPi = alarmPi(context, ACTION_ALARM_START, 100)
        val stopPi = alarmPi(context, ACTION_ALARM_STOP, 101)
        am.cancel(startPi)
        am.cancel(stopPi)
        if (!schedule.enabled) return

        val startAt = nextOccurrence(schedule.startMin)
        val stopAt = nextOccurrence(schedule.endMin)
        val canExact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(startAt, showPi(context)), startPi)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAt, stopPi)
        } else {
            // senza permesso exact: meglio un allarme approssimato che niente
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startAt, startPi)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAt, stopPi)
        }
    }

    /** Prossima occorrenza (epoch ms) dell'orario [minOfDay], oggi o domani. */
    fun nextOccurrence(minOfDay: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minOfDay / 60)
            set(Calendar.MINUTE, minOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun alarmPi(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun showPi(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 102,
            Intent(context, io.github.adrianss31.lowfreqhunter.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
