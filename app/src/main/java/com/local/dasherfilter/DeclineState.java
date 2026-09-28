package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;

/** Prevents repeated taps on one offer without delaying a different offer. */
final class DeclineState {
    private String tappedOffer = "";
    private long lastTapAt;
    private int attempts;
    private long confirmationUntil;
    private long lastConfirmationAt = -1;
    private int confirmationAttempts;

    static String offerKey(OfferSnapshot offer, List<String> labels) {
        StringBuilder identity = new StringBuilder(offer.fingerprint());
        for (String label : labels) {
            String normalized = label.trim().toLowerCase(Locale.US);
            if (normalized.startsWith("accept") || normalized.startsWith("decline") ||
                    normalized.contains("seconds") || normalized.contains("remaining") ||
                    normalized.matches("\\d{1,2}(?::\\d{2})?\\s*(?:s|sec)?")) continue;
            identity.append('\n').append(label);
        }
        return identity.toString();
    }

    boolean mayDecline(String offerKey, long now) {
        return !offerKey.equals(tappedOffer) ||
                (attempts < 4 && now - lastTapAt >= 250);
    }

    void declineSent(String offerKey, long now) {
        if (!offerKey.equals(tappedOffer)) attempts = 0;
        tappedOffer = offerKey;
        attempts++;
        lastTapAt = now;
        confirmationUntil = now + 10000;
        lastConfirmationAt = -1;
        confirmationAttempts = 0;
    }

    boolean hasPendingConfirmation(long now) { return now < confirmationUntil; }

    boolean mayConfirm(long now) {
        return hasPendingConfirmation(now) && confirmationAttempts < 4 &&
                (lastConfirmationAt < 0 || now - lastConfirmationAt >= 250);
    }

    void confirmationSent(long now) {
        lastConfirmationAt = now;
        confirmationAttempts++;
    }

    void offerGone() {
        tappedOffer = "";
        attempts = 0;
    }

    void reset() {
        tappedOffer = "";
        confirmationUntil = 0;
        lastConfirmationAt = -1;
        confirmationAttempts = 0;
    }
}
