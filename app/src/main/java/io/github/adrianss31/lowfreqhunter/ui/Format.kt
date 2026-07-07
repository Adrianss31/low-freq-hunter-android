package io.github.adrianss31.lowfreqhunter.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtClock(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.ITALIAN).format(Date(ms))

fun fmtClockShort(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date(ms))

fun fmtDate(ms: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(Date(ms))

fun fmtDur(totalS: Long): String {
    val s = if (totalS < 0) 0 else totalS
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${ss}s"
        else -> "${ss}s"
    }
}

fun fmtHm(totalMin: Int): String = "%02d:%02d".format(totalMin / 60, totalMin % 60)

fun fmtDb(v: Double?): String = if (v == null || !v.isFinite()) "—" else "%.1f".format(v)
