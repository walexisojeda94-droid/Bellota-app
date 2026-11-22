package com.bellota.mascota.voice.provider;

import org.junit.Test;
import static org.junit.Assert.*;

public class RealtimeSessionBudgetTest {
    @Test public void usageAccumulatesAcrossResponses() {
        RealtimeSessionBudget budget = new RealtimeSessionBudget();
        assertFalse(budget.recordResponseUsage(8000));
        assertFalse(budget.recordResponseUsage(8000));
        assertTrue(budget.recordResponseUsage(8000));
        assertEquals(24000L, budget.getTotalTokens());
    }

    @Test public void invalidUsageDoesNotIncreaseTotal() {
        RealtimeSessionBudget budget = new RealtimeSessionBudget();
        assertFalse(budget.recordResponseUsage(0));
        assertFalse(budget.recordResponseUsage(-10));
        assertEquals(0L, budget.getTotalTokens());
    }

    @Test public void resetStartsANewUserRequestedSessionBudget() {
        RealtimeSessionBudget budget = new RealtimeSessionBudget();
        budget.recordResponseUsage(12000);
        budget.reset();
        assertEquals(0L, budget.getTotalTokens());
        assertFalse(budget.recordResponseUsage(12000));
    }
}
