package io.github.adrianss31.lowfreqhunter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.RoundedCorner
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.adrianss31.lowfreqhunter.ui.Lfh
import io.github.adrianss31.lowfreqhunter.ui.LfhTheme
import io.github.adrianss31.lowfreqhunter.ui.LiveScreen
import io.github.adrianss31.lowfreqhunter.ui.NightScreen
import io.github.adrianss31.lowfreqhunter.ui.SettingsScreen
import io.github.adrianss31.lowfreqhunter.ui.SummaryScreen
import io.github.adrianss31.lowfreqhunter.ui.TabBar
import io.github.adrianss31.lowfreqhunter.widget.LiveWidgetController

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_WIDGET_LIVE_TOGGLE = "io.github.adrianss31.lowfreqhunter.WIDGET_LIVE_TOGGLE"
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        handleToggleIntent(intent)
        setContent {
            LfhTheme {
                Root()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleToggleIntent(intent)
    }

    /** Tap sul disco del widget: avvia/ferma il Live (qui l'app è in primo piano). */
    private fun handleToggleIntent(intent: Intent?) {
        if (intent?.action == ACTION_WIDGET_LIVE_TOGGLE) {
            LiveWidgetController.toggle(this)
        }
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}

/**
 * Raggio di arrotondamento reale del display (Android 12+, API 31+).
 * Legge i 4 angoli e prende il massimo; fallback 28dp sui device senza dato.
 */
@Composable
private fun roundedDisplayRadius(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val radiusPx = if (Build.VERSION.SDK_INT >= 31) {
        val rc = view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
            ?: view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
            ?: view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
            ?: view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
        rc?.radius?.toFloat() ?: -1f
    } else -1f
    return if (radiusPx > 0f) with(density) { radiusPx.toDp() } else 28.dp
}

@Composable
private fun Root() {
    val view = LocalView.current
    val radius = roundedDisplayRadius()
    // Lo sfondo della finestra = Bg: gli spicchi d'angolo fuori dal ritaglio si fondono.
    LaunchedEffect(Unit) {
        (view.context as? android.app.Activity)?.window?.setBackgroundDrawable(
            ColorDrawable(Lfh.Bg.toArgb()),
        )
    }
    var tab by remember { mutableIntStateOf(1) } // parte su NOTTE
    Column(
        Modifier
            .fillMaxSize()
            .background(Lfh.Bg)
            .clip(RoundedCornerShape(radius))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> LiveScreen()
                1 -> NightScreen()
                2 -> SummaryScreen()
                else -> SettingsScreen()
            }
        }
        TabBar(listOf("Live", "Notte", "Log", "Setup"), tab) { tab = it }
    }
}
