package com.bellota.mascota.local;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TextUtilTest {
    @Test public void normalizeRemovesAccentsPunctuationAndExtraWhitespace() {
        assertEquals("como estas bellota", TextUtil.normalize("  ¿CÓMO estás, Bellota?!  "));
    }

    @Test public void normalizationIsIndependentFromDefaultPhoneLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            assertEquals("internet", TextUtil.normalize("INTERNET"));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test public void shortWordsAreNotFuzzilyMatched() {
        assertFalse(TextUtil.similar("hora", "hola"));
        assertTrue(TextUtil.similar("hola", "hola"));
    }

    @Test public void longWordsAllowOnlyConfiguredEditDistance() {
        assertTrue(TextUtil.similar("temperatrua", "temperatura"));
        assertFalse(TextUtil.similar("temperaturaxyz", "temperatura"));
    }

    @Test public void utterancePhraseMatchingRespectsWordBoundaries() {
        Utterance utterance = Utterance.of("Hola, Bellota!");
        assertTrue(utterance.hasPhrase("hola bellota"));
        assertFalse(utterance.hasPhrase("ola bello"));
        assertTrue(utterance.startsWith("hola"));
    }

    @Test public void nullAndBlankUtterancesAreSafe() {
        assertTrue(Utterance.of(null).isEmpty());
        assertTrue(Utterance.of("   ").isEmpty());
        assertEquals(0, Utterance.of(null).size());
    }
}
