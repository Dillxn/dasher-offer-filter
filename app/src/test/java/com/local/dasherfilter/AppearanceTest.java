package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
public class AppearanceTest {
    private Application app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private long at(String utc) { return Instant.parse(utc).toEpochMilli(); }

    private void location(long age) {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        Location fix = new Location(LocationManager.NETWORK_PROVIDER);
        fix.setLatitude(39.1);
        fix.setLongitude(-84.5);
        fix.setAccuracy(1500);
        fix.setTime(System.currentTimeMillis() - age);
        fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - age * 1_000_000L);
        Shadows.shadowOf(app.getSystemService(LocationManager.class))
                .setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix);
    }

    @Test public void freshDefaultIsAutoAndExplicitLegacyChoicesSurvive() {
        assertEquals(Appearance.Mode.AUTO, Appearance.mode(app));
        assertNull(Appearance.chosen(app));
        app.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("night", true).commit();
        assertEquals(Appearance.Mode.NIGHT, Appearance.mode(app));
        app.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("night", false).commit();
        assertEquals(Appearance.Mode.DAY, Appearance.mode(app));
    }

    @Test public void allFourModesPersistAndReplaceTheLegacyChoice() {
        app.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("night", true).commit();
        for (Appearance.Mode mode : Appearance.Mode.values()) {
            assertTrue(Appearance.choose(app, mode));
            assertEquals(mode, Appearance.mode(app));
            assertFalse(app.getSharedPreferences("appearance", Context.MODE_PRIVATE).contains("night"));
        }
    }

    @Test public void forcedDayAndNightIgnoreClockAndSystem() {
        Appearance.choose(app, Appearance.Mode.DAY);
        assertFalse(Appearance.resolve(app, at("2026-10-03T03:00:00Z")).night);
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
                Appearance.override(app).uiMode & Configuration.UI_MODE_NIGHT_MASK);
        Appearance.choose(app, Appearance.Mode.NIGHT);
        assertTrue(Appearance.resolve(app, at("2026-10-03T15:00:00Z")).night);
        assertEquals(Configuration.UI_MODE_NIGHT_YES,
                Appearance.override(app).uiMode & Configuration.UI_MODE_NIGHT_MASK);
    }

    @Test public void systemFollowsAndroidEvenWhenCalledFromAnOldForcedContext() {
        Appearance.choose(app, Appearance.Mode.SYSTEM);
        RuntimeEnvironment.setQualifiers("night");
        Configuration daytime = new Configuration(app.getResources().getConfiguration());
        daytime.uiMode = (daytime.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        Context oldOverride = app.createConfigurationContext(daytime);
        assertTrue(Appearance.resolve(oldOverride).night);
        assertNull(Appearance.override(app));
        RuntimeEnvironment.setQualifiers("notnight");
        assertFalse(Appearance.resolve(app).night);
    }

    @Test public void autoUsesRecentAuthorizedLocationAndKeepsNoCoordinates() {
        location(0);
        Appearance.State state = Appearance.resolve(app, at("2026-06-22T00:00:00Z"));
        assertFalse(state.night); // Cincinnati is still daylight at 8 pm in June.
        assertFalse(state.clockFallback);
        assertTrue(state.description().contains("sunrise and sunset"));
        assertTrue(app.getSharedPreferences("appearance", Context.MODE_PRIVATE).getAll().isEmpty());
    }

    @Test public void missingLocationUsesAnExplicitLocalClockFallback() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            Appearance.State state = Appearance.resolve(app, at("2026-06-22T00:00:00Z"));
            assertTrue(state.night);
            assertTrue(state.clockFallback);
            assertTrue(state.description().contains("clock fallback"));
            assertTrue(Appearance.explanation(app).contains("6 am–6 pm local time"));
        } finally { TimeZone.setDefault(before); }
    }

    @Test public void turningOffMapOrRevokingPermissionStopsThemeLocationUse() {
        location(0);
        assertFalse(Appearance.resolve(app).clockFallback);
        AreaMap.setEnabled(app, false);
        assertTrue(Appearance.resolve(app).clockFallback);
        AreaMap.setEnabled(app, true);
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        assertTrue(Appearance.resolve(app).clockFallback);
    }

    @Test public void unacceptedNoticeAndStaleFixDoNotSupportAClaimOfLocalSunlight() {
        location(AreaMap.FRESH_MS + 60000);
        assertTrue(Appearance.resolve(app).clockFallback);
        location(0);
        assertFalse(Appearance.resolve(app).clockFallback);
        app.getSharedPreferences("consent", Context.MODE_PRIVATE).edit().clear().commit();
        assertFalse(Consent.accepted(app));
        assertTrue(Appearance.resolve(app).clockFallback);
    }

    @Test public void invalidSavedModeFallsBackToAutoNotAnOldForcedChoice() {
        app.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
                .putString("mode", "corrupt").putBoolean("night", true).commit();
        assertEquals(Appearance.Mode.AUTO, Appearance.mode(app));
    }
}
