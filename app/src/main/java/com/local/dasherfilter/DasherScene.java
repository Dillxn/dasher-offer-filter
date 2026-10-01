package com.local.dasherfilter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What Dasher's screen shows, for the filter tab and the best-area guide over it only: nothing about offers is decided
 * from it. Only a screen that positively shows the wait for offers is {@link #WAITING}; a screen not recognised is
 * {@link #UNKNOWN}, never taken for waiting.
 */
enum DasherScene {
    /** Waiting for offers: Dasher's "finding offers" screen, the dash's own screen or the dash home, and no route. */
    WAITING,
    /** A delivery under way: a route is stored, or the screen shows delivery steps. */
    ROUTE,
    /** Any sign of an offer or its confirmation, even partly drawn, or a screen too big to read. */
    OFFER,
    /** Anything else, or nothing read. */
    UNKNOWN;

    /** The dash's own screen and the dash home, as Dasher words them while nothing is under way. */
    private static final List<String> WAITING_LABELS = Arrays.asList(
            "zone offer wait", "avg. offer wait", "you're in a good place to wait for offers",
            "earnings mode switcher");
    /**
     * Labels a delivery screen begins with ("Deliver by 7:45 PM", "Delivery for Sam"), and the pickup leg's alike
     * ("Pick up by 7:30 PM").
     */
    private static final List<String> ROUTE_PREFIXES = Arrays.asList("deliver by ", "delivery for ",
            "pick up by ", "pickup by ");
    private static final List<String> ROUTE_LABELS = Arrays.asList("complete delivery steps", "complete pickup steps",
            "directions");
    /** Dasher's headline for a new offer ("New Delivery!", "New Order: Go to …"): an offer, never a delivery. */
    private static final List<String> NEW_OFFER_PREFIXES = Arrays.asList("new delivery", "new order");

    /**
     * A read screen with no sign of an offer on it: a delivery when a route is stored or the screen shows one (that
     * comes first, whatever else the screen shows), waiting when it shows the wait for offers, else unknown.
     */
    static DasherScene of(List<String> labels, boolean routeStored) {
        if (labels == null) return UNKNOWN;
        if (showsNewOffer(labels)) return OFFER;
        if (routeStored || showsRoute(labels)) return ROUTE;
        if (showsWaiting(labels)) return WAITING;
        return UNKNOWN;
    }

    /** Whether a screen shows a delivery or pickup under way: its progress steps, or its route markers. */
    static boolean showsRoute(List<String> labels) {
        return labels != null && (AcceptedOfferTracker.isDeliveryScreen(labels)
                || shows(labels, ROUTE_LABELS, ROUTE_PREFIXES));
    }

    /** Whether a screen shows the wait for offers: finding offers, the dash's own screen or the dash home. */
    static boolean showsWaiting(List<String> labels) {
        return labels != null && (OfferEvidence.isIdle(labels) || shows(labels, WAITING_LABELS, null));
    }

    /**
     * Whether a screen carries Dasher's headline for a new offer ("New Delivery!", "New Order: Go to …"). It is a new
     * offer (the user: "it's a new order, not necessarily one I accepted"), never a sign that one was accepted.
     */
    static boolean showsNewOffer(List<String> labels) {
        return labels != null && shows(labels, Collections.<String>emptyList(), NEW_OFFER_PREFIXES);
    }

    private static boolean shows(List<String> labels, List<String> exact, List<String> prefixes) {
        for (String raw : labels) {
            String label = OfferEvidence.normalize(raw).replace('’', '\'').toLowerCase(Locale.US)
                    .replaceAll("[.!…]+$", "");
            if (exact.contains(label)) return true;
            if (prefixes == null) continue;
            for (String prefix : prefixes) {
                if (label.startsWith(prefix)) return true;
            }
        }
        return false;
    }
}
