package app.bellterm.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** A release described by the update manifest on the download server. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val minSdk: Int,
    /** Release notes keyed by language ("en", "zh"), as plain text. */
    val notes: Map<String, String>,
) {
    /** Notes in [language], falling back to English, then to any language given. */
    fun notesFor(language: String): String = notes[language] ?: notes["en"] ?: notes.values.firstOrNull().orEmpty()

    /** Worth offering to a device running [installed] on API [sdk]. */
    fun offers(installed: Long, sdk: Int): Boolean = versionCode > installed && minSdk <= sdk
}

class UpdateException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Parses `latest.json`. The server is trusted no more than any other network
 * input: every field is checked, the download must come from [DOWNLOAD_HOST]
 * over HTTPS, and notes are capped and only ever shown as plain text.
 */
object UpdateManifest {
    const val URL = "https://dl.bellterm.app/latest.json"
    const val DOWNLOAD_HOST = "dl.bellterm.app"
    private const val MAX_NOTES = 2000

    fun parse(text: String): UpdateInfo {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw UpdateException("not JSON", e)
        }
        try {
            val versionCode = json.getLong("versionCode")
            val versionName = json.getString("versionName").trim()
            val url = json.getString("url")
            val sha256 = json.getString("sha256").lowercase()
            val size = json.optLong("size", 0)
            val minSdk = json.optInt("minSdk", 0)
            if (versionCode <= 0) throw UpdateException("bad versionCode")
            if (versionName.isEmpty() || versionName.length > 32 || !versionName.all { it.isLetterOrDigit() || it in ".-+" }) {
                throw UpdateException("bad versionName")
            }
            if (!isDownload(url)) throw UpdateException("bad url")
            if (sha256.length != 64 || !sha256.all { it in '0'..'9' || it in 'a'..'f' }) throw UpdateException("bad sha256")
            if (size < 0 || minSdk < 0) throw UpdateException("bad size or minSdk")
            val notes = json.optJSONObject("notes")?.let { byLanguage ->
                byLanguage.keys().asSequence()
                    .filter { key -> key.length in 2..8 && key.all { it.isLetter() || it == '-' } }
                    .associateWith { key -> byLanguage.optString(key).trim().take(MAX_NOTES) }
                    .filterValues { it.isNotEmpty() }
            }.orEmpty()
            return UpdateInfo(versionCode, versionName, url, sha256, size, minSdk, notes)
        } catch (e: JSONException) {
            throw UpdateException("missing field", e)
        }
    }

    private fun isDownload(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == DOWNLOAD_HOST && uri.userInfo == null && uri.port == -1
    }

    /** Whether the last automatic check, at [lastCheck] millis, is old enough to repeat at [now]. */
    fun due(lastCheck: Long, now: Long): Boolean = now - lastCheck !in 0 until CHECK_INTERVAL

    const val CHECK_INTERVAL = 24L * 60 * 60 * 1000
}

/** What the update check last found. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data object Failed : UpdateState
}

/**
 * Checks the download server for a newer release: at most daily on its own,
 * or whenever the user asks. Automatic checks fail silently; nothing is
 * downloaded here, the user opens the APK link in a browser.
 */
class UpdateChecker(private val context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val installed: Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    private val _state = MutableStateFlow<UpdateState>(cached()?.let(UpdateState::Available) ?: UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val _skipped = MutableStateFlow(prefs.getLong("skipped", 0))

    /** The version the user chose to skip; the banner stays quiet for it. */
    val skipped: StateFlow<Long> = _skipped

    fun skip(info: UpdateInfo) {
        prefs.edit().putLong("skipped", info.versionCode).apply()
        _skipped.value = info.versionCode
    }

    /** Checks now when [force]d, otherwise only once a day has passed. */
    suspend fun check(force: Boolean) {
        if (!force && !UpdateManifest.due(prefs.getLong("last_check", 0), System.currentTimeMillis())) return
        if (_state.value == UpdateState.Checking) return
        val previous = _state.value
        _state.value = UpdateState.Checking
        _state.value = try {
            val text = withContext(Dispatchers.IO) { fetch() }
            val info = UpdateManifest.parse(text)
            prefs.edit().putLong("last_check", System.currentTimeMillis()).putString("manifest", text).apply()
            if (info.offers(installed, Build.VERSION.SDK_INT)) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: IOException) {
            // A failed background check keeps whatever was known before.
            if (force) UpdateState.Failed else previous
        }
    }

    private fun cached(): UpdateInfo? =
        prefs.getString("manifest", null)
            ?.let { runCatching { UpdateManifest.parse(it) }.getOrNull() }
            ?.takeIf { it.offers(installed, Build.VERSION.SDK_INT) }

    private fun fetch(): String {
        val connection = (URL(UpdateManifest.URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_MANIFEST) throw IOException("manifest too large")
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_MANIFEST = 64 * 1024
    }
}
