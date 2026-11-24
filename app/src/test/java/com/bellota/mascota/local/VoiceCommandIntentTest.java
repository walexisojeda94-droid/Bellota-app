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

    @Test public void systemQueriesRequireAnActualRequest() {
        assertTrue(VoiceCommandIntent.isBatteryQuery(Utterance.of("Cuánta batería tiene el teléfono")));
        assertTrue(VoiceCommandIntent.isTimeOrDateQuery(Utterance.of("Qué hora es")));
        assertTrue(VoiceCommandIntent.isVolumeQuery(Utterance.of("Volumen actual")));
        assertFalse(VoiceCommandIntent.isBatteryQuery(Utterance.of("No me gusta la batería")));
        assertFalse(VoiceCommandIntent.isTimeOrDateQuery(Utterance.of("No sé qué hora era")));
        assertFalse(VoiceCommandIntent.isVolumeQuery(Utterance.of("El volumen actual del juego")));
    }

    @Test public void volumeChangesRequireAnExplicitCommand() {
        assertTrue(VoiceCommandIntent.isVolumeChangeRequest(Utterance.of("Subí el volumen")));
        assertTrue(VoiceCommandIntent.isVolumeChangeRequest(Utterance.of("Bajá el volumen al 30 por ciento")));
        assertTrue(VoiceCommandIntent.isVolumeChangeRequest(Utterance.of("Poné el volumen al 75")));
        assertEquals(75, VoiceCommandIntent.requestedVolumePercent(Utterance.of("Poné el volumen al 75 por ciento")));
        assertEquals(30, VoiceCommandIntent.requestedVolumePercent(Utterance.of("Bajá el volumen al 30")));
        assertEquals(10, VoiceCommandIntent.volumeChangeDelta(Utterance.of("Subí el volumen")));
        assertEquals(-10, VoiceCommandIntent.volumeChangeDelta(Utterance.of("Bajá el volumen")));
        assertFalse(VoiceCommandIntent.isVolumeChangeRequest(Utterance.of("El volumen de la música")));
    }

    @Test public void incidentalSoundMentionDoesNotUnmute() {
        assertFalse(VoiceCommandIntent.isUnmuteRequest(Utterance.of("No hay sonido")));
        assertFalse(VoiceCommandIntent.isUnmuteRequest(Utterance.of("Me gusta ese sonido")));
        assertFalse(VoiceCommandIntent.isUnmuteRequest(Utterance.of("Activá el sonido de la tele")));
        assertTrue(VoiceCommandIntent.isUnmuteRequest(Utterance.of("Activá el sonido por favor")));
    }
}
