package io.github.adrianss31.lowfreqhunter.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scrive una clip WAV 16-bit mono dal PCM float della cattura in corso.
 * WAV invece di AAC: nessun codec, nessun secondo handle sul microfono,
 * riproducibile ovunque (~1.9 MB per 20 s a 48 kHz).
 */
class ClipWriter(private val file: File, private val sampleRate: Int, private val maxSeconds: Int) {

    private var raf: RandomAccessFile? = null
    private var written = 0
    private val maxBytes = sampleRate * 2 * maxSeconds

    val isOpen: Boolean get() = raf != null
    val isFull: Boolean get() = written >= maxBytes

    fun open() {
        val f = RandomAccessFile(file, "rw")
        f.setLength(0)
        f.write(ByteArray(44)) // header scritto alla chiusura
        raf = f
        written = 0
    }

    fun append(pcm: FloatArray, n: Int) {
        val f = raf ?: return
        if (written >= maxBytes) return
        val count = minOf(n, (maxBytes - written) / 2)
        val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val v = (pcm[i] * 32767f).toInt().coerceIn(-32768, 32767)
            buf.putShort(v.toShort())
        }
        f.write(buf.array())
        written += count * 2
    }

    fun close() {
        val f = raf ?: return
        raf = null
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + written)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)                       // PCM
        header.putShort(1)                       // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)            // byte rate
        header.putShort(2)                       // block align
        header.putShort(16)                      // bit depth
        header.put("data".toByteArray())
        header.putInt(written)
        f.seek(0)
        f.write(header.array())
        f.close()
    }
}
