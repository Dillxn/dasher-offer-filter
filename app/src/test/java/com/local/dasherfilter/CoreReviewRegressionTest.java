package com.local.dasherfilter;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Second-round core review: every probe shape the independent reviewers reported against the first 0.5.0 core diff.
 * Each case failed on that diff (wrong exact pay, wrong DECLINE/KEEP, lost acceptance, or unsafe confirmation).
 */
public final class CoreReviewRegressionTest {
    private static final EvaluationContext CTX = EvaluationContext.at(1_700_000_000_000L);
    private static final FilterSettings NONE = new FilterSettings(true, 0, 0, 0, 0, 0);
    private static final FilterSettings FLAT_6 = NONE.withFlatCents(600);
    private static List<String> l(String... s) { return Arrays.asList(s); }
    private static OfferSnapshot p(String... labels) { return OfferParser.parse(l(labels)); }
    private static OfferRule.Decision screen(FilterSettings s, List<String> labels) { return OfferRule.evaluate(OfferParser.parse(labels), labels, s, CTX); }
    /** Pay must be one of the allowed values (null = unknown, REVIEW); anything else is an inferred wrong payout. */
    private static void payIn(List<String> labels, Integer... allowed) {
        Integer pay = OfferParser.parse(labels).payCents;
        assertTrue(labels + " parsed " + pay, new HashSet<>(Arrays.asList(allowed)).contains(pay));
    }

    // Issue: a bare amount on the line before a pay label won over the real payout on the next line.
    @Test public void payLabelNeighboursThatDisagreeAreUnknownNeverTheWrongNeighbour() {
        payIn(l("Peak Pay", "$2.00", "Guaranteed (incl. tips)", "$9.50 · 4.2 mi"), 950, null);
        payIn(l("$2.00", "Guaranteed", "$9.50 • 3.1 mi • 2 stops"), 950, null);
        payIn(l("Flash offer boost", "$4.00", "Guaranteed", "$11.00 · 5.0 mi"), 1100, null);
        payIn(l("Customer tip", "$3.50", "Total pay", "$7.50 est."), 750, null);
        payIn(l("$54.10", "Guaranteed", "$9.50 • 4.2 mi"), 950, null);
        payIn(l("$1.50", "Guaranteed", "$9.50 • 4.2 mi"), 950, null);
        payIn(l("$54.10", "Guaranteed", "$9.50", "4.2 mi"), 950, null);
        for (List<String> card : Arrays.asList(l("Peak Pay", "$2.00", "Guaranteed (incl. tips)", "$9.50 · 4.2 mi"), l("$2.00", "Guaranteed", "$9.50 • 3.1 mi • 2 stops"),
                l("Flash offer boost", "$4.00", "Guaranteed", "$11.00 · 5.0 mi"), l("Customer tip", "$3.50", "Total pay", "$7.50 est."), l("$1.50", "Guaranteed", "$9.50 • 4.2 mi")))
            assertNotEquals(card.toString(), OfferRule.Result.DECLINE, screen(FLAT_6, card).result);
        assertNotEquals("an earnings pill must never ring the passing bell", OfferRule.Result.KEEP, screen(FLAT_6, l("$54.10", "Guaranteed", "$9.50 • 4.2 mi")).result);
        // The redesigned card still reads the amount before its label, and the original card the amount after it.
        assertEquals(Integer.valueOf(2824), p("$28.24", "Guaranteed", "Includes a $15.00 Flash offer boost", "3.2 mi").payCents);
        assertEquals(Integer.valueOf(850), p("$100.00 Weekly earnings", "Guaranteed", "$8.50", "4 mi").payCents);
        assertEquals(Integer.valueOf(950), p("Guaranteed", "$9.50 · 4.2 mi").payCents);
    }

    // Issue: whole rate/component lines were dropped from the ambiguity set, so a leftover amount became exact.
    @Test public void onlyAttributedAmountsLeaveTheAmbiguitySet() {
        payIn(l("$9.50 (tips included)", "Peak Pay", "$2.00", "4.2 mi", "Accept", "Decline"), 950, null);
        payIn(l("$9.50 before tips", "Peak Pay", "$2.00", "4.2 mi"), (Integer) null);
        payIn(l("$9.50 with tip", "Boost", "$3.00", "4.2 mi"), (Integer) null);
        payIn(l("$9.00 Guaranteed (incl. tips) • Store is less than a mile away", "Peak Pay", "$2.00", "4.2 mi"), 900);
        payIn(l("$9.50 Guaranteed · Deliver within an hour", "Earn an extra $3.00", "4.2 mi"), 950);
        payIn(l("$9.50", "per order", "Earn an extra $3.00", "4.2 mi"), 950, null);
        payIn(l("$11.25 with tip", "$54.10"), (Integer) null);
        for (List<String> card : Arrays.asList(l("$9.50 (tips included)", "Peak Pay", "$2.00", "4.2 mi", "Accept", "Decline"), l("$9.50 before tips", "Peak Pay", "$2.00", "4.2 mi"),
                l("$9.50 with tip", "Boost", "$3.00", "4.2 mi"), l("$9.00 Guaranteed (incl. tips) • Store is less than a mile away", "Peak Pay", "$2.00", "4.2 mi"),
                l("$9.50 Guaranteed · Deliver within an hour", "Earn an extra $3.00", "4.2 mi"), l("$9.50", "per order", "Earn an extra $3.00", "4.2 mi")))
            assertNotEquals(card.toString(), OfferRule.Result.DECLINE, screen(FLAT_6, card).result);
        assertNotEquals(OfferRule.Result.KEEP, screen(FLAT_6, l("$11.25 with tip", "$54.10")).result);
        // Rate units must follow the amount itself; "per" alone is not a rate.
        assertNull(p("$18.00", "per hour", "4.1 mi").payCents);
        assertNull(p("$12 a mile", "4.1 mi").payCents);
        assertEquals(Integer.valueOf(800), p("$8.00", "per order", "4.1 mi").payCents);
    }

    // Issue: a '+$' amount did not block the unlabeled single-amount fallback (H1.3 / H3).
    @Test public void plusAmountBlocksTheUnlabeledFallback() {
        assertNull(p("$5.00", "+$5.00").payCents);
        for (String peak : new String[]{"+$2.00 Peak Pay", "Peak Pay +$2.00", "+ $2.00 Peak Pay", "+$2.00 boost"}) {
            List<String> card = l("$5.00", peak, "3.1 mi");
            assertNull(peak, OfferParser.parse(card).payCents);
            assertEquals(peak, OfferRule.Result.REVIEW, OfferRule.evaluate(OfferParser.parse(card), FLAT_6).result);
            assertEquals(peak, OfferRule.Result.REVIEW, screen(FLAT_6, card).result);
        }
        assertEquals("labeled pay is unchanged", Integer.valueOf(850), p("$8.50 Guaranteed", "+$2.00 Peak Pay").payCents);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(p("Add to your route", "+$5.00", "+2 mi"), FLAT_6).result);
        // A component amount blocks the fallback too: whether $11 includes the $4 tip is not shown.
        assertNull(p("$11.00", "Customer tip $4.00", "4 mi").payCents);
    }

    // Issue: a bare '+$' node was add-on evidence, so a split Peak Pay line became a wrong add-on KEEP.
    @Test public void bareplusDollarIsNotAddOnEvidence() {
        List<String> card = l("$3.00", "Guaranteed", "+$2.00", "Peak Pay", "4.2 mi • 21 min", "Accept", "Decline");
        List<String> notification = l("New order", "$3.00 Guaranteed", "+$2.00", "Peak Pay", "4.2 mi");
        assertFalse(AddOnOffer.isLikely(card)); assertFalse(AddOnOffer.isLikely(notification));
        FilterSettings flat7 = NONE.withFlatCents(700);
        OfferSnapshot route = new OfferSnapshot(2500, 5.0, 30, 1);
        for (List<String> labels : Arrays.asList(card, notification)) {
            OfferSnapshot shown = OfferParser.parse(labels);
            OfferRule.Decision legacy = AddOnOffer.isLikely(labels) ? OfferRule.evaluateAddOn(AddOnOffer.parse(route, shown, labels), flat7) : OfferRule.evaluate(shown, flat7);
            assertNotEquals(labels.toString(), OfferRule.Result.KEEP, legacy.result);
            assertNotEquals(labels.toString(), OfferRule.Result.KEEP, OfferRule.evaluate(shown, labels, flat7, CTX).result);
        }
        List<String> passing = l("$8.50", "Guaranteed", "+$2.00", "Peak Pay", "4.2 mi • 21 min", "Accept", "Decline");
        assertEquals(OfferRule.Result.KEEP, screen(flat7, passing).result);
        assertTrue("explicit add-on increments still count", AddOnOffer.isLikely(l("+2.1 mi", "$4.00")));
        assertTrue(AddOnOffer.isLikely(l("$4.00", "+1 stop")));
    }

    // Issue: the Earn by Time guard was bypassed by the label-less entry points the adapters still call.
    @Test public void earnByTimeIsReviewThroughEveryPublicEntryPoint() {
        FilterSettings s = NONE.withMaxStops(2).withMaxMilesHundredths(600).withFlatCents(2000);
        OfferSnapshot route = new OfferSnapshot(2500, 5.0, 30, 1);
        for (List<String> labels : Arrays.asList(l("Earn by Time", "$15.00/active hr + tips", "3 stops", "Accept", "Decline"),
                l("$15/active hr", "7.2 mi", "3 stops", "Accept", "Decline"), l("New order", "$15.00 per active hour", "3 stops • 7.2 mi"),
                l("Add to route", "$15/active hr", "+1 stop", "Decline"))) {
            OfferSnapshot shown = OfferParser.parse(labels);
            AddOnOffer addOn = AddOnOffer.parse(route, shown, labels);
            OfferRule.Decision[] all = {OfferRule.evaluate(shown, s), OfferRule.evaluate(shown, labels, s, CTX), OfferRule.evaluate(shown, null, s, CTX),
                    OfferRule.evaluateAddOn(addOn, s), OfferRule.evaluateAddOn(addOn, labels, s, CTX), OfferRule.evaluateAddOn(addOn, null, s, CTX)};
            for (OfferRule.Decision d : all) { assertEquals(labels.toString(), OfferRule.Result.REVIEW, d.result); assertEquals(labels.toString(), OfferRule.Code.HOURLY_MODE, d.code); }
        }
    }

    // Issue: a derived increment (new total minus stored route pay) drove a marginal DECLINE.
    @Test public void derivedIncrementIsDisplayOnlyNeverADecline() {
        FilterSettings s = NONE.withPerMileCents(300);
        OfferSnapshot route = new OfferSnapshot(2000, 5.0, null, null);
        List<String> derived = l("Add-on order", "New total $24.00", "+2 mi", "Add to route", "Decline");
        AddOnOffer a = AddOnOffer.parse(route, OfferParser.parse(derived), derived);
        assertEquals("shown for context", Integer.valueOf(400), a.incremental.payCents); assertFalse(a.explicitIncrementPay);
        for (OfferRule.Decision d : new OfferRule.Decision[]{OfferRule.evaluateAddOn(a, s), OfferRule.evaluate(OfferParser.parse(derived), derived, s, CTX.withAddOn(route))}) {
            assertEquals(OfferRule.Result.REVIEW, d.result); assertEquals(OfferRule.Code.ADDON_AMBIGUOUS, d.code);
        }
        List<String> explicit = l("Add-on order", "+$4.00", "+2 mi", "Add to route", "Decline");
        AddOnOffer e = AddOnOffer.parse(route, OfferParser.parse(explicit), explicit);
        assertTrue(e.explicitIncrementPay);
        OfferRule.Decision d = OfferRule.evaluateAddOn(e, s);
        assertEquals(OfferRule.Result.DECLINE, d.result); assertEquals(600L, d.requiredCents);
    }

    // Issue: bare durations paired with pickup/ready/ETA context.
    @Test public void bareDurationNeedsMetricOnlyNeighbours() {
        FilterSettings perHour = NONE.withPerHourCents(3000);
        for (String[] group : new String[][]{{"Ready for pickup in", "35 min", "3.1 mi"}, {"Pickup in", "35 min", "3.1 mi"}, {"ETA", "35 min", "3.1 mi"}, {"3.1 mi", "35 min", "Prep time"}}) {
            List<String> labels = new java.util.ArrayList<>(Arrays.asList(group)); labels.add(0, "$12.00 Guaranteed");
            OfferSnapshot o = OfferParser.parse(labels, OfferParser.joinMetricSiblings(Arrays.asList(group)));
            assertNull(Arrays.toString(group), o.minutes);
            assertNotEquals(Arrays.toString(group), OfferRule.Result.DECLINE, OfferRule.evaluate(o, labels, perHour, CTX).result);
        }
        assertEquals(Integer.valueOf(21), OfferParser.parse(l("$8.50", "4.2 mi", "21 min"), OfferParser.joinMetricSiblings(l("4.2 mi", "•", "21 min"))).minutes);
    }

    // Issue: a road named "8 Mile" without a suffix was read as mileage and triggered max miles.
    @Test public void detroitMileRoadsAreNotMileage() {
        for (String road : new String[]{"Pickup: Coney Island · 8 Mile & Gratiot", "14 Mile and Van Dyke", "8 Mile/Woodward", "Coney Island @ 8 Mile", "Leo's Coney Island - 8 Mile", "12 MILE & DEQUINDRE"}) {
            List<String> card = l(road, "Accept", "Decline");
            assertNull(road, OfferParser.parse(card).miles);
            assertNotEquals(road, OfferRule.Result.DECLINE, screen(NONE.withMaxMilesHundredths(600), card).result);
        }
        assertEquals(1.0, p("$8.50", "1 mile").miles, 0.0);
        assertEquals(7.2, p("$8.50", "7.2 Miles").miles, 0.0);
        assertEquals(4.2, p("$8.50", "Coney Island · 8 Mile & Gratiot", "4.2 mi").miles, 0.0);
        assertFalse(AddOnOffer.isLikely(l("Coney Island +8 Mile & Gratiot")));
    }

    // Issue: store matching on every visible label declined on hotspots and numeric countdowns.
    @Test public void avoidListIgnoresHotspotsPromosAndNumbers() {
        assertNotNull(StoreMatcher.termError("76")); assertNotNull(StoreMatcher.termError("7-11"));
        assertTrue(StoreMatcher.sanitize(l("76", "Target")).equals(l("target")));
        FilterSettings s = NONE.withFlatCents(600).withAvoidStores(l("Target"));
        OfferRule.Decision d = screen(s, l("$14.00", "Guaranteed", "3.1 mi • 18 min", "McDonald's", "Accept", "Decline", "Hotspot: Target Plaza"));
        assertEquals(OfferRule.Result.KEEP, d.result);
        for (String promo : new String[]{"Busy area: Target Plaza", "Promotion: Target run bonus", "Peak Pay near Target", "Customer: Target Drive"})
            assertNotEquals(promo, OfferRule.Code.AVOIDED_STORE, screen(s, l("$14.00", "Guaranteed", "McDonald's", promo, "Accept", "Decline")).code);
        assertNull(StoreMatcher.firstMatch(l("76 gas"), null, l("76", "Accept 76")));
        assertEquals(OfferRule.Code.AVOIDED_STORE, screen(s, l("$14.00", "Guaranteed", "Target", "Accept", "Decline")).code);
        assertEquals(OfferRule.Code.AVOIDED_STORE, screen(s, l("$14.00", "Guaranteed", "Pickup at Target (Main St)", "Accept", "Decline")).code);
    }

    // Issue: with only the rising rule and its baseline inactive, standalone offers were KEEP.
    @Test public void inactiveRisingBaselineAloneNeverPasses() {
        FilterSettings undated = new FilterSettings(true, 0, 0, 0, 0, 0, true, 2500);
        OfferRule.Decision d = OfferRule.evaluate(new OfferSnapshot(100, 3.0, 20, 1), undated);
        assertEquals(OfferRule.Result.REVIEW, d.result); assertEquals(OfferRule.Code.NO_PAY_RULE, d.code);
        assertEquals("rising baseline not active; no other pay rule set", d.reason);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(100, 3.0, 20, 1), new FilterSettings(true, 0, 0, 0, 0, 0, true, 0)).result);
        FilterSettings dated = undated.withLastAccepted(2500, CTX.nowWallMs - 60_000L);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(100, null, null, null), null, dated, CTX).result);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(2600, null, null, null), null, dated, CTX).result);
        assertEquals("another rule still passes normally", OfferRule.Result.KEEP, OfferRule.evaluate(new OfferSnapshot(900, null, null, null), undated.withFlatCents(800)).result);
    }

    // Issues: a nav "Back" (or "Cancel") on a fresh full offer counted as a decline confirmation.
    @Test public void freshOfferWithNavBackOrCancelIsNeverAConfirmation() {
        for (String nav : new String[]{"Back", "Cancel", "Go back"}) for (String accept : new String[]{"Accept", "Slide to accept"}) {
            List<String> b = l("$25.00", "2 stops (7.2 mi) • 21 min", accept, "Decline", nav);
            boolean hasAccept = OfferControls.isButton(accept, "accept");
            assertFalse(b.toString(), DeclineConfirmation.isSurface(b, hasAccept));
            assertEquals(b.toString(), -1, DeclineConfirmation.select(b, l("Decline"), hasAccept));
            assertEquals("a different offer is never bound to the declined one", -1, DeclineConfirmation.select(b, l("Decline"), hasAccept, false));
        }
        assertFalse("bare Back is navigation, not a cancel control", DeclineConfirmation.isSurface(l("Decline offer", "Back"), true));
        assertFalse("a Decline-only frame with offer evidence is not a surface", DeclineConfirmation.isSurface(l("$30.00", "Decline"), false));
        assertFalse(DeclineConfirmation.isSurface(l("Are you sure?", "Cancel"), false));   // no decline control
        // The sheet over the SAME declined offer (adapter-bound identity) still confirms.
        List<String> overlay = l("$3.00", "Accept 29", "Decline", "Cancel", "Decline offer");
        assertEquals(-1, DeclineConfirmation.select(overlay, l("Decline", "Decline offer"), true));
        assertEquals(1, DeclineConfirmation.select(overlay, l("Decline", "Decline offer"), true, true));
        assertEquals(0, DeclineConfirmation.select(l("Are you sure you want to decline this offer?", "Go Back", "Decline"), l("Decline"), false));
    }

    // Issue: offerObserved compared label keys against a value that offerGone() cleared.
    @Test public void declinedOfferIdentitySurvivesOfferGoneAndOverlays() {
        OfferSnapshot a = new OfferSnapshot(790, 7.2, 21, 2), b = new OfferSnapshot(2500, 7.2, 21, 2);
        DeclineState s = new DeclineState();
        s.declineSent(DeclineState.offerKey(a, l("$7.90", "2 stops (7.2 mi) • 21 min", "Accept", "Decline")), a, 1000);
        s.offerGone();
        assertFalse("the same offer seen again keeps authority", s.offerObserved(a, 1500));
        assertTrue(s.isDeclinedOffer(OfferParser.parse(l("$7.90", "2 stops (7.2 mi) • 21 min", "Accept", "Decline", "Are you sure?", "Cancel", "Decline offer"))));
        assertFalse("a partial frame of the same offer is not a different offer", s.offerObserved(new OfferSnapshot(790, null, null, null), 1600));
        assertFalse("no known facts: neither proof of identity nor of a new offer", s.offerObserved(new OfferSnapshot(null, null, null, null), 1700));
        assertTrue(s.mayConfirm(1700));
        assertFalse(s.isDeclinedOffer(b));
        assertTrue("a different offer revokes", s.offerObserved(b, 1800));
        assertFalse(s.hasPendingConfirmation(1800));
        assertTrue("revocation never delays B's own first decline", s.mayDecline("b", 1800));
        s.reset(); assertFalse(s.isDeclinedOffer(a));
    }
    @Test public void finishedConfirmationFlowEndsAuthorityWhenTheSheetCloses() {
        DeclineState s = new DeclineState();
        s.declineSent("a", new OfferSnapshot(790, null, null, null), 1000);
        s.offerGone();
        assertTrue("a transition before any confirmation keeps authority", s.mayConfirm(1100));
        s.confirmationSent(1200);
        s.offerGone();
        assertFalse("once a confirmation was requested and the sheet closed, authority ends", s.hasPendingConfirmation(1300));
    }

    // Issue: acceptance tracking credited failed accepts, dropped real add-on accepts, and lost pending accepts.
    @Test public void routeLabelsSeenBeforeTheOfferNeverConfirmAnAccept() {
        OfferSnapshot o = new OfferSnapshot(900, 3.0, null, null);
        for (boolean labelsPassed : new boolean[]{true, false}) {
            AcceptedOfferTracker t = new AcceptedOfferTracker();
            assertEquals(AcceptedOfferTracker.Outcome.NONE, t.observeScreen(l("Arrived at customer", "Directions"), 500).outcome);
            assertEquals(AcceptedOfferTracker.Outcome.NONE, t.observeScreen(l("$9.00", "Decline"), 700).outcome);   // partial overlay hides the route
            if (labelsPassed) t.observeOffer(o, o, false, l("$9.00", "3.0 mi", "Accept", "Decline"), 1000); else t.observeOffer(o, 1000);
            assertEquals(AcceptedOfferTracker.AcceptClick.RECORDED, t.acceptClicked(1100));
            AcceptedOfferTracker.Observation seen = t.observeScreen(l("Arrived at customer", "Directions"), 1400);
            assertEquals(AcceptedOfferTracker.Outcome.AMBIGUOUS, seen.outcome); assertNull(seen.acceptance); assertTrue(seen.clearsRoute());
            assertNull("a later route step of the current delivery cannot confirm it", t.observeOtherScreen(l("Complete delivery"), 1600));
        }
    }
    @Test public void midRouteAddOnAcceptIsAmbiguousNotDropped() {
        AcceptedOfferTracker t = new AcceptedOfferTracker();
        OfferSnapshot combined = new OfferSnapshot(1600, 6.0, null, 3);
        t.observeOffer(new OfferSnapshot(null, null, null, null), combined, true, l("Arrived at customer", "Add to route", "+$10.00", "+3 mi", "Decline"), 1000);
        assertEquals(AcceptedOfferTracker.AcceptClick.RECORDED, t.acceptClicked(1100));
        AcceptedOfferTracker.Observation seen = t.observeScreen(l("Arrived at customer"), 3000);
        assertEquals(AcceptedOfferTracker.Outcome.AMBIGUOUS, seen.outcome); assertTrue(seen.addOn); assertTrue(seen.clearsRoute());
        // Expiry without any progress label is also reported, never dropped silently.
        AcceptedOfferTracker quiet = new AcceptedOfferTracker();
        quiet.observeOffer(new OfferSnapshot(null, null, null, null), combined, true, 1000); quiet.acceptClicked(1100);
        assertEquals(AcceptedOfferTracker.Outcome.NONE, quiet.observeScreen(l("Loading"), 3000).outcome);
        assertEquals(AcceptedOfferTracker.Outcome.AMBIGUOUS, quiet.observeScreen(l("Loading"), 17_000).outcome);
    }
    @Test public void failureTextIsRejectedAndLeavesBaselineAndRoute() {
        AcceptedOfferTracker t = new AcceptedOfferTracker();
        t.observeScreen(l("Arrived at customer"), 500);
        t.observeOffer(new OfferSnapshot(2500, null, null, null), 1000); t.acceptClicked(1100);
        AcceptedOfferTracker.Observation seen = t.observeScreen(l("This offer is no longer available", "OK"), 1200);
        assertEquals(AcceptedOfferTracker.Outcome.REJECTED, seen.outcome); assertFalse(seen.clearsRoute());
        assertNull(t.observeOtherScreen(l("Arrived at customer"), 1300));
    }
    @Test public void aTransientPartialFrameDoesNotLoseThePendingAccept() {
        for (boolean addOn : new boolean[]{false, true}) {
            AcceptedOfferTracker t = new AcceptedOfferTracker();
            OfferSnapshot shown = new OfferSnapshot(addOn ? null : 900, 4.0, null, null), route = addOn ? new OfferSnapshot(3000, 6.0, null, 4) : shown;
            t.observeOffer(shown, route, addOn, 1000);
            assertEquals(AcceptedOfferTracker.AcceptClick.RECORDED, t.acceptClicked(1100));
            assertNull(t.observeOtherScreen(addOn ? l("+$5.00", "Decline") : l("$9.00", "4 mi", "Decline"), 1200));
            t.observeOffer(shown, route, addOn, 1300);
            AcceptedOfferTracker.Acceptance a = t.observeOtherScreen(l(addOn ? "Confirm pickup" : "Arrived at store"), 1600);
            assertNotNull("addOn=" + addOn, a); assertEquals(addOn, a.addOn);
        }
    }
    @Test public void acceptOnAQuietScreenUsesTheLongBackstopAndUntrackedAddOnsAreReported() {
        AcceptedOfferTracker t = new AcceptedOfferTracker();
        t.observeOffer(new OfferSnapshot(null, null, null, null), new OfferSnapshot(3000, 6.0, null, 4), true, 1000);
        assertEquals("a quiet KEEP screen is not rescanned; 6 s later the tap still belongs to the offer", AcceptedOfferTracker.AcceptClick.RECORDED, t.acceptClicked(7000));
        AcceptedOfferTracker gone = new AcceptedOfferTracker();
        gone.observeOffer(new OfferSnapshot(null, null, null, null), new OfferSnapshot(3000, 6.0, null, 4), true, 1000);
        gone.observeScreen(l("Loading"), 1500);
        assertEquals(AcceptedOfferTracker.AcceptClick.ADDON_UNTRACKED, gone.acceptClicked(1600));
        assertEquals(AcceptedOfferTracker.AcceptClick.NONE, new AcceptedOfferTracker().acceptClicked(1000));
    }

    // Issue: "order from" / "offer from" markers turned customer messages and account notices into offers.
    @Test public void operationalNotificationsAreNotOffers() {
        for (List<String> labels : Arrays.asList(l("Dasher", "Customer message about your order from Chipotle: Please leave it at the door"),
                l("Dasher", "Your order from Taco Bell was cancelled by the customer"), l("Jane sent you a message", "Can you pick up my order from Panda Express too?"),
                l("Customer", "Is my order from Chipotle on the way?"))) {
            assertEquals(labels.toString(), NotificationOffer.Kind.NOT_OFFER, NotificationOffer.classify(labels));
            assertFalse(labels.toString(), NotificationOffer.isLikelyOffer(labels));
        }
        for (List<String> labels : Arrays.asList(l("Promotion: earn $3.00 extra on every new order tonight", "2 stops"), l("New order request", "Balance: $54.10 available for Fast Pay", "2 stops"))) {
            assertNotEquals(labels.toString(), NotificationOffer.Kind.OFFER, NotificationOffer.classify(labels));
            assertFalse("review-only is never processed by the legacy decline/hide/ring path", NotificationOffer.isLikelyOffer(labels));
        }
        assertEquals(NotificationOffer.Kind.OFFER, NotificationOffer.classify(l("$28.24 Flash offer available", "3.2mi offer from Mr. Pollo. ... Tap to view offer details.")));
        assertEquals(NotificationOffer.Kind.OFFER, NotificationOffer.classify(l("New Delivery!", "New Order: Go to Chick-fil-A")));
        assertEquals("Chipotle", NotificationOffer.merchant(l("New order from Chipotle: 2 items")));
    }
    @Test public void avoidedStoreWithoutAnyOfferFactsIsReviewOffScreen() {
        FilterSettings s = NONE.withFlatCents(600).withAvoidStores(l("Chipotle"));
        List<String> notification = l("New Delivery!", "New Order: Go to Chipotle");
        OfferRule.Decision d = OfferRule.evaluate(OfferParser.parse(notification), notification, s, CTX.withMerchant(NotificationOffer.merchant(notification)));
        assertEquals(OfferRule.Result.REVIEW, d.result); assertEquals(OfferRule.Code.AVOIDED_STORE, d.code); assertEquals("chipotle", d.avoidedStore);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(null, 3.2, null, null), notification, s, CTX.withMerchant("Chipotle")).result);
        assertEquals("both screen controls prove a real offer card", OfferRule.Result.DECLINE, screen(s, l("Chipotle", "Accept", "Decline")).result);
    }

    // Issue: the H1.9 regression asserted nothing about the rule effect.
    @Test public void combinedMilesSumHasNoPhantomCent() {
        AddOnOffer a = AddOnOffer.parse(new OfferSnapshot(110, 1.1, null, null), new OfferSnapshot(null, null, null, null), l("Add to route", "+$0.60", "+0.6 mi"));
        assertEquals(1.7, a.combined.miles, 0.0);
        OfferRule.Decision d = OfferRule.evaluateAddOn(a, NONE.withPerMileCents(100));
        // Third review: a route total derived from the stored route never decides an add-on (it was KEEP here, and the $1.09
        // route below was DECLINE, on the derived 1.7 mi). The exact sum still reaches the breakdown and the saved route context.
        assertEquals(OfferRule.Result.REVIEW, d.result);
        assertTrue(d.breakdown.toString(), d.breakdown.contains("Route after adding: $1.00/mi: route total not shown (derived 1.7 mi is not used)"));
        assertTrue(d.breakdown.toString(), d.breakdown.contains("+0.6 mi × $1.00/mi = $0.60"));
        assertNotEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(AddOnOffer.parse(new OfferSnapshot(109, 1.1, null, null), null, l("Add to route", "+$0.60", "+0.6 mi")), NONE.withPerMileCents(100)).result);
        // Displayed totals decide, with the same exact ceiling arithmetic: 1.7 mi × $1.00 = $1.70.
        OfferSnapshot route = new OfferSnapshot(110, 1.1, null, null);
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluateAddOn(AddOnOffer.parse(route, null, l("Add to route", "+$0.60", "+0.6 mi", "New total $1.70", "Total distance: 1.7 mi")), NONE.withPerMileCents(100)).result);
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluateAddOn(AddOnOffer.parse(new OfferSnapshot(109, 1.1, null, null), null, l("Add to route", "+$0.60", "+0.6 mi", "New total $1.69", "Total distance: 1.7 mi")), NONE.withPerMileCents(100)).result);
    }

    // Reviewer probes H, I, N (also present in 0.4.5): relative times, "to" ranges, and split "+ tips" never become exact facts.
    @Test public void pickupEtaTimesAndToRangesAreNotTripFacts() {
        assertNull(p("$9.50", "Pickup in 5 min • 4.2 mi").minutes);
        assertNull(p("$9.50", "Ready for pickup in 12 min • 3 stops").minutes);
        assertNull(p("$9.50", "ETA 8 min • 4.2 mi").minutes);
        assertEquals(Integer.valueOf(21), p("$9.50", "Pickup in 5 min • 4.2 mi • 21 min").minutes);
        assertEquals(Integer.valueOf(21), p("$9.50", "4.2 mi in 21 min").minutes);
        assertNull(p("$9.50", "4.2 mi • 21 to 35 min").minutes);
        assertNull(p("$9.50", "3 to 5 miles").miles);
        assertNull(p("$9.50", "2 to 4 stops").stops);
        assertNotEquals(OfferRule.Result.DECLINE, screen(NONE.withPerHourCents(3000), l("$9.50 Guaranteed", "Pickup in 5 min • 4.2 mi", "Accept", "Decline")).result);
    }
    @Test public void splitPlusTipsNodesMakePayALowerBound() {
        assertNull(p("$7.50", "+", "tips").payCents);
        assertNull(p("$7.50", "plus", "tips", "3 mi").payCents);
        assertNull(p("$7.50", "+", "$2.00").payCents);
        assertEquals("a lone + elsewhere (map zoom) is not a pay qualifier", Integer.valueOf(750), p("$7.50", "3 mi", "+", "Zoom out").payCents);
    }
    @Test public void moreCountdownShapesKeepTheOfferKeyStable() {
        OfferSnapshot o = new OfferSnapshot(790, 7.2, 21, 2);
        String[][] ticks = {{"(32)", "(31)"}, {":32", ":31"}, {"⏱ 32", "⏱ 31"}, {"Time to respond 32", "Time to respond 31"}};
        for (String[] t : ticks) assertEquals(t[0], DeclineState.offerKey(o, l("Cafe", "$7.90", t[0], "Decline")), DeclineState.offerKey(o, l("Cafe", "$7.90", t[1], "Decline")));
        assertNotEquals(DeclineState.offerKey(o, l("Cafe", "$7.90")), DeclineState.offerKey(o, l("Pizza", "$7.90")));
    }
}
