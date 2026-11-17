package app.bellterm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    private val files = mapOf(
        "1.1.1.en.txt" to "Saved machines.\n",
        "1.1.1.zh.txt" to "保存的机器。\n",
        "1.9.0.en.txt" to "Nine.\n",
        "1.10.0.en.txt" to "First line.\n\n  Second line.  \n",
        "1.0.1.en.txt" to "Glass.\n",
        "2.0.0.en.txt" to "\n",
        "README.md" to "not notes",
    )

    @Test
    fun newestFirstInTheAskedLanguageElseEnglish() {
        assertEquals(
            listOf(
                Release("1.10.0", listOf("First line.", "Second line.")),
                Release("1.9.0", listOf("Nine.")),
                Release("1.1.1", listOf("保存的机器。")),
                Release("1.0.1", listOf("Glass.")),
            ),
            ReleaseNotes.parse(files, "zh"),
        )
        assertEquals(listOf("Saved machines."), ReleaseNotes.parse(files, "en").first { it.version == "1.1.1" }.notes)
        assertEquals(listOf("Saved machines."), ReleaseNotes.parse(files, "fr").first { it.version == "1.1.1" }.notes)
    }
}
