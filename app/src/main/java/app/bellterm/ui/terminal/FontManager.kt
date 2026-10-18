package app.bellterm.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The faces the terminal draws with, and the icon font behind them. */
data class TerminalTypefaces(
    val regular: Typeface,
    val bold: Typeface,
    val italic: Typeface,
    val boldItalic: Typeface,
    /** Nerd Font icons for glyphs the chosen font lacks. */
    val symbols: Typeface,
)

/** A font the picker can offer. */
data class FontChoice(val id: String, val name: String, val catalog: CatalogFont?, val available: Boolean, val nerd: Boolean)

/**
 * Terminal fonts: the bundled default, catalogue fonts downloaded on request
 * (verified against pinned SHA-256 hashes before they are used), the system
 * monospace face, and TTF/OTF files the user imports. Loading happens off the
 * UI thread; the view only receives finished typefaces.
 */
class FontManager(private val context: Context) {
    private val dir = File(context.filesDir, "fonts").apply { mkdirs() }
    private val customDir = File(dir, "custom").apply { mkdirs() }
    private val _changes = MutableStateFlow(0)

    /** Bumped whenever the set of available fonts changes. */
    val changes: StateFlow<Int> = _changes

    fun choices(): List<FontChoice> =
        FontCatalog.fonts.map { FontChoice(it.id, it.name, it, available(it), nerd = true) } +
            FontChoice(SYSTEM, "System monospace", null, available = true, nerd = false) +
            customFiles().map { FontChoice(CUSTOM + it.name, it.nameWithoutExtension, null, available = true, nerd = false) }

    fun displayName(id: String): String = choices().firstOrNull { it.id == id }?.name ?: FontCatalog.fonts.first().name

    private fun customFiles(): List<File> =
        customDir.listFiles { file -> file.extension.lowercase() in setOf("ttf", "otf") }?.sortedBy { it.name }.orEmpty()

    private fun file(font: CatalogFont, style: FontFile) = File(dir, "${font.id}-${style.style.name}.ttf")

    /** Usable once its regular face is bundled or downloaded. */
    fun available(font: CatalogFont): Boolean =
        font.files.first { it.style == FontStyle.Regular }.let { it.asset != null || file(font, it).exists() }

    fun fullyDownloaded(font: CatalogFont): Boolean = font.files.all { it.asset != null || file(font, it).exists() }

    /** Downloads every style not yet on the device, verifying each before keeping it. */
    suspend fun download(font: CatalogFont, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val missing = font.files.filter { it.asset == null && !file(font, it).exists() }
        val total = missing.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        for (style in missing) {
            val url = style.url ?: continue
            val partial = File(dir, file(font, style).name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP ${connection.responseCode}")
                }
                var written = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > style.size) throw IOException("larger than expected")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            progress((done + written).toFloat() / total)
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (written != style.size || hash != style.sha256) throw IOException("checksum mismatch")
                if (!partial.renameTo(file(font, style))) throw IOException("could not save")
                done += style.size
            } finally {
                connection.disconnect()
                partial.delete()
            }
        }
        _changes.value++
    }

    fun deleteDownloads(font: CatalogFont) {
        font.files.filter { it.asset == null }.forEach { file(font, it).delete() }
        _changes.value++
    }

    /** Copies a picked font file in; returns its id. Throws when it is not a usable font. */
    suspend fun importCustom(uri: Uri, name: String): String = withContext(Dispatchers.IO) {
        val safe = name.substringAfterLast('/').filter { it.isLetterOrDigit() || it in "-_. " }.ifBlank { "font.ttf" }
        val target = File(customDir, safe)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("could not open")
        val face = runCatching { Typeface.createFromFile(target) }.getOrNull()
        if (face == null || face == Typeface.DEFAULT) {
            target.delete()
            throw IOException("not a font")
        }
        _changes.value++
        CUSTOM + target.name
    }

    fun deleteCustom(id: String) {
        File(customDir, id.removePrefix(CUSTOM)).delete()
        _changes.value++
    }

    /** Loads [id], falling back to the bundled default for anything missing. */
    suspend fun typefaces(id: String): TerminalTypefaces = withContext(Dispatchers.IO) {
        val symbols = asset(FontCatalog.SYMBOLS_ASSET)
        val default = FontCatalog.fonts.first()
        val regular: Typeface
        var faces = mutableMapOf<FontStyle, Typeface>()
        when {
            id == SYSTEM -> regular = Typeface.MONOSPACE
            id.startsWith(CUSTOM) -> regular = runCatching { Typeface.createFromFile(File(customDir, id.removePrefix(CUSTOM))) }
                .getOrElse { asset(default.files.first().asset!!) }
            else -> {
                val font = FontCatalog.fonts.firstOrNull { it.id == id && available(it) } ?: default
                faces = font.files.mapNotNull { style ->
                    val face = when {
                        style.asset != null -> asset(style.asset)
                        file(font, style).exists() -> runCatching { Typeface.createFromFile(file(font, style)) }.getOrNull()
                        else -> null
                    }
                    face?.let { style.style to it }
                }.toMap().toMutableMap()
                regular = faces[FontStyle.Regular] ?: asset(default.files.first().asset!!)
            }
        }
        TerminalTypefaces(
            regular = regular,
            bold = faces[FontStyle.Bold] ?: Typeface.create(regular, Typeface.BOLD),
            italic = faces[FontStyle.Italic] ?: Typeface.create(regular, Typeface.ITALIC),
            boldItalic = faces[FontStyle.BoldItalic] ?: Typeface.create(faces[FontStyle.Bold] ?: regular, Typeface.BOLD_ITALIC),
            symbols = symbols,
        )
    }

    private fun asset(path: String): Typeface = Typeface.createFromAsset(context.assets, path)

    companion object {
        const val SYSTEM = "system"
        const val CUSTOM = "custom:"
    }
}
