package io.github.adrianss31.lowfreqhunter.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.adrianss31.lowfreqhunter.data.*
import kotlinx.coroutines.launch

@Composable
fun LlmExportPanel(sessions: List<SessionEntity>, activeId: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var raw by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    Panel(Modifier.fillMaxWidth()) {
        HwButton("dossier per LLM") { expanded = !expanded }
        if (expanded) {
            Text("Scegli fino a 31 sessioni concluse. Il dossier resta locale: caricalo nella chat che preferisci.", color = Lfh.TextDim)
            sessions.filter { it.endedAt != null && it.id != activeId }.forEach { s ->
                Row {
                    Checkbox(s.id in selected, { checked -> if (!busy) selected = if (checked && selected.size < 31) selected + s.id else selected - s.id }, enabled = !busy)
                    Text(s.label, color = Lfh.Text)
                }
            }
            Row { Checkbox(raw, { raw = it }, enabled = !busy); Text("Includi campioni al secondo", color = Lfh.Text) }
            Row { Checkbox(audio, { audio = it }, enabled = !busy); Text("Includi le prime 3 clip per sessione (possono contenere voci)", color = Lfh.Text) }
            HwButton(if (busy) "preparazione…" else "crea e condividi ZIP") {
                if (!busy && selected.isNotEmpty()) {
                    busy = true; status = ""
                    val ids = sessions.filter { it.id in selected }.map { it.id }
                    scope.launch {
                        try {
                            val file = LlmExporter.export(ctx, ids, raw, audio)
                            Exporter.shareFile(ctx, file, "application/zip")
                            status = "Dossier pronto: ${ids.size} sessioni. Puoi salvarlo dalla condivisione."
                        } catch (e: Exception) { status = "Esportazione non completata: ${e.message}" }
                        finally { busy = false }
                    }
                }
            }
            if (status.isNotEmpty()) Text(status, color = Lfh.TextDim)
        }
    }
}
