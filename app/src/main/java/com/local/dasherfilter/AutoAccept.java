package com.local.dasherfilter;

/**
 * An opt-in Accept request needs two complete reads of one continuing offer, a quiet touch watch and current KEEP
 * rules. This class never taps or infers an acceptance. A refused request also consumes the one-request budget.
 */
final class AutoAccept {
    static final long QUIET_MS = 700;
    static final long MIN_REMAINING_MS = 3_000;
    static final long SUPPRESS_MS = 120_000;
    enum State { SKIP, WAIT, READY, BLOCKED }

    private OfferSnapshot candidate;
    private String key = "", rules = "";
    private long began, deadline, generation;
    private OfferSnapshot blocked;
    private long blockedUntil;

    static boolean eligible(OfferSnapshot offer, FilterSettings settings, boolean enabled, boolean addOn,
                            boolean routeStored, int countdown) {
        return enabled && settings.enabled && settings.hasAnyRule() && !addOn && !routeStored
                && (settings.maxStops > 0 || AreaScore.floors(settings, offer).anyActive())
                && offer.payCents != null && offer.payCents > 0 && offer.payAtMostCents == null
                && offer.miles != null && offer.miles > 0 && offer.minutes != null && offer.minutes > 0
                && offer.stops != null && offer.stops >= 2
                && (!offer.itemCountApplicable || offer.items != null)
                && countdown > MIN_REMAINING_MS / 1000
                && OfferRule.evaluate(offer, settings).result == OfferRule.Result.KEEP;
    }

    State observe(OfferSnapshot offer, String offerKey, FilterSettings settings, int countdown,
                  long notificationGeneration, long now, boolean freshInstance) {
        newOffer(offer, now, freshInstance);
        if (blocks(offer, now)) { clearCandidate(); return State.BLOCKED; }
        String currentRules = rulesKey(settings);
        if (candidate != null && !rules.equals(currentRules)) {
            block(now);
            return State.BLOCKED;
        }
        if (candidate == null || freshInstance || !key.equals(offerKey)
                || generation != notificationGeneration) {
            candidate = offer;
            key = offerKey;
            rules = currentRules;
            began = now;
            deadline = now + countdown * 1000L - MIN_REMAINING_MS;
            generation = notificationGeneration;
            return State.WAIT;
        }
        // A changed/fresh countdown cannot inherit this offer's quiet interval or original deadline.
        long expected = deadline + MIN_REMAINING_MS - now;
        if (countdown < 0 || countdown * 1000L > expected + 3_000 || now >= deadline) {
            block(now);
            return State.BLOCKED;
        }
        return now - began >= QUIET_MS ? State.READY : State.WAIT;
    }

    boolean blocks(OfferSnapshot offer, long now) {
        return blocked != null && now < blockedUntil && !offer.contradicts(blocked);
    }
    void newOffer(OfferSnapshot offer, long now, boolean freshInstance) {
        if (blocked != null && (now >= blockedUntil || offer.contradicts(blocked) || freshInstance)) blocked = null;
    }

    OfferSnapshot candidate() { return candidate; }
    long due() { return began + QUIET_MS; }

    boolean current(OfferSnapshot offer, String offerKey, FilterSettings settings, long notificationGeneration,
                    long now) {
        return candidate != null && now >= began + QUIET_MS && now < deadline
                && key.equals(offerKey) && rules.equals(rulesKey(settings))
                && generation == notificationGeneration && candidate.fingerprint().equals(offer.fingerprint());
    }

    void block(long now) {
        if (candidate != null) { blocked = candidate; blockedUntil = now + SUPPRESS_MS; }
        clearCandidate();
    }
    void clearCandidate() { candidate = null; key = ""; rules = ""; }
    void clear() { clearCandidate(); blocked = null; }

    /** Exact rule provenance, including learned floors; no formatted/rounded label decides authority. */
    static String rulesKey(FilterSettings s) {
        return s.enabled + ":" + java.util.Arrays.toString(s.minimums()) + ":" + s.maxStops + ":"
                + s.risingOffers + ":" + s.lastAcceptedCents + ":" + bestKey(s.best) + ":"
                + s.declined.payCents + ":" + bestKey(s.declined.rates) + ":" + s.scoreByArea
                + ":" + s.minimumScalePercent;
    }
    private static String bestKey(AcceptedBest b) {
        return b.minutePay + ":" + b.minutes + ":" + b.milePay + ":" + b.miles + ":" + b.stopPay + ":" + b.stops;
    }
}
