package com.bellota.mascota.voice.provider;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RealtimeSessionRenewalPolicyTest {
    @Test public void startsRenewalTwoMinutesBeforeFortyFiveMinuteExpiry() {
        assertEquals(43L * 60L * 1000L,
                RealtimeSessionRenewalPolicy.delayBeforeExpiry(45L * 60L * 1000L));
    }

    @Test public void keepsAtLeastOneSecondForNearlyExpiredSessions() {
        assertEquals(1000L,
                RealtimeSessionRenewalPolicy.delayBeforeExpiry(30L * 1000L));
    }

    @Test public void returnsZeroWhenSessionIsAlreadyExpired() {
        assertEquals(0L, RealtimeSessionRenewalPolicy.delayBeforeExpiry(0L));
        assertEquals(0L, RealtimeSessionRenewalPolicy.delayBeforeExpiry(-1L));
    }
}
