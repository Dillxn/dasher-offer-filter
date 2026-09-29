package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;

/** Prevents repeated taps on one offer without delaying a different offer. */
final class DeclineState {
    static final int MAX_ATTEMPTS = 4;
    static final long RETRY_INTERVAL_MS = 250;
    static final long CONFIRMATION_WINDOW_MS = 10_000;
    /** How long after our confirmation tap a missing dialog is taken to mean it closed, not a passing glitch. */
    static final long CONFIRMATION_SETTLE_MS = 1_000;

    private String tappedOffer = "";
    private long lastTapAt;
    private int attempts;
    private long confirmationUntil;
    private long lastConfirmationAt = -1;
    private int confirmationAttempts;

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
        return !offerKey.equals(tappedOffer) || (attempts < MAX_ATTEMPTS && now - lastTapAt >= RETRY_INTERVAL_MS);
    }

    void declineSent(String offerKey, long now) {
        if (!offerKey.equals(tappedOffer)) attempts = 0;
        tappedOffer = offerKey;
        attempts++;
        lastTapAt = now;
        confirmationUntil = now + CONFIRMATION_WINDOW_MS;
        lastConfirmationAt = -1;
        confirmationAttempts = 0;
    }

    boolean hasPendingConfirmation(long now) {
        return now < confirmationUntil;
    }

    boolean mayConfirm(long now) {
        return hasPendingConfirmation(now) && confirmationAttempts < MAX_ATTEMPTS
                && (lastConfirmationAt < 0 || now - lastConfirmationAt >= RETRY_INTERVAL_MS);
    }

    void confirmationSent(long now) {
        lastConfirmationAt = now;
        confirmationAttempts++;
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
        lastConfirmationAt = -1;
        confirmationAttempts = 0;
    }

    /** The offer left the screen; an identical next offer is a new offer. Confirmation authority is kept. */
    void offerGone() {
        tappedOffer = "";
        attempts = 0;
    }

    /** Revokes all decline and confirmation authority. */
    void reset() {
        tappedOffer = "";
        confirmationUntil = 0;
        lastConfirmationAt = -1;
        confirmationAttempts = 0;
    }
}
