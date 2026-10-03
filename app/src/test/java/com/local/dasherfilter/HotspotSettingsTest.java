package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Legacy preferences stay off; fixed hotspot rules and numeric observations survive safe copies and persistence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public final class HotspotSettingsTest {
    private Application app;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE).edit().clear().commit();
        DecisionLog.forgetCache();
    }

    @Test public void legacyPreferencesAndConstructorsLeaveFifthSpokeDisabled() {
        app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", true).putInt("flat", 500).apply();
        FilterSettings legacy = FilterStore.load(app);
        assertEquals(0, legacy.hotspotProximityHundredths);
        assertArrayEquals(new int[] {500, 0, 0, 0, 0, 0}, legacy.minimums());
        assertEquals(0, new FilterSettings(true, 1, 2, 3, 4, 5, true, 6,
                AcceptedBest.NONE, DeclinedFloor.NONE, true).hotspotProximityHundredths);
    }

    @Test public void saveReloadAndAllRuleCopiesPreserveTheIndependentMinimum() {
        FilterSettings original = new FilterSettings(true, 500, 100, 20, 200, 4)
                .withHotspotProximity(75);
        FilterStore.save(app, original);
        FilterSettings saved = FilterStore.load(app);
        assertEquals(75, saved.hotspotProximityHundredths);
        assertArrayEquals(new int[] {500, 100, 20, 200, 75, 0}, saved.minimums());
        for (FilterSettings copy : new FilterSettings[] {saved.withEnabled(false), saved.withScoreByArea(true),
                saved.withMaxStops(2), saved.withAdaptive(true), saved.withoutRisingBaseline(),
                saved.withMinimums(new int[] {600, 110, 30, 210}), saved.adoptAdaptive()}) {
            assertEquals(75, copy.hotspotProximityHundredths);
        }
        assertEquals(90, saved.withMinimums(new int[] {600, 110, 30, 210, 90}).hotspotProximityHundredths);
        FilterStore.save(app, saved.withHotspotProximity(0));
        assertEquals(0, FilterStore.load(app).hotspotProximityHundredths);
    }

    @Test public void onlyFifthSpokeIsARealRuleButNeverAMarginalPayRule() {
        FilterSettings rules = new FilterSettings(true, 0, 0, 0, 0, 0).withHotspotProximity(50);
        assertTrue(rules.hasAnyRule());
        assertFalse(rules.hasMarginalRule());
        assertFalse(rules.withHotspotProximity(0).hasAnyRule());
        assertEquals(0, rules.withHotspotProximity(-1).hotspotProximityHundredths);
    }

    @Test public void learningAdoptingAndResettingDoNotAlterTheFixedHotspotRule() {
        FilterSettings rules = new FilterSettings(true, 0, 0, 0, 0, 0, true, 0).withHotspotProximity(50);
        FilterStore.save(app, rules);
        assertTrue(FilterStore.recordAccepted(app,
                new OfferSnapshot(1000, 4.0, 20, 2).withFinalStopHotspotMiles(0.1)));
        FilterSettings learned = FilterStore.load(app);
        assertEquals(50, learned.hotspotProximityHundredths);
        assertEquals(1000, learned.lastAcceptedCents);
        FilterStore.save(app, learned.adoptAdaptive());
        assertEquals(50, FilterStore.load(app).hotspotProximityHundredths);
        FilterStore.resetAccepted(app);
        FilterSettings reset = FilterStore.load(app);
        assertEquals(50, reset.hotspotProximityHundredths);
        assertEquals(0, reset.lastAcceptedCents);
    }

    @Test public void decisionHistoryRoundTripsOnlyTheNumericDistanceAndSupportsOldEntries() throws Exception {
        OfferSnapshot offer = new OfferSnapshot(1000, 4.0, 20, 2).withFinalStopHotspotMiles(0.25);
        DecisionLog.Entry entry = new DecisionLog.Entry(1000, DecisionLog.Source.SCREEN, false, offer, 0,
                OfferRule.Result.KEEP, "meets enabled rules", DecisionLog.Action.PASSES, true,
                Collections.emptyList());
        JSONObject json = entry.toJson();
        assertEquals(0.25, json.getDouble("finalStopHotspotMiles"), 0);
        assertEquals(Double.valueOf(0.25), DecisionLog.Entry.fromJson(json).facts.finalStopHotspotMiles);
        assertFalse(json.has("latitude"));
        assertFalse(json.has("longitude"));
        assertTrue(DecisionLog.facts(entry.facts).contains("0.25 mi from final stop to nearest hotspot"));
        json.remove("finalStopHotspotMiles");
        assertNull(DecisionLog.Entry.fromJson(json).facts.finalStopHotspotMiles);
        json.put("finalStopHotspotMiles", -1);
        assertNull(DecisionLog.Entry.fromJson(json).facts.finalStopHotspotMiles);
        json.put("finalStopHotspotMiles", "unavailable");
        assertNull(DecisionLog.Entry.fromJson(json).facts.finalStopHotspotMiles);
    }
}
