package com.local.dasherfilter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The ticket's "How this offer was judged": each minimum as this offer's own miles and minutes made it, and what the
 * bar used, e.g.
 *
 * <pre>
 * Your minimums · bar 82% (Autopilot)
 * Pay per stop — set $4.00 · used ≈$3.28
 * Per mile — set $6.60 (6.6 mi × $1.00) · used ≈$5.42
 * Per hour — set $6.75 (27 min × $15/hr) · used ≈$5.54
 * Required ≈$5.54: 82% of the highest set amount ($6.75) · score 85%
 * Max stops 3 (never scaled). Below 100%, offers pass only because Autopilot lowered the bar and are never
 * auto-accepted. ≈ means rounded up to the next cent.
 * </pre>
 *
 * Worked out from the minimums as they are now (a line does not keep them) at the bar the line recorded; where the pay
 * it needed then differs, it says so. A line an older version decided (score by area, learned minimums, the minimums
 * scale) says that first, with what it needs now, and is then worked out at the current bar.
 */
final class MinimumsDetails {
    static final String LEGACY = "Decided under earlier area or learned rules.";
    static final String ADD_ON = "An add-on is judged twice: the whole route against your minimums at the bar, and "
            + "its added pay against what its added miles and minutes ask at the bar (pay per stop does not apply to "
            + "what it adds).";
    static final String BELOW = "Below 100%, offers pass only because Autopilot lowered the bar and are never "
            + "auto-accepted.";
    static final String ROUNDED = "≈ means rounded up to the next cent.";
    /**
     * A "+$" offer, its pay unread beside a "+$" amount, is declined only when even the most it can pay misses the
     * minimums at min(bar, 100) (OfferRule): Autopilot's bar above 100% never makes that stricter.
     */
    static final String CEILING = "Its pay was not read, only the most it can pay with its +$ amount: that is judged "
            + "at exactly your minimums, never at a bar above 100%.";

    private MinimumsDetails() {}

    /** How {@code entry} was judged against {@code rules} (the minimums as they are now). */
    static String describe(FilterSettings rules, DecisionLog.Entry entry) {
        OfferSnapshot facts = entry == null ? OfferSnapshot.UNKNOWN : entry.facts;
        FilterSettings recorded = entry == null ? null : entry.scoringRules;
        if (rules.scoreByArea || rules.acceptanceLearning || !rules.accepted.isEmpty()
                || recorded != null) {
            return resolvedDescription(recorded == null ? rules : recorded, entry, facts, recorded != null);
        }
        boolean legacy = entry != null && entry.model < 2;
        int bar = entry == null || legacy ? rules.minimumScalePercent : entry.barPercent;
        boolean autopilot = entry == null || legacy ? rules.autopilot : entry.autopilot;
        List<String> lines = new ArrayList<>();
        if (legacy) lines.add(LEGACY + " Now: " + needsNow(rules, facts));
        lines.add("Your minimums · bar " + bar + "%" + (autopilot ? " (Autopilot)" : ""));
        boolean[] approximate = {false};
        if (entry != null && entry.addOn) {
            lines.add(rates(rules));
            lines.add(ADD_ON);
        } else {
            // A "+$" ceiling's decline was judged at min(bar, 100): worked out, and compared, at that bar.
            boolean ceiling = !legacy && entry != null && entry.reason != null
                    && entry.reason.startsWith("pay at most ") && bar > FilterSettings.BAR_AT_MINIMUMS;
            int judged = ceiling ? FilterSettings.BAR_AT_MINIMUMS : bar;
            if (ceiling) lines.add(CEILING);
            if (rules.flatCents > 0) lines.add(minimum(AreaScore.PAY, rules.flatCents, facts, judged, approximate));
            if (rules.perMileCents > 0) {
                lines.add(minimum(AreaScore.MILE, rules.perMileCents, facts, judged, approximate));
            }
            if (rules.perMinuteCents > 0) {
                lines.add(minimum(AreaScore.MINUTE, rules.perMinuteCents, facts, judged, approximate));
            }
            lines.add(required(rules, facts, judged, approximate));
            if (!legacy && entry != null && entry.requiredCents > 0 && entry.requiredCents < Long.MAX_VALUE) {
                long now = requiredCents(rules, facts, judged);
                if (now != entry.requiredCents) {
                    lines.add("When decided it needed " + DecisionLog.money(entry.requiredCents)
                            + "; your minimums have changed since.");
                }
            }
        }
        List<String> notes = new ArrayList<>();
        if (rules.maxStops > 0) notes.add("Max stops " + rules.maxStops + " (never scaled).");
        if (bar < FilterSettings.BAR_AT_MINIMUMS) notes.add(BELOW);
        if (approximate[0]) notes.add(ROUNDED);
        if (!notes.isEmpty()) lines.add(String.join(" ", notes));
        return String.join("\n", lines);
    }

    /** On-demand local bests; exact numeric pairs avoid pretending a rounded rate is the learned rule. */
    static String learningSummary(FilterSettings rules) {
        AcceptedFloors best = rules.accepted;
        if (best.isEmpty()) return "No learned bests yet. Saved minimums are unchanged.";
        List<String> values = new ArrayList<>();
        if (best.highestPayCents > 0) values.add(best.payUnits == 1
                ? "Best pay per stop " + DecisionLog.money(best.highestPayCents) + " (learned pay asks one cent more)"
                : "Best pay per stop: " + DecisionLog.money(best.highestPayCents) + " for " + best.payUnits
                        + " stops, the pickup included (learned pay asks one cent more)");
        if (best.hasPerMile()) values.add("Best mileage rate: " + DecisionLog.money(best.milePay)
                + " for " + miles(best.miles) + " mi");
        if (best.hasPerMinute()) values.add("Best time rate: " + DecisionLog.money(best.minutePay)
                + " for " + best.minutes + " min");
        return String.join("\n", values) + (rules.acceptanceLearning ? "\nLearning is on."
                : "\nStored bests are off and do not affect offers.");
    }

    private static String resolvedDescription(FilterSettings rules, DecisionLog.Entry entry, OfferSnapshot facts,
                                               boolean recorded) {
        List<String> lines = new ArrayList<>();
        if (entry != null) {
            String kind = entry.model < 2 ? "earlier area rules" : entry.model == 2 ? "strict rules"
                    : "area".equals(entry.scoreKind) ? "area rules" : "strict rules";
            lines.add("Recorded decision: " + kind + (entry.scorePercent >= 0
                    ? ", score " + entry.scorePercent + "%" : ", score unread") + ", bar " + entry.barPercent + "%.");
        }
        lines.add((recorded ? "Recorded" : "Current") + " requirements · " + (rules.scoreByArea ? "Area" : "Strict")
                + " · bar " + rules.minimumScalePercent + "%" + (rules.autopilot ? " (Autopilot)" : ""));
        if (entry != null && entry.addOn) {
            lines.add(rates(rules));
            lines.add(ADD_ON + " Learned standalone bests do not apply to add-ons.");
            return String.join("\n", lines);
        }
        AreaScore.Floors floors = AreaScore.floors(rules, facts);
        boolean ceiling = entry != null && entry.reason != null && entry.reason.startsWith("pay at most ");
        int judgedBar = ceiling ? Math.min(rules.minimumScalePercent, FilterSettings.BAR_AT_MINIMUMS)
                : rules.minimumScalePercent;
        if (ceiling) lines.add("Its +$ amount is only a pay ceiling, not readable pay. Judged at " + judgedBar
                + "%, never above 100%.");
        boolean[] approximate = {false};
        for (int axis : new int[] {AreaScore.PAY, AreaScore.MILE, AreaScore.MINUTE}) {
            if (!floors.active[axis] && floors.acceptedCents[axis] == null) continue;
            String name = axis == AreaScore.PAY ? "Pay per stop" : axis == AreaScore.MILE ? "Per mile" : "Per hour";
            lines.add(name + " — saved " + projection(floors.fixedCents[axis], approximate)
                    + " · learned " + projection(floors.acceptedCents[axis], approximate)
                    + (rules.acceptanceLearning ? "" : " (off)")
                    + " · resolved " + projection(floors.effectiveCents[axis], approximate));
        }
        long required = AreaScore.requiredPay(rules, facts, judgedBar);
        lines.add(required < 0 ? rules.scoreByArea && floors.degenerateArea()
                ? "Area fitness cannot be formed from a zero route amount across multiple active measures; review it."
                : "Required pay is unknown until every active measure (miles, minutes, stops) is read."
                : "Required " + money(required) + " under the " + (recorded ? "recorded " : "current ")
                        + (rules.scoreByArea ? "area" : "strict")
                        + " model at " + judgedBar + "% (one final cent rounding).");
        if (rules.scoreByArea) lines.add("Stronger measures can compensate for weaker ones; no individual money "
                + "minimum is a hard floor in area mode. Max stops stays hard.");
        else lines.add("Every active resolved minimum must be met at the bar.");
        lines.add("Permitted by the filter does not mean accepted. Automatic acceptance separately requires strict "
                + "saved and enabled learned minimums at 100% or any higher bar. Add-ons use strict saved minimums "
                + "at the current bar; learned standalone bests do not apply until adopted as saved minimums.");
        if (rules.maxStops > 0) lines.add("Max stops " + rules.maxStops + " (never scaled).");
        if (approximate[0]) lines.add(ROUNDED);
        return String.join("\n", lines);
    }

    private static String projection(BigDecimal cents, boolean[] approximate) {
        return cents == null ? "off or unread" : amount(cents, approximate);
    }

    /**
     * "Pay per stop — set $15.00 (3 stops: 3 × $5.00) · used ≈$12.30", "Per mile — set
     * $6.60 (6.6 mi × $1.00) · used ≈$5.42", or unread ("Pay per stop — $13.00 a stop; stops not read (at least
     * $13.00)").
     */
    private static String minimum(int axis, int rate, OfferSnapshot facts, int bar, boolean[] approximate) {
        String name = axis == AreaScore.PAY ? "Pay per stop" : axis == AreaScore.MILE ? "Per mile" : "Per hour";
        String unit = axis == AreaScore.MILE ? DecisionLog.money(rate) + " a mile"
                : axis == AreaScore.MINUTE ? perHour(rate) : DecisionLog.money(rate) + " a stop";
        BigDecimal set = AreaScore.fixedFloor(axis, rate, facts);
        if (set == null) {
            return name + " — " + unit + "; " + (axis == AreaScore.MILE ? "miles" : axis == AreaScore.MINUTE
                    ? "minutes" : "stops") + " not read" + (axis == AreaScore.PAY ? " (at least "
                    + DecisionLog.money(rate) + ")" : "");
        }
        String how = axis == AreaScore.MILE ? " (" + miles(facts.miles) + " mi × " + DecisionLog.money(rate) + ")"
                : axis == AreaScore.MINUTE ? " (" + facts.minutes + " min × " + perHour(rate) + ")"
                : AreaScore.payUnits(facts.stops) == 1 ? "" : " (" + facts.stops + " stops: "
                        + AreaScore.payUnits(facts.stops) + " × " + DecisionLog.money(rate) + ")";
        String line = name + " — set " + amount(set, approximate) + how;
        if (bar == FilterSettings.BAR_AT_MINIMUMS) return line;
        approximate[0] = true;
        return line + " · used ≈" + money(AreaScore.roundedCents(set, bar));
    }

    /**
     * "Required ≈$5.54: 82% of the highest set amount ($6.75) · score 85%"; at 100%, "Required $6.75: the highest set
     * amount · score 85%"; a floor only ("at least") while a minimum's miles or minutes were not read.
     */
    private static String required(FilterSettings rules, OfferSnapshot facts, int bar, boolean[] approximate) {
        if (!rules.hasMonetaryRule()) return "No pay, per-mile or hourly minimum is set.";
        AreaScore.Asks asks = AreaScore.asks(rules, facts);
        if (asks.known == null) return "Required: not known until this offer's " + unread(rules) + " are read.";
        String head = "Required " + (asks.missing ? "at least " : "");
        String score = "";
        int percent = AreaScore.scorePercent(rules, facts);
        if (percent >= 0) score = " · score " + percent + "%";
        if (bar == FilterSettings.BAR_AT_MINIMUMS) {
            return head + amount(asks.known, approximate) + ": the highest set amount" + score;
        }
        approximate[0] = true;
        return head + "≈" + money(AreaScore.roundedCents(asks.known, bar)) + ": " + bar
                + "% of the highest set amount (" + amount(asks.known, approximate) + ")" + score;
    }

    /** The pay the minimums ask of {@code facts} at {@code bar}, as a decision rounds it; 0 when none. */
    private static long requiredCents(FilterSettings rules, OfferSnapshot facts, int bar) {
        BigDecimal known = AreaScore.knownRequired100(rules, facts);
        return known == null ? 0 : AreaScore.roundedCents(known, bar);
    }

    /** What a line an older version decided needs under the minimums and the bar now. */
    private static String needsNow(FilterSettings rules, OfferSnapshot facts) {
        if (!rules.hasMonetaryRule()) return "no pay, per-mile or hourly minimum is set.";
        BigDecimal required = AreaScore.required100(rules, facts);
        if (required == null) return "needs this offer's " + unread(rules) + " to be read.";
        return "needs " + money(AreaScore.roundedCents(required, rules.minimumScalePercent)) + " at "
                + rules.minimumScalePercent + "%.";
    }

    /** What a requirement can wait for: "miles and minutes", and the stops with pay per stop set. */
    private static String unread(FilterSettings rules) {
        return rules.flatCents > 0 ? "miles, minutes and stops" : "miles and minutes";
    }

    /** "Your rates: $4.00 a stop · $1.00 a mile · $15/hr", the minimums without an offer to apply them to. */
    private static String rates(FilterSettings rules) {
        List<String> parts = new ArrayList<>();
        if (rules.flatCents > 0) parts.add(DecisionLog.money(rules.flatCents) + " a stop");
        if (rules.perMileCents > 0) parts.add(DecisionLog.money(rules.perMileCents) + " a mile");
        if (rules.perMinuteCents > 0) parts.add(perHour(rules.perMinuteCents));
        return parts.isEmpty() ? "No pay, per-mile or hourly minimum is set." : "Your rates: " + String.join(" · ", parts);
    }

    /** "$15/hr" for 25 cents a minute. */
    private static String perHour(int centsPerMinute) {
        return DecisionLog.shortMoney(centsPerMinute * 60L) + "/hr";
    }

    private static String miles(Double miles) {
        if (miles == null) return "?";
        return BigDecimal.valueOf(miles).stripTrailingZeros().toPlainString();
    }

    /** "$6.60", or "≈$7.56" where the exact amount is not a whole cent (rounded up), noted in {@code approximate}. */
    private static String amount(BigDecimal exactCents, boolean[] approximate) {
        long rounded = AreaScore.roundedCents(exactCents, FilterSettings.BAR_AT_MINIMUMS);
        if (rounded == Long.MAX_VALUE) return "out of range";
        if (exactCents.compareTo(BigDecimal.valueOf(rounded)) == 0) return money(rounded);
        approximate[0] = true;
        return "≈" + money(rounded);
    }

    private static String money(long cents) {
        return cents == Long.MAX_VALUE ? "out of range" : DecisionLog.money(cents);
    }
}
