package com.local.dasherfilter;

import java.util.List;
import java.util.Locale;

/** A passing offer alone never advances the baseline. */
final class AcceptedOfferTracker {
    static final class Acceptance {
        final OfferSnapshot acceptedOffer;
        final OfferSnapshot routeAfter;
        final boolean addOn;

        Acceptance(OfferSnapshot acceptedOffer, OfferSnapshot routeAfter, boolean addOn) {
            this.acceptedOffer = acceptedOffer;
            this.routeAfter = routeAfter;
            this.addOn = addOn;
        }

        Integer baselinePay() {
            return routeAfter != null && routeAfter.payCents != null
                    ? routeAfter.payCents : acceptedOffer.payCents;
        }
    }

    private OfferSnapshot visibleOffer;
    private OfferSnapshot visibleRouteAfter;
    private boolean visibleAddOn;
    private String visibleKey = "";
    private long visibleAt;
    private OfferSnapshot pendingOffer;
    private OfferSnapshot pendingRouteAfter;
    private boolean pendingAddOn;
    private long clickedAt;

    void observeOffer(OfferSnapshot offer, long now) {
        observeOffer(offer, offer, false, now);
    }

    void observeOffer(OfferSnapshot offer, OfferSnapshot routeAfter, boolean addOn, long now) {
        String key = offer.fingerprint() + "->" +
                (routeAfter == null ? "null" : routeAfter.fingerprint()) + ":" + addOn;
        if (!key.equals(visibleKey)) clearPending();
        visibleKey = key;
        visibleOffer = offer;
        visibleRouteAfter = routeAfter;
        visibleAddOn = addOn;
        visibleAt = now;
    }

    void acceptClicked(long now) {
        if (visibleOffer != null && visibleOffer.payCents != null && visibleOffer.payCents > 0 &&
                now - visibleAt <= 90000) {
            pendingOffer = visibleOffer;
            pendingRouteAfter = visibleRouteAfter;
            pendingAddOn = visibleAddOn;
            clickedAt = now;
        }
    }

    Acceptance observeOtherScreen(List<String> labels, long now) {
        if (pendingOffer == null || now - clickedAt > 15000) {
            clearPending();
            return null;
        }
        for (String label : labels) {
            String lower = label.trim().toLowerCase(Locale.US);
            if (lower.equals("arrived at store") || lower.equals("arrived at pickup") ||
                    lower.equals("arrived at customer") || lower.equals("arrived at drop-off") ||
                    lower.equals("confirm pickup") || lower.equals("confirm pick up") ||
                    lower.equals("complete pickup") || lower.equals("complete delivery") ||
                    lower.equals("slide to confirm pickup")) {
                Acceptance accepted = new Acceptance(pendingOffer, pendingRouteAfter, pendingAddOn);
                reset();
                return accepted;
            }
        }
        return null;
    }

    void reset() {
        visibleOffer = null;
        visibleRouteAfter = null;
        visibleAddOn = false;
        visibleKey = "";
        clearPending();
    }

    private void clearPending() {
        pendingOffer = null;
        pendingRouteAfter = null;
        pendingAddOn = false;
        clickedAt = 0;
    }
}
