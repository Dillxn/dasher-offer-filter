package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** 0.5.0 rules: per-hour, max miles, avoid list, Earn by Time, baseline expiry, decline limit, explanations (spec A2, B3-B9). */
public final class OfferRuleExtendedTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final EvaluationContext CTX = EvaluationContext.at(NOW);
    private static final FilterSettings NONE = new FilterSettings(true, 0, 0, 0, 0, 0);
    private static List<String> l(String... s) { return Arrays.asList(s); }
    private static OfferSnapshot o(Integer pay, Double miles, Integer minutes, Integer stops) { return new OfferSnapshot(pay, miles, minutes, stops); }
    private static OfferRule.Decision eval(OfferSnapshot offer, FilterSettings s) { return OfferRule.evaluate(offer, Collections.emptyList(), s, CTX); }
    private static OfferRule.Decision screen(FilterSettings s, String... labels) { return OfferRule.evaluate(OfferParser.parse(l(labels)), l(labels), s, CTX); }

    // B3: per-hour time rule.
    @Test public void perHourUsesCeilingAndEqualPasses() {
        FilterSettings s = NONE.withPerHourCents(2500);
        assertEquals(OfferRule.Result.KEEP, eval(o(875, null, 21, null), s).result);
        OfferRule.Decision d = eval(o(874, null, 21, null), s);
        assertEquals(OfferRule.Result.DECLINE, d.result); assertEquals(875L, d.requiredCents); assertEquals(OfferRule.Code.PER_HOUR, d.code);
        FilterSettings ten = NONE.withPerHourCents(1000);   // 7 min at $10/hr = 116.67 -> 117
        assertEquals(117L, eval(o(117, null, 7, null), ten).requiredCents);
        assertEquals(OfferRule.Result.KEEP, eval(o(117, null, 7, null), ten).result);
        assertEquals(OfferRule.Result.DECLINE, eval(o(116, null, 7, null), ten).result);
    }
    @Test public void timeRuleIsTheMaxOfPerMinuteAndPerHour() {
        FilterSettings s = NONE.withPerMinuteCents(30).withPerHourCents(2400);
        assertEquals(840L, eval(o(1000, null, 21, null), s).requiredCents);   // 21 × 40¢ > 21 × 30¢
        assertEquals(OfferRule.Code.PER_HOUR, eval(o(839, null, 21, null), s).code);
        assertEquals(OfferRule.Code.PER_MINUTE, eval(o(100, null, 21, null), NONE.withPerMinuteCents(50).withPerHourCents(600)).code);
    }
    @Test public void unknownMinutesWithEitherRateIsReviewUnlessAnotherRuleFails() {
        assertEquals(OfferRule.Code.MINUTES_MISSING, eval(o(5000, null, null, null), NONE.withPerHourCents(2500)).code);
        assertEquals(OfferRule.Result.REVIEW, eval(o(5000, null, null, null), NONE.withPerMinuteCents(30)).result);
        assertEquals(OfferRule.Result.DECLINE, eval(o(500, null, null, null), NONE.withPerHourCents(2500).withFlatCents(700)).result);
    }
    @Test public void migratedPerHourRateIsExactlyThePerMinuteRate() {
        for (int rate : new int[]{1, 7, 30, 42, 99, 1000}) for (int minutes = 0; minutes <= 240; minutes++) {
            long perMinute = eval(o(1, null, minutes, null), NONE.withPerMinuteCents(rate)).requiredCents;
            long perHour = eval(o(1, null, minutes, null), NONE.withPerHourCents(rate * 60)).requiredCents;
            assertEquals(rate + "@" + minutes, perMinute, perHour);
        }
    }
    @Test public void perHourCannotOverflow() {
        OfferRule.Decision d = eval(o(100, null, Integer.MAX_VALUE, null), NONE.withPerHourCents(Integer.MAX_VALUE));
        assertEquals(OfferRule.Result.DECLINE, d.result); assertTrue(d.requiredCents > 0);
    }

    // B4: max miles.
    @Test public void maxMilesEqualPassesOneHundredthOverFails() {
        FilterSettings s = NONE.withMaxMilesHundredths(600);
        assertEquals(OfferRule.Result.KEEP, eval(o(null, 6.0, null, null), s).result);
        assertEquals(OfferRule.Result.DECLINE, eval(o(null, 6.01, null, null), s).result);
        assertEquals("DECLINE: 7.2 mi exceeds your 6.0 mi maximum", eval(o(900, 7.2, null, null), s).summary());
        assertEquals(OfferRule.Code.MAX_MILES, eval(o(900, 7.2, null, null), s).code);
        assertEquals(OfferRule.Result.KEEP, eval(o(null, 0.3, null, null), NONE.withMaxMilesHundredths(30)).result);   // 0.1 + 0.2 style doubles compare exactly
    }
    @Test public void maxMilesUnknownIsReviewUnlessAnotherKnownFailure() {
        FilterSettings s = NONE.withMaxMilesHundredths(600);
        assertEquals(OfferRule.Code.MILES_MISSING, eval(o(900, null, null, null), s).code);
        assertEquals(OfferRule.Result.DECLINE, eval(o(500, null, null, null), s.withFlatCents(700)).result);
        assertEquals(OfferRule.Result.DECLINE, eval(o(null, null, null, 5), s.withMaxStops(3)).result);
    }
    @Test public void addOnMaxMilesOnlyOnExplicitRouteTotal() {
        FilterSettings s = NONE.withMaxMilesHundredths(600);
        OfferSnapshot route = o(2500, 5.0, null, 2);
        EvaluationContext addOn = CTX.withAddOn(route);
        List<String> derived = l("Add to route", "+$5.00", "+2.2 mi", "Decline");
        OfferRule.Decision d = OfferRule.evaluate(OfferParser.parse(derived), derived, s, addOn);
        assertEquals("a derived sum never declines on max miles", OfferRule.Result.REVIEW, d.result);
        List<String> explicit = l("Add to route", "+$5.00", "Total distance: 7.2 mi", "Decline");
        d = OfferRule.evaluate(OfferParser.parse(explicit), explicit, s, addOn);
        assertEquals(OfferRule.Result.DECLINE, d.result); assertEquals(OfferRule.Code.MAX_MILES, d.code);
        List<String> after = l("Add to route", "+$5.00", "7.2 mi total", "Decline");
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(OfferParser.parse(after), after, s, addOn).result);
        List<String> within = l("Add to route", "+$5.00", "Total distance: 6.0 mi", "Decline");
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(OfferParser.parse(within), within, s, addOn).result);
        assertTrue(AddOnOffer.parse(route, null, explicit).explicitTotalMiles);
        assertFalse(AddOnOffer.parse(route, null, derived).explicitTotalMiles);
    }

    // B5: avoid list.
    @Test public void avoidedStoreDeclinesEvenWithUnknownPay() {
        FilterSettings s = NONE.withAvoidStores(l("Chick-fil-A"));
        OfferRule.Decision d = screen(s, "Chick-fil-A", "Accept", "Decline");
        assertEquals(OfferRule.Result.DECLINE, d.result); assertEquals(OfferRule.Code.AVOIDED_STORE, d.code);
        assertEquals("DECLINE: avoided store: chick fil a", d.summary()); assertEquals("chick fil a", d.avoidedStore);
        assertEquals(OfferRule.Result.DECLINE, screen(s.withFlatCents(500), "$25.00", "Pickup at CHICK-FIL-A (Main St)", "4 mi", "Accept", "Decline").result);
    }
    @Test public void storeRulesNeverCreateKeep() {
        FilterSettings s = NONE.withAvoidStores(l("Chick-fil-A"));
        OfferRule.Decision d = screen(s, "Taco Bell", "$25.00", "2 stops (1.2 mi) • 9 min", "Accept", "Decline");
        assertEquals(OfferRule.Result.REVIEW, d.result); assertEquals(OfferRule.Code.NO_PAY_RULE, d.code); assertEquals("no pay rule set", d.reason);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(o(9999, 1.0, 5, 1), l("New Order: Go to Taco Bell"), s, CTX.withMerchant("Taco Bell")).result);
        assertEquals(OfferRule.Result.KEEP, screen(s.withFlatCents(500), "Taco Bell", "$25.00", "Accept", "Decline").result);
        assertEquals(OfferRule.Code.NO_PAY_RULE, eval(o(2500, 2.0, 10, 2), NONE).code);   // no rule at all is never a pass either
    }
    @Test public void storeMatchUsesWholePhrasesAndIgnoresControls() {
        FilterSettings s = NONE.withFlatCents(100).withAvoidStores(l("Taco", "Decline"));
        assertEquals(OfferRule.Result.KEEP, screen(s, "Tacos El Gordo", "$9.00", "Accept", "Decline").result);
        assertEquals(OfferRule.Result.DECLINE, screen(s, "Taco Shack", "$9.00", "Accept", "Decline").result);
        assertEquals(OfferRule.Result.KEEP, screen(s, "Burger Barn", "$9.00", "Accept", "Decline offer").result);
    }
    @Test public void notificationMerchantMatchesAndAddOnScreensUseOnlyTheMerchant() {
        FilterSettings s = NONE.withFlatCents(100).withAvoidStores(l("Mr. Pollo"));
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(o(null, 3.2, null, null), l("Flash offer available"), s, CTX.withMerchant("Mr. Pollo")).result);
        List<String> addOn = l("Mr Pollo", "Add to route", "+$5.00", "+1 mi", "Decline");
        OfferRule.Decision d = OfferRule.evaluate(OfferParser.parse(addOn), addOn, s, CTX.withAddOn(o(2500, 5.0, 30, 2)));
        assertNotEquals("the current route's store can appear on an add-on screen", OfferRule.Code.AVOIDED_STORE, d.code);
        d = OfferRule.evaluate(OfferParser.parse(addOn), addOn, s, CTX.withAddOn(o(2500, 5.0, 30, 2)).withMerchant("Mr. Pollo"));
        assertEquals(OfferRule.Code.AVOIDED_STORE, d.code); assertEquals(OfferRule.Result.DECLINE, d.result);
    }

    // A2: Earn by Time is always REVIEW.
    @Test public void earnByTimeIsReviewEvenWhenAnotherRuleWouldFail() {
        FilterSettings s = NONE.withFlatCents(2000).withMaxStops(1).withAvoidStores(l("Chick-fil-A"));
        for (List<String> labels : Arrays.asList(
                l("$15/active hr + tips", "From when you accept to when you complete this offer", "Chick-fil-A", "4.1 mi", "3 stops", "Accept", "Decline"),
                l("$15", "/active hr", "+ tips", "3 stops", "Accept", "Decline"),
                l("Earn by Time", "$12.00", "3 stops", "Accept", "Decline"))) {
            OfferRule.Decision d = OfferRule.evaluate(OfferParser.parse(labels), labels, s, CTX);
            assertEquals(labels.toString(), OfferRule.Result.REVIEW, d.result);
            assertEquals(OfferRule.Code.HOURLY_MODE, d.code);
            assertEquals("Earn by Time offer — auto-decline disabled (DoorDash ends the dash after more than one decline per hour)", d.reason);
        }
        assertTrue(OfferParser.isHourlyMode(l("$18", "per active hour")));
        assertFalse(OfferParser.isHourlyMode(l("$18.00", "4 mi", "Deliver by 7:45 PM")));
    }

    // B6: rising baseline expiry.
    @Test public void risingBaselineAppliesOnlyWithinEightHoursAndNotFarInTheFuture() {
        FilterSettings base = NONE.withFlatCents(1000).withRisingOffers(true);
        long hour = 3_600_000L, minute = 60_000L;
        assertEquals(2501L, eval(o(2000, null, null, null), base.withLastAccepted(2500, NOW - 8 * hour)).requiredCents);
        assertEquals(1000L, eval(o(2000, null, null, null), base.withLastAccepted(2500, NOW - 8 * hour - 1)).requiredCents);
        assertEquals(2501L, eval(o(2000, null, null, null), base.withLastAccepted(2500, NOW + 5 * minute)).requiredCents);
        assertEquals(1000L, eval(o(2000, null, null, null), base.withLastAccepted(2500, NOW + 5 * minute + 1)).requiredCents);
        OfferRule.Decision legacy = eval(o(2000, null, null, null), new FilterSettings(true, 1000, 0, 0, 0, 0, true, 2500));
        assertEquals("an undated legacy baseline no longer applies", OfferRule.Result.KEEP, legacy.result);
        assertTrue(legacy.explanation().contains("not applied"));
        OfferRule.Decision fresh = eval(o(2500, null, null, null), base.withLastAccepted(2500, NOW - hour));
        assertEquals(OfferRule.Code.RISING, fresh.code); assertTrue(fresh.breakdown.contains("Must beat last accepted $25.00"));
    }

    // B7: opt-in decline limit.
    @Test public void declineLimitTurnsOnlyWouldBeDeclinesIntoReview() {
        FilterSettings s = NONE.withFlatCents(2000).withMaxDeclinesPerHour(3);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(o(1000, null, null, null), null, s, CTX.withDeclinesInLastHour(2)).result);
        OfferRule.Decision d = OfferRule.evaluate(o(1000, null, null, null), null, s, CTX.withDeclinesInLastHour(3));
        assertEquals(OfferRule.Result.REVIEW, d.result); assertEquals(OfferRule.Code.DECLINE_LIMIT, d.code);
        assertEquals("REVIEW: decline limit reached (3 per hour)", d.summary());
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(o(2500, null, null, null), null, s, CTX.withDeclinesInLastHour(99)).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(o(1000, null, null, null), null, s.withMaxDeclinesPerHour(0), CTX.withDeclinesInLastHour(99)).result);
        List<String> addOn = l("Add to route", "+$1.00", "+4 mi", "Decline");
        FilterSettings mile = NONE.withPerMileCents(150).withMaxDeclinesPerHour(1);
        assertEquals(OfferRule.Code.DECLINE_LIMIT, OfferRule.evaluate(OfferParser.parse(addOn), addOn, mile, CTX.withAddOn(o(2500, 10.0, null, 2)).withDeclinesInLastHour(1)).code);
    }
    @Test public void budgetCountsDistinctOffersAndNeverBlocksTheCurrentOfferItself() {
        DeclineBudget b = new DeclineBudget();
        b.record("a", 0); b.record("a", 100); b.record("b", 200);
        assertEquals(2, b.count(300)); assertEquals(1, b.count(300, "b"));
        FilterSettings s = NONE.withFlatCents(2000).withMaxDeclinesPerHour(2);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(o(1000, null, null, null), null, s, CTX.withBudget(b, 300, "b")).result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(o(1000, null, null, null), null, s, CTX.withBudget(b, 300, "c")).result);
    }

    // B8: explanation and codes.
    @Test public void breakdownExplainsEveryEnabledRule() {
        FilterSettings s = NONE.withFlatCents(700).withPerMileCents(150).withPerHourCents(2500).withExtraStopCents(200).withMaxMilesHundredths(1000).withMaxStops(3);
        OfferRule.Decision d = eval(o(1300, 7.2, 21, 3), s);
        assertEquals(OfferRule.Result.KEEP, d.result); assertEquals(1280L, d.requiredCents); assertEquals(OfferRule.Code.MEETS, d.code);
        for (String line : new String[]{"Flat minimum $7.00", "7.2 mi × $1.50/mi = $10.80", "21 min at $25.00/hr = $8.75", "+ $2.00 for 1 extra stop", "Max 10.0 mi: 7.2 mi OK", "Max 3 stops: 3 OK", "Pay $13.00 vs needed $12.80"})
            assertTrue(line + " in " + d.breakdown, d.breakdown.contains(line));
        assertEquals("KEEP: required at least $12.80 (meets enabled rules)", d.summary());
        assertEquals("DECLINE: required at least $12.80 (dollars per mile and extra stops)", eval(o(1279, 7.2, 21, 3), s).summary());
    }
    @Test public void failureCodesNameTheBindingRule() {
        FilterSettings s = NONE.withFlatCents(700).withExtraStopCents(200);
        assertEquals(OfferRule.Code.EXTRA_STOPS, eval(o(800, null, null, 3), s).code);   // passes the floor, fails only because of the fee
        assertEquals(OfferRule.Code.FLOOR, eval(o(600, null, null, 3), s).code);
        assertEquals(OfferRule.Code.PER_MILE, eval(o(900, 7.2, null, null), NONE.withFlatCents(700).withPerMileCents(150)).code);
        assertEquals(OfferRule.Code.MAX_STOPS, eval(o(9000, null, null, 4), NONE.withMaxStops(3)).code);
        assertEquals(OfferRule.Code.PAY_MISSING, eval(o(null, 2.0, null, null), NONE.withFlatCents(700).withPerMileCents(150)).code);
        OfferRule.Decision several = eval(o(null, null, null, null), NONE.withFlatCents(700).withPerMileCents(150).withPerHourCents(2000));
        assertEquals("pay, miles and minutes not shown", several.reason);
    }
    @Test public void legacyEntryPointsMatchTheNewOnesForLegacySettings() {
        int[][] rules = {{2000, 0, 0, 0, 0}, {600, 150, 30, 100, 0}, {0, 0, 0, 0, 3}, {2200, 150, 30, 100, 2}, {0, 100, 0, 200, 4}};
        Integer[] pays = {null, 0, 599, 600, 2200, 9999};
        Double[] miles = {null, 0.0, 1.1, 4.2, 12.0};
        Integer[] minutes = {null, 0, 21, 60};
        Integer[] stops = {null, 1, 2, 3, 5};
        for (int[] r : rules) for (Integer p : pays) for (Double m : miles) for (Integer t : minutes) for (Integer st : stops) {
            FilterSettings s = new FilterSettings(true, r[0], r[1], r[2], r[3], r[4]);
            OfferRule.Decision a = OfferRule.evaluate(o(p, m, t, st), s), b = OfferRule.evaluate(o(p, m, t, st), null, s, CTX);
            assertEquals(a.result, b.result); assertEquals(a.requiredCents, b.requiredCents); assertEquals(a.code, b.code);
        }
    }
    @Test public void pausedDecisionIsQuietReview() {
        OfferRule.Decision d = OfferRule.Decision.paused();
        assertEquals(OfferRule.Result.REVIEW, d.result); assertEquals(OfferRule.Code.PAUSED, d.code); assertEquals("REVIEW: Auto-decline is off; inspect this offer manually.", d.summary());
        assertEquals(OfferRule.Code.UNSPECIFIED, new OfferRule.Decision(OfferRule.Result.REVIEW, 0, "legacy").code);
    }

    // B9 / A3 consequences: add-on evidence always uses the add-on path, and the add-on card is not a confirmation.
    @Test public void addOnEvidenceNeverFallsIntoTheStandaloneFlatMinimum() {
        FilterSettings s = NONE.withFlatCents(2000);
        List<String> card = l("Add to route 77", "+$5.00", "+1.2 mi", "Decline");
        OfferRule.Decision d = OfferRule.evaluate(OfferParser.parse(card), card, s, CTX);   // no route context
        assertEquals(OfferRule.Result.REVIEW, d.result); assertEquals(OfferRule.Code.ADDON_AMBIGUOUS, d.code);
        // With route context the $20 floor is held to the route, not the $5 increment. The route total ($25 + $5) is derived
        // from the stored route, so it is REVIEW (third review: derived totals never decide), never the standalone DECLINE.
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(OfferParser.parse(card), card, s, CTX.withAddOn(o(2500, 5.0, null, 2))).result);
        List<String> shown = l("Add to route 77", "+$5.00", "+1.2 mi", "New total $30.00", "Decline");
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(OfferParser.parse(shown), shown, s, CTX.withAddOn(o(2500, 5.0, null, 2))).result);
        boolean hasAccept = false; for (String label : card) hasAccept |= OfferControls.isButton(label, "accept");
        assertTrue(hasAccept); assertFalse(DeclineConfirmation.isSurface(card, hasAccept));
        List<String> noPlus = l("New total $30.00", "Add to your route", "Decline");
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(OfferParser.parse(noPlus), noPlus, s, CTX).result);
    }
    @Test public void unreadableLabelsAreReview() {
        OfferRule.Decision d = OfferRule.evaluate(o(100, null, null, null), Collections.nCopies(300, "x"), NONE.withFlatCents(2000), CTX);
        assertEquals(OfferRule.Result.REVIEW, d.result); assertEquals(OfferRule.Code.UNREADABLE, d.code);
    }
}
