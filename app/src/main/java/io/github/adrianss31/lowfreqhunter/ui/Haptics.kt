package io.github.adrianss31.lowfreqhunter.ui

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Feedback aptico "da strumento fisico": ogni interazione dà un tick corto.
 * Usa le costanti di sistema (rispettano l'impostazione aptica dell'utente).
 */
object Haptics {
    /** Tick leggero: scatti di stepper, selettori, detent slider. */
    fun tick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Tap: toggle, tab, pulsanti normali. */
    fun tap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Conferma decisa: avvio/stop REC, marker. */
    fun heavy(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Rifiuto/limite raggiunto. */
    fun reject(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }
}
