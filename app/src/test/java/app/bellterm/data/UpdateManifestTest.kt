package app.bellterm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val sha = "a".repeat(64)

    private fun manifest(
        versionCode: Any = 2,
        versionName: String = "1.0.1",
        url: String = "https://dl.bellterm.app/bellterm-1.0.1.apk",
        sha256: String = sha,
        extra: String = "",
    ) = """{"versionCode":$versionCode,"versionName":"$versionName","url":"$url","sha256":"$sha256"$extra}"""

    @Test
    fun parsesAFullManifest() {
        val info = UpdateManifest.parse(
            manifest(extra = ""","size":20376499,"minSdk":29,"notes":{"en":" Fixes ","zh":"修复"}"""),
        )
        assertEquals(2L, info.versionCode)
        assertEquals("1.0.1", info.versionName)
        assertEquals(20376499L, info.size)
        assertEquals(29, info.minSdk)
        assertEquals("Fixes", info.notesFor("en"))
        assertEquals("修复", info.notesFor("zh"))
    }

    @Test
    fun notesFallBackToEnglishThenAnything() {
        assertEquals("Fixes", UpdateManifest.parse(manifest(extra = ""","notes":{"en":"Fixes"}""")).notesFor("zh"))
        assertEquals("修复", UpdateManifest.parse(manifest(extra = ""","notes":{"zh":"修复"}""")).notesFor("fr"))
        assertEquals("", UpdateManifest.parse(manifest()).notesFor("en"))
    }

    @Test
    fun longNotesAreCapped() {
        val info = UpdateManifest.parse(manifest(extra = ""","notes":{"en":"${"x".repeat(5000)}"}"""))
        assertEquals(2000, info.notesFor("en").length)
    }

    @Test
    fun downloadsOnlyComeFromTheServerOverHttps() {
        listOf(
            "http://dl.bellterm.app/bellterm.apk",
            "https://evil.example/bellterm.apk",
            "https://dl.bellterm.app.evil.example/bellterm.apk",
            "https://user@dl.bellterm.app/bellterm.apk",
            "https://dl.bellterm.app:8443/bellterm.apk",
            "javascript:alert(1)",
            "not a url",
        ).forEach { url -> assertThrows(url, UpdateException::class.java) { UpdateManifest.parse(manifest(url = url)) } }
    }

    @Test
    fun malformedManifestsAreRejected() {
        listOf(
            "",
            "[]",
            "{}",
            manifest(versionCode = 0),
            manifest(versionCode = "\"two\""),
            manifest(versionName = ""),
            manifest(versionName = "1.0.1<script>"),
            manifest(sha256 = "abc"),
            manifest(sha256 = "g".repeat(64)),
        ).forEach { text -> assertThrows(text, UpdateException::class.java) { UpdateManifest.parse(text) } }
    }

    @Test
    fun offersOnlyNewerReleasesTheDeviceCanRun() {
        val info = UpdateManifest.parse(manifest(versionCode = 5, extra = ""","minSdk":31"""))
        assertTrue(info.offers(installed = 4, sdk = 36))
        assertFalse(info.offers(installed = 5, sdk = 36))
        assertFalse(info.offers(installed = 6, sdk = 36))
        assertFalse(info.offers(installed = 4, sdk = 30))
    }

    @Test
    fun automaticChecksRunDaily() {
        val day = UpdateManifest.CHECK_INTERVAL
        assertTrue(UpdateManifest.due(lastCheck = 0, now = day * 100))
        assertFalse(UpdateManifest.due(lastCheck = day * 100, now = day * 100 + day - 1))
        assertTrue(UpdateManifest.due(lastCheck = day * 100, now = day * 101))
        // A clock set back since the last check does not postpone the next one.
        assertTrue(UpdateManifest.due(lastCheck = day * 100, now = day * 99))
    }
}
