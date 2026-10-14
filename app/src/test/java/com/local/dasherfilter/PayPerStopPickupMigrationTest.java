package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 0.6.14 (the owner, 14 October 2026: "i don't want the pay per stop to not count the pickup"; "it doesn't need to do
 * any halving"): pay per stop counts every stop, the pickup included, and a 0.6.12/0.6.13 install's saved number is
 * untouched. A stored learned pay best's stops rise by one (in the learned raise's note too), "minimums since" moves to
 * now, one log line, none on a new install; running it twice changes nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
public class PayPerStopPickupMigrationTest {
    private static final long BEFORE = 1_760_000_000_000L;
    private static final long NOW = 1_760_400_000_000L;
    private Application app;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        prefs().edit().clear().commit();
        DiagnosticLog.forgetCache();
        DiagnosticLog.clear(app);
        DiagnosticLog.setEnabled(app, true);
        FilterStore.wallClock = () -> NOW;
    }

    @After public void tearDown() {
        FilterStore.wallClock = System::currentTimeMillis;
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
    }

    /** A 0.6.12/0.6.13 install: rule C's step done (pay_per_stop_schema 1). */
    private void installRuleC(int flat, int mile, int learnedPay, int learnedUnits) {
        prefs().edit().putInt("rules_model", 2).putInt("learner_v3_schema", 2).putInt("autopilot_default_schema", 1)
                .putInt(FilterStore.PAY_PER_STOP_SCHEMA, 1)
                .putBoolean("enabled", true).putInt("flat", flat).putInt("mile", mile).putInt("minute", 0)
                .putInt("max_stops", 0).putBoolean("acceptance_learning_v3", true)
                .putInt(FilterStore.ACCEPTED_PAY, learnedPay).putInt(FilterStore.ACCEPTED_PAY_UNITS, learnedUnits)
                .putLong("minimums_since", BEFORE).commit();
    }

    private int lines(String log) {
        return log.split("now counts every stop", -1).length - 1;
    }

    @Test public void theNumberIsUntouchedAndTheBestGainsItsPickupOnce() {
        installRuleC(500, 0, 1500, 1);
        FilterSettings rules = FilterStore.load(app);
        assertEquals("FLAT is untouched", 500, rules.flatCents);
        assertEquals(1500, rules.accepted.highestPayCents);
        assertEquals("units + 1", 2, rules.accepted.payUnits);
        assertEquals(2, prefs().getInt(FilterStore.PAY_PER_STOP_SCHEMA, 0));
        assertEquals("evidence restarts", NOW, FilterStore.minimumsSince(app));
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[rules] pay per stop now counts every stop, the pickup included (number "
                + "unchanged, so a single order asks twice what it did)"));
        assertEquals(log, 1, lines(log));
        // $5 a stop asks $10.00 of a single order; the learned best of $15.00 for a single order still asks $15.01.
        OfferSnapshot single = new OfferSnapshot(1500, 3.0, 15, 2);
        assertEquals(1000, OfferRule.evaluate(single, rules.withAcceptanceLearning(false)).requiredCents);
        assertEquals(1501, OfferRule.evaluate(single, rules).requiredCents);
        assertEquals(1500, OfferRule.evaluate(new OfferSnapshot(1500, 3.0, 15, 3),
                rules.withAcceptanceLearning(false)).requiredCents);

        // Twice changes nothing and logs nothing more.
        FilterStore.wallClock = () -> NOW + 60_000;
        FilterSettings again = FilterStore.load(app);
        assertEquals(500, again.flatCents);
        assertEquals(2, again.accepted.payUnits);
        assertEquals(1500, again.accepted.highestPayCents);
        assertEquals(NOW, FilterStore.minimumsSince(app));
        assertEquals(1, lines(DiagnosticLog.read(app)));
    }

    @Test public void aDowngradeAndUpgradeNeverRunsItAgain() {
        installRuleC(500, 0, 1500, 1);
        FilterStore.load(app);
        // An older version writes a retired key again, so the 0.5.0 move runs again on the next load.
        prefs().edit().putInt("per_item", 0).commit();
        FilterSettings again = FilterStore.load(app);
        assertFalse(prefs().contains("per_item"));
        assertEquals(500, again.flatCents);
        assertEquals("units + 1 once only", 2, again.accepted.payUnits);
        assertEquals(2, prefs().getInt(FilterStore.PAY_PER_STOP_SCHEMA, 0));
        assertEquals(1, lines(DiagnosticLog.read(app)));
    }

    @Test public void aStackedBestCountsItsPickupToo() {
        installRuleC(1301, 0, 2600, 2);
        FilterSettings rules = FilterStore.load(app);
        assertEquals(1301, rules.flatCents);
        assertEquals("a 3-stop best (2 after the pickup) is 3 stops", 3, rules.accepted.payUnits);
        // Its floor is the accepted pay ÷ its own 3 stops + 1¢ for each stop.
        assertEquals(2601, OfferRule.evaluate(new OfferSnapshot(9000, 3.0, 15, 3),
                rules.withMinimums(0, 0, 0)).requiredCents);
    }

    @Test public void theLearnedRaisesNoteKeepsMatching() throws Exception {
        installRuleC(1000, 0, 2600, 1);
        prefs().edit().putString("learner_v3_raise_note", new JSONObject().put("id", 7).put("pay", 2600)
                .put("payUnits", 1).put("milePay", 0).put("miles", 0).put("minutePay", 0).put("minutes", 0)
                .toString()).commit();
        FilterStore.LearnedRaise raise = FilterStore.learnedRaise(app);
        assertNotNull("the note still shows after the step", raise);
        assertEquals(2, raise.bests.payUnits);
        assertEquals(1000, raise.savedFlat);
        assertEquals("⌈($26.00 + 1¢) ÷ 2⌉", 1301, raise.flat);
    }

    @Test public void withoutAPayMinimumOnlyTheOtherMinimumsRestartAndANewInstallSaysNothing() {
        installRuleC(0, 100, 0, 1);
        FilterSettings rules = FilterStore.load(app);
        assertEquals(0, rules.flatCents);
        assertEquals(100, rules.perMileCents);
        assertEquals(NOW, FilterStore.minimumsSince(app));
        assertTrue(DiagnosticLog.read(app).contains(FilterStore.PICKUP_COUNTED_LOG));

        prefs().edit().clear().commit();
        DiagnosticLog.clear(app);
        FilterStore.load(app);
        assertEquals(2, prefs().getInt(FilterStore.PAY_PER_STOP_SCHEMA, 0));
        assertFalse(DiagnosticLog.read(app).contains("pay per stop"));
    }

    @Test public void nothingSetNothingRestarts() {
        installRuleC(0, 0, 0, 1);
        FilterStore.load(app);
        assertEquals(BEFORE, FilterStore.minimumsSince(app));
    }

    @Test public void stackedOffersAskEveryStopAndUnreadStopsKeepTheOneStopBound() {
        FilterSettings rules = FilterSettings.of(true, 650, 0, 0, 0);
        assertEquals(1300, OfferRule.evaluate(new OfferSnapshot(1000, 3.0, 15, 2), rules).requiredCents);
        assertEquals(1950, OfferRule.evaluate(new OfferSnapshot(1000, 3.0, 15, 3), rules).requiredCents);
        assertEquals(2600, OfferRule.evaluate(new OfferSnapshot(1000, 3.0, 15, 4), rules).requiredCents);
        // Unread stops: one stop's worth ($6.50) declines; above it the offer is left for review.
        assertEquals(OfferRule.Result.DECLINE, OfferRule.evaluate(new OfferSnapshot(649, 3.0, 15, null), rules).result);
        assertEquals(OfferRule.Result.REVIEW, OfferRule.evaluate(new OfferSnapshot(900, 3.0, 15, null), rules).result);
    }
}
