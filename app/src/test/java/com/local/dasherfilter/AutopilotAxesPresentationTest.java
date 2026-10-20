package com.local.dasherfilter;

import android.content.Context;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The actual working requirements, recorded score basis and display-only rounding across the existing surfaces. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
public final class AutopilotAxesPresentationTest {
    private static final long NOW = Instant.parse("2026-10-19T14:00:00Z").toEpochMilli();
    private static final OfferSnapshot ACCEPT = new OfferSnapshot(660, 6.0, 12, 2);

    private static FilterSettings baseline(int bar) {
        return FilterSettings.of(true, 400, 200, 40, 3).withAutopilot(true, 0)
                .withMinimumScalePercent(bar);
    }

    private static FilterSettings accepted(FilterSettings rules, OfferSnapshot offer, long at) {
        return rules.withAutopilotAxes(rules.autopilotAxes.withOffer(rules, offer, at));
    }

    private static AutopilotText.Status status(FilterSettings rules) {
        return new AutopilotText.Status(rules, true, null, null, null, NOW);
    }

    private static DecisionLog.Entry entry(FilterSettings rules, OfferSnapshot offer, long at) throws Exception {
        OfferRule.Decision decision = OfferRule.evaluate(offer, rules);
        DecisionLog.Entry line = DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, offer, decision,
                DecisionLog.Action.PASSES, true, Collections.emptyList());
        JSONObject stored = line.toJson().put("at", at);
        return DecisionLog.Entry.fromJson(stored);
    }

    @Test public void currentAmountsFollowEveryAxisAtANonHundredBarWithoutApplyingItTwice() {
        FilterSettings first = accepted(baseline(82), ACCEPT, NOW);
        assertEquals("$3.30/stop · $1.10/mi · $33.00/hr", AutopilotText.workingMinimums(first));
        String details = AutopilotText.detailsAxes(status(first));
        assertTrue(details, details.contains("Working minimums now: $3.30/stop · $1.10/mi · $33.00/hr"));
        assertTrue(details, details.contains("up or down"));
        assertTrue(details, details.contains("Saved minimums and learned bests stay separate"));
        assertFalse(AutopilotText.detailsBar(status(first)).contains("of your minimums"));

        FilterSettings later = accepted(first, new OfferSnapshot(900, 3.0, 18, 3), NOW + 1000);
        assertEquals("two measures fell while the mileage rate rose", "$3.00/stop · $3.00/mi · $30.00/hr",
                AutopilotText.workingMinimums(later));
        assertEquals("the displayed bar is unchanged", 82, later.minimumScalePercent);
        assertEquals(400, later.flatCents);
        assertEquals(200, later.perMileCents);
        assertEquals(40, later.perMinuteCents);
    }

    @Test public void repeatingUnitRatesAreApproximateButRecordedOfferAsksStayExact() throws Exception {
        OfferSnapshot thirds = new OfferSnapshot(1000, 3.0, 17, 3);
        FilterSettings rules = accepted(baseline(83), thirds, NOW);
        assertEquals("≈$3.34/stop · ≈$3.34/mi · ≈$35.30/hr", AutopilotText.workingMinimums(rules));
        AreaScore.Floors floors = AreaScore.floors(rules, thirds);
        for (int axis : new int[] {AreaScore.PAY, AreaScore.MILE, AreaScore.MINUTE}) {
            assertEquals("display rounding cannot add a cent when the exact bar cancels", 1000,
                    floors.axisRequiredCents(axis, 83));
        }
        DecisionLog.Entry line = entry(rules, thirds, NOW);
        assertEquals(1000, line.requiredCents);
        String how = MinimumsDetails.describe(baseline(100), line);
        assertTrue(how, how.contains("Recorded decision: strict rules, Autopilot score 83%, bar 83%."));
        assertTrue(how, how.contains("Pay per stop — saved $12.00"));
        assertEquals("each offer-specific ask is exact, unlike its rounded unit rate", 3,
                how.split("Autopilot used \\$10\\.00", -1).length - 1);
        assertTrue(how, how.contains("Required $10.00 under the recorded strict model at 83%"));
    }

    @Test public void unreadAndDisabledMeasuresDoNotAcquireInventedValues() {
        FilterSettings rules = FilterSettings.of(true, 400, 200, 0, 3).withAutopilot(true, 0)
                .withMinimumScalePercent(82);
        FilterSettings first = accepted(rules, ACCEPT, NOW);
        FilterSettings later = accepted(first, new OfferSnapshot(900, null, 18, 3), NOW + 1000);
        assertEquals("$3.00/stop · $1.10/mi", AutopilotText.workingMinimums(later));
        assertEquals("off", AutopilotText.workingAxisRate(later, AreaScore.MINUTE));
        assertFalse(later.autopilotAxes.hasAxis(AreaScore.MINUTE));
        assertEquals(first.autopilotAxes.rate(AreaScore.MILE), later.autopilotAxes.rate(AreaScore.MILE));
    }

    @Test public void pendingAndPerAxisChangesHaveAnExplanationWithoutAFictitiousBarMove() {
        AutopilotText.Status pending = new AutopilotText.Status(baseline(82), true, null, null, null, false, 0,
                null, NOW, true);
        assertNull("a pending adjustment is not yet an effective amount", AutopilotText.detailsAxes(pending));
        assertTrue(AutopilotText.detailsLines(pending).contains("Your latest acceptance will update each readable "
                + "active minimum as soon as no offer is up."));

        FilterSettings rules = accepted(baseline(82), ACCEPT, NOW);
        AutopilotStore.Change changed = new AutopilotStore.Change(NOW, 82, 82,
                Autopilot.Reason.ACCEPTED_AXES.name());
        AutopilotText.Status committed = new AutopilotText.Status(rules, true, null, null, changed, NOW);
        String reason = AutopilotText.detailsLastChange(committed);
        assertTrue(reason, reason.contains("working minimums followed each readable measure of your accepted offer"));
        assertFalse(reason, reason.contains("82% → 82%"));
        assertTrue(String.join(" ", AutopilotText.detailsLines(committed)).contains("working minimums wherever "
                + "they ask more"));
    }

    @Test public void recordedScoreLabelsSurvivePersistenceAndDoNotFollowCurrentRules() throws Exception {
        FilterSettings original = baseline(82);
        FilterSettings adjusted = accepted(original, ACCEPT, NOW);
        DecisionLog.Entry before = entry(original, ACCEPT, NOW - 600_000);
        DecisionLog.Entry after = entry(adjusted, ACCEPT, NOW);
        assertEquals("Score 55% of your minimums · bar 82%", AutopilotText.ticketScoreLine(before));
        assertEquals("Autopilot score 82% · bar 82%", AutopilotText.ticketScoreLine(after));
        assertFalse(MinimumsDetails.describe(adjusted, before).contains("Autopilot score"));
        assertTrue(MinimumsDetails.describe(original.withAutopilot(false, 0), after)
                .contains("Recorded decision: strict rules, Autopilot score 82%"));
        assertTrue(OfferReport.read(after).contains(", Autopilot score 82%"));
        assertFalse(OfferReport.read(before).contains("Autopilot score"));
    }

    @Test public void belowBaselineAtOneHundredStillExplainsThatItIsForTheUser() throws Exception {
        FilterSettings rules = accepted(baseline(100), ACCEPT, NOW);
        OfferRule.Decision decision = OfferRule.evaluate(ACCEPT, rules);
        assertEquals(OfferRule.Result.KEEP, decision.result);
        assertTrue(decision.belowMinimums);
        DecisionLog.Entry line = entry(rules, ACCEPT, NOW);
        assertTrue(AutopilotText.passedBelowMinimums(line));
        assertEquals("Autopilot score 100% · bar 100%", AutopilotText.ticketScoreLine(line));
        assertTrue(AutopilotText.ticketBelowMinimums(line).contains("permitted by Autopilot's working minimums"));
        assertTrue(AutopilotText.belowMinimumsCard(ACCEPT, decision).contains("Yours to accept"));
        assertFalse(AutopilotText.belowMinimumsCard(ACCEPT, decision).contains("passed by Autopilot's 100% bar"));
    }

    @Test public void radarShowsIndependentWorkingAsksEvenAtOneHundred() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        OfferSnapshot facts = new OfferSnapshot(600, 2.0, 10, 2);
        FilterSettings rules = accepted(baseline(100), facts, NOW);
        MinimumsStarView radar = new MinimumsStarView(app, new Ui(app));
        radar.show(rules, facts, Collections.singletonList(entry(rules, facts, NOW)));
        assertEquals(800, radar.setAsks(AreaScore.PAY), 0.00001);
        assertEquals(400, radar.setAsks(AreaScore.MILE), 0.00001);
        assertEquals(400, radar.setAsks(AreaScore.MINUTE), 0.00001);
        for (int axis : new int[] {AreaScore.PAY, AreaScore.MILE, AreaScore.MINUTE}) {
            assertEquals(600, radar.barAsks(axis), 0.00001);
        }
        assertTrue(radar.getContentDescription().toString().contains("Autopilot score of 100%"));
        assertTrue(radar.caption().contains("Autopilot's working minimums (bar 100%)"));
        radar.show(rules.withAutopilot(false, 0), facts, Collections.emptyList());
        assertTrue("off means no working override shape", Double.isNaN(radar.barAsks(AreaScore.PAY)));
    }

    @Test public void skylinePayLineAndSelectedDescriptionUseTheAppropriateRuleBasis() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        FilterSettings original = baseline(82);
        FilterSettings adjusted = accepted(original, ACCEPT, NOW);
        DecisionLog.Entry old = entry(original, ACCEPT, NOW - 600_000);
        DecisionLog.Entry current = entry(adjusted, ACCEPT, NOW);
        DecisionChartView chart = new DecisionChartView(app, new Ui(app));
        chart.setRules(adjusted);
        chart.setEntries(Arrays.asList(current, old));
        assertEquals("two-stop working pay, with the bar applied once", 660, chart.payoutThresholdCents());
        chart.select(1);
        assertTrue(chart.getContentDescription().toString().contains("recorded Autopilot score 82%"));
        chart.select(0);
        assertTrue(chart.getContentDescription().toString().contains("score 55% of your minimums"));
    }

    @Test public void offerClockRecomputesUnderCurrentWorkingRulesAndNamesThatBasis() throws Exception {
        OfferSnapshot facts = new OfferSnapshot(600, 2.0, 10, 2);
        FilterSettings original = baseline(100);
        FilterSettings adjusted = accepted(original, facts, NOW);
        List<DecisionLog.Entry> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(entry(original, new OfferSnapshot(600 + i, 2.0, 10, 2), NOW - i * 300_000L));
        }
        OfferClock clock = OfferClock.of(history, adjusted, ZoneOffset.UTC, NOW);
        assertNotNull(clock);
        assertEquals(10, clock.total);
        assertEquals(100, clock.middle[4]);
        assertTrue(clock.said().contains("Autopilot scores under your working minimums now"));
        assertTrue(clock.details().contains("recomputed under the current working minimums"));
        OfferClock off = OfferClock.of(history, original.withAutopilot(false, 0), ZoneOffset.UTC, NOW);
        assertNotNull(off);
        assertEquals(75, off.middle[4]);
        assertEquals(OfferClock.DETAILS, off.details());
        assertFalse(off.said().contains("Autopilot scores"));
    }
}
