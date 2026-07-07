package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette "strumento hardware" (Teenage Engineering / Nothing):
// quasi-nero, superfici piatte, accento rosso REC, teal per i dati.
object Lfh {
    val Bg = Color(0xFF0A0A0C)
    val Surface = Color(0xFF121214)
    val Surface2 = Color(0xFF1A1A1E)
    val Border = Color(0xFF2A2A30)
    val Text = Color(0xFFE8E8EC)
    val TextDim = Color(0xFF8A8A96)
    val TextFaint = Color(0xFF4A4A54)
    val Rec = Color(0xFFFF3B30)
    val Accent = Color(0xFF00D4AA)
    val Amber = Color(0xFFFFAA00)

    // colore banda per lettera (A, B, C, ... come nella PWA)
    private val palette = listOf(
        Color(0xFFFFAA00), Color(0xFF00D4AA), Color(0xFF9988FF), Color(0xFFFF6688),
        Color(0xFF55AAFF), Color(0xFFAAEE44), Color(0xFFFF8844), Color(0xFF44DDEE),
    )

    fun bandColor(id: String): Color {
        val i = (id.firstOrNull()?.code ?: 65) - 65
        return palette[((i % palette.size) + palette.size) % palette.size]
    }

    val VibColor = Color(0xFFDDDDE4) // canale accelerometro "V"
}

private val ColorScheme = darkColorScheme(
    primary = Lfh.Accent,
    background = Lfh.Bg,
    surface = Lfh.Surface,
    onPrimary = Color.Black,
    onBackground = Lfh.Text,
    onSurface = Lfh.Text,
)

@Composable
fun LfhTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme() // tema unico scuro, ignora il sistema
    MaterialTheme(colorScheme = ColorScheme, content = content)
}
