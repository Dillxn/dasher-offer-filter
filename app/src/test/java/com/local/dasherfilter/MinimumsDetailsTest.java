package com.local.dasherfilter;

import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * The ticket's "How this offer was judged" (finalSpec explainability): each of the three minimums as the selected
 * offer's own miles and minutes made it, what the bar used, the requirement and the score, and nothing of the retired
 * rules (learned minimums, score by area, per stop, per item, the hotspot). Unread amounts stay unread.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class MinimumsDetailsTest {
    /** The typical starter minimums: $4.00, $1.00 a mile, $15 an hour (25¢ a minute), at most 3 stops. */
    private static FilterSettings starters() {
        return FilterSettings.of(true, 400, 100, 25, 3);
    }

    /** The spec's worked offer: $5.75 for 6.6 mi and 27 min, 2 stops. */
    private static OfferSnapshot worked() {
        return new OfferSnapshot(575, 6.6, 27, 2);
    }

    /** A line this version decided for {@code facts} at {@code bar} (Autopilot on below or above 100). */
    private static DecisionLog.Entry decided(OfferSnapshot facts, FilterSettings rules) {
        OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
        return DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision, DecisionLog.Action.PASSES, true,
                Collections.<String>emptyList());
    }

    private static FilterSettings atBar(FilterSettings rules, int bar) {
        return new FilterSettings(rules.enabled, rules.flatCents, rules.perMileCents, rules.perMinuteCents,
                rules.maxStops, bar != FilterSettings.BAR_AT_MINIMUMS, FilterSettings.GOAL_TOP_TIER, bar);
    }

    private static void assertNothingRetired(String text) {
        for (String retired : new String[] {"Learned", "learned", "Saved", "blue", "purple", "area", "Area", "Pay/stop",
                "per stop", "item", "Hotspot", "hotspot", "baseline", "Score reference", "strict"}) {
            assertFalse("no retired words (" + retired + "): " + text, text.contains(retired));
        }
    }

    @Test public void aKnownOfferShowsEachMinimumAsItsMilesAndMinutesMadeItAndWhatTheBarUsed() {
        // The spec's ticket, word for word: decided at Autopilot's 82% bar.
        DecisionLog.Entry entry = decided(worked(), atBar(starters(), 82));
        assertEquals(82, entry.barPercent);
        assertTrue(entry.autopilot);
        String text = MinimumsDetails.describe(atBar(starters(), 82), entry);
        assertEquals("Your minimums · bar 82% (Autopilot)\n"
                + "Pay — set $4.00 · used ≈$3.28\n"
                + "Per mile — set $6.60 (6.6 mi × $1.00) · used ≈$5.42\n"
                + "Per hour — set $6.75 (27 min × $15/hr) · used ≈$5.54\n"
                + "Required ≈$5.54: 82% of the highest set amount ($6.75) · score 85%\n"
                + "Max stops 3 (never scaled). Below 100%, offers pass only because Autopilot lowered the bar and are "
                + "never auto-accepted. ≈ means rounded up to the next cent.", text);
        assertNothingRetired(text);
    }

    @Test public void theSelectedOffersOwnAmountsChangeWhatEachMinimumAsksWithoutInventingPay() {
        // Another offer, its pay unread, at exactly the minimums: the per-mile and per-hour amounts follow its own miles
        // and minutes, the highest set amount is the requirement, and there is no score without pay.
        OfferSnapshot unpaid = new OfferSnapshot(null, 2.0, 10, 4);
        DecisionLog.Entry entry = decided(unpaid, starters());
        String text = MinimumsDetails.describe(starters(), entry);
        assertTrue(text, text.startsWith("Your minimums · bar 100%\n"));
        assertTrue(text, text.contains("Pay — set $4.00\n"));
        assertTrue(text, text.contains("Per mile — set $2.00 (2 mi × $1.00)\n"));
        assertTrue(text, text.contains("Per hour — set $2.50 (10 min × $15/hr)\n"));
        assertTrue(text, text.contains("Required $4.00: the highest set amount\n"));
        assertFalse("nothing used at a bar of exactly 100%: " + text, text.contains("used"));
        assertFalse("no score without pay: " + text, text.contains("score"));
        assertFalse(text, text.contains("Offer $"));
        assertFalse("no Autopilot below 100% to explain: " + text, text.contains("Below 100%"));
        assertTrue(text, text.endsWith("Max stops 3 (never scaled)."));
        assertNothingRetired(text);
    }

    @Test public void unreadMilesAndMinutesStayUnreadAndTheRequirementIsOnlyAFloor() {
        OfferSnapshot unread = new OfferSnapshot(900, null, null, 2);
        String text = MinimumsDetails.describe(starters(), decided(unread, starters()));
        assertTrue(text, text.contains("Per mile — $1.00 a mile; miles not read"));
        assertTrue(text, text.contains("Per hour — $15/hr; minutes not read"));
        assertTrue("what is known is a floor, never a requirement: " + text,
                text.contains("Required at least $4.00: the highest set amount"));
        assertFalse("no score from what is missing: " + text, text.contains("score"));

        // With only rate minimums and nothing to apply them to, nothing is required yet.
        FilterSettings rates = FilterSettings.of(true, 0, 100, 25, 0);
        String none = MinimumsDetails.describe(rates, decided(OfferSnapshot.UNKNOWN, rates));
        assertTrue(none, none.contains("Required: not known until this offer's miles and minutes are read."));
        assertFalse(none, none.contains("Pay —"));
        String off = MinimumsDetails.describe(FilterSettings.of(true, 0, 0, 0, 3), decided(worked(),
                FilterSettings.of(true, 0, 0, 0, 3)));
        assertTrue(off, off.contains("No pay, per-mile or hourly minimum is set."));
        assertNothingRetired(text + none + off);
    }

    @Test public void aLineDecidedUnderTheRetiredRulesSaysSoAndWhatItNeedsNow() throws Exception {
        // Written by 0.4.x: no "model" in its JSON, a learned-minimum reason and an area score.
        JSONObject json = new JSONObject().put("at", 1_000L).put("source", "SCREEN").put("addOn", false)
                .put("required", 1101).put("result", "DECLINE").put("reason", "must beat highest accepted payout $11.00")
                .put("action", "DECLINE_TAPPED").put("autoDecline", true).put("evidence", new org.json.JSONArray())
                .put("pay", 575).put("miles", 6.6).put("minutes", 27).put("stops", 2).put("score", 121);
        DecisionLog.Entry legacy = DecisionLog.Entry.fromJson(json);
        assertEquals(DecisionLog.LEGACY_MODEL, legacy.model);
        String text = MinimumsDetails.describe(atBar(starters(), 82), legacy);
        assertTrue(text, text.startsWith("Decided under retired rules (score by area or learned minimums). Now: needs "
                + "$5.54 at 82%.\nYour minimums · bar 82% (Autopilot)\n"));
        assertTrue("worked out again at today's bar: " + text,
                text.contains("Required ≈$5.54: 82% of the highest set amount ($6.75) · score 85%"));
        assertFalse("an old requirement is not compared with today's: " + text, text.contains("When decided"));

        String plain = MinimumsDetails.describe(starters(), legacy);
        assertTrue(plain, plain.startsWith("Decided under retired rules (score by area or learned minimums). Now: needs "
                + "$6.75 at 100%.\nYour minimums · bar 100%\n"));
    }

    @Test public void itemsAreAFactOfTheOfferNeverAMinimum() {
        // A shopping offer with 4 items read: nothing per item is asked, used or explained; only the three minimums.
        OfferSnapshot shopping = worked().withItems(4, true);
        String text = MinimumsDetails.describe(starters(), decided(shopping, starters()));
        assertEquals(MinimumsDetails.describe(starters(), decided(worked(), starters())), text);
        assertTrue(text, text.contains("Required $6.75: the highest set amount · score 85%"));
        String unread = MinimumsDetails.describe(starters(), decided(worked().withItems(null, true), starters()));
        assertEquals("an unread count changes nothing either", text, unread);
        assertNothingRetired(text);
    }

    @Test public void whatTheBarUsedIsExactlyWhatTheRuleDecidedWith() {
        // At every bar Autopilot can hold, the ticket's "used" requirement is the decision's own, cent for cent.
        for (int bar = 50; bar <= 150; bar += 7) {
            FilterSettings rules = atBar(starters(), bar);
            OfferRule.Decision decision = OfferRule.evaluate(worked(), rules);
            String text = MinimumsDetails.describe(rules, decided(worked(), rules));
            String required = bar == 100 ? "Required " + DecisionLog.money(decision.requiredCents) + ":"
                    : "Required ≈" + DecisionLog.money(decision.requiredCents) + ": " + bar + "% of the highest set "
                    + "amount ($6.75)";
            assertTrue(bar + "%: " + text, text.contains(required));
            assertFalse("the decision's own requirement: " + text, text.contains("When decided"));
        }
    }

    @Test public void minimumsChangedSinceTheDecisionAreSaidWithWhatItNeededThen() {
        DecisionLog.Entry entry = decided(worked(), starters());
        assertEquals(675, entry.requiredCents);
        FilterSettings raised = FilterSettings.of(true, 400, 150, 25, 3);
        String text = MinimumsDetails.describe(raised, entry);
        assertTrue(text, text.contains("Per mile — set $9.90 (6.6 mi × $1.50)\n"));
        assertTrue(text, text.contains("Required $9.90: the highest set amount · score 58%\n"));
        assertTrue(text, text.contains("When decided it needed $6.75; your minimums have changed since."));
        assertFalse(MinimumsDetails.describe(starters(), entry).contains("When decided"));
    }

    @Test public void theBarIsSeparateAndAFractionalCentIsNeverRoundedBeforeTheBarApplies() {
        FilterSettings rules = atBar(FilterSettings.of(true, 0, 100, 0, 0), 97);
        OfferSnapshot offer = new OfferSnapshot(100, 1.0301, null, null);
        String text = MinimumsDetails.describe(rules, decided(offer, rules));
        // $1.0301 a mile asks 103.01¢: shown as ≈$1.04, but 97% of the exact amount is 99.92¢, ≈$1.00 (not 97% of
        // $1.04, which would be $1.01).
        assertTrue(text, text.contains("Per mile — set ≈$1.04 (1.0301 mi × $1.00) · used ≈$1.00"));
        assertTrue(text, text.contains("Required ≈$1.00: 97% of the highest set amount (≈$1.04) · score 97%"));
        assertTrue(text, text.endsWith("≈ means rounded up to the next cent."));
        assertEquals("the stored minimum stays as set", 100, rules.perMileCents);
        assertEquals(100, OfferRule.evaluate(offer, rules).requiredCents);
    }

    @Test public void aKnownZeroMileRouteAsksNothingOfThePerMileMinimum() {
        FilterSettings mile = FilterSettings.of(true, 0, 100, 0, 0);
        OfferSnapshot zero = new OfferSnapshot(100, 0.0, null, null);
        String text = MinimumsDetails.describe(mile, decided(zero, mile));
        assertTrue(text, text.contains("Per mile — set $0.00 (0 mi × $1.00)"));
        assertTrue(text, text.contains("Required $0.00: the highest set amount"));
        assertFalse("nothing asked, no score: " + text, text.contains("score"));
        assertEquals(OfferRule.Result.KEEP, OfferRule.evaluate(zero, mile).result);
        assertNothingRetired(text);
    }

    @Test public void aFinalStopDistanceIsNeverPartOfHowAnOfferIsJudged() {
        // An older line could carry a final stop's distance to a hotspot; 0.5.0 has no hotspot rule, so it is no
        // requirement, no dollars and no words, whatever the distance (an exact zero included).
        OfferSnapshot near = new OfferSnapshot(575, 6.6, 27, 2, null, 0.0, null, false);
        OfferSnapshot far = new OfferSnapshot(575, 6.6, 27, 2, null, 12.5, null, false);
        String plain = MinimumsDetails.describe(starters(), decided(worked(), starters()));
        assertEquals(plain, MinimumsDetails.describe(starters(), decided(near, starters())));
        assertEquals(plain, MinimumsDetails.describe(starters(), decided(far, starters())));
        assertNothingRetired(plain);
    }
}
