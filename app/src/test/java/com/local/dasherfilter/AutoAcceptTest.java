package com.local.dasherfilter;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AutoAcceptTest {
    private final OfferSnapshot offer = new OfferSnapshot(2000, 4.0, 20, 2);
    private final FilterSettings rules = FilterSettings.of(true, 1000, 100, 20, 4);
    /** The starter minimums: $4.00, $1.00 a mile, 25¢ a minute ($15 an hour). */
    private static final FilterSettings STARTERS = FilterSettings.of(true, 400, 100, 25, 0);

    @Test public void optInCompleteStandaloneAndKeepAreAllRequired() {
        assertTrue(AutoAccept.eligible(offer, rules, true, false, false, 30));
        assertFalse(AutoAccept.eligible(offer, rules, false, false, false, 30));
        assertFalse(AutoAccept.eligible(offer, rules.withEnabled(false), true, false, false, 30));
        assertFalse(AutoAccept.eligible(offer, rules, true, true, false, 30));
        assertFalse(AutoAccept.eligible(offer, rules, true, false, true, 30));
        assertFalse(AutoAccept.eligible(offer, rules, true, false, false, -1));
        assertFalse(AutoAccept.eligible(offer, rules, true, false, false, 3));
        assertFalse(AutoAccept.eligible(offer, FilterSettings.of(true, 0, 0, 0, 0), true, false, false, 30));
        assertFalse(AutoAccept.eligible(new OfferSnapshot(2000, null, 20, 2), rules, true, false, false, 30));
        assertFalse(AutoAccept.eligible(new OfferSnapshot(2000, 4.0, null, 2), rules, true, false, false, 30));
        assertFalse(AutoAccept.eligible(new OfferSnapshot(2000, 4.0, 20, null), rules, true, false, false, 30));
        assertFalse(AutoAccept.eligible(new OfferSnapshot(2000, 4.0, 20, 1), rules, true, false, false, 30));
        assertFalse(AutoAccept.eligible(new OfferSnapshot(null, 4.0, 20, 2, 2500), rules, true, false, false, 30));
        assertFalse(AutoAccept.eligible(new OfferSnapshot(2000, 4.0, 20, 5), rules, true, false, false, 30));
        assertFalse(AutoAccept.eligible(offer, rules.withMinimums(new int[]{3000, 0, 0, 0}), true, false, false, 30));
    }

    @Test public void maxStopsAloneIsNeverEnoughToAccept() {
        // The $2 / 15 mi probe: nothing but a max stops limit would have let it be accepted.
        FilterSettings stopsOnly = FilterSettings.of(true, 0, 0, 0, 3);
        OfferSnapshot probe = new OfferSnapshot(200, 15.0, 40, 2);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(probe, stopsOnly).result);
        assertFalse(AutoAccept.eligible(probe, stopsOnly, true, false, false, 30));
        assertTrue(AutoAccept.eligible(new OfferSnapshot(1600, 15.0, 40, 2), stopsOnly.withMinimums(400, 100, 0),
                true, false, false, 30));
    }

    @Test public void aDeclaredShoppingOfferNeedsItsItemCountRead() {
        assertFalse(AutoAccept.eligible(offer.withItems(null, true), rules, true, false, false, 30));
        assertTrue(AutoAccept.eligible(offer.withItems(12, true), rules, true, false, false, 30));
    }

    @Test public void aPassBelowTheMinimumsIsKeptForTheUserButNeverAccepted() {
        // $5.75 / 6.6 mi / 27 min scores 85: kept at an 82% bar, but it misses 100% of the minimums.
        OfferSnapshot score85 = new OfferSnapshot(575, 6.6, 27, 2);
        FilterSettings at82 = STARTERS.withMinimumScalePercent(82);
        OfferRule.Decision decision = OfferRule.evaluate(score85, at82);
        assertEquals(85, decision.scorePercent);
        assertEquals(OfferRule.Result.KEEP, decision.result);
        assertTrue(decision.belowMinimums);
        assertFalse(AutoAccept.eligible(score85, at82, true, false, false, 30));
        // What it would take: 100% of the minimums, $6.75.
        assertTrue(AutoAccept.eligible(new OfferSnapshot(675, 6.6, 27, 2), at82, true, false, false, 30));
        assertEquals(100, AutoAccept.acceptRules(at82).minimumScalePercent);
    }

    @Test public void aBarAboveOneHundredRaisesWhatAnAcceptNeedsToo() {
        FilterSettings at120 = STARTERS.withMinimumScalePercent(120);
        // 675 × 1.20 = $8.10: a score of 119 is not enough, 120 is.
        OfferSnapshot score119 = new OfferSnapshot(809, 6.6, 27, 2);
        OfferSnapshot score120 = new OfferSnapshot(810, 6.6, 27, 2);
        assertEquals(119, AreaScore.scorePercent(STARTERS, score119));
        assertEquals(120, AreaScore.scorePercent(STARTERS, score120));
        assertFalse(AutoAccept.eligible(score119, at120, true, false, false, 30));
        assertTrue(AutoAccept.eligible(score120, at120, true, false, false, 30));
        assertEquals(120, AutoAccept.acceptRules(at120).minimumScalePercent);
        assertEquals(100, AutoAccept.acceptRules(STARTERS).minimumScalePercent);
        assertEquals(STARTERS.rulesKey(), AutoAccept.acceptRules(STARTERS.withMinimumScalePercent(50)).rulesKey());
    }

    @Test public void theRulesKeyIsTheMinimumsMaxStopsSwitchAndBar() {
        assertEquals("true:1000:100:20:4:100", AutoAccept.rulesKey(rules));
        assertNotEquals(AutoAccept.rulesKey(rules), AutoAccept.rulesKey(rules.withMinimumScalePercent(99)));
        assertNotEquals(AutoAccept.rulesKey(rules), AutoAccept.rulesKey(rules.withMinimums(1001, 100, 20)));
        assertNotEquals(AutoAccept.rulesKey(rules), AutoAccept.rulesKey(rules.withMinimums(1000, 101, 20)));
        assertNotEquals(AutoAccept.rulesKey(rules), AutoAccept.rulesKey(rules.withMinimums(1000, 100, 21)));
        assertNotEquals(AutoAccept.rulesKey(rules), AutoAccept.rulesKey(rules.withMaxStops(3)));
        assertNotEquals(AutoAccept.rulesKey(rules), AutoAccept.rulesKey(rules.withEnabled(false)));
        assertEquals("true:400:100:25:0:82", AutoAccept.rulesKey(new FilterSettings(true, 400, 100, 25, 0, true, 70,
                82)));
    }

    @Test public void secondCompleteContinuingReadAndQuietIntervalAreRequired() {
        AutoAccept a = new AutoAccept();
        assertEquals(AutoAccept.State.WAIT, a.observe(offer, "same", rules, 30, 1, 1000, false));
        assertEquals(AutoAccept.State.WAIT, a.observe(offer, "same", rules, 30, 1, 1699, false));
        assertEquals(AutoAccept.State.READY, a.observe(offer, "same", rules, 29, 1, 1700, false));
        assertTrue(a.current(offer, "same", rules, 1, 1700));
        assertFalse(a.current(offer, "changed", rules, 1, 1700));
        assertFalse(a.current(offer, "same", rules, 2, 1700));
        assertFalse(a.current(offer, "same", rules.withMinimumScalePercent(99), 1, 1700));
    }

    @Test public void changedLabelsAndNotificationsRestartQuietButChangedRulesCancelOffer() {
        AutoAccept a = new AutoAccept();
        a.observe(offer, "same", rules, 30, 1, 1000, false);
        assertEquals(AutoAccept.State.WAIT, a.observe(offer, "changed", rules, 29, 1, 1800, false));
        assertEquals(AutoAccept.State.WAIT, a.observe(offer, "changed", rules, 29, 2, 2600, false));
        assertEquals(AutoAccept.State.BLOCKED, a.observe(offer, "changed", rules.withMinimums(1050, 100, 20), 28, 2,
                3400, false));
    }

    @Test public void aChangedBarAloneCancelsTheCandidate() {
        AutoAccept a = new AutoAccept();
        a.observe(offer, "same", rules, 30, 1, 1000, false);
        assertEquals(AutoAccept.State.BLOCKED, a.observe(offer, "same", rules.withMinimumScalePercent(105), 29, 1,
                1800, false));
    }

    @Test public void deadlineAndUnexpectedCountdownNeverExtendAuthority() {
        AutoAccept a = new AutoAccept();
        a.observe(offer, "same", rules, 5, 1, 1000, false);
        assertEquals(AutoAccept.State.BLOCKED, a.observe(offer, "same", rules, 4, 1, 3000, false));
        a.clear(); a.observe(offer, "same", rules, 10, 1, 1000, false);
        assertEquals(AutoAccept.State.BLOCKED, a.observe(offer, "same", rules, 30, 1, 1800, false));
    }

    @Test public void oneRequestBudgetAlsoCoversPartialFramesAndOnlyNewInstanceClearsIt() {
        AutoAccept a = new AutoAccept();
        a.observe(offer, "same", rules, 30, 1, 1000, false); a.block(1800);
        assertTrue(a.blocks(offer, 2000));
        assertTrue(a.blocks(new OfferSnapshot(2000, null, null, null), 2000));
        assertEquals(AutoAccept.State.BLOCKED, a.observe(offer, "changed words", rules, 28, 2, 2800, false));
        assertEquals(AutoAccept.State.WAIT, a.observe(offer, "same", rules, 35, 2, 3000, true));
    }

    @Test public void contentRereadsKeepOriginalDeadlineAndHaveABoundedBudget() {
        AutoAccept a = new AutoAccept();
        a.observe(offer, "same", rules, 5, 1, 1000, false);
        assertTrue(a.rereadAfterContent(1800));
        assertEquals(AutoAccept.State.READY, a.observe(offer, "same", rules, 4, 1, 1950, false));
        assertTrue(a.rereadAfterContent(1950));
        assertEquals(AutoAccept.State.READY, a.observe(offer, "same", rules, 4, 1, 2100, false));
        assertFalse(a.rereadAfterContent(2100));
        assertFalse(a.current(offer, "same", rules, 1, 3000));
    }

    @Test public void contentRereadCannotInheritAuthorityForChangedIdentityRulesOrGeneration() {
        for (int change = 0; change < 6; change++) {
            AutoAccept a = new AutoAccept();
            OfferSnapshot original = offer.withItems(2, true);
            a.observe(original, "same", rules, 30, 1, 1000, false);
            assertTrue(a.rereadAfterContent(1800));
            assertEquals(AutoAccept.State.BLOCKED, a.observe(
                    change == 0 ? original.withItems(3, true) : change == 1 ? OfferSnapshot.UNKNOWN : original,
                    change == 2 ? "different" : "same", change == 3 ? rules.withMinimumScalePercent(90) : rules,
                    29, change == 4 ? 2 : 1, 1950, change == 5));
            assertFalse(a.rereadAfterContent(2000));
            assertFalse(a.current(original, "same", rules, 1, 2000));
        }
    }

    @Test public void contentRereadCannotRestartExpiredOrCanceledCandidate() {
        AutoAccept a = new AutoAccept();
        a.observe(offer, "same", rules, 5, 1, 1000, false);
        assertFalse(a.rereadAfterContent(3000));
        a.clear();
        a.observe(offer, "same", rules, 30, 1, 4000, false);
        assertTrue(a.rereadAfterContent(4800));
        a.block(4801);
        assertFalse(a.rereadAfterContent(4900));
        assertEquals(AutoAccept.State.BLOCKED, a.observe(offer, "same", rules, 29, 1, 5000, false));
    }
}
