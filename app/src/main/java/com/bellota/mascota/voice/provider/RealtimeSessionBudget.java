package com.bellota.mascota.voice.provider;

import java.util.concurrent.atomic.AtomicLong;

/** Per-session safeguards for Realtime output, usage and duration. */
public final class RealtimeSessionBudget {
    public static final int MAX_OUTPUT_TOKENS_PER_RESPONSE = 1024;
    public static final long MAX_TOTAL_TOKENS = 24000L;
    public static final long MAX_DURATION_SECONDS = 2700L;

    private final AtomicLong totalTokens = new AtomicLong();

    /** Adds usage reported by one completed response and returns true at/above the cap. */
    public boolean recordResponseUsage(long responseTokens) {
        if (responseTokens <= 0L) return totalTokens.get() >= MAX_TOTAL_TOKENS;
        while (true) {
            long current = totalTokens.get();
            long updated = responseTokens > Long.MAX_VALUE - current
                    ? Long.MAX_VALUE : current + responseTokens;
            if (totalTokens.compareAndSet(current, updated)) {
                return updated >= MAX_TOTAL_TOKENS;
            }
        }
    }

    public long getTotalTokens() { return totalTokens.get(); }
    public void reset() { totalTokens.set(0L); }
}
