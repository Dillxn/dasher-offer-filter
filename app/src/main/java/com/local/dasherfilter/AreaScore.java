package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The area score: an offer's shape on the constellation against the minimums' shape, as one number (1 is exactly the
 * minimums' area). Score by area, a mode the user turns on, decides by it; it is shown for every offer it can be
 * worked out for, in either mode. Example-independent: it depends on the rules and the offer alone, never on which
 * offer the chart happens to use as its example.
 *
 * <p>Each active spoke {@code i} (pay, per mile, per minute, per stop; see {@link #active}) has a floor {@code d_i}:
 * the cents it asks of this offer. Its ratio is {@code r_i = pay ÷ d_i}, which is the offer's own value on that spoke
 * over the spoke's minimum ({@code (pay ÷ miles) ÷ m_mile}, and so on), {@code m_i = d_i ÷ this offer's amount}. The
 * floor is the set minimum, exactly as set ({@code rate × amount}, unrounded), or, while the adaptive minimum is on,
 * the higher of that and what the adaptive minimum asks of this same offer on that spoke, in the cents the strict rules
 * ask for it (one cent above the highest accepted pay or the pay declined by hand; a best accepted rate over this
 * offer's amount rounded up to the cent; a rate declined by hand beaten by the next cent). So a learned floor's
 * per-unit rate is exactly what the strict rules make of it for this offer, and with one active spoke the score
 * reaches 100% at exactly the pay the strict rules ask, to the cent.
 *
 * <p>The polygon joins the active spokes' points in the order they are drawn ({@link #ANGLES}: pay top-left, per mile
 * top-right, per minute bottom-right, per stop bottom-left). Its area is the sum of the triangles each pair of
 * neighboring points makes with the middle, {@code ½ r_i r_j sin(gap)}. The spokes stand 120° and 60° apart, whose
 * sines are equal, so with all four active the area is in proportion to {@code Σ r_i·r_next} over the four neighboring
 * pairs (pay–mile, mile–minute, minute–stop, stop–pay). With three, two of the active spokes stand opposite each other
 * (180°, a sine of 0): that pair's triangle has no area and does not count. With two, the one pair counts; with one,
 * the score is its ratio. The score is {@code sqrt(area of the offer ÷ area of the minimums)}, the minimums' points all
 * being at 1, so it rises in proportion to pay: twice the pay, twice the score. Whether an offer reaches 100% is worked
 * out exactly (in decimals, never in floating point), so an offer exactly at its minimums scores exactly 100%.
 */
final class AreaScore {
    static final int PAY = 0;
    static final int MILE = 1;
    static final int MINUTE = 2;
    static final int STOP = 3;
    static final int AXES = 4;
    /** The spokes stand this many degrees above and below level. */
    static final float SPREAD = 30;
    /**
     * Each spoke's drawn angle in degrees, clockwise from the right (y grows downwards on the screen): pay top-left,
     * per mile top-right, per minute bottom-right, per stop bottom-left. They ascend, so the spokes in index order are
     * their clockwise order on the screen and each one's neighbor is the next (the last's, the first). The
     * constellation draws its spokes at exactly these angles.
     */
    static final float[] ANGLES = {SPREAD - 180, -SPREAD, SPREAD, 180 - SPREAD};
    /** A pay requirement past this many cents ($10 billion) is out of reach, answered as {@code Long.MAX_VALUE}. */
    private static final double OUT_OF_REACH = 1e12;

    /** What each spoke asks of one offer. */
    static final class Floors {
        /** Per spoke, the cents its floor asks of the offer; null where the spoke is off or the amount was not read. */
        final BigDecimal[] cents = new BigDecimal[AXES];
        /** Per spoke: some minimum is on there (a set one, or an adaptive one that applies). */
        final boolean[] active = new boolean[AXES];

        /** Some spoke has a minimum. */
        boolean anyActive() {
            for (boolean on : active) if (on) return true;
            return false;
        }

        /** Every active spoke's amount was read, so the score can be worked out from pay. */
        boolean readable() {
            for (int i = 0; i < AXES; i++) if (active[i] && cents[i] == null) return false;
            return true;
        }

        /** The active spokes, in drawn order. */
        int[] activeAxes() {
            int count = 0;
            for (boolean on : active) if (on) count++;
            int[] axes = new int[count];
            int at = 0;
            for (int i = 0; i < AXES; i++) if (active[i]) axes[at++] = i;
            return axes;
        }
    }

    /** Which spokes have a minimum under {@code rules}: a set one, or (adaptive minimum on) a learned one. */
    static boolean[] active(FilterSettings rules) {
        boolean adaptive = rules.risingOffers;
        AcceptedBest best = rules.best;
        AcceptedBest declined = rules.declined.rates;
        return new boolean[] {
                rules.flatCents > 0 || (adaptive && (rules.lastAcceptedCents > 0 || rules.declined.payCents > 0)),
                rules.perMileCents > 0 || (adaptive && (best.hasPerMile() || declined.hasPerMile())),
                rules.perMinuteCents > 0 || (adaptive && (best.hasPerMinute() || declined.hasPerMinute())),
                rules.perStopCents > 0 || (adaptive && (best.hasPerStop() || declined.hasPerStop()))};
    }

    /**
     * The floors {@code rules} put on {@code offer}'s spokes. An amount of 0 (no distance or time) and fewer stops than
     * an order has ("1 stop", a misread, as the strict rules treat it) are not read: a spoke that needs one is unread.
     */
    static Floors floors(FilterSettings rules, OfferSnapshot offer) {
        Floors floors = new Floors();
        boolean[] active = active(rules);
        System.arraycopy(active, 0, floors.active, 0, AXES);
        boolean adaptive = rules.risingOffers;
        AcceptedBest best = rules.best;
        DeclinedFloor declined = rules.declined;
        if (active[PAY]) {
            long pay = Math.max(0, rules.flatCents);
            if (adaptive && rules.lastAcceptedCents > 0) pay = Math.max(pay, rules.lastAcceptedCents + 1L);
            if (adaptive && declined.payCents > 0) pay = Math.max(pay, declined.beatPay());
            floors.cents[PAY] = BigDecimal.valueOf(pay);
        }
        if (active[MILE] && offer.miles != null && offer.miles > 0) {
            double miles = offer.miles;
            BigDecimal ask = BigDecimal.valueOf(rules.perMileCents).multiply(BigDecimal.valueOf(miles));
            if (adaptive && best.hasPerMile()) ask = ask.max(BigDecimal.valueOf(best.forMiles(miles)));
            if (adaptive && declined.rates.hasPerMile()) ask = ask.max(BigDecimal.valueOf(declined.beatMiles(miles)));
            floors.cents[MILE] = ask;
        }
        if (active[MINUTE] && offer.minutes != null && offer.minutes > 0) {
            int minutes = offer.minutes;
            BigDecimal ask = BigDecimal.valueOf((long) rules.perMinuteCents * minutes);
            if (adaptive && best.hasPerMinute()) ask = ask.max(BigDecimal.valueOf(best.forMinutes(minutes)));
            if (adaptive && declined.rates.hasPerMinute()) {
                ask = ask.max(BigDecimal.valueOf(declined.beatMinutes(minutes)));
            }
            floors.cents[MINUTE] = ask;
        }
        if (active[STOP] && offer.stops != null && offer.stops >= AcceptedBest.PLAUSIBLE_STOPS) {
            int stops = offer.stops;
            BigDecimal ask = BigDecimal.valueOf((long) rules.perStopCents * stops);
            if (adaptive && best.hasPerStop()) ask = ask.max(BigDecimal.valueOf(best.forStops(stops)));
            if (adaptive && declined.rates.hasPerStop()) ask = ask.max(BigDecimal.valueOf(declined.beatStops(stops)));
            floors.cents[STOP] = ask;
        }
        return floors;
    }

    /**
     * The pairs of active spokes whose triangles make up the polygon's area, in drawn order: neighbors around the
     * circle, less any pair standing opposite each other (no area); with two active spokes, the one pair.
     */
    static List<int[]> pairs(int[] axes) {
        List<int[]> pairs = new ArrayList<>();
        if (axes.length == 2) {
            pairs.add(new int[] {axes[0], axes[1]});
            return pairs;
        }
        if (axes.length < 2) return pairs;
        for (int k = 0; k < axes.length; k++) {
            int from = axes[k];
            int to = axes[(k + 1) % axes.length];
            float gap = ((ANGLES[to] - ANGLES[from]) % 360 + 360) % 360;
            if (gap != 180) pairs.add(new int[] {from, to});
        }
        return pairs;
    }

    /** {@code pay ÷ d_i} on each spoke (NaN where off or unread). */
    static double[] ratios(Floors floors, long payCents) {
        double[] ratios = new double[AXES];
        for (int i = 0; i < AXES; i++) {
            ratios[i] = floors.active[i] && floors.cents[i] != null
                    ? payCents / floors.cents[i].doubleValue() : Double.NaN;
        }
        return ratios;
    }

    /** The score at {@code payCents}, 1 being the minimums' area; NaN when no spoke is active or one is unread. */
    static double score(Floors floors, long payCents) {
        if (!floors.anyActive() || !floors.readable() || payCents < 0) return Double.NaN;
        int[] axes = floors.activeAxes();
        double[] r = ratios(floors, payCents);
        if (axes.length == 1) return r[axes[0]];
        List<int[]> pairs = pairs(axes);
        double area = 0;
        for (int[] pair : pairs) area += r[pair[0]] * r[pair[1]];
        return Math.sqrt(area / pairs.size());
    }

    /**
     * Whether {@code payCents} scores at least 100%, exactly: with one spoke {@code pay ≥ d}; else
     * {@code pay² × Σ 1 ÷ (d_i d_j) ≥ k} over the k pairs, multiplied through by every pair's {@code d_i d_j}.
     */
    static boolean reaches(Floors floors, long payCents) {
        if (!floors.anyActive() || !floors.readable() || payCents < 0) return false;
        int[] axes = floors.activeAxes();
        BigDecimal pay = BigDecimal.valueOf(payCents);
        if (axes.length == 1) return pay.compareTo(floors.cents[axes[0]]) >= 0;
        List<int[]> pairs = pairs(axes);
        BigDecimal[] products = new BigDecimal[pairs.size()];
        BigDecimal all = BigDecimal.ONE;
        for (int p = 0; p < products.length; p++) {
            products[p] = floors.cents[pairs.get(p)[0]].multiply(floors.cents[pairs.get(p)[1]]);
            all = all.multiply(products[p]);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int p = 0; p < products.length; p++) {
            BigDecimal others = BigDecimal.ONE;
            for (int q = 0; q < products.length; q++) if (q != p) others = others.multiply(products[q]);
            sum = sum.add(others);
        }
        return pay.multiply(pay).multiply(sum).compareTo(all.multiply(BigDecimal.valueOf(products.length))) >= 0;
    }

    /** The least whole-cent pay that scores 100%; {@code Long.MAX_VALUE} when out of reach, 0 when nothing is asked. */
    static long requiredPay(Floors floors) {
        if (!floors.anyActive() || !floors.readable()) return 0;
        double atOneCent = score(floors, 1);
        if (!(atOneCent > 0)) return Long.MAX_VALUE;
        double estimate = Math.ceil(1 / atOneCent);
        if (estimate > OUT_OF_REACH) return Long.MAX_VALUE;
        long pay = (long) estimate;
        // The estimate is within a cent or two; settle it exactly.
        for (int i = 0; i < 8 && pay > 0 && reaches(floors, pay - 1); i++) pay--;
        for (int i = 0; i < 8 && !reaches(floors, pay); i++) pay++;
        return pay;
    }

    /**
     * The score at {@code payCents} as a whole percent, rounded to the nearest, but never 100 or more when it falls
     * short of 100% nor under 100 when it reaches it; -1 when it cannot be worked out.
     */
    static int percent(Floors floors, long payCents) {
        double score = score(floors, payCents);
        if (Double.isNaN(score)) return -1;
        long rounded = Math.round(Math.min(score * 100, Integer.MAX_VALUE));
        int shown = (int) Math.min(Integer.MAX_VALUE, rounded);
        return reaches(floors, payCents) ? Math.max(100, shown) : Math.min(99, shown);
    }

    /** The score of {@code offer} under {@code rules} as a whole percent ({@link #percent}); -1 when not computable. */
    static int percent(FilterSettings rules, OfferSnapshot offer) {
        if (offer.payCents == null) return -1;
        return percent(floors(rules, offer), offer.payCents);
    }

    /** "Score 121%". */
    static String label(int percent) {
        return "Score " + percent + "%";
    }

    private AreaScore() {}
}
