package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The accepted floor in the plan (0.6.11): the highest score, under the current rules, of offers the user accepted by
 * hand since the floor's start (the dash, or the user's own last change), at most 150. It lifts the target to
 * max(own target, floor) in every planning mode but off and no rules, with the mode kept. Add-ons, replays, misread
 * offers, skipped stores, offers over max stops and offers whose pay or a needed figure was unread never count; nor
 * does a score below 50. The score is recomputed, never the recorded one.
 */
public final class AutopilotFloorPlanTest {
    private static final long NOW = 1_760_000_000_000L;
    private static final long MIN = 60_000L;
    /** The floor's start in these plans: an hour ago. */
    private static final long SINCE = NOW - 60 * MIN;

    // ---- Fixtures ----

    /** Strict rules with Autopilot on: only a flat minimum, so an offer's score is pay ÷ that minimum. */
    private static FilterSettings flat(int cents, int goal) {
        // The single-order ask; pay per stop is half, a single order being 2 stops, the pickup included (0.6.14).
        return new FilterSettings(true, cents / 2, 0, 0, 0, true, goal, 100);
    }

    /** A counted line {@code minutesAgo} old, accepted by hand {@code handAgo} minutes ago (-1: never). */
    private static Autopilot.OfferRecord line(OfferSnapshot facts, long minutesAgo, long handAgo) {
        return record(facts, minutesAgo, handAgo, false, false);
    }

    private static Autopilot.OfferRecord record(OfferSnapshot facts, long minutesAgo, long handAgo, boolean addOn,
                                                boolean skipped) {
        boolean hand = handAgo >= 0;
        return new Autopilot.OfferRecord(facts, NOW - minutesAgo * MIN, addOn, false, hand, false, false, 100, true,
                DecisionLog.MODEL, null, skipped, hand ? NOW - handAgo * MIN : Autopilot.NEVER);
    }

    /** {@code count} lines of pay {@code pay} (5 mi, minutes as given), three minutes apart from {@code from}. */
    private static List<Autopilot.OfferRecord> lines(int count, int pay, Integer minutes, long from) {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(line(new OfferSnapshot(pay + i % 3, 5.0, minutes, 2), from + 3L * i, -1));
        }
        return lines;
    }

    private static List<Autopilot.OfferRecord> join(List<Autopilot.OfferRecord> first,
                                                    List<Autopilot.OfferRecord> second) {
        List<Autopilot.OfferRecord> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static Autopilot.Plan plan(FilterSettings rules, List<Autopilot.OfferRecord> lines,
                                       Autopilot.Reading reading, long floorSince) {
        return plan(rules, lines, reading, floorSince, busy());
    }

    private static Autopilot.Plan plan(FilterSettings rules, List<Autopilot.OfferRecord> lines,
                                       Autopilot.Reading reading, long floorSince, List<QualifyingWait.Sample> waits) {
        Autopilot.State state = new Autopilot.State(false, 0, Autopilot.NEVER, -1, Autopilot.NEVER,
                rules.minimumScalePercent, null);
        return Autopilot.plan(new Autopilot.Inputs(rules, lines, waits, reading, state, NOW, 1, Long.MAX_VALUE,
                TimeZone.getTimeZone("UTC"), 0, floorSince));
    }

    /** The same lines with no hand accept: the plan's own target. */
    private static Autopilot.Plan withoutHand(FilterSettings rules, List<Autopilot.OfferRecord> lines,
                                              Autopilot.Reading reading) {
        return withoutHand(rules, lines, reading, busy());
    }

    private static Autopilot.Plan withoutHand(FilterSettings rules, List<Autopilot.OfferRecord> lines,
                                              Autopilot.Reading reading, List<QualifyingWait.Sample> waits) {
        List<Autopilot.OfferRecord> plain = new ArrayList<>();
        for (Autopilot.OfferRecord r : lines) {
            plain.add(new Autopilot.OfferRecord(r.facts, r.at, r.addOn, r.replay, r.accepted, r.declined, r.arExempt,
                    r.bar, r.autopilot, r.model, r.scoringRules, r.skipped));
        }
        return plan(rules, plain, reading, SINCE, waits);
    }

    private static List<QualifyingWait.Sample> busy() {
        List<QualifyingWait.Sample> waits = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            waits.add(new QualifyingWait.Sample(NOW - (38 - 4L * i) * MIN, 2 * MIN,
                    new OfferSnapshot(700 + 50 * i, 5.0, 22, 2)));
        }
        waits.add(new QualifyingWait.Sample(NOW, 2 * MIN, null));
        return waits;
    }

    /** Slow: three arrivals in two hours, after long waits. */
    private static List<QualifyingWait.Sample> slow() {
        return Arrays.asList(new QualifyingWait.Sample(NOW - 110 * MIN, 21 * MIN, new OfferSnapshot(780, 8.1, 29, 2)),
                new QualifyingWait.Sample(NOW - 64 * MIN, 17 * MIN, new OfferSnapshot(615, 3.9, 21, 2)),
                new QualifyingWait.Sample(NOW - 23 * MIN, 14 * MIN, new OfferSnapshot(1150, 10.4, 37, 2)),
                new QualifyingWait.Sample(NOW, 9 * MIN, null));
    }

    private static Autopilot.Reading dasher(int percent) {
        return new Autopilot.Reading(percent, NOW - MIN);
    }

    /** One hand accept of pay {@code pay} (5 mi, 20 min), the newest line, accepted a minute ago. */
    private static Autopilot.OfferRecord hand(int pay) {
        return line(new OfferSnapshot(pay, 5.0, 20, 2), 2, 1);
    }

    // ---- The owner's case ----

    @Test
    public void theOwnersAcceptAtSixtyThreeLiftsTheBarFromFifty() {
        // A $3.18-a-mile minimum asks $22.90 of 7.2 mi: his $14.45 offer (7.2 mi, 27 min) scores 63.
        FilterSettings rules = new FilterSettings(true, 250, 318, 0, 0, true, 70, 50);
        OfferSnapshot his = new OfferSnapshot(1445, 7.2, 27, 2);
        assertEquals(63, AreaScore.scorePercent(rules, his));
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        lines.add(line(his, 2, 1));
        for (int i = 0; i < 99; i++) lines.add(line(new OfferSnapshot(500 + i, 7.2, 27, 2), 5 + 3L * i, -1));
        Autopilot.Plan plan = plan(rules, lines, dasher(10), SINCE);
        assertEquals(100, plan.counted);
        assertSame(Autopilot.Mode.PINNED, plan.mode);
        assertTrue(plan.pinned);
        assertEquals(50, plan.barShare);
        assertEquals("what the acceptance-rate limit alone allows", 50, plan.ownTarget);
        assertEquals(63, plan.floor);
        assertEquals(63, plan.target);
        assertTrue(plan.floorBinds());
        assertEquals("only his offer passes at 63", 1, plan.passAtTarget);
        assertSame(Autopilot.Reason.ACCEPTED, Autopilot.commitReason(plan, 50, 63, null));
        // Not his to hold: before the floor's start, or with no dash (no start at all).
        Autopilot.Plan before = plan(rules, lines, dasher(10), NOW - 30_000);
        assertEquals(-1, before.floor);
        assertEquals(50, before.target);
        assertEquals(-1, plan(rules, lines, dasher(10), Long.MAX_VALUE).floor);
    }

    // ---- Every planning mode ----

    @Test
    public void theFloorLiftsEveryPlanningModeButOffAndNoRules() {
        FilterSettings goal = flat(1000, 70);
        // 80 lines at 80% and 20 at 30%, no minutes read (no value mix): the share bar is 80.
        List<Autopilot.OfferRecord> limited = join(lines(80, 800, null, 5), lines(20, 300, null, 300));
        List<Autopilot.OfferRecord> recovery = join(Collections.singletonList(hand(900)), limited.subList(0, 99));
        assertMode(Autopilot.Mode.RECOVERY, 80, 90, goal, recovery, dasher(60));
        assertMode(Autopilot.Mode.GOAL, 80, 90, goal, recovery, dasher(90));
        FilterSettings payFirst = flat(1000, FilterSettings.GOAL_PAY_FIRST);
        List<Autopilot.OfferRecord> passFloor = join(Collections.singletonList(hand(900)),
                join(lines(20, 800, null, 5), lines(79, 300, null, 100)));
        assertMode(Autopilot.Mode.PASS_FLOOR, 80, 90, payFirst, passFloor, null);
        // Every line at 150%, no value mix: exactly the minimums.
        List<Autopilot.OfferRecord> high = join(Collections.singletonList(hand(1200)), lines(30, 1500, null, 5));
        assertMode(Autopilot.Mode.AT_MINIMUMS, 100, 120, goal, high, dasher(90));
        // A value mix (minutes read) in a slow market, pay first: the value rule's own pick, lifted to the floor.
        List<Autopilot.OfferRecord> mix = new ArrayList<>();
        mix.add(hand(1500));
        for (int i = 0; i < 40; i++) mix.add(line(new OfferSnapshot(600 + 25 * i, 5.0, 20, 2), 5 + 3L * i, -1));
        Autopilot.Plan own = withoutHand(payFirst, mix, null, slow());
        assertSame(Autopilot.Mode.VALUE, own.mode);
        assertTrue("the value rule's pick is below the accept's 150", own.target < 150);
        Autopilot.Plan value = plan(payFirst, mix, null, SINCE, slow());
        assertSame(Autopilot.Mode.VALUE, value.mode);
        assertEquals(own.target, value.ownTarget);
        assertEquals(150, value.floor);
        assertEquals(150, value.target);
        // Learning (fewer than 20): max(100, floor).
        List<Autopilot.OfferRecord> few = join(Collections.singletonList(hand(1200)), lines(9, 800, 20, 5));
        assertMode(Autopilot.Mode.LEARNING, 100, 120, goal, few, null);
        Autopilot.Plan lowLearning = plan(goal, join(Collections.singletonList(hand(800)), lines(9, 800, 20, 5)), null,
                SINCE);
        assertEquals(80, lowLearning.floor);
        assertEquals("a floor below the learning bar changes nothing", 100, lowLearning.target);
        assertFalse(lowLearning.floorBinds());
        // Off, and no money minimum: exactly the minimums, no floor.
        Autopilot.Plan off = plan(goal.withAutopilot(false, 70), high, null, SINCE);
        assertSame(Autopilot.Mode.OFF, off.mode);
        assertEquals(-1, off.floor);
        assertEquals(100, off.target);
        FilterSettings stopsOnly = new FilterSettings(true, 0, 0, 0, 3, true, 70, 100);
        Autopilot.Plan noRules = plan(stopsOnly, high, null, SINCE);
        assertSame(Autopilot.Mode.NO_RULES, noRules.mode);
        assertEquals(-1, noRules.floor);
        assertEquals(100, noRules.target);
    }

    private static void assertMode(Autopilot.Mode mode, int own, int floor, FilterSettings rules,
                                   List<Autopilot.OfferRecord> lines, Autopilot.Reading reading) {
        Autopilot.Plan plain = withoutHand(rules, lines, reading);
        assertSame(mode + ": without the accept", mode, plain.mode);
        assertEquals(mode + ": own target", own, plain.target);
        assertEquals(-1, plain.floor);
        Autopilot.Plan plan = plan(rules, lines, reading, SINCE);
        assertSame(mode + ": the mode is kept", mode, plan.mode);
        assertEquals(mode + ": own target", own, plan.ownTarget);
        assertEquals(mode + ": floor", floor, plan.floor);
        assertEquals(mode + ": target", Math.max(own, floor), plan.target);
        assertEquals(mode + ": pass at the target", plan.passAt(plan.target), plan.passAtTarget);
        assertTrue(plan.floorBinds());
    }

    @Test
    public void aFloorBelowTheOwnTargetChangesNothing() {
        FilterSettings goal = flat(1000, 70);
        List<Autopilot.OfferRecord> lines = join(Collections.singletonList(hand(700)),
                join(lines(80, 800, null, 5), lines(19, 300, null, 300)));
        Autopilot.Plan plan = plan(goal, lines, dasher(90), SINCE);
        assertEquals(70, plan.floor);
        assertEquals(80, plan.ownTarget);
        assertEquals(80, plan.target);
        assertFalse(plan.floorBinds());
        assertSame("a commit to the own target keeps the mode's reason", Autopilot.Reason.GOAL,
                Autopilot.commitReason(plan, 100, 80, null));
    }

    // ---- Which accepts count ----

    @Test
    public void onlyAcceptsSinceTheStartCountAndTheHighestWins() {
        FilterSettings rules = flat(1000, 70);
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        lines.add(line(new OfferSnapshot(700, 5.0, 20, 2), 3, 2));
        lines.add(line(new OfferSnapshot(1100, 5.0, 20, 2), 20, 19));
        lines.add(line(new OfferSnapshot(900, 5.0, 20, 2), 40, 39));
        // Before the start: the best of all, but not this dash's.
        lines.add(line(new OfferSnapshot(1400, 5.0, 20, 2), 90, 89));
        List<Autopilot.HandAccept> accepts = Autopilot.handAccepts(rules, lines);
        assertEquals(4, accepts.size());
        assertEquals("newest first", 70, accepts.get(0).score);
        assertEquals(NOW - 2 * MIN, accepts.get(0).at);
        assertEquals(110, Autopilot.acceptedFloor(accepts, SINCE));
        assertEquals("from exactly an accept's time on, it counts", 140,
                Autopilot.acceptedFloor(accepts, NOW - 89 * MIN));
        assertEquals(110, Autopilot.acceptedFloor(accepts, NOW - 89 * MIN + 1));
        assertEquals(110, Autopilot.acceptedFloor(accepts, NOW - 19 * MIN));
        assertEquals(70, Autopilot.acceptedFloor(accepts, NOW - 19 * MIN + 1));
        assertEquals(140, Autopilot.acceptedFloor(accepts, 0));
        assertEquals(-1, Autopilot.acceptedFloor(accepts, NOW));
        assertEquals(-1, Autopilot.acceptedFloor(accepts, Long.MAX_VALUE));
        assertEquals(-1, Autopilot.acceptedFloor(Collections.<Autopilot.HandAccept>emptyList(), 0));
        // At most 150.
        List<Autopilot.HandAccept> rich = Autopilot.handAccepts(rules,
                Collections.singletonList(line(new OfferSnapshot(4000, 5.0, 20, 2), 3, 2)));
        assertEquals(150, rich.get(0).score);
        assertEquals(150, Autopilot.acceptedFloor(rich, 0));
    }

    /**
     * 0.6.19: accepts kept past the history's latest lines count as the history's own do, scored under the current
     * rules, and one the history still holds (the same accept) counts once.
     */
    @Test
    public void keptAcceptsCountBesideTheHistoryOnceEach() {
        FilterSettings rules = flat(1000, 70);
        List<Autopilot.OfferRecord> history = new ArrayList<>();
        history.add(line(new OfferSnapshot(700, 5.0, 20, 2), 3, 2));
        history.add(line(new OfferSnapshot(900, 5.0, 20, 2), 40, 39));
        List<Autopilot.OfferRecord> kept = new ArrayList<>();
        // The history's own 90% accept again, then a 120% one whose line left the history.
        kept.add(line(new OfferSnapshot(900, 5.0, 20, 2), 39, 39));
        kept.add(line(new OfferSnapshot(1200, 5.0, 20, 2), 50, 50));
        kept.add(record(new OfferSnapshot(1500, 5.0, 20, 2), 51, 51, true, false));
        List<Autopilot.HandAccept> accepts = Autopilot.handAccepts(rules, history, kept);
        assertEquals("an add-on never counts, kept or not", 3, accepts.size());
        assertEquals(70, accepts.get(0).score);
        assertEquals(90, accepts.get(1).score);
        assertEquals(NOW - 50 * MIN, accepts.get(2).at);
        assertEquals(120, accepts.get(2).score);
        assertEquals(120, Autopilot.acceptedFloor(accepts, SINCE));
        assertEquals(Autopilot.handAccepts(rules, history).size(), Autopilot.handAccepts(rules, history, null).size());
        // In the plan: the kept one holds the bar, from the floor's start like any other.
        List<Autopilot.OfferRecord> lines = join(history, lines(40, 400, 20, 60));
        assertEquals(120, kept(rules, lines, kept, SINCE).floor);
        assertEquals("before the floor's start", 90, kept(rules, lines, kept, NOW - 45 * MIN).floor);
        assertEquals("without them, the history's best", 90, plan(rules, lines, dasher(10), SINCE).floor);
    }

    private static Autopilot.Plan kept(FilterSettings rules, List<Autopilot.OfferRecord> lines,
                                       List<Autopilot.OfferRecord> kept, long floorSince) {
        Autopilot.State state = new Autopilot.State(false, 0, Autopilot.NEVER, -1, Autopilot.NEVER,
                rules.minimumScalePercent, null);
        return Autopilot.plan(new Autopilot.Inputs(rules, lines, busy(), dasher(10), state, NOW, 1, Long.MAX_VALUE,
                TimeZone.getTimeZone("UTC"), 0, floorSince, kept));
    }

    @Test
    public void addOnsReplaysMisreadsSkippedStoresOverMaxStopsAndUnreadFiguresNeverCount() {
        FilterSettings rules = new FilterSettings(true, 500, 0, 25, 3, true, 70, 100);
        OfferSnapshot good = new OfferSnapshot(1200, 5.0, 20, 2);
        assertEquals(120, AreaScore.scorePercent(rules, good));
        List<Autopilot.OfferRecord> never = Arrays.asList(
                record(good, 2, 1, true, false),
                new Autopilot.OfferRecord(good, NOW - 3 * MIN, false, true, true, false, false, 100, true,
                        DecisionLog.MODEL, null, false, NOW - 2 * MIN),
                line(new OfferSnapshot(1200, 5.0, 2, 2), 4, 3),
                line(new OfferSnapshot(1200, 0.2, 20, 2), 5, 4),
                line(new OfferSnapshot(1200, 5.0, 20, 1), 6, 5),
                record(good, 7, 6, false, true),
                line(new OfferSnapshot(1200, 5.0, 20, 4), 8, 7),
                line(new OfferSnapshot(null, 5.0, 20, 2), 9, 8),
                line(new OfferSnapshot(1200, 5.0, null, 2), 10, 9),
                // Accepted, but not by hand (an automatic accept, an add-on's, one observed): no ACCEPTED step.
                record(good, 11, -1, false, false),
                // Scores 40: below the lowest bar.
                line(new OfferSnapshot(400, 5.0, 20, 2), 12, 11));
        assertEquals(1, Autopilot.handAccepts(rules, never).size());
        assertEquals(40, Autopilot.handAccepts(rules, never).get(0).score);
        assertEquals(-1, Autopilot.acceptedFloor(Autopilot.handAccepts(rules, never), 0));
        List<Autopilot.OfferRecord> plus = new ArrayList<>(never);
        plus.add(line(good, 13, 12));
        assertEquals(120, Autopilot.acceptedFloor(Autopilot.handAccepts(rules, plus), 0));
        // Unknown stops leave pay per stop unread (0.6.12): no score, so it never holds the floor.
        assertEquals(-1, Autopilot.acceptedFloor(Autopilot.handAccepts(rules,
                Collections.singletonList(line(new OfferSnapshot(1200, 5.0, 20, null), 2, 1))), 0));
    }

    @Test
    public void theScoreIsRecomputedUnderTheCurrentRulesNeverTheRecordedOne() {
        // Recorded at 120% under a $10 minimum; a lesson since raised the learned pay floor to its pay + 1¢.
        OfferSnapshot accepted = new OfferSnapshot(1200, 5.0, 20, 2);
        FilterSettings then = flat(1000, 70);
        assertEquals(120, AreaScore.scorePercent(then, accepted));
        FilterSettings now = new FilterSettings(true, 500, 0, 0, 0, true, 70, 100, false, true,
                new AcceptedFloors(1200, 2, 0, 0, 0, 0));
        List<Autopilot.OfferRecord> lines = Collections.singletonList(line(accepted, 2, 1));
        assertEquals(120, Autopilot.acceptedFloor(Autopilot.handAccepts(then, lines), SINCE));
        assertEquals("the ask is about the accepted pay, not 120% of the new minimums", 99,
                Autopilot.acceptedFloor(Autopilot.handAccepts(now, lines), SINCE));
    }

    // ---- Why the bar moved ----

    @Test
    public void aCommitLandingOnABindingFloorSaysTheAcceptsWords() {
        FilterSettings rules = new FilterSettings(true, 250, 318, 0, 0, true, 70, 50);
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        lines.add(line(new OfferSnapshot(1445, 7.2, 27, 2), 2, 1));
        for (int i = 0; i < 99; i++) lines.add(line(new OfferSnapshot(500 + i, 7.2, 27, 2), 5 + 3L * i, -1));
        Autopilot.Plan plan = plan(rules, lines, dasher(10), SINCE);
        assertSame("up from below", Autopilot.Reason.ACCEPTED, Autopilot.commitReason(plan, 50, 63, null));
        assertSame("down from above", Autopilot.Reason.ACCEPTED, Autopilot.commitReason(plan, 100, 63, null));
        assertSame("a lesson's jump landing there", Autopilot.Reason.ACCEPTED,
                Autopilot.commitReason(plan, 50, 63, Autopilot.Reason.LEARNED));
        assertSame("turning Autopilot on keeps the floor (0.6.19): its jump landing there too",
                Autopilot.Reason.ACCEPTED, Autopilot.commitReason(plan, 100, 63, Autopilot.Reason.TURNED_ON));
        for (Autopilot.Reason users : Arrays.asList(Autopilot.Reason.GOAL_CHANGED, Autopilot.Reason.RULES_CHANGED,
                Autopilot.Reason.CLEARED)) {
            assertSame("a change that restarts the floor keeps its reason", users,
                    Autopilot.commitReason(plan, 100, 63, users));
        }
        assertSame(Autopilot.Reason.TURNED_ON, Autopilot.commitReason(plan, 100, 80, Autopilot.Reason.TURNED_ON));
        assertSame("a step short of the floor keeps the mode's reason", Autopilot.Reason.PINNED,
                Autopilot.commitReason(plan, 100, 80, null));
        assertSame(Autopilot.Reason.LEARNED, Autopilot.commitReason(plan, 100, 80, Autopilot.Reason.LEARNED));
        // No floor binding: a lesson's jump says its own words.
        Autopilot.Plan none = plan(rules, lines, dasher(10), Long.MAX_VALUE);
        assertSame(Autopilot.Reason.LEARNED, Autopilot.commitReason(none, 63, 50, Autopilot.Reason.LEARNED));
        assertSame(Autopilot.Reason.PINNED, Autopilot.commitReason(none, 63, 50, null));
    }
}
