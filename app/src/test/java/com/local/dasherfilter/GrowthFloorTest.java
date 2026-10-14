package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Bars an accept held up never grow the minimums (0.6.11, keeping the owner's 12 October rule that the saved minimums
 * never rise by themselves from an accept): a line whose bar a hand accept could have held (the accept came before it,
 * at most 24 hours before, and scored at least its bar) counts as 100 toward growth. A bar Autopilot's own value rule
 * set above every accept's score still counts, and so does an accept from more than a day before.
 */
public final class GrowthFloorTest {
    private static final long HOUR = 60 * 60_000L;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    /** Noon UTC, so 15 lines this morning and 15 the morning before fall on two days. */
    private static final long NOW = 1_760_011_200_000L;
    private static final long SINCE = NOW - 7 * 24 * HOUR;
    private static final FilterSettings RULES = new FilterSettings(true, 500, 0, 0, 0, true, 70, 110);

    /** 30 counted lines Autopilot decided at 110% under these rules: 15 this morning, 15 yesterday morning. */
    private static List<Autopilot.OfferRecord> lines() {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long at = NOW - (1 + i % 15) * 10 * 60_000L - (i >= 15 ? 24 * HOUR : 0);
            lines.add(new Autopilot.OfferRecord(new OfferSnapshot(1300 + i, 5.0, 20, 2), at, false, false, false,
                    false, false, 110, true, DecisionLog.MODEL, RULES));
        }
        return lines;
    }

    /** The oldest line's time. */
    private static long oldest() {
        long at = Long.MAX_VALUE;
        for (Autopilot.OfferRecord line : lines()) at = Math.min(at, line.at);
        return at;
    }

    private static Growth.Evidence evidence(Autopilot.HandAccept accept) {
        return Growth.evidence(RULES, Autopilot.countedLines(lines()), SINCE, UTC,
                Collections.singletonList(accept));
    }

    @Test
    public void withoutAnAcceptThirtyLinesAtOneHundredTenOverTwoDaysGrow() {
        Growth.Evidence due = Growth.evidence(RULES, Autopilot.countedLines(lines()), SINCE, UTC);
        assertNotNull(due);
        assertEquals(10, due.percent());
        assertEquals(2, due.days);
        assertNotNull("the four-argument form is the empty list's",
                Growth.evidence(RULES, Autopilot.countedLines(lines()), SINCE, UTC,
                        Collections.<Autopilot.HandAccept>emptyList()));
    }

    @Test
    public void anAcceptThatScoredTheLinesBarHeldThemUpSoNothingGrows() {
        assertNull(evidence(new Autopilot.HandAccept(oldest() - HOUR, 110)));
        assertNull("more than their bar holds them too", evidence(new Autopilot.HandAccept(oldest() - HOUR, 140)));
    }

    @Test
    public void anAcceptBelowTheLinesBarLeavesThemAutopilotsOwn() {
        Growth.Evidence due = evidence(new Autopilot.HandAccept(oldest() - HOUR, 105));
        assertNotNull("the lines' 110 is Autopilot's own, above the accept's 105", due);
        assertEquals(110, due.lowestBar);
    }

    @Test
    public void anAcceptMoreThanADayBeforeHoldsNothing() {
        assertNotNull(evidence(new Autopilot.HandAccept(oldest() - 25 * HOUR, 140)));
        // An accept after every line held none of them.
        assertNotNull(evidence(new Autopilot.HandAccept(NOW, 140)));
        // Exactly a day before the oldest line still holds it, and so the lowest is 100.
        assertNull(evidence(new Autopilot.HandAccept(oldest() - 24 * HOUR, 140)));
    }

    @Test
    public void thePlanPassesItsHandAcceptsToGrowth() {
        List<Autopilot.OfferRecord> lines = lines();
        Autopilot.State state = new Autopilot.State(false, 0, Autopilot.NEVER, -1, Autopilot.NEVER, 110, null);
        Autopilot.Plan plain = Autopilot.plan(new Autopilot.Inputs(RULES, lines, null, null, state, NOW, 1, SINCE, UTC,
                0, Long.MAX_VALUE));
        assertNotNull(plain.growth);
        // The same 30 lines, plus yesterday's accept at 6 am that scored 140, an hour before the oldest of them.
        List<Autopilot.OfferRecord> held = new ArrayList<>(lines);
        held.add(new Autopilot.OfferRecord(new OfferSnapshot(1400, 5.0, 20, 2), oldest() - 2 * HOUR, false, false,
                true, false, false, 110, true, DecisionLog.MODEL, RULES, false, oldest() - HOUR));
        Autopilot.Plan plan = Autopilot.plan(new Autopilot.Inputs(RULES, held, null, null, state, NOW, 1, SINCE, UTC, 0,
                Long.MAX_VALUE));
        assertNull("no floor this dash, but yesterday's accept held the bars", plan.growth);
        assertEquals(-1, plan.floor);
        assertEquals(24 * HOUR, Growth.HELD_HORIZON_MS);
    }
}
