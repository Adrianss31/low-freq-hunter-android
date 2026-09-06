package io.github.adrianss31.lowfreqhunter.update

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AppUpdater {
    data class Release(val tag: String, val url: String, val size: Long, val digest: String?)
    data class State(val message: String = "", val busy: Boolean = false, val release: Release? = null, val apk: File? = null)
    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private const val API = "https://api.github.com/repos/Adrianss31/low-freq-hunter-android/releases/latest"
    private const val MAX_APK = 150L * 1024 * 1024

    fun isNewerTag(candidate: String, installed: String): Boolean {
        fun parse(v: String): List<Int>? {
            val m = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(v) ?: return null
            return m.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
        }
        val c = parse(candidate) ?: return false
        val i = parse(installed) ?: return true // APK versionCode is always checked before installation.
        for (n in c.indices) if (c[n] != i[n]) return c[n] > i[n]
        return false
    }

    private fun connect(address: String): HttpURLConnection {
        var current = URL(address)
        repeat(6) {
            require(current.protocol == "https" && (current.host == "api.github.com" || current.host == "github.com" || current.host.endsWith(".githubusercontent.com"))) { "Destinazione aggiornamento non consentita" }
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "LowFreqHunter-Android")
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location") ?: error("Redirect senza destinazione")
                c.disconnect(); current = URL(current, location)
            } else {
                if (code != 200) { c.disconnect(); error(if (code == 403 || code == 429) "Limite GitHub raggiunto: riprova più tardi" else "GitHub: HTTP $code") }
                return c
            }
        }
        error("Troppi reindirizzamenti")
    }

    @Synchronized fun check(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        state.value = State("Ricerca aggiornamenti…", busy = true)
        job = scope.launch {
            try {
                val c = connect(API)
                val raw = try { c.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        require(output.size() + n <= 2_000_000) { "Risposta GitHub troppo grande" }
                        output.write(buffer, 0, n)
                    }
                    val bytes = output.toByteArray()
                    require(bytes.size <= 2_000_000) { "Risposta GitHub troppo grande" }
                    bytes.toString(Charsets.UTF_8)
                } } finally { c.disconnect() }
                val obj = Json.parseToJsonElement(raw).jsonObject
                require(obj["prerelease"]?.jsonPrimitive?.booleanOrNull != true && obj["draft"]?.jsonPrimitive?.booleanOrNull != true)
                val tag = obj.getValue("tag_name").jsonPrimitive.content
                val installed = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
                if (!isNewerTag(tag, installed)) { state.value = State("L'app è aggiornata ($installed)"); return@launch }
                val asset = obj.getValue("assets").jsonArray.map { it.jsonObject }
                    .singleOrNull { it["name"]?.jsonPrimitive?.content == "lowfreqhunter.apk" }
                    ?: error("La release non contiene l'APK")
                val url = asset.getValue("browser_download_url").jsonPrimitive.content
                require(url.startsWith("https://github.com/Adrianss31/low-freq-hunter-android/releases/download/"))
                val size = asset.getValue("size").jsonPrimitive.long
                require(size in 1..MAX_APK) { "Dimensione APK non valida" }
                val release = Release(tag, url, size, asset["digest"]?.jsonPrimitive?.contentOrNull)
                state.value = State("Disponibile $tag", release = release)
            } catch (e: Exception) { state.value = State("Controllo non riuscito: ${e.message}") }
        }
    }

    @Synchronized fun download(context: Context) {
        if (job?.isActive == true) return
        val release = state.value.release ?: return
        val app = context.applicationContext
        job = scope.launch {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            val partial = File(dir, "update.part")
            val apk = File(dir, "update.apk")
            state.value = State("Download…", true, release)
            try {
                val c = connect(release.url)
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    c.inputStream.use { input -> partial.outputStream().use { output ->
                        val buf = ByteArray(65536)
                        var total = 0L
                        var lastPercent = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf); if (n < 0) break
                            total += n
                            require(total <= release.size && total <= MAX_APK) { "Download troppo grande" }
                            output.write(buf, 0, n); digest.update(buf, 0, n)
                            val percent = (total * 100 / release.size).toInt()
                            if (percent != lastPercent) { state.value = State("Download $percent%", true, release); lastPercent = percent }
                        }
                        require(total == release.size) { "Download incompleto" }
                    } }
                } finally { c.disconnect() }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                release.digest?.let { require(it.equals("sha256:$hash", ignoreCase = true)) { "Integrità APK non verificata" } }
                validateApk(app, partial)
                apk.delete(); check(partial.renameTo(apk)) { "Impossibile preparare APK" }
                state.value = State("${release.tag} pronta da installare", release = release, apk = apk)
            } catch (e: Exception) {
                partial.delete()
                state.value = State(if (e is CancellationException) "Download annullato" else "Aggiornamento non riuscito: ${e.message}", release = release)
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun validateApk(context: Context, file: File) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("APK non leggibile")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive.packageName == context.packageName) { "APK di un'altra app" }
        require(archive.longVersionCode > installed.longVersionCode) { "Versione già installata o precedente" }
        val expected = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val actual = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        require(expected.isNotEmpty() && expected == actual) { "Firma diversa: l'aggiornamento non è compatibile con questa installazione" }
    }
}
