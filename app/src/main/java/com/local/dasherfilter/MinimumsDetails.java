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
 * Pay — set $4.00 · used ≈$3.28
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
    static final String LEGACY = "Decided under retired rules (score by area or learned minimums).";
    static final String ADD_ON = "An add-on is judged twice: the whole route against your minimums at the bar, and "
            + "its added pay against what its added miles and minutes ask at the bar (minimum pay does not apply to "
            + "what it adds).";
    static final String BELOW = "Below 100%, offers pass only because Autopilot lowered the bar and are never "
            + "auto-accepted.";
    static final String ROUNDED = "≈ means rounded up to the next cent.";

    private MinimumsDetails() {}

    /** How {@code entry} was judged against {@code rules} (the minimums as they are now). */
    static String describe(FilterSettings rules, DecisionLog.Entry entry) {
        OfferSnapshot facts = entry == null ? OfferSnapshot.UNKNOWN : entry.facts;
        boolean legacy = entry != null && entry.model < DecisionLog.MODEL;
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
            if (rules.flatCents > 0) lines.add(minimum(AreaScore.PAY, rules.flatCents, facts, bar, approximate));
            if (rules.perMileCents > 0) lines.add(minimum(AreaScore.MILE, rules.perMileCents, facts, bar, approximate));
            if (rules.perMinuteCents > 0) {
                lines.add(minimum(AreaScore.MINUTE, rules.perMinuteCents, facts, bar, approximate));
            }
            lines.add(required(rules, facts, bar, approximate));
            if (!legacy && entry != null && entry.requiredCents > 0 && entry.requiredCents < Long.MAX_VALUE) {
                long now = requiredCents(rules, facts, bar);
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

    /** "Pay — set $4.00 · used ≈$3.28", "Per mile — set $6.60 (6.6 mi × $1.00) · used ≈$5.42", or unread. */
    private static String minimum(int axis, int rate, OfferSnapshot facts, int bar, boolean[] approximate) {
        String name = axis == AreaScore.PAY ? "Pay" : axis == AreaScore.MILE ? "Per mile" : "Per hour";
        String unit = axis == AreaScore.MILE ? DecisionLog.money(rate) + " a mile"
                : axis == AreaScore.MINUTE ? perHour(rate) : DecisionLog.money(rate);
        BigDecimal set = AreaScore.fixedFloor(axis, rate, facts);
        if (set == null) {
            return name + " — " + unit + "; " + (axis == AreaScore.MILE ? "miles" : "minutes") + " not read";
        }
        String how = axis == AreaScore.MILE ? " (" + miles(facts.miles) + " mi × " + DecisionLog.money(rate) + ")"
                : axis == AreaScore.MINUTE ? " (" + facts.minutes + " min × " + perHour(rate) + ")" : "";
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
        if (asks.known == null) return "Required: not known until this offer's miles and minutes are read.";
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
        if (required == null) return "needs this offer's miles and minutes to be read.";
        return "needs " + money(AreaScore.roundedCents(required, rules.minimumScalePercent)) + " at "
                + rules.minimumScalePercent + "%.";
    }

    /** "Your rates: $4.00 · $1.00 a mile · $15/hr", the minimums without an offer to apply them to. */
    private static String rates(FilterSettings rules) {
        List<String> parts = new ArrayList<>();
        if (rules.flatCents > 0) parts.add(DecisionLog.money(rules.flatCents));
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
