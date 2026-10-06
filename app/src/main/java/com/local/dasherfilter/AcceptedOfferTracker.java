package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What became of an offer: accepted, declined by hand, or neither, and why. It feeds the offer's history line and the
 * acceptance-rate count; nothing is learned from it (0.5.0), nothing here taps anything, and nothing about an offer's
 * own decision changes. Whenever the evidence is ambiguous nothing is counted, and the history line says why.
 *
 * <p><b>Accepted, tap seen.</b> The user's Accept tap on a readable offer, then a delivery screen within 15 s (or,
 * for an offer left alone, Dasher's next settled screen within the minute). Even with the tap, a standalone offer is
 * not counted as accepted when a delivery was already under way as it came (a stored route, or the last
 * delivery-or-waiting screen read was a delivery), or when its countdown, as last read, had {@value #TIME_LEFT_MS} ms
 * or less left by the time a read found it gone (the tap may have failed as it ran out). Dasher may report no finger
 * taps at all (its screens look like Jetpack Compose, which reports clicks made through accessibility only), so this
 * alone may never see one.
 *
 * <p><b>Accepted, no tap seen</b> (the user's decision, approved in chat). A readable standalone offer Offer Filter did
 * not decline (it passed, or it was left for the user's review) counts as accepted when all of these hold:
 * <ul>
 *   <li>Dasher's wait for offers was read after the offer before it, and before it came (positive evidence the user
 *       was waiting, not delivering); unknown, a restart, or another offer or an unclear screen since, is not;</li>
 *   <li>no route was stored as it came;</li>
 *   <li>it closed while its countdown still had more than {@value #TIME_LEFT_MS} ms left (the countdown at the last
 *       read, less the time until a read found it gone), so it did not run out;</li>
 *   <li>Dasher never asked to confirm declining it, no Decline tap on it was seen, and no decline of it was
 *       requested, on screen or through Dasher's notification;</li>
 *   <li>within {@value #AFTER_MS} ms Dasher's next settled screen is a delivery screen ({@link #isDeliveryScreen}, or
 *       the pickup, drop-off or directions screens {@link DasherScene} knows, such as "Deliver to Sam", "Leave it at
 *       the door" or "Confirm to complete delivery"), not the wait for offers (which includes the dash's summary
 *       between deliveries, "This dash so far" / "Continue dashing", and the zone screens).</li>
 * </ul>
 * A next screen that is neither, once it has stayed {@value #SETTLE_MS} ms, counts nothing, and its words go to the
 * screens log so a report shows them. So does a screen showing both a delivery and the wait for offers, turn-by-turn
 * navigation alone (its speed and distances, "Turn left", "Exit": it shows on the way to a customer and on the way
 * back to a zone alike), and Dasher's "End your current dash?" (the dash counts as ended only once the user's "End
 * dash" on it was seen and a screen without the dash followed). A read that shows any of an offer's facts (pay,
 * miles, minutes, stops) is an offer being drawn, never a next screen, except the figures a screen explains itself:
 * navigation's distances and times, and every figure on the dash's summary. Dasher's "New Delivery!" / "New Order:
 * Go to …" is a new offer, never evidence of an acceptance; notifications never reach this class.
 *
 * <p><b>Declined by hand.</b> A seen Decline tap, or Dasher's own "Are you sure you want to decline this offer?" about
 * an offer Offer Filter left alone while no decline of its own is under way. Either is held until Dasher moves on,
 * and counts only on a clear outcome: Dasher goes back to the wait for offers, or another offer comes (with no other
 * screen read between). A seen tap also counts when Dasher goes back to a delivery that was under way before.
 * Anything else counts nothing, and says why: going back to the offer (any read of it after the question, unless the
 * wait for offers or the next offer follows at once), a later Accept tap on it, a delivery screen (for the question
 * alone, or with none under way before), an unclear screen that settles, the minute running out, or the dash ending.
 *
 * <p>Each step is noted for the offer's history line ({@link #takeNotes}). Pure Java; the screen reader drives it on
 * its scanner thread.
 */
final class AcceptedOfferTracker {
    private static final long MAX_OFFER_AGE_AT_CLICK_MS = 90_000;
    static final long MAX_CLICK_TO_PROGRESS_MS = 15_000;
    /** Dasher's next settled screen counts only within this long after the offer left. */
    static final long AFTER_MS = 60_000;
    /** A screen neither a delivery nor the wait for offers has settled once its words stay this long. */
    static final long SETTLE_MS = 5_000;
    /** More than this left on the countdown when the offer closed: it did not run out. */
    static final long TIME_LEFT_MS = 3_000;
    /** Dasher's decline question is about the offer read at most this long before it. */
    static final long QUESTION_AGE_MS = 10_000;
    /**
     * The same offer again this long after Dasher's question (or a seen Decline tap): the user went back to it. A read
     * sooner may be the question's sheet closing, and counts only if the wait for offers or the next offer follows.
     */
    static final long BACK_TO_OFFER_MS = 1_000;
    static final String WAIT_NOT_SEEN = "Dasher's wait for offers wasn't seen before it";
    static final String DELIVERY_UNDER_WAY =
            "a delivery was already under way when it came, so the delivery screen after it proves nothing";
    /** Why a delivery screen after an offer does not count it, beside {@link #DELIVERY_UNDER_WAY} and its countdown. */
    static final String BEGAN_TO_DECLINE = "you began to decline it, so the delivery screen after it is no Accept";
    static final String DECLINED_THROUGH_NOTIFICATION =
            "a decline was requested through Dasher's notification, so the delivery screen after it is no Accept";
    static final String PAY_NOT_READ = "its pay was not read";
    static final String ADD_ON_NEEDS_TAP = "an add-on counts as accepted only with a seen Accept tap";
    static final String NO_COUNTDOWN = "no countdown was read, so it may have run out";
    /** How the reason begins when the offer's countdown, as last read, may have run out as it went. */
    static final String MAY_HAVE_RUN_OUT = "it may have run out: ";
    private static final List<String> PROGRESS_LABELS = Arrays.asList(
            "arrived at store", "arrived at pickup", "arrived at customer", "arrived at drop-off",
            "confirm pickup", "confirm pick up", "complete pickup", "complete delivery", "slide to confirm pickup");
    private static final Pattern BARE_RETAINED_PAY = Pattern.compile("\\$\\s*\\d{1,4}(?:[.,]\\d{1,2})?");
    private static final Pattern RETAINED_PAY_QUALIFIER = Pattern.compile("(?i)\\b(?:per|each|every|apiece"
            + "|up\\s+to|at\\s+least|at\\s+most|about|approximately|estimated?|bonus(?:es)?|tips?"
            + "|base\\s+pay|peak\\s+pay|guaranteed|total\\s+pay)\\b|×|\\b\\d+\\s*x\\b|\\bx\\s*\\d");
    private static final Pattern ROUTE_UNIT = Pattern.compile(
            "(?i)\\b(?:mi|miles?|mins?|minutes?|hrs?|hours?|stops?|ft|feet|yards?|yds?|mph|kph"
                    + "|km|kilomet(?:er|re)s?|meters?|metres?)\\b");
    private static final Pattern ROUTE_COUNT = Pattern.compile(
            "(?i)\\b\\d+\\s+(?:orders?|pick[ -]?ups?|drop[ -]?offs?)\\b");
    private static final Pattern BARE_COUNTDOWN = Pattern.compile("\\d{1,2}:[0-5]\\d");

    static final class Acceptance {
        final OfferSnapshot acceptedOffer;
        final OfferSnapshot routeAfter;
        final boolean addOn;
        /** The offer as its history line has it. */
        final OfferSnapshot line;
        /** Whether the Accept tap itself was seen. */
        final boolean tapSeen;

        Acceptance(OfferSnapshot acceptedOffer, OfferSnapshot routeAfter, boolean addOn) {
            this(acceptedOffer, routeAfter, addOn, acceptedOffer, true);
        }

        Acceptance(OfferSnapshot acceptedOffer, OfferSnapshot routeAfter, boolean addOn, OfferSnapshot line,
                   boolean tapSeen) {
            this.acceptedOffer = acceptedOffer;
            this.routeAfter = routeAfter;
            this.addOn = addOn;
            this.line = line == null ? acceptedOffer : line;
            this.tapSeen = tapSeen;
        }

        Integer baselinePay() {
            return routeAfter != null && routeAfter.payCents != null ? routeAfter.payCents : acceptedOffer.payCents;
        }
    }

    /** One step to keep on an offer's history line, with what the screen reader is to do about it. */
    static final class Note {
        final DecisionLog.StepKind kind;
        /** The offer as its history line has it. */
        final OfferSnapshot line;
        /** Fixed words and numbers only, never screen text. */
        final String detail;
        /** Accepted without the tap path: the acceptance to record. Null otherwise. */
        final Acceptance accepted;
        /**
         * Retired (0.5.0): an offer declined by hand was once held here to teach the adaptive minimum. Nothing is
         * taught any more, so it is always null.
         */
        @Deprecated
        final OfferSnapshot declined;
        /** Dasher's next screen, when it was neither a delivery nor the wait for offers: for the screens log. */
        final List<String> screen;

        Note(DecisionLog.StepKind kind, OfferSnapshot line, String detail, Acceptance accepted, OfferSnapshot declined,
             List<String> screen) {
            this.kind = kind;
            this.line = line;
            this.detail = detail == null ? "" : detail;
            this.accepted = accepted;
            this.declined = declined;
            this.screen = screen;
        }

        @Override public String toString() {
            return kind.label + (detail.isEmpty() ? "" : ": " + detail);
        }
    }

    /** What a screen with no offer on it shows, for what came after an offer. */
    enum After {
        /** A delivery or pickup under way. */
        ROUTE,
        /** The wait for offers. */
        WAITING,
        /**
         * The dash ended or paused, or Dasher's home before a dash; or (in {@link #afterScreen} only) the screen after
         * the user's "End dash" on Dasher's "End your current dash?" that shows no dash.
         */
        DASH_OVER,
        /**
         * Something else, a delivery and the wait for offers at once, navigation alone, or Dasher's "End your current
         * dash?": settles after {@link #SETTLE_MS} unchanged.
         */
        UNCLEAR,
        /** Nothing readable yet (a screen between two others). */
        EMPTY,
        /** Dasher's headline for a new offer. */
        NEW_OFFER,
        /** Any of an offer's facts (pay, miles, minutes, stops): an offer being drawn, never a next screen. */
        OFFER_FACTS
    }

    /** How a watch ended: what Dasher showed next, or what came first. */
    private enum End {
        ROUTE, WAITING, DASH_OVER, UNCLEAR,
        /** {@link #AFTER_MS} without a verdict. */
        TIMEOUT,
        /** Another offer came first. */
        ANOTHER_OFFER,
        /** Offer Filter requested a decline of it (or the user took it over during one). */
        DECLINED_BY_APP;

        /** A screen Dasher showed, so its time after the offer left is worth saying. */
        boolean screen() {
            return this == ROUTE || this == WAITING || this == DASH_OVER || this == UNCLEAR;
        }
    }

    /** What a screen without an offer on it shows. The words alone decide, never a stored route. */
    static After classify(List<String> labels) {
        return classify(labels, false);
    }

    /**
     * What a screen without an offer's controls on it shows. The words alone decide, never a stored route. Dasher's
     * "End your current dash?" is no answer either way here ({@link #afterScreen} counts the dash's end only after the
     * user's "End dash" on it), and nor is turn-by-turn navigation alone ({@link DasherScene#showsNavigation}).
     *
     * @param offerFacts the read found an offer's facts ({@link #offerFacts}), which its words alone may not show
     *     (split metric parts)
     */
    static After classify(List<String> labels, boolean offerFacts) {
        if (labels == null || labels.isEmpty()) return After.EMPTY;
        if (DasherScene.showsNewOffer(labels)) return After.NEW_OFFER;
        if (offerFacts || offerFacts(OfferParser.parse(labels), labels)) return After.OFFER_FACTS;
        if (DasherScene.showsEndDashQuestion(labels)) return After.UNCLEAR;
        boolean route = DasherScene.showsRoute(labels);
        boolean over = OfferEvidence.isDashOver(labels) || OfferEvidence.isPreDashHome(labels);
        boolean waiting = DasherScene.showsWaiting(labels);
        // Both at once is no clear answer either way.
        if (route && (over || waiting)) return After.UNCLEAR;
        if (over) return After.DASH_OVER;
        if (route) return After.ROUTE;
        if (waiting) return After.WAITING;
        return After.UNCLEAR;
    }

    /** Whether these labels show any offer facts, including a partial item/shopping declaration. */
    static boolean showsOfferFacts(List<String> labels) {
        OfferSnapshot facts = OfferParser.parse(labels);
        return facts.payCents != null || facts.payAtMostCents != null || facts.miles != null || facts.minutes != null
                || facts.stops != null || facts.itemCountApplicable;
    }

    /**
     * Whether a read of a screen without an offer's controls shows an offer being drawn: any of an offer's facts it
     * parsed (pay or a bound on it, miles, minutes, stops, items), except figures the screen itself explains. On
     * turn-by-turn navigation the distances and times are the navigation's own, so only pay or stops there are an
     * offer's; on the dash's summary between deliveries ("This dash so far", "This offer $9.00") every figure is the
     * dash's.
     *
     * @param read what the read parsed, from its labels and any metric parts joined from sibling nodes
     */
    static boolean offerFacts(OfferSnapshot read, List<String> labels) {
        if (read == null || DasherScene.showsDashSummary(labels)) return false;
        if (read.payCents != null || read.payAtMostCents != null || read.stops != null) return true;
        if (itemOfferEvidence(read, labels)) return true;
        return (read.miles != null || read.minutes != null) && !DasherScene.showsNavigation(labels);
    }

    /**
     * Item text holds a partial offer open; it is never positive acceptance or tap evidence. A delivery/shopping
     * list, navigation or dash summary can explain its own items and remains governed by its existing markers.
     */
    static boolean itemOfferEvidence(OfferSnapshot read, List<String> labels) {
        if (read == null || !read.itemCountApplicable || DasherScene.showsDashSummary(labels)
                || DasherScene.showsRoute(labels) || DasherScene.showsNavigation(labels)) return false;
        if (DasherScene.showsNewOffer(labels)) return true;
        if (labels != null) for (String label : labels) {
            String words = OfferEvidence.normalize(label);
            // These detail/list surfaces remain unknown (never positive delivery/acceptance evidence).
            if (words.equalsIgnoreCase("Order details") || words.equalsIgnoreCase("Pick these items")) return false;
        }
        if (read.items != null) return true;
        // Bare references to items also occur on unknown shopping lists/details. They remain unknown and may
        // settle as not counted; only this explicit offer-type label holds a count that has not drawn yet.
        if (labels != null) for (String label : labels) {
            if (OfferEvidence.normalize(label).matches("(?i)shop\\s*(?:&|and)\\s*deliver")) return true;
        }
        return false;
    }

    /**
     * Dasher shows the wait for offers, the dash's end or its home before a dash, and no sign of a delivery: no route
     * is under way, so a stored one is over.
     */
    static boolean showsNoRoute(List<String> labels) {
        if (labels == null || labels.isEmpty() || DasherScene.showsNewOffer(labels) || DasherScene.showsRoute(labels)) {
            return false;
        }
        return DasherScene.showsWaiting(labels) || OfferEvidence.isDashOver(labels)
                || OfferEvidence.isPreDashHome(labels);
    }

    /** A delivery-progress screen: an offer, and any decline confirmation for it, is over. */
    static boolean isDeliveryScreen(List<String> labels) {
        for (String label : labels) {
            if (PROGRESS_LABELS.contains(progressKey(label))) return true;
        }
        return false;
    }

    /** Fixed vocabulary for the existing bounded outcome diagnostics; never an arbitrary screen label. */
    static String progressKind(List<String> labels) {
        String kind = "none";
        if (labels != null) for (String label : labels) {
            String key = progressKey(label);
            if (!PROGRESS_LABELS.contains(key)) continue;
            String next = key.replace(' ', '_').replace('-', '_');
            if (!kind.equals("none") && !kind.equals(next)) return "multiple";
            kind = next;
        }
        return kind;
    }

    /** Relation to the watched original offer, without logging either amount or retaining new screen text. */
    String retainedPayRelation(OfferSnapshot read, List<String> labels) {
        OfferSnapshot original = watch != null ? watch.learn : pending != null ? pending.offer : null;
        return retainedPayRelation(read, labels, original);
    }

    private static String retainedPayRelation(OfferSnapshot read, List<String> labels, OfferSnapshot original) {
        if (!OfferEvidence.bounded(labels)) return "ambiguous";
        boolean money = false;
        boolean qualified = false;
        for (String raw : labels) {
            String label = OfferEvidence.normalize(raw);
            qualified |= RETAINED_PAY_QUALIFIER.matcher(label).find();
            if (!label.contains("$")) continue;
            money = true;
            // Labeled totals can win over other money in the general offer parser. This exception cannot:
            // every money label must be an unqualified amount, and the parser must find one consistent value.
            if (!BARE_RETAINED_PAY.matcher(label).matches()) return "ambiguous";
        }
        if (!money) return "none";
        if (qualified || read == null || read.payCents == null || read.payAtMostCents != null
                || OfferEvidence.malformedMoney(labels)) return "ambiguous";
        return original != null && original.payCents != null && original.payCents.equals(read.payCents)
                ? "matched" : "other";
    }

    /**
     * Acceptance only: a complete pickup/delivery read can retain the exact watched payout and item count.
     * Shared classification stays conservative for Peek, decline completion, and every unrelated screen. Only
     * the original full standalone offer can count as accepted; none of this read's figures replace its observed
     * facts.
     */
    private static After acceptanceScreen(List<String> labels, boolean offerFacts, OfferSnapshot read,
                                          boolean completeWithoutControls, OfferSnapshot original) {
        After shown = classify(labels, offerFacts);
        // Malformed or conflicting money can disappear from the general parser's facts entirely. For this
        // complete acceptance read it remains competing evidence, not a route with no monetary evidence.
        if (shown == After.ROUTE && original != null && read != null
                && retainedPayRelation(read, labels, original).equals("ambiguous")) return After.OFFER_FACTS;
        if (shown != After.OFFER_FACTS || !completeWithoutControls || original == null
                || original.payCents == null || original.payCents <= 0 || original.payAtMostCents != null
                || original.miles == null || original.minutes == null || original.stops == null
                || original.stops < 2 || original.itemCountApplicable && original.items == null
                || read == null || read.miles != null || read.minutes != null || read.stops != null
                || read.finalStopHotspotMiles != null || !isDeliveryScreen(labels)
                || !retainedPayRelation(read, labels, original).equals("matched")
                || read.itemCountApplicable && (read.items == null || !read.items.equals(original.items))
                || DasherScene.showsNewOffer(labels) || DasherScene.showsWaiting(labels)
                || OfferEvidence.isDashOver(labels) || OfferEvidence.isPreDashHome(labels)
                || DasherScene.showsEndDashQuestion(labels) || DeclineConfirmation.isSurface(labels)
                || DasherScene.showsNavigation(labels) || AddOnOffer.isLikely(labels)) return shown;
        for (String raw : labels) {
            String label = OfferEvidence.normalize(raw);
            if (OfferControls.isButton(label, "accept") || OfferControls.isButton(label, "decline")
                    || BARE_COUNTDOWN.matcher(label).matches() || ROUTE_UNIT.matcher(label).find()
                    || ROUTE_COUNT.matcher(label).find()) return shown;
        }
        return After.ROUTE;
    }

    /** A label as the progress list has it: normalized, lower case, without trailing punctuation. */
    private static String progressKey(String label) {
        return OfferEvidence.normalize(label).toLowerCase(Locale.US).replaceAll("[.!…]+$", "");
    }

    // ---- The tap path ----

    /** An offer on screen, whatever its decision, with what a seen Accept tap on it needs. */
    private static final class Sighting {
        final String key;
        final OfferSnapshot offer;
        final OfferSnapshot routeAfter;
        final boolean addOn;
        /** A delivery was positively under way as it came: a stored route, or the last clear screen a delivery. */
        final boolean deliveryBefore;
        /** Dasher's wait for offers was read after the offer before this one, and before this one came. */
        final boolean waitingBefore;
        OfferSnapshot line;
        long at;
        int secondsLeft = -1;
        long countdownAt;
        /** When a read first found it gone after its last read, -1 while it shows. */
        long goneAt = -1;
        /** Automatic requests never enter the manual/no-tap inference path. */
        boolean automaticRequest;

        Sighting(String key, OfferSnapshot offer, OfferSnapshot routeAfter, boolean addOn, boolean deliveryBefore,
                 boolean waitingBefore) {
            this.key = key;
            this.offer = offer;
            this.routeAfter = routeAfter;
            this.addOn = addOn;
            this.deliveryBefore = deliveryBefore;
            this.waitingBefore = waitingBefore;
        }
    }

    /** The offer on screen now; null once Dasher left the screen. */
    private Sighting visible;
    /** The latest offer seen, kept while Dasher is away, so the same offer drawn again keeps what came before it. */
    private Sighting last;
    /** The offer an Accept tap was seen on, waiting for its delivery screen. */
    private Sighting pending;
    private long clickedAt;
    /** A screen other than an offer (or an empty one) was read since the latest offer was first seen. */
    private boolean screenSinceOffer;

    // ---- What came after an offer left alone ----

    /** The offer Offer Filter left alone that is on screen, or left it moments ago; null when none. */
    private Watch watch;
    /**
     * The last screen that showed a delivery, the wait for offers or the dash over since the last offer came, so the
     * next offer knows which; null when none was read since (an offer uses it up), after a restart, and after a watch
     * ends on anything else.
     */
    private After lastClear;
    private final List<Note> notes = new ArrayList<>();

    private static final class Watch {
        final OfferSnapshot line;
        /** What an acceptance of it records (an add-on's own increment). */
        OfferSnapshot learn;
        OfferSnapshot routeAfter;
        final boolean addOn;
        final boolean deliveryBefore;
        final boolean waitingBefore;
        final long firstAt;
        long lastAt;
        int secondsLeft = -1;
        long countdownAt;
        long leftAt = -1;
        boolean acceptTapped;
        boolean declineTapped;
        /** The latest read of Dasher's decline question while it is open, else -1. */
        long questionAt = -1;
        boolean questioned;
        /** A decline by hand (a seen tap, or Dasher's question) held until Dasher moves on. */
        boolean declinePending;
        /** The latest sign of that decline (the tap, or a read of the question). */
        long declineSignalAt = -1;
        /** The offer was read again while that decline was held (perhaps the question's sheet closing). */
        boolean reread;
        /** A screen neither empty nor an offer was read while that decline was held. */
        boolean otherSince;
        /** A decline was requested through Dasher's notification while it was watched. */
        boolean declinedElsewhere;
        /** The screen that is neither a delivery nor the wait for offers, while it may still settle. */
        List<String> unclear;
        String unclearWords;
        long unclearSince;

        Watch(OfferSnapshot line, OfferSnapshot learn, OfferSnapshot routeAfter, boolean addOn,
              boolean deliveryBefore, boolean waitingBefore, long now) {
            this.line = line;
            this.learn = learn;
            this.routeAfter = routeAfter;
            this.addOn = addOn;
            this.deliveryBefore = deliveryBefore;
            this.waitingBefore = waitingBefore;
            this.firstAt = now;
            this.lastAt = now;
        }

        boolean sameOffer(OfferSnapshot facts, boolean otherAddOn) {
            return addOn == otherAddOn && !facts.contradicts(line);
        }

        /** The user began to decline it (a seen tap, or Dasher's question), whether or not that still counts. */
        boolean declineBegun() {
            return declineTapped || questioned;
        }
    }

    void observeOffer(OfferSnapshot offer, long now) {
        observeOffer(offer, offer, false, now);
    }

    void observeOffer(OfferSnapshot offer, OfferSnapshot routeAfter, boolean addOn, long now) {
        observeOffer(offer, routeAfter, addOn, offer, now);
    }

    void observeOffer(OfferSnapshot offer, OfferSnapshot routeAfter, boolean addOn, OfferSnapshot line, long now) {
        observeOffer(offer, routeAfter, addOn, line, -1, false, now);
    }

    /**
     * An offer is on screen, whatever its decision: what a seen Accept tap would accept. A different offer uses up the
     * wait for offers read before it: the offer after it needs the wait read again.
     *
     * @param secondsLeft its countdown, -1 when none was read
     * @param routeStored whether a route was stored as it came
     */
    void observeOffer(OfferSnapshot offer, OfferSnapshot routeAfter, boolean addOn, OfferSnapshot line,
                      int secondsLeft, boolean routeStored, long now) {
        String key = key(offer, routeAfter, addOn);
        // A delivery, the wait for offers or the dash's end read since the last offer: this is a new showing.
        boolean anew = lastClear != null;
        // A different offer cancels an Accept waiting for its delivery screen; the same offer read again does not.
        if (pending != null && (anew || !key.equals(pending.key))) {
            unconfirmedAutomatic("another offer or a new showing came before delivery confirmation");
            clearPending();
        }
        if (pending != null && pending.automaticRequest && (pending.goneAt >= 0
                || secondsLeft >= 0 && pending.secondsLeft >= 0
                && secondsLeft * 1000L > pending.secondsLeft * 1000L - (now - pending.countdownAt) + 3_000)) {
            unconfirmedAutomatic("the offer returned or its countdown restarted before delivery confirmation");
        }
        Sighting s = !anew && visible != null && visible.key.equals(key) ? visible
                : pending != null ? pending
                : sighting(key, offer, routeAfter, addOn, routeStored);
        s.line = line;
        s.at = now;
        s.goneAt = -1;
        if (secondsLeft >= 0) {
            s.secondsLeft = secondsLeft;
            s.countdownAt = now;
        }
        visible = s;
        last = s;
    }

    /**
     * A new sighting: the same offer drawn further (no other screen read since the last one, which showed none of its
     * facts yet or the same ones) keeps what came before it; another offer takes what was read since, and uses it up.
     */
    private Sighting sighting(String key, OfferSnapshot offer, OfferSnapshot routeAfter, boolean addOn,
                              boolean routeStored) {
        Sighting before = last;
        boolean again = before != null && lastClear == null && !screenSinceOffer && before.addOn == addOn
                && (noFacts(before.offer) || offer.agreesWith(before.offer));
        boolean deliveryBefore = routeStored || (again ? before.deliveryBefore : lastClear == After.ROUTE);
        boolean waitingBefore = !routeStored && (again ? before.waitingBefore : lastClear == After.WAITING);
        lastClear = null;
        screenSinceOffer = false;
        return new Sighting(key, offer, routeAfter, addOn, deliveryBefore, waitingBefore);
    }

    private static boolean noFacts(OfferSnapshot offer) {
        return offer.payCents == null && offer.payAtMostCents == null && offer.miles == null && offer.minutes == null
                && offer.stops == null;
    }

    private static String key(OfferSnapshot offer, OfferSnapshot routeAfter, boolean addOn) {
        return offer.fingerprint() + "->" + (routeAfter == null ? "null" : routeAfter.fingerprint()) + ":" + addOn;
    }

    /**
     * Android received one automatic request. A click result is not a user's tap and cannot enter the weaker
     * manual/no-tap route inference. Only this request's subsequent explicit delivery-progress read can confirm it.
     */
    void automaticAcceptRequested(OfferSnapshot offer, long now, boolean dispatched) {
        Watch w = watch;
        if (w != null && w.sameOffer(offer, false)) watch = null;
        clearPending();
        Sighting s = visible;
        if (!dispatched || s == null || s.addOn || now - s.at > 2_000
                || !s.offer.fingerprint().equals(offer.fingerprint())) {
            note(DecisionLog.StepKind.AUTO_ACCEPT_UNCONFIRMED, offer,
                    dispatched ? "the requested offer could not be verified; no acceptance claimed"
                            : "Android refused the request; no acceptance claimed and no repeat request");
            return;
        }
        s.automaticRequest = true;
        pending = s;
        clickedAt = now;
        note(DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED, s.line,
                "waiting up to 15 s for explicit pickup or delivery progress; no completion claimed");
    }

    /** Clear only automatic confirmation authority, with a truthful outcome; never grant another click. */
    private boolean unconfirmedAutomatic(String reason) {
        if (pending == null || !pending.automaticRequest) return false;
        note(DecisionLog.StepKind.AUTO_ACCEPT_UNCONFIRMED, pending.line, reason);
        clearPending();
        return true;
    }

    /** @return the offer the tap is taken to accept, or null when no readable offer was on screen recently */
    OfferSnapshot acceptClicked(long now) {
        Watch w = watch;
        if (w != null && now - w.lastAt <= MAX_OFFER_AGE_AT_CLICK_MS) {
            w.acceptTapped = true;
            if (w.declinePending) dropDecline(w, "you tapped Accept on it after all");
        }
        Sighting s = visible;
        boolean recent = s != null && now - s.at <= MAX_OFFER_AGE_AT_CLICK_MS;
        boolean readablePay = recent && s.offer.payCents != null && s.offer.payCents > 0;
        if (readablePay) {
            s.automaticRequest = false;
            pending = s;
            clickedAt = now;
            note(DecisionLog.StepKind.ACCEPT_TAPPED, s.line, "waiting for a delivery screen");
            return s.offer;
        }
        if (recent) note(DecisionLog.StepKind.ACCEPT_TAPPED, s.line, PAY_NOT_READ);
        return null;
    }

    /**
     * An Accept tap whose delivery screen never came in time: returns that offer once (and forgets it), so the log
     * can say it was not counted; null otherwise.
     */
    OfferSnapshot missedAcceptance(long now) {
        if (pending == null || now - clickedAt <= MAX_CLICK_TO_PROGRESS_MS) return null;
        OfferSnapshot missed = pending.offer;
        note(pending.automaticRequest ? DecisionLog.StepKind.AUTO_ACCEPT_UNCONFIRMED
                : DecisionLog.StepKind.ACCEPT_UNCONFIRMED, pending.line,
                pending.automaticRequest ? "no explicit pickup or delivery progress within 15 s" : "");
        clearPending();
        return missed;
    }

    Acceptance observeOtherScreen(List<String> labels, long now) {
        return observeOtherScreen(labels, false, now);
    }

    /**
     * Returns the acceptance once delivery progress follows a recent Accept tap; otherwise null. A standalone offer
     * that came during a delivery, or may have run out, is not counted as accepted even with the tap: its history line
     * says so.
     *
     * @param offerFacts the read found an offer's facts: an offer being drawn, never a delivery screen
     */
    Acceptance observeOtherScreen(List<String> labels, boolean offerFacts, long now) {
        return observeOtherScreen(labels, offerFacts, null, false, now);
    }

    /** A complete screen read may explain matching retained pay only for an existing manual tap. */
    Acceptance observeOtherScreen(List<String> labels, boolean offerFacts, OfferSnapshot read,
                                  boolean completeWithoutControls, long now) {
        if (pending == null || now - clickedAt > MAX_CLICK_TO_PROGRESS_MS) {
            unconfirmedAutomatic("no explicit pickup or delivery progress within 15 s");
            clearPending();
            return null;
        }
        OfferSnapshot original = pending.automaticRequest || pending.addOn
                || notCountedWithTap(pending.deliveryBefore, pending.secondsLeft, pending.countdownAt,
                        pending.goneAt < 0 ? now : pending.goneAt) != null ? null : pending.offer;
        After shown = acceptanceScreen(labels, offerFacts, read, completeWithoutControls, original);
        if (pending.automaticRequest && (shown == After.WAITING || shown == After.DASH_OVER
                || shown == After.NEW_OFFER || DeclineConfirmation.hasPrompt(labels))) {
            unconfirmedAutomatic("Dasher returned to waiting, ended the dash, or showed another offer/decline question");
            return null;
        }
        if (pending.goneAt < 0) pending.goneAt = now;
        if (!isDeliveryScreen(labels) || shown != After.ROUTE) return null;
        Sighting s = pending;
        clearPending();
        Watch w = watch;
        // Its verdict is given here: what came after that offer needs none of its own.
        if (w != null && w.sameOffer(s.line, s.addOn)) watch = null;
        String why = s.addOn ? null : notCountedWithTap(s.deliveryBefore, s.secondsLeft, s.countdownAt, s.goneAt);
        if (why != null) {
            note(s.automaticRequest ? DecisionLog.StepKind.AUTO_ACCEPT_UNCONFIRMED
                    : DecisionLog.StepKind.NOT_LEARNED, s.line, why);
            return null;
        }
        lastClear = After.ROUTE;
        visible = null;
        return new Acceptance(s.offer, s.routeAfter, s.addOn, s.line, true);
    }

    /**
     * The user tapped something on Dasher near an offer that was neither Accept nor Decline (or named both): the
     * offer's line says so, so a report shows whether Dasher reports the user's taps at all.
     */
    void tapNotRecognized(long now) {
        Sighting s = visible;
        if (s != null && now - s.at <= MAX_OFFER_AGE_AT_CLICK_MS) {
            note(DecisionLog.StepKind.TAP_NOT_RECOGNIZED, s.line, "its shape and words are in the screens log");
        }
    }

    // ---- What came after an offer left alone ----

    /**
     * An offer Offer Filter leaves alone is on screen: it passed, was left for the user's review, or auto-decline is
     * paused. A different offer gives the one watched before its verdict.
     *
     * @param line        the offer as its history line has it
     * @param learn       what an acceptance of it records (an add-on's own increment)
     * @param passed      the rules let it through; kept for callers, it no longer changes anything here
     * @param secondsLeft its countdown, -1 when none was read
     * @param routeStored whether a route was stored as it came
     */
    void offerLeftAlone(OfferSnapshot line, OfferSnapshot learn, OfferSnapshot routeAfter, boolean addOn,
                        boolean passed, int secondsLeft, boolean routeStored, long now) {
        // A frame with nothing read yet could be any offer: it neither continues nor ends one.
        if (noFacts(line)) return;
        Watch w = watch;
        if (w != null && w.sameOffer(line, addOn)) {
            if (w.declinePending) {
                // The question's sheet closing (or the offer before Dasher asks) can show the offer for a moment:
                // a real return is the offer, positively the same, a second after the last sign of the decline.
                boolean back = now - w.declineSignalAt >= BACK_TO_OFFER_MS && line.agreesWith(w.line);
                if (!back) {
                    w.reread = true;
                    return;
                }
                dropDecline(w, "you went back to the offer");
            }
            w.learn = learn;
            w.routeAfter = routeAfter;
            seen(w, secondsLeft, now);
            return;
        }
        if (w != null) resolve(w, End.ANOTHER_OFFER, now);
        Sighting s = visible;
        boolean deliveryBefore;
        boolean waitingBefore;
        if (s != null && s.key.equals(key(learn, routeAfter, addOn))) {
            deliveryBefore = s.deliveryBefore || routeStored;
            waitingBefore = s.waitingBefore && !routeStored;
        } else {
            deliveryBefore = routeStored || lastClear == After.ROUTE;
            waitingBefore = !routeStored && lastClear == After.WAITING;
        }
        // This offer uses up what was read before it.
        lastClear = null;
        screenSinceOffer = false;
        Watch next = new Watch(line, learn, routeAfter, addOn, deliveryBefore, waitingBefore, now);
        seen(next, secondsLeft, now);
        watch = next;
    }

    private static void seen(Watch w, int secondsLeft, long now) {
        w.lastAt = now;
        w.leftAt = -1;
        w.unclear = null;
        w.unclearWords = null;
        if (secondsLeft >= 0) {
            w.secondsLeft = secondsLeft;
            w.countdownAt = now;
        }
    }

    /**
     * A peek brought Dasher up: an offer read during it counts only what is read during it. The wait for offers read
     * before (perhaps long before, while Dasher was hidden) is forgotten, and a decline by hand held for an offer before
     * it has had another screen since, so the peeked offer never counts it.
     */
    void peekBegan() {
        lastClear = null;
        Watch w = watch;
        if (w != null && w.declinePending) w.otherSince = true;
    }

    /** Offer Filter requested a decline of this offer (or the user took it over during one): it is not watched. */
    void offerDeclinedByApp(OfferSnapshot line, boolean addOn, long now) {
        // An offer came: the wait read before it is used up.
        lastClear = null;
        Watch w = watch;
        if (w == null) return;
        resolve(w, w.sameOffer(line, addOn) ? End.DECLINED_BY_APP : End.ANOTHER_OFFER, now);
    }

    /**
     * A decline of an offer was requested through Dasher's notification lately: the offer watched, if any, is never
     * taken as accepted without a tap (a delivery screen after it would prove nothing).
     */
    void declineRequestedElsewhere() {
        unconfirmedAutomatic("a decline was requested before delivery confirmation");
        Watch w = watch;
        if (w != null) w.declinedElsewhere = true;
    }

    /**
     * Dasher asks "Are you sure you want to decline this offer?" while no decline of Offer Filter's own is under way:
     * the user tapped Decline on the offer watched, if it was read moments ago. Held until Dasher moves on.
     */
    void declineQuestion(long now) {
        unconfirmedAutomatic("Dasher asked to confirm declining it before delivery confirmation");
        Watch w = watch;
        if (w == null) return;
        if (w.questionAt < 0) {
            if (now - w.lastAt > QUESTION_AGE_MS) return;
            w.questioned = true;
            note(DecisionLog.StepKind.DECLINE_QUESTION, w.line,
                    w.declinePending && w.declineTapped ? "after your Decline tap" : "");
            if (!w.declinePending) hold(w);
        }
        w.questionAt = now;
        w.declineSignalAt = now;
        // The question shows again: whatever showed between was not a return to the offer.
        w.reread = false;
        if (w.leftAt < 0) w.leftAt = now;
        w.unclear = null;
        w.unclearWords = null;
    }

    /**
     * The user's own Decline tap (never Offer Filter's echo): held, like Dasher's question, until Dasher moves on.
     * Nothing counts at the tap itself.
     */
    void declineTapped(long now) {
        unconfirmedAutomatic("you tapped Decline before delivery confirmation");
        Watch w = watch;
        if (w == null || now - w.lastAt > MAX_OFFER_AGE_AT_CLICK_MS) return;
        w.declineTapped = true;
        if (w.declinePending) {
            // Already held (by Dasher's question, say: this may be the user confirming it).
            w.declineSignalAt = Math.max(w.declineSignalAt, now);
            return;
        }
        hold(w);
        w.declineSignalAt = now;
        note(DecisionLog.StepKind.DECLINE_TAPPED, w.line,
                "it counts once Dasher goes back to the wait for offers or another offer comes");
    }

    private static void hold(Watch w) {
        w.declinePending = true;
        w.reread = false;
        w.otherSince = false;
    }

    /** A held decline no longer counts, for the reason given. */
    private void dropDecline(Watch w, String why) {
        w.declinePending = false;
        w.questionAt = -1;
        w.reread = false;
        note(DecisionLog.StepKind.DECLINE_DROPPED, w.line, why);
    }

    void afterScreen(List<String> labels, long now) {
        afterScreen(labels, false, now);
    }

    /**
     * Dasher's screen with neither Accept nor Decline on it, nor its decline question: the offer watched has left.
     * A delivery, the wait for offers or the dash over gives it its verdict at once; an empty screen, or an offer
     * being drawn, waits; anything else gives it once its words stay {@link #SETTLE_MS}.
     *
     * @param offerFacts the read found an offer's facts, which its words alone may not show
     */
    void afterScreen(List<String> labels, boolean offerFacts, long now) {
        afterScreen(labels, offerFacts, null, false, now);
    }

    /** A complete screen read may explain matching retained pay only within the current manual offer watch. */
    void afterScreen(List<String> labels, boolean offerFacts, OfferSnapshot read,
                     boolean completeWithoutControls, long now) {
        Watch w = watch;
        OfferSnapshot original = w == null || w.addOn || w.leftAt >= 0 && now - w.leftAt >= AFTER_MS
                || notAcceptedBecause(w, w.leftAt < 0 ? now : w.leftAt) != null ? null : w.learn;
        After shown = endOfDash(labels,
                acceptanceScreen(labels, offerFacts, read, completeWithoutControls, original), now);
        if (shown == After.ROUTE || shown == After.WAITING || shown == After.DASH_OVER) lastClear = shown;
        if (shown != After.EMPTY && shown != After.NEW_OFFER && shown != After.OFFER_FACTS) screenSinceOffer = true;
        if (w == null) {
            // A wait read before an unrelated/unclear screen is not proof the user was waiting when the next offer
            // arrived. With a watched offer the existing settling path makes this decision instead.
            if (shown == After.UNCLEAR) lastClear = null;
            return;
        }
        if (w.leftAt < 0) w.leftAt = now;
        switch (shown) {
            case EMPTY:
            case NEW_OFFER:
            case OFFER_FACTS:
                // The screen between two others, or the next offer coming: not a next screen yet.
                w.unclear = null;
                w.unclearWords = null;
                return;
            case UNCLEAR:
                if (w.declinePending) w.otherSince = true;
                String words = words(labels);
                if (!words.equals(w.unclearWords)) {
                    w.unclear = new ArrayList<>(labels);
                    w.unclearWords = words;
                    w.unclearSince = now;
                } else if (now - w.unclearSince >= SETTLE_MS) {
                    resolve(w, End.UNCLEAR, now);
                }
                return;
            case ROUTE:
                resolve(w, End.ROUTE, now);
                return;
            case WAITING:
                resolve(w, End.WAITING, now);
                return;
            default:
                resolve(w, End.DASH_OVER, now);
        }
    }

    // ---- Dasher's "End your current dash?" ----

    /** The latest read showing Dasher's question before it ends a dash, -1 when none is being followed. */
    private long endQuestionAt = -1;
    /** The first read since that question showing neither it nor the dash, -1 for none yet. */
    private long dashGoneAt = -1;
    /** The user's "End dash" tap on that question, -1 for none seen. */
    private long endTapAt = -1;

    /**
     * What a read means once Dasher asked "End your current dash?": the question itself is unclear (the user may go
     * back); a screen without the question that shows neither the dash (a delivery, the wait for offers) nor an offer
     * is the dash's end once the user's "End dash" on the question was seen, else as unclear as its words; anything of
     * the dash means it went on.
     */
    private After endOfDash(List<String> labels, After shown, long now) {
        if (DasherScene.showsEndDashQuestion(labels)) {
            endQuestionAt = now;
            dashGoneAt = -1;
            return shown;
        }
        if (endQuestionAt < 0 || shown == After.EMPTY) return shown;
        if (shown != After.UNCLEAR || (endTapAt >= 0 && now - endTapAt > AFTER_MS)) {
            // The dash went on (or its own words say it is over), or the tap is long past.
            forgetEndQuestion();
            return shown;
        }
        if (endTapAt >= 0) {
            forgetEndQuestion();
            return After.DASH_OVER;
        }
        if (dashGoneAt < 0) {
            dashGoneAt = now;
        } else if (now - dashGoneAt > QUESTION_AGE_MS) {
            // No "End dash" came with it: just another screen.
            forgetEndQuestion();
        }
        return shown;
    }

    /**
     * The user's "End dash" tap (Offer Filter never taps it), by the tap's own time: it counts only on Dasher's "End
     * your current dash?" while that question was the last screen read. The dash is over once a read shows neither the
     * question nor the dash: at once when one already has (its click is read after the read of what came next), else
     * at the next such read. A dash's screen read again means it went on.
     */
    void endDashTapped(long at) {
        if (endQuestionAt < 0) return;
        if (dashGoneAt < 0) {
            endTapAt = at;
            return;
        }
        boolean madeItGo = at <= dashGoneAt && dashGoneAt - at <= QUESTION_AGE_MS;
        forgetEndQuestion();
        if (!madeItGo) return;
        lastClear = After.DASH_OVER;
        screenSinceOffer = true;
        Watch w = watch;
        if (w != null && w.leftAt >= 0) resolve(w, End.DASH_OVER, Math.max(at, dashGoneAt));
    }

    private void forgetEndQuestion() {
        endQuestionAt = -1;
        dashGoneAt = -1;
        endTapAt = -1;
    }

    /** A timeout only records that no acceptance was established; it never reads a screen. */
    boolean expire(long now) {
        if (pending != null && pending.automaticRequest && now - clickedAt > MAX_CLICK_TO_PROGRESS_MS) {
            return unconfirmedAutomatic("no explicit pickup or delivery progress within 15 s");
        }
        Watch w = watch;
        if (w == null || w.leftAt < 0 || now - w.leftAt < AFTER_MS) return false;
        resolve(w, End.TIMEOUT, now);
        return true;
    }

    /**
     * Time passing: an unrecognised screen that stayed {@link #SETTLE_MS} (while Dasher can be read), or
     * {@link #AFTER_MS} without a verdict.
     */
    void tick(long now, boolean dasherReadable) {
        if (expire(now)) return;
        Watch w = watch;
        if (w == null || w.leftAt < 0) return;
        if (w.unclear != null && now - w.unclearSince >= SETTLE_MS) {
            if (dasherReadable) {
                resolve(w, End.UNCLEAR, now);
            } else {
                // Dasher left the screen: that screen is not known to have stayed. The next read starts again.
                w.unclear = null;
                w.unclearWords = null;
            }
        }
    }

    /** When {@link #tick} is next due (uptime), or -1 when nothing is waiting. */
    long nextDeadline() {
        long automaticDue = pending != null && pending.automaticRequest
                ? clickedAt + MAX_CLICK_TO_PROGRESS_MS + 1 : -1;
        Watch w = watch;
        if (w == null || w.leftAt < 0) return automaticDue;
        long due = w.leftAt + AFTER_MS;
        if (w.unclear != null) due = Math.min(due, w.unclearSince + SETTLE_MS);
        return automaticDue < 0 ? due : Math.min(due, automaticDue);
    }

    /**
     * Read-only outcome observation shares the existing deadlines. It cannot extend a watch, recreate one after
     * restart, or restore action authority. A pending tap and a no-tap watch may overlap; each retains its own guard.
     */
    long outcomeObservationDeadline() {
        long due = pending == null ? -1 : clickedAt + MAX_CLICK_TO_PROGRESS_MS;
        if (watch != null && watch.leftAt >= 0) due = Math.max(due, watch.leftAt + AFTER_MS);
        return due;
    }

    /** In-process observation identity for bounded fixed-category diagnostics; never persisted. */
    long outcomeObservationKey() {
        return watch != null ? watch.firstAt : pending != null ? clickedAt : -1;
    }

    /** How long ago the offer watched left the screen, or -1 while it shows or none is watched. */
    long leftFor(long now) {
        Watch w = watch;
        return w == null || w.leftAt < 0 ? -1 : Math.max(0, now - w.leftAt);
    }

    /**
     * The offer Offer Filter left alone that is on screen, or left it moments ago, as its history line has it; null
     * when none is watched. It names the offer a decline question the user brought up is about (for the
     * acceptance-rate reading); {@link #watchedLine(long)} also applies this tracker's own rule for that.
     */
    OfferSnapshot watchedLine() {
        Watch w = watch;
        return w == null ? null : w.line;
    }

    /**
     * As {@link #watchedLine()}, but only while a decline question seen at {@code now} (uptime) would be about it: one
     * was already taken to be, or the offer was read at most {@link #QUESTION_AGE_MS} before. Null otherwise.
     */
    OfferSnapshot watchedLine(long now) {
        Watch w = watch;
        if (w == null || (w.questionAt < 0 && now - w.lastAt > QUESTION_AGE_MS)) return null;
        return w.line;
    }

    /** When the offer watched first showed (uptime), or -1: tells one watch from the next. */
    long watchedSince() {
        Watch w = watch;
        return w == null ? -1 : w.firstAt;
    }

    /** The steps noted since last asked, oldest first. */
    List<Note> takeNotes() {
        if (notes.isEmpty()) return java.util.Collections.emptyList();
        List<Note> taken = new ArrayList<>(notes);
        notes.clear();
        return taken;
    }

    /** The verdict on the offer watched, by how the watch ended. */
    private void resolve(Watch w, End end, long now) {
        watch = null;
        // Anything but a clear screen leaves unknown what Dasher shows now: the next offer needs the wait read again.
        if (end != End.WAITING && end != End.DASH_OVER && end != End.ROUTE) lastClear = null;
        String when = w.leftAt >= 0 && end.screen() ? " " + seconds(now - w.leftAt) + " s after it left" : "";
        List<String> screen = end == End.UNCLEAR ? w.unclear : null;
        if (w.declinePending) {
            resolveDecline(w, end, when, screen);
            return;
        }
        switch (end) {
            case WAITING:
            case DASH_OVER:
                note(DecisionLog.StepKind.NOT_ACCEPTED, w.line, describe(end) + when);
                return;
            case ROUTE:
                break;
            default:
                notes.add(new Note(DecisionLog.StepKind.NOT_LEARNED, w.line, describe(end) + when, null, null,
                        screen));
                return;
        }
        String reason = notAcceptedBecause(w);
        if (reason != null) {
            note(DecisionLog.StepKind.NOT_LEARNED, w.line, reason);
            return;
        }
        // Counted here: a seen Accept tap on it waits for nothing more, so it is never counted twice.
        clearPending();
        long left = timeLeftWhenGone(w);
        String detail = w.acceptTapped
                ? "you tapped Accept, and Dasher showed a delivery screen" + when
                : "it closed with about " + clock(left) + " left on its countdown, and Dasher showed a delivery screen"
                        + when;
        notes.add(new Note(DecisionLog.StepKind.ACCEPTED_LEARNED, w.line, detail,
                new Acceptance(w.learn, w.routeAfter, w.addOn, w.line, w.acceptTapped), null, null));
    }

    /**
     * A held decline by hand: it counts only when Dasher goes back to the wait for offers, or another offer comes
     * (for a seen tap, also back to a delivery under way before). Anything else counts nothing, and says why.
     */
    private void resolveDecline(Watch w, End end, String when, List<String> screen) {
        String drop;
        switch (end) {
            case WAITING:
                drop = w.reread && w.otherSince
                        ? "you went back to the offer, and Dasher showed another screen before the wait for offers"
                        : null;
                break;
            case ANOTHER_OFFER:
                drop = w.otherSince ? "Dasher showed another screen before the next offer came" : null;
                break;
            case ROUTE:
                if (!w.declineTapped) {
                    drop = "Dasher showed a delivery screen next, so you may have accepted it after all";
                } else if (!w.deliveryBefore) {
                    drop = "a delivery screen came next, and none was under way before";
                } else {
                    drop = w.reread ? "you went back to the offer" : null;
                }
                break;
            case DASH_OVER:
                drop = "the dash ended or paused";
                break;
            case UNCLEAR:
                drop = "Dasher's next screen was neither the wait for offers nor another offer (its words are in the "
                        + "screens log)";
                break;
            case TIMEOUT:
                drop = "Dasher showed neither the wait for offers nor another offer within " + AFTER_MS / 1000 + " s";
                break;
            default:
                drop = AppName.NAME + " requested a decline of it";
                break;
        }
        if (drop != null) {
            notes.add(new Note(DecisionLog.StepKind.DECLINE_DROPPED, w.line, drop, null, null, screen));
            return;
        }
        String outcome = end == End.ANOTHER_OFFER ? "another offer came"
                : end == End.ROUTE ? "Dasher went back to the delivery under way" : describe(end) + when;
        notes.add(new Note(DecisionLog.StepKind.DECLINE_COUNTED, w.line, outcome, null, null, null));
    }

    /** Why a delivery screen after this offer does not show it was accepted; null when it does. */
    private static String notAcceptedBecause(Watch w) {
        return notAcceptedBecause(w, w.leftAt);
    }

    /** The same eligibility before the first complete no-controls read has recorded its disappearance time. */
    private static String notAcceptedBecause(Watch w, long goneAt) {
        if (w.declineBegun()) return BEGAN_TO_DECLINE;
        if (w.declinedElsewhere) return DECLINED_THROUGH_NOTIFICATION;
        // An acceptance is counted, and a route kept, only with its pay: none was read.
        if (w.learn == null || w.learn.payCents == null) return PAY_NOT_READ;
        if (w.addOn) return w.acceptTapped ? null : ADD_ON_NEEDS_TAP;
        if (w.acceptTapped) return notCountedWithTap(w.deliveryBefore, w.secondsLeft, w.countdownAt, goneAt);
        if (w.deliveryBefore) return DELIVERY_UNDER_WAY;
        if (!w.waitingBefore) return WAIT_NOT_SEEN;
        if (w.secondsLeft < 0) return NO_COUNTDOWN;
        return ranOut(w.secondsLeft, w.countdownAt, goneAt);
    }

    /**
     * Whether a "Not counted as accepted" reason is one given when Dasher showed a delivery screen after the offer
     * (not counted, but the screen came): every reason {@link #notAcceptedBecause} and the Accept tap's own path give,
     * never those of a watch that ended any other way. With a seen Accept tap before it, the offer was seen accepted.
     */
    static boolean deliveryScreenFollowed(String reason) {
        if (reason == null) return false;
        return reason.equals(DELIVERY_UNDER_WAY) || reason.startsWith(MAY_HAVE_RUN_OUT)
                || reason.equals(BEGAN_TO_DECLINE) || reason.equals(DECLINED_THROUGH_NOTIFICATION)
                || reason.equals(PAY_NOT_READ) || reason.equals(ADD_ON_NEEDS_TAP) || reason.equals(WAIT_NOT_SEEN)
                || reason.equals(NO_COUNTDOWN);
    }

    /**
     * Why a standalone offer with a seen Accept tap is not counted as accepted: a delivery was under way as it came,
     * or its countdown (when one was read) may have run out. Null when neither.
     */
    private static String notCountedWithTap(boolean deliveryBefore, int secondsLeft, long countdownAt, long goneAt) {
        if (deliveryBefore) return DELIVERY_UNDER_WAY;
        return secondsLeft < 0 ? null : ranOut(secondsLeft, countdownAt, goneAt);
    }

    /** "it may have run out: …" when its countdown had {@link #TIME_LEFT_MS} or less left as it went; else null. */
    private static String ranOut(int secondsLeft, long countdownAt, long goneAt) {
        if (timeLeftWhenGone(secondsLeft, countdownAt, goneAt) > TIME_LEFT_MS) return null;
        return MAY_HAVE_RUN_OUT + "its countdown showed " + clock(secondsLeft * 1000L) + ", "
                + seconds(goneAt - countdownAt) + " s before a read found it gone";
    }

    /** The countdown when a read found the offer gone: as last read, less the time since. */
    private static long timeLeftWhenGone(Watch w) {
        return timeLeftWhenGone(w.secondsLeft, w.countdownAt, w.leftAt);
    }

    private static long timeLeftWhenGone(int secondsLeft, long countdownAt, long goneAt) {
        return secondsLeft * 1000L - Math.max(0, goneAt - countdownAt);
    }

    private static String describe(End end) {
        switch (end) {
            case ROUTE:
                return "Dasher showed a delivery screen";
            case WAITING:
                return "Dasher went back to the wait for offers";
            case DASH_OVER:
                return "the dash ended or paused";
            case UNCLEAR:
                return "Dasher's next screen was neither a delivery nor the wait for offers (its words are in the "
                        + "screens log)";
            case TIMEOUT:
                return "Dasher showed neither a delivery nor the wait for offers within " + AFTER_MS / 1000 + " s";
            case ANOTHER_OFFER:
                return "another offer came first";
            default:
                return AppName.NAME + " requested a decline of it";
        }
    }

    /** A screen's words, numbers aside (a ticking clock is the same screen). */
    private static String words(List<String> labels) {
        return labels.toString().replaceAll("[0-9]", "#");
    }

    private static long seconds(long ms) {
        return Math.round(Math.max(0, ms) / 1000.0);
    }

    /** "0:28". */
    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s / 60 + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    private void note(DecisionLog.StepKind kind, OfferSnapshot line, String detail) {
        if (line == null) return;
        notes.add(new Note(kind, line, detail, null, null, null));
    }

    /** Dasher left the screen for a moment (the shade, recent apps, another app): what was seen is kept. */
    void forgetVisible() {
        unconfirmedAutomatic("Dasher left the readable foreground before delivery confirmation");
        visible = null;
    }

    void reset() {
        visible = null;
        last = null;
        screenSinceOffer = false;
        clearPending();
        watch = null;
        lastClear = null;
        notes.clear();
        forgetEndQuestion();
    }

    private void clearPending() {
        pending = null;
        clickedAt = 0;
    }
}
