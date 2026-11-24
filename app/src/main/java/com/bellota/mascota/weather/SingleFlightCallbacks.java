package com.bellota.mascota.weather;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Coalesces concurrent callers behind one in-flight operation. */
final class SingleFlightCallbacks<T> {
    private final List<T> callbacks = new ArrayList<>();
    private boolean inFlight;

    /** Returns true only to the caller responsible for starting the operation. */
    synchronized boolean register(T callback) {
        if (callback == null) return false;
        callbacks.add(callback);
        if (inFlight) return false;
        inFlight = true;
        return true;
    }

    /** Completes the operation and returns every waiting callback exactly once. */
    synchronized List<T> complete() {
        if (!inFlight) return Collections.emptyList();
        List<T> completed = new ArrayList<>(callbacks);
        callbacks.clear();
        inFlight = false;
        return completed;
    }

    synchronized boolean isInFlight() {
        return inFlight;
    }
}
