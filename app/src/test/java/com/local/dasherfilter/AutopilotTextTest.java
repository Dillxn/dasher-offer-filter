package com.local.dasherfilter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Every Autopilot string of finalSpec.autopilot.explainability, for the worked example's states: the owner pinned at
 * the lowest bar, the same offers after typical minimums, recovery, holding the goal, no reading yet, the app's own
 * estimate, pay first (and its one-in-five floor), learning, and the gates before any of them. Pure string building:
 * the plans come from the pure engine, the statuses are built directly.
 */
public final class AutopilotTextTest {
    private static final long NOW = 1_760_000_000_000L;
    private static final long MIN = 60_000L;

    /** The worked example's 20 counted offers, newest first: pay in cents, miles, minutes; two stops each. */
    private static final int[] PAY = {1625, 975, 700, 1350, 575, 800, 2250, 600, 1100, 350, 925, 1490, 400, 1050,
            725, 1700, 875, 500, 1225, 650};
    private static final double[] MILES = {6.2, 11.8, 2.2, 7.0, 6.6, 3.0, 12.6, 7.9, 5.1, 4.8, 6.3, 9.9, 2.6, 4.4,
            5.5, 8.8, 7.4, 3.2, 6.0, 4.1};
    private static final int[] MINUTES = {26, 41, 14, 28, 27, 16, 44, 29, 23, 19, 25, 38, 15, 20, 24, 33, 31, 18, 26,
            22};

    // ---- Fixtures ----

    private static FilterSettings rules(int flat, int mile, int minute, int maxStops, int goal, int bar) {
        return new FilterSettings(true, flat, mile, minute, maxStops, true, goal, bar);
    }

    /** Typical minimums: $4.00, $1.00 a mile, $15 an hour. */
    private static FilterSettings starters(int goal, int bar) {
        return rules(400, 100, 25, 0, goal, bar);
    }

    /** The owner's rules after the 0.5.0 migration: $20.40, $4.00 a mile, $28.80 an hour. */
    private static FilterSettings owner(int goal, int bar) {
        return rules(2040, 400, 48, 0, goal, bar);
    }

    private static OfferSnapshot offer(int i) {
        return new OfferSnapshot(PAY[i], MILES[i], MINUTES[i], 2);
    }

    /** The first {@code count} worked offers, the newest 2 minutes old and the rest 3 minutes apart. */
    private static List<Autopilot.OfferRecord> window(int count) {
        long[] ages = new long[count];
        for (int i = 0; i < count; i++) ages[i] = 2 + 3L * i;
        return window(ages, 0);
    }

    /** The worked offers at these ages (minutes), the newest {@code accepted} of them accepted. */
    private static List<Autopilot.OfferRecord> window(long[] ages, int accepted) {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < ages.length; i++) {
            lines.add(new Autopilot.OfferRecord(offer(i), NOW - ages[i] * MIN, false, false, i < accepted, false,
                    false));
        }
        return lines;
    }

    /** The two newest offers 2 and 7 minutes old, the rest from 13 minutes on: two offers after a 12-minute reading. */
    private static long[] twoAfterTwelveMinutes() {
        long[] ages = new long[PAY.length];
        ages[0] = 2;
        ages[1] = 7;
        for (int i = 2; i < ages.length; i++) ages[i] = 13 + 3L * (i - 2);
        return ages;
    }

    /** {@code count} distinct readable offers, {@code accepted} accepted and the rest declined (the app's estimate). */
    private static List<Autopilot.OfferRecord> outcomes(int count, int accepted) {
        List<Autopilot.OfferRecord> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(new Autopilot.OfferRecord(new OfferSnapshot(1000 + 25 * i, 5.0, 20, 2), NOW - (2 + 3L * i) * MIN,
                    false, false, i < accepted, i >= accepted, false));
        }
        return lines;
    }

    private static QualifyingWait.Sample sample(long minutesAgo, long waitMinutes, OfferSnapshot arrival) {
        return new QualifyingWait.Sample(NOW - minutesAgo * MIN, waitMinutes * MIN, arrival);
    }

    /** Busy: 9 arrivals aged 38 … 3 minutes after waits of 2 or 3 minutes, plus a 2-minute open wait (17.1 an hour). */
    private static List<QualifyingWait.Sample> busy() {
        return Arrays.asList(sample(38, 2, new OfferSnapshot(900, 5.0, 24, 2)),
                sample(34, 3, new OfferSnapshot(525, 3.9, 19, 2)), sample(29, 2, new OfferSnapshot(1150, 6.8, 27, 2)),
                sample(25, 3, new OfferSnapshot(700, 5.2, 22, 2)), sample(19, 2, new OfferSnapshot(1400, 9.1, 34, 2)),
                sample(15, 3, new OfferSnapshot(450, 2.9, 16, 2)), sample(11, 2, new OfferSnapshot(1025, 4.6, 21, 2)),
                sample(7, 3, new OfferSnapshot(800, 7.7, 30, 2)), sample(3, 2, new OfferSnapshot(1275, 6.4, 25, 2)),
                sample(0, 2, null));
    }

    /** Dasher's question showed {@code percent} that many minutes ago. */
    private static AutopilotStore.Reading dasher(int percent, long minutesAgo) {
        return new AutopilotStore.Reading(percent, NOW - minutesAgo * MIN, "");
    }

    private static Autopilot.Plan plan(FilterSettings rules, List<Autopilot.OfferRecord> lines,
                                       AutopilotStore.Reading reading, int current) {
        Autopilot.State state = new Autopilot.State(false, 0, Autopilot.NEVER, -1, Autopilot.NEVER, current, null);
        Autopilot.Reading engine = reading == null ? null : new Autopilot.Reading(reading.percent, reading.at);
        return Autopilot.plan(new Autopilot.Inputs(rules, lines, busy(), engine, state, NOW, 1));
    }

    private static AutopilotText.Status status(FilterSettings rules, Autopilot.Plan plan,
                                               AutopilotStore.Reading reading, AutopilotStore.Change change) {
        return new AutopilotText.Status(rules, true, plan, reading, change, NOW);
    }

    private static AutopilotStore.Change change(long minutesAgo, int from, int to, Autopilot.Reason why) {
        return new AutopilotStore.Change(NOW - minutesAgo * MIN, from, to, why.name());
    }

    // ---- The owner, pinned at the lowest bar ----

    @Test
    public void theOwnerPinnedAtTheLowestBar() {
        AutopilotStore.Reading nine = dasher(9, 1);
        Autopilot.Plan plan = plan(owner(70, 100), window(20), nine, 100);
        assertEquals(Autopilot.Mode.PINNED, plan.mode);
        AutopilotText.Status s = status(owner(70, 50), plan, nine, change(4, 100, 50, Autopilot.Reason.TURNED_ON));

        assertEquals(AutopilotText.Status.Kind.PINNED, s.kind);
        assertEquals("Autopilot 50% (lowest) · AR 9% → 70%: about 61 more accepts", AutopilotText.statusLine(s));
        assertEquals("Auto 50% lowest", AutopilotText.chip(s));
        assertEquals("heard in words, not symbols", "Autopilot bar 50 percent, the lowest. Acceptance rate 9 percent, "
                + "goal 70 percent: about 61 more accepts. Opens Autopilot details.", AutopilotText.chipDescription(s));
        assertTrue("the ring is amber", s.belowGoal());
        assertTrue(s.pinned());
        assertEquals(Arrays.asList(
                "Bar: 50% of your minimums (100% = exactly your minimums).",
                "Goal: Keep a top tier — acceptance rate 70% or more.",
                "Acceptance rate: 9%, shown by Dasher 1 min ago (no offers since).",
                "Below your goal: about 61 more accepts to reach 70% (an estimate — DoorDash counts your last 100 "
                        + "offers, so older accepts also roll off).",
                "Offers came about every 2.7 min while you waited with Dasher on screen (last 2 hours).",
                "Offers passing at 50% paid about $32 per hour of Dasher's own time estimates (4 offers). This is "
                        + "history, not a promise: waiting and the drive back are not included.",
                "Last change 4 min ago: 100% → 50% (Autopilot turned on).",
                "Auto-accept only takes offers that meet 100% of your minimums.",
                "Even at the lowest bar (50%) only 4 of your last 20 offers pass. Lower a minimum or use typical "
                        + "minimums."), AutopilotText.detailsLines(s));
        assertEquals("pinned: typical minimums instead of the goal",
                Arrays.asList("Use typical minimums", "Turn off", "Close"), AutopilotText.detailsButtons(s));
        assertEquals("Autopilot: on; goal 70%; bar 50%; mode PINNED; recovering yes; extra 0; AR 9% (Dasher, 1 min "
                + "ago, 0 offers since); last change 100%->50% (Autopilot turned on) 4 min ago",
                AutopilotText.reportLine(s));
        assertEquals("plan 50% PINNED: need 80%, pass 4/20 at 50%, share bar 50%, ar 9% dasher (1 min ago, 0 since), "
                + "recovering yes, extra 0, lambda 17.1/h, mix 20", AutopilotText.logPlan(plan, nine.at, NOW));

        // With max stops set, turning it off is advice too: the spec's whole sentence.
        FilterSettings stops = rules(2040, 400, 48, 3, 70, 50);
        AutopilotText.Status withStops = status(stops, plan(stops.withMinimumScalePercent(100), window(20), nine, 100),
                nine, null);
        assertEquals("Even at the lowest bar (50%) only 4 of your last 20 offers pass. Lower a minimum, turn off max "
                + "stops, or use typical minimums.", AutopilotText.detailsPinned(withStops));

        // On the way down (100 → 80 → 60 → 50) the bar is not the lowest yet.
        AutopilotText.Status stepping = status(owner(70, 80), plan, nine, null);
        assertEquals("Autopilot 80% · AR 9% → 70%: about 61 more accepts", AutopilotText.statusLine(stepping));
        assertEquals("Auto 80% ▲", AutopilotText.chip(stepping));
        assertEquals("Of your last 20 offers, 0 would pass at 80% (your goal needs 16).",
                AutopilotText.detailsPass(stepping));

        // No reading at all: the minimums are the problem, not the acceptance rate.
        Autopilot.Plan unread = plan(owner(70, 100), window(20), null, 100);
        assertEquals(Autopilot.Mode.PINNED, unread.mode);
        AutopilotText.Status noAr = status(owner(70, 50), unread, null, null);
        assertEquals("Autopilot 50% (lowest) · lower a minimum to pass more", AutopilotText.statusLine(noAr));
        assertEquals(AutopilotText.DETAILS_AR_NOT_SEEN, AutopilotText.detailsAcceptanceRate(noAr));
        assertNull("no goal progress without a rate", AutopilotText.detailsGoalProgress(noAr));
    }

    @Test
    public void theOwnerAfterTypicalMinimumsRecoversAtOneHundred() {
        AutopilotStore.Reading nine = dasher(9, 1);
        Autopilot.Plan plan = plan(starters(70, 100), window(20), nine, 100);
        assertEquals(Autopilot.Mode.RECOVERY, plan.mode);
        AutopilotText.Status s = status(starters(70, 100), plan, nine, null);
        assertEquals("Autopilot 100% · AR 9% → 70%: about 61 more accepts", AutopilotText.statusLine(s));
        assertEquals("Auto 100% ▲", AutopilotText.chip(s));
        assertEquals(Arrays.asList("Change goal", "Turn off", "Close"), AutopilotText.detailsButtons(s));
        assertEquals("Of your last 20 offers, 16 would pass at 100% (your goal needs 16).",
                AutopilotText.detailsPass(s));
    }

    /**
     * Pinned with the typical minimums already ($4.00, $1.00 a mile, $15 an hour; max stops 2 against three-stop
     * offers): offering typical minimums would change nothing, so neither the line nor the button offers them.
     */
    @Test
    public void pinnedWithTheTypicalMinimumsAlreadyOffersTheGoalNotTypicalMinimums() {
        AutopilotStore.Reading nine = dasher(9, 1);
        List<Autopilot.OfferRecord> stacked = new ArrayList<>();
        for (int i = 0; i < PAY.length; i++) {
            stacked.add(new Autopilot.OfferRecord(new OfferSnapshot(PAY[i], MILES[i], MINUTES[i], 3),
                    NOW - (2 + 3L * i) * MIN, false, false, false, false, false));
        }
        FilterSettings typical = rules(400, 100, 25, 2, 70, 100);
        Autopilot.Plan plan = plan(typical, stacked, nine, 100);
        assertEquals(Autopilot.Mode.PINNED, plan.mode);
        AutopilotText.Status s = status(typical.withMinimumScalePercent(50), plan, nine, null);
        assertTrue(s.pinned());
        assertTrue(s.typicalMinimums());
        assertEquals("Even at the lowest bar (50%) only 0 of your last 20 offers pass. Lower a minimum or turn off max "
                + "stops.", AutopilotText.detailsPinned(s));
        assertEquals(Arrays.asList("Change goal", "Turn off", "Close"), AutopilotText.detailsButtons(s));

        // Without max stops, offers paying a tenth of what the minimums ask: lowering a minimum is the one advice left.
        List<Autopilot.OfferRecord> cheap = new ArrayList<>();
        for (int i = 0; i < PAY.length; i++) {
            cheap.add(new Autopilot.OfferRecord(new OfferSnapshot(100, 10.0, 40, 2), NOW - (2 + 3L * i) * MIN, false,
                    false, false, false, false));
        }
        FilterSettings noStops = rules(400, 100, 25, 0, 70, 100);
        Autopilot.Plan low = plan(noStops, cheap, nine, 100);
        assertEquals(Autopilot.Mode.PINNED, low.mode);
        AutopilotText.Status open = status(noStops.withMinimumScalePercent(50), low, nine, null);
        assertEquals("Even at the lowest bar (50%) only 0 of your last 20 offers pass. Lower a minimum.",
                AutopilotText.detailsPinned(open));
        assertEquals(Arrays.asList("Change goal", "Turn off", "Close"), AutopilotText.detailsButtons(open));
        assertTrue(AutopilotText.typicalMinimums(FilterSettings.of(true, 400, 100, 25, 3)));
        assertFalse(AutopilotText.typicalMinimums(FilterSettings.of(true, 400, 100, 30, 0)));
        assertFalse(AutopilotText.typicalMinimums(null));
    }

    /** Every status line has a spoken twin in words: no arrow, dot, tilde or "AR" for a screen reader to spell. */
    @Test
    public void everyStatusIsHeardInWordsNotSymbols() {
        AutopilotStore.Reading nine = dasher(9, 1);
        AutopilotStore.Reading reading = dasher(55, 12);
        Autopilot.Plan recovering = plan(starters(70, 100), window(twoAfterTwelveMinutes(), 1), reading, 100);
        Autopilot.Plan atGoal = plan(starters(70, 100), window(20), dasher(74, 1), 100);
        Autopilot.Plan estimate = plan(starters(70, 100), outcomes(29, 9), null, 100);
        Autopilot.Plan estimateHigh = plan(starters(70, 100), outcomes(27, 20), null, 100);
        Autopilot.Plan unread = plan(starters(70, 100), window(20), null, 100);
        Autopilot.Plan learning = plan(starters(70, 100), window(12), null, 100);
        Autopilot.Plan pinned = plan(owner(70, 100), window(20), nine, 100);
        Autopilot.Plan pinnedUnread = plan(owner(70, 100), window(20), null, 100);
        Autopilot.Plan payFirst = plan(starters(0, 100), window(20), null, 100);
        Autopilot.Plan floor = plan(owner(0, 100), window(20), null, 100);
        Object[][] cases = {
                {status(FilterSettings.of(true, 400, 100, 25, 0), null, null, null), "Autopilot off."},
                {new AutopilotText.Status(starters(70, 82), false, recovering, null, null, NOW),
                        "Autopilot waits for screen reading."},
                {status(rules(0, 0, 0, 3, 70, 100), null, null, null),
                        "Autopilot needs a pay, per-mile or hourly minimum."},
                {status(new FilterSettings(false, 400, 100, 25, 0, true, 70, 82), recovering, null, null),
                        "Autopilot bar 82 percent. Auto-decline paused."},
                {status(starters(70, 100), null, null, null), "Autopilot bar 100 percent. Checking your offers."},
                {status(starters(70, 100), learning, null, null),
                        "Autopilot bar 100 percent. Learning, 12 of 20 offers."},
                {status(owner(70, 50), pinned, nine, null), "Autopilot bar 50 percent, the lowest. Acceptance rate 9 "
                        + "percent, goal 70 percent: about 61 more accepts."},
                {status(owner(70, 50), pinnedUnread, null, null),
                        "Autopilot bar 50 percent, the lowest. Lower a minimum to pass more."},
                {status(starters(70, 82), recovering, reading, null), "Autopilot bar 82 percent. Acceptance rate 55 "
                        + "percent, goal 70 percent: about 15 more accepts."},
                {status(starters(70, 100), estimate, null, null), "Autopilot bar 100 percent. Estimated acceptance "
                        + "rate 31 percent, goal 70 percent: about 39 more accepts."},
                {status(starters(70, 96), atGoal, dasher(74, 1), null),
                        "Autopilot bar 96 percent. Acceptance rate 74 percent, goal 70 percent."},
                {status(starters(70, 100), estimateHigh, null, null),
                        "Autopilot bar 100 percent. Estimated acceptance rate 74 percent, goal 70 percent."},
                {status(starters(70, 100), unread, null, null),
                        "Autopilot bar 100 percent. Goal 70 percent. Acceptance rate not seen yet."},
                {status(owner(0, 61), floor, null, null),
                        "Autopilot bar 61 percent. Pay first, keeping 1 in 5 offers."},
                {status(starters(0, 119), payFirst, null, null), "Autopilot bar 119 percent. Pay first."},
        };
        java.util.Set<AutopilotText.Status.Kind> kinds = java.util.EnumSet.noneOf(AutopilotText.Status.Kind.class);
        for (Object[] each : cases) {
            AutopilotText.Status s = (AutopilotText.Status) each[0];
            kinds.add(s.kind);
            String said = AutopilotText.statusSaid(s);
            assertEquals(s.kind.name(), each[1], said);
            assertEquals(said + " Opens Autopilot details.", AutopilotText.chipDescription(s));
            for (String symbol : new String[] {"→", "·", "~", "AR ", "%", "▲"}) {
                assertFalse(s.kind + " says " + symbol + ": " + said, said.contains(symbol));
            }
        }
        assertEquals("every kind of status is heard", java.util.EnumSet.allOf(AutopilotText.Status.Kind.class), kinds);
    }

    // ---- Recovery, the goal held, no reading, the app's estimate ----

    @Test
    public void recoveryAtFiftyFiveWithTwoOffersSinceTheReading() {
        // Dasher showed 55% 12 minutes ago; of the two offers since, the newer was accepted: 5,500 + 100 − 110 =
        // 5,490, which every Autopilot text shows as 55% (rounded half up), Dasher's own figure.
        AutopilotStore.Reading reading = dasher(55, 12);
        List<Autopilot.OfferRecord> lines = window(twoAfterTwelveMinutes(), 1);
        Autopilot.Plan plan = plan(starters(70, 100), lines, reading, 100);
        assertEquals(Autopilot.Mode.RECOVERY, plan.mode);
        assertEquals(5_490, plan.arHundredths);
        AutopilotText.Status s = status(starters(70, 82), plan, reading,
                change(4, 100, 82, Autopilot.Reason.RECOVERY));

        assertEquals(AutopilotText.Status.Kind.BELOW_GOAL, s.kind);
        assertEquals("the rate as shown", 55, s.shownArPercent());
        assertEquals("the rate as a report records it: exact to the hundredth (D3)", 54.9, s.exactArPercent(), 0);
        assertEquals("the goal less the rate as shown", 15, s.acceptsNeeded());
        assertEquals(12, s.arAgeMinutes());
        assertEquals("RECOVERY", s.modeName());
        assertTrue(s.recovering);
        assertEquals(0, s.extra);
        assertEquals("Autopilot 82% · AR 55% → 70%: about 15 more accepts", AutopilotText.statusLine(s));
        assertEquals("Auto 82% ▲", AutopilotText.chip(s));
        assertEquals("Autopilot bar 82 percent. Acceptance rate 55 percent, goal 70 percent: about 15 more accepts. "
                + "Opens Autopilot details.", AutopilotText.chipDescription(s));
        assertEquals(Arrays.asList(
                "Bar: 82% of your minimums (100% = exactly your minimums).",
                "Goal: Keep a top tier — acceptance rate 70% or more.",
                "Acceptance rate: 55%, shown by Dasher 12 min ago (2 offers since).",
                "Below your goal: about 15 more accepts to reach 70% (an estimate — DoorDash counts your last 100 "
                        + "offers, so older accepts also roll off).",
                "Of your last 20 offers, 18 would pass at 82% (your goal needs 16).",
                "Offers came about every 2.7 min while you waited with Dasher on screen (last 2 hours).",
                "Offers passing at 82% paid about $24 per hour of Dasher's own time estimates (18 offers). This is "
                        + "history, not a promise: waiting and the drive back are not included.",
                "Last change 4 min ago: 100% → 82% (acceptance rate below your goal).",
                "Auto-accept only takes offers that meet 100% of your minimums."), AutopilotText.detailsLines(s));
        assertEquals("Autopilot: on; goal 70%; bar 82%; mode RECOVERY; recovering yes; extra 0; AR 55% (Dasher, 12 "
                + "min ago, 2 offers since); last change 100%->82% (acceptance rate below your goal) 4 min ago",
                AutopilotText.reportLine(s));
        assertEquals("plan 100% RECOVERY: need 80%, pass 16/20 at 100%, share bar 100%, ar 55% dasher (12 min ago, 2 "
                + "since), recovering yes, extra 0, lambda 17.1/h, mix 20, best 86%, ok 86-100%",
                AutopilotText.logPlan(plan, reading.at, NOW));
    }

    @Test
    public void theRateIsShownRoundedHalfUpTheSameWayEverywhere() {
        assertEquals(56, AutopilotText.shownPercent(5_590));
        assertEquals(55, AutopilotText.shownPercent(5_549));
        assertEquals("half up", 56, AutopilotText.shownPercent(5_550));
        assertEquals(70, AutopilotText.shownPercent(6_950));
        assertEquals(0, AutopilotText.shownPercent(0));
        assertEquals(100, AutopilotText.shownPercent(10_000));
        assertEquals("unknown", -1, AutopilotText.shownPercent(-1));

        // Dasher showed 55% 12 minutes ago and both offers since were accepted: 5,500 + 200 − 110 = 5,590, so the
        // status line, the chip, the details, the shared report and the plan's log line all say 56%, never 55%.
        AutopilotStore.Reading reading = dasher(55, 12);
        Autopilot.Plan plan = plan(starters(70, 100), window(twoAfterTwelveMinutes(), 2), reading, 100);
        assertEquals(5_590, plan.arHundredths);
        AutopilotText.Status s = status(starters(70, 100), plan, reading, null);
        assertEquals(56, s.shownArPercent());
        assertEquals("a report keeps it exact", 55.9, s.exactArPercent(), 0);
        assertEquals("70 − 56", 14, s.acceptsNeeded());
        assertEquals("Autopilot 100% · AR 56% → 70%: about 14 more accepts", AutopilotText.statusLine(s));
        assertEquals("Auto 100% ▲", AutopilotText.chip(s));
        assertEquals("Acceptance rate: about 56% (Dasher showed 55% 12 min ago; 2 offers since).",
                AutopilotText.detailsAcceptanceRate(s));
        assertTrue(AutopilotText.detailsGoalProgress(s).startsWith("Below your goal: about 14 more accepts"));
        assertTrue(AutopilotText.reportLine(s), AutopilotText.reportLine(s)
                .endsWith("; AR 56% (Dasher, 12 min ago, 2 offers since)"));
        assertTrue(AutopilotText.logPlan(plan, reading.at, NOW).contains(", ar 56% dasher (12 min ago, 2 since), "));

        // 16 of 23 counted offers accepted: ⌊10,000 × 16 ÷ 23⌋ = 6,956, just under the goal in the engine's exact
        // arithmetic, shows as 70%: at the goal, so no amber, no "▲" and no "0 more accepts".
        Autopilot.Plan close = plan(starters(70, 100), outcomes(23, 16), null, 100);
        assertEquals(6_956, close.arHundredths);
        assertTrue("the engine still counts it below the goal", close.recovering);
        AutopilotText.Status shown = status(starters(70, 100), close, null, null);
        assertEquals(70, shown.shownArPercent());
        assertEquals("exact: still under 70", 69.56, shown.exactArPercent(), 1e-9);
        assertFalse(shown.belowGoal());
        assertEquals(0, shown.acceptsNeeded());
        assertEquals(AutopilotText.Status.Kind.AT_GOAL, shown.kind);
        assertEquals("Autopilot 100% · AR ~70%, goal 70%", AutopilotText.statusLine(shown));
        assertEquals("Auto 100%", AutopilotText.chip(shown));
        assertEquals("Acceptance rate: about 70%, estimated from 23 of your recent offers.",
                AutopilotText.detailsAcceptanceRate(shown));
        assertEquals(AutopilotText.DETAILS_AT_GOAL, AutopilotText.detailsGoalProgress(shown));
        assertTrue(AutopilotText.reportLine(shown).contains("; AR ~70% (estimate, 23 offers)"));
        assertTrue(AutopilotText.logPlan(close, -1, NOW).contains(", ar ~70% estimate (23 counted), "));
    }

    @Test
    public void aReadingCarriedForwardSaysWhatDasherShowedAndWhatItIsNow() {
        // The spec's carry-forward: 9%, then 10 counted offers with 8 accepts: 900 + 800 − 90 = 1,610.
        long[] ages = new long[PAY.length];
        for (int i = 0; i < ages.length; i++) ages[i] = 2 + 3L * i;
        AutopilotStore.Reading nine = dasher(9, 30);
        Autopilot.Plan plan = plan(starters(70, 100), window(ages, 8), nine, 100);
        assertEquals(1_610, plan.arHundredths);
        assertEquals(10, plan.arOffersSince);
        AutopilotText.Status s = status(starters(70, 100), plan, nine, null);
        assertEquals("Autopilot 100% · AR 16% → 70%: about 54 more accepts", AutopilotText.statusLine(s));
        assertEquals("Acceptance rate: about 16% (Dasher showed 9% 30 min ago; 10 offers since).",
                AutopilotText.detailsAcceptanceRate(s));
        assertEquals("Below your goal: about 54 more accepts to reach 70% (an estimate — DoorDash counts your last "
                + "100 offers, so older accepts also roll off).", AutopilotText.detailsGoalProgress(s));
        assertTrue(AutopilotText.reportLine(s).endsWith("; AR 16% (Dasher, 30 min ago, 10 offers since)"));
    }

    @Test
    public void atOrAboveTheGoal() {
        AutopilotStore.Reading reading = dasher(74, 1);
        Autopilot.Plan plan = plan(starters(70, 100), window(20), reading, 100);
        assertEquals(Autopilot.Mode.VALUE, plan.mode);
        assertEquals(100, plan.target);
        AutopilotText.Status s = status(starters(70, 100), plan, reading, null);
        assertEquals(AutopilotText.Status.Kind.AT_GOAL, s.kind);
        assertEquals("Autopilot 100% · AR 74%, goal 70%", AutopilotText.statusLine(s));
        assertEquals("Autopilot 96% · AR 74%, goal 70%",
                AutopilotText.statusLine(status(starters(70, 96), plan, reading, null)));
        assertEquals("Auto 100%", AutopilotText.chip(s));
        assertFalse(s.belowGoal());
        assertEquals("Acceptance rate: 74%, shown by Dasher 1 min ago (no offers since).",
                AutopilotText.detailsAcceptanceRate(s));
        assertEquals("At or above your goal.", AutopilotText.detailsGoalProgress(s));
        assertEquals("Of your last 20 offers, 16 would pass at 100% (your goal needs 15).",
                AutopilotText.detailsPass(s));
        assertNull("no change kept", AutopilotText.detailsLastChange(s));
        assertEquals("Autopilot: on; goal 70%; bar 100%; mode VALUE; recovering no; extra 0; AR 74% (Dasher, 1 min "
                + "ago, 0 offers since)", AutopilotText.reportLine(s));
    }

    @Test
    public void noReadingYet() {
        Autopilot.Plan plan = plan(starters(70, 100), window(20), null, 100);
        AutopilotText.Status s = status(starters(70, 100), plan, null, null);
        assertEquals(AutopilotText.Status.Kind.AR_UNKNOWN, s.kind);
        assertEquals("Autopilot 100% · goal 70% · AR not seen yet", AutopilotText.statusLine(s));
        assertEquals("Auto 100%", AutopilotText.chip(s));
        assertEquals("Acceptance rate: not seen yet. Dasher shows it when an offer is declined.",
                AutopilotText.detailsAcceptanceRate(s));
        assertNull(AutopilotText.detailsGoalProgress(s));
        assertTrue(AutopilotText.reportLine(s).endsWith("; AR unknown"));
        assertTrue(AutopilotText.logPlan(plan, -1, NOW).contains(", ar unknown, "));
    }

    @Test
    public void theAppsOwnEstimateIsMarkedWithATilde() {
        // 9 accepted of 29 counted: ⌊10,000 × 9 ÷ 29⌋ = 3,103 → 31%, about 39 more accepts.
        Autopilot.Plan low = plan(starters(70, 100), outcomes(29, 9), null, 100);
        assertEquals(Autopilot.ArSource.ESTIMATE, low.arSource);
        assertEquals(3_103, low.arHundredths);
        AutopilotText.Status s = status(starters(70, 100), low, null, null);
        assertEquals("Autopilot 100% · AR ~31% → 70%: about 39 more accepts", AutopilotText.statusLine(s));
        assertEquals("Acceptance rate: about 31%, estimated from 29 of your recent offers.",
                AutopilotText.detailsAcceptanceRate(s));
        assertTrue(AutopilotText.reportLine(s).contains("; AR ~31% (estimate, 29 offers)"));
        assertTrue(AutopilotText.logPlan(low, -1, NOW).contains(", ar ~31% estimate (29 counted), "));

        // 20 of 27: 7,407 → 74%, at the goal.
        Autopilot.Plan high = plan(starters(70, 100), outcomes(27, 20), null, 100);
        assertEquals(7_407, high.arHundredths);
        assertEquals("Autopilot 100% · AR ~74%, goal 70%",
                AutopilotText.statusLine(status(starters(70, 100), high, null, null)));
    }

    // ---- Pay first ----

    @Test
    public void payFirst() {
        Autopilot.Plan plan = plan(starters(0, 100), window(20), null, 100);
        assertEquals(Autopilot.Mode.VALUE, plan.mode);
        assertEquals(119, plan.target);
        AutopilotText.Status s = status(starters(0, 119), plan, null, change(6, 115, 119, Autopilot.Reason.VALUE_UP));
        assertEquals(AutopilotText.Status.Kind.PAY_FIRST, s.kind);
        assertEquals("Autopilot 119% · pay first", AutopilotText.statusLine(s));
        assertEquals("Auto 119%", AutopilotText.chip(s));
        assertEquals("Goal: Pay first — no acceptance-rate goal.", AutopilotText.detailsLines(s).get(1));
        assertEquals("Of your last 20 offers, 12 would pass at 119%.", AutopilotText.detailsPass(s));
        assertNull("no goal to reach", AutopilotText.detailsGoalProgress(s));
        assertEquals("Last change 6 min ago: 115% → 119% (offers are coming often).",
                AutopilotText.detailsLastChange(s));
        assertTrue(AutopilotText.reportLine(s).startsWith("Autopilot: on; pay first; bar 119%; mode VALUE; "));
        assertEquals("Autopilot, on. Bar 119 percent of your minimums. Goal: pay first.",
                AutopilotText.buttonDescription(true, 119, 0));

        // The owner's minimums with pay first: only the one-in-five floor holds the bar down.
        Autopilot.Plan floor = plan(owner(0, 100), window(20), null, 100);
        assertEquals(Autopilot.Mode.PASS_FLOOR, floor.mode);
        assertEquals(51, floor.target);
        assertEquals("Autopilot 51% · pay first · keeping 1 in 5 offers",
                AutopilotText.statusLine(status(owner(0, 51), floor, null, null)));
        assertEquals("Autopilot 61% · pay first · keeping 1 in 5 offers",
                AutopilotText.statusLine(status(owner(0, 61), floor, null, null)));
    }

    // ---- Learning and the gates ----

    @Test
    public void learningUntilTwentyOffers() {
        Autopilot.Plan plan = plan(starters(70, 100), window(12), null, 100);
        assertEquals(Autopilot.Mode.LEARNING, plan.mode);
        AutopilotText.Status s = status(starters(70, 100), plan, null, null);
        assertEquals("Autopilot 100% · learning (12 of 20 offers)", AutopilotText.statusLine(s));
        assertEquals("Auto learning", AutopilotText.chip(s));
        assertEquals("before the need is planned, no goal part", "Of your last 12 offers, 8 would pass at 100%.",
                AutopilotText.detailsPass(s));
        assertEquals("plan 100% LEARNING: pass 8/12 at 100%, ar unknown, recovering no, extra 0, lambda 17.1/h, "
                + "mix 12", AutopilotText.logPlan(plan, -1, NOW));
    }

    @Test
    public void theGatesComeFirst() {
        Autopilot.Plan plan = plan(starters(70, 100), window(20), dasher(55, 12), 100);
        assertEquals("before the first plan", "Autopilot 100% · checking your offers",
                AutopilotText.statusLine(status(starters(70, 100), null, null, null)));
        assertEquals("Auto 100%", AutopilotText.chip(status(starters(70, 100), null, null, null)));

        AutopilotText.Status waits = new AutopilotText.Status(starters(70, 82), false, plan, null, null, NOW);
        assertEquals("Autopilot waits for screen reading", AutopilotText.statusLine(waits));
        assertEquals("Auto waits", AutopilotText.chip(waits));

        FilterSettings stopsOnly = rules(0, 0, 0, 3, 70, 100);
        AutopilotText.Status needs = status(stopsOnly, null, null, null);
        assertEquals("Autopilot needs a pay, per-mile or hourly minimum", AutopilotText.statusLine(needs));
        assertEquals("Auto needs a minimum", AutopilotText.chip(needs));

        FilterSettings paused = new FilterSettings(false, 400, 100, 25, 0, true, 70, 82);
        assertEquals("Autopilot 82% · auto-decline paused",
                AutopilotText.statusLine(status(paused, plan, null, null)));

        // A plan made for other rules (another goal) says nothing about these.
        AutopilotText.Status otherGoal = status(starters(50, 100), plan, null, null);
        assertNull(otherGoal.plan);
        assertEquals("Autopilot 100% · checking your offers", AutopilotText.statusLine(otherGoal));
    }

    @Test
    public void off() {
        FilterSettings off = FilterSettings.of(true, 400, 100, 25, 0);
        AutopilotText.Status s = status(off, plan(starters(70, 100), window(20), null, 100), dasher(55, 12),
                null);
        assertEquals(AutopilotText.Status.Kind.OFF, s.kind);
        assertNull("no plan while off", s.plan);
        assertNull(s.modeName());
        assertEquals("Dasher's reading still counts", 55, s.shownArPercent());
        assertEquals(55.0, s.exactArPercent(), 0);
        assertEquals(12, s.arAgeMinutes());
        AutopilotText.Status nothing = status(off, null, null, null);
        assertEquals(-1, nothing.shownArPercent());
        assertEquals(-1, nothing.exactArPercent(), 0);
        assertEquals(-1, nothing.arAgeMinutes());
        assertEquals(Autopilot.ArSource.UNKNOWN, nothing.arSource);
        assertEquals("Autopilot off", AutopilotText.statusLine(s));
        assertEquals("Auto off", AutopilotText.chip(s));
        assertFalse(s.belowGoal());
        assertEquals(Arrays.asList(
                "Autopilot is off: offers are judged at exactly your minimums.",
                "Acceptance rate: 55%, shown by Dasher 12 min ago."), AutopilotText.detailsLines(s));
        assertEquals("off: no Change goal, which would turn it on too", Arrays.asList("Turn on", "Close"),
                AutopilotText.detailsButtons(s));
        assertEquals("Autopilot off. Opens Autopilot details.", AutopilotText.chipDescription(s));
        assertEquals("Autopilot: off; goal 70%; bar 100%; AR 55% (Dasher, 12 min ago)", AutopilotText.reportLine(s));
        assertEquals(Arrays.asList("Autopilot is off: offers are judged at exactly your minimums."),
                AutopilotText.detailsLines(status(off, null, null, null)));
        assertEquals("Autopilot: off; goal 70%; bar 100%; AR unknown",
                AutopilotText.reportLine(status(off, null, null, null)));
    }

    // ---- The button, the chooser and the toasts ----

    @Test
    public void theButton() {
        assertEquals("Auto", AutopilotText.BUTTON_TITLE);
        assertEquals("82%", AutopilotText.buttonLabel(true, 82));
        assertEquals("Off", AutopilotText.buttonLabel(false, 100));
        assertEquals("Autopilot, on. Bar 82 percent of your minimums. Goal: keep a top tier, acceptance rate 70 "
                + "percent or more.", AutopilotText.buttonDescription(true, 82, 70));
        assertEquals("Autopilot, on. Bar 82 percent of your minimums. Goal: keep a tier, acceptance rate 50 percent "
                + "or more.", AutopilotText.buttonDescription(true, 82, 50));
        assertEquals("Autopilot, off. Offers are judged at exactly your minimums.",
                AutopilotText.buttonDescription(false, 100, 70));
        assertEquals("Turn Autopilot on", AutopilotText.buttonAction(false));
        assertEquals("Turn Autopilot off", AutopilotText.buttonAction(true));
        assertEquals("Turn Autopilot on", AutopilotText.ACTION_TURN_ON);
        assertEquals("Turn Autopilot off", AutopilotText.ACTION_TURN_OFF);
        assertEquals("Change acceptance goal", AutopilotText.ACTION_CHANGE_GOAL);
        assertEquals("Autopilot details", AutopilotText.ACTION_DETAILS);
        assertEquals(0x4F460003, AutopilotText.DETAILS_ACTION_ID);
        assertEquals("Set a pay, per-mile or hourly minimum first.", AutopilotText.TOAST_NEEDS_MINIMUM);
        assertEquals("Autopilot off · back to exactly your minimums", AutopilotText.TOAST_OFF);
    }

    @Test
    public void theGoalChooser() {
        assertEquals("What matters more?", AutopilotText.CHOOSER_TITLE);
        assertEquals(Arrays.asList(
                "Keep a top tier — acceptance rate 70% or more",
                "Keep a tier — acceptance rate 50% or more",
                "Pay first — no acceptance-rate goal"), AutopilotText.chooserItems());
        assertEquals("70% is preselected", 0, AutopilotText.chooserIndex(70));
        assertEquals(1, AutopilotText.chooserIndex(50));
        assertEquals(2, AutopilotText.chooserIndex(0));
        assertEquals("anything else reads as 70%", 0, AutopilotText.chooserIndex(33));
        assertEquals("About acceptance rate", AutopilotText.CHOOSER_ABOUT);
        assertEquals("DoorDash computes acceptance rate over your recent offers (it reports the last 100). Its rewards "
                + "tiers use acceptance-rate minimums — check the Dasher app for your current requirements.",
                AutopilotText.ABOUT_ACCEPTANCE_RATE);
        assertEquals("Not now", AutopilotText.CHOOSER_NOT_NOW);
        assertEquals("named under the choices while Autopilot is off", "Choosing one turns on Autopilot, which adjusts "
                + "how much of your minimums an offer must meet. Not now leaves it off.",
                AutopilotText.CHOOSER_TURNS_ON);
        assertEquals("Autopilot on · goal: acceptance rate 70% or more", AutopilotText.toastTurnedOn(70));
        assertEquals("Autopilot on · goal: acceptance rate 50% or more", AutopilotText.toastTurnedOn(50));
        assertEquals("Autopilot on · pay first", AutopilotText.toastTurnedOn(0));
        assertEquals("Autopilot goal: acceptance rate 70% or more", AutopilotText.toastGoalChanged(70));
        assertEquals("Autopilot goal: acceptance rate 50% or more", AutopilotText.toastGoalChanged(50));
        assertEquals("Autopilot goal: pay first", AutopilotText.toastGoalChanged(0));
        assertEquals("Autopilot", AutopilotText.DETAILS_TITLE);
    }

    // ---- Reasons, the ticket, the card and the skyline ----

    @Test
    public void everyReasonHasItsWords() {
        String[][] words = {
                {"TURNED_ON", "Autopilot turned on"}, {"GOAL_CHANGED", "you changed your goal"},
                {"RULES_CHANGED", "you changed your minimums"}, {"CLEARED", "history cleared"},
                {"LEARNING", "learning from your offers"}, {"PINNED", "your minimums are high for these offers"},
                {"RECOVERY", "acceptance rate below your goal"}, {"GOAL", "keeping enough offers for your goal"},
                {"PASS_FLOOR", "keeping at least 1 in 5 offers coming to you"},
                {"AT_MINIMUMS", "holding at your minimums"}, {"VALUE_UP", "offers are coming often"},
                {"VALUE_DOWN", "offers are slower"}, {"MINIMUMS_GREW", "your minimums grew"}};
        assertEquals(Autopilot.Reason.values().length, words.length);
        for (String[] reason : words) {
            assertEquals(reason[1], AutopilotText.reasonWords(Autopilot.Reason.valueOf(reason[0])));
            assertEquals("a stored name reads the same", reason[1], AutopilotText.reasonWords(reason[0]));
        }
        assertEquals("a name this version does not know", "", AutopilotText.reasonWords("SOMETHING_NEWER"));
        assertEquals("", AutopilotText.reasonWords((String) null));
        assertEquals("a change note with an unknown reason has no parenthesis",
                "Last change 4 min ago: 100% → 82%.", AutopilotText.detailsLastChange(status(starters(70, 82), null,
                        null, new AutopilotStore.Change(NOW - 4 * MIN, 100, 82, "SOMETHING_NEWER"))));
    }

    @Test
    public void theTicketAndTheBelowMinimumsCard() {
        assertEquals("Score 85% of your minimums · bar 82%", AutopilotText.ticketScoreLine(85, 82, 2));
        assertEquals("Score 85% of your minimums", AutopilotText.ticketScoreLine(85, 100, 2));
        assertEquals("Area score (retired rules) 121%", AutopilotText.ticketScoreLine(121, 100, 1));
        assertNull("no score", AutopilotText.ticketScoreLine(-1, 82, 2));
        assertEquals("Below your minimums: passed by Autopilot's 82% bar. Never auto-accepted.",
                AutopilotText.ticketBelowMinimums(82));
        assertEquals("Dasher said declining it does not lower your acceptance rate.", AutopilotText.TICKET_EXEMPT);
        assertEquals("How this offer was judged", AutopilotText.TICKET_KEY);
        assertEquals("$5.75 · 6.6 mi · 27 min; below your minimums (85%), passed by Autopilot's 82% bar. Yours to "
                + "accept.", AutopilotText.belowMinimumsCard("$5.75 · 6.6 mi · 27 min", 85, 82));
        // As the passing and review cards show facts: OfferSnapshot.summary().
        OfferSnapshot facts = new OfferSnapshot(575, 6.6, 27, 2);
        assertEquals(facts.summary() + "; below your minimums (85%), passed by Autopilot's 82% bar. Yours to accept.",
                AutopilotText.belowMinimumsCard(facts, 85, 82));

        // The homepage's words for an offer that passed only by the lowered bar, and which lines those are.
        assertEquals("Passed below your minimums", AutopilotText.PASSED_BELOW_MINIMUMS);
        FilterSettings at82 = FilterSettings.of(true, 400, 100, 25, 3).withAutopilot(true, 70)
                .withMinimumScalePercent(82);
        assertTrue("$5.75 against $6.75: 85%, passed by the 82% bar",
                AutopilotText.passedBelowMinimums(line(facts, at82)));
        assertFalse("$9.00 meets the minimums themselves",
                AutopilotText.passedBelowMinimums(line(new OfferSnapshot(900, 6.6, 27, 2), at82)));
        assertFalse("a decline is no pass", AutopilotText.passedBelowMinimums(line(new OfferSnapshot(300, 6.6, 27, 2),
                at82)));
        assertFalse("at exactly the minimums nothing passes below them", AutopilotText.passedBelowMinimums(
                line(facts, FilterSettings.of(true, 400, 100, 25, 3))));
        assertFalse(AutopilotText.passedBelowMinimums(null));
    }

    /** {@code facts} as a line decided under {@code rules}, as the screen reader records it. */
    private static DecisionLog.Entry line(OfferSnapshot facts, FilterSettings rules) {
        OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
        return DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision,
                decision.result == OfferRule.Result.DECLINE ? DecisionLog.Action.DECLINE_TAPPED
                        : DecisionLog.Action.PASSES, true, java.util.Collections.<String>emptyList());
    }

    @Test
    public void theSkyline() {
        assertEquals("Bar 82%", AutopilotText.skylineBarRail(82));
        assertEquals("Bar 100%", AutopilotText.skylineBarRail(100));
        assertEquals("⌈0.82 × $4.00⌉", "Pay $3.28 min", AutopilotText.skylinePayRail(400, 82));
        assertEquals("⌈0.82 × $4.01⌉: rounded up to the cent", "Pay $3.29 min", AutopilotText.skylinePayRail(401, 82));
        assertNull("no rail without a minimum pay", AutopilotText.skylinePayRail(0, 82));
        assertEquals("Tree height is the recorded score: pay as a percent of your minimums; the dashed line is the "
                + "current bar.", AutopilotText.SKYLINE_DESCRIPTION);
    }

    // ---- The diagnostic log ----

    @Test
    public void logLinesAreFixedWordsAndNumbers() {
        assertEquals("on; goal 70%", AutopilotText.logOn(70));
        assertEquals("on; pay first", AutopilotText.logOn(0));
        assertEquals("off; bar back to 100%", AutopilotText.LOG_OFF);
        assertEquals("goal 50% (was 70%)", AutopilotText.logGoal(50, 70));
        assertEquals("pay first (was 50%)", AutopilotText.logGoal(0, 50));
        assertEquals("goal 70% (was pay first)", AutopilotText.logGoal(70, 0));
        assertEquals("commit 100% -> 82% (acceptance rate below your goal)",
                AutopilotText.logCommit(100, 82, Autopilot.Reason.RECOVERY));
        assertEquals("commit deferred: offer or decline in flight", AutopilotText.LOG_DEFERRED);
        assertEquals("plan discarded: rules changed", AutopilotText.DISCARD_RULES);
        assertEquals("plan discarded: older than 15 min", AutopilotText.DISCARD_OLD);
        assertEquals("plan discarded: history cleared", AutopilotText.DISCARD_CLEARED);
        assertEquals("ar reading 55% (exempt no)", AutopilotText.logReading(55, false));
        assertEquals("ar reading 9% (exempt yes)", AutopilotText.logReading(9, true));
        assertEquals("ar reading none (exempt yes)", AutopilotText.logReading(-1, true));
        assertEquals("ar exemptions ignored: 12 of 20 declines flagged", AutopilotText.logExemptionsIgnored(12, 20));
        assertEquals("the class, never its message", "plan failed: IllegalStateException",
                AutopilotText.logPlanFailed(new IllegalStateException("Deliver to Sam at 12 Elm St")));
        assertEquals("commit failed: ArithmeticException",
                AutopilotText.logCommitFailed(new ArithmeticException("9%")));
    }

    @Test
    public void timesReadPlainly() {
        assertEquals("less than a minute ago", AutopilotText.ago(0));
        assertEquals("less than a minute ago", AutopilotText.ago(59_999));
        assertEquals("a later clock reads as just now", "less than a minute ago", AutopilotText.ago(-5_000));
        assertEquals("1 min ago", AutopilotText.ago(MIN));
        assertEquals("59 min ago", AutopilotText.ago(59 * MIN + 59_999));
        assertEquals("1 h ago", AutopilotText.ago(60 * MIN));
        assertEquals("47 h ago", AutopilotText.ago(47 * 60 * MIN));
        assertEquals("2 days ago", AutopilotText.ago(48 * 60 * MIN));
        assertEquals("3.5 min", AutopilotText.minutes(210_000));
        assertEquals("2.7 min", AutopilotText.minutes(160_000));
        assertEquals("2 min", AutopilotText.minutes(120_000));
        assertEquals("9.9 min", AutopilotText.minutes(594_000));
        assertEquals("10 min", AutopilotText.minutes(599_999));
        assertEquals("17 min", AutopilotText.minutes(17 * MIN));
        assertEquals("0.1 min", AutopilotText.minutes(1_000));
    }

    @Test
    public void singularWords() {
        // One offer since the reading, one accept still needed, one offer passing.
        long[] ages = new long[PAY.length];
        ages[0] = 2;
        for (int i = 1; i < ages.length; i++) ages[i] = 13 + 3L * i;
        AutopilotStore.Reading reading = dasher(69, 12);
        Autopilot.Plan plan = plan(starters(70, 100), window(ages, 1), reading, 100);
        assertEquals("6,900 + 100 − 69", 6_931, plan.arHundredths);
        AutopilotText.Status s = status(starters(70, 100), plan, reading, null);
        assertEquals("Autopilot 100% · AR 69% → 70%: about 1 more accept", AutopilotText.statusLine(s));
        assertEquals("Acceptance rate: 69%, shown by Dasher 12 min ago (1 offer since).",
                AutopilotText.detailsAcceptanceRate(s));
        assertTrue(AutopilotText.detailsGoalProgress(s).startsWith("Below your goal: about 1 more accept to reach"));
        // Ten offers score 150% or more: $132.90 over 268 of Dasher's minutes is $29.75 an hour, about $30.
        assertEquals("Offers passing at 150% paid about $30 per hour of Dasher's own time estimates (10 offers). This "
                + "is history, not a promise: waiting and the drive back are not included.",
                AutopilotText.detailsPaid(status(starters(70, 150), plan, reading, null)));
        // Under the owner's minimums only the $16.25 offer (26 min, score 65%) passes at 60%: $37.50 an hour.
        AutopilotStore.Reading nine = dasher(9, 1);
        Autopilot.Plan ownerPlan = plan(owner(70, 100), window(20), nine, 100);
        AutopilotText.Status one = status(owner(70, 60), ownerPlan, nine, null);
        assertEquals("Offers passing at 60% paid about $38 per hour of Dasher's own time estimates (1 offer). This is "
                + "history, not a promise: waiting and the drive back are not included.",
                AutopilotText.detailsPaid(one));
        assertEquals("Of your last 20 offers, 1 would pass at 60% (your goal needs 16).",
                AutopilotText.detailsPass(one));
        assertNull("none passes: no pay figure", AutopilotText.detailsPaid(status(owner(70, 66), ownerPlan, nine,
                null)));
    }

    // ---- Purity ----

    @Test
    public void autopilotTextIsPureStringBuilding() throws IOException {
        File root = new File("../app").isDirectory() ? new File("..") : new File(".");
        File source = new File(root, "app/src/main/java/com/local/dasherfilter/AutopilotText.java");
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        assertFalse("imports no android class", text.contains("import android"));
        assertFalse("names no android class", text.contains("android."));
        assertFalse("reads no clock", text.contains("currentTimeMillis") || text.contains("elapsedRealtime"));
    }
}
