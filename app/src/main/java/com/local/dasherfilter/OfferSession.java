package com.local.dasherfilter;

import java.util.Objects;

/**
 * One history record per visible offer. A session starts when both offer controls are readable; countdown ticks and
 * progressive rendering keep updating it; a snapshot with a conflicting known fact, offer-gone, or idle ends it.
 */
final class OfferSession {
    /** Result of one observation. upgrade: this snapshot is at least as complete as any before, so its verdict should be recorded. */
    static final class Step {
        final long id; final boolean started; final boolean upgrade;
        Step(long id, boolean started, boolean upgrade) { this.id = id; this.started = started; this.upgrade = upgrade; }
    }
    private long id = -1;
    private boolean addOn;
    private OfferSnapshot seen;          // union of every fact seen in this session
    private int bestKnown = -1;

    /** newId is used only when a new session starts (e.g. OfferHistory.nextId). */
    Step observe(OfferSnapshot offer, boolean isAddOn, long newId) {
        if (id >= 0 && addOn == isAddOn && compatible(seen, offer)) {
            int known = known(offer);
            boolean upgrade = known >= bestKnown;
            if (upgrade) bestKnown = known;
            seen = union(seen, offer);
            return new Step(id, false, upgrade);
        }
        id = newId; addOn = isAddOn; seen = offer; bestKnown = known(offer);
        return new Step(id, true, true);
    }
    /** Offer gone, idle, or a different screen: the next readable offer starts a new record. */
    void end() { id = -1; seen = null; bestKnown = -1; }
    boolean active() { return id >= 0; }
    long id() { return id; }

    /**
     * Same offer unless a fact known in both snapshots differs. A fact disappearing while the card animates away does not
     * start a new record (lenient superset of "every non-null older field equals the newer one").
     */
    static boolean compatible(OfferSnapshot older, OfferSnapshot newer) {
        if (older == null || newer == null) return false;
        return same(older.payCents, newer.payCents) && same(older.miles, newer.miles) && same(older.minutes, newer.minutes) && same(older.stops, newer.stops);
    }
    private static OfferSnapshot union(OfferSnapshot a, OfferSnapshot b) {
        return new OfferSnapshot(a.payCents != null ? a.payCents : b.payCents, a.miles != null ? a.miles : b.miles,
                a.minutes != null ? a.minutes : b.minutes, a.stops != null ? a.stops : b.stops);
    }
    private static boolean same(Object a, Object b) { return a == null || b == null || Objects.equals(a, b); }
    static int known(OfferSnapshot o) {
        return o == null ? 0 : (o.payCents != null ? 1 : 0) + (o.miles != null ? 1 : 0) + (o.minutes != null ? 1 : 0) + (o.stops != null ? 1 : 0);
    }
}
