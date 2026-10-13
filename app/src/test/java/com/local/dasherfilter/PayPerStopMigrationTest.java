package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The 0.6.12 move to pay per stop on real stored preferences: once, the saved number unchanged, a stored learned pay
 * best counted as one stop, "minimums since" restamped, one log line; and every 2-stop offer Dillon captured on 12
 * October judged exactly as the minimum pay judged it. Also the decision history's "payPer" and the knob's words.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
public class PayPerStopMigrationTest {
    private static final long BEFORE = 1_760_000_000_000L;
    private static final long NOW = 1_760_300_000_000L;
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

    /** A 0.6.11 install: model 2, learner schema 2, Autopilot's schema, $13.00 minimum pay and a learned $15.00. */
    private void install0611(int flat, int learnedPay) {
        prefs().edit().putInt("rules_model", 2).putInt("learner_v3_schema", 2).putInt("autopilot_default_schema", 1)
                .putBoolean("enabled", true).putInt("flat", flat).putInt("mile", 0).putInt("minute", 0)
                .putInt("max_stops", 0).putBoolean("acceptance_learning_v3", true)
                .putInt(FilterStore.ACCEPTED_PAY, learnedPay).putLong("minimums_since", BEFORE).commit();
    }

    @Test public void theSavedNumberStaysAndCountsPerStopOnce() {
        install0611(1300, 1500);
        FilterSettings rules = FilterStore.load(app);
        assertEquals("the number is unchanged", 1300, rules.flatCents);
        assertEquals(1500, rules.accepted.highestPayCents);
        assertEquals("a stored best counts as one stop", 1, rules.accepted.payUnits);
        assertEquals(1, prefs().getInt(FilterStore.ACCEPTED_PAY_UNITS, 0));
        assertEquals(1, prefs().getInt(FilterStore.PAY_PER_STOP_SCHEMA, 0));
        assertEquals("evidence restarts", NOW, FilterStore.minimumsSince(app));
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains(FilterStore.payPerStopLog(1300)));
        assertTrue(log, log.contains("minimum pay $13.00 now counts per stop after the first pickup (single orders "
                + "unchanged)"));

        // Idempotent: a second load changes nothing and logs nothing more.
        FilterStore.wallClock = () -> NOW + 60_000;
        assertEquals(1300, FilterStore.load(app).flatCents);
        assertEquals(NOW, FilterStore.minimumsSince(app));
        String again = DiagnosticLog.read(app);
        assertEquals(again, log.split("pay per stop:", -1).length, again.split("pay per stop:", -1).length);
    }

    @Test public void withNoMoneyMinimumItOnlyNotesItAndANewInstallSaysNothing() {
        install0611(0, 0);
        FilterStore.load(app);
        assertEquals(BEFORE, FilterStore.minimumsSince(app));
        assertTrue(DiagnosticLog.read(app).contains(FilterStore.payPerStopLog(0)));
        assertEquals("pay per stop: no pay minimum set", FilterStore.payPerStopLog(0));

        prefs().edit().clear().commit();
        DiagnosticLog.clear(app);
        FilterStore.load(app);
        assertEquals(1, prefs().getInt(FilterStore.PAY_PER_STOP_SCHEMA, 0));
        assertFalse(DiagnosticLog.read(app).contains("pay per stop:"));
    }

    @Test public void everyCapturedTwoStopOfferIsJudgedAsTheMinimumPayJudgedIt() throws Exception {
        int[][] rulesSets = {{1300, 0, 0}, {800, 150, 25}, {500, 200, 0}, {1000, 0, 30}};
        int twoStop = 0;
        InputStream in = getClass().getClassLoader().getResourceAsStream("offer-screens-2026-10-12.txt");
        assertNotNull(in);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("#") || line.trim().isEmpty()) continue;
                String[] facts = line.split(" \\| ")[1].split(":");
                if (!"2".equals(facts[3]) || "null".equals(facts[0])) continue;
                twoStop++;
                int pay = Integer.parseInt(facts[0]);
                double miles = Double.parseDouble(facts[1]);
                int minutes = Integer.parseInt(facts[2]);
                OfferSnapshot offer = new OfferSnapshot(pay, miles, minutes, 2);
                for (int[] r : rulesSets) {
                    for (int bar : new int[] {82, 100, 119}) {
                        FilterSettings rules = FilterSettings.of(true, r[0], r[1], r[2], 0)
                                .withMinimumScalePercent(bar);
                        // The minimum pay's own requirement: the highest of flat, miles × per mile, minutes × rate.
                        java.math.BigDecimal ask = java.math.BigDecimal.valueOf(r[0])
                                .max(java.math.BigDecimal.valueOf(r[1]).multiply(java.math.BigDecimal.valueOf(miles)))
                                .max(java.math.BigDecimal.valueOf((long) r[2] * minutes));
                        long required = AreaScore.roundedCents(ask, bar);
                        OfferRule.Decision decision = OfferRule.evaluate(offer, rules);
                        assertEquals(line, required, decision.requiredCents);
                        assertEquals(line, pay < required ? OfferRule.Result.DECLINE : OfferRule.Result.KEEP,
                                decision.result);
                    }
                }
            }
        }
        assertTrue("the captures hold 2-stop offers: " + twoStop, twoStop >= 5);
    }

    @Test public void historyLinesSayPayPerStopAndOlderOnesAreNotReplayed() throws Exception {
        FilterSettings rules = FilterSettings.of(true, 1300, 100, 0, 0).withAcceptanceLearning(true)
                .withAccepted(new AcceptedFloors(2600, 2, 0, 0, 0, 0));
        OfferSnapshot facts = new OfferSnapshot(3000, 5.0, 20, 3);
        DecisionLog.Entry entry = DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts,
                OfferRule.evaluate(facts, rules), DecisionLog.Action.PASSES, false, Collections.<String>emptyList());
        JSONObject json = entry.toJson();
        assertEquals("stop", json.getJSONObject("rules").getString("payPer"));
        assertEquals(2, json.getJSONObject("rules").getJSONObject("accepted").getInt("payUnits"));
        DecisionLog.Entry back = DecisionLog.Entry.fromJson(json);
        assertNotNull(back.scoringRules);
        assertEquals(rules.scoringKey(), back.scoringRules.scoringKey());
        assertEquals(2, back.scoringRules.accepted.payUnits);

        json.getJSONObject("rules").remove("payPer");
        assertNull("judged by the minimum pay: no replayable rules", DecisionLog.Entry.fromJson(json).scoringRules);
    }

    @Test public void theKnobAndTheReasonSayPerStop() {
        assertEquals("$13.00/stop", MinimumsStarView.readout(AreaScore.PAY, 1300));
        assertEquals("Pay / stop", MinimumsStarView.AXIS_LABELS[AreaScore.PAY]);
        assertEquals("Below your pay per stop", MainActivity.plainReason(OfferRule.PAY_PER_STOP));
        assertEquals("Below Autopilot's 119% of your pay per stop",
                MainActivity.plainReason("119% bar: " + OfferRule.PAY_PER_STOP));
        assertEquals("Below your minimum pay", MainActivity.plainReason("flat minimum"));
    }

    @Test public void adoptSetsPayPerStopFromTheBest() {
        install0611(1000, 0);
        FilterStore.load(app);
        prefs().edit().putInt(FilterStore.ACCEPTED_PAY, 2601).putInt(FilterStore.ACCEPTED_PAY_UNITS, 2).commit();
        assertTrue(FilterStore.adoptAccepted(app));
        assertEquals("⌈($26.01 + 1¢) ÷ 2⌉", 1301, FilterStore.load(app).flatCents);
    }
}
