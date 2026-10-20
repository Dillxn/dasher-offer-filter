package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.List;

/** Immutable saved minima, selected standalone model, accepted proof, Autopilot working rates and shared bar.
 * Only pay, mile and minute participate. Saved knobs remain separate from accepted proof; the bar is applied once
 * after their exact resolved requirement. The retired axis slots stay reserved and max stops remains hard. */
final class FilterSettings {
    /** The most any minimum can be set to on its knob: $1,000 (in cents; a rate's cents per unit). */
    static final int MOST_CENTS = 100_000;
    /**
     * Autopilot's goals in whole percent: pay first (the default from 0.6.18; the owner, 14 October 2026: Autopilot's
     * goal "is to arm wrestle with the dispatch algorithm to ratchet up earnings"), and the opt-in goals that protect
     * the acceptance rate, 70% (a top tier) and 50% (a tier).
     */
    static final int GOAL_TOP_TIER = 70;
    static final int GOAL_TIER = 50;
    static final int GOAL_PAY_FIRST = 0;
    /** The goal a new install has, and the one anything unknown reads as: pay first (0.6.18; 70% before). */
    static final int GOAL_DEFAULT = GOAL_PAY_FIRST;
    /** The bar that means "exactly your minimums". */
    static final int BAR_AT_MINIMUMS = 100;

    final boolean scoreByArea;
    final boolean acceptanceLearning;
    final AcceptedFloors accepted;
    /** Independent working rates, ignored while Autopilot is off or their baseline axis is disabled. */
    final AutopilotAxes autopilotAxes;
    final boolean enabled;
    /**
     * Minimum pay per stop, in cents (each stop, the pickup included, {@link AreaScore#payUnits}; 0.6.12, the pickup
     * counted from 0.6.14). The field and its prefs key keep their old names ("flat"), and the saved number never
     * changed: a single order (Dasher's two stops) asks it twice, three stops three times.
     */
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
     * (anything else is the default, pay first) and the bar to 1–200.
     */
    FilterSettings(boolean enabled, int flatCents, int perMileCents, int perMinuteCents, int maxStops,
                   boolean autopilot, int autopilotGoalPercent, int minimumScalePercent) {
        this(enabled, flatCents, perMileCents, perMinuteCents, maxStops, autopilot, autopilotGoalPercent,
                minimumScalePercent, false, false, AcceptedFloors.NONE);
    }

    FilterSettings(boolean enabled, int flatCents, int perMileCents, int perMinuteCents, int maxStops,
                   boolean autopilot, int autopilotGoalPercent, int minimumScalePercent,
                   boolean scoreByArea, boolean acceptanceLearning, AcceptedFloors accepted) {
        this(enabled, flatCents, perMileCents, perMinuteCents, maxStops, autopilot, autopilotGoalPercent,
                minimumScalePercent, scoreByArea, acceptanceLearning, accepted, AutopilotAxes.NONE);
    }

    FilterSettings(boolean enabled, int flatCents, int perMileCents, int perMinuteCents, int maxStops,
                   boolean autopilot, int autopilotGoalPercent, int minimumScalePercent,
                   boolean scoreByArea, boolean acceptanceLearning, AcceptedFloors accepted, AutopilotAxes axes) {
        this.autopilotAxes = axes == null ? AutopilotAxes.NONE : axes;
        this.scoreByArea = scoreByArea;
        this.acceptanceLearning = acceptanceLearning;
        this.accepted = accepted == null ? AcceptedFloors.NONE : accepted;
        this.enabled = enabled;
        this.flatCents = money(flatCents);
        this.perMileCents = money(perMileCents);
        this.perMinuteCents = money(perMinuteCents);
        this.maxStops = Math.max(0, maxStops);
        this.autopilot = autopilot;
        this.autopilotGoalPercent = sanitizedGoal(autopilotGoalPercent);
        this.minimumScalePercent = Math.max(1, Math.min(200, minimumScalePercent));
    }

    /** Rules with Autopilot off, its goal at the default (pay first) and the bar at exactly the minimums. */
    static FilterSettings of(boolean enabled, int flatCents, int perMileCents, int perMinuteCents, int maxStops) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, maxStops, false, GOAL_DEFAULT,
                BAR_AT_MINIMUMS);
    }

    /** 70, 50 and 0 (pay first) stand; anything else is the default, pay first (0.6.18; 70 before). */
    static int sanitizedGoal(int percent) {
        return percent == GOAL_TOP_TIER || percent == GOAL_TIER || percent == GOAL_PAY_FIRST ? percent : GOAL_DEFAULT;
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
        return flatCents > 0 || perMileCents > 0 || perMinuteCents > 0
                || (acceptanceLearning && !accepted.isEmpty());
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
     * Plain-language summary, e.g. "$4.00 per stop · $1.00 per mile · $15.00 per hour · at most 3 stops · Autopilot
     * bar 82% (goal 70%)"; "No rules set" without a rule.
     */
    String describe() {
        List<String> rules = new ArrayList<>();
        if (flatCents > 0) rules.add(DecisionLog.money(flatCents) + " per stop");
        if (perMileCents > 0) rules.add(DecisionLog.money(perMileCents) + " per mile");
        if (perMinuteCents > 0) rules.add(DecisionLog.money(perHourCents()) + " per hour");
        if (maxStops > 0) rules.add("at most " + maxStops + (maxStops == 1 ? " stop" : " stops"));
        if (acceptanceLearning && !accepted.isEmpty()) rules.add("learned minimums");
        if (rules.isEmpty()) return "No rules set";
        if (scoreByArea) rules.add("area mode");
        if (autopilot) {
            rules.add("Autopilot bar " + minimumScalePercent + "% ("
                    + (autopilotGoalPercent == GOAL_PAY_FIRST ? "pay first" : "goal " + autopilotGoalPercent + "%")
                    + ")");
        } else if (minimumScalePercent != BAR_AT_MINIMUMS) {
            rules.add("bar " + minimumScalePercent + "%");
        }
        return String.join(" · ", rules);
    }

    /** Compact summary for the status line, e.g. "$4/stop · $1/mi · $15/hr · ≤3 stops · Auto 82%". */
    String brief() {
        List<String> rules = new ArrayList<>();
        if (flatCents > 0) rules.add(DecisionLog.shortMoney(flatCents) + "/stop");
        if (perMileCents > 0) rules.add(DecisionLog.shortMoney(perMileCents) + "/mi");
        if (perMinuteCents > 0) rules.add(DecisionLog.shortMoney(perHourCents()) + "/hr");
        if (maxStops > 0) rules.add("≤" + maxStops + (maxStops == 1 ? " stop" : " stops"));
        if (acceptanceLearning && !accepted.isEmpty()) rules.add("learned minimums");
        if (rules.isEmpty()) return "No rules set";
        if (scoreByArea) rules.add("area mode");
        if (autopilot) rules.add("Auto " + minimumScalePercent + "%");
        else if (minimumScalePercent != BAR_AT_MINIMUMS) rules.add("bar " + minimumScalePercent + "%");
        return String.join(" · ", rules);
    }

    /**
     * What a plan made from these rules depends on: everything but the bar, which is the plan's own output
     * ("enabled:flat:mile:minute:maxStops:autopilot:goal").
     */
    String rulesKey() {
        return enabled + ":" + scoringKey() + ":" + autopilot + ":" + autopilotGoalPercent
                + ":proof:" + accepted.stableKey();
    }

    /**
     * Exact scoring policy identity; the changing bar and action switches are deliberately separate. ":pst" marks the
     * pay minimum as pay per stop with the pickup counted (0.6.14; 0.6.12's rule C was ":ps"), so no plan or rules key
     * from the minimum pay or rule C matches.
     */
    String scoringKey() {
        return flatCents + ":" + perMileCents + ":" + perMinuteCents + ":" + maxStops + ":"
                + scoreByArea + ":" + acceptanceLearning + ":" + (acceptanceLearning ? accepted.stableKey() : "off")
                + ":pst" + (hasAutopilotAxes() ? ":autoaxes:" + autopilotAxes.stableKey() : "");
    }

    /** Whether an independent Autopilot rate currently applies to at least one active money axis. */
    boolean hasAutopilotAxes() {
        if (!autopilot || autopilotAxes.isEmpty()) return false;
        for (int axis = AreaScore.PAY; axis <= AreaScore.MINUTE; axis++) {
            if (AutopilotAxes.active(this, axis) && autopilotAxes.hasAxis(axis)) return true;
        }
        return false;
    }

    FilterSettings withAutopilotAxes(AutopilotAxes axes) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, maxStops, autopilot,
                autopilotGoalPercent, minimumScalePercent, scoreByArea, acceptanceLearning, accepted, axes);
    }

    /** The original saved/learned scoring policy, without Autopilot's independent working rates. */
    FilterSettings withoutAutopilotAxes() { return withAutopilotAxes(AutopilotAxes.NONE); }

    FilterSettings withScoreByArea(boolean on) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, maxStops, autopilot,
                autopilotGoalPercent, minimumScalePercent, on, acceptanceLearning, accepted, autopilotAxes);
    }

    FilterSettings withAcceptanceLearning(boolean on) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, maxStops, autopilot,
                autopilotGoalPercent, minimumScalePercent, scoreByArea, on, accepted, autopilotAxes);
    }

    FilterSettings withAccepted(AcceptedFloors proof) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, maxStops, autopilot,
                autopilotGoalPercent, minimumScalePercent, scoreByArea, acceptanceLearning, proof, autopilotAxes);
    }

    FilterSettings withEnabled(boolean value) {
        return new FilterSettings(value, flatCents, perMileCents, perMinuteCents, maxStops, autopilot,
                autopilotGoalPercent, minimumScalePercent, scoreByArea, acceptanceLearning, accepted, autopilotAxes);
    }

    /** These rules with at most {@code stops} stops (0: no limit); nothing else changes. */
    FilterSettings withMaxStops(int stops) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, stops, autopilot,
                autopilotGoalPercent, minimumScalePercent, scoreByArea, acceptanceLearning, accepted, autopilotAxes);
    }

    /** These rules at another bar (clamped 1–200); the minimums themselves never change. */
    FilterSettings withMinimumScalePercent(int percent) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, maxStops, autopilot,
                autopilotGoalPercent, percent, scoreByArea, acceptanceLearning, accepted, autopilotAxes);
    }

    /**
     * Autopilot on or off with {@code goal} (70, 50 or 0). Turning it off puts the bar back at exactly the minimums;
     * turning it on keeps the bar until Autopilot moves it.
     */
    FilterSettings withAutopilot(boolean on, int goal) {
        return new FilterSettings(enabled, flatCents, perMileCents, perMinuteCents, maxStops, on, goal,
                on ? minimumScalePercent : BAR_AT_MINIMUMS, scoreByArea, acceptanceLearning, accepted, autopilotAxes);
    }

    /** The three money minimums replaced; max stops, the switch, Autopilot and the bar stay. */
    FilterSettings withMinimums(int flat, int mile, int minute) {
        return new FilterSettings(enabled, flat, mile, minute, maxStops, autopilot, autopilotGoalPercent,
                minimumScalePercent, scoreByArea, acceptanceLearning, accepted, autopilotAxes);
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
