package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/** Shared exact three-axis floors, strict/area requirements and stable drawing geometry.
 * Decimal route facts and learned rate pairs remain rational through aggregation and the bar. Only the final
 * required pay rounds up to a cent. Display projections never decide a result. The pay axis is pay per stop (0.6.12):
 * it asks its rate for each stop after the first pickup ({@link #payUnits}). */
final class AreaScore {
    static final int PAY = 0, MILE = 1, MINUTE = 2, STOP = 3, HOTSPOT = 4, ITEM = 5, AXES = 6;
    static final float SPREAD = 30;
    static final float[] ANGLES = {SPREAD - 180, -SPREAD, SPREAD, 180 - SPREAD, -90, 90};
    static final int[] DRAW_ORDER = {PAY, HOTSPOT, MILE, MINUTE, ITEM, STOP};
    private static final int[] MONEY_AXES = {PAY, MILE, MINUTE};

    /** Nonnegative exact fraction; no floating-point tolerance participates in a cutoff. */
    private static final class Fraction implements Comparable<Fraction> {
        final BigInteger n, d;
        Fraction(BigInteger n, BigInteger d) {
            BigInteger gcd = n.gcd(d);
            this.n = n.divide(gcd); this.d = d.divide(gcd);
        }
        static Fraction of(long value) { return new Fraction(BigInteger.valueOf(value), BigInteger.ONE); }
        static Fraction of(BigDecimal value) {
            int scale = value.scale();
            return scale < 0 ? new Fraction(value.unscaledValue().multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE)
                    : new Fraction(value.unscaledValue(), BigInteger.TEN.pow(scale));
        }
        Fraction times(Fraction b) { return new Fraction(n.multiply(b.n), d.multiply(b.d)); }
        Fraction dividedBy(Fraction b) { return new Fraction(n.multiply(b.d), d.multiply(b.n)); }
        Fraction plus(Fraction b) { return new Fraction(n.multiply(b.d).add(b.n.multiply(d)), d.multiply(b.d)); }
        Fraction square() { return times(this); }
        boolean zero() { return n.signum() == 0; }
        BigDecimal display() { return new BigDecimal(n).divide(new BigDecimal(d), MathContext.DECIMAL128); }
        @Override public int compareTo(Fraction b) { return n.multiply(b.d).compareTo(b.n.multiply(d)); }
    }

    /** Saved, observed accepted and currently effective asks of one route. Null means inactive or unread.
     * Public decimal arrays are display projections; private fractions retain exact decision arithmetic. */
    static final class Floors {
        final BigDecimal[] fixedCents = new BigDecimal[AXES];
        final BigDecimal[] acceptedCents = new BigDecimal[AXES];
        final BigDecimal[] effectiveCents = new BigDecimal[AXES];
        final boolean[] active = new boolean[AXES];
        final boolean missing;
        private final Fraction[] effective = new Fraction[AXES];
        /** The least an active axis whose own fact is unread can ask (pay per stop with stops unread: one stop). */
        private final Fraction[] lower = new Fraction[AXES];
        private final int count;
        private Floors(FilterSettings rules, OfferSnapshot offer) {
            int[] saved = rules.minimums();
            boolean unread = false;
            int axes = 0;
            for (int axis : MONEY_AXES) {
                Fraction fixed = fraction(fixedFloor(axis, saved[axis], offer));
                Fraction learned = acceptedFloor(axis, rules.accepted, offer);
                boolean learnedSet = axis == PAY ? rules.accepted.highestPayCents > 0
                        : axis == MILE ? rules.accepted.hasPerMile() : rules.accepted.hasPerMinute();
                active[axis] = saved[axis] > 0 || (rules.acceptanceLearning && learnedSet);
                fixedCents[axis] = display(fixed);
                acceptedCents[axis] = display(learned);
                if (!active[axis]) continue;
                axes++;
                Fraction used = maximum(fixed, rules.acceptanceLearning ? learned : null);
                effective[axis] = used;
                effectiveCents[axis] = display(used);
                if (used == null) {
                    unread = true;
                    if (axis == PAY) lower[axis] = maximum(fraction(fixedFloor(PAY, saved[PAY], 1)),
                            rules.acceptanceLearning ? acceptedFloor(PAY, rules.accepted, 1) : null);
                }
            }
            missing = unread;
            count = axes;
        }
        boolean complete() { return !missing; }
        /** A multi-axis area cannot use a reciprocal of zero as an observed fitness. */
        boolean degenerateArea() {
            if (count < 2) return false;
            for (int axis : MONEY_AXES) if (active[axis] && effective[axis] != null && effective[axis].zero()) return true;
            return false;
        }
        private Fraction knownMaximum() {
            Fraction result = null;
            for (int axis : MONEY_AXES) result = maximum(result, effective[axis] != null ? effective[axis] : lower[axis]);
            return result;
        }
        private Fraction requiredSquare(boolean area, boolean partial) {
            if (!area) {
                Fraction highest = knownMaximum();
                return (!partial && missing) || highest == null ? null : highest.square();
            }
            if (missing || degenerateArea() || count == 0) return null;
            Fraction[] asks = new Fraction[count];
            int index = 0;
            for (int axis : MONEY_AXES) if (active[axis]) asks[index++] = effective[axis];
            if (count == 1) return asks[0].square();
            if (count == 2) return asks[0].times(asks[1]);
            // Current angles: PAY→MILE=120°, MILE→MINUTE=60°, MINUTE→PAY=180° (zero area).
            // Equal sine weights normalize to R² = 2*a*b*c/(a+c).
            return asks[0].times(asks[1]).times(asks[2]).times(Fraction.of(2))
                    .dividedBy(asks[0].plus(asks[2]));
        }
    }

    static Floors floors(FilterSettings rules, OfferSnapshot offer) { return new Floors(rules, offer); }

    /** Exact, unscaled per-axis raise at the supplied observed route (not rounded display values). */
    static boolean effectiveFloorsRaised(FilterSettings before, FilterSettings after, OfferSnapshot offer) {
        Floors old = floors(before, offer), next = floors(after, offer);
        for (int axis : MONEY_AXES) {
            Fraction a = old.effective[axis], b = next.effective[axis];
            if (b != null && (a == null ? b.n.signum() > 0 : b.compareTo(a) > 0)) return true;
        }
        return false;
    }

    /** Strict highest ask, retained for callers that need its binding axis or a partial known failure. */
    static final class Asks {
        final BigDecimal known;
        final int axis;
        final boolean missing;
        private final Fraction exact;
        private Asks(Floors floors) {
            exact = floors.knownMaximum();
            known = display(exact);
            missing = floors.missing;
            int found = -1;
            for (int money : MONEY_AXES) {
                Fraction ask = floors.effective[money] != null ? floors.effective[money] : floors.lower[money];
                if (exact != null && ask != null && exact.compareTo(ask) == 0) { found = money; break; }
            }
            axis = found;
        }
        BigDecimal complete() { return missing ? null : known; }
        long required(int bar) { return exact == null ? 0 : cents(exact.square(), bar); }
        int score(Integer pay) { return missing || pay == null || exact == null ? -1 : percent(pay, exact.square()); }
    }
    static Asks asks(FilterSettings rules, OfferSnapshot offer) { return new Asks(floors(rules, offer)); }

    /** Display-only unscaled requirement; null when no requirement or a selected-model fact is unread/degenerate. */
    static BigDecimal required100(FilterSettings rules, OfferSnapshot offer) {
        Fraction square = floors(rules, offer).requiredSquare(rules.scoreByArea, false);
        if (square == null) return null;
        if (!rules.scoreByArea) return asks(rules, offer).complete();
        // This approximate projection is never used by a decision, threshold, score, or cent ceiling.
        BigDecimal value = square.display();
        if (value.signum() == 0) return BigDecimal.ZERO;
        int exponent = value.precision() - value.scale();
        BigDecimal root = BigDecimal.ONE.scaleByPowerOfTen(Math.floorDiv(exponent + 1, 2));
        for (int i = 0; i < 128; i++) {
            BigDecimal next = root.add(value.divide(root, MathContext.DECIMAL128))
                    .divide(BigDecimal.valueOf(2), MathContext.DECIMAL128);
            if (next.compareTo(root) == 0) break;
            root = next;
        }
        return root;
    }
    static BigDecimal knownRequired100(FilterSettings rules, OfferSnapshot offer) { return asks(rules, offer).known; }

    /** Final whole-cent requirement at a bar, or -1 when an active fact/area fitness cannot be determined. */
    static long requiredPay(FilterSettings rules, OfferSnapshot offer, int bar) {
        if (!rules.hasMonetaryRule()) return 0;
        Fraction square = floors(rules, offer).requiredSquare(rules.scoreByArea, false);
        return square == null ? -1 : cents(square, bar);
    }

    /** Largest integer bar that is not a known decline, including REVIEW and the conservative +$ ceiling policy. */
    static int passThreshold(FilterSettings rules, OfferSnapshot offer) {
        if (rules.maxStops > 0 && offer.stops != null && offer.stops > rules.maxStops) return 0;
        Fraction square = floors(rules, offer).requiredSquare(rules.scoreByArea, true);
        if (square == null || square.zero()) return Integer.MAX_VALUE;
        if (offer.payCents != null) return percent(offer.payCents, square);
        if (offer.payAtMostCents == null) return Integer.MAX_VALUE;
        int ceiling = percent(offer.payAtMostCents, square);
        return ceiling >= 100 ? Integer.MAX_VALUE : ceiling;
    }

    static int scorePercent(FilterSettings rules, OfferSnapshot offer) {
        Fraction square = floors(rules, offer).requiredSquare(rules.scoreByArea, false);
        return offer.payCents == null || square == null ? -1 : percent(offer.payCents, square);
    }
    private static int percent(long pay, Fraction square) {
        if (square.zero()) return -1;
        BigInteger numerator = BigInteger.valueOf(pay).pow(2).multiply(BigInteger.valueOf(10_000)).multiply(square.d);
        BigInteger score = sqrtFloor(numerator.divide(square.n));
        return score.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }
    private static long cents(Fraction square, int bar) {
        BigInteger n = square.n.multiply(BigInteger.valueOf(bar).pow(2));
        BigInteger d = square.d.multiply(BigInteger.valueOf(10_000));
        BigInteger whole = sqrtFloor(n.divide(d));
        if (whole.pow(2).multiply(d).compareTo(n) < 0) whole = whole.add(BigInteger.ONE);
        return whole.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }
    private static BigInteger sqrtFloor(BigInteger n) {
        if (n.signum() == 0) return BigInteger.ZERO;
        BigInteger x = BigInteger.ONE.shiftLeft((n.bitLength() + 1) / 2);
        while (true) {
            BigInteger next = x.add(n.divide(x)).shiftRight(1);
            if (next.compareTo(x) >= 0) return x;
            x = next;
        }
    }

    /**
     * How many times the pay-per-stop minimum applies to an offer of {@code stops} stops: rule C (0.6.12), each stop
     * after the first pickup, {@code max(1, stops − 1)}. Dasher counts a single order as two stops (its pickup and its
     * drop-off), so a single order asks the number once, exactly as the minimum pay did before; three stops ask it
     * twice, four three times. Null when the stops were not read (a read of 0 counts as unread).
     */
    static Integer payUnits(Integer stops) {
        return stops == null || stops < 1 ? null : Math.max(1, stops - 1);
    }

    /** The learned pay ask for {@code units} stops: the best pay per stop × units, plus a cent. */
    private static Fraction acceptedFloor(int axis, AcceptedFloors proof, int units) {
        return axis == PAY && proof.highestPayCents > 0 ? Fraction.of((long) proof.highestPayCents * units)
                .dividedBy(Fraction.of(proof.payUnits)).plus(Fraction.of(1)) : null;
    }

    private static Fraction acceptedFloor(int axis, AcceptedFloors proof, OfferSnapshot offer) {
        if (axis == PAY) {
            Integer units = payUnits(offer.stops);
            return units == null ? null : acceptedFloor(PAY, proof, units);
        }
        if (axis == MILE && proof.hasPerMile() && offer.miles != null) {
            return Fraction.of(proof.milePay).times(Fraction.of(BigDecimal.valueOf(offer.miles)))
                    .dividedBy(Fraction.of(BigDecimal.valueOf(proof.miles)));
        }
        if (axis == MINUTE && proof.hasPerMinute() && offer.minutes != null) {
            return Fraction.of(proof.minutePay).times(Fraction.of(offer.minutes)).dividedBy(Fraction.of(proof.minutes));
        }
        return null;
    }
    private static Fraction fraction(BigDecimal value) { return value == null ? null : Fraction.of(value); }
    private static BigDecimal display(Fraction value) { return value == null ? null : value.display(); }
    private static Fraction maximum(Fraction a, Fraction b) { return a == null ? b : b == null || a.compareTo(b) >= 0 ? a : b; }

    /** The pay-per-stop ask for a known number of {@code units} (its lower bound when the stops are unread: 1). */
    static BigDecimal fixedFloor(int axis, int rate, int units) {
        return rate <= 0 || axis != PAY ? null : BigDecimal.valueOf((long) rate * units);
    }

    /** Exact fixed component; a known zero route asks zero, while an unread route (or unread stops) remains null. */
    static BigDecimal fixedFloor(int axis, int rate, OfferSnapshot offer) {
        if (rate <= 0) return null;
        switch (axis) {
            case PAY: {
                Integer units = payUnits(offer.stops);
                return units == null ? null : BigDecimal.valueOf((long) rate * units);
            }
            case MILE: return offer.miles == null ? null : BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(offer.miles));
            case MINUTE: return offer.minutes == null ? null : BigDecimal.valueOf((long) rate * offer.minutes);
            default: return null;
        }
    }
    static long roundedCents(BigDecimal cents, int scale) {
        try {
            return cents.multiply(BigDecimal.valueOf(scale)).movePointLeft(2).setScale(0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
    static boolean closesThroughCenter(int from, int to) {
        float gap = ((ANGLES[to] - ANGLES[from]) % 360 + 360) % 360;
        return gap >= 180;
    }
    static int percent(FilterSettings rules, OfferSnapshot offer) { return scorePercent(rules, offer); }
    static String label(int percent) { return "Score " + percent + "%"; }
    private AreaScore() {}
}
