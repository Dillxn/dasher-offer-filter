package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/** Shared exact three-axis floors, strict/area requirements and stable drawing geometry.
 * Decimal route facts and learned rate pairs remain rational through aggregation and the bar. Only the final
 * required pay rounds up to a cent. Display projections never decide a result. The pay axis is pay per stop (0.6.12):
 * it asks its rate for each stop, the pickup included ({@link #payUnits}). Optional time awareness divides working
 * requirements by its exact score factor, including the total-pay reference, before any final cent rounding.
 * Saved/learned baseline projections remain unadjusted.
 *
 * <p>Total pay (0.6.31, the owner: "we must factor in overall pay"): while Autopilot's working minimums apply in Area
 * mode, a quarter of the score is the offer's total pay against the latest acceptance's ({@link AutopilotAxes#totalPay}),
 * and three quarters its area, as before: R' = R^(3/4) · T^(1/4), held exactly as R'^8 = (R²)³ · T². T is total
 * against total, its own number: never pay per stop × stops, never on the pay-per-stop spoke. Strict mode, the
 * saved and learned baseline, add-ons and auto-accept never use it. */
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
     * The decimal arrays are display projections, made at their first read (0.6.27: made with every floor, they were
     * most of Autopilot's planning time, which never reads them); private fractions retain exact decision arithmetic. */
    static final class Floors {
        final boolean[] active = new boolean[AXES];
        final boolean missing;
        private final Fraction[] fixed = new Fraction[AXES];
        private final Fraction[] accepted = new Fraction[AXES];
        /** The saved/enabled-learned requirement, before Autopilot's independent working rates. */
        private final Fraction[] baseline = new Fraction[AXES];
        private final Fraction[] effective = new Fraction[AXES];
        /** The least an active axis whose own fact is unread can ask (pay per stop with stops unread: one stop). */
        private final Fraction[] lower = new Fraction[AXES];
        /** Autopilot's total pay T at a bar of 100 while its working minimums apply (0.6.31), else null; Area mode only. */
        private final Fraction totalPay;
        private final int count;
        /** The saved, accepted and effective projections once read (from any thread: a race makes the same twice). */
        private volatile BigDecimal[][] projections;
        private Floors(FilterSettings rules, OfferSnapshot offer) {
            Fraction time = rules.timeWeight.factor == TimeScore.ONE ? null
                    : Fraction.of(TimeScore.ONE).dividedBy(Fraction.of(rules.timeWeight.factor));
            int[] saved = rules.minimums();
            boolean unread = false;
            int axes = 0;
            for (int axis : MONEY_AXES) {
                Fraction fixed = fraction(fixedFloor(axis, saved[axis], offer));
                Fraction learned = acceptedFloor(axis, rules.accepted, offer);
                boolean learnedSet = axis == PAY ? rules.accepted.highestPayCents > 0
                        : axis == MILE ? rules.accepted.hasPerMile() : rules.accepted.hasPerMinute();
                active[axis] = saved[axis] > 0 || (rules.acceptanceLearning && learnedSet);
                this.fixed[axis] = fixed;
                accepted[axis] = learned;
                if (!active[axis]) continue;
                axes++;
                Fraction used = maximum(fixed, rules.acceptanceLearning ? learned : null);
                baseline[axis] = used;
                if (rules.autopilot && rules.autopilotAxes.hasAxis(axis)) {
                    used = autopilotFloor(axis, rules.autopilotAxes, offer);
                }
                effective[axis] = used == null || time == null ? used : used.times(time);
                if (used == null) {
                    unread = true;
                    if (axis == PAY) lower[axis] = rules.autopilot && rules.autopilotAxes.hasAxis(PAY)
                            ? autopilotRate(rules.autopilotAxes, PAY)
                            : maximum(fraction(fixedFloor(PAY, saved[PAY], 1)),
                                    rules.acceptanceLearning ? acceptedFloor(PAY, rules.accepted, 1) : null);
                    if (lower[axis] != null && time != null) lower[axis] = lower[axis].times(time);
                }
            }
            missing = unread;
            count = axes;
            AutopilotAxes.Rate whole = rules.hasAutopilotAxes() ? rules.autopilotAxes.totalPay() : null;
            Fraction wholeAsk = whole == null ? null : new Fraction(whole.numerator, whole.denominator);
            totalPay = wholeAsk == null || time == null ? wholeAsk : wholeAsk.times(time);
        }
        /** The saved asks in cents, a display projection. */
        BigDecimal[] fixedCents() { return projections()[0]; }
        /** The observed accepted asks in cents, a display projection. */
        BigDecimal[] acceptedCents() { return projections()[1]; }
        /** The currently effective asks in cents, a display projection. */
        BigDecimal[] effectiveCents() { return projections()[2]; }
        /** Saved/enabled-learned asks, without the independent Autopilot rates or its shared bar. */
        BigDecimal[] baselineCents() { return projections()[3]; }
        /** Exact final whole-cent working ask for one axis; never round a repeating display projection first. */
        long axisRequiredCents(int axis, int bar) {
            return axis < 0 || axis >= AXES || effective[axis] == null ? -1 : cents(effective[axis].square(), bar);
        }
        private BigDecimal[][] projections() {
            BigDecimal[][] made = projections;
            if (made != null) return made;
            made = new BigDecimal[4][AXES];
            for (int axis : MONEY_AXES) {
                made[0][axis] = display(fixed[axis]);
                made[1][axis] = display(accepted[axis]);
                made[2][axis] = display(effective[axis]);
                made[3][axis] = display(baseline[axis]);
            }
            projections = made;
            return made;
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
        /**
         * The requirement R as an exact power: R² (root 2), or, when Autopilot's total pay T counts in Area mode,
         * R'^8 = (R²)³ · T² (root 8), so R' = R^(3/4) · T^(1/4) and an offer exactly like the latest acceptance still
         * meets the bar exactly. Null as {@link #requiredSquare} is.
         */
        private Need required(boolean area, boolean partial) {
            Fraction square = requiredSquare(area, partial);
            if (square == null) return null;
            if (!area || totalPay == null) return new Need(square, 2);
            return new Need(square.square().times(square).times(totalPay.square()), 8);
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

    /** An exact requirement R held as R^root, root 2 or 8 (a power of two, so its root is repeated square roots). */
    private static final class Need {
        final Fraction power;
        final int root;
        Need(Fraction power, int root) { this.power = power; this.root = root; }
        boolean zero() { return power.zero(); }
    }

    /** Whether Autopilot's total pay counts for these rules: Area mode with its working minimums applying (0.6.31). */
    static boolean totalPayCounts(FilterSettings rules) {
        return rules.scoreByArea && rules.hasAutopilotAxes() && rules.autopilotAxes.totalPay() != null;
    }

    /** Autopilot's total pay in whole cents at a bar (the latest acceptance's own pay at its bar), or -1 when it doesn't count. */
    static long totalPayCents(FilterSettings rules, int bar) {
        if (!totalPayCounts(rules)) return -1;
        AutopilotAxes.Rate whole = rules.autopilotAxes.totalPay();
        return cents(new Fraction(whole.numerator, whole.denominator).times(Fraction.of(TimeScore.ONE))
                .dividedBy(Fraction.of(rules.timeWeight.factor)).square(), bar);
    }

    /** Exact, unscaled per-axis raise at the supplied observed route (not rounded display values). */
    static boolean effectiveFloorsRaised(FilterSettings before, FilterSettings after, OfferSnapshot offer) {
        Floors old = floors(before, offer), next = floors(after, offer);
        for (int axis : MONEY_AXES) {
            Fraction a = old.baseline[axis], b = next.baseline[axis];
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
        Need need = floors(rules, offer).required(rules.scoreByArea, false);
        if (need == null) return null;
        if (!rules.scoreByArea) return asks(rules, offer).complete();
        // This approximate projection is never used by a decision, threshold, score, or cent ceiling.
        BigDecimal value = need.power.display();
        for (int root = need.root; root > 1; root /= 2) value = squareRoot(value);
        return value;
    }
    private static BigDecimal squareRoot(BigDecimal value) {
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
        Need need = floors(rules, offer).required(rules.scoreByArea, false);
        return need == null ? -1 : cents(need, bar);
    }

    /** Largest integer bar that is not a known decline, including REVIEW and the conservative +$ ceiling policy. */
    static int passThreshold(FilterSettings rules, OfferSnapshot offer) {
        if (offer.payCents == null && offer.payAtMostCents != null && (rules.hasAutopilotAxes() || rules.timeAware)) {
            return passThreshold(rules.withoutAutopilotAxes().withoutTimeAdjustment(), offer);
        }
        if (rules.maxStops > 0 && offer.stops != null && offer.stops > rules.maxStops) return 0;
        Need need = floors(rules, offer).required(rules.scoreByArea, true);
        if (need == null || need.zero()) return Integer.MAX_VALUE;
        if (offer.payCents != null) return percent(offer.payCents, need);
        if (offer.payAtMostCents == null) return Integer.MAX_VALUE;
        int ceiling = percent(offer.payAtMostCents, need);
        return ceiling >= 100 ? Integer.MAX_VALUE : ceiling;
    }

    static int scorePercent(FilterSettings rules, OfferSnapshot offer) {
        Need need = floors(rules, offer).required(rules.scoreByArea, false);
        return offer.payCents == null || need == null ? -1 : percent(offer.payCents, need);
    }
    /** Original saved/enabled-learned score; a lowered working rate never relabels a below-minimum offer. */
    static int baselineScorePercent(FilterSettings rules, OfferSnapshot offer) {
        return scorePercent(rules.withoutAutopilotAxes().withoutTimeAdjustment(), offer);
    }
    private static int percent(long pay, Fraction square) { return percent(pay, new Need(square, 2)); }
    /** ⌊100 × pay ÷ R⌋ exactly: the root of 100^root × pay^root ÷ R^root, floored. */
    private static int percent(long pay, Need need) {
        if (need.zero()) return -1;
        BigInteger numerator = BigInteger.valueOf(pay).multiply(BigInteger.valueOf(100)).pow(need.root)
                .multiply(need.power.d);
        BigInteger score = rootFloor(numerator.divide(need.power.n), need.root);
        return score.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }
    private static long cents(Fraction square, int bar) { return cents(new Need(square, 2), bar); }
    /** ⌈bar × R ÷ 100⌉ exactly: the least whole cent whose root-th power reaches bar^root × R^root ÷ 100^root. */
    private static long cents(Need need, int bar) {
        BigInteger n = need.power.n.multiply(BigInteger.valueOf(bar).pow(need.root));
        BigInteger d = need.power.d.multiply(BigInteger.valueOf(100).pow(need.root));
        BigInteger whole = rootFloor(n.divide(d), need.root);
        if (whole.pow(need.root).multiply(d).compareTo(n) < 0) whole = whole.add(BigInteger.ONE);
        return whole.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }
    /** ⌊n^(1/root)⌋ for root a power of two: repeated floored square roots are exact for integers. */
    private static BigInteger rootFloor(BigInteger n, int root) {
        for (int left = root; left > 1; left /= 2) n = sqrtFloor(n);
        return n;
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
     * How many times the pay-per-stop minimum applies to an offer of {@code stops} stops: each stop, the pickup
     * included (0.6.14), Dasher's own count. Dasher counts a single order as two stops (its pickup and its drop-off), so
     * a single order asks the number twice; three stops ask it three times. Null when the stops were not read (a read of
     * 0 counts as unread). 0.6.12's rule C was {@code max(1, stops − 1)}.
     */
    static Integer payUnits(Integer stops) {
        return stops == null || stops < 1 ? null : stops;
    }

    /**
     * An offer's pay per stop, in cents: its pay ÷ {@link #payUnits} (every stop, the pickup included, 0.6.14), the same
     * count the requirement uses, so $7.50 over 2 stops is 375 ($3.75 a stop), never 0.6.12's $7.50. Null when the pay
     * or the stops were not read.
     */
    static BigDecimal payPerStop(Integer payCents, Integer stops) {
        Integer units = payUnits(stops);
        return payCents == null || units == null ? null
                : BigDecimal.valueOf(payCents).divide(BigDecimal.valueOf(units), 6, RoundingMode.HALF_EVEN);
    }

    /** "$3.75 a stop" for $7.50 over 2 stops (a fraction of a cent dropped, never shown higher); null when unread. */
    static String payPerStopText(Integer payCents, Integer stops) {
        BigDecimal cents = payPerStop(payCents, stops);
        return cents == null ? null : DecisionLog.money(cents.setScale(0, RoundingMode.FLOOR).longValue()) + " a stop";
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
    private static Fraction autopilotRate(AutopilotAxes axes, int axis) {
        AutopilotAxes.Rate rate = axes.rate(axis);
        return rate == null ? null : new Fraction(rate.numerator, rate.denominator);
    }

    /** Exact independent working ask at a bar of 100; the normal decision applies the shared bar once. */
    private static Fraction autopilotFloor(int axis, AutopilotAxes axes, OfferSnapshot offer) {
        Fraction rate = autopilotRate(axes, axis);
        if (rate == null) return null;
        if (axis == PAY) {
            Integer stops = payUnits(offer.stops);
            return stops == null ? null : rate.times(Fraction.of(stops));
        }
        if (axis == MILE) return offer.miles == null ? null
                : rate.times(Fraction.of(BigDecimal.valueOf(offer.miles)));
        if (axis == MINUTE) return offer.minutes == null ? null : rate.times(Fraction.of(offer.minutes));
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
