package io.github.adrianss31.lowfreqhunter.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Canale vibrazioni "V": accelerometro alla massima frequenza disponibile,
 * high-pass per togliere gravità e inclinazione, RMS per secondo espresso in
 * dB rel 1 g. Il telefono appoggiato sulla superficie (comodino, pavimento)
 * misura la vibrazione strutturale che il microfono può non captare.
 */
class VibrationEngine(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val available: Boolean get() = sensor != null

    private var onLevel: ((Double) -> Unit)? = null

    // high-pass: media mobile esponenziale sottratta per asse
    private var emaX = 0.0
    private var emaY = 0.0
    private var emaZ = 0.0
    private var initialized = false
    private val alpha = 0.995

    // accumulo RMS del secondo corrente
    private var sumSq = 0.0
    private var count = 0
    private var curSec = -1L

    fun start(onLevel: (Double) -> Unit): Boolean {
        val s = sensor ?: return false
        this.onLevel = onLevel
        initialized = false
        sumSq = 0.0
        count = 0
        curSec = -1L
        return sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stop() {
        sm.unregisterListener(this)
        onLevel = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        if (!initialized) {
            emaX = x; emaY = y; emaZ = z
            initialized = true
            return
        }
        emaX = alpha * emaX + (1 - alpha) * x
        emaY = alpha * emaY + (1 - alpha) * y
        emaZ = alpha * emaZ + (1 - alpha) * z
        val hx = x - emaX
        val hy = y - emaY
        val hz = z - emaZ
        val g = sqrt(hx * hx + hy * hy + hz * hz) / SensorManager.GRAVITY_EARTH
        sumSq += g * g
        count++

        val sec = event.timestamp / 1_000_000_000L
        if (curSec == -1L) curSec = sec
        if (sec != curSec && count > 0) {
            val rms = sqrt(sumSq / count)
            onLevel?.invoke(20.0 * log10(rms + 1e-7))
            sumSq = 0.0
            count = 0
            curSec = sec
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
