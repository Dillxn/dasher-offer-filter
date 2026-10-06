package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every word Autopilot shows, says or logs (finalSpec.autopilot.explainability), as pure builders: no Android class,
 * no clock, no storage. What they describe arrives as a {@link Status} ({@link AutopilotRuntime#status} assembles it),
 * a {@link Autopilot.Plan}, or plain numbers.
 *
 * <p>The words never promise earnings: pay per hour is only ever history ("paid"), the accepts still needed are "about"
 * and called an estimate, and no tier rule is claimed beyond "check the Dasher app". The acceptance rate is shown in
 * whole percent rounded down, so it never reads as the goal while it is still below it. Log lines ({@code log*}) are
 * fixed words and numbers only, never screen text.
 */
final class AutopilotText {
    // ---- The Autopilot button (the constellation's SCORE_ID slot) ----

    /** The button's top line; its second line is {@link #buttonLabel}. */
    static final String BUTTON_TITLE = "Auto";
    static final String ACTION_TURN_ON = "Turn Autopilot on";
    static final String ACTION_TURN_OFF = "Turn Autopilot off";
    static final String ACTION_CHANGE_GOAL = "Change acceptance goal";
    static final String ACTION_DETAILS = "Autopilot details";
    /** The button's custom accessibility action that opens the details. */
    static final int DETAILS_ACTION_ID = 0x4F460003;
    static final String TOAST_NEEDS_MINIMUM = "Set a pay, per-mile or hourly minimum first.";
    static final String TOAST_OFF = "Autopilot off · back to exactly your minimums";

    // ---- The goal chooser ----

    static final String CHOOSER_TITLE = "What matters more?";
    static final String CHOOSER_ABOUT = "About acceptance rate";
    static final String CHOOSER_NOT_NOW = "Not now";
    static final String ABOUT_ACCEPTANCE_RATE = "DoorDash computes acceptance rate over your recent offers (it "
            + "reports the last 100). Its rewards tiers use acceptance-rate minimums — check the Dasher app for your "
            + "current requirements.";

    // ---- The details dialog ----

    static final String DETAILS_TITLE = "Autopilot";
    static final String DETAILS_CHANGE_GOAL = "Change goal";
    static final String DETAILS_TYPICAL_MINIMUMS = "Use typical minimums";
    static final String DETAILS_TURN_OFF = "Turn off";
    static final String DETAILS_TURN_ON = "Turn on";
    static final String DETAILS_CLOSE = "Close";
    static final String DETAILS_OFF = "Autopilot is off: offers are judged at exactly your minimums.";
    static final String DETAILS_AR_NOT_SEEN = "Acceptance rate: not seen yet. Dasher shows it when an offer is "
            + "declined.";
    static final String DETAILS_AT_GOAL = "At or above your goal.";
    static final String DETAILS_NO_WAITING = "Not enough watched waiting yet to tell how often offers come.";
    static final String DETAILS_AUTO_ACCEPT = "Auto-accept only takes offers that meet 100% of your minimums.";

    // ---- The offer ticket ----

    static final String TICKET_EXEMPT = "Dasher said declining it does not lower your acceptance rate.";
    static final String TICKET_KEY = "How this offer was judged";

    // ---- The skyline ----

    static final String SKYLINE_DESCRIPTION = "Tree height is the recorded score: pay as a percent of your minimums; "
            + "the dashed line is the current bar.";

    // ---- The diagnostic log (category "autopilot") ----

    static final String LOG_OFF = "off; bar back to " + Autopilot.BAR_OFF + "%";
    static final String LOG_DEFERRED = "commit deferred: offer or decline in flight";
    static final String DISCARD_RULES = "plan discarded: rules changed";
    static final String DISCARD_OLD = "plan discarded: older than " + Autopilot.PLAN_MAX_AGE_MS / 60_000 + " min";
    static final String DISCARD_CLEARED = "plan discarded: history cleared";
    /** A plan worked out across turning Autopilot off and on, or another goal, whose state that change forgot. */
    static final String DISCARD_AUTOPILOT_CHANGED = "plan discarded: Autopilot or its goal changed";

    /**
     * What Autopilot is doing, as the homepage, the chip, the button, the details and the reports describe it. Pure:
     * {@link AutopilotRuntime#status} reads it from storage; tests build it directly.
     */
    static final class Status {
        /** The first of these that holds names the status line (finalSpec order). */
        enum Kind {
            /** Autopilot is off. */
            OFF,
            /** The screen reader is not running, so Autopilot cannot commit. */
            WAITS,
            /** No pay, per-mile or hourly minimum. */
            NEEDS_MINIMUM,
            /** Auto-decline is paused. */
            PAUSED,
            /** No plan yet for these rules. */
            CHECKING,
            /** Fewer than 20 counted offers. */
            LEARNING,
            /** Even the lowest bar passes too few offers for the goal. */
            PINNED,
            /** The acceptance rate is known and below the goal. */
            BELOW_GOAL,
            /** The acceptance rate is known and at or above the goal. */
            AT_GOAL,
            /** A goal, and no acceptance rate yet. */
            AR_UNKNOWN,
            /** Pay first, and its one-in-five floor binds. */
            PASS_FLOOR,
            /** Pay first. */
            PAY_FIRST
        }

        final FilterSettings rules;
        final boolean on;
        /** The bar deciding offers now (exactly 100 while Autopilot is off). */
        final int bar;
        final int goal;
        final boolean readerConnected;
        /** The latest plan for exactly these rules; null before the first one (or while off). */
        final Autopilot.Plan plan;
        /** Dasher's latest reading, or null. */
        final AutopilotStore.Reading reading;
        /** The last bar change, or null. */
        final AutopilotStore.Change lastChange;
        /** Recovering and the stall correction: the plan's, else as stored. */
        final boolean recovering;
        final int extra;
        final long wallNow;
        /** The acceptance rate in hundredths of a percent (-1 unknown): the plan's, else Dasher's latest reading. */
        final int arHundredths;
        final Autopilot.ArSource arSource;
        /** Accounted offers since Dasher's reading; -1 when not known. */
        final int arOffersSince;
        /** Accepted and declined offers behind the app's estimate; -1 unless estimated. */
        final int arCounted;
        final Kind kind;

        Status(FilterSettings rules, boolean readerConnected, Autopilot.Plan plan, AutopilotStore.Reading reading,
               AutopilotStore.Change lastChange, long wallNow) {
            this(rules, readerConnected, plan, reading, lastChange, false, 0, wallNow);
        }

        Status(FilterSettings rules, boolean readerConnected, Autopilot.Plan plan, AutopilotStore.Reading reading,
               AutopilotStore.Change lastChange, boolean storedRecovering, int storedExtra, long wallNow) {
            if (rules == null) throw new IllegalArgumentException("rules");
            this.rules = rules;
            this.on = rules.autopilot;
            this.bar = rules.minimumScalePercent;
            this.goal = rules.autopilotGoalPercent;
            this.readerConnected = readerConnected;
            // A plan made for other rules (another goal, minimum or switch) says nothing about these.
            this.plan = on && plan != null && plan.rulesKey.equals(rules.rulesKey()) ? plan : null;
            this.reading = reading;
            this.lastChange = lastChange;
            this.recovering = this.plan != null ? this.plan.recovering : storedRecovering;
            this.extra = this.plan != null ? this.plan.extra : Math.max(0, Math.min(Autopilot.EXTRA_MAX, storedExtra));
            this.wallNow = wallNow;
            if (this.plan != null) {
                arHundredths = this.plan.arHundredths;
                arSource = this.plan.arSource;
                arOffersSince = this.plan.arOffersSince;
                arCounted = this.plan.arCounted;
            } else if (reading != null && reading.percent >= 0 && reading.percent <= 100) {
                arHundredths = 100 * reading.percent;
                arSource = Autopilot.ArSource.DASHER;
                arOffersSince = -1;
                arCounted = -1;
            } else {
                arHundredths = -1;
                arSource = Autopilot.ArSource.UNKNOWN;
                arOffersSince = -1;
                arCounted = -1;
            }
            this.kind = kind();
        }

        private Kind kind() {
            if (!on) return Kind.OFF;
            if (!readerConnected) return Kind.WAITS;
            if (!rules.hasMonetaryRule()) return Kind.NEEDS_MINIMUM;
            if (!rules.enabled) return Kind.PAUSED;
            if (plan == null || plan.mode == Autopilot.Mode.OFF || plan.mode == Autopilot.Mode.NO_RULES) {
                return Kind.CHECKING;
            }
            if (plan.mode == Autopilot.Mode.LEARNING) return Kind.LEARNING;
            if (plan.mode == Autopilot.Mode.PINNED) return Kind.PINNED;
            if (goal > 0) return arHundredths < 0 ? Kind.AR_UNKNOWN : belowGoal() ? Kind.BELOW_GOAL : Kind.AT_GOAL;
            return plan.mode == Autopilot.Mode.PASS_FLOOR ? Kind.PASS_FLOOR : Kind.PAY_FIRST;
        }

        /** Autopilot is on with a goal, and the acceptance rate is known and below it (the amber ring and chip). */
        boolean belowGoal() {
            return on && goal > 0 && arHundredths >= 0 && arHundredths < 100 * goal;
        }

        /** Even the lowest bar passes too few offers for the goal. */
        boolean pinned() {
            return plan != null && plan.pinned;
        }

        /** About how many more accepts reach the goal; 0 at or above it, or when unknown. */
        int acceptsNeeded() {
            return Autopilot.acceptsNeeded(goal, arHundredths);
        }

        /** The acceptance rate in whole percent, rounded down; -1 when unknown. */
        int arPercent() {
            return arHundredths < 0 ? -1 : arHundredths / 100;
        }

        /** Whole minutes since Dasher's latest reading was seen; -1 without one. */
        long arAgeMinutes() {
            return reading == null ? -1 : Math.max(0, wallNow - reading.at) / 60_000;
        }

        /** The plan's mode ("RECOVERY"), or null before the first plan for these rules. */
        String modeName() {
            return plan == null ? null : plan.mode.name();
        }
    }

    // ---- Goals ----

    /** The chooser's item for a goal (and the details' "Goal:" line). */
    static String goalLabel(int goal) {
        switch (FilterSettings.sanitizedGoal(goal)) {
            case FilterSettings.GOAL_TIER:
                return "Keep a tier — acceptance rate 50% or more";
            case FilterSettings.GOAL_PAY_FIRST:
                return "Pay first — no acceptance-rate goal";
            default:
                return "Keep a top tier — acceptance rate 70% or more";
        }
    }

    /** The chooser's single-choice items, in {@link Autopilot#GOALS} order (70% first, preselected). */
    static List<String> chooserItems() {
        List<String> items = new ArrayList<>();
        for (int goal : Autopilot.GOALS) items.add(goalLabel(goal));
        return Collections.unmodifiableList(items);
    }

    /** The chooser item checked for the stored goal; 70% (the first) for anything else. */
    static int chooserIndex(int goal) {
        int index = Autopilot.GOALS.indexOf(FilterSettings.sanitizedGoal(goal));
        return Math.max(0, index);
    }

    /** "Autopilot on · goal: acceptance rate 70% or more" / "Autopilot on · pay first". */
    static String toastTurnedOn(int goal) {
        int sane = FilterSettings.sanitizedGoal(goal);
        return sane == FilterSettings.GOAL_PAY_FIRST ? "Autopilot on · pay first"
                : "Autopilot on · goal: acceptance rate " + sane + "% or more";
    }

    /** With Autopilot already on: "Autopilot goal: acceptance rate 50% or more" / "Autopilot goal: pay first". */
    static String toastGoalChanged(int goal) {
        int sane = FilterSettings.sanitizedGoal(goal);
        return sane == FilterSettings.GOAL_PAY_FIRST ? "Autopilot goal: pay first"
                : "Autopilot goal: acceptance rate " + sane + "% or more";
    }

    // ---- The button ----

    /** The button's second line: "82%" while on, "Off" while off. */
    static String buttonLabel(boolean on, int bar) {
        return on ? bar + "%" : "Off";
    }

    /** What a screen reader hears for the button (a Switch). */
    static String buttonDescription(boolean on, int bar, int goal) {
        if (!on) return "Autopilot, off. Offers are judged at exactly your minimums.";
        return "Autopilot, on. Bar " + bar + " percent of your minimums. Goal: " + spokenGoal(goal) + ".";
    }

    /** The button's click action label: what a tap does now. */
    static String buttonAction(boolean on) {
        return on ? ACTION_TURN_OFF : ACTION_TURN_ON;
    }

    private static String spokenGoal(int goal) {
        switch (FilterSettings.sanitizedGoal(goal)) {
            case FilterSettings.GOAL_TIER:
                return "keep a tier, acceptance rate 50 percent or more";
            case FilterSettings.GOAL_PAY_FIRST:
                return "pay first";
            default:
                return "keep a top tier, acceptance rate 70 percent or more";
        }
    }

    // ---- The homepage status line ----

    /**
     * The homepage's one status line (in place of "Next match" while Autopilot is on; a tap opens the details). The
     * first matching line wins, in the spec's order: waiting for the screen reader; no money minimum; auto-decline
     * paused; no plan yet ("checking your offers"); learning; pinned at the lowest bar; below the goal; at or above it;
     * no reading yet; pay first's floor; pay first.
     */
    static String statusLine(Status s) {
        switch (s.kind) {
            case OFF:
                return "Autopilot off";
            case WAITS:
                return "Autopilot waits for screen reading";
            case NEEDS_MINIMUM:
                return "Autopilot needs a pay, per-mile or hourly minimum";
            case PAUSED:
                return "Autopilot " + s.bar + "% · auto-decline paused";
            case CHECKING:
                return "Autopilot " + s.bar + "% · checking your offers";
            case LEARNING:
                return "Autopilot " + s.bar + "% · learning (" + s.plan.counted + " of " + Autopilot.MIN_WINDOW
                        + " offers)";
            case PINNED:
                return "Autopilot " + s.bar + "%" + (s.bar == Autopilot.BAR_MIN ? " (lowest)" : "") + " · "
                        + (s.belowGoal() ? progress(s) : "lower a minimum to pass more");
            case BELOW_GOAL:
                return "Autopilot " + s.bar + "% · " + progress(s);
            case AT_GOAL:
                return "Autopilot " + s.bar + "% · AR " + arShort(s) + ", goal " + s.goal + "%";
            case AR_UNKNOWN:
                return "Autopilot " + s.bar + "% · goal " + s.goal + "% · AR not seen yet";
            case PASS_FLOOR:
                return "Autopilot " + s.bar + "% · pay first · keeping 1 in 5 offers";
            default:
                return "Autopilot " + s.bar + "% · pay first";
        }
    }

    /** "AR 55% → 70%: about 15 more accepts" ("AR ~31%" when it is the app's own estimate). */
    private static String progress(Status s) {
        int accepts = s.acceptsNeeded();
        return "AR " + arShort(s) + " → " + s.goal + "%: about " + accepts + " more " + plural(accepts, "accept");
    }

    /** "55%" (Dasher's, carried forward) or "~31%" (the app's own estimate): whole percent, rounded down. */
    private static String arShort(Status s) {
        return (s.arSource == Autopilot.ArSource.ESTIMATE ? "~" : "") + s.arHundredths / 100 + "%";
    }

    // ---- The compact / split chip ----

    /** The one-line chip of the compact header: "Auto off", "Auto learning", "Auto 82%", "Auto 82% ▲" (below the goal). */
    static String chip(Status s) {
        switch (s.kind) {
            case OFF:
                return "Auto off";
            case WAITS:
                return "Auto waits";
            case NEEDS_MINIMUM:
                return "Auto needs a minimum";
            case LEARNING:
                return "Auto learning";
            case PINNED:
                if (s.bar == Autopilot.BAR_MIN) return "Auto " + s.bar + "% lowest";
                return chipBar(s);
            default:
                return chipBar(s);
        }
    }

    private static String chipBar(Status s) {
        return "Auto " + s.bar + "%" + (s.belowGoal() ? " ▲" : "");
    }

    /** The chip's content description: the full status line, and what a tap does. */
    static String chipDescription(Status s) {
        return statusLine(s) + ". Opens Autopilot details";
    }

    // ---- The details dialog ----

    /** The details' lines in order; each appears only when its facts are known. */
    static List<String> detailsLines(Status s) {
        List<String> lines = new ArrayList<>();
        lines.add(detailsBar(s));
        if (s.on) lines.add("Goal: " + goalLabel(s.goal) + ".");
        String ar = detailsAcceptanceRate(s);
        if (ar != null) lines.add(ar);
        String goal = detailsGoalProgress(s);
        if (goal != null) lines.add(goal);
        String pass = detailsPass(s);
        if (pass != null) lines.add(pass);
        String interval = detailsInterval(s);
        if (interval != null) lines.add(interval);
        String paid = detailsPaid(s);
        if (paid != null) lines.add(paid);
        String change = detailsLastChange(s);
        if (change != null) lines.add(change);
        if (s.on) lines.add(DETAILS_AUTO_ACCEPT);
        String pinned = detailsPinned(s);
        if (pinned != null) lines.add(pinned);
        return Collections.unmodifiableList(lines);
    }

    /** "Bar: 82% of your minimums (100% = exactly your minimums)."; while off, that offers meet exactly them. */
    static String detailsBar(Status s) {
        if (!s.on) return DETAILS_OFF;
        return "Bar: " + s.bar + "% of your minimums (100% = exactly your minimums).";
    }

    /**
     * The acceptance rate: Dasher's ("55%, shown by Dasher 12 min ago (2 offers since)"), carried forward over the
     * offers since it when that moved it ("about 16% (Dasher showed 9% 12 min ago; 10 offers since)"), the app's own
     * estimate, or not seen yet. Null while Autopilot is off and nothing was read.
     */
    static String detailsAcceptanceRate(Status s) {
        if (s.arSource == Autopilot.ArSource.UNKNOWN || s.arHundredths < 0) return s.on ? DETAILS_AR_NOT_SEEN : null;
        int percent = s.arHundredths / 100;
        if (s.arSource == Autopilot.ArSource.ESTIMATE) {
            return "Acceptance rate: about " + percent + "%, estimated from " + s.arCounted + " of your recent offers.";
        }
        String when = s.reading == null ? "" : " " + ago(s.wallNow - s.reading.at);
        String since = s.arOffersSince < 0 ? "" : s.arOffersSince == 0 ? "no offers since"
                : s.arOffersSince + " " + plural(s.arOffersSince, "offer") + " since";
        if (s.reading != null && s.reading.percent != percent) {
            return "Acceptance rate: about " + percent + "% (Dasher showed " + s.reading.percent + "%" + when
                    + (since.isEmpty() ? "" : "; " + since) + ").";
        }
        return "Acceptance rate: " + percent + "%, shown by Dasher" + when + (since.isEmpty() ? "" : " (" + since + ")")
                + ".";
    }

    /** Below the goal, about how many more accepts (an estimate), or at or above it; null without a goal or a rate. */
    static String detailsGoalProgress(Status s) {
        if (!s.on || s.goal <= 0 || s.arHundredths < 0) return null;
        if (!s.belowGoal()) return DETAILS_AT_GOAL;
        int accepts = s.acceptsNeeded();
        return "Below your goal: about " + accepts + " more " + plural(accepts, "accept") + " to reach " + s.goal
                + "% (an estimate — DoorDash counts your last 100 offers, so older accepts also roll off).";
    }

    /**
     * "Of your last 20 offers, 18 would pass at 82% (your goal needs 16)."; pay first, or before the need is planned,
     * without the goal's part. Null without counted offers, and at the lowest bar while pinned (the pinned line says
     * it then).
     */
    static String detailsPass(Status s) {
        Autopilot.Plan plan = s.plan;
        if (plan == null || plan.counted <= 0) return null;
        if (plan.pinned && s.bar == Autopilot.BAR_MIN) return null;
        int pass = plan.passAt(s.bar);
        String goal = s.goal > 0 && plan.need >= 0
                ? " (your goal needs " + ceilPercentOf(plan.need, plan.counted) + ")" : "";
        if (plan.counted == 1) {
            return "Your last offer would" + (pass == 1 ? "" : " not") + " pass at " + s.bar + "%" + goal + ".";
        }
        return "Of your last " + plan.counted + " offers, " + pass + " would pass at " + s.bar + "%" + goal + ".";
    }

    /** How often offers came while the user waited with Dasher on screen (last 2 hours), or that it is not known. */
    static String detailsInterval(Status s) {
        if (s.plan == null) return null;
        long interval = s.plan.offerIntervalMs();
        if (interval < 0) return DETAILS_NO_WAITING;
        return "Offers came about every " + minutes(interval) + " while you waited with Dasher on screen (last 2 "
                + "hours).";
    }

    /**
     * History, never a promise: what the readable counted offers passing at the bar paid per hour of Dasher's own
     * time estimates, rounded to whole dollars. Null when none pass or none has minutes.
     */
    static String detailsPaid(Status s) {
        if (s.plan == null) return null;
        Autopilot.Paid paid = s.plan.paidAt(s.bar);
        long perHour = paid.centsPerHour();
        if (paid.count <= 0 || perHour < 0) return null;
        long dollars = (perHour + 50) / 100;
        return "Offers passing at " + s.bar + "% paid about " + DecisionLog.shortMoney(dollars * 100) + " per hour of "
                + "Dasher's own time estimates (" + paid.count + " " + plural(paid.count, "offer") + "). This is "
                + "history, not a promise: waiting and the drive back are not included.";
    }

    /** "Last change 4 min ago: 100% → 82% (acceptance rate below your goal)."; null when none is kept. */
    static String detailsLastChange(Status s) {
        AutopilotStore.Change change = s.lastChange;
        if (change == null) return null;
        String words = reasonWords(change.why);
        return "Last change " + ago(s.wallNow - change.at) + ": " + change.from + "% → " + change.to + "%"
                + (words.isEmpty() ? "" : " (" + words + ")") + ".";
    }

    /**
     * While pinned: how few offers even the lowest bar passes, and what helps (turning off max stops only when it is
     * set). Null otherwise.
     */
    static String detailsPinned(Status s) {
        if (s.plan == null || !s.plan.pinned) return null;
        int pass = s.plan.passAt(Autopilot.BAR_MIN);
        String help = s.rules.maxStops > 0 ? "Lower a minimum, turn off max stops, or use typical minimums."
                : "Lower a minimum or use typical minimums.";
        return "Even at the lowest bar (" + Autopilot.BAR_MIN + "%) only " + pass + " of your last " + s.plan.counted
                + " offers " + (pass == 1 ? "passes" : "pass") + ". " + help;
    }

    /** The details' buttons, in order: "Change goal" ("Use typical minimums" while pinned), "Turn off"/"Turn on", "Close". */
    static List<String> detailsButtons(Status s) {
        return Collections.unmodifiableList(Arrays.asList(
                s.pinned() ? DETAILS_TYPICAL_MINIMUMS : DETAILS_CHANGE_GOAL,
                s.on ? DETAILS_TURN_OFF : DETAILS_TURN_ON,
                DETAILS_CLOSE));
    }

    // ---- Why the bar moved ----

    /** A commit reason's words, e.g. "acceptance rate below your goal". */
    static String reasonWords(Autopilot.Reason reason) {
        return reason == null ? "" : reason.words;
    }

    /** The words of a stored reason name (the last change's "why"); "" for one this version does not know. */
    static String reasonWords(String name) {
        if (name == null || name.isEmpty()) return "";
        try {
            return Autopilot.Reason.valueOf(name).words;
        } catch (IllegalArgumentException unknown) {
            return "";
        }
    }

    // ---- The offer ticket and the below-minimums card ----

    /**
     * The ticket's score line: "Score 85% of your minimums · bar 82%" (the bar only when it was not 100), or
     * "Area score (retired rules) 121%" for a line an older version decided; null without a score.
     */
    static String ticketScoreLine(int scorePercent, int barPercent, int model) {
        if (scorePercent < 0) return null;
        if (model < DecisionLog.MODEL) return "Area score (retired rules) " + scorePercent + "%";
        return "Score " + scorePercent + "% of your minimums"
                + (barPercent != Autopilot.BAR_OFF ? " · bar " + barPercent + "%" : "");
    }

    /** A passing offer below 100% of the minimums: why it passed, and that it is never auto-accepted. */
    static String ticketBelowMinimums(int barPercent) {
        return "Below your minimums: passed by Autopilot's " + barPercent + "% bar. Never auto-accepted.";
    }

    /**
     * The review-channel card (never the pass chime) of an offer that passed only because the bar is below 100:
     * "&lt;facts&gt;; below your minimums (85%), passed by Autopilot's 82% bar. Yours to accept."
     */
    static String belowMinimumsCard(String facts, int scorePercent, int barPercent) {
        return (facts == null ? "" : facts) + "; below your minimums" + (scorePercent >= 0 ? " (" + scorePercent + "%)"
                : "") + ", passed by Autopilot's " + barPercent + "% bar. Yours to accept.";
    }

    /** As {@link #belowMinimumsCard(String, int, int)}, with the facts as a card shows them. */
    static String belowMinimumsCard(OfferSnapshot facts, int scorePercent, int barPercent) {
        return belowMinimumsCard((facts == null ? OfferSnapshot.UNKNOWN : facts).summary(), scorePercent, barPercent);
    }

    // ---- The skyline ----

    /** The dashed rail at the bar: "Bar 82%". */
    static String skylineBarRail(int barPercent) {
        return "Bar " + barPercent + "%";
    }

    /** The solid payout rail, only with a minimum pay: "Pay $3.28 min" ({@code ⌈bar × flat ÷ 100⌉}); else null. */
    static String skylinePayRail(int flatCents, int barPercent) {
        if (flatCents <= 0) return null;
        return "Pay " + DecisionLog.money((barPercent * (long) flatCents + 99) / 100) + " min";
    }

    // ---- The shared report ----

    /**
     * One line for the shared report and attached diagnostics: "Autopilot: on; goal 70%; bar 82%; mode RECOVERY;
     * recovering yes; extra 0; AR 55% (Dasher, 12 min ago, 2 offers since); last change 100%->82% (acceptance rate
     * below your goal) 4 min ago". The plan's part only once there is a plan; the acceptance rate only travels here.
     */
    static String reportLine(Status s) {
        List<String> parts = new ArrayList<>();
        parts.add("Autopilot: " + (s.on ? "on" : "off"));
        parts.add(s.goal == FilterSettings.GOAL_PAY_FIRST ? "pay first" : "goal " + s.goal + "%");
        parts.add("bar " + s.bar + "%");
        if (s.plan != null) {
            parts.add("mode " + s.plan.mode.name());
            parts.add("recovering " + yesNo(s.plan.recovering));
            parts.add("extra " + s.plan.extra);
        }
        parts.add(reportAcceptanceRate(s));
        AutopilotStore.Change change = s.lastChange;
        if (change != null) {
            String words = reasonWords(change.why);
            parts.add("last change " + change.from + "%->" + change.to + "%" + (words.isEmpty() ? "" : " (" + words
                    + ")") + " " + ago(s.wallNow - change.at));
        }
        return String.join("; ", parts);
    }

    private static String reportAcceptanceRate(Status s) {
        if (s.arSource == Autopilot.ArSource.UNKNOWN || s.arHundredths < 0) return "AR unknown";
        int percent = s.arHundredths / 100;
        if (s.arSource == Autopilot.ArSource.ESTIMATE) {
            return "AR ~" + percent + "% (estimate, " + s.arCounted + " " + plural(s.arCounted, "offer") + ")";
        }
        List<String> about = new ArrayList<>();
        about.add("Dasher");
        if (s.reading != null) about.add(ago(s.wallNow - s.reading.at));
        if (s.arOffersSince >= 0) about.add(s.arOffersSince + " " + plural(s.arOffersSince, "offer") + " since");
        return "AR " + percent + "% (" + String.join(", ", about) + ")";
    }

    // ---- The diagnostic log (category "autopilot"): fixed words and numbers only ----

    /** "on; goal 70%" / "on; pay first". */
    static String logOn(int goal) {
        return "on; " + goalWords(goal);
    }

    /** "goal 50% (was 70%)"; pay first reads "pay first (was 70%)", and "goal 70% (was pay first)". */
    static String logGoal(int goal, int was) {
        int before = FilterSettings.sanitizedGoal(was);
        return goalWords(goal) + " (was " + (before == FilterSettings.GOAL_PAY_FIRST ? "pay first" : before + "%")
                + ")";
    }

    /**
     * One plan: "plan 82% RECOVERY: need 80%, pass 18/20 at 82%, share bar 82%, ar 55% dasher (12 min ago, 2 since),
     * recovering yes, extra 0, lambda 17.1/h, mix 20, best 86%, ok 86-100%". Figures a gated plan did not work out
     * (the need, the share bar, the value's best and acceptable bars) are left out.
     *
     * @param readingAt when Dasher's reading behind the plan was seen (wall clock), or -1
     */
    static String logPlan(Autopilot.Plan plan, long readingAt, long wallNow) {
        List<String> parts = new ArrayList<>();
        if (plan.need >= 0) parts.add("need " + plan.need + "%");
        parts.add("pass " + plan.passAtTarget + "/" + plan.counted + " at " + plan.target + "%");
        if (plan.barShare >= 0) parts.add("share bar " + plan.barShare + "%");
        parts.add("ar " + logAcceptanceRate(plan, readingAt, wallNow));
        parts.add("recovering " + yesNo(plan.recovering));
        parts.add("extra " + plan.extra);
        parts.add("lambda " + tenths(plan.offersPerHourTenths()) + "/h");
        parts.add("mix " + plan.mixSize);
        if (plan.bestBar >= 0) parts.add("best " + plan.bestBar + "%");
        if (plan.acceptableLow >= 0) parts.add("ok " + plan.acceptableLow + "-" + plan.acceptableHigh + "%");
        return "plan " + plan.target + "% " + plan.mode.name() + ": " + String.join(", ", parts);
    }

    private static String logAcceptanceRate(Autopilot.Plan plan, long readingAt, long wallNow) {
        if (plan.arSource == Autopilot.ArSource.UNKNOWN || plan.arHundredths < 0) return "unknown";
        int percent = plan.arHundredths / 100;
        if (plan.arSource == Autopilot.ArSource.ESTIMATE) {
            return "~" + percent + "% estimate (" + plan.arCounted + " counted)";
        }
        String ago = readingAt < 0 ? "" : Math.max(0, wallNow - readingAt) / 60_000 + " min ago, ";
        return percent + "% dasher (" + ago + Math.max(0, plan.arOffersSince) + " since)";
    }

    /** "commit 100% -> 82% (acceptance rate below your goal)". */
    static String logCommit(int from, int to, Autopilot.Reason reason) {
        return "commit " + from + "% -> " + to + "% (" + reasonWords(reason) + ")";
    }

    /** "ar reading 55% (exempt no)"; a question that showed no single percent, "ar reading none (exempt yes)". */
    static String logReading(int percent, boolean exempt) {
        return "ar reading " + (percent >= 0 ? percent + "%" : "none") + " (exempt " + yesNo(exempt) + ")";
    }

    /** "ar exemptions ignored: 12 of 20 declines flagged" (the exemption valve). */
    static String logExemptionsIgnored(int exempt, int declined) {
        return "ar exemptions ignored: " + exempt + " of " + declined + " declines flagged";
    }

    /** "plan failed: IllegalStateException": the class only, never its message. */
    static String logPlanFailed(Throwable failure) {
        return "plan failed: " + (failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    /** "commit failed: IllegalStateException": the class only, never its message. */
    static String logCommitFailed(Throwable failure) {
        return "commit failed: " + (failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    // ---- Helpers ----

    /** "goal 70%" or "pay first". */
    private static String goalWords(int goal) {
        int sane = FilterSettings.sanitizedGoal(goal);
        return sane == FilterSettings.GOAL_PAY_FIRST ? "pay first" : "goal " + sane + "%";
    }

    /**
     * How long ago, in plain words: "less than a minute ago", "12 min ago", "3 h ago" (under two days), "4 days ago".
     * A time in the future (another clock) reads as just now.
     */
    static String ago(long ms) {
        long minutes = Math.max(0, ms) / 60_000;
        if (minutes < 1) return "less than a minute ago";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 48) return hours + " h ago";
        return hours / 24 + " days ago";
    }

    /** "3.5 min" (tenths below ten minutes, rounded half up; "2 min" when whole), "17 min" from ten minutes. */
    static String minutes(long ms) {
        long tenths = Math.max(1, (Math.max(0, ms) + 3_000) / 6_000);
        if (tenths >= 100) return (Math.max(0, ms) + 30_000) / 60_000 + " min";
        return (tenths % 10 == 0 ? String.valueOf(tenths / 10) : tenths / 10 + "." + tenths % 10) + " min";
    }

    /** Tenths as a decimal: 171 → "17.1". */
    private static String tenths(long tenths) {
        long value = Math.max(0, tenths);
        return value / 10 + "." + value % 10;
    }

    /** {@code ⌈percent × count ÷ 100⌉}: how many of {@code count} offers a share of {@code percent}% is. */
    private static int ceilPercentOf(int percent, int count) {
        return (int) ((percent * (long) count + 99) / 100);
    }

    private static String plural(long count, String word) {
        return count == 1 ? word : word + "s";
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private AutopilotText() {}
}
