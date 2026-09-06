package io.github.adrianss31.lowfreqhunter

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import io.github.adrianss31.lowfreqhunter.data.*
import io.github.adrianss31.lowfreqhunter.engine.*
import io.github.adrianss31.lowfreqhunter.audio.ClipWriter
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PersistenceExportTest {
    @Test fun immediateStopDrainsSamplesEventsAndMarkers() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, LfhDb::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val recorder = SessionRecorder(db.dao(), scope, EngineCfg(), 48000, 1.46, "UNPROCESSED")
            recorder.onSample(SampleData(100, mapOf("A" to -30.0), -30.0, 50.0, null, 90))
            recorder.onEvent(EventData("A", 90, 100, 10, -20.0, -30.0))
            recorder.addMarker("nota: prova")
            recorder.closeAndJoin()
            scope.cancel()
            assertEquals(1, db.dao().samples(recorder.sessionId).size)
            assertEquals(1, db.dao().events(recorder.sessionId).size)
            assertEquals(1, db.dao().markers(recorder.sessionId).size)
            assertNotNull(db.dao().session(recorder.sessionId)!!.endedAt)
        } finally { scope.cancel(); db.close() }
    }

    @Test fun dossierContainsQuietMinutesGapsAndExactPeak() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dao = LfhDb.get(ctx).dao()
        val id = "export-test"
        dao.upsertSession(SessionEntity(id, "Test", 0, 180000, 121, Json.encodeToString(EngineCfg()), 48000, 1.46, "UNPROCESSED"))
        dao.insertSamples(listOf(
            SampleEntity(id, 1, "{\"A\":-80.0}", -80.0, 50.0, null, 100),
            SampleEntity(id, 121, "{\"A\":-20.0}", -20.0, 50.0, null, 99),
        ))
        dao.insertMarker(MarkerEntity("note", id, 121, "nota: spento, poi acceso"))
        val file = LlmExporter.export(ctx, listOf(id), raw = true, audio = false)
        ZipFile(file).use { zip ->
            fun read(name: String) = zip.getInputStream(zip.getEntry(name)).bufferedReader().readText()
            val minutes = read("sessioni/$id/andamento.csv")
            assertTrue(minutes.contains("\"1970-01-01T00:01:00Z\",\"A\",\"0\",\"\""))
            assertTrue(minutes.contains("-80.0")); assertTrue(minutes.contains("-20.0"))
            assertTrue(read("annotazioni.csv").contains("spento, poi acceso"))
            assertNotNull(zip.getEntry("sessioni/$id/campioni.csv"))
            assertTrue(read("riepilogo.json").contains("\"stored_sample_seconds\":2"))
        }
        file.delete()
        Unit
    }

    @Test fun partialClipHasAValidWavHeader() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = File(ctx.cacheDir, "partial.wav")
        val writer = ClipWriter(file, 48000, 20)
        writer.open(); writer.append(FloatArray(4800) { .1f }, 4800); writer.close()
        assertEquals(9644, file.length().toInt())
        assertEquals("RIFF", file.readBytes().take(4).toByteArray().toString(Charsets.US_ASCII))
        writer.close(); file.delete()
    }
}
