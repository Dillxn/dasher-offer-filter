package com.local.dasherfilter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What Dasher's screen shows, for the filter tab and the best-area guide over it, and for what came after an offer
 * ({@link AcceptedOfferTracker#classify}): nothing about an offer's own decision comes from it. Only a screen that
 * positively shows the wait for offers is {@link #WAITING}; a screen not recognised is {@link #UNKNOWN}, never taken
 * for waiting. Beyond the delivery, pickup and directions markers it always knew, its wording is Dasher's own as the
 * user's 0.4.41 report captured it (the drop-off steps, the dash's summary between deliveries, the zone screens),
 * never a guess.
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

    /**
     * The dash's own screen and the dash home, as Dasher words them while nothing is under way; the dash's summary
     * between deliveries ("This dash so far", "Continue dashing"); and the zone screens ("Dash here", "Navigate back
     * to old zone", "Switch to this zone with peak pay!").
     */
    private static final List<String> WAITING_LABELS = Arrays.asList(
            "zone offer wait", "avg. offer wait", "you're in a good place to wait for offers",
            "earnings mode switcher", "this dash so far", "continue dashing", "dash here", "navigate back to old zone",
            "switch to this zone with peak pay");
    /** The dash's summary between deliveries: the figures on it are the dash's, never an offer's. */
    private static final List<String> DASH_SUMMARY_LABELS = Collections.singletonList("this dash so far");
    /**
     * Labels a delivery screen begins with ("Deliver by 7:45 PM", "Delivery for Sam", "Deliver to Sam", "Arriving at
     * 2:39 AM"), and the pickup leg's alike ("Pick up by 7:30 PM").
     */
    private static final List<String> ROUTE_PREFIXES = Arrays.asList("deliver by ", "delivery for ",
            "pick up by ", "pickup by ", "deliver to ", "arriving at");
    /** Whole labels of a delivery or pickup screen: its steps, as Dasher words them on the drop-off leg too. */
    private static final List<String> ROUTE_LABELS = Arrays.asList("complete delivery steps", "complete pickup steps",
            "directions", "leave it at the door", "hand it to me", "verify correct order",
            "check you have the correct order from pick-up", "take photo of drop-off location",
            "handed order to customer", "confirm you handed order directly to customer",
            "confirm to complete delivery", "arriving soon", "remember to grab the drinks before delivering the order");
    /** Dasher's headline for a new offer ("New Delivery!", "New Order: Go to …"): an offer, never a delivery. */
    private static final List<String> NEW_OFFER_PREFIXES = Arrays.asList("new delivery", "new order");
    /** Dasher's question before it ends a dash (its buttons: "End dash", "Go back"). */
    private static final List<String> END_DASH_QUESTION = Collections.singletonList("end your current dash");
    /** Turn-by-turn navigation's speed: a label "mph", or "25 mph". */
    private static final Pattern SPEED = Pattern.compile("(?:\\d{1,3} ?)?mph");
    /** Turn-by-turn navigation's distance to the next turn, within a label: "300 ft", "0.3 mi". */
    private static final Pattern DISTANCE = Pattern.compile("(?<![\\d.,])\\b\\d{1,4}(?:[.,]\\d{1,2})? ?(?:ft|mi)\\b");

    /**
     * A read screen with no sign of an offer on it: a delivery when a route is stored or the screen shows one (that
     * comes first, whatever else the screen shows), waiting when it shows the wait for offers, else unknown. Dasher's
     * question before it ends a dash is not the wait for offers, whatever it is drawn over; navigation alone (its
     * speed, distances and turns) is neither.
     */
    static DasherScene of(List<String> labels, boolean routeStored) {
        if (labels == null) return UNKNOWN;
        if (showsNewOffer(labels)) return OFFER;
        if (routeStored || showsRoute(labels)) return ROUTE;
        if (showsEndDashQuestion(labels)) return UNKNOWN;
        if (showsWaiting(labels)) return WAITING;
        return UNKNOWN;
    }

    /** Whether a screen shows a delivery or pickup under way: its progress steps, or its route markers. */
    static boolean showsRoute(List<String> labels) {
        return labels != null && (AcceptedOfferTracker.isDeliveryScreen(labels)
                || shows(labels, ROUTE_LABELS, ROUTE_PREFIXES));
    }

    /**
     * Whether a screen shows the wait for offers: finding offers, the dash's own screen or the dash home, the dash's
     * summary between deliveries, or the zone screens.
     */
    static boolean showsWaiting(List<String> labels) {
        return labels != null && (OfferEvidence.isIdle(labels) || shows(labels, WAITING_LABELS, null));
    }

    /** Whether a screen is the dash's summary between deliveries ("This dash so far"), whose figures are the dash's. */
    static boolean showsDashSummary(List<String> labels) {
        return labels != null && shows(labels, DASH_SUMMARY_LABELS, null);
    }

    /**
     * Whether a screen carries Dasher's headline for a new offer ("New Delivery!", "New Order: Go to …"). It is a new
     * offer (the user: "it's a new order, not necessarily one I accepted"), never a sign that one was accepted.
     */
    static boolean showsNewOffer(List<String> labels) {
        return labels != null && shows(labels, Collections.<String>emptyList(), NEW_OFFER_PREFIXES);
    }

    /**
     * Whether a screen asks "End your current dash?". The question alone ends nothing ("Go back" keeps the dash on):
     * only the user's "End dash" on it, and then the dash's screen going away, ends the dash
     * ({@link AcceptedOfferTracker}).
     */
    static boolean showsEndDashQuestion(List<String> labels) {
        return labels != null && shows(labels, END_DASH_QUESTION, null);
    }

    /**
     * Whether a screen is turn-by-turn navigation: a label with its speed ("mph") and one with a distance ("300 ft",
     * "0.3 mi"). Navigation shows on the way to a pickup or a customer and on the way back to a zone alike, so on its
     * own it is neither a delivery nor the wait for offers, and its distances and times are not an offer's.
     */
    static boolean showsNavigation(List<String> labels) {
        if (labels == null) return false;
        boolean speed = false;
        boolean distance = false;
        for (String raw : labels) {
            if (raw == null) continue;
            String label = OfferEvidence.normalize(raw).toLowerCase(Locale.US);
            if (!speed && SPEED.matcher(label).matches()) speed = true;
            if (!distance && DISTANCE.matcher(label).find()) distance = true;
            if (speed && distance) return true;
        }
        return false;
    }

    private static boolean shows(List<String> labels, List<String> exact, List<String> prefixes) {
        for (String raw : labels) {
            String label = OfferEvidence.normalize(raw).replace('’', '\'').toLowerCase(Locale.US)
                    .replaceAll("[.!?…]+$", "");
            if (exact.contains(label)) return true;
            if (prefixes == null) continue;
            for (String prefix : prefixes) {
                if (label.startsWith(prefix)) return true;
            }
        }
        return false;
    }
}
