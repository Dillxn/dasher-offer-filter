package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * What an offer's minimums ask of it, exactly (0.5.0), plus the constellation's stable axis geometry.
 *
 * <p>The requirement at 100% is {@code R100 = max(flat, per mile × miles, per minute × minutes)} over the money
 * minimums that are set, worked in exact decimals ({@code miles} is taken at its decimal value). At bar σ an offer
 * needs {@code ⌈σ·R100 ÷ 100⌉} whole cents ({@link #roundedCents}: one rounding, after scaling). Its score is
 * {@code ⌊100·pay ÷ R100⌋}, "pay as a percent of what your minimums ask"; for an integer bar σ,
 * {@code pay ≥ ⌈σ·R100 ÷ 100⌉ ⟺ 100·pay ≥ σ·R100 ⟺ score ≥ σ}, so a shown score never claims a cutoff the offer
 * missed. A known zero distance or time asks nothing (the established strict zero-route rule); unknown is not zero.
 *
 * <p>The axis indexes, angles and drawn order stay as drawing geometry only. Stop, hotspot and item are retired as
 * rules; their indexes stay reserved. The old area-score functions ({@link Floors} and its helpers) are deprecated:
 * no decision calls them.
 */
final class AreaScore {
    static final int PAY = 0;
    static final int MILE = 1;
    static final int MINUTE = 2;
    static final int STOP = 3;
    static final int HOTSPOT = 4;
    static final int ITEM = 5;
    static final int AXES = 6;
    /** The spokes stand this many degrees above and below level. */
    static final float SPREAD = 30;
    /** Angles by stable axis index, clockwise from the right; the original five never move. */
    static final float[] ANGLES = {SPREAD - 180, -SPREAD, SPREAD, 180 - SPREAD, -90, 90};
    /** Stable axis indexes in clockwise drawn order. */
    static final int[] DRAW_ORDER = {PAY, HOTSPOT, MILE, MINUTE, ITEM, STOP};
    /** The money axes, in the order a tie between their asks is named. */
    private static final int[] MONEY_AXES = {PAY, MILE, MINUTE};

    // ---- The exact requirement ----

    /** What the money minimums ask of one offer at 100%, exactly. */
    static final class Asks {
        /** The highest ask among the set minimums whose quantity was read; null when none is set or none was read. */
        final BigDecimal known;
        /** The first axis (pay, per mile, per minute) whose ask equals {@link #known}; -1 when it is null. */
        final int axis;
        /** A set rate minimum's quantity (miles or minutes) was not read. */
        final boolean missing;

        private Asks(BigDecimal known, int axis, boolean missing) {
            this.known = known;
            this.axis = axis;
            this.missing = missing;
        }

        /** R100: the highest ask once every set minimum was read; null otherwise, or with no money minimum. */
        BigDecimal complete() {
            return missing ? null : known;
        }

        /** The score of {@code payCents} against these asks ({@link #scorePercent}); -1 when there is none. */
        int score(Integer payCents) {
            BigDecimal required = complete();
            if (payCents == null || required == null || required.signum() == 0) return -1;
            return floorPercent(payCents, required);
        }
    }

    /** The money minimums' asks of {@code offer} at 100%. */
    static Asks asks(FilterSettings rules, OfferSnapshot offer) {
        int[] minimums = rules.minimums();
        BigDecimal known = null;
        int axis = -1;
        boolean missing = false;
        for (int money : MONEY_AXES) {
            if (minimums[money] <= 0) continue;
            BigDecimal ask = fixedFloor(money, minimums[money], offer);
            if (ask == null) {
                missing = true;
            } else if (known == null || ask.compareTo(known) > 0) {
                known = ask;
                axis = money;
            }
        }
        return new Asks(known, axis, missing);
    }

    /**
     * R100, the exact cents the money minimums ask at 100%: null when a set minimum's quantity (miles or minutes) was
     * not read, or no money minimum is set.
     */
    static BigDecimal required100(FilterSettings rules, OfferSnapshot offer) {
        return asks(rules, offer).complete();
    }

    /** The highest ask among the set money minimums whose quantity was read: a partial R100; null when none. */
    static BigDecimal knownRequired100(FilterSettings rules, OfferSnapshot offer) {
        return asks(rules, offer).known;
    }

    /**
     * θ, the largest bar at which {@code offer} is not declined: {@code θ ≥ σ ⟺ OfferRule.evaluate(offer,
     * rules.withMinimumScalePercent(σ)).result ≠ DECLINE} for every integer bar σ. 0 above max stops;
     * {@code Integer.MAX_VALUE} when pay is unread (and no "+$" ceiling proves less), when no read minimum asks
     * anything, or when the asks are 0; otherwise {@code ⌊100·pay ÷ R_known⌋}. A "+$" ceiling C on unread pay declines
     * only below {@code min(σ, 100)}, so it gives {@code ⌊100·C ÷ R_known⌋} when that is under 100 and
     * {@code Integer.MAX_VALUE} otherwise. For a complete offer within max stops, θ is the score.
     */
    static int passThreshold(FilterSettings rules, OfferSnapshot offer) {
        if (rules.maxStops > 0 && offer.stops != null && offer.stops > rules.maxStops) return 0;
        if (!rules.hasMonetaryRule()) return Integer.MAX_VALUE;
        BigDecimal known = knownRequired100(rules, offer);
        if (known == null || known.signum() == 0) return Integer.MAX_VALUE;
        if (offer.payCents != null) return floorPercent(offer.payCents, known);
        if (offer.payAtMostCents == null) return Integer.MAX_VALUE;
        int ceiling = floorPercent(offer.payAtMostCents, known);
        return ceiling >= 100 ? Integer.MAX_VALUE : ceiling;
    }

    /**
     * The score: {@code ⌊100·pay ÷ R100⌋} as a whole percent of what the minimums ask; -1 when pay is unread, a set
     * minimum's quantity was not read, no money minimum is set, or R100 is 0. Max stops never changes it.
     */
    static int scorePercent(FilterSettings rules, OfferSnapshot offer) {
        return asks(rules, offer).score(offer.payCents);
    }

    /** {@code ⌊100·cents ÷ required⌋}, exactly, held to {@code Integer.MAX_VALUE}; {@code required} must be positive. */
    private static int floorPercent(long cents, BigDecimal required) {
        BigDecimal percent = BigDecimal.valueOf(cents).movePointRight(2).divide(required, 0, RoundingMode.FLOOR);
        return percent.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) >= 0 ? Integer.MAX_VALUE : percent.intValue();
    }

    /** An exact fixed monetary component, shared with knob previews; null when off, unread or inapplicable. */
    static BigDecimal fixedFloor(int axis, int rate, OfferSnapshot offer) {
        return fixedFloor(axis, rate, offer, false);
    }

    private static BigDecimal fixedFloor(int axis, int rate, OfferSnapshot offer, boolean incremental) {
        if (rate <= 0) return null;
        switch (axis) {
            case PAY: return incremental ? null : BigDecimal.valueOf(rate);
            case MILE: return offer.miles == null ? null
                    : BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(offer.miles));
            case MINUTE: return offer.minutes == null ? null : BigDecimal.valueOf((long) rate * offer.minutes);
            case STOP: return offer.stops == null || offer.stops < (incremental ? 0 : OfferSanity.PLAUSIBLE_STOPS)
                    ? null : BigDecimal.valueOf((long) rate * offer.stops);
            case ITEM: return !offer.itemCountApplicable || offer.items == null ? null
                    : BigDecimal.valueOf((long) rate * offer.items);
            default: return null;
        }
    }

    /** {@code ⌈cents × scale ÷ 100⌉}: exact scaling, then one rounding up to whole cents; saturates at Long.MAX_VALUE. */
    static long roundedCents(BigDecimal cents, int scale) {
        try {
            return cents.multiply(BigDecimal.valueOf(scale)).movePointLeft(2)
                    .setScale(0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Whether a clockwise edge closes via the middle instead of across an empty semicircle. Renderers use this
     * same predicate when constructing polygons with three or more active points. A 180-degree edge has no area.
     */
    static boolean closesThroughCenter(int from, int to) {
        float gap = ((ANGLES[to] - ANGLES[from]) % 360 + 360) % 360;
        return gap >= 180;
    }

    /** The contributing neighbors, in drawn order; one pair for the two-spoke fallback. */
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

    /** The score of {@code offer} under {@code rules}: {@link #scorePercent}. */
    static int percent(FilterSettings rules, OfferSnapshot offer) {
        return scorePercent(rules, offer);
    }

    /** "Score 121%". */
    static String label(int percent) {
        return "Score " + percent + "%";
    }

    // ---- Retired area score (deprecated: drawing only until its last callers go; no decision calls it) ----

    /** What each money spoke asks of one offer: fixed costs only (nothing is learned any more). */
    @Deprecated
    static final class Floors {
        /** Where a spoke's amount was read (null where off or unread; a zero route is not scored here). */
        final BigDecimal[] cents = new BigDecimal[AXES];
        /** The exact fixed costs; null if absent or unread. */
        final BigDecimal[] fixedCents = new BigDecimal[AXES];
        /** Retired learned costs: always null. */
        final BigDecimal[] acceptedCents = new BigDecimal[AXES];
        final BigDecimal[] declinedCents = new BigDecimal[AXES];
        /** Per spoke: a minimum is set there. */
        final boolean[] active = new boolean[AXES];

        /** A fixed cost before scaling; no floor and an unread floor both project to zero here. */
        BigDecimal effectiveCents(int axis) {
            return fixedCents[axis] == null ? BigDecimal.ZERO : fixedCents[axis];
        }

        /** Whole-cent projection for display. */
        long wholeCents(int axis) {
            return roundedCents(effectiveCents(axis), 100);
        }

        /** Scale once, then round up; 0 where there is no fixed cost. */
        long scaledCents(int axis, int scale, boolean fixedOnly) {
            return fixedCents[axis] == null ? 0 : roundedCents(fixedCents[axis], scale);
        }

        boolean anyActive() {
            for (boolean on : active) if (on) return true;
            return false;
        }

        boolean needsPay() {
            return anyActive();
        }

        /** Every active spoke's amount was read. */
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
            for (int axis : DRAW_ORDER) if (active[axis]) axes[at++] = axis;
            return axes;
        }
    }

    /** Which spokes have a minimum under {@code rules}: pay, per mile and per minute only. */
    @Deprecated
    static boolean[] active(FilterSettings rules) {
        return new boolean[] {rules.flatCents > 0, rules.perMileCents > 0, rules.perMinuteCents > 0,
                false, false, false};
    }

    /** The fixed floors {@code rules} put on {@code offer}'s spokes; a zero distance or time is not scored here. */
    @Deprecated
    static Floors floors(FilterSettings rules, OfferSnapshot offer) {
        return floors(rules, offer, false);
    }

    /** The same fixed costs for explicitly added amounts; no flat constraint. */
    @Deprecated
    static Floors incrementalFloors(FilterSettings rules, OfferSnapshot added) {
        return floors(rules, added, true);
    }

    private static Floors floors(FilterSettings rules, OfferSnapshot offer, boolean incremental) {
        Floors floors = new Floors();
        boolean[] active = active(rules);
        if (incremental) active[PAY] = false;
        System.arraycopy(active, 0, floors.active, 0, AXES);
        int[] minimums = rules.minimums();
        for (int axis : MONEY_AXES) {
            floors.fixedCents[axis] = fixedFloor(axis, minimums[axis], offer, incremental);
            if (!active[axis] || floors.fixedCents[axis] == null) continue;
            if (axis == MILE && offer.miles != null && offer.miles == 0) continue;
            if (axis == MINUTE && offer.minutes != null && offer.minutes == 0) continue;
            floors.cents[axis] = floors.fixedCents[axis];
        }
        return floors;
    }

    /** Each normalized spoke value, pay ÷ its floor (NaN where off or unread). */
    @Deprecated
    static double[] ratios(Floors floors, long payCents) {
        double[] ratios = new double[AXES];
        for (int i = 0; i < AXES; i++) {
            ratios[i] = !floors.active[i] || floors.cents[i] == null ? Double.NaN
                    : payCents / floors.cents[i].doubleValue();
        }
        return ratios;
    }

    /** The retired area score at {@code payCents}, 1 being the minimums' area; NaN when not computable. */
    @Deprecated
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

    /** Whether the retired area score reaches 100%, exactly. */
    @Deprecated
    static boolean reaches(Floors floors, long payCents) {
        return reaches(floors, payCents, 100);
    }

    /** Whether the retired area score reaches the percent, exactly (cross-multiplied, no decimal division). */
    @Deprecated
    static boolean reaches(Floors floors, long payCents, int minimumScalePercent) {
        if (!floors.anyActive() || !floors.readable() || payCents < 0) return false;
        int[] axes = floors.activeAxes();
        BigDecimal pay = BigDecimal.valueOf(payCents);
        if (axes.length == 1) {
            return pay.multiply(BigDecimal.valueOf(100))
                    .compareTo(floors.cents[axes[0]].multiply(BigDecimal.valueOf(minimumScalePercent))) >= 0;
        }
        List<int[]> pairs = pairs(axes);
        BigDecimal[] denominators = new BigDecimal[pairs.size()];
        BigDecimal all = BigDecimal.ONE;
        for (int p = 0; p < pairs.size(); p++) {
            denominators[p] = floors.cents[pairs.get(p)[0]].multiply(floors.cents[pairs.get(p)[1]]);
            if (denominators[p].signum() == 0) return payCents > 0;
            all = all.multiply(denominators[p]);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int p = 0; p < pairs.size(); p++) {
            BigDecimal others = pay.multiply(pay);
            for (int q = 0; q < pairs.size(); q++) if (q != p) others = others.multiply(denominators[q]);
            sum = sum.add(others);
        }
        return sum.multiply(BigDecimal.valueOf(10_000)).compareTo(all.multiply(BigDecimal.valueOf(pairs.size()))
                .multiply(BigDecimal.valueOf((long) minimumScalePercent * minimumScalePercent))) >= 0;
    }

    /** The least whole-cent pay whose retired area score reaches 100%. */
    @Deprecated
    static long requiredPay(Floors floors) {
        return requiredPay(floors, 100);
    }

    /** The least whole-cent pay whose retired area score reaches the percent; Long.MAX_VALUE when out of reach. */
    @Deprecated
    static long requiredPay(Floors floors, int minimumScalePercent) {
        if (!floors.anyActive() || !floors.readable()) return 0;
        double atOneCent = score(floors, 1);
        if (!(atOneCent > 0)) return Long.MAX_VALUE;
        double estimate = Math.ceil((minimumScalePercent / 100.0) / atOneCent);
        if (estimate > 1_000_000_000_000L) return Long.MAX_VALUE;
        long pay = (long) estimate;
        for (int i = 0; i < 8 && pay > 0 && reaches(floors, pay - 1, minimumScalePercent); i++) pay--;
        for (int i = 0; i < 8 && !reaches(floors, pay, minimumScalePercent); i++) pay++;
        return pay;
    }

    /** The retired area score as a whole percent; -1 when not computable. */
    @Deprecated
    static int percent(Floors floors, long payCents) {
        return percent(floors, payCents, 100);
    }

    /** The retired area score as a whole percent, never shown as reaching a cutoff it misses; -1 when none. */
    @Deprecated
    static int percent(Floors floors, long payCents, int minimumScalePercent) {
        double score = score(floors, payCents);
        if (Double.isNaN(score)) return -1;
        int shown = (int) Math.min(Integer.MAX_VALUE, Math.round(Math.min(score * 100, Integer.MAX_VALUE)));
        shown = reaches(floors, payCents) ? Math.max(100, shown) : Math.min(99, shown);
        if (minimumScalePercent == 100) return shown;
        return reaches(floors, payCents, minimumScalePercent)
                ? Math.max(minimumScalePercent, shown) : Math.min(minimumScalePercent - 1, shown);
    }

    private AreaScore() {}
}
