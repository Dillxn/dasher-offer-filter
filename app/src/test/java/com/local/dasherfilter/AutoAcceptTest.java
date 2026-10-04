package com.local.dasherfilter;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AutoAcceptTest {
    private final OfferSnapshot offer = new OfferSnapshot(2000, 4.0, 20, 2);
    private final FilterSettings rules = new FilterSettings(true, 1000, 100, 20, 100, 4);
    @Test public void optInCompleteStandaloneAndKeepAreAllRequired() {
        assertTrue(AutoAccept.eligible(offer, rules, true, false, false, 30));
        assertFalse(AutoAccept.eligible(offer, rules, false, false, false, 30));
        assertFalse(AutoAccept.eligible(offer, rules.withEnabled(false), true, false, false, 30));
        assertFalse(AutoAccept.eligible(offer, rules, true, true, false, 30));
        assertFalse(AutoAccept.eligible(offer, rules, true, false, true, 30));
        assertFalse(AutoAccept.eligible(offer, rules, true, false, false, -1));
        assertFalse(AutoAccept.eligible(offer, rules, true, false, false, 3));
        assertFalse(AutoAccept.eligible(offer, new FilterSettings(true, 0, 0, 0, 0, 0), true, false, false, 30));
        assertFalse(AutoAccept.eligible(offer, new FilterSettings(true, 0, 0, 0, 0, 0, true, 0), true, false, false, 30));
        assertFalse(AutoAccept.eligible(offer, new FilterSettings(true, 0, 0, 0, 0, 0).withPerItem(100), true, false, false, 30));
        assertFalse(AutoAccept.eligible(new OfferSnapshot(2000, null, 20, 2), rules, true, false, false, 30));
        assertFalse(AutoAccept.eligible(new OfferSnapshot(null, 4.0, 20, 2, 2500), rules, true, false, false, 30));
        assertFalse(AutoAccept.eligible(offer.withItems(null, true), rules, true, false, false, 30));
        assertFalse(AutoAccept.eligible(offer, rules.withHotspotProximity(100), true, false, false, 30));
        assertFalse(AutoAccept.eligible(offer, rules.withMinimums(new int[]{3000, 0, 0, 0}), true, false, false, 30));
    }
    @Test public void effectiveLearnedScaledFloorsAndCompensatingAreaAreTheSameRules() {
        FilterSettings learned = new FilterSettings(true, 1000, 100, 0, 0, 0, true, 2050);
        assertFalse(AutoAccept.eligible(offer, learned, true, false, false, 30));
        assertTrue(AutoAccept.eligible(offer, learned.withMinimumScalePercent(97), true, false, false, 30));
        FilterSettings area = new FilterSettings(true, 3000, 100, 20, 100, 0).withScoreByArea(true);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(offer, area).result);
        assertTrue(AutoAccept.eligible(offer, area, true, false, false, 30));
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
        assertEquals(AutoAccept.State.BLOCKED, a.observe(offer, "changed", rules.withPerItem(50), 28, 2, 3400, false));
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
    @Test public void exactLearnedRatesArePartOfAuthority() {
        FilterSettings a = new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0,
                new AcceptedBest(2000, 20, 2000, 4.00001, 2000, 2));
        FilterSettings b = new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0,
                new AcceptedBest(2000, 20, 2000, 4.00002, 2000, 2));
        assertNotEquals(AutoAccept.rulesKey(a), AutoAccept.rulesKey(b));
    }

    @Test public void exactLearnedItemRatesArePartOfAuthorityEvenWhenBothOffersPass() {
        FilterSettings a = new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0,
                new AcceptedBest(0, 0, 0, 0, 0, 0, 100, 2));
        FilterSettings b = new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0,
                new AcceptedBest(0, 0, 0, 0, 0, 0, 101, 2));
        assertNotEquals(AutoAccept.rulesKey(a), AutoAccept.rulesKey(b));
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
                    change == 2 ? "different" : "same", change == 3 ? rules.withPerItem(10) : rules,
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
