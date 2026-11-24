package com.bellota.mascota.weather;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SingleFlightCallbacksTest {
    @Test public void concurrentCallersShareOneOperationAndAllReceiveCompletion() {
        SingleFlightCallbacks<String> gate = new SingleFlightCallbacks<>();
        assertTrue(gate.register("first"));
        assertFalse(gate.register("second"));
        assertFalse(gate.register("third"));
        assertTrue(gate.isInFlight());

        List<String> completed = gate.complete();
        assertEquals(3, completed.size());
        assertEquals("first", completed.get(0));
        assertEquals("second", completed.get(1));
        assertEquals("third", completed.get(2));
        assertFalse(gate.isInFlight());
    }

    @Test public void completionAllowsANewIndependentOperation() {
        SingleFlightCallbacks<String> gate = new SingleFlightCallbacks<>();
        assertTrue(gate.register("first"));
        assertEquals(1, gate.complete().size());
        assertTrue(gate.register("next"));
        assertEquals(1, gate.complete().size());
    }

    @Test public void repeatedCompletionDoesNotRedeliverCallbacks() {
        SingleFlightCallbacks<String> gate = new SingleFlightCallbacks<>();
        assertTrue(gate.register("once"));
        assertEquals(1, gate.complete().size());
        assertTrue(gate.complete().isEmpty());
    }
}
