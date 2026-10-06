package com.local.dasherfilter;

import java.util.Locale;

/** Immutable offer facts. A null field is unknown; negative or non-finite inputs are coerced to unknown. */
final class OfferSnapshot {
    static final OfferSnapshot UNKNOWN = new OfferSnapshot(null, null, null, null);

    final Integer payCents;
    final Double miles;
    final Integer minutes;
    final Integer stops;
    /** Explicit total item units, never stops, orders or a count of unique products. Null means unread. */
    final Integer items;
    /** This offer declares shopping/items. No declaration means this scoped rule is not applicable. */
    final boolean itemCountApplicable;
    /**
     * Retired (0.5.0): miles from the final stop to the nearest Dasher hotspot. No reader ever measured it, no rule
     * uses it, and nothing sets it any more, so it is always null in practice. The field stays only so older history
     * and wait records that name it still parse; it is excluded from offer identity and never shown.
     */
    @Deprecated
    final Double finalStopHotspotMiles;
    /**
     * Decline-only ceiling while a total beside one bare "+$" amount leaves pay unknown. A corroborated stack
     * allows that bonus on every observed order. Never observed pay; never learned, stored or used as identity.
     */
    final Integer payAtMostCents;

    OfferSnapshot(Integer payCents, Double miles, Integer minutes, Integer stops) {
        this(payCents, miles, minutes, stops, null);
    }

    OfferSnapshot(Integer payCents, Double miles, Integer minutes, Integer stops, Integer payAtMostCents) {
        this(payCents, miles, minutes, stops, payAtMostCents, null);
    }

    OfferSnapshot(Integer payCents, Double miles, Integer minutes, Integer stops, Integer payAtMostCents,
                  Double finalStopHotspotMiles) {
        this(payCents, miles, minutes, stops, payAtMostCents, finalStopHotspotMiles, null, false);
    }

    OfferSnapshot(Integer payCents, Double miles, Integer minutes, Integer stops, Integer payAtMostCents,
                  Double finalStopHotspotMiles, Integer items, boolean itemCountApplicable) {
        this.payCents = nonNegative(payCents);
        this.miles = miles != null && Double.isFinite(miles) && miles >= 0 ? miles : null;
        this.minutes = nonNegative(minutes);
        this.stops = nonNegative(stops);
        this.items = items != null && items > 0 ? items : null;
        this.itemCountApplicable = itemCountApplicable || this.items != null;
        this.finalStopHotspotMiles = finalStopHotspotMiles != null && Double.isFinite(finalStopHotspotMiles)
                && finalStopHotspotMiles >= 0 ? finalStopHotspotMiles : null;
        this.payAtMostCents = this.payCents == null ? nonNegative(payAtMostCents) : null;
    }

    /** These facts without the bound on unknown pay: what a path that must never use it sees. */
    OfferSnapshot withoutPayBound() {
        return payAtMostCents == null ? this
                : new OfferSnapshot(payCents, miles, minutes, stops, null, finalStopHotspotMiles,
                        items, itemCountApplicable);
    }

    /** Retired with the hotspot rule: nothing reads the distance; no other offer fact changes. */
    @Deprecated
    OfferSnapshot withFinalStopHotspotMiles(Double distance) {
        return new OfferSnapshot(payCents, miles, minutes, stops, payAtMostCents, distance,
                items, itemCountApplicable);
    }

    /** Attach explicit item evidence; a missing declared count stays unknown, never zero or one. */
    OfferSnapshot withItems(Integer count, boolean applicable) {
        return new OfferSnapshot(payCents, miles, minutes, stops, payAtMostCents, finalStopHotspotMiles,
                count, applicable);
    }

    private static Integer nonNegative(Integer value) {
        return value != null && value >= 0 ? value : null;
    }

    /** True when some fact is known in both snapshots and differs, so they cannot describe the same offer. */
    boolean contradicts(OfferSnapshot other) {
        return differ(payCents, other.payCents) || differ(miles, other.miles)
                || differ(minutes, other.minutes) || differ(stops, other.stops) || differ(items, other.items);
    }

    /** True when no fact contradicts and an existing route/pay fact agrees. Item count alone grants no authority. */
    boolean agreesWith(OfferSnapshot other) {
        return !contradicts(other) && (same(payCents, other.payCents) || same(miles, other.miles)
                || same(minutes, other.minutes) || same(stops, other.stops));
    }

    private static boolean same(Object a, Object b) {
        return a != null && a.equals(b);
    }

    private static boolean differ(Object a, Object b) {
        return a != null && b != null && !a.equals(b);
    }

    /** Identity of the facts only; used to tell one offer screen from the next. */
    String fingerprint() {
        return payCents + ":" + miles + ":" + minutes + ":" + stops
                + (itemCountApplicable ? ":items=" + items : "");
    }

    String summary() {
        return "Pay " + (payCents == null ? "?" : String.format(Locale.US, "$%.2f", payCents / 100.0))
                + (payAtMostCents == null ? "" : String.format(Locale.US, " (at most $%.2f)", payAtMostCents / 100.0))
                + ", miles " + (miles == null ? "?" : miles)
                + ", minutes " + (minutes == null ? "?" : minutes)
                + ", stops " + (stops == null ? "?" : stops)
                + (itemCountApplicable ? ", items " + (items == null ? "?" : items) : "");
    }
}
