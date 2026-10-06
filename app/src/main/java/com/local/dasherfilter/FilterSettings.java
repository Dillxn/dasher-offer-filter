package com.local.dasherfilter;

import java.math.BigDecimal;
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
 * <p>Score by area, the hotspot, per-stop and per-item minimums, and every learned minimum are retired. The members
 * marked {@code @Deprecated} below are an inert compatibility surface that keeps files of later work packages
 * compiling until they drop their uses: constant retired fields, no-op or throwing retired methods, and the old
 * positional constructors, which throw {@code IllegalArgumentException("retired rule: …")} for any retired argument
 * that is not its default rather than silently change its meaning.
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

    // ---- Retired rules: constant, so no decision can depend on them (removed with their last uses). ----
    /** Retired adaptive minimum: always off. */
    @Deprecated final boolean risingOffers = false;
    /** Retired score by area: always off. */
    @Deprecated final boolean scoreByArea = false;
    /** Retired per-stop minimum (folded into minimum pay by the 0.5.0 migration): always 0. */
    @Deprecated final int perStopCents = 0;
    /** Retired per-item minimum: always 0. */
    @Deprecated final int perItemCents = 0;
    /** Retired hotspot-proximity minimum: always 0. */
    @Deprecated final int hotspotProximityHundredths = 0;
    /** Retired highest-accepted payout floor: always 0. */
    @Deprecated final int lastAcceptedCents = 0;
    /** Retired best accepted rates: always none. */
    @Deprecated final AcceptedBest best = AcceptedBest.NONE;
    /** Retired decline lessons: always none. */
    @Deprecated final DeclinedFloor declined = DeclinedFloor.NONE;

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
     * when present: {@code IllegalArgumentException("retired rule: …")} otherwise.
     */
    FilterSettings withMinimums(int[] cents) {
        String[] retired = {"per stop", "hotspot", "per item"};
        for (int slot = 3; slot < cents.length; slot++) {
            if (cents[slot] != 0) throw retired(slot - 3 < retired.length ? retired[slot - 3] : "slot " + slot);
        }
        return withMinimums(cents[0], cents[1], cents[2]);
    }

    // ---- Deprecated compatibility surface (inert) ----

    /** Score by area is retired: turning it off changes nothing; turning it on is refused. */
    @Deprecated
    FilterSettings withScoreByArea(boolean on) {
        if (on) throw new IllegalStateException("retired rule: score by area");
        return this;
    }

    /** The adaptive minimum is retired: turning it off changes nothing; turning it on is refused. */
    @Deprecated
    FilterSettings withAdaptive(boolean on) {
        if (on) throw new IllegalStateException("retired rule: adaptive");
        return this;
    }

    /** Nothing is learned any more, so there is nothing to adopt. */
    @Deprecated
    FilterSettings adoptAdaptive() {
        return this;
    }

    /** There is no adaptive baseline any more. */
    @Deprecated
    FilterSettings withoutRisingBaseline() {
        return this;
    }

    /** The hotspot rule is retired: 0 changes nothing; anything else is refused. */
    @Deprecated
    FilterSettings withHotspotProximity(int hundredths) {
        if (hundredths != 0) throw retired("hotspot");
        return this;
    }

    /** The per-item rule is retired: 0 changes nothing; anything else is refused. */
    @Deprecated
    FilterSettings withPerItem(int cents) {
        if (cents != 0) throw retired("per item");
        return this;
    }

    /** Plain reciprocal-distance units of the retired hotspot rule; never money. */
    @Deprecated
    static String proximityLabel(int hundredths) {
        return BigDecimal.valueOf(hundredths, 2).stripTrailingZeros().toPlainString() + " /mi";
    }

    /** Test shim: enabled, flat, per mile, per minute and max stops; per stop must be 0. Autopilot off, bar 100. */
    @Deprecated
    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops) {
        this(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, false, 0);
    }

    /** Test shim; the adaptive minimum must be off with nothing learned. */
    @Deprecated
    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents) {
        this(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, risingOffers,
                lastAcceptedCents, AcceptedBest.NONE);
    }

    /** Test shim; the adaptive minimum must be off with nothing learned. */
    @Deprecated
    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents, AcceptedBest best) {
        this(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, risingOffers,
                lastAcceptedCents, best, DeclinedFloor.NONE);
    }

    /** Test shim; the adaptive minimum must be off with nothing learned. */
    @Deprecated
    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents, AcceptedBest best, DeclinedFloor declined) {
        this(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, risingOffers,
                lastAcceptedCents, best, declined, false);
    }

    /** Test shim; score by area must be off. */
    @Deprecated
    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents, AcceptedBest best, DeclinedFloor declined,
                   boolean scoreByArea) {
        this(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, risingOffers,
                lastAcceptedCents, best, declined, scoreByArea, 0);
    }

    /** Test shim; the hotspot minimum must be 0. */
    @Deprecated
    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents, AcceptedBest best, DeclinedFloor declined,
                   boolean scoreByArea, int hotspotProximityHundredths) {
        this(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, risingOffers,
                lastAcceptedCents, best, declined, scoreByArea, hotspotProximityHundredths, BAR_AT_MINIMUMS);
    }

    /** Test shim; the old minimums scale becomes the bar (Autopilot off). */
    @Deprecated
    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents, AcceptedBest best, DeclinedFloor declined,
                   boolean scoreByArea, int hotspotProximityHundredths, int minimumScalePercent) {
        this(enabled, flatCents, perMileCents, perMinuteCents, perStopCents, maxStops, risingOffers,
                lastAcceptedCents, best, declined, scoreByArea, hotspotProximityHundredths, minimumScalePercent, 0);
    }

    /**
     * Test shim for every older positional form: maps enabled, flat, per mile, per minute, max stops and the old scale
     * (as the bar) with Autopilot off, and refuses any retired argument that is not its default.
     */
    @Deprecated
    FilterSettings(boolean enabled, int flatCents, int perMileCents,
                   int perMinuteCents, int perStopCents, int maxStops,
                   boolean risingOffers, int lastAcceptedCents, AcceptedBest best, DeclinedFloor declined,
                   boolean scoreByArea, int hotspotProximityHundredths, int minimumScalePercent, int perItemCents) {
        this(enabled, flatCents, perMileCents, perMinuteCents, maxStops, false, GOAL_TOP_TIER,
                legacyBar(perStopCents, risingOffers, lastAcceptedCents, best, declined, scoreByArea,
                        hotspotProximityHundredths, perItemCents, minimumScalePercent));
    }

    /** The old scale, once every retired argument is confirmed at its default; throws otherwise. */
    private static int legacyBar(int perStopCents, boolean risingOffers, int lastAcceptedCents, AcceptedBest best,
                                 DeclinedFloor declined, boolean scoreByArea, int hotspotProximityHundredths,
                                 int perItemCents, int minimumScalePercent) {
        if (perStopCents != 0) throw retired("per stop");
        if (risingOffers || lastAcceptedCents != 0 || (best != null && !best.isEmpty())
                || (declined != null && !declined.isEmpty())) {
            throw retired("adaptive");
        }
        if (scoreByArea) throw retired("score by area");
        if (hotspotProximityHundredths != 0) throw retired("hotspot");
        if (perItemCents != 0) throw retired("per item");
        return minimumScalePercent;
    }

    private static IllegalArgumentException retired(String rule) {
        return new IllegalArgumentException("retired rule: " + rule);
    }
}
