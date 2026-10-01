package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * An offer's constellation against the minimums' constellation. The four monetary spokes have ratios pay / the
 * cents each floor asks of this offer, including the existing adaptive floors. Hotspot proximity is independent of
 * pay: (1 / final-stop distance in miles) / (minimum reciprocal miles), or 100 / (hundredths × miles). It is never a
 * fabricated pay floor and does not learn a fifth adaptive minimum.
 *
 * <p>Spokes retain their original angles, with hotspot proximity between pay and per mile. Neighboring active points
 * are joined in {@link #DRAW_ORDER}. An empty clockwise sector of at least 180 degrees closes through the center,
 * rather than contributing a negative triangle: the rendered polygon and this score share that rule. All remaining
 * sectors are 60 or 120 degrees, whose sines are equal. The squared score is therefore the sum of neighboring ratio
 * products divided by the number of contributing pairs. As before, one active spoke uses its ratio and two use their
 * geometric mean (including the old opposite-spoke case, whose drawn line has no literal area).
 *
 * <p>With hotspot proximity off, all original four-spoke scores and adaptive comparisons are unchanged. With it on,
 * the area is quadratic plus linear in pay, not proportional to pay squared. Threshold decisions and required pay
 * use exact decimal products, never a rounded display score. A known zero final-stop distance has infinite proximity;
 * a mixed pair with a zero-length monetary spoke contributes zero area even then. This makes zero pay's degenerate
 * polygon well-defined without inventing an epsilon distance or disguising a known zero as missing.
 */
final class AreaScore {
    static final int PAY = 0;
    static final int MILE = 1;
    static final int MINUTE = 2;
    static final int STOP = 3;
    static final int HOTSPOT = 4;
    static final int AXES = 5;
    /** The spokes stand this many degrees above and below level. */
    static final float SPREAD = 30;
    /** Angles by stable axis index, clockwise from the right; the original four never move. */
    static final float[] ANGLES = {SPREAD - 180, -SPREAD, SPREAD, 180 - SPREAD, -90};
    /** Stable axis indexes in clockwise drawn order. */
    static final int[] DRAW_ORDER = {PAY, HOTSPOT, MILE, MINUTE, STOP};
    /** A pay requirement past this many cents ($10 billion) is out of reach, answered as {@code Long.MAX_VALUE}. */
    private static final long OUT_OF_REACH = 1_000_000_000_000L;

    /** What each spoke asks of one offer. */
    static final class Floors {
        /** Monetary floors only; null where off/unread, and always null for the independent hotspot spoke. */
        final BigDecimal[] cents = new BigDecimal[AXES];
        /** Reciprocal of the hotspot ratio: minimum reciprocal miles × final-stop miles; null unread, 0 at hotspot. */
        BigDecimal hotspotDenominator;
        /** Per spoke: some minimum is on there (a set one, or an adaptive one that applies). */
        final boolean[] active = new boolean[AXES];

        /** Some spoke has a minimum. */
        boolean anyActive() {
            for (boolean on : active) if (on) return true;
            return false;
        }

        /** At least one active spoke depends on pay; a proximity-only rule does not need pay read. */
        boolean needsPay() {
            return active[PAY] || active[MILE] || active[MINUTE] || active[STOP];
        }

        /** Every active spoke's amount was read, so the score can be worked out from pay when pay is needed. */
        boolean readable() {
            for (int i = 0; i < AXES; i++) {
                if (active[i] && (i == HOTSPOT ? hotspotDenominator == null : cents[i] == null)) return false;
            }
            return true;
        }

        /** The active spokes, in drawn order. */
        int[] activeAxes() {
            int count = 0;
            for (boolean on : active) if (on) count++;
            int[] axes = new int[count];
            int at = 0;
            for (int axis : DRAW_ORDER) if (active[axis]) axes[at++] = axis;
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
                rules.perStopCents > 0 || (adaptive && (best.hasPerStop() || declined.hasPerStop())),
                rules.hotspotProximityHundredths > 0};
    }

    /**
     * The floors {@code rules} put on {@code offer}'s spokes. The monetary rate spokes require positive route miles
     * and time, and at least an order's plausible stop count. Final-stop-to-hotspot distance is independent: a known
     * zero is valid and makes proximity infinite; a missing distance keeps that active spoke unread.
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
        if (active[HOTSPOT] && offer.finalStopHotspotMiles != null
                && Double.isFinite(offer.finalStopHotspotMiles) && offer.finalStopHotspotMiles >= 0) {
            floors.hotspotDenominator = BigDecimal.valueOf(rules.hotspotProximityHundredths)
                    .multiply(BigDecimal.valueOf(offer.finalStopHotspotMiles)).movePointLeft(2);
        }
        return floors;
    }

    /**
     * Whether a clockwise edge closes via the middle instead of across an empty semicircle. Renderers use this
     * same predicate when constructing polygons with three or more active points. A 180-degree edge has no area.
     */
    static boolean closesThroughCenter(int from, int to) {
        float gap = ((ANGLES[to] - ANGLES[from]) % 360 + 360) % 360;
        return gap >= 180;
    }

    /** The contributing neighbors, in drawn order; one pair for the existing two-spoke fallback. */
    static List<int[]> pairs(int[] axes) {
        List<int[]> pairs = new ArrayList<>();
        int[] ordered = new int[axes.length];
        int count = 0;
        for (int axis : DRAW_ORDER) {
            for (int active : axes) if (axis == active) {
                ordered[count++] = axis;
                break;
            }
        }
        if (count == 2) {
            pairs.add(new int[] {ordered[0], ordered[1]});
            return pairs;
        }
        if (count < 2) return pairs;
        for (int k = 0; k < count; k++) {
            int from = ordered[k];
            int to = ordered[(k + 1) % count];
            if (!closesThroughCenter(from, to)) pairs.add(new int[] {from, to});
        }
        return pairs;
    }

    /** Each independent normalized spoke value (NaN where off or unread); proximity never changes with pay. */
    static double[] ratios(Floors floors, long payCents) {
        double[] ratios = new double[AXES];
        for (int i = 0; i < AXES; i++) {
            if (!floors.active[i]) ratios[i] = Double.NaN;
            else if (i == HOTSPOT) {
                BigDecimal denominator = floors.hotspotDenominator;
                ratios[i] = denominator == null ? Double.NaN : denominator.signum() == 0
                        ? Double.POSITIVE_INFINITY
                        : BigDecimal.ONE.divide(denominator, java.math.MathContext.DECIMAL64).doubleValue();
            } else ratios[i] = floors.cents[i] == null ? Double.NaN : payCents / floors.cents[i].doubleValue();
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
        for (int[] pair : pairs) {
            double left = r[pair[0]], right = r[pair[1]];
            // A collapsed monetary radius contributes no triangle, including beside infinite proximity.
            if (left != 0 && right != 0) area += left * right;
        }
        return Math.sqrt(area / pairs.size());
    }

    /**
     * Whether the score reaches 100%, exactly. Each monetary ratio is pay / cents; proximity is 1 / its independent
     * denominator. Sum pair products by multiplying through their positive denominators, with no decimal division.
     */
    static boolean reaches(Floors floors, long payCents) {
        if (!floors.anyActive() || !floors.readable() || payCents < 0) return false;
        int[] axes = floors.activeAxes();
        BigDecimal pay = BigDecimal.valueOf(payCents);
        if (axes.length == 1) {
            return axes[0] == HOTSPOT ? BigDecimal.ONE.compareTo(floors.hotspotDenominator) >= 0
                    : pay.compareTo(floors.cents[axes[0]]) >= 0;
        }
        List<int[]> pairs = pairs(axes);
        if (payCents == 0) return false; // Every pair contains at least one zero-length monetary radius.
        BigDecimal[] denominators = new BigDecimal[pairs.size()];
        BigDecimal[] numerators = new BigDecimal[pairs.size()];
        BigDecimal all = BigDecimal.ONE;
        for (int p = 0; p < pairs.size(); p++) {
            int left = pairs.get(p)[0], right = pairs.get(p)[1];
            boolean hotspot = left == HOTSPOT || right == HOTSPOT;
            BigDecimal leftDenominator = left == HOTSPOT ? floors.hotspotDenominator : floors.cents[left];
            BigDecimal rightDenominator = right == HOTSPOT ? floors.hotspotDenominator : floors.cents[right];
            denominators[p] = leftDenominator.multiply(rightDenominator);
            if (denominators[p].signum() == 0) return true; // Known hotspot, nonzero monetary neighbor: infinite area.
            numerators[p] = hotspot ? pay : pay.multiply(pay);
            all = all.multiply(denominators[p]);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int p = 0; p < pairs.size(); p++) {
            BigDecimal others = numerators[p];
            for (int q = 0; q < pairs.size(); q++) if (q != p) others = others.multiply(denominators[q]);
            sum = sum.add(others);
        }
        return sum.compareTo(all.multiply(BigDecimal.valueOf(pairs.size()))) >= 0;
    }

    /**
     * The least whole-cent pay that scores 100%; Long.MAX_VALUE when pay cannot satisfy the rules or exceeds the
     * existing $10-billion bound. Zero means no pay is asked (or the active data is incomplete; callers must REVIEW).
     * Unlike the original monetary-only score, proximity is independent of pay, so the general case uses an exact
     * monotone search, not 1 / score(1 cent). A proximity-only rule either already passes at zero or no pay can fix it.
     */
    static long requiredPay(Floors floors) {
        if (!floors.anyActive() || !floors.readable()) return 0;
        if (floors.active[HOTSPOT]) {
            if (reaches(floors, 0)) return 0;
            if (!reaches(floors, OUT_OF_REACH)) return Long.MAX_VALUE;
            long low = 0, high = OUT_OF_REACH;
            while (high - low > 1) {
                long middle = low + (high - low) / 2;
                if (reaches(floors, middle)) high = middle;
                else low = middle;
            }
            return high;
        }
        // Preserve the original monetary-only result and fast path exactly.
        double atOneCent = score(floors, 1);
        if (!(atOneCent > 0)) return Long.MAX_VALUE;
        double estimate = Math.ceil(1 / atOneCent);
        if (estimate > OUT_OF_REACH) return Long.MAX_VALUE;
        long pay = (long) estimate;
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
        Floors floors = floors(rules, offer);
        if (offer.payCents == null && floors.needsPay()) return -1;
        return percent(floors, offer.payCents == null ? 0 : offer.payCents);
    }

    /** "Score 121%". */
    static String label(int percent) {
        return "Score " + percent + "%";
    }

    private AreaScore() {}
}
