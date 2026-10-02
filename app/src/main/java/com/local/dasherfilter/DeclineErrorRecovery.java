package com.local.dasherfilter;

/** Ephemeral error-toast evidence. It can authorize only a bounded Back, never a decline or its confirmation. */
final class DeclineErrorRecovery {
    static final long ERROR_FRESH_MS = 4_000;
    static final long BLANK_SETTLE_MS = 450;
    static final long RETURN_MS = 3_000;
    static final int MAX_BACKS = 2;
    private Object request;
    private long requestedAt;
    private long errorAt = -1;
    private long blankAt = -1;
    private long returnUntil = -1;
    private boolean usedRequest;
    private int backs;

    void requested(Object identity, long at, int attempt) {
        if (attempt == 1) backs = 0;
        request = identity;
        requestedAt = at;
        usedRequest = false;
        clearSignal();
    }

    boolean error(Object identity, long eventAt, long now) {
        if (request == null || request != identity || usedRequest || eventAt < requestedAt || eventAt > now
                || now - eventAt > ERROR_FRESH_MS) return false;
        if (errorAt < 0) errorAt = eventAt; // Repeated toasts never extend evidence freshness.
        return true;
    }

    boolean pending() { return errorAt >= 0 || returnUntil >= 0; }
    boolean returning() { return returnUntil >= 0; }
    boolean hasBackAttempt() { return backs > 0; }
    boolean expired(long now) {
        return returnUntil >= 0 ? now >= returnUntil : errorAt >= 0 && now - errorAt > ERROR_FRESH_MS;
    }
    boolean exhausted() { return backs >= MAX_BACKS; }
    boolean blankReady(long now) {
        if (errorAt < 0 || expired(now) || exhausted() || usedRequest) return false;
        if (blankAt < 0) blankAt = now;
        return now - blankAt >= BLANK_SETTLE_MS;
    }
    void notBlank() { blankAt = -1; }
    void backRequested(long now) {
        backs++;
        usedRequest = true;
        errorAt = -1;
        blankAt = -1;
        returnUntil = now + RETURN_MS;
    }
    void clearSignal() { errorAt = -1; blankAt = -1; returnUntil = -1; }
    void end() { request = null; backs = 0; usedRequest = true; clearSignal(); }
}
