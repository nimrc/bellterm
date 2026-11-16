package app.bellterm.data

import android.content.Context

/** One release's notes, a line each. */
data class Release(val version: String, val notes: List<String>)

/**
 * The changelog from `release-notes/<version>.<language>.txt`, which the
 * build copies into the app's assets: newest release first, in [language]
 * where a release has it, else English.
 */
object ReleaseNotes {
    private const val DIR = "release-notes"

    fun load(context: Context, language: String): List<Release> {
        val names = context.assets.list(DIR).orEmpty()
        return parse(names.associateWith { name -> context.assets.open("$DIR/$name").bufferedReader().use { it.readText() } }, language)
    }

    /** [files] maps file names to their text. */
    fun parse(files: Map<String, String>, language: String): List<Release> =
        files.entries
            .mapNotNull { (name, text) ->
                val stem = name.removeSuffix(".txt").takeIf { it != name } ?: return@mapNotNull null
                val version = stem.substringBeforeLast('.', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Triple(version, stem.substringAfterLast('.'), text)
            }
            .groupBy({ it.first }, { it.second to it.third })
            .map { (version, texts) ->
                val text = texts.firstOrNull { it.first == language }?.second
                    ?: texts.firstOrNull { it.first == "en" }?.second
                    ?: texts.first().second
                Release(version, text.lines().map(String::trim).filter(String::isNotEmpty))
            }
            .filter { it.notes.isNotEmpty() }
            .sortedWith(compareByDescending<Release, List<Int>>(VersionOrder) { it.version.split('.').map { part -> part.toIntOrNull() ?: 0 } })

    /** Compares dotted versions part by part, so 1.10.0 follows 1.9.0. */
    private object VersionOrder : Comparator<List<Int>> {
        override fun compare(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
