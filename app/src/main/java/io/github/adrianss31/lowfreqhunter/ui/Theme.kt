package io.github.adrianss31.lowfreqhunter.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import io.github.adrianss31.lowfreqhunter.R
import io.github.adrianss31.lowfreqhunter.engine.Palette

/**
 * Palette "capsula": corpo dello strumento grigio caldo, display scuri
 * incassati, tasti chiari in rilievo, un solo accento arancio per REC e
 * per ciò che supera la soglia.
 */
object Lfh {
    // corpo e tasti
    val Bg = Color(0xFFD9D7D0)
    val Card = Color(0xFFE6E4DE)
    val CardOpen = Color(0xFFECEBE5)
    val Key = Color(0xFFEFEEE9)
    val Track = Color(0xFFCFCDC6)
    val Track2 = Color(0xFFD2D0C9)
    val Field = Color(0xFFF4F3EF)

    // inchiostro sul corpo chiaro
    val Ink = Color(0xFF1B1B19)
    val InkDim = Color(0xFF5D5B55)
    val InkFaint = Color(0xFF8A8880)

    // display scuri
    val Panel = Color(0xFF10100E)
    val Panel2 = Color(0xFF22211E)
    val Panel3 = Color(0xFF2A2925)
    val PanelLine = Color(0xFF262521)
    val Paper = Color(0xFFEFECE3)
    val PaperDim = Color(0xFF8D8A80)
    val PaperFaint = Color(0xFF6B6860)
    val WfBg = Color(0xFF05060F)

    // accenti
    val Orange = Color(0xFFFF5A1F)
    val OrangeInk = Color(0xFFB8380A)
    val Amber = Color(0xFFFFB000)

    /** Colore banda per lettera (stessa palette di report e dashboard). */
    fun bandColor(id: String): Color = Color(Palette.bandColorInt(id))

    /** Canale vibrazioni: grigio lavanda leggibile sia sul chiaro sia sullo scuro. */
    val VibColor = Color(0xFFA8A6B4)

    fun channelColor(id: String): Color =
        if (id == io.github.adrianss31.lowfreqhunter.engine.Channels.VIB) VibColor else bandColor(id)
}

/** Geist (sans) e Geist Mono sono font variabili: il peso arriva dall'asse wght. */
val Geist = FontFamily(
    Font(R.font.geist, FontWeight.Normal),
    Font(R.font.geist, FontWeight.Medium),
    Font(R.font.geist, FontWeight.SemiBold),
    Font(R.font.geist, FontWeight.Bold),
    Font(R.font.geist, FontWeight.ExtraBold),
)

val GeistMono = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal),
    Font(R.font.geist_mono, FontWeight.Medium),
    Font(R.font.geist_mono, FontWeight.SemiBold),
)

/** Font dot-matrix per i numeri grandi (Doto, OFL). */
val DotFont = FontFamily(Font(R.font.doto, weight = FontWeight.Bold))

/** Typeface nativi per i disegni su Canvas (etichette di assi e griglie). */
object Typefaces {
    var mono: Typeface = Typeface.MONOSPACE
        private set
    var dot: Typeface = Typeface.MONOSPACE
        private set

    fun init(ctx: Context) {
        runCatching { ResourcesCompat.getFont(ctx, R.font.geist_mono) }.getOrNull()?.let { mono = it }
        runCatching { ResourcesCompat.getFont(ctx, R.font.doto) }.getOrNull()?.let { dot = it }
    }
}

private val ColorScheme = lightColorScheme(
    primary = Lfh.Ink,
    onPrimary = Lfh.Paper,
    background = Lfh.Bg,
    surface = Lfh.Card,
    onBackground = Lfh.Ink,
    onSurface = Lfh.Ink,
)

@Composable
fun LfhTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme) {
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = Geist, color = Lfh.Ink),
            content = content,
        )
    }
}
