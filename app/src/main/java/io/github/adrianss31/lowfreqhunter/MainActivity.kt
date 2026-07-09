package io.github.adrianss31.lowfreqhunter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import io.github.adrianss31.lowfreqhunter.ui.Lfh
import io.github.adrianss31.lowfreqhunter.ui.LfhTheme
import io.github.adrianss31.lowfreqhunter.ui.LiveScreen
import io.github.adrianss31.lowfreqhunter.ui.NightScreen
import io.github.adrianss31.lowfreqhunter.ui.SettingsScreen
import io.github.adrianss31.lowfreqhunter.ui.SummaryScreen
import io.github.adrianss31.lowfreqhunter.ui.SurveyScreen
import io.github.adrianss31.lowfreqhunter.ui.TabBar

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        setContent {
            LfhTheme {
                Root()
            }
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

@Composable
private fun Root() {
    var tab by remember { mutableIntStateOf(1) } // parte su NOTTE
    Column(
        Modifier
            .fillMaxSize()
            .background(Lfh.Bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> LiveScreen()
                1 -> NightScreen()
                2 -> SummaryScreen()
                3 -> SurveyScreen()
                else -> SettingsScreen()
            }
        }
        TabBar(listOf("Live", "Notte", "Log", "Mappa", "Setup"), tab) { tab = it }
    }
}
