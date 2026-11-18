package io.github.adrianss31.lowfreqhunter.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.github.adrianss31.lowfreqhunter.dsp.ControfaseEngine
import io.github.adrianss31.lowfreqhunter.dsp.ControfaseSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AudioDeviceChoice(
    val id: Int,
    val label: String,
    val type: Int,
)

/**
 * Bus e controller condiviso tra [ControfaseService] e la UI Compose.
 */
object ControfaseBus {
    val engine: ControfaseEngine = ControfaseEngine()

    private val _snapshot = MutableStateFlow(engine.snapshot())
    val snapshot: StateFlow<ControfaseSnapshot> = _snapshot

    init {
        engine.onSnapshot = { snap -> _snapshot.value = snap }
    }

    val error = MutableStateFlow<String?>(null)
    val inputs = MutableStateFlow<List<AudioDeviceChoice>>(emptyList())
    val outputs = MutableStateFlow<List<AudioDeviceChoice>>(emptyList())
    val selectedInputId = MutableStateFlow<Int?>(null)
    val selectedOutputId = MutableStateFlow<Int?>(null)

    fun refreshDevices(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        inputs.value = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            .map { AudioDeviceChoice(it.id, formatDeviceLabel(it, input = true), it.type) }
        outputs.value = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            .map { AudioDeviceChoice(it.id, formatDeviceLabel(it, input = false), it.type) }
    }

    private fun formatDeviceLabel(dev: AudioDeviceInfo, input: Boolean): String {
        val kind = when (dev.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfono telefono"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Altoparlante telefono"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Capsula auricolare"
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> if (input) "Mic jack" else "Cuffie/cassa jack"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> if (input) "Mic USB-C" else "Audio USB-C"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> if (input) "Ingresso" else "Uscita"
        }
        val prod = dev.productName?.toString()?.trim().orEmpty()
        return if (prod.isNotEmpty() && !prod.equals(kind, ignoreCase = true)) "$kind ($prod)" else kind
    }
}
