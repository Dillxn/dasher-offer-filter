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
 * earlier version, gets Autopilot on, with the goal chosen before or else the suggested one, the bar at exactly the
 * minimums and no jump; from then on the user's switch decides. The goal chooser still asks once, saying Autopilot is on
 * already. Pay first is the default goal (0.6.18; the owner chose "Earnings first", 14 October 2026): a new install has
 * it, and once an install updating whose goal the user never picked (a stored 70%, which can't be told from the
 * suggested one) moves to it, while a picked goal (50%, pay first, or one marked chosen) stays. Every other suite runs as
 * an install whose user kept Autopilot off (ConsentedTestApp).
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
        assertEquals("pay first is the suggested goal (0.6.18)", FilterSettings.GOAL_PAY_FIRST,
                first.autopilotGoalPercent);
        assertFalse("suggested, not picked", FilterStore.goalChosen(app));
        assertEquals(FilterSettings.BAR_AT_MINIMUMS, first.minimumScalePercent);
        assertFalse("no rule yet: nothing else is set", first.hasAnyRule());
        assertFalse("the goal was not asked yet", FilterStore.goalAsked(app));
        assertNull("no jump: Autopilot moves the bar from 100% only by its own commits", AutopilotStore.jump(app));
        FilterStore.load(app);
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[autopilot] " + AutopilotText.LOG_ON_BY_DEFAULT));
        assertEquals("logged once", log.indexOf(AutopilotText.LOG_ON_BY_DEFAULT),
                log.lastIndexOf(AutopilotText.LOG_ON_BY_DEFAULT));
        assertFalse("a new install's goal moved from nothing: no line", log.contains("goal pay first by default"));
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
            assertEquals(FilterSettings.GOAL_PAY_FIRST, kept.autopilotGoalPercent);
            assertFalse("Not now picks nothing", FilterStore.goalChosen(app));
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
            Shadows.shadowOf(chooser).clickOnItem(2);
            idle();
            assertEquals(FilterSettings.GOAL_TIER, FilterStore.load(app).autopilotGoalPercent);
            assertTrue(FilterStore.load(app).autopilot);
            assertTrue("a pick in the chooser is marked chosen", FilterStore.goalChosen(app));
        }
    }

    // ---- Pay first by default (0.6.18) ----

    /** A 0.6.17 install as it stood: the rules saved, Autopilot on by its 0.6.2 default, the goal step not yet run. */
    private void updatingFrom0617(int goal, boolean asked) {
        FilterStore.save(app, FilterSettings.of(true, 250, 318, 0, 0));
        rules.edit().remove(FilterStore.GOAL_DEFAULT_SCHEMA).remove("autopilot_goal_chosen")
                .putBoolean("autopilot_on", true).putInt("autopilot_goal", goal)
                .putBoolean("autopilot_goal_asked", asked).putInt("autopilot_bar_percent", 50).commit();
        DiagnosticLog.clear(app);
    }

    @Test
    public void theSuggestedSeventyMovesToPayFirstOnceKeepingTheBarAndTheFloor() {
        // The owner's own state: 70%, the question answered (Not now or a tap: nothing before 0.6.18 told them
        // apart), the bar pinned at 50%, recovering toward 70%, a hand accept's floor running since an hour ago.
        updatingFrom0617(FilterSettings.GOAL_TOP_TIER, true);
        AutopilotStore.setRecovering(app, true);
        long since = System.currentTimeMillis() - 60 * 60_000L;
        AutopilotStore.restartFloor(app, since);

        FilterSettings updated = FilterStore.load(app);
        assertEquals(FilterSettings.GOAL_PAY_FIRST, updated.autopilotGoalPercent);
        assertTrue(updated.autopilot);
        assertEquals("the bar moves only by Autopilot's commits", 50, updated.minimumScalePercent);
        assertEquals(250, updated.flatCents);
        assertEquals(318, updated.perMileCents);
        assertFalse("no recovery toward the old goal", AutopilotStore.recovering(app));
        assertEquals("the accepted floor restarts only at the user's own change", since,
                AutopilotStore.floorSince(app));
        assertTrue("asked stays asked", FilterStore.goalAsked(app));
        assertFalse(FilterStore.goalChosen(app));
        String log = DiagnosticLog.read(app);
        String line = "[autopilot] " + AutopilotText.logGoalDefault(FilterSettings.GOAL_TOP_TIER);
        assertTrue(log, log.contains(line));
        assertEquals("goal pay first by default (was the suggested 70%)",
                AutopilotText.logGoalDefault(FilterSettings.GOAL_TOP_TIER));

        // Once only: the user's 70% picked from then on stands, and so does one written after the step.
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.goalChosen(app));
        assertEquals(FilterSettings.GOAL_TOP_TIER, FilterStore.load(app).autopilotGoalPercent);
        rules.edit().remove("autopilot_goal_chosen").putInt("autopilot_goal", FilterSettings.GOAL_TOP_TIER).commit();
        assertEquals("the step never runs again", FilterSettings.GOAL_TOP_TIER,
                FilterStore.load(app).autopilotGoalPercent);
        log = DiagnosticLog.read(app);
        assertEquals("logged once", log.indexOf(line), log.lastIndexOf(line));
    }

    @Test
    public void aSeventyNeverAskedAndOneLeftWithAutopilotOffMoveToo() {
        updatingFrom0617(FilterSettings.GOAL_TOP_TIER, false);
        assertEquals(FilterSettings.GOAL_PAY_FIRST, FilterStore.load(app).autopilotGoalPercent);

        rules.edit().clear().commit();
        updatingFrom0617(FilterSettings.GOAL_TOP_TIER, true);
        rules.edit().putBoolean("autopilot_on", false).commit();
        FilterSettings off = FilterStore.load(app);
        assertFalse("the user's off stands", off.autopilot);
        assertEquals(FilterSettings.GOAL_PAY_FIRST, off.autopilotGoalPercent);
        assertEquals(FilterSettings.BAR_AT_MINIMUMS, off.minimumScalePercent);
    }

    @Test
    public void aGoalTheUserPickedStays() {
        // 50% and pay first were only ever set in the chooser: kept, and marked chosen from now on.
        updatingFrom0617(FilterSettings.GOAL_TIER, true);
        AutopilotStore.setRecovering(app, true);
        assertEquals(FilterSettings.GOAL_TIER, FilterStore.load(app).autopilotGoalPercent);
        assertTrue(FilterStore.goalChosen(app));
        assertTrue("its recovery is the user's goal's: kept", AutopilotStore.recovering(app));
        assertFalse(DiagnosticLog.read(app).contains("goal pay first by default"));

        rules.edit().clear().commit();
        updatingFrom0617(FilterSettings.GOAL_PAY_FIRST, true);
        assertEquals(FilterSettings.GOAL_PAY_FIRST, FilterStore.load(app).autopilotGoalPercent);
        assertTrue(FilterStore.goalChosen(app));

        // A 70% marked chosen (picked in 0.6.18's chooser, on a phone that went back to 0.6.17 and forward again).
        rules.edit().clear().commit();
        updatingFrom0617(FilterSettings.GOAL_TOP_TIER, true);
        rules.edit().putBoolean("autopilot_goal_chosen", true).commit();
        assertEquals(FilterSettings.GOAL_TOP_TIER, FilterStore.load(app).autopilotGoalPercent);
        assertFalse(DiagnosticLog.read(app).contains("goal pay first by default"));
    }

    @Test
    public void turningAutopilotOffKeepsTheGoalWithoutPickingIt() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 0));
        AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_PAY_FIRST);
        assertFalse("off is no pick of a goal", FilterStore.goalChosen(app));
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        assertTrue("a pick in the chooser turns it on", FilterStore.goalChosen(app));
    }
}
