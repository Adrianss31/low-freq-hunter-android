package io.github.adrianss31.lowfreqhunter

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import io.github.adrianss31.lowfreqhunter.data.LfhDb
import io.github.adrianss31.lowfreqhunter.data.MarkerEntity
import io.github.adrianss31.lowfreqhunter.data.MeasurementContext
import io.github.adrianss31.lowfreqhunter.data.SampleEntity
import io.github.adrianss31.lowfreqhunter.data.SessionEntity
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.data.SliceEntity
import io.github.adrianss31.lowfreqhunter.data.SurveyEntity
import io.github.adrianss31.lowfreqhunter.data.SurveyPointEntity
import io.github.adrianss31.lowfreqhunter.data.EventEntity
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import io.github.adrianss31.lowfreqhunter.engine.EventData
import io.github.adrianss31.lowfreqhunter.service.MonitorBus
import io.github.adrianss31.lowfreqhunter.ui.AppShell
import io.github.adrianss31.lowfreqhunter.ui.LfhTheme
import io.github.adrianss31.lowfreqhunter.ui.Shell
import io.github.adrianss31.lowfreqhunter.ui.Sheet
import io.github.adrianss31.lowfreqhunter.ui.Tab
import io.github.adrianss31.lowfreqhunter.ui.Typefaces
import io.github.adrianss31.lowfreqhunter.ui.monthKeyOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.Calendar
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fotografie della UI con dati sintetici (Robolectric, grafica nativa): non
 * verificano nulla da sole, servono a guardare il redesign senza un
 * dispositivo. I PNG finiscono in build/reports/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class UiScreenshotTest {

    private val out = File("build/reports/screenshots").apply { mkdirs() }
    private val json = Json { encodeDefaults = true }
    private val rnd = Random(7)

    private fun frame(t: Long, a: Double, b: Double): MonitorBus.SpectrumFrame {
        val binHz = 48000.0 / 32768
        val n = (2000 / binHz).toInt()
        val spec = FloatArray(n) { i ->
            val f = i * binHz
            var lin = Math.pow(10.0, (-94 + 18 * exp(-f / 14) + rnd.nextDouble() * 3) / 10)
            for ((c, l) in listOf(50.0 to a + 7, 100.0 to b + 7, 150.0 to b - 6, 200.0 to a - 14)) {
                lin += Math.pow(10.0, l / 10) * exp(-((f - c) / 1.3) * ((f - c) / 1.3))
            }
            (10 * Math.log10(lin)).toFloat()
        }
        return MonitorBus.SpectrumFrame(spec, binHz, t)
    }

    private fun slice(a: Double, b: Double): ByteArray = ByteArray(64) { k ->
        val f = 20 + k / 64.0 * 180
        var v = -92 + 16 * exp(-(f - 20) / 18) + rnd.nextDouble() * 4
        v = max(v, max(a - ((f - 50) / 2.2) * ((f - 50) / 2.2), b - ((f - 100) / 2.2) * ((f - 100) / 2.2)))
        (((v + 110) / 90 * 255).coerceIn(0.0, 255.0)).toInt().toByte()
    }

    private fun seed() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        SettingsRepo.get(ctx).update {
            it.copy(
                continuous = it.continuous.copy(enabled = true, splitMin = 21 * 60, split2Enabled = true, split2Min = 9 * 60),
                lan = it.lan.copy(enabled = true, token = "abc123"),
                engine = it.engine.copy(clipsEnabled = true),
                context = MeasurementContext("Camera da letto", "Comodino, verticale, mic verso muro nord", "Finestre chiuse, frigo acceso"),
            )
        }
        val dao = LfhDb.get(ctx).dao()
        val cfg = json.encodeToString(EngineCfg())
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -40)
        }
        var day = 0
        while (true) {
            for (night in listOf(false, true)) {
                val start = cal.timeInMillis + (if (night) 12 * 3600_000L else 0)
                val end = start + 12 * 3600_000L
                if (end > now - 3600_000L) return@runBlocking
                if (rnd.nextDouble() < 0.06) continue
                val id = "s$day${if (night) "N" else "G"}"
                val wk = Calendar.getInstance().apply { timeInMillis = start }.get(Calendar.DAY_OF_WEEK) in 2..6
                val samples = ArrayList<SampleEntity>()
                val events = ArrayList<EventEntity>()
                var openA: Long? = null
                var peakA = -200.0
                for (m in 0 until 720) {
                    val t = start / 1000 + m * 60L
                    val clock = ((if (night) 21 else 9) + m / 60) % 24
                    val nightBurst = clock in 1..4 && wk
                    val a = -68 + (if (nightBurst) 14.0 * (0.6 + 0.4 * rnd.nextDouble()) else 0.0) +
                        (if (clock in 14..15 && rnd.nextDouble() > 0.55) 12 * rnd.nextDouble() + 3 else 0.0) + (rnd.nextDouble() - 0.5) * 6
                    val b = -71 + (if (rnd.nextDouble() < 0.08) 13 * rnd.nextDouble() else 0.0) + (if (clock in 9..10 && wk) 9 * rnd.nextDouble() else 0.0) + (rnd.nextDouble() - 0.5) * 4
                    samples.add(SampleEntity(id, t, "{\"A\":${"%.1f".format(java.util.Locale.US, a)},\"B\":${"%.1f".format(java.util.Locale.US, b)}}", -60.0, 50.0, null, 80))
                    if (a > -55) {
                        if (openA == null) openA = t
                        peakA = max(peakA, a)
                    } else if (openA != null) {
                        events.add(EventEntity("$id-e${events.size}", id, "A", openA, t, t - openA, peakA, peakA - 3))
                        openA = null
                        peakA = -200.0
                    }
                }
                dao.upsertSession(
                    SessionEntity(
                        id, (if (night) "Notte " else "Giorno ") + "x", start, end, end / 1000, cfg, 48000, 1.46, "UNPROCESSED",
                        eventsCount = events.size, contextJson = json.encodeToString(MeasurementContext("Camera da letto")),
                    ),
                )
                dao.insertSamples(samples)
                events.forEach { dao.insertEvent(it) }
                for (k in 0 until 144) {
                    val sm = samples[minOf(719, k * 5)]
                    val a = sm.lvJson.substringAfter("\"A\":").substringBefore(",").toDouble()
                    val b = sm.lvJson.substringAfter("\"B\":").substringBefore("}").toDouble()
                    dao.insertSlice(SliceEntity(id, start / 1000 + k * 300L + 300, slice(a, b)))
                }
                if (rnd.nextDouble() < 0.4) dao.insertMarker(MarkerEntity("$id-m", id, start / 1000 + 7200, "nota: spento climatizzatore"))
                if (rnd.nextDouble() < 0.2) {
                    dao.insertEvent(EventEntity("$id-g", id, "gap", start / 1000 + 16000, start / 1000 + 16180, 180, null, null))
                }
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
            day++
        }
    }

    private fun seedMap() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val dao = LfhDb.get(ctx).dao()
        dao.upsertSurvey(SurveyEntity("m1", "Piano terra", 1, null, json.encodeToString(EngineCfg())))
        dao.upsertSurvey(SurveyEntity("m2", "Cantina", 2, null, json.encodeToString(EngineCfg())))
        val pts = listOf(
            Triple(.18f, .22f, -51.0), Triple(.36f, .3f, -54.0), Triple(.2f, .48f, -56.0), Triple(.62f, .2f, -61.0),
            Triple(.82f, .3f, -64.0), Triple(.6f, .45f, -59.0), Triple(.22f, .8f, -52.0), Triple(.5f, .78f, -57.0),
            Triple(.84f, .8f, -66.0), Triple(.84f, .62f, -63.0),
        )
        pts.forEachIndexed { i, (x, y, a) ->
            dao.insertSurveyPoint(SurveyPointEntity("p$i", "m1", x, y, "{\"A\":$a,\"B\":${a - 10}}", null, i.toLong(), 10))
        }
    }

    private fun liveBus(rec: Boolean) {
        val now = System.currentTimeMillis()
        val start = now - 8040_000L
        MonitorBus.resetSession()
        if (!rec) {
            MonitorBus.state.value = MonitorBus.State()
            MonitorBus.spectrum.value = null
            return
        }
        MonitorBus.state.value = MonitorBus.State(
            running = true, mode = "rec", sessionId = "live", startedAt = start, eventsCount = 4,
            activeBands = mapOf("A" to now / 1000 - 190), levels = mapOf("A" to -52.4, "B" to -61.2),
            domHz = 99.9, audioSource = "UNPROCESSED", batteryPct = 87,
        )
        MonitorBus.slices.value = (0 until 134).map { i ->
            Pair(start / 1000 + i * 60L, slice(-64 + 10 * max(0.0, sin(i / 14.0)), -71 + 6 * max(0.0, sin(i / 19.0 + 1))))
        }
        val s0 = start / 1000
        MonitorBus.events.value = listOf(
            EventData("A", s0 + 21 * 60, s0 + 29 * 60, 480, -50.2, -52.0),
            EventData("A", s0 + 62 * 60, s0 + 81 * 60, 1140, -47.9, -50.0),
            EventData("B", s0 + 70 * 60, s0 + 76 * 60, 360, -53.4, -54.0),
            EventData("A", s0 + 108 * 60, s0 + 117 * 60, 540, -51.6, -53.0),
        )
        MonitorBus.notes.value = listOf(Pair(s0 + 40 * 60, "spento climatizzatore"))
    }

    private fun shoot(name: String, shell: Shell, steps: Int = 60, live: Boolean = false) {
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val act = ctl.get()
        Typefaces.init(act)
        act.setContent { LfhTheme { AppShell(shell) } }
        var t = System.currentTimeMillis()
        repeat(steps) { i ->
            if (live && i % 5 == 0) {
                t += 250
                MonitorBus.spectrum.value = frame(t, -52.0 + 2 * sin(i / 7.0), -61.0 + sin(i / 5.0))
            }
            Thread.sleep(25)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        val v = act.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ctl.pause().stop().destroy()
    }

    @Test
    fun screens() {
        // solo su richiesta (workflow "UI screenshots"): lento e non è un controllo
        org.junit.Assume.assumeTrue(System.getenv("LFH_SCREENSHOTS") == "1")
        seed()
        seedMap()

        liveBus(rec = true)
        shoot("01_monitor_rec", Shell(), steps = 80, live = true)
        shoot("02_monitor_edit", Shell().apply { selBand = "A"; bandEdit = true }, steps = 80, live = true)

        liveBus(rec = false)
        shoot("03_archivio", Shell().apply { tab = Tab.ARCHIVE }, steps = 160)
        val latest = runBlocking { LfhDb.get(ApplicationProvider.getApplicationContext()).dao().sessionsList().first() }
        shoot("04_sessione", Shell().apply { tab = Tab.ARCHIVE; sheet = Sheet.Session(latest.id) }, steps = 140)
        shoot("05_dossier", Shell().apply { tab = Tab.ARCHIVE; sheet = Sheet.Dossier(monthKeyOf(System.currentTimeMillis())) }, steps = 80)
        shoot("06_mappa", Shell().apply { tab = Tab.MAP; mapSel = "m1" }, steps = 80)
        shoot("07_setup_programma", Shell().apply { tab = Tab.SETUP; setupOpen = "prog" }, steps = 80)
        shoot("08_setup_sensori", Shell().apply { tab = Tab.SETUP; setupOpen = "sens" }, steps = 80)
        shoot("09_setup_sistema", Shell().apply { tab = Tab.SETUP; setupOpen = "sys" }, steps = 80)
        shoot("10_contesto", Shell().apply { sheet = Sheet.Context }, steps = 80)
        shoot("11_monitor_standby", Shell(), steps = 60)
    }
}
