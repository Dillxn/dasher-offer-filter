package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Arrays;
import java.util.Objects;

/**
 * Autopilot's independent working rates. A confirmed personal acceptance replaces each observed active rate,
 * in either direction. The exact rate is normalized to that offer's own Autopilot score, the one its ticket showed
 * (0.6.40; the bar at that acceptance from 0.6.29), and the bar moves to that score with it, so applying it once asks
 * exactly what that offer paid and the accepted offer keeps its score. Saved knobs and the best-ever learner remain
 * separate.
 *
 * <p>Why the score and not the bar (the owner, 23 October 2026, beside a 32% offer accepted at a 25% bar: "the app
 * should be declarative and therefore naturally self healing: the 32% accepted offer within 11 orders from most
 * recent should be setting the auto bar"): normalized to the bar, every acceptance above it shrank every later score
 * by the bar ÷ its score, so the scores and the bar sank together, acceptance after acceptance, toward the lowest bar.
 * What a standalone offer needs is the same either way: that offer's own rates at the bar it set. What asks the saved
 * minimums at the bar (add-ons, automatic accepts) follows the bar.
 *
 * <p>From 0.6.31 the same acceptance also keeps its total pay, normalized the same way: Area mode weighs an offer's
 * total pay against it for a quarter of the score ({@link AreaScore}; the owner's "ticket value"). It is total against total:
 * never pay per stop × stops, and never drawn on the pay-per-stop spoke. State written before 0.6.31 has none until
 * another acceptance or, from 0.6.44, reconciliation from an eligible retained confirmation's complete record.
 */
final class AutopilotAxes {
    static final AutopilotAxes NONE = new AutopilotAxes(new Rate[3], null, 0, FilterSettings.BAR_AT_MINIMUMS);
    private static final int MAX_ENCODED = 4096;
    private static final int MAX_INTEGER_DIGITS = 512;

    /** The latest confirmed personal acceptance which set at least one active, observed rate. */
    final long acceptedAt;
    /**
     * The bar this acceptance set and its next-24-offers protection: that offer's own Autopilot score as seen, held to
     * 10–150 (0.6.40); the shared bar at that acceptance when its score was not known, and for working minimums saved
     * by 0.6.29 to 0.6.39.
     */
    final int referenceBar;
    private final Rate[] rates;
    /** The latest acceptance's whole pay in cents at a bar of 100, set with its rates; null before 0.6.31's first. */
    private final Rate totalPay;

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

        /** This rate times {@code times} ÷ {@code over}, exactly. */
        private Rate scaled(BigInteger times, BigInteger over) {
            return new Rate(numerator.multiply(times), denominator.multiply(over));
        }

        private String encode() { return numerator + "/" + denominator; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Rate)) return false;
            Rate that = (Rate) other;
            return numerator.equals(that.numerator) && denominator.equals(that.denominator);
        }
        @Override public int hashCode() { return Objects.hash(numerator, denominator); }
    }

    private AutopilotAxes(Rate[] rates, Rate totalPay, long acceptedAt, int referenceBar) {
        this.rates = rates.clone();
        this.totalPay = totalPay;
        this.acceptedAt = acceptedAt;
        this.referenceBar = referenceBar;
    }

    boolean isEmpty() { return rates[0] == null && rates[1] == null && rates[2] == null; }
    boolean hasAxis(int axis) { return axis >= 0 && axis < rates.length && rates[axis] != null; }
    Rate rate(int axis) { return hasAxis(axis) ? rates[axis] : null; }
    /** The latest acceptance's whole pay at a bar of 100, or null (none since 0.6.31, or no rates). */
    Rate totalPay() { return isEmpty() ? null : totalPay; }

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
     *
     * @param seenScore the offer's Autopilot score as its ticket showed it, at its decision (0.6.40): the rates are
     *     normalized to it, held to 10–150, and it becomes the reference bar, the bar the commit sets; -1 when it was
     *     not worked out (a needed figure unread), for the bar of the moment, as from 0.6.29. Never scored again here:
     *     by the acceptance, the learner may already have learned from this very offer.
     */
    AutopilotAxes withOffer(FilterSettings rules, OfferSnapshot offer, long at, int seenScore) {
        if (rules == null || !rules.autopilot || at < 0 || offer == null || offer.payCents == null || offer.payCents <= 0
                || offer.payAtMostCents != null || OfferSanity.looksMisread(offer)
                || (rules.maxStops > 0 && offer.stops != null && offer.stops > rules.maxStops)) return this;
        int reference = seenScore < 0 ? rules.minimumScalePercent
                : Math.max(FilterStore.BAR_MIN, Math.min(FilterStore.BAR_MAX, seenScore));
        BigInteger now = BigInteger.valueOf(rules.minimumScalePercent), to = BigInteger.valueOf(reference);
        Rate[] next = rates.clone();
        BigDecimal[] units = {offer.stops == null ? null : BigDecimal.valueOf(offer.stops),
                offer.miles == null ? null : BigDecimal.valueOf(offer.miles),
                offer.minutes == null ? null : BigDecimal.valueOf(offer.minutes)};
        boolean observed = false;
        for (int axis = 0; axis < next.length; axis++) {
            if (!active(rules, axis) || units[axis] == null || units[axis].signum() <= 0) {
                // A rate this offer did not read keeps asking, at the bar this sets, what it asks at today's.
                if (next[axis] != null && reference != rules.minimumScalePercent) {
                    next[axis] = next[axis].scaled(now, to);
                }
                continue;
            }
            next[axis] = Rate.observed(offer.payCents, units[axis], reference);
            observed = true;
        }
        if (!observed) return this;
        Rate whole = Rate.observed(offer.payCents, BigDecimal.ONE, reference);
        AutopilotAxes result = new AutopilotAxes(next, whole, at, reference);
        return equals(result) ? this : result;
    }

    /** Same effective references expressed at another bar, for identifying an old normalization of one acceptance. */
    AutopilotAxes rebased(int bar, long at) {
        if (isEmpty() || bar < FilterStore.BAR_MIN || bar > FilterStore.BAR_MAX) return NONE;
        BigInteger from = BigInteger.valueOf(referenceBar), to = BigInteger.valueOf(bar);
        Rate[] next = rates.clone();
        for (int i = 0; i < next.length; i++) if (next[i] != null) next[i] = next[i].scaled(from, to);
        return new AutopilotAxes(next, totalPay == null ? null : totalPay.scaled(from, to), at, bar);
    }

    /**
     * Local bounded numeric state only, including the latest acceptance's hold metadata. Version 2 (0.6.31) adds the
     * total pay as a seventh field; state without one keeps version 1's exact text.
     */
    String encode() {
        if (isEmpty()) return "";
        StringBuilder text = new StringBuilder(totalPay == null ? "1|" : "2|").append(acceptedAt).append('|').append(referenceBar);
        for (Rate rate : rates) text.append('|').append(rate == null ? "-" : rate.encode());
        if (totalPay != null) text.append('|').append(totalPay.encode());
        return text.toString();
    }

    /** Invalid or incomplete state never creates a rule. No historical records are used to reconstruct it. */
    static AutopilotAxes decode(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED) return NONE;
        try {
            String[] fields = encoded.split("\\|", -1);
            boolean withTotal = fields.length == 7 && "2".equals(fields[0]);
            if (!withTotal && (fields.length != 6 || !"1".equals(fields[0]))) return NONE;
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
            Rate total = null;
            if (withTotal) {
                String[] ratio = fields[6].split("/", -1);
                if (ratio.length != 2 || !positiveInteger(ratio[0]) || !positiveInteger(ratio[1])) return NONE;
                total = new Rate(new BigInteger(ratio[0]), new BigInteger(ratio[1]));
            }
            AutopilotAxes result = new AutopilotAxes(rates, total, at, bar);
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
        return acceptedAt == that.acceptedAt && referenceBar == that.referenceBar && Arrays.equals(rates, that.rates)
                && Objects.equals(totalPay, that.totalPay);
    }
    @Override public int hashCode() { return Objects.hash(Arrays.hashCode(rates), totalPay, acceptedAt, referenceBar); }
}
