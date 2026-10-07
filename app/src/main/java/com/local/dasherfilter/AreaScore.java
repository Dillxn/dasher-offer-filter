package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
 * rules (and score by area with them): their indexes stay reserved, and they ask nothing.
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

    /**
     * An exact fixed monetary component, shared with knob previews: minimum pay itself, or a rate times the miles or
     * minutes read. Null when off or unread, and on every retired axis (stop, hotspot, item), which asks nothing.
     */
    static BigDecimal fixedFloor(int axis, int rate, OfferSnapshot offer) {
        if (rate <= 0) return null;
        switch (axis) {
            case PAY: return BigDecimal.valueOf(rate);
            case MILE: return offer.miles == null ? null
                    : BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(offer.miles));
            case MINUTE: return offer.minutes == null ? null : BigDecimal.valueOf((long) rate * offer.minutes);
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

    /** The score of {@code offer} under {@code rules}: {@link #scorePercent}. */
    static int percent(FilterSettings rules, OfferSnapshot offer) {
        return scorePercent(rules, offer);
    }

    /** "Score 121%". */
    static String label(int percent) {
        return "Score " + percent + "%";
    }

    private AreaScore() {}
}
