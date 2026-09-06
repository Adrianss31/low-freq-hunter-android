package io.github.adrianss31.lowfreqhunter.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import io.github.adrianss31.lowfreqhunter.update.AppUpdater

@Composable
fun UpdatePanel() {
    val ctx = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    val bus by MonitorBus.state.collectAsState()
    var message by remember { mutableStateOf("") }
    var requested by remember { mutableStateOf(false) }
    fun install() {
        if (MonitorBus.state.value.running) { message = "Termina prima la registrazione dalla scheda Notte."; return }
        val file = AppUpdater.state.value.apk ?: return
        runCatching {
            AppUpdater.validateApk(ctx, file)
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            ctx.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            message = "Conferma l'installazione nella schermata Android. Se annulli, puoi riprovare."
        }.onFailure { message = "Installazione non avviata: ${it.message}" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (requested && ctx.packageManager.canRequestPackageInstalls()) install()
        else message = "Autorizza questa app a installare aggiornamenti, poi riprova."
        requested = false
    }
    LaunchedEffect(Unit) { if (state.message.isEmpty()) AppUpdater.check(ctx) }
    // The explicit update tap authorizes download followed by the Android installer.
    var installAfterDownload by remember { mutableStateOf(false) }
    LaunchedEffect(state.apk, state.busy) {
        if (installAfterDownload && !state.busy) {
            installAfterDownload = false
            if (state.apk != null) {
                if (ctx.packageManager.canRequestPackageInstalls()) install()
                else {
                    requested = true
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        if (state.release != null || state.busy || message.isNotEmpty()) {
            Text(state.message, color = Lfh.TextDim)
            if (state.busy) HwButton("annulla download") { AppUpdater.cancel() }
            else if (state.release != null) HwButton(if (bus.running) "aggiornamento pronto · termina prima REC" else "aggiorna") {
                if (bus.running) message = "Termina prima la registrazione dalla scheda Notte."
                else if (state.apk == null) { installAfterDownload = true; AppUpdater.download(ctx) }
                else if (ctx.packageManager.canRequestPackageInstalls()) install()
                else {
                    requested = true
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                }
            }
            if (message.isNotEmpty()) Text(message, color = Lfh.TextDim)
        }
    }
}
