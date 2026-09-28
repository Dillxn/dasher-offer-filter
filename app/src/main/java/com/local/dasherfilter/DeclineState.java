package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;

/** Prevents repeated taps on one offer without delaying a different offer. */
final class DeclineState {
    private String tappedOffer = "";
    private long confirmationUntil;

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

    boolean mayDecline(String offerKey) {
        return !offerKey.equals(tappedOffer);
    }

    void declineSent(String offerKey, long now) {
        tappedOffer = offerKey;
        confirmationUntil = now + 3000;
    }

    boolean mayConfirm(long now) {
        return now < confirmationUntil;
    }

    void confirmationSent() {
        confirmationUntil = 0;
    }

    void offerGone() {
        tappedOffer = "";
    }

    void reset() {
        tappedOffer = "";
        confirmationUntil = 0;
    }
}
