package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * Area scoring by default (0.6.27; the owner, 17 October 2026: "it should be on area score mode by default"): a new
 * install scores offers by area, and so, once, does an install updating whose user never picked a mode (nothing before
 * 0.6.27 kept whether Strict was picked, so a stored Strict moves too), keeping its rules, its "minimums since" and
 * Autopilot's accepted floor. An Area already on, a mode marked picked and a pick in How offers are judged stand from
 * then on. Every other suite runs as an install whose user kept Strict (ConsentedTestApp).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class AreaModeDefaultTest extends AndroidAdapterTestBase {
    private static final String LINE = "[rules] " + FilterStore.AREA_BY_DEFAULT_LOG;
    private SharedPreferences rules;

    @Before
    public void areaByDefault() {
        FilterStore.areaModeByDefault = true;
        rules = app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
        // Nothing of an earlier load: as on a phone opening this version for the first time.
        rules.edit().clear().commit();
        DiagnosticLog.clear(app);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** How many times the default's line is in the log. */
    private int lines() {
        String log = DiagnosticLog.read(app);
        int count = 0;
        for (int at = log.indexOf(LINE); at >= 0; at = log.indexOf(LINE, at + 1)) count++;
        return count;
    }

    private long epoch() {
        return rules.getLong("learner_v3_epoch", 0);
    }

    /** A 0.6.26 install as it stood: these rules saved in Strict, the area step not yet run, nothing in the log. */
    private void updatingFrom0626(boolean area) {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        rules.edit().remove("area_mode_default_schema").remove("area_mode_chosen")
                .putBoolean(FilterStore.AREA_MODE, area).commit();
        DiagnosticLog.clear(app);
    }

    @Test
    public void aNewInstallScoresByAreaAndItsFirstRulesKeepIt() {
        FilterSettings first = FilterStore.load(app);
        assertTrue(first.scoreByArea);
        assertTrue("learning stays on by default", first.acceptanceLearning);
        assertFalse("no rule yet: nothing else is set", first.hasAnyRule());
        assertFalse(first.enabled);
        FilterStore.load(app);
        assertEquals("logged once", 1, lines());
        assertEquals("area scoring on by default (How offers are judged chooses Strict)",
                FilterStore.AREA_BY_DEFAULT_LOG);

        // The first rule is saved from the rules as loaded, as every change in the app is: the mode stays.
        FilterSettings loaded = FilterStore.load(app);
        FilterStore.save(app, loaded, loaded.withMinimums(700, 150, 30).withEnabled(true));
        FilterSettings saved = FilterStore.load(app);
        assertTrue(saved.scoreByArea);
        assertEquals(700, saved.flatCents);
        assertTrue(saved.enabled);
        assertEquals(1, lines());
    }

    @Test
    public void anInstallUpdatingInStrictMovesToAreaOnceKeepingItsRulesAndFloors() {
        updatingFrom0626(false);
        long since = FilterStore.minimumsSince(app);
        long floor = System.currentTimeMillis() - 60 * 60_000L;
        AutopilotStore.restartFloor(app, floor);
        long before = epoch();

        FilterSettings updated = FilterStore.load(app);
        assertTrue(updated.scoreByArea);
        assertTrue(updated.enabled);
        assertEquals(700, updated.flatCents);
        assertEquals(150, updated.perMileCents);
        assertEquals(30, updated.perMinuteCents);
        assertEquals(3, updated.maxStops);
        assertEquals(FilterSettings.BAR_AT_MINIMUMS, updated.minimumScalePercent);
        assertEquals("the minimums take effect as they did", since, FilterStore.minimumsSince(app));
        assertEquals("the accepted floor restarts only at the user's own change", floor,
                AutopilotStore.floorSince(app));
        assertEquals("nothing seen under Strict teaches the learner under Area", before + 1, epoch());
        assertEquals(1, lines());

        // Once only.
        FilterStore.load(app);
        FilterStore.save(app, FilterStore.load(app), FilterStore.load(app).withMaxStops(2));
        assertTrue(FilterStore.load(app).scoreByArea);
        assertEquals(before + 1, epoch());
        assertEquals(1, lines());
    }

    @Test
    public void anAreaAlreadyOnStaysWithNoLineAndNoNewEpoch() {
        updatingFrom0626(true);
        long before = epoch();
        assertTrue(FilterStore.load(app).scoreByArea);
        assertEquals(before, epoch());
        assertEquals(0, lines());
    }

    @Test
    public void aStrictMarkedPickedStands() {
        updatingFrom0626(false);
        rules.edit().putBoolean("area_mode_chosen", true).commit();
        long before = epoch();
        assertFalse(FilterStore.load(app).scoreByArea);
        assertEquals(before, epoch());
        assertEquals(0, lines());
    }

    @Test
    public void strictPickedAfterTheStepStandsAndIsMarkedPicked() {
        assertTrue(FilterStore.load(app).scoreByArea);
        FilterStore.setScoreByArea(app, false);
        assertFalse(FilterStore.load(app).scoreByArea);
        assertTrue("picked, so a later default step leaves it", rules.getBoolean("area_mode_chosen", false));
        // Were the step to run again, the pick stands.
        rules.edit().remove("area_mode_default_schema").commit();
        assertFalse(FilterStore.load(app).scoreByArea);
        assertEquals(1, lines());
    }

    @Test
    public void theRadarSaysAreaAndOneTapInTheChooserBringsStrictBack() {
        FilterSettings loaded = FilterStore.load(app);
        FilterStore.save(app, loaded, loaded.withMinimums(250, 100, 25).withMaxStops(3));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertEquals(ModeChip.AREA, star.modeShownWord());
            assertEquals(ModeChip.AREA_SAID, node(star, MinimumsStarView.MODE_ID).getContentDescription().toString());

            assertTrue(act(star, MinimumsStarView.MODE_ID, AccessibilityNodeInfo.ACTION_CLICK));
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Area checked", 1, chooser.getListView().getCheckedItemPosition());
            Shadows.shadowOf(chooser).clickOnItem(0);
            idle();
            FilterSettings strict = FilterStore.load(app);
            assertFalse(strict.scoreByArea);
            assertEquals("the rules stay", 250, strict.flatCents);
            assertEquals(ModeChip.STRICT, star.modeShownWord());
            assertTrue(rules.getBoolean("area_mode_chosen", false));
        }
    }
}
