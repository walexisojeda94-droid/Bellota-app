package com.bellota.mascota.local;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceCommandIntentTest {
    @Test public void recognizesExplicitMuteRequests() {
        assertTrue(VoiceCommandIntent.isMuteRequest(Utterance.of("Silencio")));
        assertTrue(VoiceCommandIntent.isMuteRequest(Utterance.of("Quiero silencio")));
        assertTrue(VoiceCommandIntent.isMuteRequest(Utterance.of("No hables")));
        assertTrue(VoiceCommandIntent.isMuteRequest(Utterance.of("Desactivá el sonido")));
    }

    @Test public void incidentalOrNegatedSilenceDoesNotMute() {
        assertFalse(VoiceCommandIntent.isMuteRequest(Utterance.of("No quiero silencio")));
        assertFalse(VoiceCommandIntent.isMuteRequest(Utterance.of("No me dejes en silencio")));
        assertFalse(VoiceCommandIntent.isMuteRequest(Utterance.of("Qué silencio hay acá")));
        assertFalse(VoiceCommandIntent.isMuteRequest(Utterance.of("El sonido del juego")));
        assertFalse(VoiceCommandIntent.isMuteRequest(Utterance.of("No hables de ese tema")));
        assertTrue(VoiceCommandIntent.isMuteRequest(Utterance.of("Desactivá el sonido por favor")));
    }

    @Test public void recognizesExplicitUnmuteRequests() {
        assertTrue(VoiceCommandIntent.isUnmuteRequest(Utterance.of("Sonido")));
        assertTrue(VoiceCommandIntent.isUnmuteRequest(Utterance.of("Activá el sonido")));
        assertTrue(VoiceCommandIntent.isUnmuteRequest(Utterance.of("Volvé a hablar")));
    }

    @Test public void incidentalSoundMentionDoesNotUnmute() {
        assertFalse(VoiceCommandIntent.isUnmuteRequest(Utterance.of("No hay sonido")));
        assertFalse(VoiceCommandIntent.isUnmuteRequest(Utterance.of("Me gusta ese sonido")));
        assertFalse(VoiceCommandIntent.isUnmuteRequest(Utterance.of("Activá el sonido de la tele")));
        assertTrue(VoiceCommandIntent.isUnmuteRequest(Utterance.of("Activá el sonido por favor")));
    }
}
