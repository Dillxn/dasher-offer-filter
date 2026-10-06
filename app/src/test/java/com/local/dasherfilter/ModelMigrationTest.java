package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The 0.5.0 move to three minimums (rules model 2) on real stored preferences: per stop folded into minimum pay, the
 * old scale built in and rounded up, the 25 retired keys gone, auto-accept off once when rules changed meaning, a pause
 * when no rule is left, one notice and one log line; idempotent and crash-safe. Then the rules' new writers: save()
 * never writes the bar, Autopilot's compare-and-set, and turning Autopilot off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class ModelMigrationTest {
    /** Every key 0.5.0 retires, written out here independently of the code under test. */
    private static final List<String> RETIRED = Arrays.asList(
            "per_stop", "per_item", "hotspot_proximity_hundredths", "minimum_scale_percent", "rising_offers",
            "score_by_area",
            "last_accepted", "best_minute_pay", "best_minutes", "best_mile_pay", "best_miles_bits", "best_stop_pay",
            "best_stops", "best_item_pay", "best_items", "declined_pay", "declined_minute_pay", "declined_minutes",
            "declined_mile_pay", "declined_miles_bits", "declined_stop_pay", "declined_stops",
            "learning_on_since", "learning_off_at", "adaptive_reset_at");

    private Application app;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        prefs().edit().clear().commit();
        app.getSharedPreferences(FilterStore.RETIRED_MANUAL_DECLINES, Context.MODE_PRIVATE).edit().clear().commit();
        DiagnosticLog.forgetCache();
        DiagnosticLog.clear(app);
        DiagnosticLog.setEnabled(app, true);
        FilterStore.migrationInterruptForTests = null;
    }

    @After public void tearDown() {
        FilterStore.migrationInterruptForTests = null;
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
    }

    /** What 0.4.73 left on the owner's phone (Oct 5–6): his shape, area mode, an 80% buffer and learned values. */
    private void ownerShape() {
        prefs().edit()
                .putBoolean("enabled", true).putInt("flat", 1950).putInt("mile", 500).putInt("minute", 60)
                .putInt("per_stop", 1275).putInt("per_item", 1790).putInt("hotspot_proximity_hundredths", 0)
                .putInt("minimum_scale_percent", 80).putInt("max_stops", 0)
                .putBoolean("rising_offers", true).putBoolean("score_by_area", true)
                .putInt("last_accepted", 1785)
                .putInt("best_minute_pay", 1800).putInt("best_minutes", 30)
                .putInt("best_mile_pay", 1350).putLong("best_miles_bits", Double.doubleToLongBits(3.3))
                .putInt("best_stop_pay", 940).putInt("best_stops", 2)
                .putInt("best_item_pay", 1785).putInt("best_items", 1)
                .putInt("declined_pay", 1300).putInt("declined_minute_pay", 0).putInt("declined_minutes", 0)
                .putInt("declined_mile_pay", 0).putLong("declined_miles_bits", 0)
                .putInt("declined_stop_pay", 790).putInt("declined_stops", 2)
                .putLong("learning_on_since", 1_759_300_000_000L).putLong("learning_off_at", 1_759_200_000_000L)
                .putLong("adaptive_reset_at", 1_759_100_000_000L)
                .putBoolean("auto_accept_matching_offers", true)
                .putBoolean("peek_background_offers", false).putBoolean("silence_while_declining", false)
                .putString("doordash_offer_channel", "offers")
                .commit();
        app.getSharedPreferences(FilterStore.RETIRED_MANUAL_DECLINES, Context.MODE_PRIVATE).edit()
                .putInt("pay", 1300).putLong("at", 1_759_400_000_000L).commit();
    }

    /** A 0.4.73 save of plain strict rules: every key written, the retired ones at their defaults. */
    private void plainSave(boolean enabled, int flat, int mile, int minute, int perStop, int maxStops, int scale) {
        prefs().edit()
                .putBoolean("enabled", enabled).putInt("flat", flat).putInt("mile", mile).putInt("minute", minute)
                .putInt("per_stop", perStop).putInt("per_item", 0).putInt("hotspot_proximity_hundredths", 0)
                .putInt("minimum_scale_percent", scale).putInt("max_stops", maxStops)
                .putBoolean("rising_offers", false).putBoolean("score_by_area", false)
                .commit();
    }

    private String log() {
        return DiagnosticLog.read(app);
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test public void theRetiredKeysAreExactlyTheTwentyFive() {
        assertEquals(25, RETIRED.size());
        assertEquals(RETIRED, FilterStore.RETIRED_KEYS);
    }

    @Test public void theOwnersShapeBecomesTwentyDollarsFortyFourPerMileAndFortyEightCentsAMinute() throws Exception {
        ownerShape();
        java.io.File held = new java.io.File(app.getDataDir(),
                "shared_prefs/" + FilterStore.RETIRED_MANUAL_DECLINES + ".xml");
        assertTrue("the old version's held hand-decline record is on disk", held.exists());
        FilterSettings rules = FilterStore.load(app);
        assertFalse("and is deleted with the learning it fed", held.exists());

        // Per stop $12.75 folds into minimum pay as $25.50; the 80% buffer is built in, rounded up.
        assertEquals(2040, rules.flatCents);
        assertEquals(400, rules.perMileCents);
        assertEquals(48, rules.perMinuteCents);
        assertEquals(2880, rules.perHourCents());
        assertEquals(0, rules.maxStops);
        assertTrue("enabled is kept", rules.enabled);
        assertFalse(rules.autopilot);
        assertEquals(70, rules.autopilotGoalPercent);
        assertEquals(100, rules.minimumScalePercent);

        Map<String, ?> stored = prefs().getAll();
        for (String key : RETIRED) assertFalse(key, stored.containsKey(key));
        assertEquals(2, stored.get("rules_model"));
        assertEquals(false, stored.get("autopilot_on"));
        assertEquals(70, stored.get("autopilot_goal"));
        assertEquals(100, stored.get("autopilot_bar_percent"));
        assertFalse("the goal was never asked", stored.containsKey("autopilot_goal_asked"));
        assertFalse("auto-accept is switched off once", FilterStore.autoAcceptEnabled(app));
        assertEquals("other settings are untouched", false, stored.get("peek_background_offers"));
        assertEquals(false, stored.get("silence_while_declining"));
        assertEquals("offers", stored.get("doordash_offer_channel"));

        assertEquals("{\"area\":true,\"adaptive\":true,\"perStop\":1275,\"foldedFlat\":2550,\"flatBefore\":1950,"
                + "\"buffer\":80,\"newFlat\":2040,\"newMile\":400,\"newMinute\":48,\"perItem\":1790,\"hotspot\":0,"
                + "\"autoAcceptOff\":true,\"paused\":false}", FilterStore.peekModelNotice(app));
        assertTrue(FilterStore.lastStatus(app), FilterStore.lastStatus(app).endsWith("\nRules simplified for 0.5.0"));
        assertTrue("the held hand-decline record is deleted",
                app.getSharedPreferences(FilterStore.RETIRED_MANUAL_DECLINES, Context.MODE_PRIVATE).getAll().isEmpty());
        String log = log();
        assertTrue(log, log.contains("[rules] rules model 2: flat 1950 -> 2040 (per stop 1275 folded, buffer 80% "
                + "built in); mile 500 -> 400; minute 60 -> 48; retired: per item 1790, score by area, adaptive "
                + "(learned values cleared), hotspot no; auto-accept turned off; paused no\n"));
        assertEquals(log, 1, count(log, "[rules]"));
    }

    @Test public void readmeRulesAtOneHundredPercentStayAsTheyWereWithThePerStopNotice() throws Exception {
        plainSave(true, 1300, 385, 41, 475, 3, 100);
        FilterSettings rules = FilterStore.load(app);
        assertEquals(1300, rules.flatCents);
        assertEquals(385, rules.perMileCents);
        assertEquals(41, rules.perMinuteCents);
        assertEquals(3, rules.maxStops);
        assertTrue(rules.enabled);
        JSONObject notice = new JSONObject(FilterStore.peekModelNotice(app));
        assertEquals(475, notice.getInt("perStop"));
        assertEquals("2 × $4.75 = $9.50 is below $13.00: the fold changes nothing", 1300,
                notice.getInt("foldedFlat"));
        assertEquals(1300, notice.getInt("flatBefore"));
        assertEquals(100, notice.getInt("buffer"));
        assertFalse(notice.getBoolean("area"));
        assertFalse(notice.getBoolean("adaptive"));
        assertFalse(notice.getBoolean("autoAcceptOff"));
        assertFalse(notice.getBoolean("paused"));
        assertTrue(log(), log().contains("rules model 2: flat 1300 -> 1300 (per stop 475 folded); mile 385 -> 385; "
                + "minute 41 -> 41; retired: per item no, score by area no, adaptive no, hotspot no; auto-accept "
                + "unchanged; paused no"));
    }

    @Test public void anOldScaleIsBuiltIntoEveryMinimumRoundedUp() {
        plainSave(true, 1300, 385, 41, 0, 0, 90);
        FilterSettings rules = FilterStore.load(app);
        assertEquals(1170, rules.flatCents);
        assertEquals("⌈385 × 0.9⌉ = ⌈346.5⌉", 347, rules.perMileCents);
        assertEquals("⌈41 × 0.9⌉ = ⌈36.9⌉", 37, rules.perMinuteCents);
        assertEquals(100, rules.minimumScalePercent);
        assertEquals(90, readNotice().optInt("buffer"));

        prefs().edit().clear().commit();
        plainSave(true, 0, 385, 0, 0, 0, 97);
        assertEquals("⌈385 × 0.97⌉ = ⌈373.45⌉", 374, FilterStore.load(app).perMileCents);
    }

    @Test public void perStopFoldsIntoMinimumPayAsTwicePerStop() {
        plainSave(true, 1300, 0, 0, 800, 0, 100);
        assertEquals(1600, FilterStore.load(app).flatCents);
        prefs().edit().clear().commit();
        plainSave(true, 700, 0, 0, 250, 0, 100);
        assertEquals("2 × $2.50 is below $7.00", 700, FilterStore.load(app).flatCents);
        prefs().edit().clear().commit();
        plainSave(true, 0, 0, 0, 475, 0, 100);
        FilterSettings perStopOnly = FilterStore.load(app);
        assertEquals("a per-stop-only rule is now minimum pay", 950, perStopOnly.flatCents);
        assertTrue("so a rule is left and nothing pauses", perStopOnly.enabled);
    }

    @Test public void theArithmeticIsExactAndNeverLooser() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("flat", 1950);
        stored.put("mile", 500);
        stored.put("minute", 60);
        stored.put("per_stop", 1275);
        stored.put("minimum_scale_percent", 80);
        FilterStore.Migration owner = FilterStore.Migration.of(stored);
        assertEquals(2550, owner.foldedFlat);
        assertArrayEquals(new int[] {2040, 400, 48}, new int[] {owner.newFlat, owner.newMile, owner.newMinute});
        assertFalse(owner.fresh);
        assertTrue(owner.retiredInUse);

        assertEquals(0, FilterStore.Migration.bake(0, 80));
        assertEquals("a cent stays at least a cent", 1, FilterStore.Migration.bake(1, 1));
        assertEquals(100_000, FilterStore.Migration.bake(100_000, 200));
        assertEquals(60_000, FilterStore.Migration.bake(30_000, 200));
        for (int cents = 1; cents <= 2_000; cents++) {
            for (int scale = 1; scale <= 200; scale += 7) {
                int baked = FilterStore.Migration.bake(cents, scale);
                // Never below the old effective requirement x·scale/100, and never a whole cent above it.
                assertTrue(cents + "@" + scale, 100L * baked >= (long) cents * scale);
                assertTrue(cents + "@" + scale, 100L * (baked - 1) < (long) cents * scale);
            }
        }
        stored.put("per_stop", 60_000);
        stored.put("minimum_scale_percent", 100);
        assertEquals("the fold is capped at the knob's most", 100_000, FilterStore.Migration.of(stored).newFlat);
        stored.put("minimum_scale_percent", "not a number");
        assertEquals("a scale no version wrote is no scale", 100, FilterStore.Migration.of(stored).scale);
        stored.put("minimum_scale_percent", 0);
        assertEquals(1, FilterStore.Migration.of(stored).scale);
        stored.put("minimum_scale_percent", 900);
        assertEquals(200, FilterStore.Migration.of(stored).scale);
        stored.clear();
        stored.put("flat", -5);
        stored.put("mile", 250_000);
        FilterStore.Migration odd = FilterStore.Migration.of(stored);
        assertEquals(0, odd.newFlat);
        assertEquals(100_000, odd.newMile);
    }

    @Test public void aHotspotOnlyOrAdaptiveOnlyFilterIsPaused() throws Exception {
        plainSave(true, 0, 0, 0, 0, 0, 100);
        prefs().edit().putInt("hotspot_proximity_hundredths", 50).commit();
        FilterSettings hotspot = FilterStore.load(app);
        assertFalse("no rule is left, so auto-decline is paused", hotspot.enabled);
        assertFalse(hotspot.hasAnyRule());
        JSONObject notice = readNotice();
        assertTrue(notice.getBoolean("paused"));
        assertEquals(50, notice.getInt("hotspot"));
        assertTrue(log(), log().contains("hotspot 50; auto-accept unchanged; paused yes"));

        prefs().edit().clear().commit();
        plainSave(true, 0, 0, 0, 0, 0, 100);
        prefs().edit().putBoolean("rising_offers", true).putInt("last_accepted", 1500).commit();
        FilterSettings adaptive = FilterStore.load(app);
        assertFalse(adaptive.enabled);
        notice = readNotice();
        assertTrue(notice.getBoolean("adaptive"));
        assertTrue(notice.getBoolean("paused"));

        // Paused already, nothing to pause: no "paused" in the notice.
        prefs().edit().clear().commit();
        plainSave(false, 0, 0, 0, 0, 0, 100);
        prefs().edit().putInt("per_item", 300).commit();
        assertFalse(FilterStore.load(app).enabled);
        assertFalse(readNotice().getBoolean("paused"));
        assertEquals(300, readNotice().getInt("perItem"));
    }

    @Test public void onlyMaxStopsLeftTurnsAutoAcceptOffButKeepsTheFilterOn() throws Exception {
        plainSave(true, 0, 0, 0, 0, 3, 100);
        FilterStore.setAutoAcceptEnabled(app, true);
        FilterSettings rules = FilterStore.load(app);
        assertTrue("max stops is still a rule", rules.enabled);
        assertEquals(3, rules.maxStops);
        assertFalse(FilterStore.autoAcceptEnabled(app));
        JSONObject notice = readNotice();
        assertTrue(notice.getBoolean("autoAcceptOff"));
        assertFalse(notice.getBoolean("paused"));
    }

    @Test public void plainStrictRulesAtOneHundredPercentKeepAutoAcceptAndGetNoNotice() {
        plainSave(true, 700, 150, 40, 0, 0, 100);
        FilterStore.setAutoAcceptEnabled(app, true);
        FilterStore.setLastStatus(app, "Declined an offer.");
        FilterSettings rules = FilterStore.load(app);
        assertArrayEquals(new int[] {700, 150, 40, 0, 0, 0}, rules.minimums());
        assertTrue(rules.enabled);
        assertTrue("nothing changed meaning: auto-accept stays on", FilterStore.autoAcceptEnabled(app));
        assertNull(FilterStore.peekModelNotice(app));
        assertTrue(FilterStore.lastStatus(app).endsWith("\nDeclined an offer."));
        for (String key : RETIRED) assertFalse(key, prefs().contains(key));
        assertTrue("an install that had rules logs the move once", log().contains("[rules] rules model 2: flat 700 "
                + "-> 700; mile 150 -> 150; minute 40 -> 40; retired: per item no, score by area no, adaptive no, "
                + "hotspot no; auto-accept unchanged; paused no"));
    }

    @Test public void anyLearnedValueOrAScaleTurnsAutoAcceptOffOnce() {
        plainSave(true, 700, 0, 0, 0, 0, 100);
        prefs().edit().putInt("best_mile_pay", 1350).commit();
        FilterStore.setAutoAcceptEnabled(app, true);
        FilterStore.load(app);
        assertFalse(FilterStore.autoAcceptEnabled(app));
        // Turned on again after the move: it stays on.
        FilterStore.setAutoAcceptEnabled(app, true);
        FilterStore.load(app);
        assertTrue(FilterStore.autoAcceptEnabled(app));

        prefs().edit().clear().commit();
        plainSave(true, 700, 0, 0, 0, 0, 99);
        FilterStore.setAutoAcceptEnabled(app, true);
        FilterStore.load(app);
        assertFalse(FilterStore.autoAcceptEnabled(app));
    }

    @Test public void aFreshInstallGetsTheDefaultsAndNoNoticeOrLogLine() {
        prefs().edit().putBoolean("peek_background_offers", false).commit();
        FilterSettings rules = FilterStore.load(app);
        assertFalse(rules.enabled);
        assertFalse(rules.hasAnyRule());
        assertFalse(rules.autopilot);
        assertEquals(70, rules.autopilotGoalPercent);
        assertEquals(100, rules.minimumScalePercent);
        Map<String, ?> stored = prefs().getAll();
        assertEquals(2, stored.get("rules_model"));
        assertEquals(false, stored.get("autopilot_on"));
        assertEquals(70, stored.get("autopilot_goal"));
        assertEquals(100, stored.get("autopilot_bar_percent"));
        assertFalse("no rule is invented", stored.containsKey("flat") || stored.containsKey("enabled"));
        assertNull(FilterStore.peekModelNotice(app));
        assertEquals("No offer evaluated yet.", FilterStore.lastStatus(app));
        assertFalse(log(), log().contains("[rules]"));
    }

    @Test public void itRunsOnceAndAgainChangesNothing() {
        ownerShape();
        FilterStore.load(app);
        Map<String, ?> after = new HashMap<>(prefs().getAll());
        for (int i = 0; i < 3; i++) {
            assertEquals(2040, FilterStore.load(app).flatCents);
            FilterStore.save(app, FilterStore.load(app));
            FilterStore.peekModelNotice(app);
        }
        assertEquals(after, prefs().getAll());
        assertEquals(log(), 1, count(log(), "[rules]"));
    }

    @Test public void aKeyAnOlderVersionWritesAfterADowngradeIsMovedAgain() throws Exception {
        plainSave(true, 1300, 385, 41, 0, 0, 100);
        FilterStore.load(app);
        FilterStore.setAutopilot(app, true, 50);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 90));
        FilterStore.dismissModelNotice(app);
        // 0.4.73 again: it saves every key it knows, per stop $8.00 among them, and knows nothing of Autopilot.
        plainSave(true, 1300, 385, 41, 800, 0, 100);
        FilterSettings rules = FilterStore.load(app);
        assertEquals(1600, rules.flatCents);
        assertEquals(385, rules.perMileCents);
        for (String key : RETIRED) assertFalse(key, prefs().contains(key));
        assertTrue("Autopilot's own settings are kept", rules.autopilot);
        assertEquals(50, rules.autopilotGoalPercent);
        assertEquals(90, rules.minimumScalePercent);
        assertEquals(800, readNotice().getInt("perStop"));
        assertEquals(1600, readNotice().getInt("foldedFlat"));
        assertEquals(log(), 2, count(log(), "[rules]"));
    }

    @Test public void aCrashBeforeTheEditLandsLeavesTheOldRulesAndTheNextLoadFinishes() {
        ownerShape();
        Map<String, ?> before = new HashMap<>(prefs().getAll());
        FilterStore.migrationInterruptForTests = () -> {
            throw new IllegalStateException("process died");
        };
        try {
            FilterStore.load(app);
            fail("the interrupted load cannot finish");
        } catch (IllegalStateException expected) {
            // As if the process had died there.
        }
        assertEquals("nothing of the edit was applied", before, prefs().getAll());
        assertFalse(log(), log().contains("[rules]"));

        FilterStore.migrationInterruptForTests = null;
        FilterSettings rules = FilterStore.load(app);
        assertArrayEquals(new int[] {2040, 400, 48, 0, 0, 0}, rules.minimums());
        for (String key : RETIRED) assertFalse(key, prefs().contains(key));
        assertEquals(2, prefs().getInt("rules_model", 0));
        assertFalse(FilterStore.autoAcceptEnabled(app));
        assertNotNull(FilterStore.peekModelNotice(app));
        assertTrue(app.getSharedPreferences(FilterStore.RETIRED_MANUAL_DECLINES, Context.MODE_PRIVATE)
                .getAll().isEmpty());
        assertEquals(log(), 1, count(log(), "[rules]"));
    }

    @Test public void aSaveBeforeTheFirstLoadIsNeverFoldedOrScaledAgain() {
        ownerShape();
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        FilterSettings rules = FilterStore.load(app);
        assertArrayEquals("saved as model 2, after the move", new int[] {400, 100, 25, 0, 0, 0}, rules.minimums());
        for (String key : RETIRED) assertFalse(key, prefs().contains(key));
    }

    @Test public void theNoticeSurvivesUntilItIsAnswered() {
        ownerShape();
        FilterStore.load(app);
        String notice = FilterStore.peekModelNotice(app);
        assertNotNull(notice);
        assertEquals("a recreated homepage shows it again", notice, FilterStore.peekModelNotice(app));
        FilterStore.load(app);
        assertEquals(notice, FilterStore.peekModelNotice(app));
        FilterStore.dismissModelNotice(app);
        assertNull(FilterStore.peekModelNotice(app));
        FilterStore.load(app);
        assertNull(FilterStore.peekModelNotice(app));
    }

    @Test public void theNoticeIsReadyBeforeAnyLoad() {
        ownerShape();
        assertNotNull("peeking first runs the move", FilterStore.peekModelNotice(app));
        assertEquals(2040, FilterStore.load(app).flatCents);
    }

    // ---- The rules' writers after the move ----

    @Test public void saveNeverWritesTheBarSoAStaleCopyCannotRestoreOne() {
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        FilterStore.setAutopilot(app, true, 70);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
        FilterSettings stale = FilterSettings.of(true, 400, 100, 25, 0)
                .withAutopilot(true, 70).withMinimumScalePercent(130);
        List<String> changed = new ArrayList<>();
        SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> changed.add(key);
        prefs().registerOnSharedPreferenceChangeListener(listener);
        try {
            FilterStore.save(app, stale.withMinimums(500, 100, 25));
            FilterStore.save(app, FilterSettings.of(true, 500, 100, 25, 3));
        } finally {
            prefs().unregisterOnSharedPreferenceChangeListener(listener);
        }
        assertFalse(changed.toString(), changed.contains("autopilot_bar_percent"));
        assertFalse(changed.toString(), changed.contains("autopilot_on"));
        assertFalse(changed.toString(), changed.contains("autopilot_goal"));
        FilterSettings rules = FilterStore.load(app);
        assertEquals(82, rules.minimumScalePercent);
        assertEquals(82, prefs().getInt("autopilot_bar_percent", -1));
        assertTrue(rules.autopilot);
        assertEquals(500, rules.flatCents);
        assertEquals(3, rules.maxStops);

        // Saving rules with Autopilot off in the copy does not turn it off either.
        FilterStore.save(app, FilterSettings.of(false, 500, 100, 25, 3));
        assertTrue(FilterStore.load(app).autopilot);
        assertEquals(82, FilterStore.load(app).minimumScalePercent);
        assertFalse(FilterStore.load(app).enabled);
    }

    @Test public void theBarIsCompareAndSetAndOnlyWhileAutopilotIsOn() {
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        assertFalse("off: nothing moves", FilterStore.commitAutopilotBar(app, 100, 82));
        assertEquals(100, FilterStore.load(app).minimumScalePercent);
        FilterStore.setAutopilot(app, true, 70);
        assertEquals(100, FilterStore.load(app).minimumScalePercent);
        assertFalse("the bar is not what was expected", FilterStore.commitAutopilotBar(app, 95, 82));
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
        assertEquals(82, FilterStore.load(app).minimumScalePercent);
        assertFalse("a stale expectation loses", FilterStore.commitAutopilotBar(app, 100, 90));
        assertFalse("below the lowest bar", FilterStore.commitAutopilotBar(app, 82, 49));
        assertFalse("above the highest bar", FilterStore.commitAutopilotBar(app, 82, 151));
        assertEquals(82, FilterStore.load(app).minimumScalePercent);
        assertTrue(FilterStore.commitAutopilotBar(app, 82, 150));
        assertTrue(FilterStore.commitAutopilotBar(app, 150, 50));
        assertEquals(50, FilterStore.load(app).minimumScalePercent);

        FilterStore.setAutopilot(app, false, 70);
        FilterSettings off = FilterStore.load(app);
        assertFalse(off.autopilot);
        assertEquals("off puts the bar back at exactly the minimums", 100, off.minimumScalePercent);
        assertEquals(100, prefs().getInt("autopilot_bar_percent", -1));
        assertFalse(FilterStore.commitAutopilotBar(app, 100, 82));
        assertFalse(FilterStore.commitAutopilotBar(app, 50, 82));
    }

    @Test public void turningAutopilotOnOrChangingItsGoalMarksTheGoalAsked() {
        assertFalse(FilterStore.goalAsked(app));
        FilterStore.setAutopilot(app, true, 33);
        FilterSettings on = FilterStore.load(app);
        assertTrue(on.autopilot);
        assertEquals("an unknown goal is the default", 70, on.autopilotGoalPercent);
        assertTrue(FilterStore.goalAsked(app));
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 112));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        FilterSettings payFirst = FilterStore.load(app);
        assertEquals(0, payFirst.autopilotGoalPercent);
        assertEquals("a new goal keeps the bar until Autopilot moves it", 112, payFirst.minimumScalePercent);
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_TIER);
        assertEquals(50, FilterStore.load(app).autopilotGoalPercent);
        FilterStore.setGoalAsked(app, false);
        assertFalse(FilterStore.goalAsked(app));
        // On again from off: the bar starts at exactly the minimums.
        prefs().edit().putInt("autopilot_bar_percent", 77).commit();
        FilterStore.setAutopilot(app, true, 70);
        assertEquals(100, FilterStore.load(app).minimumScalePercent);
    }

    @Test public void aStoredBarIsReadWithinItsRangeAndOnlyWhileOn() {
        FilterStore.load(app);
        prefs().edit().putBoolean("autopilot_on", true).putInt("autopilot_bar_percent", 300)
                .putInt("autopilot_goal", 42).commit();
        FilterSettings high = FilterStore.load(app);
        assertEquals(150, high.minimumScalePercent);
        assertEquals(70, high.autopilotGoalPercent);
        assertTrue("compare-and-set compares what load reads", FilterStore.commitAutopilotBar(app, 150, 120));
        prefs().edit().putInt("autopilot_bar_percent", 10).commit();
        assertEquals(50, FilterStore.load(app).minimumScalePercent);
        prefs().edit().putBoolean("autopilot_on", false).putInt("autopilot_bar_percent", 82).commit();
        assertEquals(100, FilterStore.load(app).minimumScalePercent);
    }

    @Test public void anExtraStopFeeFromBeforePerStopIsRetiredInWordsTrueToTheseRules() {
        // A phone that last ran a version from before per stop, updating straight to 0.5.0 (the feed allows it).
        prefs().edit().putBoolean("enabled", true).putInt("flat", 700).putInt("mile", 150).putInt("stop", 200)
                .putInt("max_stops", 3).commit();
        FilterSettings rules = FilterStore.load(app);
        assertArrayEquals("the fee is read as no rule", new int[] {700, 150, 0, 0, 0, 0}, rules.minimums());
        assertEquals(3, rules.maxStops);
        assertTrue(rules.enabled);
        assertFalse(prefs().contains("stop"));
        String notice = FilterStore.takeStopFeeNotice(app);
        assertEquals("Your $2.00 extra-stop fee was removed; it is not a rule any more. Use Max stops to limit "
                + "stacked orders.", notice);
        assertFalse("0.5.0 has no per-stop minimum to set", notice.contains("Per stop"));
        assertNull("shown once", FilterStore.takeStopFeeNotice(app));
        assertNull("the rules' meaning did not change: no model notice", FilterStore.peekModelNotice(app));
        assertEquals(1, count(log(), "the old extra-stop fee of $2.00 was retired; it is not a rule any more (max "
                + "stops limits stacked orders)"));
        assertFalse(log().contains("per stop is now a minimum"));

        // The fee was the only rule: auto-decline pauses in the same edit, and both texts say so.
        prefs().edit().clear().commit();
        DiagnosticLog.clear(app);
        prefs().edit().putBoolean("enabled", true).putInt("stop", 300).commit();
        assertFalse(FilterStore.load(app).enabled);
        assertEquals("Your $3.00 extra-stop fee was removed; it is not a rule any more. Use Max stops to limit "
                + "stacked orders. It was your only rule, so auto-decline is paused.",
                FilterStore.takeStopFeeNotice(app));
        assertTrue(log().contains("the old extra-stop fee of $3.00 was retired; it is not a rule any more (max stops "
                + "limits stacked orders); no rule was left, so auto-decline was paused"));

        // A fee of 0 was never a rule: it goes without a word.
        prefs().edit().clear().commit();
        prefs().edit().putBoolean("enabled", true).putInt("flat", 500).putInt("stop", 0).commit();
        assertTrue(FilterStore.load(app).enabled);
        assertNull(FilterStore.takeStopFeeNotice(app));
    }

    @Test public void theRetiredLearningIsInert() {
        FilterStore.save(app, FilterSettings.of(true, 1000, 0, 0, 0));
        OfferSnapshot offer = new OfferSnapshot(2500, 7.2, 21, 2);
        Map<String, ?> before = new HashMap<>(prefs().getAll());
        assertFalse(FilterStore.recordAccepted(app, offer));
        assertEquals(FilterStore.AcceptedLesson.SWITCHES_OFF, FilterStore.recordAcceptedLesson(app, offer));
        assertEquals(FilterStore.DeclineLesson.SWITCHES_OFF, FilterStore.learnFromDecline(app, offer));
        FilterStore.resetAccepted(app);
        assertArrayEquals(new long[] {0, 0, 0}, FilterStore.learningTimes(app));
        assertEquals("nothing is stored", before, prefs().getAll());
        assertArrayEquals(new int[] {1000, 0, 0, 0, 0, 0}, FilterStore.load(app).minimums());
    }

    private JSONObject readNotice() {
        try {
            return new JSONObject(FilterStore.peekModelNotice(app));
        } catch (Exception malformed) {
            throw new AssertionError(malformed);
        }
    }
}
