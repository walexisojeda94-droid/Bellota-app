package com.bellota.mascota.local;

import org.junit.Test;
import static org.junit.Assert.*;

public class OfflineRepliesTest {
    @Test public void recognizesAccentedWeatherQuestions() {
        OfflineReplies replies = new OfflineReplies();
        assertTrue(replies.isWeatherQuestion("¿Cómo está el clima?"));
        assertTrue(replies.isWeatherQuestion("¿Cuántos grados hacen?"));
        assertFalse(replies.isWeatherQuestion("¿Cómo estás?"));
    }

    @Test public void knownGreetingsReturnLocalText() {
        String answer = new OfflineReplies().reply("¡Hola!");
        assertNotNull(answer);
        assertFalse(answer.trim().isEmpty());
    }

    @Test public void unknownQuestionsAreDelegatedToContextualFallback() {
        String answer = new OfflineReplies().reply("La mecánica cuántica y sus interpretaciones");
        assertNull(answer);
    }

    @Test public void weatherQuestionsAreNotAnsweredWithGenericText() {
        OfflineReplies replies = new OfflineReplies();
        assertNull(replies.reply("¿Qué temperatura hace?"));
    }

    @Test public void angryAndOffendedStatesRemainDistinct() {
        OfflineReplies replies = new OfflineReplies();
        String angry = replies.reply("¿Cómo estás?", null, "ANGRY");
        String offended = replies.reply("¿Cómo estás?", null, "OFFENDED");
        assertTrue(angry.contains("fastidiada") || angry.contains("enojada") || angry.contains("impaciente"));
        assertTrue(offended.contains("dolida") || offended.contains("sentida") || offended.contains("triste"));
    }

    @Test public void nullInputStillProducesAUsableFallback() {
        assertNotNull(new OfflineReplies().reply(null));
    }
}
