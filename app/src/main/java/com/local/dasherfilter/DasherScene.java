package com.local.dasherfilter;

import java.util.Arrays;
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
    /** Labels a delivery screen begins with ("Deliver by 7:45 PM", "Delivery for Sam"). */
    private static final List<String> ROUTE_PREFIXES = Arrays.asList("deliver by ", "delivery for ");
    private static final List<String> ROUTE_LABELS = Arrays.asList("complete delivery steps");

    /**
     * A read screen with no sign of an offer on it: a delivery when a route is stored or the screen shows one (that
     * comes first, whatever else the screen shows), waiting when it shows the wait for offers, else unknown.
     */
    static DasherScene of(List<String> labels, boolean routeStored) {
        if (labels == null) return UNKNOWN;
        if (routeStored || AcceptedOfferTracker.isDeliveryScreen(labels) || shows(labels, ROUTE_LABELS, ROUTE_PREFIXES)) {
            return ROUTE;
        }
        if (OfferEvidence.isIdle(labels) || shows(labels, WAITING_LABELS, null)) return WAITING;
        return UNKNOWN;
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
