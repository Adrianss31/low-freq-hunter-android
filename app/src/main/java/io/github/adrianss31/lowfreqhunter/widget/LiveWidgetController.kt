package io.github.adrianss31.lowfreqhunter.widget

import android.content.Context
import android.util.Log
import io.github.adrianss31.lowfreqhunter.audio.CaptureEngine
import io.github.adrianss31.lowfreqhunter.data.SettingsRepo
import io.github.adrianss31.lowfreqhunter.dsp.Bands
import io.github.adrianss31.lowfreqhunter.engine.Channels
import io.github.adrianss31.lowfreqhunter.engine.EngineCfg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Proprietario della cattura LIVE pilotata dal widget home (stile Teenage
 * Engineering). È un singleton a livello di processo: il widget (e l'app, se
 * l'utente apre Live mentre il widget è attivo) condividono la stessa
 * istanza, così il microfono non viene aperto due volte.
 *
 * Android vieta di acquisire RECORD_AUDIO da un widget in background: il tap
 * sul disco lancia MainActivity con ACTION_WIDGET_LIVE_TOGGLE, che chiama
 * [toggle] — lì l'app è in primo piano e può prendere l'audio.
 *
 * Mentre il Live è attivo, una coroutine pinga il widget ~3/s (opzione "a")
 * così disco e LED seguono quasi in tempo reale; quando è fermo basta un
 * singolo update (opzione "b").
 */
object LiveWidgetController {

    private const val TAG = "LiveWidget"
    private const val PUSH_MS = 300L

    data class BandView(
        val id: String,
        val center: Double,
        val thr: Double,
        val db: Double?,
        val over: Boolean,
    )

    data class LiveWidgetState(
        val running: Boolean = false,
        val source: String = "",
        val bands: List<BandView> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var capture: CaptureEngine? = null
    private var pushJob: Job? = null
    private var cfg: EngineCfg = EngineCfg()
    private var appCtx: Context? = null

    private val _state = MutableStateFlow(LiveWidgetState())
    val state: StateFlow<LiveWidgetState> = _state

    fun isLive(): Boolean = capture != null

    /** Avvia/ferma il Live del widget. Deve essere chiamato con l'app in primo piano. */
    fun toggle(ctx: Context) {
        if (capture != null) stop() else start(ctx)
    }

    fun start(ctx: Context) {
        if (capture != null) return
        val appCtx = ctx.applicationContext
        this.appCtx = appCtx
        cfg = runBlocking { SettingsRepo.get(appCtx).flow.first() }.engine
        val cap = CaptureEngine(appCtx, cfg.fftSize, cfg.smoothLive)
        val ok = cap.start(scope) { spec, binHz, _ ->
            val bands = cfg.enabledBands().map { b ->
                val db = Bands.bandDb(spec, binHz, b.lo, b.hi)
                BandView(b.id, b.center, b.thr, db, db >= b.thr)
            }
            _state.value = _state.value.copy(running = true, source = cap.sourceName, bands = bands)
        }
        if (!ok) {
            Log.e(TAG, "Live widget: microfono non disponibile")
            cap.stop()
            _state.value = LiveWidgetState()
            return
        }
        capture = cap
        _state.value = LiveWidgetState(running = true, source = cap.sourceName,
            bands = cfg.enabledBands().map { b -> BandView(b.id, b.center, b.thr, null, false) })
        // Ping del widget a ~3/s
        pushJob?.cancel()
        pushJob = scope.launch {
            while (capture != null) {
                delay(PUSH_MS)
                LiveWidget.push(appCtx)
            }
        }
        LiveWidget.push(appCtx)
    }

    fun stop() {
        pushJob?.cancel()
        pushJob = null
        capture?.stop()
        capture = null
        _state.value = LiveWidgetState()
        // ultimo update per mostrare lo stato fermo
        appCtx?.let { LiveWidget.push(it) }
    }

    /** Usato da LiveScreen per fermare la cattura se l'utente chiude da lì. */
    fun ensureStopped() {
        if (capture != null) stop()
    }
}
