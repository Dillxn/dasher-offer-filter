package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Third-round core safety review. Every case here failed on the second-round 0.5.0 core diff: an hours-only relative time
 * became the trip duration, a one-fact match bound confirmation authority to a different offer, an hourly-rate card was
 * declined by a non-pay rule, derived add-on route totals decided declines, a rising rule with an inactive baseline stopped
 * requiring pay, and rate amounts in split or later-on-the-line rate context became exact pay.
 */
public final class CoreSafetyReviewTest {
    private static final EvaluationContext CTX = EvaluationContext.at(1_700_000_000_000L);
    private static final FilterSettings NONE = new FilterSettings(true, 0, 0, 0, 0, 0);
    private static List<String> l(String... s) { return Arrays.asList(s); }
    private static OfferSnapshot p(String... labels) { return OfferParser.parse(l(labels)); }
    private static OfferRule.Decision screen(FilterSettings s, List<String> labels) { return OfferRule.evaluate(OfferParser.parse(labels), labels, s, CTX); }

    // ---- Issue 1: hours-only relative times, store hours, and glued hour tokens are never the trip duration.
    @Test public void hoursOnlyRelativeTimesAndStoreHoursAreNeverTheTripDuration() {
        List<List<String>> cards = Arrays.asList(
                l("$12.00", "Guaranteed", "4.2 mi • Ready for pickup in 1 hour", "Accept", "Decline"),
                l("$12.00", "Guaranteed", "4.2 mi • Pickup in 1 hr", "Accept", "Decline"),
                l("$12.00", "Guaranteed", "4.2 mi", "Est. pickup in 1 hr", "Accept", "Decline"),
                l("$12.00", "Guaranteed", "CVS 24h • 4.2 mi", "Accept", "Decline"),
                l("$12.00", "Guaranteed", "Open 24 hours • 4.2 mi", "Accept", "Decline"),
                l("$12.00", "Guaranteed", "4.2 mi • ETA 1 hr", "Accept", "Decline"),
                l("$12.00", "Guaranteed", "4.2 mi • in 1 hr", "Accept", "Decline"),
                l("$12.00", "Guaranteed", "4.2 mi • closes in 2 hours", "Accept", "Decline"),
                l("$12.00", "Guaranteed", "4.2 mi • 12 hrs", "Accept", "Decline"),
                l("$12.00", "Guaranteed", "4.2 mi • Ready by 1 hr", "Accept", "Decline"));
        FilterSettings perHour30 = NONE.withPerHourCents(3000);
        for (List<String> card : cards) {
            assertNull(card.toString(), OfferParser.parse(card).minutes);
            OfferRule.Decision d = screen(perHour30, card);
            assertEquals(card.toString(), OfferRule.Result.REVIEW, d.result);
            assertEquals(card.toString(), OfferRule.Code.MINUTES_MISSING, d.code);
        }
        // The minutes form was already stripped; the hours forms now behave the same.
        assertNull(p("$12.00", "Guaranteed", "4.2 mi • Ready for pickup in 65 min").minutes);
        assertNull(p("$12.00", "4.2 mi • Ready for pickup in 1 hr 5 min").minutes);
        // A relative clause is removed, so an explicit trip duration beside it still reads.
        assertEquals(Integer.valueOf(21), p("$12.00", "4.2 mi • Ready for pickup in 1 hour • 21 min").minutes);
    }
    @Test public void aWholeHourDurationElementStillReads() {
        assertEquals(Integer.valueOf(60), p("$20.00", "Estimated 1 hr").minutes);
        assertEquals(Integer.valueOf(60), p("$20.00", "Est. 1 hr").minutes);
        assertEquals(Integer.valueOf(60), p("$20.00", "Estimated time: 1 hour").minutes);
        assertEquals(Integer.valueOf(120), p("$20.00", "3 stops • 2 hrs").minutes);
        assertEquals(Integer.valueOf(60), p("$20.00", "4.2 mi • 1 hr").minutes);
        assertEquals(Integer.valueOf(60), p("$20.00", "4.2 mi · 1 hr · 2 stops").minutes);
        assertEquals(Integer.valueOf(65), p("$20.00", "4.2 mi • 1 hr 5 min").minutes);
        // Any other hour token on a metric line leaves the duration unknown (0.5.0 H1.2 guard).
        assertNull(p("$20.00", "4.2 mi • 1.5 hr • 20 min").minutes);
        assertNull(p("$20.00", "4.2 mi • 20 min • 2 hours").minutes);
        assertNull(p("$20.00", "CVS 24h • 4.2 mi • 20 min").minutes);
    }

    // ---- Issue 2: confirmation authority needs the candidate's facts to be a subset of the declined offer's, with pay or
    // at least two facts shared. A candidate that shows a fact the declined offer lacked is a different offer.
    @Test public void oneSharedLowEntropyFactNeverBindsAuthorityToADifferentOffer() {
        FilterSettings s = NONE.withFlatCents(700).withAvoidStores(l("Chipotle"));
        List<String> a = l("$7.50+", "2 stops", "Pickup", "Chipotle", "Accept", "Decline");
        OfferSnapshot oa = OfferParser.parse(a);
        assertEquals(OfferRule.Result.DECLINE, screen(s, a).result);
        DeclineState st = new DeclineState();
        st.declineSent(DeclineState.offerKey(oa, a), oa, 1000);
        List<String> b = l("$25.00", "Guaranteed", "2 stops • 4.1 mi", "Pickup", "Wendy's", "Accept", "Decline offer", "Go back");
        OfferSnapshot ob = OfferParser.parse(b);
        assertEquals(OfferRule.Result.KEEP, screen(s, b).result);
        assertFalse("B shows pay and miles the declined offer never showed", st.isDeclinedOffer(ob));
        assertEquals(-1, DeclineConfirmation.select(b, l("Decline offer"), true, st.isDeclinedOffer(ob)));
        assertTrue("a different readable offer revokes the pending authority", st.offerObserved(ob, 2000));
        assertFalse(st.hasPendingConfirmation(2000));
    }
    @Test public void declinedOfferIdentityRules() {
        OfferSnapshot a = new OfferSnapshot(790, 7.2, 21, 2);
        DeclineState st = new DeclineState();
        st.declineSent("a", a, 1000);
        assertTrue("same offer", st.isDeclinedOffer(new OfferSnapshot(790, 7.2, 21, 2)));
        assertTrue("partial frame with the pay", st.isDeclinedOffer(new OfferSnapshot(790, null, null, null)));
        assertTrue("partial frame with two travel facts", st.isDeclinedOffer(new OfferSnapshot(null, 7.2, null, 2)));
        assertFalse("one travel fact is not proof", st.isDeclinedOffer(new OfferSnapshot(null, null, null, 2)));
        assertFalse("one travel fact is not proof", st.isDeclinedOffer(new OfferSnapshot(null, 7.2, null, null)));
        assertFalse("any conflict", st.isDeclinedOffer(new OfferSnapshot(790, 7.3, 21, 2)));
        assertFalse(st.isDeclinedOffer(new OfferSnapshot(null, null, null, null)));

        OfferSnapshot partial = new OfferSnapshot(null, null, null, 2);   // pay unreadable, only stops known
        st.declineSent("p", partial, 5000);
        assertFalse("the candidate shows pay the declined offer lacked", st.isDeclinedOffer(new OfferSnapshot(2500, null, null, 2)));
        assertFalse("the candidate shows miles the declined offer lacked", st.isDeclinedOffer(new OfferSnapshot(null, 4.1, null, 2)));
        assertFalse("a single shared stop count is never proof", st.isDeclinedOffer(new OfferSnapshot(null, null, null, 2)));
        assertTrue("so it revokes", st.offerObserved(new OfferSnapshot(null, 4.1, null, 2), 5100));
    }
    @Test public void sheetOverTheDeclinedOfferStillConfirms() {
        List<String> a = l("$7.90", "2 stops (7.2 mi) • 21 min", "Accept", "Decline");
        OfferSnapshot oa = OfferParser.parse(a);
        DeclineState st = new DeclineState();
        st.declineSent(DeclineState.offerKey(oa, a), oa, 1000);
        List<String> overlay = l("$7.90", "2 stops (7.2 mi) • 21 min", "Accept", "Decline", "Cancel", "Decline offer");
        boolean same = st.isDeclinedOffer(OfferParser.parse(overlay));
        assertTrue(same);
        assertEquals(1, DeclineConfirmation.select(overlay, l("Decline", "Decline offer"), true, same));
        assertFalse(st.offerObserved(OfferParser.parse(overlay), 1100));
    }

    // Found alongside issue 2 (reviewer probe F6): DoorDash's Flash offer card carries informational acceptance-rate text.
    // A fresh offer with a recognized Accept, offer evidence and such text is never a confirmation surface.
    @Test public void informationalAcceptanceRateTextOnAFreshOfferIsNotAConfirmationPrompt() {
        for (List<String> fresh : Arrays.asList(
                l("$28.24", "Guaranteed", "3.2 mi", "Your acceptance rate will not be affected", "Accept", "Decline"),
                l("$28.24", "Guaranteed", "3.2 mi", "Declining will not affect your acceptance rate", "Accept", "Decline"),
                l("$28.24", "Guaranteed", "3.2 mi", "Declining this offer won't affect your acceptance rate", "Accept", "Decline"),
                l("$28.24", "Guaranteed", "3.2 mi", "Declining this offer does not affect your acceptance rate", "Accept", "Decline"))) {
            assertFalse(fresh.toString(), DeclineConfirmation.isSurface(fresh, true));
            assertEquals(fresh.toString(), -1, DeclineConfirmation.select(fresh, l("Decline"), true, false));
        }
        // Real prompts still qualify (no Accept on the sheet, or the sheet proven to show the declined offer).
        assertTrue(DeclineConfirmation.isSurface(l("Are you sure you want to decline this order? You are the best dasher for this order.", "Decline", "Go Back"), false));
        assertTrue(DeclineConfirmation.isSurface(l("Declining may lower your acceptance rate. Your acceptance rate may drop", "Decline"), true));
        assertTrue(DeclineConfirmation.isSurface(l("$12.00", "Are you sure you want to decline?", "Decline"), false));
        List<String> sheetOverA = l("$7.90", "2 stops (7.2 mi) • 21 min", "Accept", "Decline", "Are you sure?", "Cancel", "Decline offer");
        assertTrue(DeclineConfirmation.isSurface(sheetOverA, true, true));
        assertFalse("unproven: a fresh card with a prompt-like line and its own Accept", DeclineConfirmation.isSurface(sheetOverA, true, false));
    }

    // ---- Issue 3: a card whose only money is an hourly rate is Earn by Time; no rule may decline it.
    @Test public void hourlyRateOnlyCardIsEarnByTimeReview() {
        FilterSettings maxMiles1 = NONE.withMaxMilesHundredths(100);
        FilterSettings avoid = NONE.withFlatCents(700).withAvoidStores(l("Chipotle"));
        FilterSettings stops1 = NONE.withMaxStops(1);
        List<List<String>> cards = Arrays.asList(
                l("$15/hr + tips", "1.6 mi · 15 min", "2 stops", "Accept", "Decline"),
                l("$15 per hour + tips", "Restaurant pickup", "Chipotle", "1.6 mi", "2 stops", "Accept", "Decline"),
                l("$15", "/hr", "+ tips", "Chipotle", "1.6 mi", "2 stops", "Accept", "Decline"),
                l("$25 an hour", "Chipotle", "1.6 mi • 2 stops", "Accept", "Decline"),
                l("$18.00 hourly", "Chipotle", "1.6 mi • 2 stops", "Accept", "Decline"),
                l("$25", "/hr • 4.1 mi", "2 stops", "Chipotle", "Accept", "Decline"));
        for (List<String> card : cards) {
            assertTrue(card.toString(), OfferParser.isHourlyMode(card));
            OfferSnapshot o = OfferParser.parse(card);
            assertTrue(card.toString(), o.hourly); assertNull(card.toString(), o.payCents);
            for (FilterSettings s : new FilterSettings[]{maxMiles1, avoid, stops1}) {
                for (OfferRule.Decision d : new OfferRule.Decision[]{screen(s, card), OfferRule.evaluate(o, s), OfferRule.evaluate(o, null, s, CTX)}) {
                    assertEquals(card.toString(), OfferRule.Result.REVIEW, d.result);
                    assertEquals(card.toString(), OfferRule.Code.HOURLY_MODE, d.code);
                }
            }
        }
        // Background notification and add-on shapes.
        assertTrue(OfferParser.isHourlyMode(l("New order", "$15/hr + tips", "1.6 mi")));
        List<String> addOn = l("Add to route", "$15/hr", "+1 stop", "Decline");
        assertEquals(OfferRule.Code.HOURLY_MODE, OfferRule.evaluate(OfferParser.parse(addOn), addOn, stops1, CTX.withAddOn(new OfferSnapshot(2500, 5.0, 30, 1))).code);
    }
    @Test public void perOfferPayBesideARateOrAMileRateAloneIsNotHourlyMode() {
        assertFalse(OfferParser.isHourlyMode(l("$9.50", "Guaranteed", "Est. $25/hr", "4.2 mi")));
        assertEquals(Integer.valueOf(950), p("$9.50", "Guaranteed", "Est. $25/hr", "4.2 mi").payCents);
        assertFalse(OfferParser.isHourlyMode(l("$1.50/mi", "4.2 mi")));
        assertFalse(OfferParser.isHourlyMode(l("$9.50 Guaranteed · Deliver within an hour", "4.2 mi")));
        assertEquals(Integer.valueOf(950), p("$9.50 Guaranteed · Deliver within an hour", "4.2 mi").payCents);
        assertFalse(OfferParser.isHourlyMode(l("$8.50", "Guaranteed", "4.2 mi • 21 min", "Accept", "Decline")));
        assertFalse(OfferParser.isHourlyMode(l("New message from customer", "Can you grab extra sauce?")));
    }

    // ---- Issue 4: derived add-on route totals (stored route + increment) never decide; only displayed totals do.
    @Test public void derivedAddOnRouteTotalsNeverDecideADecline() {
        OfferSnapshot route = new OfferSnapshot(500, 5.0, null, 1);
        List<String> card = l("+$3.00", "Guaranteed", "+1.0 mi", "Add to route", "Decline");
        FilterSettings pm = NONE.withPerMileCents(200).withMaxMilesHundredths(1000);
        OfferRule.Decision d = OfferRule.evaluate(OfferParser.parse(card), card, pm, CTX.withAddOn(route));
        assertEquals(d.breakdown.toString(), OfferRule.Result.REVIEW, d.result);
        assertEquals(OfferRule.Code.ADDON_AMBIGUOUS, d.code);
        String why = d.breakdown.toString();
        assertFalse(why, why.contains("6.0 mi × $2.00/mi"));
        assertTrue(why, why.contains("derived 6.0 mi is not used"));
        assertTrue(why, why.contains("+1.0 mi × $2.00/mi = $2.00"));

        List<String> research = l("+$7.75", "Guaranteed", "Additional 2.8 mi", "Deliver by 8:35 PM", "Pickup", "CAFFE:IN", "Add to route", "77", "Decline");
        OfferRule.Decision r = OfferRule.evaluate(OfferParser.parse(research), research, NONE.withPerMileCents(200), CTX.withAddOn(route));
        assertNotEquals(r.summary(), OfferRule.Result.DECLINE, r.result);

        List<String> flat = l("Add to route", "+$2.00", "+1 mi", "Decline");
        OfferRule.Decision f = OfferRule.evaluate(OfferParser.parse(flat), flat, NONE.withFlatCents(700), CTX.withAddOn(new OfferSnapshot(300, 3.0, null, 1)));
        assertEquals(f.summary(), OfferRule.Result.REVIEW, f.result);
        assertTrue(f.breakdown.toString(), f.breakdown.toString().contains("Pay total not shown (derived $5.00 is not used)"));

        List<String> time = l("Add to route", "+$6.00", "+10 min", "Decline");
        OfferRule.Decision t = OfferRule.evaluate(OfferParser.parse(time), time, NONE.withPerHourCents(3000), CTX.withAddOn(new OfferSnapshot(500, null, 40, 1)));
        assertEquals(t.summary(), OfferRule.Result.REVIEW, t.result);   // derived $11.00 for 50 min is not used; +$6.00 covers +10 min at $30/hr
        assertTrue(t.breakdown.toString(), t.breakdown.toString().contains("derived 50 min is not used"));
    }
    @Test public void displayedAddOnRouteTotalsAndExplicitIncrementsStillDecide() {
        OfferSnapshot route = new OfferSnapshot(500, 5.0, null, 1);
        EvaluationContext ctx = CTX.withAddOn(route);
        List<String> totals = l("Add to route", "+$3.00", "+1.0 mi", "New total $8.00", "Total distance: 6.0 mi", "Decline");
        OfferRule.Decision d = OfferRule.evaluate(OfferParser.parse(totals), totals, NONE.withPerMileCents(200), ctx);
        assertEquals(d.breakdown.toString(), OfferRule.Result.DECLINE, d.result);
        assertEquals("DECLINE: required at least $12.00 (combined route fails: dollars per mile)", d.summary());
        List<String> newTotal = l("Add to route", "+$2.00", "New total $5.00", "Decline");
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(OfferParser.parse(newTotal), newTotal, NONE.withFlatCents(700), CTX.withAddOn(new OfferSnapshot(300, null, null, 1))).result);
        List<String> marginal = l("Add to route", "+$1.00", "+4 mi", "Decline");
        OfferRule.Decision m = OfferRule.evaluate(OfferParser.parse(marginal), marginal, NONE.withPerMileCents(150), ctx);
        assertEquals(OfferRule.Result.DECLINE, m.result); assertEquals("DECLINE: required at least $6.00 (add-on marginal economics)", m.summary());
        List<String> good = l("Add to route", "+$9.00", "+1 mi", "Total pay $14.00", "Total distance: 6.0 mi", "Decline");
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(OfferParser.parse(good), good, NONE.withPerMileCents(200), ctx).result);
        // Derived stops keep the 0.4.5 route ceiling (stored accepted stops + explicit "+N stops").
        List<String> stops = l("Add to route", "+$3.00", "+2 stops", "Decline");
        assertEquals(OfferRule.Code.MAX_STOPS, OfferRule.evaluate(OfferParser.parse(stops), stops, NONE.withMaxStops(3), CTX.withAddOn(new OfferSnapshot(2500, null, null, 2))).code);
    }

    // Found alongside issue 4 (reviewer probe F5): a "+$2.00 Peak Pay" node is a pay component, never the add-on's increment.
    @Test public void payComponentNodeIsNeverTheAddOnIncrement() {
        OfferSnapshot route = new OfferSnapshot(1000, 3.0, null, 2);
        List<String> peak = l("$7.75", "Guaranteed", "+$2.00 Peak Pay", "Additional 2.8 mi", "Add to route", "Decline");
        AddOnOffer a = AddOnOffer.parse(route, OfferParser.parse(peak), peak);
        assertFalse(a.explicitIncrementPay); assertNull(a.incremental.payCents);
        OfferRule.Decision d = OfferRule.evaluate(OfferParser.parse(peak), peak, NONE.withPerMileCents(100), CTX.withAddOn(route));
        assertEquals(d.summary(), OfferRule.Result.REVIEW, d.result);
        // The add-on's own "+$" still reads beside a component node, and "(incl. tips)" describes the increment itself.
        List<String> both = l("+$7.75", "Guaranteed", "+$2.00 Peak Pay", "Additional 2.8 mi", "Add to route", "Decline");
        assertEquals(Integer.valueOf(775), AddOnOffer.parse(route, null, both).incremental.payCents);
        List<String> tips = l("+$5.00 Guaranteed (incl. tips)", "+2 mi", "Add to route", "Decline");
        assertEquals(Integer.valueOf(500), AddOnOffer.parse(route, null, tips).incremental.payCents);
        assertEquals(Integer.valueOf(500), AddOnOffer.parse(route, null, l("+$5.00 with tip", "+2 mi", "Add to route")).incremental.payCents);
        assertNull(AddOnOffer.parse(route, null, l("Customer tip +$4.00", "+2 mi", "Add to route")).incremental.payCents);
        assertNull(AddOnOffer.parse(route, null, l("+$3.00 boost", "+2 mi", "Add to route")).incremental.payCents);
    }

    // ---- Issue 5: while the rising rule is enabled, unknown pay is REVIEW even when its baseline is inactive.
    @Test public void risingRuleWithInactiveBaselineStillRequiresPay() {
        long now = CTX.nowWallMs;
        OfferSnapshot unknownPay = new OfferSnapshot(null, 3.0, 20, 2);
        FilterSettings[] baselines = {
                NONE.withRisingOffers(true).withLastAccepted(2500, 0L),                                   // undated legacy baseline
                NONE.withRisingOffers(true).withLastAccepted(2500, now - FilterSettings.BASELINE_MAX_AGE_MS - 1),   // stale
                NONE.withRisingOffers(true).withLastAccepted(0, 0L),                                      // never accepted
                new FilterSettings(true, 0, 0, 0, 0, 0, true, 2500), new FilterSettings(true, 0, 0, 0, 0, 0, true, 0)};   // 0.4.x constructors
        for (FilterSettings rising : baselines) {
            for (FilterSettings s : new FilterSettings[]{rising.withMaxStops(3), rising.withMaxMilesHundredths(600)}) {
                OfferRule.Decision d = OfferRule.evaluate(unknownPay, l(), s, CTX);
                assertEquals(s.maxStops + "/" + s.maxMilesHundredths + " at " + s.lastAcceptedAt, OfferRule.Result.REVIEW, d.result);
                assertEquals(OfferRule.Code.PAY_MISSING, d.code);
                assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(unknownPay, s).result);
                // Known pay: the inactive baseline does not apply (loosening only) and the other rule decides.
                assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(900, 3.0, 20, 2), l(), s, CTX).result);
            }
            OfferRule.Decision alone = OfferRule.evaluate(unknownPay, l(), rising, CTX);
            assertEquals(OfferRule.Result.REVIEW, alone.result); assertEquals(OfferRule.Code.NO_PAY_RULE, alone.code);
            // A separate known failure still declines.
            assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(null, 3.0, 20, 4), l(), rising.withMaxStops(3), CTX).result);
        }
    }

    // ---- Issue 6: rate context later on the line or at the start of the next node makes the amount a rate, never pay.
    @Test public void rateContextLaterOnTheLineOrStartingTheNextNodeIsNeverPay() {
        List<List<String>> rates = Arrays.asList(
                l("$25", "/hr • 4.1 mi", "Accept", "Decline"),
                l("$15", "/hr guaranteed", "4.1 mi", "Accept", "Decline"),
                l("$15", "per hour guaranteed", "4.1 mi", "Accept", "Decline"),
                l("$25.00 guaranteed per hour", "4.1 mi", "Accept", "Decline"),
                l("$25 guaranteed hourly", "4.1 mi", "Accept", "Decline"),
                l("Guaranteed", "$25", "/hr • 4.1 mi", "Accept", "Decline"),
                l("$1.50", "per mile guaranteed", "4.1 mi", "Accept", "Decline"),
                l("$1.50 guaranteed /mi", "4.1 mi", "Accept", "Decline"),
                l("$25", "/ hr", "4.1 mi", "Accept", "Decline"),
                l("$8.00", "Guaranteed", "per hour", "4.1 mi", "Accept", "Decline"),
                l("$8.00", "Guaranteed", "/hr • 4.1 mi", "Accept", "Decline"));
        for (List<String> card : rates) {
            assertNull(card.toString(), OfferParser.parse(card).payCents);
            assertNotEquals(card.toString(), OfferRule.Result.KEEP, screen(NONE.withFlatCents(1000), card).result);
            assertNotEquals(card.toString(), OfferRule.Result.DECLINE, screen(NONE.withFlatCents(3000), card).result);
        }
        // "per order" is not a rate, a later amount's rate does not reach back, and a deadline "within an hour" is not a rate.
        assertEquals(Integer.valueOf(950), p("$9.50", "per order", "4.2 mi").payCents);
        assertEquals(Integer.valueOf(950), p("$9.50 Guaranteed per order • 4.2 mi").payCents);
        assertEquals(Integer.valueOf(950), p("$9.50 Guaranteed · Deliver within an hour", "4.2 mi").payCents);
        assertEquals(Integer.valueOf(950), p("$9.50", "Guaranteed", "Est. $25/hr", "4.2 mi").payCents);
        assertEquals(Integer.valueOf(950), p("$9.50", "Guaranteed", "4.2 mi • 21 min").payCents);
        assertEquals(Integer.valueOf(950), p("Guaranteed", "$9.50 · 4.2 mi").payCents);
        assertEquals(Integer.valueOf(2824), p("$28.24", "Guaranteed", "Includes a $15.00 Flash offer boost", "3.2 mi").payCents);
    }
}
