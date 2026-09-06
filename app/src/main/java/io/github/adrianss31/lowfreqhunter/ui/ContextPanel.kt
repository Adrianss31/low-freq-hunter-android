package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.adrianss31.lowfreqhunter.data.*
import io.github.adrianss31.lowfreqhunter.service.MonitorService
import kotlinx.coroutines.launch

@Composable
fun ContextPanel(recording: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsRepo.get(ctx).flow.collectAsState(initial = AppSettings())
    var room by remember(settings.context) { mutableStateOf(settings.context.room) }
    var position by remember(settings.context) { mutableStateOf(settings.context.position) }
    var conditions by remember(settings.context) { mutableStateOf(settings.context.conditions) }
    val notes by io.github.adrianss31.lowfreqhunter.service.MonitorBus.notes.collectAsState()
    var note by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    Panel(Modifier.fillMaxWidth()) {
        CapsLabel(if (recording) "Annota un'osservazione o un'azione" else "Contesto della prossima registrazione")
        if (recording) {
            OutlinedTextField(note, { note = it.take(2000) }, label = { Text("Es. spento climatizzatore, rumore diminuito") }, modifier = Modifier.fillMaxWidth())
            HwButton("salva nota") {
                if (note.isNotBlank()) {
                    MonitorService.instance?.addMarker("nota: ${note.trim()}")
                    note = ""
                    status = "Nota aggiunta con l'orario attuale"
                }
            }
            notes.takeLast(5).forEach { (t, text) -> Text("${fmtClock(t * 1000)} · $text", color = Lfh.TextDim) }
        } else {
            OutlinedTextField(room, { room = it.take(2000) }, label = { Text("Stanza") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(position, { position = it.take(2000) }, label = { Text("Posizione e orientamento del telefono") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(conditions, { conditions = it.take(2000) }, label = { Text("Finestre, impianti, altre condizioni") }, modifier = Modifier.fillMaxWidth())
            HwButton("salva contesto") { scope.launch {
                runCatching { SettingsRepo.get(ctx).update { it.copy(context = MeasurementContext(room, position, conditions)) } }
                    .onSuccess { status = "Contesto salvato per le prossime sessioni" }
                    .onFailure { status = it.message.orEmpty() }
            } }
        }
        if (status.isNotBlank()) Text(status, color = Lfh.TextDim)
        Spacer(Modifier.height(4.dp))
    }
}
