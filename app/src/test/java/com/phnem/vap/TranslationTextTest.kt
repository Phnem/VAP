package com.phnem.vap

import com.phnem.vap.ai.translation.TranslationText
import org.junit.Assert.*
import org.junit.Test

class TranslationTextTest {
    @Test fun longestTermAndWordBoundariesPreserveNames() {
        val glossary = TranslationText.glossary("New York=Нью-Йорк\nYork=Йорк\nJohn=Джон")
        val plan = TranslationText.plan("John met Johnson in New York!", glossary)
        assertEquals(listOf("Джон", "Нью-Йорк", "!"), plan.filter { it.literal }.map { it.text })
        assertTrue(plan.any { "Johnson" in it.text && !it.literal })
    }
    @Test fun literalPunctuationAndWhitespaceSurviveTranslation() {
        assertEquals(" привет, ", TranslationText.restoreSpacing(" hello, ", " привет, "))
        assertEquals("Hello, world.", TranslationText.punctuation(" hello ,  world "))
        assertEquals("Really?", TranslationText.punctuation("really?"))
    }
    @Test fun unicodeChunksDoNotSplitSurrogatePairsOrLoseText() {
        val text = "🙂".repeat(600) + " hello".repeat(100)
        val pieces = TranslationText.plan(text, emptyMap())
        assertEquals(text, pieces.joinToString("") { it.text })
        assertTrue(pieces.all { it.text.codePointCount(0, it.text.length) <= 200 })
        assertTrue(pieces.none { it.text.lastOrNull()?.isHighSurrogate() == true })
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateTermsAreRejected() { TranslationText.glossary("Alice=Алиса\nalice=Элис") }
    @Test(expected = IllegalArgumentException::class) fun unstructuredGlossaryIsRejected() { TranslationText.glossary("Alice Алиса") }
}
