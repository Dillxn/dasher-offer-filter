package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.List;

/**
 * Saved rules (0.5.0): three money minimums, an optional max stops, and Autopilot's switch, goal and bar. Zero turns a
 * minimum off. An offer passes when its pay reaches {@code ⌈bar × max(flat, per mile × miles, per minute × minutes)
 * ÷ 100⌉} and it has at most {@code maxStops} stops ({@link OfferRule}). Per minute is stored in cents per minute and
 * shown as per hour (× 60); its stored number never changes for the display.
 *
 * <p>The bar ({@link #minimumScalePercent}) is how much of the minimums an offer must pay. The stored rules keep it at
 * exactly 100 while Autopilot is off and within 50–150 while Autopilot moves it; this value class itself accepts 1–200
 * so any bar can be evaluated. Max stops is never scaled.
 *
 * <p>Score by area, the hotspot, per-stop and per-item minimums, and every learned minimum are retired: nothing here
 * holds, sets or reads them (the 0.5.0 migration, {@link FilterStore#migrateToModel2}, folded per stop into minimum pay
 * and built the old scale into the minimums). Their stable axis slots stay reserved ({@link #minimums}).
 */
final class FilterSettings {
    /** The most any minimum can be set to on its knob: $1,000 (in cents; a rate's cents per unit). */
    static final int MOST_CENTS = 100_000;
    /** Autopilot's acceptance-rate goals in whole percent: keep a top tier, keep a tier, pay first. */
    static final int GOAL_TOP_TIER = 70;
    static final int GOAL_TIER = 50;
    static final int GOAL_PAY_FIRST = 0;
    /** The bar that means "exactly your minimums". */
    static final int BAR_AT_MINIMUMS = 100;

    final boolean enabled;
    /** Minimum pay, in cents. */
    final int flatCents;
    /** Minimum pay per mile, in cents per mile. */
    final int perMileCents;
    /** Minimum pay per minute of Dasher's own time estimate, in cents per minute; shown × 60 as per hour. */
    final int perMinuteCents;
    /** At most this many stops; 0 is no limit. A hard limit the bar never scales. */
    final int maxStops;
    /** Autopilot on: only Autopilot moves the bar, between offers. */
    final boolean autopilot;
    /** Autopilot's acceptance-rate goal: 70, 50 or 0 (pay first). */
    final int autopilotGoalPercent;
    /** The bar: how much of the minimums an offer must pay, in percent (clamped 1–200; 100 is exactly them). */
    final int minimumScalePercent;

    /**
     * The rules. Money minimums are held to [0, {@link #MOST_CENTS}], max stops to 0 or more, the goal to 70, 50 or 0
     * (anything else is 70) and the bar to 1–200.
     */
    FilterSettings(boolean enabled, int flatCents, int perMileCents, int perMinuteCents, int maxStops,
                   boolean autopilot, int autopilotGoalPercent, int minimumScalePercent) {
        this.enabled = enabled;
        this.flatCents = money(flatCents);
        this.perMileCents = money(perMileCents);
        this.perMinuteCents = money(perMinuteCents);
        this.maxStops = Math.max(0, maxStops);
        this.autopilot = autopilot;
        this.autopilotGoalPercent = sanitizedGoal(autopilotGoalPercent);
        this.minimumScalePercent = Math.max(1, Math.min(200, minimumScalePercent));
    }

    /** Rules with Autopilot off, its goal at the default 70% and the bar at exactly the minimums. */
    static FilterSettings of(boolean enabled, int flatCents, int perMileCents, int perMinuteCents, int maxStops) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, maxStops, false, GOAL_TOP_TIER,
                BAR_AT_MINIMUMS);
    }

    /** 70, 50 and 0 (pay first) stand; anything else is the default, 70. */
    static int sanitizedGoal(int percent) {
        return percent == GOAL_TOP_TIER || percent == GOAL_TIER || percent == GOAL_PAY_FIRST ? percent : GOAL_TOP_TIER;
    }

    private static int money(int cents) {
        return Math.max(0, Math.min(MOST_CENTS, cents));
    }

    /** True when at least one rule can reject an offer or leave it for review: a money minimum or max stops. */
    boolean hasAnyRule() {
        return hasMonetaryRule() || maxStops > 0;
    }

    /** A pay, per-mile or per-hour minimum is set. Autopilot and auto-accept need one. */
    boolean hasMonetaryRule() {
        return flatCents > 0 || perMileCents > 0 || perMinuteCents > 0;
    }

    /** A rate minimum is set, which prices what an add-on adds (its miles and minutes). */
    boolean hasMarginalRule() {
        return perMileCents > 0 || perMinuteCents > 0;
    }

    /** Per minute shown as per hour: cents per minute × 60. */
    long perHourCents() {
        return perMinuteCents * 60L;
    }

    /**
     * Plain-language summary, e.g. "at least $4.00 · $1.00 per mile · $15.00 per hour · at most 3 stops · Autopilot
     * bar 82% (goal 70%)"; "No rules set" without a rule.
     */
    String describe() {
        List<String> rules = new ArrayList<>();
        if (flatCents > 0) rules.add("at least " + DecisionLog.money(flatCents));
        if (perMileCents > 0) rules.add(DecisionLog.money(perMileCents) + " per mile");
        if (perMinuteCents > 0) rules.add(DecisionLog.money(perHourCents()) + " per hour");
        if (maxStops > 0) rules.add("at most " + maxStops + (maxStops == 1 ? " stop" : " stops"));
        if (rules.isEmpty()) return "No rules set";
        if (autopilot) {
            rules.add("Autopilot bar " + minimumScalePercent + "% ("
                    + (autopilotGoalPercent == GOAL_PAY_FIRST ? "pay first" : "goal " + autopilotGoalPercent + "%")
                    + ")");
        } else if (minimumScalePercent != BAR_AT_MINIMUMS) {
            rules.add("bar " + minimumScalePercent + "%");
        }
        return String.join(" · ", rules);
    }

    /** Compact summary for the status line, e.g. "$4 min · $1/mi · $15/hr · ≤3 stops · Auto 82%". */
    String brief() {
        List<String> rules = new ArrayList<>();
        if (flatCents > 0) rules.add(DecisionLog.shortMoney(flatCents) + " min");
        if (perMileCents > 0) rules.add(DecisionLog.shortMoney(perMileCents) + "/mi");
        if (perMinuteCents > 0) rules.add(DecisionLog.shortMoney(perHourCents()) + "/hr");
        if (maxStops > 0) rules.add("≤" + maxStops + (maxStops == 1 ? " stop" : " stops"));
        if (rules.isEmpty()) return "No rules set";
        if (autopilot) rules.add("Auto " + minimumScalePercent + "%");
        else if (minimumScalePercent != BAR_AT_MINIMUMS) rules.add("bar " + minimumScalePercent + "%");
        return String.join(" · ", rules);
    }

    /**
     * What a plan made from these rules depends on: everything but the bar, which is the plan's own output
     * ("enabled:flat:mile:minute:maxStops:autopilot:goal").
     */
    String rulesKey() {
        return enabled + ":" + flatCents + ":" + perMileCents + ":" + perMinuteCents + ":" + maxStops + ":"
                + autopilot + ":" + autopilotGoalPercent;
    }

    FilterSettings withEnabled(boolean value) {
        return new FilterSettings(value, flatCents, perMileCents, perMinuteCents, maxStops, autopilot,
                autopilotGoalPercent, minimumScalePercent);
    }

    /** These rules with at most {@code stops} stops (0: no limit); nothing else changes. */
    FilterSettings withMaxStops(int stops) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, stops, autopilot,
                autopilotGoalPercent, minimumScalePercent);
    }

    /** These rules at another bar (clamped 1–200); the minimums themselves never change. */
    FilterSettings withMinimumScalePercent(int percent) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, maxStops, autopilot,
                autopilotGoalPercent, percent);
    }

    /**
     * Autopilot on or off with {@code goal} (70, 50 or 0). Turning it off puts the bar back at exactly the minimums;
     * turning it on keeps the bar until Autopilot moves it.
     */
    FilterSettings withAutopilot(boolean on, int goal) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, maxStops, on, goal,
                on ? minimumScalePercent : BAR_AT_MINIMUMS);
    }

    /** The three money minimums replaced; max stops, the switch, Autopilot and the bar stay. */
    FilterSettings withMinimums(int flat, int mile, int minute) {
        return new FilterSettings(enabled, flat, mile, minute, maxStops, autopilot, autopilotGoalPercent,
                minimumScalePercent);
    }

    /** The set minimums by stable axis index: pay, per mile, per minute, then 0 for the retired stop, hotspot, item. */
    int[] minimums() {
        return new int[] {flatCents, perMileCents, perMinuteCents, 0, 0, 0};
    }

    /**
     * Replace the minimums in {@link #minimums} order. The retired slots (3 per stop, 4 hotspot, 5 per item) must be 0
     * when present: {@code IllegalArgumentException("retired rule: …")} otherwise, never a silent change of meaning.
     */
    FilterSettings withMinimums(int[] cents) {
        String[] retired = {"per stop", "hotspot", "per item"};
        for (int slot = 3; slot < cents.length; slot++) {
            if (cents[slot] != 0) {
                throw new IllegalArgumentException("retired rule: "
                        + (slot - 3 < retired.length ? retired[slot - 3] : "slot " + slot));
            }
        }
        return withMinimums(cents[0], cents[1], cents[2]);
    }
}
