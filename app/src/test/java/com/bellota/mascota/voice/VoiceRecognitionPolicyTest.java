package com.bellota.mascota.voice;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceRecognitionPolicyTest {
    @Test public void api24And27PreferOfflineButCannotGuaranteeIt() {
        assertEquals(VoiceRecognitionPolicy.Mode.OFFLINE_PREFERRED_PROVIDER,
                VoiceRecognitionPolicy.modeForApi(24));
        assertEquals(VoiceRecognitionPolicy.Mode.OFFLINE_PREFERRED_PROVIDER,
                VoiceRecognitionPolicy.modeForApi(27));
        assertTrue(VoiceRecognitionPolicy.shouldPreferOffline(24));
        assertTrue(VoiceRecognitionPolicy.shouldPreferOffline(27));
        assertFalse(VoiceRecognitionPolicy.usesDedicatedOnDeviceRecognizer(24));
        assertFalse(VoiceRecognitionPolicy.usesDedicatedOnDeviceRecognizer(27));
        assertTrue(VoiceRecognitionPolicy.descriptionForApi(27).contains("puede requerir Internet"));
    }

    @Test public void api31RequiresDedicatedOnDeviceRecognizer() {
        assertEquals(VoiceRecognitionPolicy.Mode.ON_DEVICE_REQUIRED,
                VoiceRecognitionPolicy.modeForApi(31));
        assertTrue(VoiceRecognitionPolicy.usesDedicatedOnDeviceRecognizer(31));
        assertTrue(VoiceRecognitionPolicy.descriptionForApi(31).contains("motor on-device"));
    }

    @Test public void selectsOnlyAdvertisedSpanishLanguageAndPrefersArgentinian() {
        assertEquals("es-AR", VoiceRecognitionPolicy.selectSpanishLanguage(
                java.util.Arrays.asList("en-US", "es-ES", "es-AR", "pt-BR")));
    }

    @Test public void selectsAnAvailableSpanishVariantWithoutUsingPhoneLocale() {
        assertEquals("es-MX", VoiceRecognitionPolicy.selectSpanishLanguage(
                java.util.Arrays.asList("en-US", "es-MX", "pt-BR")));
        assertNull(VoiceRecognitionPolicy.selectSpanishLanguage(
                java.util.Arrays.asList("en-US", "pt-BR")));
        assertNull(VoiceRecognitionPolicy.selectSpanishLanguage(null));
    }

}
