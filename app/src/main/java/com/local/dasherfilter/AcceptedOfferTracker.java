package com.local.dasherfilter;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A passing offer alone never advances the baseline. An accept counts only when the tapped offer was visible and a
 * delivery-progress label that was NOT already on screen appears afterwards. Progress labels seen on the route screen
 * before the offer (even when the overlay hides them) or on the offer scan itself never confirm an accept; seeing only
 * those again is AMBIGUOUS. "No longer available" and similar text is REJECTED.
 * Route policy for callers: any accept tap may change the route (including taps this tracker cannot record, and accepts
 * whose confirmation is never seen because the dasher switched apps), so the stored route is cleared at every accept tap
 * and only an ACCEPTED observation stores the new one. The rising baseline changes only on ACCEPTED.
 */
final class AcceptedOfferTracker {
    /** Backstop only: a quiet offer screen is not rescanned, so the tap may come long after the last scan. Leaving the offer screen is the real guard. */
    static final long VISIBLE_WINDOW_MS = 90_000L;
    static final long CONFIRM_WINDOW_MS = 15_000L;

    /** What an accept tap did. */
    enum AcceptClick {
        /** Not tied to a readable offer (a standalone offer without readable pay, or no offer seen). */
        NONE,
        /** Pending accept with a known route after acceptance. */
        RECORDED,
        /** Pending add-on accept whose combined route is unknown; an ACCEPTED result will carry routeAfter == null. */
        ROUTE_UNKNOWN,
        /** An add-on accept tap with no add-on still visible: it cannot be recorded, and only the route clear at the tap remains. */
        ADDON_UNTRACKED
    }

    /** Outcome of a non-offer screen for a pending accept. */
    enum Outcome {
        /** Nothing to report (no pending accept, or still waiting for evidence). */
        NONE,
        /** A new delivery-progress label appeared: record baseline (standalone) and route. */
        ACCEPTED,
        /** Only progress labels that were already on screen came back, or the window expired: never touch the baseline; clear the route. */
        AMBIGUOUS,
        /** DoorDash said the offer is no longer available: leave baseline and route unchanged. */
        REJECTED
    }

    static final class Observation {
        final Outcome outcome;
        /** Non-null only for ACCEPTED. */
        final Acceptance acceptance;
        /** The pending accept was for an add-on. */
        final boolean addOn;
        private Observation(Outcome outcome, Acceptance acceptance, boolean addOn) { this.outcome = outcome; this.acceptance = acceptance; this.addOn = addOn; }
        /** The stored route must be cleared: acceptance is unknown, or accepted without a known route. */
        boolean clearsRoute() { return outcome == Outcome.AMBIGUOUS || (outcome == Outcome.ACCEPTED && acceptance.routeAfter == null); }
        private static final Observation NOTHING = new Observation(Outcome.NONE, null, false);
    }

    static final class Acceptance {
        final OfferSnapshot acceptedOffer;
        /** Route context after acceptance; null means the route is now unknown and stored context must be cleared. */
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
        /** Only a standalone accept with a known payout may become the rising baseline. */
        boolean updatesBaseline() { return !addOn && acceptedOffer.payCents != null && acceptedOffer.payCents > 0 && routeAfter != null; }
    }

    private OfferSnapshot visibleOffer;
    private OfferSnapshot visibleRouteAfter;
    private boolean visibleAddOn;
    /** Identity of the last readable offer; kept while it is briefly not visible so seeing it again keeps a pending accept. */
    private String visibleKey = "";
    private long visibleAt;
    private Set<String> visibleProgress = Collections.emptySet();
    /** The last readable offer was an add-on (an accept tap after it disappears is an untracked add-on accept). */
    private boolean lastOfferAddOn;
    /** Progress labels on the route screen seen while nothing was pending (kept through frames without progress labels). */
    private Set<String> progressBefore = Collections.emptySet();
    private OfferSnapshot pendingOffer;
    private OfferSnapshot pendingRouteAfter;
    private boolean pendingAddOn;
    private Set<String> pendingProgress = Collections.emptySet();
    private long clickedAt;

    void observeOffer(OfferSnapshot offer, long now) {
        observeOffer(offer, offer, false, now);
    }

    void observeOffer(OfferSnapshot offer, OfferSnapshot routeAfter, boolean addOn, long now) {
        observeOffer(offer, routeAfter, addOn, Collections.emptyList(), now);
    }

    /** labels: everything readable with the offer, so delivery labels already on screen (mid-route) cannot later confirm an accept. */
    void observeOffer(OfferSnapshot offer, OfferSnapshot routeAfter, boolean addOn, List<String> labels, long now) {
        String key = offer.fingerprint() + "->" +
                (routeAfter == null ? "null" : routeAfter.fingerprint()) + ":" + addOn;
        if (!key.equals(visibleKey)) clearPending();
        visibleKey = key;
        visibleOffer = offer;
        visibleRouteAfter = routeAfter;
        visibleAddOn = addOn;
        lastOfferAddOn = addOn;
        visibleAt = now;
        visibleProgress = progressLabels(labels);
    }

    AcceptClick acceptClicked(long now) {
        if (visibleOffer == null || now < visibleAt || now - visibleAt > VISIBLE_WINDOW_MS) return lastOfferAddOn ? AcceptClick.ADDON_UNTRACKED : AcceptClick.NONE;
        boolean routeKnown;
        if (visibleAddOn) routeKnown = visibleRouteAfter != null && visibleRouteAfter.payCents != null && visibleRouteAfter.payCents > 0;
        else if (visibleOffer.payCents == null || visibleOffer.payCents <= 0) return AcceptClick.NONE;   // unchanged 0.4.x standalone behavior
        else routeKnown = true;
        pendingOffer = visibleOffer;
        pendingRouteAfter = routeKnown ? visibleRouteAfter : null;
        pendingAddOn = visibleAddOn;
        Set<String> before = new HashSet<>(progressBefore);
        before.addAll(visibleProgress);
        pendingProgress = before;
        clickedAt = now;
        return routeKnown ? AcceptClick.RECORDED : AcceptClick.ROUTE_UNKNOWN;
    }

    /**
     * The offer is not readable (incomplete or truncated scan): it can no longer be credited to a later tap. A pending
     * accept and the offer identity are kept, so the same offer seen again after a transient frame keeps the pending accept.
     */
    void offerNotVisible() {
        visibleOffer = null;
        visibleRouteAfter = null;
        visibleAddOn = false;
        visibleProgress = Collections.emptySet();
    }

    /** 0.4.x shape: the Acceptance for ACCEPTED, otherwise null. Adapters should use observeScreen for AMBIGUOUS/REJECTED. */
    Acceptance observeOtherScreen(List<String> labels, long now) {
        Observation seen = observeScreen(labels, now);
        return seen.outcome == Outcome.ACCEPTED ? seen.acceptance : null;
    }

    /** Any Dasher screen that is not a complete readable offer. */
    Observation observeScreen(List<String> labels, long now) {
        offerNotVisible();
        List<String> safe = labels == null ? Collections.emptyList() : labels;
        Set<String> progress = progressLabels(safe);
        if (pendingOffer == null) {
            remember(safe, progress);
            return Observation.NOTHING;
        }
        boolean addOn = pendingAddOn;
        if (now < clickedAt || now - clickedAt > CONFIRM_WINDOW_MS) return resolve(Outcome.AMBIGUOUS, null, addOn, safe, progress);
        for (String label : safe) {
            String lower = OfferEvidence.normalize(label).toLowerCase(Locale.US);
            if (lower.contains("no longer available") || lower.contains("expired") || lower.contains("offer taken") ||
                    lower.contains("order taken") || lower.contains("was taken") || lower.contains("taken by another") ||
                    lower.contains("already been taken") || lower.contains("already accepted") || lower.contains("unable to accept") ||
                    lower.contains("already assigned") || lower.contains("couldn't accept") || lower.contains("could not accept"))
                return resolve(Outcome.REJECTED, null, addOn, safe, progress);
        }
        if (progress.isEmpty()) return Observation.NOTHING;   // loading or transition: keep waiting
        for (String label : progress) {
            if (!pendingProgress.contains(label)) {
                Acceptance accepted = new Acceptance(pendingOffer, pendingRouteAfter, pendingAddOn);
                return resolve(Outcome.ACCEPTED, accepted, addOn, safe, progress);
            }
        }
        // Only labels that were already there: the route screen came back, but whether the accept went through is unknown.
        // Resolving now (not waiting) keeps the dasher's own next route step from confirming a failed accept.
        return resolve(Outcome.AMBIGUOUS, null, addOn, safe, progress);
    }

    void reset() {
        visibleOffer = null;
        visibleRouteAfter = null;
        visibleAddOn = false;
        visibleKey = "";
        visibleProgress = Collections.emptySet();
        lastOfferAddOn = false;
        progressBefore = Collections.emptySet();
        clearPending();
    }

    private Observation resolve(Outcome outcome, Acceptance acceptance, boolean addOn, List<String> labels, Set<String> progress) {
        clearPending();
        remember(labels, progress);
        return new Observation(outcome, acceptance, addOn);
    }

    /** Route-screen progress labels become the "already there" set; an idle screen clears it; other frames keep it. */
    private void remember(List<String> labels, Set<String> progress) {
        if (OfferEvidence.isIdle(labels)) { progressBefore = Collections.emptySet(); lastOfferAddOn = false; }
        else if (!progress.isEmpty()) progressBefore = progress;
    }

    private static Set<String> progressLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String label : labels) { String lower = OfferEvidence.normalize(label).toLowerCase(Locale.US); if (progress(lower)) out.add(lower); }
        return out;
    }
    private static boolean progress(String lower) {
        return lower.equals("arrived at store") || lower.equals("arrived at pickup") ||
                lower.equals("arrived at customer") || lower.equals("arrived at drop-off") ||
                lower.equals("confirm pickup") || lower.equals("confirm pick up") ||
                lower.equals("complete pickup") || lower.equals("complete delivery") ||
                lower.equals("slide to confirm pickup");
    }

    private void clearPending() {
        pendingOffer = null;
        pendingRouteAfter = null;
        pendingAddOn = false;
        pendingProgress = Collections.emptySet();
        clickedAt = 0;
    }
}
