package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;

/** Prevents repeated taps on one offer without delaying a different offer. */
final class DeclineState {
    static final int MAX_ATTEMPTS = 4;
    /** A taken first-step request gets time to open the question before another tap is sent. */
    static final long RETRY_INTERVAL_MS = 2_000;
    private long readDurationMs;
    private long lastTryDelay = 2_000;
    private long firstRetryDelay = RETRY_INTERVAL_MS;

    void readDuration(long ms) { readDurationMs = Math.max(0, ms); }

    long retryDelay() {
        return lastTryDelay;
    }

    long confirmationUntil() { return confirmationUntil; }

    long lastConfirmationAt() { return lastConfirmationAt; }

    /** The next first-step retry, or -1 after the request cap or confirmation authority has ended. */
    long nextDeclineAt() {
        return attempts < MAX_ATTEMPTS && confirmationUntil != 0 && !confirmationTapped()
                ? Math.min(confirmationUntil, lastTapAt + firstRetryDelay) : -1;
    }

    int declineAttempts() { return attempts; }
    /**
     * Tries at Dasher's question at most: the first and two retries. Refusals wait 300 ms; a taken request waits
     * 2–3 seconds for Dasher to close the question, based on that read's duration.
     */
    static final int MAX_CONFIRMATION_TRIES = 3;
    static final long CONFIRMATION_WINDOW_MS = 10_000;
    /** How long after our confirmation tap a missing dialog is taken to mean it closed, not a passing glitch. */
    static final long CONFIRMATION_SETTLE_MS = 1_000;

    private String tappedOffer = "";
    private String authorityOffer = "";
    private long lastTapAt;
    private int attempts;
    private long confirmationUntil;
    /** The last try Android took, and how many it took. */
    private long lastConfirmationAt = -1;
    private int confirmationAttempts;
    /** Every try at the question, refused or taken, the last one's time, and whether Android refused it. */
    private int confirmationTries;
    private long lastTryAt = -1;
    private boolean lastTryRefused;

    /** The offer's facts plus its stable labels; countdowns and the action buttons are excluded. */
    static String offerKey(OfferSnapshot offer, List<String> labels) {
        StringBuilder identity = new StringBuilder(offer.fingerprint());
        for (String label : labels) {
            String normalized = label.trim().toLowerCase(Locale.US);
            boolean volatileLabel = normalized.startsWith("accept") || normalized.startsWith("decline")
                    || normalized.contains("seconds") || normalized.contains("remaining")
                    || normalized.matches("\\d{1,2}(?::\\d{2})?\\s*(?:s|sec)?");
            if (!volatileLabel) identity.append('\n').append(label);
        }
        return identity.toString();
    }

    /** A new offer may be declined at once; the same offer is retried at most {@link #MAX_ATTEMPTS} times. */
    boolean mayDecline(String offerKey, long now) {
        return !offerKey.equals(tappedOffer) || (hasPendingConfirmation(now) && !confirmationTapped()
                && attempts < MAX_ATTEMPTS && now - lastTapAt >= firstRetryDelay);
    }

    void declineSent(String offerKey, long now) {
        declineSent(offerKey, now, -1);
    }

    void declineSent(String offerKey, long now, long countdownMs) {
        boolean fresh = !offerKey.equals(authorityOffer) || confirmationUntil == 0;
        if (fresh) attempts = 0;
        tappedOffer = offerKey;
        attempts++;
        lastTapAt = now;
        firstRetryDelay = Math.min(3_000, Math.max(RETRY_INTERVAL_MS, 2 * readDurationMs));
        if (fresh) {
            authorityOffer = offerKey;
            confirmationUntil = now + (countdownMs >= 0
                    ? Math.min(60_000, countdownMs) + 3_000 : CONFIRMATION_WINDOW_MS);
        }
        clearTries();
    }

    boolean hasPendingConfirmation(long now) {
        return now < confirmationUntil;
    }

    /** Whether the confirmation authority ran out by time (not revoked): a question left untapped is given up. */
    boolean confirmationLapsed(long now) {
        return confirmationUntil != 0 && now >= confirmationUntil;
    }

    boolean mayConfirm(long now) {
        return hasPendingConfirmation(now) && confirmationTries < MAX_CONFIRMATION_TRIES
                && (lastTryAt < 0 || now - lastTryAt >= retryDelay());
    }

    /** Android took a try at the question's Decline (a request; Dasher may still not act on it). */
    void confirmationSent(long now) {
        lastConfirmationAt = now;
        confirmationAttempts++;
        tried(now, false);
    }

    /** Android refused a try at the question's Decline, or there was nothing it could click. */
    void confirmationRefused(long now) {
        tried(now, true);
    }

    private void tried(long now, boolean refused) {
        confirmationTries++;
        lastTryAt = now;
        lastTryRefused = refused;
        lastTryDelay = refused ? 300 : Math.min(3_000, Math.max(2_000, 2 * readDurationMs));
    }

    int confirmationTries() {
        return confirmationTries;
    }

    /** When the last try at the question was made, or -1. */
    long lastTryAt() {
        return lastTryAt;
    }

    /**
     * Every try is used and the question is still there: the last was refused, or was taken at least
     * the patient closing interval ago and Dasher did not act on it.
     */
    boolean confirmationExhausted(long now) {
        return confirmationTries >= MAX_CONFIRMATION_TRIES
                && (lastTryRefused && confirmationAttempts == 0 || now - lastTryAt >= retryDelay());
    }

    /** Whether the declined offer's confirmation has been tapped, so the decline is already fully requested. */
    boolean confirmationTapped() {
        return confirmationAttempts > 0;
    }

    /** True once we tapped a confirmation and at least {@link #CONFIRMATION_SETTLE_MS} has passed since. */
    boolean confirmationSettled(long now) {
        return confirmationAttempts > 0 && now - lastConfirmationAt >= CONFIRMATION_SETTLE_MS;
    }

    /**
     * Ends confirmation authority but keeps the per-offer decline attempt count, so a still-visible declined offer
     * is not re-tapped more than {@link #MAX_ATTEMPTS} times.
     */
    void endConfirmation() {
        confirmationUntil = 0;
        clearTries();
    }

    /** The offer left the screen; an identical next offer is a new offer. Confirmation authority is kept. */
    void offerGone() {
        tappedOffer = "";
        attempts = 0;
    }

    /** Revokes all decline and confirmation authority. */
    void reset() {
        tappedOffer = "";
        authorityOffer = "";
        confirmationUntil = 0;
        clearTries();
    }

    private void clearTries() {
        lastConfirmationAt = -1;
        confirmationAttempts = 0;
        confirmationTries = 0;
        lastTryAt = -1;
        lastTryRefused = false;
    }
}
