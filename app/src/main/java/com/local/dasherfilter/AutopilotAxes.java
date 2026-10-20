package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Arrays;
import java.util.Objects;

/**
 * Autopilot's independent working rates. A confirmed personal acceptance replaces each observed active rate,
 * in either direction. The exact rate is normalized to the bar at that acceptance, so applying the bar once asks
 * exactly what that offer paid. Saved knobs and the best-ever learner remain separate.
 */
final class AutopilotAxes {
    static final AutopilotAxes NONE = new AutopilotAxes(new Rate[3], 0, FilterSettings.BAR_AT_MINIMUMS);
    private static final int MAX_ENCODED = 4096;
    private static final int MAX_INTEGER_DIGITS = 512;

    /** The latest prospective acceptance which set at least one active, observed rate. */
    final long acceptedAt;
    /** The shared bar at that acceptance; its existing next-24-offers protection starts here. */
    final int referenceBar;
    private final Rate[] rates;

    /** Positive exact cents per unit at a bar of 100. Decimal miles retain their exact observed decimal value. */
    static final class Rate {
        final BigInteger numerator, denominator;

        Rate(BigInteger numerator, BigInteger denominator) {
            if (numerator.signum() <= 0 || denominator.signum() <= 0) throw new IllegalArgumentException("rate");
            BigInteger gcd = numerator.gcd(denominator);
            this.numerator = numerator.divide(gcd);
            this.denominator = denominator.divide(gcd);
        }

        private static Rate observed(int pay, BigDecimal units, int bar) {
            int scale = units.scale();
            BigInteger n = BigInteger.valueOf(pay).multiply(BigInteger.valueOf(100));
            BigInteger d = units.unscaledValue().multiply(BigInteger.valueOf(bar));
            if (scale >= 0) n = n.multiply(BigInteger.TEN.pow(scale));
            else d = d.multiply(BigInteger.TEN.pow(-scale));
            return new Rate(n, d);
        }

        BigDecimal display() {
            return new BigDecimal(numerator).divide(new BigDecimal(denominator), MathContext.DECIMAL128);
        }

        private String encode() { return numerator + "/" + denominator; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Rate)) return false;
            Rate that = (Rate) other;
            return numerator.equals(that.numerator) && denominator.equals(that.denominator);
        }
        @Override public int hashCode() { return Objects.hash(numerator, denominator); }
    }

    private AutopilotAxes(Rate[] rates, long acceptedAt, int referenceBar) {
        this.rates = rates.clone();
        this.acceptedAt = acceptedAt;
        this.referenceBar = referenceBar;
    }

    boolean isEmpty() { return rates[0] == null && rates[1] == null && rates[2] == null; }
    boolean hasAxis(int axis) { return axis >= 0 && axis < rates.length && rates[axis] != null; }
    Rate rate(int axis) { return hasAxis(axis) ? rates[axis] : null; }

    /** Active means a saved or enabled learned minimum, never a retained Autopilot override alone. */
    static boolean active(FilterSettings rules, int axis) {
        if (axis < 0 || axis > AreaScore.MINUTE) return false;
        int saved = axis == AreaScore.PAY ? rules.flatCents
                : axis == AreaScore.MILE ? rules.perMileCents : rules.perMinuteCents;
        if (saved > 0) return true;
        if (!rules.acceptanceLearning) return false;
        return axis == AreaScore.PAY ? rules.accepted.highestPayCents > 0
                : axis == AreaScore.MILE ? rules.accepted.hasPerMile() : rules.accepted.hasPerMinute();
    }

    /**
     * The confirmed offer's known active rates replace the old ones exactly; no averaging, cent rounding, or saved
     * minimum clamp. Unknown and disabled axes keep their stored values. Eligibility/provenance and the safe point
     * are the runtime's responsibility; a known implausible figure still rejects the entire offer here.
     */
    AutopilotAxes withOffer(FilterSettings rules, OfferSnapshot offer, long at) {
        if (rules == null || !rules.autopilot || at < 0 || offer == null || offer.payCents == null || offer.payCents <= 0
                || offer.payAtMostCents != null || OfferSanity.looksMisread(offer)
                || (rules.maxStops > 0 && offer.stops != null && offer.stops > rules.maxStops)) return this;
        Rate[] next = rates.clone();
        BigDecimal[] units = {offer.stops == null ? null : BigDecimal.valueOf(offer.stops),
                offer.miles == null ? null : BigDecimal.valueOf(offer.miles),
                offer.minutes == null ? null : BigDecimal.valueOf(offer.minutes)};
        boolean observed = false;
        for (int axis = 0; axis < next.length; axis++) {
            if (!active(rules, axis) || units[axis] == null || units[axis].signum() <= 0) continue;
            next[axis] = Rate.observed(offer.payCents, units[axis], rules.minimumScalePercent);
            observed = true;
        }
        if (!observed) return this;
        AutopilotAxes result = new AutopilotAxes(next, at, rules.minimumScalePercent);
        return equals(result) ? this : result;
    }

    /** Local bounded numeric state only, including the latest acceptance's hold metadata. */
    String encode() {
        if (isEmpty()) return "";
        StringBuilder text = new StringBuilder("1|").append(acceptedAt).append('|').append(referenceBar);
        for (Rate rate : rates) text.append('|').append(rate == null ? "-" : rate.encode());
        return text.toString();
    }

    /** Invalid or incomplete state never creates a rule. No historical records are used to reconstruct it. */
    static AutopilotAxes decode(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED) return NONE;
        try {
            String[] fields = encoded.split("\\|", -1);
            if (fields.length != 6 || !"1".equals(fields[0])) return NONE;
            long at = Long.parseLong(fields[1]);
            int bar = Integer.parseInt(fields[2]);
            if (at < 0 || bar < 1 || bar > 200) return NONE;
            Rate[] rates = new Rate[3];
            for (int axis = 0; axis < rates.length; axis++) {
                String value = fields[axis + 3];
                if ("-".equals(value)) continue;
                String[] ratio = value.split("/", -1);
                if (ratio.length != 2 || !positiveInteger(ratio[0]) || !positiveInteger(ratio[1])) return NONE;
                rates[axis] = new Rate(new BigInteger(ratio[0]), new BigInteger(ratio[1]));
            }
            AutopilotAxes result = new AutopilotAxes(rates, at, bar);
            return result.isEmpty() ? NONE : result;
        } catch (IllegalArgumentException malformed) {
            return NONE;
        }
    }

    private static boolean positiveInteger(String value) {
        if (value.isEmpty() || value.length() > MAX_INTEGER_DIGITS || value.charAt(0) == '0') return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        return true;
    }

    String stableKey() { return encode(); }
    @Override public boolean equals(Object other) {
        if (!(other instanceof AutopilotAxes)) return false;
        AutopilotAxes that = (AutopilotAxes) other;
        return acceptedAt == that.acceptedAt && referenceBar == that.referenceBar && Arrays.equals(rates, that.rates);
    }
    @Override public int hashCode() { return Objects.hash(Arrays.hashCode(rates), acceptedAt, referenceBar); }
}
