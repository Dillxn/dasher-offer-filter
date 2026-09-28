package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;

/** A passing offer alone never advances the baseline. */
final class AcceptedOfferTracker {
    private Integer visiblePay;
    private String visibleKey = "";
    private long visibleAt;
    private Integer pendingPay;
    private long clickedAt;

    void observeOffer(OfferSnapshot offer, long now) {
        if (!offer.fingerprint().equals(visibleKey)) pendingPay = null;
        visibleKey = offer.fingerprint();
        visiblePay = offer.payCents;
        visibleAt = now;
    }

    void acceptClicked(long now) {
        if (visiblePay != null && visiblePay > 0 && now - visibleAt <= 90000) {
            pendingPay = visiblePay;
            clickedAt = now;
        }
    }

    Integer observeOtherScreen(List<String> labels, long now) {
        if (pendingPay == null || now - clickedAt > 15000) {
            reset();
            return null;
        }
        for (String label : labels) {
            String lower = label.trim().toLowerCase(Locale.US);
            if (lower.equals("arrived at store") || lower.equals("arrived at pickup") ||
                    lower.equals("arrived at customer") || lower.equals("arrived at drop-off") ||
                    lower.equals("confirm pickup") || lower.equals("confirm pick up") ||
                    lower.equals("complete pickup") || lower.equals("complete delivery") ||
                    lower.equals("slide to confirm pickup")) {
                Integer accepted = pendingPay;
                reset();
                return accepted;
            }
        }
        return null;
    }

    void reset() {
        visiblePay = null;
        visibleKey = "";
        pendingPay = null;
    }
}
