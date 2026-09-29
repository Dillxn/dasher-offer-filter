package com.local.dasherfilter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Records an acceptance only after the user taps Accept on a readable offer and Dasher then shows delivery
 * progress. A passing offer alone never advances the baseline.
 */
final class AcceptedOfferTracker {
    private static final long MAX_OFFER_AGE_AT_CLICK_MS = 90_000;
    private static final long MAX_CLICK_TO_PROGRESS_MS = 15_000;
    private static final List<String> PROGRESS_LABELS = Arrays.asList(
            "arrived at store", "arrived at pickup", "arrived at customer", "arrived at drop-off",
            "confirm pickup", "confirm pick up", "complete pickup", "complete delivery", "slide to confirm pickup");

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
            return routeAfter != null && routeAfter.payCents != null ? routeAfter.payCents : acceptedOffer.payCents;
        }
    }

    /** A delivery-progress screen: an offer, and any decline confirmation for it, is over. */
    static boolean isDeliveryScreen(List<String> labels) {
        for (String label : labels) {
            if (PROGRESS_LABELS.contains(label.trim().toLowerCase(Locale.US))) return true;
        }
        return false;
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
        String route = routeAfter == null ? "null" : routeAfter.fingerprint();
        String key = offer.fingerprint() + "->" + route + ":" + addOn;
        if (!key.equals(visibleKey)) clearPending();
        visibleKey = key;
        visibleOffer = offer;
        visibleRouteAfter = routeAfter;
        visibleAddOn = addOn;
        visibleAt = now;
    }

    void acceptClicked(long now) {
        boolean readablePay = visibleOffer != null && visibleOffer.payCents != null && visibleOffer.payCents > 0;
        if (readablePay && now - visibleAt <= MAX_OFFER_AGE_AT_CLICK_MS) {
            pendingOffer = visibleOffer;
            pendingRouteAfter = visibleRouteAfter;
            pendingAddOn = visibleAddOn;
            clickedAt = now;
        }
    }

    /** Returns the acceptance once delivery progress follows a recent Accept tap; otherwise null. */
    Acceptance observeOtherScreen(List<String> labels, long now) {
        if (pendingOffer == null || now - clickedAt > MAX_CLICK_TO_PROGRESS_MS) {
            clearPending();
            return null;
        }
        for (String label : labels) {
            if (PROGRESS_LABELS.contains(label.trim().toLowerCase(Locale.US))) {
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
