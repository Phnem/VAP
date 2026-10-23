package com.phnem.vap.ui

import com.phnem.vap.ui.onboarding.LanguageChoice
import com.phnem.vap.ui.onboarding.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Выбор темы и языка с первого запуска: разбор сохранённого значения и что отмечено по умолчанию. */
class AppChoicesTest {
    @Test
    fun themeFollowsSystemOnlyInSystemMode() {
        assertTrue(ThemeChoice.SYSTEM.isDark(true))
        assertFalse(ThemeChoice.SYSTEM.isDark(false))
        assertTrue(ThemeChoice.DARK.isDark(false))
        assertFalse(ThemeChoice.LIGHT.isDark(true))
    }

    @Test
    fun unknownOrMissingStoredValuesFallBackToSystem() {
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.parse(null))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.parse("nonsense"))
        assertEquals(ThemeChoice.DARK, ThemeChoice.parse("DARK"))
        assertEquals(LanguageChoice.SYSTEM, LanguageChoice.parse(null))
        assertEquals(LanguageChoice.RU, LanguageChoice.parse("RU"))
    }

    @Test
    fun suggestedLanguageIsRussianOnlyForRussianSystems() {
        assertEquals(LanguageChoice.RU, LanguageChoice.suggested("ru"))
        assertEquals(LanguageChoice.RU, LanguageChoice.suggested("RU"))
        assertEquals(LanguageChoice.EN, LanguageChoice.suggested("en"))
        assertEquals(LanguageChoice.EN, LanguageChoice.suggested("de"))
    }

    @Test
    fun languageTagsAreStable() {
        assertNull(LanguageChoice.SYSTEM.tag)
        assertEquals("ru", LanguageChoice.RU.tag)
        assertEquals("en", LanguageChoice.EN.tag)
    }
}
