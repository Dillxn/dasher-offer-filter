package com.local.dasherfilter;

/**
 * An opt-in Accept request needs two complete reads of one continuing offer, a quiet touch watch and a KEEP under
 * {@link #acceptRules}: 100% of the user's own minimums, or Autopilot's bar when that is higher, so an offer passing
 * only below the minimums is never accepted automatically. This class never taps or infers an acceptance. A refused
 * request also consumes the one-request budget.
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
    private int contentRechecks;
    private boolean needsSameRead;

    static boolean eligible(OfferSnapshot offer, FilterSettings settings, boolean enabled, boolean addOn,
                            boolean routeStored, int countdown) {
        return enabled && settings.enabled && settings.hasMonetaryRule() && !addOn && !routeStored
                && offer.payCents != null && offer.payCents > 0 && offer.payAtMostCents == null
                && offer.miles != null && offer.miles > 0 && offer.minutes != null && offer.minutes > 0
                && offer.stops != null && offer.stops >= 2
                && (!offer.itemCountApplicable || offer.items != null)
                && countdown > MIN_REMAINING_MS / 1000
                && OfferRule.evaluate(offer, acceptRules(settings)).result == OfferRule.Result.KEEP;
    }

    /**
     * The rules an automatic Accept is judged by: the same minimums at {@code max(100, bar)}. Autopilot may lower the
     * bar to protect the acceptance rate, but never what an automatic Accept asks.
     */
    static FilterSettings acceptRules(FilterSettings settings) {
        return settings.withMinimumScalePercent(Math.max(FilterSettings.BAR_AT_MINIMUMS, settings.minimumScalePercent));
    }

    State observe(OfferSnapshot offer, String offerKey, FilterSettings settings, int countdown,
                  long notificationGeneration, long now, boolean freshInstance) {
        // A stale final node can only be replaced by the same complete offer. This never resets its original
        // deadline or quiet interval, nor authorizes a changed offer using the old candidate's verification.
        if (needsSameRead) {
            needsSameRead = false;
            if (freshInstance || !current(offer, offerKey, settings, notificationGeneration, now)) {
                block(now);
                return State.BLOCKED;
            }
        }
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

    /** Before persistence/dispatch only: discard a stale node and allow at most two complete rereads. */
    boolean rereadAfterContent(long now) {
        if (candidate == null || now >= deadline || contentRechecks >= 2) return false;
        contentRechecks++;
        needsSameRead = true;
        return true;
    }

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
    void clearCandidate() {
        candidate = null; key = ""; rules = "";
        contentRechecks = 0; needsSameRead = false;
    }
    void clear() { clearCandidate(); blocked = null; }

    /** Exact rule provenance, the bar included ("enabled:flat:mile:minute:maxStops:bar"); no rounded label decides. */
    static String rulesKey(FilterSettings s) {
        return s.enabled + ":" + s.flatCents + ":" + s.perMileCents + ":" + s.perMinuteCents + ":" + s.maxStops
                + ":" + s.minimumScalePercent;
    }
}
