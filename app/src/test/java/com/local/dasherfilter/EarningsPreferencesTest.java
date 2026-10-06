package com.local.dasherfilter;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public final class EarningsPreferencesTest {
    private Context app;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("earnings_optimizer", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void legacyScaleMapsToAQuietInitialTradeoffWithoutOptingIn() {
        assertEquals(50, EarningsPreferences.tradeoff(app, 100));
        assertEquals(47, EarningsPreferences.tradeoff(app, 97));
        assertFalse(EarningsPreferences.chosen(app));
        assertFalse(EarningsPreferences.autoTune(app));
    }

    @Test public void explicitTradeoffPersistsButDoesNotSilentlyEnableAutoTune() {
        EarningsPreferences.setTradeoff(app, 72);
        assertTrue(EarningsPreferences.chosen(app));
        assertEquals(72, EarningsPreferences.tradeoff(app, 100));
        assertFalse(EarningsPreferences.autoTune(app));
    }

    @Test public void enablingAutoTuneKeepsTheExistingBoundaryAsItsInitialPreference() {
        EarningsPreferences.setAutoTune(app, true, 97);
        assertTrue(EarningsPreferences.autoTune(app));
        assertTrue(EarningsPreferences.chosen(app));
        assertEquals(47, EarningsPreferences.tradeoff(app, 100));
        EarningsPreferences.setAutoTune(app, false, 100);
        EarningsPreferences.setTradeoff(app, 80);
        assertFalse(EarningsPreferences.autoTune(app));
    }

    @Test public void vehicleCostIsLocalAndBounded() {
        assertEquals(0, EarningsPreferences.vehicleCostCentsPerMile(app));
        EarningsPreferences.setVehicleCostCentsPerMile(app, 37);
        assertEquals(37, EarningsPreferences.vehicleCostCentsPerMile(app));
        EarningsPreferences.setVehicleCostCentsPerMile(app, 9999);
        assertEquals(500, EarningsPreferences.vehicleCostCentsPerMile(app));
    }

    @Test public void fallbackKeepsBalancedAtTheExistingHundredPercentBoundary() {
        assertEquals(50, EarningsPreferences.fallbackScale(0));
        assertEquals(100, EarningsPreferences.fallbackScale(50));
        assertEquals(200, EarningsPreferences.fallbackScale(100));
        assertEquals(50, EarningsPreferences.inferredTradeoff(100));
    }
}
