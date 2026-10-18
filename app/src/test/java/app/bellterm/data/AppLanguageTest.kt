package app.bellterm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun noTagFollowsTheSystem() {
        assertEquals(AppLanguage.System, AppLanguage.fromTag(null))
    }

    @Test
    fun shippedLanguagesAreMatchedByLanguageNotRegion() {
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en-GB"))
        assertEquals(AppLanguage.Chinese, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.Chinese, AppLanguage.fromTag("zh-Hans-TW"))
    }

    @Test
    fun languagesBelltermDoesNotShipFollowTheSystem() {
        assertEquals(AppLanguage.System, AppLanguage.fromTag("fr-FR"))
        assertEquals(AppLanguage.System, AppLanguage.fromTag(""))
    }

    @Test
    fun everyPinnedLanguageRoundTripsThroughItsTag() {
        AppLanguage.entries.filter { it.tag != null }.forEach { assertEquals(it, AppLanguage.fromTag(it.tag)) }
    }
}
