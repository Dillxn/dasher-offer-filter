package com.local.dasherfilter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Saved rules. Every threshold uses zero to mean "disabled". */
final class FilterSettings {
    final boolean enabled;
    final int flatCents;
    final int perMileCents;
    final int perMinuteCents;
    /** Minimum pay per stop, a floor like per mile and per minute: an offer needs at least stops × this. */
    final int perStopCents;
    final int maxStops;
    final boolean risingOffers;
    /** The highest standalone pay accepted while learning (the adaptive pay minimum); only Reset lowers it. */
    final int lastAcceptedCents;
    /** Best accepted pay per minute, mile and stop; floors while the adaptive minimum is on. */
    final AcceptedBest best;
    /** What offers declined by hand taught the adaptive minimum: values later offers must beat. */
    final DeclinedFloor declined;
    /**
     * Score by area, the user's choice: a standalone offer passes when its area score ({@link AreaScore}) reaches
     * 100%, rather than when it meets every minimum. Max stops stays a hard limit, and add-ons keep the strict rules.
     */
    final boolean scoreByArea;

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops) {
        this(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, false, 0);
    }

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents) {
        this(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, risingOffers,
                lastAcceptedCents, AcceptedBest.NONE);
    }

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents, AcceptedBest best) {
        this(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, risingOffers,
                lastAcceptedCents, best, DeclinedFloor.NONE);
    }

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents, AcceptedBest best, DeclinedFloor declined) {
        this(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, risingOffers,
                lastAcceptedCents, best, declined, false);
    }

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents, AcceptedBest best, DeclinedFloor declined,
                   boolean scoreByArea) {
        this.enabled = enabled;
        this.flatCents = flatCents;
        this.perMileCents = perMileCents;
        this.perMinuteCents = perMinuteCents;
        this.perStopCents = perStopCents;
        this.maxStops = maxStops;
        this.risingOffers = risingOffers;
        this.lastAcceptedCents = lastAcceptedCents;
        this.best = best == null ? AcceptedBest.NONE : best;
        this.declined = declined == null ? DeclinedFloor.NONE : declined;
        this.scoreByArea = scoreByArea;
    }

    /** True when at least one rule can reject or require review of an offer. */
    boolean hasAnyRule() {
        return flatCents > 0 || perMileCents > 0 || perMinuteCents > 0 || perStopCents > 0
                || maxStops > 0 || risingOffers;
    }

    /** Rules whose cost scales with an add-on's own miles, minutes, or stops. */
    boolean hasMarginalRule() {
        return perMileCents > 0 || perMinuteCents > 0 || perStopCents > 0;
    }

    /**
     * Plain-language summary of the enabled rules, e.g. "at least $7.00 · $1.50 per mile · at most 3 stops"; by area,
     * "scored by area, 100% needed (max stops is a hard limit) · $7.00 pay · $1.50 per mile · at most 3 stops".
     */
    String describe() {
        List<String> rules = new ArrayList<>();
        // By area no single minimum is a floor of its own, so pay reads like the others.
        if (flatCents > 0) rules.add(scoreByArea ? DecisionLog.money(flatCents) + " pay"
                : "at least " + DecisionLog.money(flatCents));
        if (perMileCents > 0) rules.add(DecisionLog.money(perMileCents) + " per mile");
        if (perMinuteCents > 0) rules.add(DecisionLog.money(perMinuteCents) + " per minute");
        if (perStopCents > 0) rules.add(DecisionLog.money(perStopCents) + " per stop");
        if (maxStops > 0) rules.add("at most " + maxStops + (maxStops == 1 ? " stop" : " stops"));
        if (risingOffers) {
            rules.add(lastAcceptedCents > 0
                    ? "more than highest accepted " + DecisionLog.money(lastAcceptedCents)
                    : "more than your highest accepted pay (none yet)");
            if (!best.isEmpty()) rules.add("at least your best accepted " + best.summary());
            if (!declined.isEmpty()) rules.add("more than you declined by hand: " + declined.summary());
        }
        if (scoreByArea && !rules.isEmpty()) {
            rules.add(0, "scored by area, 100% needed" + (maxStops > 0 ? " (max stops is a hard limit)" : ""));
        }
        return rules.isEmpty() ? "No rules set" : String.join(" · ", rules);
    }

    /** Compact summary for the status line, e.g. "$7 min · $1.50/mi · ≤3 stops". */
    String brief() {
        List<String> rules = new ArrayList<>();
        if (flatCents > 0) rules.add(DecisionLog.shortMoney(flatCents) + " min");
        if (perMileCents > 0) rules.add(DecisionLog.shortMoney(perMileCents) + "/mi");
        if (perMinuteCents > 0) rules.add(DecisionLog.shortMoney(perMinuteCents) + "/min");
        if (perStopCents > 0) rules.add(DecisionLog.shortMoney(perStopCents) + "/stop");
        if (maxStops > 0) rules.add("≤" + maxStops + (maxStops == 1 ? " stop" : " stops"));
        if (risingOffers) {
            String adaptive = lastAcceptedCents > 0
                    ? "beat " + DecisionLog.shortMoney(lastAcceptedCents) : "beat highest accepted";
            rules.add(best.isEmpty() && declined.isEmpty() ? adaptive : adaptive + " + learned rates");
        }
        if (scoreByArea && !rules.isEmpty()) rules.add(0, "by area");
        return rules.isEmpty() ? "No rules set" : String.join(" · ", rules);
    }

    FilterSettings withEnabled(boolean value) {
        return new FilterSettings(value, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops,
                risingOffers, lastAcceptedCents, best, declined, scoreByArea);
    }

    /** These rules decided by area score ({@code on}) or by every minimum; nothing else changes. */
    FilterSettings withScoreByArea(boolean on) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops,
                risingOffers, lastAcceptedCents, best, declined, on);
    }

    /** These rules with at most {@code stops} stops (0: no limit); nothing else changes. */
    FilterSettings withMaxStops(int stops) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, Math.max(0, stops),
                risingOffers, lastAcceptedCents, best, declined, scoreByArea);
    }

    /**
     * These rules with the adaptive minimum on or off; what it learned is kept either way (only Reset forgets it), and
     * nothing else changes.
     */
    FilterSettings withAdaptive(boolean on) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, on,
                lastAcceptedCents, best, declined, scoreByArea);
    }

    /** The four set minimums in the constellation's spoke order: pay, per mile, per minute, per stop. */
    int[] minimums() {
        return new int[] {flatCents, perMileCents, perMinuteCents, perStopCents};
    }

    /** These rules with the four set minimums ({@link #minimums} order) replaced, and nothing else changed. */
    FilterSettings withMinimums(int[] cents) {
        return new FilterSettings(enabled, cents[0], cents[1], cents[2], cents[3], maxStops, risingOffers,
                lastAcceptedCents, best, declined, scoreByArea);
    }

    /**
     * These rules with each set minimum raised to what the adaptive minimum on the same measure asks, so the set
     * minimums alone are never looser than the adaptive ones, for an offer of any length. Pay: one cent above the
     * highest accepted payout and above the payout declined by hand. Per mile, minute and stop: the smallest whole-cent
     * rate that asks at least as much as the best accepted rate (which an offer must match, rounded up), so
     * {@code ceil(pay ÷ amount)}, and more than the declined rate (which an offer must beat), so
     * {@code floor(pay ÷ amount) + 1}. Worked exactly, never in floating point.
     *
     * <p>A set minimum is never lowered, a measure with nothing learned is left as it is, and nothing else changes:
     * the on or paused state, max stops, the adaptive minimum and everything it learned stay as they are (it goes on
     * rising, until Reset). Each is held to the most a knob can be set to, {@link #MOST_CENTS}.
     */
    FilterSettings adoptAdaptive() {
        long pay = 0;
        if (lastAcceptedCents > 0) pay = lastAcceptedCents + 1L;
        if (declined.payCents > 0) pay = Math.max(pay, declined.beatPay());
        long mile = 0;
        if (best.hasPerMile()) mile = matched(best.milePay, miles(best.miles));
        if (declined.rates.hasPerMile()) {
            mile = Math.max(mile, beaten(declined.rates.milePay, miles(declined.rates.miles)));
        }
        long minute = 0;
        if (best.hasPerMinute()) minute = matched(best.minutePay, BigDecimal.valueOf(best.minutes));
        if (declined.rates.hasPerMinute()) {
            minute = Math.max(minute, beaten(declined.rates.minutePay, BigDecimal.valueOf(declined.rates.minutes)));
        }
        long stop = 0;
        if (best.hasPerStop()) stop = matched(best.stopPay, BigDecimal.valueOf(best.stops));
        if (declined.rates.hasPerStop()) {
            stop = Math.max(stop, beaten(declined.rates.stopPay, BigDecimal.valueOf(declined.rates.stops)));
        }
        return withMinimums(new int[] {raised(flatCents, pay), raised(perMileCents, mile),
                raised(perMinuteCents, minute), raised(perStopCents, stop)});
    }

    /** The most any minimum can be set to on its knob: $1,000 (in cents; a rate's cents per unit). */
    static final int MOST_CENTS = 100_000;

    /** A set minimum raised to {@code asks} (0 when nothing was learned), held to {@link #MOST_CENTS}; never lower. */
    private static int raised(int set, long asks) {
        if (asks <= 0) return set;
        return (int) Math.max(set, Math.min(MOST_CENTS, asks));
    }

    /** Miles as the adaptive minimums compare them (their decimal form); null when not a usable amount. */
    private static BigDecimal miles(double miles) {
        return Double.isFinite(miles) && miles > 0 ? BigDecimal.valueOf(miles) : null;
    }

    /** The smallest whole-cent rate never below {@code pay ÷ amount}: {@code ceil(pay ÷ amount)}; saturates. */
    private static long matched(int pay, BigDecimal amount) {
        return perUnit(pay, amount, RoundingMode.CEILING, 0);
    }

    /** The smallest whole-cent rate always above {@code pay ÷ amount}: {@code floor(pay ÷ amount) + 1}; saturates. */
    private static long beaten(int pay, BigDecimal amount) {
        return perUnit(pay, amount, RoundingMode.FLOOR, 1);
    }

    private static long perUnit(int pay, BigDecimal amount, RoundingMode rounding, int plus) {
        if (pay <= 0 || amount == null || amount.signum() <= 0) return 0;
        try {
            long rate = BigDecimal.valueOf(pay).divide(amount, 0, rounding).longValueExact();
            return rate > Long.MAX_VALUE - plus ? Long.MAX_VALUE : rate + plus;
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Without the adaptive minimum (no payout baseline, best rates or decline floors): how add-on routes are judged,
     * and all that a bound on unknown pay is judged by. Score by area is kept; add-ons ask for the strict rules
     * themselves.
     */
    FilterSettings withoutRisingBaseline() {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops,
                false, lastAcceptedCents, best, declined, scoreByArea);
    }
}
