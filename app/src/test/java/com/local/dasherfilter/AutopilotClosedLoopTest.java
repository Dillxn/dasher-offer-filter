package com.local.dasherfilter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Autopilot in a closed loop: a seeded Java port of the calibrated Cincinnati stream (lognormal pay, minutes linear in
 * miles; design-judge/econ_check.py's model and parameters) and of the judge's dash simulation
 * (closed_loop2/closed_loop3.py), driving the real engine. Every tick of watched waiting plans with
 * {@link Autopilot#plan} (again after each offer and every five minutes) and commits with {@link Autopilot#step};
 * every offer is decided by {@link OfferRule#evaluate} at the committed bar. The Dasher takes a passing offer with a
 * given probability; an accepted trip lasts 1.15 × Dasher's minutes; DoorDash's acceptance rate is the accepted share of
 * the last 100 offers, and Dasher's question shows it (before that decline counts) whenever the app declines.
 *
 * <p>The market model is floating point, as a simulation may be; every decision inside it is the engine's exact
 * arithmetic. Seeds are fixed, so every figure is deterministic; the thresholds sit well inside what the seeds show.
 */
public final class AutopilotClosedLoopTest {
    private static final long START = 1_760_000_000_000L;
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    /** Autopilot off: the bar stays exactly at the minimums. */
    private static final int OFF = -1;
    private static final int OFFERS = 2_000;
    private static final int WARMUP = 500;
    private static final double BUSY = 26;
    private static final double MODERATE = 12;
    private static final double SLOW = 3;

    /** The calibrated stream (econ_check.sample): lognormal pay correlated with minutes, minutes linear in miles. */
    private static final class Market {
        private static final double ALPHA = 9.2526;
        private static final double BETA = 2.7236;
        private static final double S_T = 3.0765;
        private static final double BASE = 3.363;
        private static final double K = 0.9568;
        private static final double S_PAY = 0.3919;
        private static final double RHO = 0.4325;
        /** Miles: median 5.85, p10 2.2, p90 12.2, a lognormal on each side of the median. */
        private static final double Z10 = -1.2815515655446004;
        private static final double SPREAD_LOW = StrictMath.log(5.85 / 2.2) / -Z10;
        private static final double SPREAD_HIGH = StrictMath.log(12.2 / 5.85) / -Z10;
        private final Random random;

        Market(long seed) {
            random = new Random(seed);
        }

        OfferSnapshot next() {
            double z = random.nextGaussian();
            double miles = Math.max(0.1,
                    Math.round(5.85 * StrictMath.exp(z < 0 ? SPREAD_LOW * z : SPREAD_HIGH * z) * 10) / 10.0);
            double timeNoise = random.nextGaussian();
            int minutes = (int) Math.max(5, Math.round(ALPHA + BETA * miles + S_T * timeNoise));
            double payNoise = RHO * timeNoise + StrictMath.sqrt(1 - RHO * RHO) * random.nextGaussian();
            double dollars = (BASE + K * miles) * StrictMath.exp(S_PAY * payNoise - 0.5 * S_PAY * S_PAY);
            int cents = (int) Math.max(200, Math.round(dollars * 100 / 5) * 5);
            double u = random.nextDouble();
            return new OfferSnapshot(cents, miles, minutes, u < 0.68 ? 2 : u < 0.83 ? 3 : 4);
        }
    }

    /** What one simulated dash did. */
    private static final class Dash {
        /** Pay per hour of waiting and trips after the warm-up. */
        double dollarsPerHour;
        /** DoorDash's acceptance rate (accepted of the last 100 offers): at the end, its mean and least after the
         * warm-up. */
        int finalAr;
        double meanAr;
        int minAr = 101;
        int finalBar;
        int commits;
        /** The most commits in any hour after the warm-up. */
        int maxCommitsPerHour;
        /** The shortest time from a commit to one in the other direction, after the warm-up. */
        long minReversalGapMs = Long.MAX_VALUE;
        /** The offer and dash-hour at which the acceptance rate first reached the goal (70 with pay first or off). */
        int offersToGoal = Integer.MAX_VALUE;
        double hoursToGoal = -1;
        int maxExtra;
        int maxExtraBeforeGoal;
        int plans;

        @Override
        public String toString() {
            return String.format(Locale.US, "$%.2f/h, AR %d (mean %.1f, least %d), bar %d, %d commits (≤%d an hour, "
                            + "reversal ≥%s min), goal after %s offers (%.1f h), extra ≤%d (≤%d before the goal), "
                            + "%d plans", dollarsPerHour, finalAr, meanAr, minAr, finalBar, commits,
                    maxCommitsPerHour, minReversalGapMs == Long.MAX_VALUE ? "-" : String.valueOf(minReversalGapMs / MIN),
                    offersToGoal == Integer.MAX_VALUE ? "-" : String.valueOf(offersToGoal), hoursToGoal, maxExtra,
                    maxExtraBeforeGoal, plans);
        }
    }

    /**
     * One seeded dash of {@code offers} offers with the starter minimums ($4, $1 a mile, $15 an hour). {@code goal}
     * is Autopilot's goal or {@link #OFF}; offers come at {@code offersPerHour} per hour of waiting; the Dasher takes a
     * passing offer with probability {@code takeRate}; DoorDash's window starts with {@code startAr} of 100 accepted.
     * Without {@code correction} the stall correction is held off (no checkpoint, no extra), as if it did not exist.
     */
    private static Dash dash(int goal, double offersPerHour, double takeRate, int startAr, boolean correction,
                             long seed, int offers, int warmup) {
        Market market = new Market(seed);
        Random arrivals = new Random(seed * 1_000_003L + 1);
        Random user = new Random(seed * 1_000_033L + 2);
        Random shuffle = new Random(seed * 1_000_037L + 3);
        boolean autopilot = goal != OFF;
        FilterSettings rules = autopilot ? new FilterSettings(true, 400, 100, 25, 0, true, goal, 100)
                : FilterSettings.of(true, 400, 100, 25, 0);
        int goalToReach = goal > 0 ? goal : FilterSettings.GOAL_TOP_TIER;

        // DoorDash's last 100 offers, startAr of them accepted, in a seeded order.
        boolean[] ring = new boolean[100];
        for (int i = 0; i < startAr; i++) ring[i] = true;
        for (int i = ring.length - 1; i > 0; i--) {
            int j = shuffle.nextInt(i + 1);
            boolean swap = ring[i];
            ring[i] = ring[j];
            ring[j] = swap;
        }
        int ringAt = 0;
        int accepted = startAr;

        Deque<Autopilot.OfferRecord> history = new ArrayDeque<>();
        Deque<QualifyingWait.Sample> waits = new ArrayDeque<>();
        Autopilot.Reading reading = null;
        boolean recovering = false;
        int extra = 0;
        long checkpointAt = Autopilot.NEVER;
        int checkpointReading = -1;
        long raisedAt = Autopilot.NEVER;
        int bar = Autopilot.BAR_OFF;
        long t = START;
        // The runtime's clocks start at process start: the first commit a minute in, the first raise five.
        long lastCommit = START;
        long lastRaise = START;
        Autopilot.Plan plan = null;
        long plannedAt = Long.MIN_VALUE;
        boolean newOffer = true;
        int lastDirection = 0;
        List<Long> commitTimes = new ArrayList<>();
        Dash dash = new Dash();
        long measuredFrom = START;
        long pay = 0;
        double arSum = 0;
        int arCount = 0;

        for (int i = 0; i < offers; i++) {
            OfferSnapshot offer = market.next();
            long gap = Math.max(1_000L,
                    Math.round(-StrictMath.log(1 - arrivals.nextDouble()) / offersPerHour * HOUR));
            double takeDraw = user.nextDouble();
            long waitStart = t;
            if (i == warmup) measuredFrom = t;

            for (long tick = waitStart; autopilot && tick < waitStart + gap; tick += Autopilot.TICK_MS) {
                if (plan == null || newOffer || tick - plannedAt >= 5 * MIN) {
                    List<QualifyingWait.Sample> snapshot = new ArrayList<>(waits);
                    if (tick > waitStart) snapshot.add(new QualifyingWait.Sample(tick, tick - waitStart, null));
                    Autopilot.State state = new Autopilot.State(recovering, correction ? extra : 0,
                            correction ? checkpointAt : Autopilot.NEVER, correction ? checkpointReading : -1,
                            raisedAt, bar, null);
                    plan = Autopilot.plan(new Autopilot.Inputs(rules.withMinimumScalePercent(bar),
                            new ArrayList<>(history), snapshot, reading, state, tick, 1));
                    dash.plans++;
                    plannedAt = tick;
                    newOffer = false;
                    recovering = plan.recovering;
                    if (correction) {
                        extra = plan.extra;
                        checkpointAt = plan.checkpointAt;
                        checkpointReading = plan.checkpointReading;
                    }
                    dash.maxExtra = Math.max(dash.maxExtra, extra);
                    if (dash.offersToGoal == Integer.MAX_VALUE) {
                        dash.maxExtraBeforeGoal = Math.max(dash.maxExtraBeforeGoal, extra);
                    }
                }
                Autopilot.Step step = Autopilot.step(bar, plan.target, false, tick - lastCommit, tick - lastRaise,
                        plan.offersSinceRaise);
                if (step.next == bar) continue;
                int direction = Integer.signum(step.next - bar);
                if (i >= warmup) {
                    if (lastDirection != 0 && direction != lastDirection) {
                        dash.minReversalGapMs = Math.min(dash.minReversalGapMs, tick - lastCommit);
                    }
                    commitTimes.add(tick);
                }
                lastDirection = direction;
                if (step.next > bar) {
                    lastRaise = tick;
                    raisedAt = tick;
                }
                lastCommit = tick;
                bar = step.next;
                dash.commits++;
            }

            t = waitStart + gap;
            waits.addLast(new QualifyingWait.Sample(t, gap, offer));
            OfferRule.Decision decision = OfferRule.evaluate(offer, autopilot ? rules.withMinimumScalePercent(bar)
                    : rules);
            long decidedAt = t;
            boolean took = false;
            if (decision.result == OfferRule.Result.DECLINE) {
                reading = new Autopilot.Reading(accepted, t + 1_000L);
                t += 10_000L;
                waits.addLast(new QualifyingWait.Sample(t, 10_000L, null));
            } else if (takeDraw < takeRate) {
                took = true;
                if (i >= warmup) pay += offer.payCents;
                t += Math.round(1.15 * offer.minutes * MIN);
            } else {
                // Left to run out: no question, so no reading; DoorDash counts it against the rate.
                t += 30_000L;
                waits.addLast(new QualifyingWait.Sample(t, 30_000L, null));
            }
            if (ring[ringAt]) accepted--;
            ring[ringAt] = took;
            if (took) accepted++;
            ringAt = (ringAt + 1) % ring.length;
            history.addFirst(new Autopilot.OfferRecord(offer, decidedAt, false, false, took, !took, false));
            while (history.size() > 200) history.removeLast();
            while (waits.size() > QualifyingWait.MAX_SAMPLES) waits.removeFirst();
            for (Iterator<QualifyingWait.Sample> it = waits.iterator(); it.hasNext(); ) {
                if (t - it.next().at < QualifyingWait.RETAIN_MS) break;
                it.remove();
            }
            newOffer = true;
            if (dash.offersToGoal == Integer.MAX_VALUE && accepted >= goalToReach) {
                dash.offersToGoal = i + 1;
                dash.hoursToGoal = (t - START) / (double) HOUR;
            }
            if (i >= warmup) {
                arSum += accepted;
                arCount++;
                dash.minAr = Math.min(dash.minAr, accepted);
            }
        }
        dash.dollarsPerHour = pay / 100.0 / ((t - measuredFrom) / (double) HOUR);
        dash.finalAr = accepted;
        dash.meanAr = arCount == 0 ? accepted : arSum / arCount;
        dash.finalBar = bar;
        for (int from = 0, to = 0; to < commitTimes.size(); to++) {
            while (commitTimes.get(to) - commitTimes.get(from) >= HOUR) from++;
            dash.maxCommitsPerHour = Math.max(dash.maxCommitsPerHour, to - from + 1);
        }
        return dash;
    }

    private static String market(double offersPerHour) {
        return String.format(Locale.US, "%.0f offers an hour", offersPerHour);
    }

    /** The bar stays within its range, settles, and never turns back within half an hour of a commit. */
    private static void assertCalm(String what, Dash dash) {
        assertTrue(what + ": bar in 50–150", dash.finalBar >= Autopilot.BAR_MIN && dash.finalBar <= Autopilot.BAR_MAX);
        assertTrue(what + ": at most 6 commits an hour", dash.maxCommitsPerHour <= 6);
        assertTrue(what + ": no reversal within 30 minutes of a commit", dash.minReversalGapMs >= 30 * MIN);
    }

    @Test
    public void goalSeventyWithEveryPassingOfferTakenHoldsSeventyPercentInEveryMarket() {
        for (double offersPerHour : new double[] {BUSY, MODERATE, SLOW}) {
            for (long seed = 1; seed <= 2; seed++) {
                Dash dash = dash(FilterSettings.GOAL_TOP_TIER, offersPerHour, 1.0, 70, true, seed, OFFERS, WARMUP);
                String what = market(offersPerHour) + ", seed " + seed + ": " + dash;
                assertTrue(what + ": AR over the last 100 offers at least 70%", dash.finalAr >= 70);
                assertTrue(what + ": mean AR at least the goal + 5", dash.meanAr >= 75);
                assertTrue(what + ": dips stay small", dash.minAr >= 65);
                assertCalm(what, dash);
            }
        }
    }

    @Test
    public void payFirstEarnsNoLessThanOffWhenOffersComeOften() {
        for (long seed = 1; seed <= 2; seed++) {
            Dash off = dash(OFF, BUSY, 1.0, 70, true, seed, OFFERS, WARMUP);
            Dash payFirst = dash(FilterSettings.GOAL_PAY_FIRST, BUSY, 1.0, 70, true, seed, OFFERS, WARMUP);
            String what = "seed " + seed + ": off " + off + "; pay first " + payFirst;
            assertTrue(what + ": pay first no worse than off by more than $0.10 an hour",
                    payFirst.dollarsPerHour >= off.dollarsPerHour - 0.10);
            assertTrue(what + ": still at least one offer in five passes", payFirst.meanAr >= 20);
            assertEquals("off never moves the bar", 0, off.commits);
            assertCalm(what, payFirst);
        }
    }

    @Test
    public void recoveryFromNinePercentReachesTheGoalWithoutWindup() {
        for (double offersPerHour : new double[] {BUSY, 6}) {
            for (long seed = 1; seed <= 2; seed++) {
                Dash recovery = dash(FilterSettings.GOAL_TOP_TIER, offersPerHour, 0.95, 9, true, seed, 400, 0);
                Dash off = dash(OFF, offersPerHour, 0.95, 9, true, seed, 400, 0);
                String what = market(offersPerHour) + ", seed " + seed + ": " + recovery + "; off " + off;
                assertTrue(what + ": 70% within 100 offers (6100 ÷ 71 = 85.9 when 80% pass)",
                        recovery.offersToGoal <= 100);
                assertTrue(what + ": sooner than with Autopilot off", recovery.offersToGoal < off.offersToGoal);
                assertEquals(what + ": a real recovery never trips the stall correction", 0,
                        recovery.maxExtraBeforeGoal);
                assertCalm(what, recovery);
            }
        }
    }

    @Test
    public void theStallCorrectionLiftsARateStalledBySkippedOffers() {
        for (long seed = 1; seed <= 2; seed++) {
            Dash without = dash(FilterSettings.GOAL_TOP_TIER, MODERATE, 0.85, 70, false, seed, OFFERS, WARMUP);
            Dash with = dash(FilterSettings.GOAL_TOP_TIER, MODERATE, 0.85, 70, true, seed, OFFERS, WARMUP);
            String what = "seed " + seed + ": without " + without + "; with " + with;
            assertEquals(what + ": held off", 0, without.maxExtra);
            assertTrue(what + ": it engaged", with.maxExtra > 0);
            assertTrue(what + ": never more than 10 points", with.maxExtra <= Autopilot.EXTRA_MAX);
            assertTrue(what + ": mean AR back at the goal", with.meanAr >= 70);
            assertTrue(what + ": at least a point above the stalled rate", with.meanAr >= without.meanAr + 1);
            assertTrue(what + ": for little pay", with.dollarsPerHour >= without.dollarsPerHour - 0.50);
            assertCalm(what, with);
        }
    }

    @Test
    public void theSameSeedRunsTheSameDash() {
        Dash first = dash(FilterSettings.GOAL_TOP_TIER, BUSY, 0.95, 40, true, 7, 600, 100);
        Dash second = dash(FilterSettings.GOAL_TOP_TIER, BUSY, 0.95, 40, true, 7, 600, 100);
        assertEquals(first.toString(), second.toString());
        assertTrue(first.plans > 0);
    }
}
