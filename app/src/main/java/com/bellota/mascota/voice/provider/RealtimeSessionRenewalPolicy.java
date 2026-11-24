package com.bellota.mascota.voice.provider;

/** Calculates when to renew a Realtime session before its server-enforced expiry. */
public final class RealtimeSessionRenewalPolicy {
    public static final long RENEWAL_LEAD_TIME_MS = 120000L;
    private static final long MINIMUM_DELAY_MS = 1000L;

    private RealtimeSessionRenewalPolicy() { }

    /**
     * Returns the delay from now until renewal should begin, given the remaining
     * lifetime of the current server session. An expired session returns zero.
     */
    public static long delayBeforeExpiry(long remainingMs) {
        if (remainingMs <= 0L) return 0L;
        return Math.max(MINIMUM_DELAY_MS, remainingMs - RENEWAL_LEAD_TIME_MS);
    }
}
