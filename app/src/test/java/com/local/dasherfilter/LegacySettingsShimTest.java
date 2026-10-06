package com.local.dasherfilter;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The retired rules' compatibility surface is inert: the old positional constructors map only what still exists and
 * refuse any retired argument that is not its default ("retired rule: …"), the retired fields are constant, and the
 * retired methods change nothing or refuse. This is the only suite that names the retired members, to prove that.
 */
@SuppressWarnings("deprecation")
public final class LegacySettingsShimTest {
    private interface Build {
        FilterSettings run();
    }

    private static void refused(String rule, Build build) {
        try {
            build.run();
            fail("expected retired rule: " + rule);
        } catch (IllegalArgumentException expected) {
            assertEquals("retired rule: " + rule, expected.getMessage());
        }
    }

    private static void sameRules(FilterSettings expected, FilterSettings actual) {
        assertEquals(expected.enabled, actual.enabled);
        assertEquals(expected.flatCents, actual.flatCents);
        assertEquals(expected.perMileCents, actual.perMileCents);
        assertEquals(expected.perMinuteCents, actual.perMinuteCents);
        assertEquals(expected.maxStops, actual.maxStops);
        assertEquals(expected.autopilot, actual.autopilot);
        assertEquals(expected.autopilotGoalPercent, actual.autopilotGoalPercent);
        assertEquals(expected.minimumScalePercent, actual.minimumScalePercent);
        assertEquals(expected.rulesKey(), actual.rulesKey());
    }

    private static final AcceptedBest LEARNED_BEST = new AcceptedBest(1420, 24, 1420, 6.0, 1420, 2);
    private static final DeclinedFloor LEARNED_DECLINE = new DeclinedFloor(1500, AcceptedBest.NONE);

    @Test
    public void everyOldPositionalFormMapsTheRulesThatStillExist() {
        FilterSettings expected = FilterSettings.of(true, 700, 150, 30, 3);
        sameRules(expected, new FilterSettings(true, 700, 150, 30, 0, 3));
        sameRules(expected, new FilterSettings(true, 700, 150, 30, 0, 3, false, 0));
        sameRules(expected, new FilterSettings(true, 700, 150, 30, 0, 3, false, 0, AcceptedBest.NONE));
        sameRules(expected, new FilterSettings(true, 700, 150, 30, 0, 3, false, 0, AcceptedBest.NONE,
                DeclinedFloor.NONE));
        sameRules(expected, new FilterSettings(true, 700, 150, 30, 0, 3, false, 0, AcceptedBest.NONE,
                DeclinedFloor.NONE, false));
        sameRules(expected, new FilterSettings(true, 700, 150, 30, 0, 3, false, 0, AcceptedBest.NONE,
                DeclinedFloor.NONE, false, 0));
        sameRules(expected, new FilterSettings(true, 700, 150, 30, 0, 3, false, 0, AcceptedBest.NONE,
                DeclinedFloor.NONE, false, 0, 100));
        sameRules(expected, new FilterSettings(true, 700, 150, 30, 0, 3, false, 0, AcceptedBest.NONE,
                DeclinedFloor.NONE, false, 0, 100, 0));
        // An empty learned record is still the default, as a fresh install stores it.
        sameRules(expected, new FilterSettings(true, 700, 150, 30, 0, 3, false, 0,
                new AcceptedBest(0, 0, 0, 0, 0, 0, 0, 0), new DeclinedFloor(0, new AcceptedBest(0, 0, 0, 0, 0, 0))));
        // The old minimums scale becomes the bar, with Autopilot off.
        FilterSettings scaled = new FilterSettings(true, 700, 150, 30, 0, 3, false, 0, AcceptedBest.NONE,
                DeclinedFloor.NONE, false, 0, 97);
        sameRules(expected.withMinimumScalePercent(97), scaled);
        assertFalse(scaled.autopilot);
        assertEquals(70, scaled.autopilotGoalPercent);
    }

    @Test
    public void everyRetiredArgumentThatIsNotItsDefaultIsRefused() {
        refused("per stop", () -> new FilterSettings(true, 1300, 385, 41, 475, 3));
        refused("per stop", () -> new FilterSettings(true, 0, 0, 0, -1, 0));
        refused("adaptive", () -> new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        refused("adaptive", () -> new FilterSettings(true, 1000, 0, 0, 0, 0, false, 2500));
        refused("adaptive", () -> new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0, LEARNED_BEST));
        refused("adaptive", () -> new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0, AcceptedBest.NONE,
                LEARNED_DECLINE));
        refused("adaptive", () -> new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0, AcceptedBest.NONE,
                new DeclinedFloor(0, new AcceptedBest(0, 0, 0, 0, 1000, 2))));
        refused("score by area", () -> new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0, AcceptedBest.NONE,
                DeclinedFloor.NONE, true));
        refused("hotspot", () -> new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0, AcceptedBest.NONE,
                DeclinedFloor.NONE, false, 50));
        refused("per item", () -> new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0, AcceptedBest.NONE,
                DeclinedFloor.NONE, false, 0, 100, 735));
        // The first retired rule found is named.
        refused("per stop", () -> new FilterSettings(true, 1000, 0, 0, 475, 3, true, 1500, LEARNED_BEST,
                LEARNED_DECLINE, true, 50, 80, 735));
    }

    @Test
    public void theRetiredFieldsAreConstant() {
        for (FilterSettings rules : new FilterSettings[] {FilterSettings.of(true, 1300, 385, 41, 3),
                new FilterSettings(false, 0, 0, 0, 0, 0), new FilterSettings(true, 400, 100, 25, 0, true, 50, 82),
                new FilterSettings(true, 700, 150, 30, 0, 3, false, 0, AcceptedBest.NONE, DeclinedFloor.NONE, false,
                        0, 97, 0)}) {
            assertFalse(rules.risingOffers);
            assertFalse(rules.scoreByArea);
            assertEquals(0, rules.perStopCents);
            assertEquals(0, rules.perItemCents);
            assertEquals(0, rules.hotspotProximityHundredths);
            assertEquals(0, rules.lastAcceptedCents);
            assertSame(AcceptedBest.NONE, rules.best);
            assertSame(DeclinedFloor.NONE, rules.declined);
            assertArrayEquals(new int[] {rules.flatCents, rules.perMileCents, rules.perMinuteCents, 0, 0, 0},
                    rules.minimums());
        }
    }

    @Test
    public void theRetiredMethodsChangeNothingOrRefuse() {
        FilterSettings rules = FilterSettings.of(true, 1300, 385, 41, 3).withMinimumScalePercent(90);
        assertSame(rules, rules.withScoreByArea(false));
        assertSame(rules, rules.withAdaptive(false));
        assertSame(rules, rules.adoptAdaptive());
        assertSame(rules, rules.withoutRisingBaseline());
        assertSame(rules, rules.withHotspotProximity(0));
        assertSame(rules, rules.withPerItem(0));
        try {
            rules.withScoreByArea(true);
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("retired rule: score by area", expected.getMessage());
        }
        try {
            rules.withAdaptive(true);
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("retired rule: adaptive", expected.getMessage());
        }
        refused("hotspot", () -> rules.withHotspotProximity(100));
        refused("per item", () -> rules.withPerItem(735));
        refused("per stop", () -> rules.withMinimums(new int[] {1300, 385, 41, 475}));
        refused("hotspot", () -> rules.withMinimums(new int[] {1300, 385, 41, 0, 50}));
        refused("per item", () -> rules.withMinimums(new int[] {1300, 385, 41, 0, 0, 735}));
        sameRules(rules.withMinimums(1400, 400, 45), rules.withMinimums(new int[] {1400, 400, 45, 0, 0, 0}));
        sameRules(rules.withMinimums(1400, 400, 45), rules.withMinimums(new int[] {1400, 400, 45, 0}));
        sameRules(rules.withMinimums(1400, 400, 45), rules.withMinimums(new int[] {1400, 400, 45}));
        assertEquals("1.25 /mi", FilterSettings.proximityLabel(125));
    }

    @Test
    public void theLearnedRecordsAreShellsThatComputeNothing() {
        OfferSnapshot offer = new OfferSnapshot(2500, 6.0, 24, 2);
        assertSame(AcceptedBest.NONE, AcceptedBest.NONE.raisedBy(offer));
        assertSame(LEARNED_BEST, LEARNED_BEST.raisedBy(offer));
        assertFalse(LEARNED_BEST.isEmpty());
        assertTrue(AcceptedBest.NONE.isEmpty());
        assertEquals(0, LEARNED_BEST.forMinutes(30));
        assertEquals(0, LEARNED_BEST.forMiles(7.5));
        assertEquals(0, LEARNED_BEST.forStops(4));
        assertEquals(0, LEARNED_BEST.forItems(3));
        assertEquals("", LEARNED_BEST.summary());
        assertEquals("", LEARNED_BEST.perMileLabel());
        assertSame(DeclinedFloor.NONE, DeclinedFloor.raisedBy(FilterSettings.of(true, 700, 150, 30, 0), offer));
        assertEquals(0, LEARNED_DECLINE.beatPay());
        assertEquals("", LEARNED_DECLINE.summary());
        assertFalse(LEARNED_DECLINE.isEmpty());
        assertEquals(OfferSanity.PLAUSIBLE_STOPS, AcceptedBest.PLAUSIBLE_STOPS);
        assertEquals(OfferSanity.PLAUSIBLE_MINUTES, AcceptedBest.PLAUSIBLE_MINUTES);
        assertEquals(OfferSanity.PLAUSIBLE_MILES, AcceptedBest.PLAUSIBLE_MILES, 0);
        assertTrue(AcceptedBest.looksMisread(new OfferSnapshot(900, 6.0, 1, 2)));
        assertFalse(AcceptedBest.looksMisread(offer));
    }

    @Test
    public void noUnreadableHotspotIsEverTheOnlyThingMissing() {
        FilterSettings rules = FilterSettings.of(true, 1000, 100, 0, 0);
        assertFalse(OfferRule.onlyHotspotMissing(new OfferSnapshot(1500, null, 20, 2), null, rules));
        assertFalse(OfferRule.onlyHotspotMissing(new OfferSnapshot(1500, 6.0, 20, 2), null, rules));
    }
}
