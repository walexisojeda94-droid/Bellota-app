package com.bellota.mascota.local;

import org.junit.Test;
import static org.junit.Assert.*;

public class UtteranceTest {
    @Test public void normalizesSpanishAccentsAndPunctuation() {
        Utterance utterance = Utterance.of("¿Cómo estás, Bellota?");
        assertEquals("como estas bellota", utterance.text);
        assertTrue(utterance.hasPhrase("como estas"));
    }

    @Test public void phraseMatchingRespectsWordBoundaries() {
        Utterance utterance = Utterance.of("peholazo");
        assertFalse(utterance.hasPhrase("hola"));
    }

    @Test public void nullAndWhitespaceInputsAreEmpty() {
        assertTrue(Utterance.of(null).isEmpty());
        assertTrue(Utterance.of("   ").isEmpty());
    }

    @Test public void matchingNormalizesTheExpectedPhraseToo() {
        Utterance utterance = Utterance.of("¿Cómo estás, Bellota?");
        assertTrue(utterance.hasPhrase("cómo estás"));
        assertTrue(utterance.hasWord("estás"));
        assertTrue(utterance.startsWith("¿cómo estás"));
        assertFalse(utterance.hasPhrase(null));
        assertFalse(utterance.hasWord("  "));
    }

    @Test public void startsWithRequiresAWholePhrase() {
        Utterance utterance = Utterance.of("buenas tardes bellota");
        assertTrue(utterance.startsWith("buenas tardes"));
        assertFalse(utterance.startsWith("buenas noche"));
    }
}
