package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.List;

/** Saved rules. Every threshold uses zero to mean "disabled". */
final class FilterSettings {
    final boolean enabled;
    final int flatCents;
    final int perMileCents;
    final int perMinuteCents;
    final int extraStopCents;
    final int maxStops;
    final boolean risingOffers;
    final int lastAcceptedCents;
    /** Best accepted pay per minute, mile and stop; floors while the adaptive minimum is on. */
    final AcceptedBest best;

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int extraStopCents, int maxStops) {
        this(enabled, flatCents, perMileCents, perMinuteCents, extraStopCents, maxStops, false, 0);
    }

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int extraStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents) {
        this(enabled, flatCents, perMileCents, perMinuteCents, extraStopCents, maxStops, risingOffers,
                lastAcceptedCents, AcceptedBest.NONE);
    }

    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int extraStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents, AcceptedBest best) {
        this.enabled = enabled;
        this.flatCents = flatCents;
        this.perMileCents = perMileCents;
        this.perMinuteCents = perMinuteCents;
        this.extraStopCents = extraStopCents;
        this.maxStops = maxStops;
        this.risingOffers = risingOffers;
        this.lastAcceptedCents = lastAcceptedCents;
        this.best = best == null ? AcceptedBest.NONE : best;
    }

    /** True when at least one rule can reject or require review of an offer. */
    boolean hasAnyRule() {
        return flatCents > 0 || perMileCents > 0 || perMinuteCents > 0 || extraStopCents > 0
                || maxStops > 0 || risingOffers;
    }

    /** Rules whose cost scales with an add-on's own miles, minutes, or stops. */
    boolean hasMarginalRule() {
        return perMileCents > 0 || perMinuteCents > 0 || extraStopCents > 0;
    }

    /** Plain-language summary of the enabled rules, e.g. "at least $7.00 · $1.50 per mile · at most 3 stops". */
    String describe() {
        List<String> rules = new ArrayList<>();
        if (flatCents > 0) rules.add("at least " + DecisionLog.money(flatCents));
        if (perMileCents > 0) rules.add(DecisionLog.money(perMileCents) + " per mile");
        if (perMinuteCents > 0) rules.add(DecisionLog.money(perMinuteCents) + " per minute");
        if (extraStopCents > 0) rules.add("+" + DecisionLog.money(extraStopCents) + " per stop after 2");
        if (maxStops > 0) rules.add("at most " + maxStops + (maxStops == 1 ? " stop" : " stops"));
        if (risingOffers) {
            rules.add(lastAcceptedCents > 0
                    ? "more than last accepted " + DecisionLog.money(lastAcceptedCents)
                    : "more than your last accepted pay (none yet)");
            if (!best.isEmpty()) rules.add("at least your best accepted " + best.summary());
        }
        return rules.isEmpty() ? "No rules set" : String.join(" · ", rules);
    }

    /** Compact summary for the status line, e.g. "$7 min · $1.50/mi · ≤3 stops". */
    String brief() {
        List<String> rules = new ArrayList<>();
        if (flatCents > 0) rules.add(DecisionLog.shortMoney(flatCents) + " min");
        if (perMileCents > 0) rules.add(DecisionLog.shortMoney(perMileCents) + "/mi");
        if (perMinuteCents > 0) rules.add(DecisionLog.shortMoney(perMinuteCents) + "/min");
        if (extraStopCents > 0) rules.add("+" + DecisionLog.shortMoney(extraStopCents) + "/extra stop");
        if (maxStops > 0) rules.add("≤" + maxStops + (maxStops == 1 ? " stop" : " stops"));
        if (risingOffers) {
            String adaptive = lastAcceptedCents > 0
                    ? "beat " + DecisionLog.shortMoney(lastAcceptedCents) : "beat last accepted";
            rules.add(best.isEmpty() ? adaptive : adaptive + " + best rates");
        }
        return rules.isEmpty() ? "No rules set" : String.join(" · ", rules);
    }

    FilterSettings withEnabled(boolean value) {
        return new FilterSettings(value, flatCents, perMileCents, perMinuteCents, extraStopCents, maxStops,
                risingOffers, lastAcceptedCents, best);
    }

    /** Add-on routes are judged without the adaptive minimum: neither the payout baseline nor the best rates. */
    FilterSettings withoutRisingBaseline() {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, extraStopCents, maxStops,
                false, lastAcceptedCents, best);
    }
}
