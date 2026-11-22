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

    @Test public void preferenceMatchingIgnoresAccentDifferences() {
        ConversationMemory memory = ConversationMemory.inMemory();
        memory.addLike("Pokémon");
        memory.addLike("Pokemon");
        assertEquals(1, memory.likes().size());
        memory.removeLike("Pokemon");
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

    @Test public void negatedLikePhrasesAreNotLearnedAsPreferences() {
        assertTrue(LocalConversationEngine.hasNegatedLikePrefix("No"));
        assertTrue(LocalConversationEngine.hasNegatedLikePrefix("No, "));
        assertFalse(LocalConversationEngine.hasNegatedLikePrefix("No, definitivamente"));
        assertFalse(LocalConversationEngine.hasNegatedLikePrefix(""));
        assertFalse(LocalConversationEngine.hasNegatedLikePrefix("Sí, claro"));
    }

    @Test public void onlineMessagesUpdateTheSamePersistentMemoryAsLocalMessages() {
        ConversationMemory memory = ConversationMemory.inMemory();
        LocalConversationEngine engine = new LocalConversationEngine(memory);

        engine.rememberOnlineMessage("Me llamo Alexis");
        engine.rememberOnlineMessage("Me gusta el mate");
        engine.rememberOnlineMessage("Llamame Ale");

        assertEquals("Alexis", memory.name());
        assertEquals(1, memory.likes().size());
        assertEquals("el mate", memory.likes().get(0));
        assertEquals("Ale", memory.nicknames().get(0));
        assertTrue(engine.memoryContextForOnline().contains("nombre: Alexis"));
        assertTrue(engine.memoryContextForOnline().contains("gustos: el mate"));
    }

    @Test public void onlineNegativePreferenceDoesNotGetStoredAsALike() {
        ConversationMemory memory = ConversationMemory.inMemory();
        LocalConversationEngine engine = new LocalConversationEngine(memory);
        engine.rememberOnlineMessage("No me gusta el ananá");
        assertTrue(memory.likes().isEmpty());
    }

    @Test public void onlineNegativePreferenceRemovesAnExistingLike() {
        ConversationMemory memory = ConversationMemory.inMemory();
        memory.addLike("el ananá");
        LocalConversationEngine engine = new LocalConversationEngine(memory);
        engine.rememberOnlineMessage("No me gusta el ananá");
        assertTrue(memory.likes().isEmpty());
    }

    @Test public void onlineYesNoAnswerResolvesTheSamePendingQuestion() {
        ConversationMemory memory = ConversationMemory.inMemory();
        memory.addLike("el mate");
        memory.expect("like:el mate", System.currentTimeMillis());
        LocalConversationEngine engine = new LocalConversationEngine(memory);
        engine.rememberOnlineMessage("No");
        assertTrue(memory.likes().isEmpty());
        assertNull(memory.expecting(System.currentTimeMillis()));
    }

    @Test public void uncertainOnlineReplyDoesNotResolvePendingPreferenceQuestion() {
        ConversationMemory memory = ConversationMemory.inMemory();
        memory.addLike("el mate");
        memory.expect("like:el mate", System.currentTimeMillis());
        LocalConversationEngine engine = new LocalConversationEngine(memory);
        engine.rememberOnlineMessage("No sé si me gusta");
        assertEquals(1, memory.likes().size());
        assertNotNull(memory.expecting(System.currentTimeMillis()));
    }

    @Test public void clearOnlineNegativeReplyResolvesPendingPreferenceQuestion() {
        ConversationMemory memory = ConversationMemory.inMemory();
        memory.addLike("el mate");
        memory.expect("like:el mate", System.currentTimeMillis());
        LocalConversationEngine engine = new LocalConversationEngine(memory);
        engine.rememberOnlineMessage("No, ya no me gusta");
        assertTrue(memory.likes().isEmpty());
        assertNull(memory.expecting(System.currentTimeMillis()));
    }

    @Test public void onlineMemoryContextIsEmptyWhenThereAreNoKnownFacts() {
        LocalConversationEngine engine = new LocalConversationEngine(ConversationMemory.inMemory());
        assertEquals("", engine.memoryContextForOnline());
    }

    @Test public void onlineContextIncludesPendingQuestionAndLongAbsence() {
        ConversationMemory memory = ConversationMemory.inMemory();
        long now = System.currentTimeMillis();
        memory.onUserMessage(now - ConversationMemory.LONG_SILENCE_MS - 1L);
        memory.expect("like:el mate", now);
        LocalConversationEngine engine = new LocalConversationEngine(memory);
        String context = engine.memoryContextForOnline();
        assertTrue(context.contains("pregunta_pendiente"));
        assertTrue(context.contains("todavía le gusta el mate"));
        assertTrue(context.contains("pausa prolongada"));
    }

    @Test public void longAbsenceCheckDoesNotMutateLastSeen() {
        ConversationMemory memory = ConversationMemory.inMemory();
        memory.onUserMessage(10_000L);
        assertFalse(memory.isReturningAfterLongSilence(10_000L + ConversationMemory.LONG_SILENCE_MS - 1L));
        assertTrue(memory.isReturningAfterLongSilence(10_000L + ConversationMemory.LONG_SILENCE_MS));
        assertEquals(0L, memory.silenceMs());
    }

    @Test public void expectedAnswerExpiresAfterThreeMinutes() {
        ConversationMemory memory = ConversationMemory.inMemory();
        memory.expect("like:Pokémon", 10_000L);
        assertEquals("like:Pokémon", memory.expecting(10_001L));
        assertNull(memory.expecting(10_000L + 3L * 60L * 1000L + 1L));
    }
}
