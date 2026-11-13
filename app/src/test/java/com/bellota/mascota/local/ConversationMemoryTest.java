package com.bellota.mascota.local;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ConversationMemoryTest {
    @Test public void storesAndRemovesLikesWithoutDuplicates() {
        ConversationMemory memory = ConversationMemory.inMemory();
        memory.addLike("Pokémon");
        memory.addLike("pokémon");
        assertEquals(1, memory.likes().size());
        memory.removeLike("POKÉMON");
        assertTrue(memory.likes().isEmpty());
    }

    @Test public void limitsStoredLikesToTwenty() {
        ConversationMemory memory = ConversationMemory.inMemory();
        for (int i = 0; i < 25; i++) memory.addLike("gusto" + i);
        List<String> likes = memory.likes();
        assertEquals(20, likes.size());
        assertEquals("gusto5", likes.get(0));
        assertEquals("gusto24", likes.get(19));
    }

    @Test public void limitsNicknamesToFive() {
        ConversationMemory memory = ConversationMemory.inMemory();
        for (int i = 0; i < 7; i++) memory.addNickname("apodo" + i);
        assertEquals(5, memory.nicknames().size());
        assertEquals("apodo2", memory.nicknames().get(0));
    }

    @Test public void welcomeBackTriggersOnlyAfterLongSilence() {
        ConversationMemory memory = ConversationMemory.inMemory();
        memory.onUserMessage(1_000L);
        assertFalse(memory.takeWelcomeBack());
        memory.onUserMessage(1_000L + ConversationMemory.LONG_SILENCE_MS);
        assertTrue(memory.takeWelcomeBack());
        assertFalse(memory.takeWelcomeBack());
    }

    @Test public void expectedAnswerExpiresAfterThreeMinutes() {
        ConversationMemory memory = ConversationMemory.inMemory();
        memory.expect("like:Pokémon", 10_000L);
        assertEquals("like:Pokémon", memory.expecting(10_001L));
        assertNull(memory.expecting(10_000L + 3L * 60L * 1000L + 1L));
    }
}
