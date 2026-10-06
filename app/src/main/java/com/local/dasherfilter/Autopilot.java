package com.local.dasherfilter;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Autopilot (0.5.0): the one control that moves the bar, the share of the user's minimums an offer must pay
 * ({@link FilterSettings#minimumScalePercent}). Pure and deterministic, with no Android class: it reads the decision
 * history, watched waiting and Dasher's own acceptance-rate reading and returns a {@link Plan}; the runtime commits a
 * plan between offers, one limited {@link #step} at a time.
 *
 * <p>Two priorities, in order.
 * <ol>
 *   <li>Acceptance rate first. The bar is never above {@link #shareBar}: the highest bar at which enough of the last
 *   100 counted offers would pass for the user's goal (goal + 5 points; goal + 10 while recovering; plus a stall
 *   correction of up to 10; at most 95%; pay first keeps 20%).</li>
 *   <li>Pay second. Within that limit, the bars whose long-run gross pay per hour ({@link #values}, the
 *   renewal-reward rate {@code λ·E[pay·1A] ÷ (1 + λ·E[minutes·1A])}) is within 2% of the best; of those, the one
 *   closest to the bar it already has.</li>
 * </ol>
 *
 * <p>Every decision is exact: whole numbers, {@code long}s and {@code BigInteger} cross-multiplication. The only
 * floating point is each recency weight ({@link #weight}), computed once with {@code StrictMath} and rounded to a whole
 * number. θ, the largest bar at which an offer is not declined, is {@link AreaScore#passThreshold}; there is no second
 * requirement formula here.
 *
 * <p>Autopilot never accepts anything, never decides a single offer and learns nothing from what the user accepts or
 * declines: the history only counts offers and outcomes for the acceptance rate, and the market for the value.
 */
final class Autopilot {
    // ---- Bar ----
    static final int BAR_MIN = 50;
    static final int BAR_MAX = 150;
    /** The bar while Autopilot is off: exactly the minimums. */
    static final int BAR_OFF = FilterSettings.BAR_AT_MINIMUMS;

    // ---- Window ----
    /** Counted offers: DoorDash reports acceptance rate over the last 100. */
    static final int WINDOW = 100;
    /** Counted offers before any acceptance-rate or value move. */
    static final int MIN_WINDOW = 20;
    /** Readable offers before any value move. */
    static final int MIN_MIX = 20;
    /** The same facts this close together are one offer (DecisionLog's merge window, AutoAccept.SUPPRESS_MS). */
    static final long DEDUP_MS = 120_000L;

    // ---- Need (whole percent of counted offers that must pass) ----
    /** The owner's "small margin" above the goal. */
    static final int STEADY_MARGIN = 5;
    /** The margin while recovering, kept until the acceptance rate reaches the goal + {@link #RECOVERY_EXIT_MARGIN}. */
    static final int RECOVERY_MARGIN = 10;
    static final int RECOVERY_EXIT_MARGIN = 2;
    static final int NEED_CAP = 95;
    /** Pay first still keeps one offer in five coming to the user. */
    static final int PAY_FIRST_FLOOR = 20;

    // ---- Stall correction ----
    static final int EXTRA_STEP = 2;
    static final int EXTRA_MAX = 10;
    /** Accounted offers between checkpoints. */
    static final int CHECK_EVERY = 25;
    /** A reading at least the goal + this releases the correction. */
    static final int RELEASE_MARGIN = 5;

    // ---- Offer rate and value ----
    static final long HALF_LIFE_MS = 7_200_000L;
    /** A full recency weight. */
    static final int WEIGHT_ONE = 1_024;
    /** The prior: one full-weight arrival per ten full-weight minutes of waiting, a cautious 6 offers an hour. */
    static final long PRIOR_ARRIVAL_WEIGHT = WEIGHT_ONE;
    static final long PRIOR_EXPOSURE_WEIGHT = 10 * 60_000L * WEIGHT_ONE;
    /** A bar is acceptable when its value is at least 98/100 of the best. */
    static final int VALUE_TOLERANCE_PERCENT = 98;
    /** Offers paying more than $150 an hour of Dasher's minutes are left out of the mix as outliers. */
    static final long MAX_RATE_CENTS_PER_HOUR = 15_000L;

    // ---- Commit timing ----
    static final long MIN_COMMIT_SPACING_MS = 60_000L;
    static final int DOWN_STEP = 20;
    static final int UP_STEP = 5;
    static final long MIN_RAISE_SPACING_MS = 300_000L;
    static final int MIN_OFFERS_BETWEEN_RAISES = 3;
    static final int DEADBAND = 3;
    static final long TICK_MS = 60_000L;
    static final long PLAN_MAX_AGE_MS = 900_000L;
    /** A commit waits this long after the last offer evidence. */
    static final long QUIET_AFTER_OFFER_MS = 10_000L;

    // ---- Acceptance-rate reading ----
    static final long AR_MAX_AGE_MS = 7L * 24 * 60 * 60_000L;
    /** A reading is stale once this many accounted offers came after it: DoorDash's window has fully turned over. */
    static final int AR_STALE_OFFERS = 100;
    /** Accepted plus declined offers before the app's own estimate stands in for a reading. */
    static final int AR_MIN_COUNTED = 20;
    /** The exemption valve: at least this many declines, more than half of them marked exempt. */
    static final int EXEMPT_VALVE_MIN_DECLINES = 10;
    /** The same offer's reading within this time only refreshes the reading's time. */
    static final long READING_DEDUP_MS = 120_000L;

    // ---- Goals and starters ----
    /** The goals offered, in the chooser's order: keep a top tier (preselected), keep a tier, pay first. */
    static final List<Integer> GOALS = Collections.unmodifiableList(Arrays.asList(
            FilterSettings.GOAL_TOP_TIER, FilterSettings.GOAL_TIER, FilterSettings.GOAL_PAY_FIRST));
    /** Typical minimums: $4.00, $1.00 a mile, 25¢ a minute ($15 an hour), no max stops. */
    static final int STARTER_FLAT_CENTS = 400;
    static final int STARTER_PER_MILE_CENTS = 100;
    static final int STARTER_PER_MINUTE_CENTS = 25;
    static final int STARTER_MAX_STOPS = 0;

    // ---- Display only ----
    /** The offer interval shown in the details covers this much recent waiting... */
    static final long RECENT_RATE_MS = 7_200_000L;
    /** ...and needs at least this many arrivals. */
    static final int RECENT_MIN_ARRIVALS = 3;

    /** No checkpoint, or no raise yet. */
    static final long NEVER = Long.MIN_VALUE;

    /** What a plan is doing. */
    enum Mode {
        /** Autopilot is off: the bar is exactly the minimums. */
        OFF,
        /** No pay, per-mile or hourly minimum to scale. */
        NO_RULES,
        /** Fewer than {@link #MIN_WINDOW} counted offers: the bar holds at 100. */
        LEARNING,
        /** Even the lowest bar passes too few offers for the goal. */
        PINNED,
        /** The acceptance-rate limit binds while recovering toward the goal. */
        RECOVERY,
        /** The acceptance-rate limit binds while holding the goal. */
        GOAL,
        /** Pay first's one-in-five floor binds. */
        PASS_FLOOR,
        /** No value data, and the limit allows exactly the minimums. */
        AT_MINIMUMS,
        /** Pay decides, within the acceptance-rate limit. */
        VALUE
    }

    /** Where the acceptance rate came from. */
    enum ArSource {
        /** Dasher's own reading, carried forward over the offers since. */
        DASHER,
        /** The app's own count of accepted and declined offers. */
        ESTIMATE,
        UNKNOWN
    }

    /** Why the bar moved: a user's change (a jump) or what the plan was doing; each carries its words. */
    enum Reason {
        TURNED_ON(true, "Autopilot turned on"),
        GOAL_CHANGED(true, "you changed your goal"),
        RULES_CHANGED(true, "you changed your minimums"),
        CLEARED(true, "history cleared"),
        LEARNING(false, "learning from your offers"),
        PINNED(false, "your minimums are high for these offers"),
        RECOVERY(false, "acceptance rate below your goal"),
        GOAL(false, "keeping enough offers for your goal"),
        PASS_FLOOR(false, "keeping at least 1 in 5 offers coming to you"),
        AT_MINIMUMS(false, "holding at your minimums"),
        VALUE_UP(false, "offers are coming often"),
        VALUE_DOWN(false, "offers are slower");

        /** A user's change: the next commit goes straight to the plan's target. */
        final boolean jump;
        final String words;

        Reason(boolean jump, String words) {
            this.jump = jump;
            this.words = words;
        }
    }

    /** What a {@link #step} did. */
    enum StepKind {
        /** A user's change: straight to the target. */
        JUMP,
        /** Already at the target. */
        HOLD,
        /** Less than a minute since the last commit. */
        SPACING,
        /** Within the deadband of 100 with 100 as the target: exactly the minimums again. */
        SNAP,
        /** Within the deadband. */
        DEADBAND,
        LOWER,
        /** A raise waits for five minutes and three counted offers since the last one. */
        RAISE_WAIT,
        RAISE
    }

    // ---- Inputs ----

    /** One decision-history line as Autopilot counts it. */
    static final class OfferRecord {
        /**
         * The offer's facts, never with a "+$" bound on unread pay; unknown when the line kept none. A stored line
         * keeps no bound, so a line still in memory must not count one either: unread pay passes at every bar, and
         * the same history plans the same before and after a restart.
         */
        final OfferSnapshot facts;
        /** When it was decided (wall clock). */
        final long at;
        final boolean addOn;
        /** A replayed line: the same offer again, not a new one. */
        final boolean replay;
        final boolean accepted;
        final boolean declined;
        /** Dasher said declining it does not lower the acceptance rate. */
        final boolean arExempt;

        OfferRecord(OfferSnapshot facts, long at, boolean addOn, boolean replay, boolean accepted, boolean declined,
                    boolean arExempt) {
            this.facts = facts == null ? OfferSnapshot.UNKNOWN : facts.withoutPayBound();
            this.at = at;
            this.addOn = addOn;
            this.replay = replay;
            this.accepted = accepted;
            this.declined = declined;
            this.arExempt = arExempt;
        }

        /** An accept in acceptance-rate accounting. */
        boolean countsAccepted() {
            return accepted;
        }

        /**
         * A decline in acceptance-rate accounting: declined and not also accepted, so no line counts twice (an
         * acceptance wins, as in DecisionLog.outcome). A line that is neither is an unknown outcome.
         */
        boolean countsDeclined() {
            return declined && !accepted;
        }
    }

    /**
     * Dasher's acceptance rate as its decline question showed it: a whole percent, when (wall clock), and the numeric
     * fingerprint of the offer the question was about ({@link OfferSnapshot#fingerprint}; "" when not known). The
     * percent is the rate before that offer's decline counts: the question comes before the decline is final.
     */
    static final class Reading {
        final int percent;
        final long at;
        final String fingerprint;

        Reading(int percent, long at) {
            this(percent, at, "");
        }

        Reading(int percent, long at, String fingerprint) {
            if (percent < 0 || percent > 100 || at < 0) throw new IllegalArgumentException("acceptance-rate reading");
            this.percent = percent;
            this.at = at;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
        }
    }

    /** What the runtime keeps between plans. */
    static final class State {
        /** Before anything happened: not recovering, no correction, no checkpoint, no raise, the bar at 100. */
        static final State INITIAL = new State(false, 0, NEVER, -1, NEVER, BAR_OFF, null);

        /** Recovering toward the goal (the need is the goal + 10 until the rate reaches the goal + 2). */
        final boolean recovering;
        /** The stall correction, 0–10 extra points of need. */
        final int extra;
        /**
         * The stall correction's checkpoint (wall clock; {@link #NEVER} for none) and the acceptance rate carried
         * forward from Dasher's reading then, in hundredths of a percent (-1: none, or not from a Dasher reading).
         */
        final long checkpointAt;
        final int checkpointAr;
        /** The last raise (wall clock); {@link #NEVER} before the first. */
        final long raisedAt;
        /** The committed bar. */
        final int current;
        /** A user's change still waiting for its commit (a {@link Reason#jump} reason), or null. */
        final Reason jumpCause;

        State(boolean recovering, int extra, long checkpointAt, int checkpointAr, long raisedAt, int current,
              Reason jumpCause) {
            if (jumpCause != null && !jumpCause.jump) throw new IllegalArgumentException("not a jump cause");
            this.recovering = recovering;
            this.extra = Math.max(0, Math.min(EXTRA_MAX, extra));
            this.checkpointAt = checkpointAt;
            this.checkpointAr = checkpointAt == NEVER ? -1 : Math.max(-1, Math.min(10_000, checkpointAr));
            this.raisedAt = raisedAt;
            this.current = current;
            this.jumpCause = jumpCause;
        }
    }

    /** Everything one plan reads. */
    static final class Inputs {
        final FilterSettings rules;
        /** Decision-history lines, newest first (up to 200). */
        final List<OfferRecord> newestFirst;
        /** Watched waiting, including the open wait, as QualifyingWaitStore's snapshot gives it. */
        final List<QualifyingWait.Sample> waits;
        /** The latest reading, or null. */
        final Reading reading;
        final State state;
        final long wallNow;
        final long generation;

        Inputs(FilterSettings rules, List<OfferRecord> newestFirst, List<QualifyingWait.Sample> waits,
               Reading reading, State state, long wallNow, long generation) {
            if (rules == null) throw new IllegalArgumentException("rules");
            this.rules = rules;
            this.newestFirst = newestFirst == null ? Collections.<OfferRecord>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(newestFirst));
            this.waits = waits == null ? Collections.<QualifyingWait.Sample>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(waits));
            this.reading = reading;
            this.state = state == null ? State.INITIAL : state;
            this.wallNow = wallNow;
            this.generation = generation;
        }
    }

    // ---- Intermediate results ----

    /** The acceptance rate now, in hundredths of a percent (-1 unknown). */
    static final class ArNow {
        static final ArNow UNKNOWN = new ArNow(-1, ArSource.UNKNOWN, -1, -1);

        final int hundredths;
        final ArSource source;
        /** With Dasher's reading: the accounted offers since it ({@code n_s}); otherwise -1. */
        final int offersSince;
        /** With the app's estimate: the accepted and declined offers it counted; otherwise -1. */
        final int counted;

        ArNow(int hundredths, ArSource source, int offersSince, int counted) {
            this.hundredths = hundredths;
            this.source = source;
            this.offersSince = offersSince;
            this.counted = counted;
        }
    }

    /** The pass share needed, in whole percent, and whether the plan is recovering. */
    static final class Need {
        final int need;
        final boolean recovering;

        Need(int need, boolean recovering) {
            this.need = need;
            this.recovering = recovering;
        }
    }

    /**
     * The stall correction after a plan: its extra points and its checkpoint (when, and the carried-forward rate then
     * in hundredths, -1 when it was not from a Dasher reading).
     */
    static final class Correction {
        final int extra;
        final long checkpointAt;
        final int checkpointAr;

        Correction(int extra, long checkpointAt, int checkpointAr) {
            this.extra = extra;
            this.checkpointAt = checkpointAt;
            this.checkpointAr = checkpointAr;
        }
    }

    /**
     * The offer rate, {@code λ = numerator ÷ denominator} offers per minute of watched waiting, with the weights it
     * came from: {@code numerator = 60,000 × (arrivalWeight + prior)}, {@code denominator = exposureWeight + prior}.
     */
    static final class Rate {
        final long arrivalWeight;
        final long exposureWeight;
        final long numerator;
        final long denominator;

        Rate(long arrivalWeight, long exposureWeight, long numerator, long denominator) {
            this.arrivalWeight = arrivalWeight;
            this.exposureWeight = exposureWeight;
            this.numerator = numerator;
            this.denominator = denominator;
        }
    }

    /** The share bar: the highest bar meeting the need; {@link #BAR_MIN} and pinned when none does. */
    static final class ShareBar {
        final int bar;
        final boolean pinned;

        ShareBar(int bar, boolean pinned) {
            this.bar = bar;
            this.pinned = pinned;
        }
    }

    /** One readable counted offer: its θ, pay and Dasher's minutes. */
    static final class MixLine {
        final int theta;
        final int payCents;
        final int minutes;

        MixLine(int theta, int payCents, int minutes) {
            this.theta = theta;
            this.payCents = payCents;
            this.minutes = minutes;
        }
    }

    /**
     * The value of each bar from {@link #BAR_MIN} to {@link #hi}, exactly: {@code V(σ) = N(σ) ÷ Dn(σ)} cents per hour,
     * with {@code N(σ) = 60·L·SP(σ)} and {@code Dn(σ) = n_r·D + L·ST(σ)} (SP and ST the pay and minutes of the mix
     * passing at σ). Every {@code Dn} is positive.
     */
    static final class Values {
        final int lo = BAR_MIN;
        final int hi;
        private final BigInteger[] numerators;
        private final BigInteger[] denominators;

        private Values(int hi, BigInteger[] numerators, BigInteger[] denominators) {
            this.hi = hi;
            this.numerators = numerators;
            this.denominators = denominators;
        }

        BigInteger numerator(int bar) {
            return numerators[index(bar)];
        }

        BigInteger denominator(int bar) {
            return denominators[index(bar)];
        }

        /** Display only: V(bar) in whole cents per hour, rounded down. */
        long centsPerHour(int bar) {
            return numerator(bar).divide(denominator(bar)).longValue();
        }

        private int index(int bar) {
            if (bar < lo || bar > hi) throw new IllegalArgumentException("bar " + bar + " outside " + lo + "–" + hi);
            return bar - lo;
        }
    }

    /** The bars within 2% of the best value: σ* (lowest on ties), the set, and its least and greatest members. */
    static final class Acceptable {
        final int best;
        final int lo;
        final int hi;
        private final boolean[] members;

        private Acceptable(int best, int lo, int hi, boolean[] members) {
            this.best = best;
            this.lo = lo;
            this.hi = hi;
            this.members = members;
        }

        boolean contains(int bar) {
            return bar >= BAR_MIN && bar - BAR_MIN < members.length && members[bar - BAR_MIN];
        }

        /** The member closest to {@code current}; the lower of two equally close. */
        int closestTo(int current) {
            int closest = -1;
            long distance = Long.MAX_VALUE;
            for (int i = 0; i < members.length; i++) {
                if (!members[i]) continue;
                long d = Math.abs((long) (BAR_MIN + i) - current);
                if (d < distance) {
                    distance = d;
                    closest = BAR_MIN + i;
                }
            }
            return closest;
        }
    }

    /** One commit decision: the bar to commit (equal to the current one when holding) and why. */
    static final class Step {
        final int next;
        final StepKind kind;

        Step(int next, StepKind kind) {
            this.next = next;
            this.kind = kind;
        }
    }

    /** Display only: the readable counted offers passing at a bar, their pay and Dasher's minutes for them. */
    static final class Paid {
        final int count;
        final long payCents;
        final long minutes;

        Paid(int count, long payCents, long minutes) {
            this.count = count;
            this.payCents = payCents;
            this.minutes = minutes;
        }

        /** What they paid per hour of Dasher's minutes, in whole cents (rounded down); -1 with no minutes. */
        long centsPerHour() {
            return minutes <= 0 ? -1 : payCents * 60 / minutes;
        }
    }

    // ---- The plan ----

    /** A plan: the bar Autopilot wants, why, and every figure behind it. Immutable. */
    static final class Plan {
        final Mode mode;
        /** The bar Autopilot wants; commits move toward it one {@link #step} at a time. */
        final int target;
        /** The committed bar the plan started from. */
        final int current;
        final int goal;
        /** The rules the plan depends on ({@link FilterSettings#rulesKey}); a plan for other rules is stale. */
        final String rulesKey;
        /** When it was planned (wall clock), and the runtime's generation then. */
        final long computedAt;
        final long generation;

        /** Counted offers (n) and how many pass at the target. */
        final int counted;
        final int passAtTarget;
        /** The pass share needed in whole percent, the share bar, and whether even 50% misses it (-1: not planned). */
        final int need;
        final int barShare;
        final boolean pinned;

        /** The acceptance rate in hundredths of a percent (-1 unknown), and where it came from. */
        final int arHundredths;
        final ArSource arSource;
        /** Accounted offers since Dasher's reading ({@code n_s}); -1 unless the rate is Dasher's. */
        final int arOffersSince;
        /** Accepted and declined offers behind the app's estimate; -1 unless the rate is estimated. */
        final int arCounted;
        /** About how many more accepts reach the goal ({@code ⌈goal − AR⌉}); 0 at or above it, or unknown. */
        final int acceptsNeeded;
        /** "Does not lower acceptance rate" marks too many declines to be per-offer, so they are counted anyway. */
        final boolean exemptionsIgnored;
        /** Declined and exempt-marked offers in the counted window. */
        final int declinedInWindow;
        final int exemptInWindow;

        /** λ = {@code lambdaNumerator ÷ lambdaDenominator} offers per minute of watched waiting, and its weights. */
        final long lambdaNumerator;
        final long lambdaDenominator;
        final long arrivalWeight;
        final long exposureWeight;
        /** Readable counted offers (n_r). */
        final int mixSize;
        /** σ*, and the least and greatest bars within 2% of its value; -1 when the value step did not run. */
        final int bestBar;
        final int acceptableLow;
        final int acceptableHigh;

        /** Counted offers since the last raise. */
        final int offersSinceRaise;
        /** The state after this plan, for the runtime to keep. */
        final boolean recovering;
        final int extra;
        final long checkpointAt;
        /** The carried-forward rate at the checkpoint, hundredths of a percent; -1 when none from a Dasher reading. */
        final int checkpointAr;

        /** Display only: watched waiting and arrivals over the last two hours. */
        final long recentWaitMs;
        final int recentArrivals;

        private final int[] windowThetas;
        private final int[] mixThetas;
        private final long[] mixPay;
        private final long[] mixMinutes;

        private Plan(Draft d, Mode mode, int target) {
            this.mode = mode;
            this.target = target;
            this.current = d.state.current;
            this.goal = d.rules.autopilotGoalPercent;
            this.rulesKey = d.rules.rulesKey();
            this.computedAt = d.wallNow;
            this.generation = d.generation;
            this.counted = d.windowThetas.length;
            this.passAtTarget = passing(d.windowThetas, target);
            this.need = d.need;
            this.barShare = d.barShare;
            this.pinned = d.pinned;
            this.arHundredths = d.ar.hundredths;
            this.arSource = d.ar.source;
            this.arOffersSince = d.ar.offersSince;
            this.arCounted = d.ar.counted;
            this.acceptsNeeded = acceptsNeeded(goal, d.ar.hundredths);
            this.exemptionsIgnored = d.exemptionsIgnored;
            this.declinedInWindow = d.declinedInWindow;
            this.exemptInWindow = d.exemptInWindow;
            this.lambdaNumerator = d.rate.numerator;
            this.lambdaDenominator = d.rate.denominator;
            this.arrivalWeight = d.rate.arrivalWeight;
            this.exposureWeight = d.rate.exposureWeight;
            this.mixSize = d.mixThetas.length;
            this.bestBar = d.bestBar;
            this.acceptableLow = d.acceptableLow;
            this.acceptableHigh = d.acceptableHigh;
            this.offersSinceRaise = d.offersSinceRaise;
            this.recovering = d.recovering;
            this.extra = d.extra;
            this.checkpointAt = d.checkpointAt;
            this.checkpointAr = d.checkpointAr;
            this.recentWaitMs = d.recentWaitMs;
            this.recentArrivals = d.recentArrivals;
            this.windowThetas = d.windowThetas;
            this.mixThetas = d.mixThetas;
            this.mixPay = d.mixPay;
            this.mixMinutes = d.mixMinutes;
        }

        /** How many counted offers would pass at {@code bar}. */
        int passAt(int bar) {
            return passing(windowThetas, bar);
        }

        /** Display only: what the readable counted offers passing at {@code bar} paid, and Dasher's minutes. */
        Paid paidAt(int bar) {
            int count = passing(mixThetas, bar);
            return new Paid(count, mixPay[count], mixMinutes[count]);
        }

        /** Display only: the mean watched wait per offer over the last two hours; -1 with fewer than 3 arrivals. */
        long offerIntervalMs() {
            return recentArrivals < RECENT_MIN_ARRIVALS ? -1 : recentWaitMs / recentArrivals;
        }

        /** Display only: λ in tenths of an offer per hour of watched waiting, rounded half up. */
        long offersPerHourTenths() {
            return (1_200L * lambdaNumerator + lambdaDenominator) / (2 * lambdaDenominator);
        }

        /** The acceptance rate is known and below a goal. */
        boolean belowGoal() {
            return goal > 0 && arHundredths >= 0 && arHundredths < 100 * goal;
        }
    }

    /** What {@link #plan} has worked out so far; it becomes the {@link Plan}. */
    private static final class Draft {
        final FilterSettings rules;
        final State state;
        final long wallNow;
        final long generation;
        final int[] windowThetas;
        final ArNow ar;
        final Rate rate;
        final boolean exemptionsIgnored;
        final int declinedInWindow;
        final int exemptInWindow;
        final int offersSinceRaise;
        final long recentWaitMs;
        final int recentArrivals;
        final int[] mixThetas;
        final long[] mixPay;
        final long[] mixMinutes;
        int need = -1;
        int barShare = -1;
        boolean pinned;
        int bestBar = -1;
        int acceptableLow = -1;
        int acceptableHigh = -1;
        boolean recovering;
        int extra;
        long checkpointAt;
        int checkpointAr;

        Draft(Inputs in, List<OfferRecord> window, int[] thetas, List<MixLine> mix, ArNow ar, Rate rate,
              boolean exemptionsIgnored) {
            rules = in.rules;
            state = in.state;
            wallNow = in.wallNow;
            generation = in.generation;
            windowThetas = descending(thetas);
            this.ar = ar;
            this.rate = rate;
            this.exemptionsIgnored = exemptionsIgnored;
            int declined = 0;
            int exempt = 0;
            int sinceRaise = 0;
            for (OfferRecord record : window) {
                if (record.countsDeclined()) declined++;
                if (record.arExempt) exempt++;
                if (record.at > state.raisedAt) sinceRaise++;
            }
            declinedInWindow = declined;
            exemptInWindow = exempt;
            offersSinceRaise = sinceRaise;
            long waited = 0;
            int arrivals = 0;
            for (QualifyingWait.Sample sample : in.waits) {
                if (sample == null || in.wallNow - sample.at > RECENT_RATE_MS) continue;
                waited += sample.observedMs;
                if (sample.arrival != null) arrivals++;
            }
            recentWaitMs = waited;
            recentArrivals = arrivals;
            MixLine[] lines = byThetaDescending(mix);
            mixThetas = new int[lines.length];
            mixPay = new long[lines.length + 1];
            mixMinutes = new long[lines.length + 1];
            for (int i = 0; i < lines.length; i++) {
                mixThetas[i] = lines[i].theta;
                mixPay[i + 1] = mixPay[i] + lines[i].payCents;
                mixMinutes[i + 1] = mixMinutes[i] + lines[i].minutes;
            }
            recovering = state.recovering;
            extra = state.extra;
            checkpointAt = state.checkpointAt;
            checkpointAr = state.checkpointAr;
        }

        /** Autopilot holds the bar at exactly the minimums; the state is unchanged. */
        Plan gated(Mode mode) {
            return new Plan(this, mode, BAR_OFF);
        }

        Plan decided(Mode mode, int target) {
            return new Plan(this, mode, target);
        }
    }

    /**
     * The plan for {@code in}, in the spec's order.
     * <ol start="0">
     *   <li>Gates, holding the bar at 100: Autopilot off ({@link Mode#OFF}); no money minimum
     *   ({@link Mode#NO_RULES}); fewer than 20 counted offers ({@link Mode#LEARNING}).</li>
     *   <li>The acceptance rate, the stall correction and the need.</li>
     *   <li>The share bar; when even 50% misses the need, 50 ({@link Mode#PINNED}).</li>
     *   <li>With fewer than 20 readable offers, {@code min(share bar, 100)}.</li>
     *   <li>Otherwise the acceptable bar closest to the current one, the lower of two equally close.</li>
     * </ol>
     * The acceptance rate, the offer rate and the display figures are worked out in every mode.
     */
    static Plan plan(Inputs in) {
        FilterSettings rules = in.rules;
        State state = in.state;
        int goal = rules.autopilotGoalPercent;
        List<OfferRecord> counted = countedLines(in.newestFirst);
        List<OfferRecord> window = firstOf(counted, WINDOW);
        int[] thetas = new int[window.size()];
        for (int i = 0; i < thetas.length; i++) thetas[i] = AreaScore.passThreshold(rules, window.get(i).facts);
        boolean ignoreExempt = exemptionsIgnored(window);
        ArNow ar = arNow(in.reading, counted, ignoreExempt, in.wallNow);
        Rate rate = lambda(in.waits, in.wallNow);
        List<MixLine> mix = mix(rules, window, thetas);
        Draft draft = new Draft(in, window, thetas, mix, ar, rate, ignoreExempt);

        if (!rules.autopilot) return draft.gated(Mode.OFF);
        if (!rules.hasMonetaryRule()) return draft.gated(Mode.NO_RULES);
        if (thetas.length < MIN_WINDOW) return draft.gated(Mode.LEARNING);

        Correction correction = correction(goal, state, ar, accounting(counted, ignoreExempt), in.wallNow);
        Need need = need(goal, ar.hundredths, state.recovering, correction.extra);
        ShareBar share = shareBar(thetas, need.need);
        draft.extra = correction.extra;
        draft.checkpointAt = correction.checkpointAt;
        draft.checkpointAr = correction.checkpointAr;
        draft.recovering = need.recovering;
        draft.need = need.need;
        draft.barShare = share.bar;
        draft.pinned = share.pinned;
        if (share.pinned) return draft.decided(Mode.PINNED, BAR_MIN);

        Mode limited = need.recovering ? Mode.RECOVERY : goal > 0 ? Mode.GOAL : Mode.PASS_FLOOR;
        if (mix.size() < MIN_MIX) {
            int target = Math.min(share.bar, BAR_OFF);
            return draft.decided(target < BAR_OFF ? limited : Mode.AT_MINIMUMS, target);
        }
        Acceptable ok = acceptable(values(mix, rate.numerator, rate.denominator, share.bar));
        draft.bestBar = ok.best;
        draft.acceptableLow = ok.lo;
        draft.acceptableHigh = ok.hi;
        int target = ok.closestTo(state.current);
        // The acceptance-rate limit binds when the bar it allows is both the best Autopilot may pick and the pick.
        return draft.decided(target == share.bar && ok.hi == share.bar ? limited : Mode.VALUE, target);
    }

    // ---- (a) The counted window ----

    /**
     * The counted window W: the newest {@link #WINDOW} {@link #countedLines counted lines}. Its offers make the pass
     * share and the value; the acceptance rate counts {@link #accounting}, which may reach further back.
     */
    static List<OfferRecord> countedWindow(List<OfferRecord> newestFirst) {
        return firstOf(countedLines(newestFirst), WINDOW);
    }

    /**
     * Every counted line of the history, newest first: skipping add-ons, replays, misread offers
     * ({@link OfferSanity#looksMisread}) and any line whose facts repeat a kept line's fingerprint within
     * {@link #DEDUP_MS}.
     */
    static List<OfferRecord> countedLines(List<OfferRecord> newestFirst) {
        List<OfferRecord> kept = new ArrayList<>();
        if (newestFirst == null) return kept;
        Map<String, List<Long>> keptAt = new HashMap<>();
        for (OfferRecord record : newestFirst) {
            if (record == null || record.addOn || record.replay || OfferSanity.looksMisread(record.facts)) continue;
            String fingerprint = record.facts.fingerprint();
            List<Long> times = keptAt.get(fingerprint);
            if (times == null) {
                times = new ArrayList<>();
                keptAt.put(fingerprint, times);
            } else if (within(times, record.at)) {
                continue;
            }
            times.add(record.at);
            kept.add(record);
        }
        return Collections.unmodifiableList(kept);
    }

    /** The first {@code count} of {@code lines} (all of them when fewer), unmodifiable. */
    private static List<OfferRecord> firstOf(List<OfferRecord> lines, int count) {
        if (lines.size() <= count) return lines;
        return Collections.unmodifiableList(new ArrayList<>(lines.subList(0, count)));
    }

    private static boolean within(List<Long> times, long at) {
        for (long time : times) {
            if (Math.abs(time - at) <= DEDUP_MS) return true;
        }
        return false;
    }

    // ---- (d) Acceptance rate ----

    /**
     * The exemption valve: "does not lower acceptance rate" is treated as Dasher's general wording, not a per-offer
     * mark, when the window holds at least 10 declines and more exempt-marked lines than half of them.
     */
    static boolean exemptionsIgnored(List<OfferRecord> window) {
        int declined = 0;
        int exempt = 0;
        for (OfferRecord record : window) {
            if (record.countsDeclined()) declined++;
            if (record.arExempt) exempt++;
        }
        return declined >= EXEMPT_VALVE_MIN_DECLINES && 2 * exempt > declined;
    }

    /**
     * The accounting set A, newest first: DoorDash's window as far as the history shows it, the newest
     * {@link #WINDOW} of {@code counted} (the {@link #countedLines counted lines}, or the counted window) without the
     * exempt-marked ones, so it reaches past the counted window by as many exempt lines as sit in it. When the valve
     * ignores the marks it is the newest {@link #WINDOW} counted lines, exempt ones included: the counted window.
     */
    static List<OfferRecord> accounting(List<OfferRecord> counted, boolean ignoreExempt) {
        List<OfferRecord> accounted = new ArrayList<>();
        if (counted == null) return accounted;
        for (OfferRecord record : counted) {
            if (accounted.size() >= WINDOW) break;
            if (ignoreExempt || !record.arExempt) accounted.add(record);
        }
        return accounted;
    }

    /**
     * The acceptance rate now, over the accounting set A of {@code counted} ({@link #accounting}). With a fresh Dasher
     * reading r (at most 7 days old, and fewer than 100 accounted offers since it: DoorDash's window has not yet
     * turned over), {@code clamp(100·r + 100·k_s − r·n_s, 0, 10000)} hundredths over the {@code n_s} offers since,
     * {@code k_s} of them accepted: the offers rolling out of DoorDash's window are taken as accepted at rate r. The
     * offer whose decline question showed r counts among the offers since ({@link #ownLine}): r is the rate before its
     * decline counts. Otherwise, with at least 20 accepted or declined offers, {@code ⌊10000·a ÷ (a + d)⌋} (unknown
     * outcomes left out). Otherwise unknown.
     */
    static ArNow arNow(Reading reading, List<OfferRecord> counted, boolean ignoreExempt, long wallNow) {
        List<OfferRecord> accounted = accounting(counted, ignoreExempt);
        if (reading != null && wallNow - reading.at <= AR_MAX_AGE_MS) {
            OfferRecord own = ownLine(reading, accounted);
            int since = 0;
            int acceptedSince = 0;
            for (OfferRecord record : accounted) {
                if (record.at <= reading.at && record != own) continue;
                since++;
                if (record.countsAccepted()) acceptedSince++;
            }
            if (since < AR_STALE_OFFERS) {
                long hundredths = 100L * reading.percent + 100L * acceptedSince - (long) reading.percent * since;
                return new ArNow((int) Math.max(0, Math.min(10_000, hundredths)), ArSource.DASHER, since, -1);
            }
        }
        int accepted = 0;
        int declined = 0;
        for (OfferRecord record : accounted) {
            if (record.countsAccepted()) accepted++;
            else if (record.countsDeclined()) declined++;
        }
        if (accepted + declined >= AR_MIN_COUNTED) {
            return new ArNow((int) (10_000L * accepted / (accepted + declined)), ArSource.ESTIMATE, -1,
                    accepted + declined);
        }
        return ArNow.UNKNOWN;
    }

    /**
     * The accounted line of the offer whose decline question showed {@code reading}: the one with its fingerprint
     * within {@link #DEDUP_MS} of the reading (the counted lines hold at most one), when it was recorded at or before
     * the reading (a question follows its offer). Null when there is none, when the reading names no offer, or when
     * that line is stamped after the reading and so already counts among the offers since.
     */
    private static OfferRecord ownLine(Reading reading, List<OfferRecord> accounted) {
        if (reading.fingerprint.isEmpty()) return null;
        for (OfferRecord record : accounted) {
            if (Math.abs(reading.at - record.at) > DEDUP_MS) continue;
            if (!reading.fingerprint.equals(record.facts.fingerprint())) continue;
            return record.at <= reading.at ? record : null;
        }
        return null;
    }

    /** About how many more accepts reach the goal: {@code ⌈(100·goal − AR) ÷ 100⌉} below it; 0 otherwise. */
    static int acceptsNeeded(int goal, int arHundredths) {
        if (goal <= 0 || arHundredths < 0 || arHundredths >= 100 * goal) return 0;
        return (100 * goal - arHundredths + 99) / 100;
    }

    // ---- (e) The need ----

    /**
     * The pass share needed, in whole percent, and whether recovering. Pay first: 20. Unknown rate: the goal + 5 +
     * extra, not recovering. Known: recovering below the goal, and while recovering until the goal + 2; the goal + 10 +
     * extra while recovering, the goal + 5 + extra otherwise. At most 95.
     */
    static Need need(int goal, int arHundredths, boolean recovering, int extra) {
        if (goal <= 0) return new Need(PAY_FIRST_FLOOR, false);
        if (arHundredths < 0) return new Need(Math.min(NEED_CAP, goal + STEADY_MARGIN + extra), false);
        boolean stillRecovering = arHundredths < 100 * goal
                || (recovering && arHundredths < 100 * (goal + RECOVERY_EXIT_MARGIN));
        return new Need(Math.min(NEED_CAP, goal + (stillRecovering ? RECOVERY_MARGIN : STEADY_MARGIN) + extra),
                stillRecovering);
    }

    // ---- (f) The stall correction ----

    /**
     * The stall correction (anti-windup), on the acceptance rate now ({@code ar}, the carried-forward AR_h), never on
     * Dasher's raw reading: AR_h credits every accept since the reading, so a reading that is merely old (the user
     * declines few offers, or declines through Dasher's notification, which shows no question) reads as the recovery
     * it is, not as a stall. It runs only with a goal, at least 25 accounted offers since the checkpoint (since ever,
     * without one). Without a checkpoint it sets one and changes nothing. Otherwise: AR_h from a fresh Dasher reading,
     * below the goal and no higher than at the checkpoint (when that was from a Dasher reading too) adds 2 (at most
     * 10); AR_h known from any source (Dasher's or the app's own estimate) at least the goal + 5 takes 2 off (at least
     * 0), so the correction never stays latched once the rate is well above the goal; then the checkpoint moves to now,
     * keeping AR_h when it is Dasher's (-1 otherwise).
     *
     * @param accounted the accounting set A ({@link #accounting}), newest first
     */
    static Correction correction(int goal, State state, ArNow ar, List<OfferRecord> accounted, long wallNow) {
        Correction unchanged = new Correction(state.extra, state.checkpointAt, state.checkpointAr);
        if (goal <= 0) return unchanged;
        int sinceCheckpoint = 0;
        for (OfferRecord record : accounted) if (record.at > state.checkpointAt) sinceCheckpoint++;
        if (sinceCheckpoint < CHECK_EVERY) return unchanged;
        int carried = ar.source == ArSource.DASHER ? ar.hundredths : -1;
        if (state.checkpointAt == NEVER) return new Correction(state.extra, wallNow, carried);
        int extra = state.extra;
        if (carried >= 0 && state.checkpointAr >= 0 && carried < 100 * goal && carried <= state.checkpointAr) {
            extra = Math.min(EXTRA_MAX, extra + EXTRA_STEP);
        } else if (ar.hundredths >= 0 && ar.hundredths >= 100 * (goal + RELEASE_MARGIN)) {
            extra = Math.max(0, extra - EXTRA_STEP);
        }
        return new Correction(extra, wallNow, carried);
    }

    // ---- (g) The offer rate ----

    /**
     * A sample's recency weight: {@code round(1024 × 2^(−age ÷ 2 h))}, 1024 at or before age 0. The only floating
     * point in Autopilot, computed with {@code StrictMath} so it is the same everywhere.
     */
    static int weight(long ageMs) {
        if (ageMs <= 0) return WEIGHT_ONE;
        return (int) Math.round(WEIGHT_ONE * StrictMath.pow(2.0, -ageMs / (double) HALF_LIFE_MS));
    }

    /**
     * The offer rate from watched waiting: {@code A_w} sums the weights of samples with an arrival (readable or not),
     * {@code E_w} the weighted waits; {@code L = 60,000 × (A_w + 1,024)}, {@code D = E_w + 614,400,000}, so
     * {@code λ = L ÷ D} offers a minute, behind a prior of one full-weight arrival per ten full-weight minutes.
     */
    static Rate lambda(List<QualifyingWait.Sample> waits, long wallNow) {
        long arrivals = 0;
        long exposure = 0;
        if (waits != null) {
            for (QualifyingWait.Sample sample : waits) {
                if (sample == null) continue;
                int w = weight(wallNow - sample.at);
                exposure += w * sample.observedMs;
                if (sample.arrival != null) arrivals += w;
            }
        }
        return new Rate(arrivals, exposure, 60_000L * (arrivals + PRIOR_ARRIVAL_WEIGHT),
                exposure + PRIOR_EXPOSURE_WEIGHT);
    }

    // ---- (c) The share bar ----

    /** The share bar: the highest bar in 50–150 at which {@code 100·pass ≥ need·n}; 50 and pinned when none. */
    static ShareBar shareBar(int[] thetas, int need) {
        long n = thetas.length;
        for (int bar = BAR_MAX; bar >= BAR_MIN; bar--) {
            if (100L * passing(thetas, bar) >= need * n) return new ShareBar(bar, false);
        }
        return new ShareBar(BAR_MIN, true);
    }

    /** How many of {@code thetas} are at least {@code bar}. */
    static int passing(int[] thetas, int bar) {
        int count = 0;
        for (int theta : thetas) if (theta >= bar) count++;
        return count;
    }

    // ---- (h) Value ----

    /**
     * The mix M: counted offers with pay read, Dasher's minutes above 0, every set minimum's quantity read, and at
     * most $150 an hour of those minutes ({@code 60·pay ≤ 15,000·minutes}). {@code thetas} is the window's θ, in order.
     */
    static List<MixLine> mix(FilterSettings rules, List<OfferRecord> window, int[] thetas) {
        List<MixLine> mix = new ArrayList<>();
        for (int i = 0; i < window.size(); i++) {
            OfferSnapshot facts = window.get(i).facts;
            if (facts.payCents == null || facts.minutes == null || facts.minutes <= 0) continue;
            if (AreaScore.required100(rules, facts) == null) continue;
            if (60L * facts.payCents > MAX_RATE_CENTS_PER_HOUR * facts.minutes) continue;
            mix.add(new MixLine(thetas[i], facts.payCents, facts.minutes));
        }
        return mix;
    }

    /**
     * Every bar's value from 50 to {@code hi}, by θ-sorted prefix sums: {@code N(σ) = 60·L·SP(σ)} over
     * {@code Dn(σ) = n_r·D + L·ST(σ)} cents per hour, the renewal-reward rate {@code 60·λ·E[pay·1A] ÷ (1 +
     * λ·E[minutes·1A])} with {@code λ = L ÷ D}. The mix must not be empty and {@code D} must be positive.
     */
    static Values values(List<MixLine> mix, long l, long d, int hi) {
        if (hi < BAR_MIN || hi > BAR_MAX) throw new IllegalArgumentException("bar " + hi);
        if (mix.isEmpty() || l < 0 || d <= 0) throw new IllegalArgumentException("no value without offers or waiting");
        MixLine[] lines = byThetaDescending(mix);
        BigInteger sixtyL = BigInteger.valueOf(l).multiply(BigInteger.valueOf(60));
        BigInteger bigL = BigInteger.valueOf(l);
        BigInteger base = BigInteger.valueOf(lines.length).multiply(BigInteger.valueOf(d));
        BigInteger[] numerators = new BigInteger[hi - BAR_MIN + 1];
        BigInteger[] denominators = new BigInteger[hi - BAR_MIN + 1];
        long pay = 0;
        long minutes = 0;
        int next = 0;
        for (int bar = hi; bar >= BAR_MIN; bar--) {
            while (next < lines.length && lines[next].theta >= bar) {
                pay += lines[next].payCents;
                minutes += lines[next].minutes;
                next++;
            }
            numerators[bar - BAR_MIN] = sixtyL.multiply(BigInteger.valueOf(pay));
            denominators[bar - BAR_MIN] = base.add(bigL.multiply(BigInteger.valueOf(minutes)));
        }
        return new Values(hi, numerators, denominators);
    }

    /**
     * σ*, the bar of best value (the lowest on ties), and the set of bars within 2% of it:
     * {@code 100·N(σ)·Dn(σ*) ≥ 98·N(σ*)·Dn(σ)}. Values are compared by cross-multiplication, never divided.
     */
    static Acceptable acceptable(Values values) {
        int best = values.lo;
        for (int bar = values.lo + 1; bar <= values.hi; bar++) {
            if (values.numerator(bar).multiply(values.denominator(best))
                    .compareTo(values.numerator(best).multiply(values.denominator(bar))) > 0) best = bar;
        }
        BigInteger bestNumerator = values.numerator(best).multiply(BigInteger.valueOf(VALUE_TOLERANCE_PERCENT));
        BigInteger bestDenominator = values.denominator(best).multiply(BigInteger.valueOf(100));
        boolean[] members = new boolean[values.hi - values.lo + 1];
        int lo = -1;
        int hi = -1;
        for (int bar = values.lo; bar <= values.hi; bar++) {
            boolean in = values.numerator(bar).multiply(bestDenominator)
                    .compareTo(bestNumerator.multiply(values.denominator(bar))) >= 0;
            members[bar - values.lo] = in;
            if (in) {
                if (lo < 0) lo = bar;
                hi = bar;
            }
        }
        return new Acceptable(best, lo, hi, members);
    }

    // ---- Commits ----

    /**
     * One commit decision, in order: a user's change jumps straight to the target; at the target, hold; within a
     * minute of the last commit, hold; within 3 of 100 with 100 as the target, exactly 100; within 3, hold; down at most
     * 20; up only after five minutes and three counted offers since the last raise, at most 5. The result is held to
     * 50–150.
     */
    static Step step(int current, int target, boolean jump, long sinceCommitMs, long sinceRaiseMs,
                     int offersSinceRaise) {
        int next;
        StepKind kind;
        if (jump) {
            next = target;
            kind = StepKind.JUMP;
        } else if (target == current) {
            next = current;
            kind = StepKind.HOLD;
        } else if (sinceCommitMs < MIN_COMMIT_SPACING_MS) {
            next = current;
            kind = StepKind.SPACING;
        } else if (target == BAR_OFF && Math.abs(current - BAR_OFF) < DEADBAND) {
            next = BAR_OFF;
            kind = StepKind.SNAP;
        } else if (Math.abs(target - current) < DEADBAND) {
            next = current;
            kind = StepKind.DEADBAND;
        } else if (target < current) {
            next = Math.max(target, current - DOWN_STEP);
            kind = StepKind.LOWER;
        } else if (sinceRaiseMs < MIN_RAISE_SPACING_MS || offersSinceRaise < MIN_OFFERS_BETWEEN_RAISES) {
            next = current;
            kind = StepKind.RAISE_WAIT;
        } else {
            next = Math.min(target, current + UP_STEP);
            kind = StepKind.RAISE;
        }
        return new Step(Math.max(BAR_MIN, Math.min(BAR_MAX, next)), kind);
    }

    /**
     * Why a commit from {@code from} to {@code to} happened: the pending user change when there is one; otherwise the
     * plan's mode, with value moves up or down. Off and no-rules plans only ever return to exactly the minimums.
     */
    static Reason commitReason(Plan plan, int from, int to, Reason jumpCause) {
        if (jumpCause != null) {
            if (!jumpCause.jump) throw new IllegalArgumentException("not a jump cause: " + jumpCause);
            return jumpCause;
        }
        switch (plan.mode) {
            case PINNED: return Reason.PINNED;
            case RECOVERY: return Reason.RECOVERY;
            case GOAL: return Reason.GOAL;
            case PASS_FLOOR: return Reason.PASS_FLOOR;
            case LEARNING: return Reason.LEARNING;
            case VALUE: return to > from ? Reason.VALUE_UP : Reason.VALUE_DOWN;
            default: return Reason.AT_MINIMUMS;
        }
    }

    // ---- Helpers ----

    private static int[] descending(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        for (int i = 0, j = sorted.length - 1; i < j; i++, j--) {
            int swap = sorted[i];
            sorted[i] = sorted[j];
            sorted[j] = swap;
        }
        return sorted;
    }

    private static MixLine[] byThetaDescending(List<MixLine> mix) {
        MixLine[] lines = mix.toArray(new MixLine[0]);
        Arrays.sort(lines, (a, b) -> Integer.compare(b.theta, a.theta));
        return lines;
    }

    private Autopilot() {}
}
