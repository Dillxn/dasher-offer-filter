package com.local.dasherfilter;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Pay per active hour (0.6.69; the owner, 25 November 2026: "we should track pay per active hour average over a rolling
 * window and graph it's change over time over our tree graph"): what the offers you accepted paid for the time they
 * took, as one rate, their total pay over their total active time, in cents an hour. Active time is Dasher's own
 * estimate of each accepted offer's time (its trip minutes, the time the per-hour minimum is asked of): the app sees
 * when an offer is accepted but keeps no record of when its delivery ends, so it cannot time one itself.
 *
 * <p>Rolling: as of each offer, the last {@value #WINDOW} accepts at or before it, so the rate moves only when an accept
 * comes in. An accept counts with its pay and minutes read: a standalone offer you or auto-accept took (never a misread
 * one), and an add-on for what it added (its "+$" and its added minutes; one judged against a failing whole route, for
 * the difference between its route before and after it). Derived from the decision history whenever it changes, never
 * stored: Clear history clears it with the history, and the history's 200 lines bound how far back it reaches.
 */
final class ActiveHourPay {
    /** The accepts each point averages over at most. */
    static final int WINDOW = 10;

    /** The rate as of one offer: whole cents an hour (rounded down), over {@link #accepts} accepts; none: -1 and 0. */
    static final class Point {
        static final Point NONE = new Point(-1, 0, false);
        final long centsPerHour;
        final int accepts;
        /** This offer is itself one of the accepts, so the rate moved here. */
        final boolean moved;

        Point(long centsPerHour, int accepts, boolean moved) {
            this.centsPerHour = centsPerHour;
            this.accepts = accepts;
            this.moved = moved;
        }

        boolean known() {
            return centsPerHour >= 0 && accepts > 0;
        }
    }

    /**
     * The rate as of each of the newest {@code shown} offers of {@code newestFirst} (the retained history, newest
     * first), oldest first as the chart draws them; the window counts the older offers' accepts too.
     */
    static Point[] rolling(List<DecisionLog.Entry> newestFirst, int shown) {
        int size = newestFirst == null ? 0 : newestFirst.size();
        int count = Math.max(0, Math.min(shown, size));
        Point[] points = new Point[count];
        ArrayDeque<long[]> window = new ArrayDeque<>();
        long pay = 0;
        long minutes = 0;
        for (int i = size - 1; i >= 0; i--) {
            long[] work = work(newestFirst.get(i));
            if (work != null) {
                window.addLast(work);
                pay += work[0];
                minutes += work[1];
                if (window.size() > WINDOW) {
                    long[] gone = window.removeFirst();
                    pay -= gone[0];
                    minutes -= gone[1];
                }
            }
            if (i < count) {
                points[count - 1 - i] = minutes > 0
                        ? new Point(pay * 60 / minutes, window.size(), work != null) : Point.NONE;
            }
        }
        return points;
    }

    /**
     * What an accepted line adds to the rate, {pay in cents, Dasher's minutes}; null for a line not accepted, or one
     * whose pay or minutes were not read.
     */
    static long[] work(DecisionLog.Entry entry) {
        if (entry == null || !DecisionLog.accepted(entry)) return null;
        OfferSnapshot facts = entry.addOn ? added(entry) : entry.facts;
        if (facts == null || facts.payCents == null || facts.minutes == null) return null;
        if (facts.payCents <= 0 || facts.minutes <= 0) return null;
        if (!entry.addOn && OfferSanity.looksMisread(facts)) return null;
        return new long[] {facts.payCents, facts.minutes};
    }

    /**
     * An add-on's own pay and minutes: the line's figures, which are the add-on's, except where its whole route failed
     * and the line keeps that route's instead ({@link OfferRule#COMBINED_FAILS}): then its route after it less before.
     */
    private static OfferSnapshot added(DecisionLog.Entry entry) {
        if (!entry.reason.startsWith(OfferRule.COMBINED_FAILS)) return entry.facts;
        OfferSnapshot before = entry.routeBefore;
        OfferSnapshot after = entry.routeAfter;
        if (before == null || after == null || before.payCents == null || after.payCents == null
                || before.minutes == null || after.minutes == null) {
            return null;
        }
        return new OfferSnapshot(after.payCents - before.payCents, null, after.minutes - before.minutes, null);
    }

    /** "$31/active hr": whole dollars, rounded half up, for the chart's line. */
    static String label(long centsPerHour) {
        return "$" + (centsPerHour + 50) / 100 + "/active hr";
    }

    private ActiveHourPay() {}
}
