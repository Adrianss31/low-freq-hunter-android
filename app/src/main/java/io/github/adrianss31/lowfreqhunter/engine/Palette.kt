package io.github.adrianss31.lowfreqhunter.engine

/**
 * Colori come Int ARGB puri (usabili sia da Compose sia da android.graphics
 * nel report PNG). Stessa palette per lettera della PWA.
 */
object Palette {
    private val bands = intArrayOf(
        0xFFFFAA00.toInt(), 0xFF00D4AA.toInt(), 0xFF9988FF.toInt(), 0xFFFF6688.toInt(),
        0xFF55AAFF.toInt(), 0xFFAAEE44.toInt(), 0xFFFF8844.toInt(), 0xFF44DDEE.toInt(),
    )

    val VIB = 0xFFDDDDE4.toInt()

    fun bandColorInt(id: String): Int {
        if (id == Channels.VIB) return VIB
        val i = (id.firstOrNull()?.code ?: 65) - 65
        return bands[((i % bands.size) + bands.size) % bands.size]
    }

    // colormap tipo "inferno" del waterfall — stessi stop della PWA
    private val stops = arrayOf(
        intArrayOf(5, 10, 30), intArrayOf(30, 20, 90), intArrayOf(120, 30, 130),
        intArrayOf(210, 60, 120), intArrayOf(255, 140, 50), intArrayOf(255, 220, 100),
        intArrayOf(255, 255, 235),
    )

    fun wfColorInt(v: Float): Int {
        val c = v.coerceIn(0f, 1f) * (stops.size - 1)
        val i = c.toInt().coerceAtMost(stops.size - 2)
        val f = c - i
        val a = stops[i]
        val b = stops[i + 1]
        val r = (a[0] + (b[0] - a[0]) * f).toInt()
        val g = (a[1] + (b[1] - a[1]) * f).toInt()
        val bl = (a[2] + (b[2] - a[2]) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
