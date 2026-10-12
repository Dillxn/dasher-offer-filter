package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
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
 * Autopilot on by default (0.6.2; the owner, 12 October 2026): a new install, and once an install updating from an
 * earlier version, gets Autopilot on, with the goal chosen before or else 70%, the bar at exactly the minimums and no
 * jump; from then on the user's switch decides. The goal chooser still asks once, saying Autopilot is on already.
 * Every other suite runs as an install whose user kept Autopilot off (ConsentedTestApp).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class AutopilotDefaultTest extends AndroidAdapterTestBase {
    private SharedPreferences rules;

    @Before
    public void onByDefault() {
        FilterStore.autopilotOnByDefault = true;
        rules = app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE);
        // Nothing of an earlier load: as on a phone opening this version for the first time.
        rules.edit().clear().commit();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void aNewInstallHasAutopilotOnWithTheSuggestedGoalAtExactlyTheMinimums() {
        FilterSettings first = FilterStore.load(app);
        assertTrue(first.autopilot);
        assertEquals(FilterSettings.GOAL_TOP_TIER, first.autopilotGoalPercent);
        assertEquals(FilterSettings.BAR_AT_MINIMUMS, first.minimumScalePercent);
        assertFalse("no rule yet: nothing else is set", first.hasAnyRule());
        assertFalse("the goal was not asked yet", FilterStore.goalAsked(app));
        assertNull("no jump: Autopilot moves the bar from 100% only by its own commits", AutopilotStore.jump(app));
        FilterStore.load(app);
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[autopilot] " + AutopilotText.LOG_ON_BY_DEFAULT));
        assertEquals("logged once", log.indexOf(AutopilotText.LOG_ON_BY_DEFAULT),
                log.lastIndexOf(AutopilotText.LOG_ON_BY_DEFAULT));
    }

    @Test
    public void anInstallUpdatingWithAutopilotOffGetsItOnOnceKeepingItsGoalAndMinimums() {
        // 0.6.1 with $7.00, $1.50 a mile and $18 an hour, Autopilot asked and left off with the 50% goal, and an
        // acceptance-rate state left from an earlier time it was on.
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        rules.edit().remove("autopilot_default_schema").putBoolean("autopilot_on", false)
                .putInt("autopilot_goal", FilterSettings.GOAL_TIER).putBoolean("autopilot_goal_asked", true)
                .commit();
        AutopilotStore.setRecovering(app, true);
        long since = FilterStore.minimumsSince(app);

        FilterSettings updated = FilterStore.load(app);
        assertTrue(updated.autopilot);
        assertEquals("the goal chosen before", FilterSettings.GOAL_TIER, updated.autopilotGoalPercent);
        assertEquals(FilterSettings.BAR_AT_MINIMUMS, updated.minimumScalePercent);
        assertEquals(700, updated.flatCents);
        assertEquals(150, updated.perMileCents);
        assertEquals(30, updated.perMinuteCents);
        assertEquals(3, updated.maxStops);
        assertTrue(updated.enabled);
        assertEquals("the minimums take effect as they did", since, FilterStore.minimumsSince(app));
        assertTrue("asked stays asked", FilterStore.goalAsked(app));
        assertFalse("no recovery left from before", AutopilotStore.recovering(app));
        assertNull(AutopilotStore.jump(app));
    }

    @Test
    public void theUsersOffStandsFromThenOn() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 0));
        assertTrue(FilterStore.load(app).autopilot);
        AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        assertFalse(FilterStore.load(app).autopilot);
        assertEquals(FilterSettings.BAR_AT_MINIMUMS, FilterStore.load(app).minimumScalePercent);
        FilterStore.save(app, FilterStore.load(app), FilterSettings.of(true, 800, 150, 30, 0));
        assertFalse("turned on once, never again", FilterStore.load(app).autopilot);
    }

    @Test
    public void aChoiceMadeBeforeTheRulesWereEverLoadedIsTheOneThatStands() {
        FilterStore.setAutopilot(app, false, FilterSettings.GOAL_PAY_FIRST);
        FilterSettings loaded = FilterStore.load(app);
        assertFalse(loaded.autopilot);
        assertEquals(FilterSettings.GOAL_PAY_FIRST, loaded.autopilotGoalPercent);
    }

    @Test
    public void theGoalChooserAsksOnceSayingAutopilotIsOnAndNotNowKeepsIt() {
        FilterStore.save(app, FilterSettings.of(false, 700, 150, 30, 0));
        assertTrue(FilterStore.load(app).autopilot);
        assertFalse(FilterStore.goalAsked(app));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            mascot.performClick();
            idle();
            assertTrue(FilterStore.load(app).enabled);
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("asked once", chooser);
            assertEquals(AutopilotText.CHOOSER_TITLE, String.valueOf(Shadows.shadowOf(chooser).getTitle()));
            assertEquals("the suggested goal checked", 0, chooser.getListView().getCheckedItemPosition());
            TextView on = findTextContaining(chooser.getWindow().getDecorView(), AutopilotText.CHOOSER_ON_BY_DEFAULT);
            assertNotNull("it says Autopilot is on already", on);
            assertTrue(on.isShown());
            assertNull("never that an answer turns it on",
                    findTextContaining(chooser.getWindow().getDecorView(), AutopilotText.CHOOSER_TURNS_ON));
            chooser.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            FilterSettings kept = FilterStore.load(app);
            assertTrue("Not now keeps it on", kept.autopilot);
            assertEquals(FilterSettings.GOAL_TOP_TIER, kept.autopilotGoalPercent);
            assertTrue(FilterStore.goalAsked(app));

            ShadowAlertDialog.reset();
            mascot.performClick();
            idle();
            mascot.performClick();
            idle();
            assertNull("never again on its own", ShadowAlertDialog.getLatestAlertDialog());
            // Changing the goal later has no line under the choices: it is on and was asked.
            assertTrue(find(content, AutopilotChip.class).performLongClick());
            chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertNull(findTextContaining(chooser.getWindow().getDecorView(), AutopilotText.CHOOSER_ON_BY_DEFAULT));
            Shadows.shadowOf(chooser).clickOnItem(1);
            idle();
            assertEquals(FilterSettings.GOAL_TIER, FilterStore.load(app).autopilotGoalPercent);
            assertTrue(FilterStore.load(app).autopilot);
        }
    }
}
