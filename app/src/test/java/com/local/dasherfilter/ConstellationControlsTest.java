package com.local.dasherfilter;

import android.app.AlertDialog;
import android.graphics.RectF;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The rules' controls on the constellation besides the knobs, now that Settings has none: the max stops badge by the
 * per-stop pin, and the one Autopilot button where the row of round buttons stood (a tap turns Autopilot off, or on
 * through the goal chooser; a long press changes the goal; no drag), each saved at once, by touch and by screen
 * readers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class ConstellationControlsTest extends AndroidAdapterTestBase {
    @Before public void plansOnThisThread() {
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.executorForTests = Runnable::run;
    }

    @After public void plansOnTheirOwnThread() {
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.forgetCache();
    }

    private static float[] middle(RectF box) {
        return new float[] {box.centerX(), box.centerY()};
    }

    @Test
    public void theMaxStopsBadgeStepsOffTwoToTenByTapAndSavesAtOnce() {
        FilterStore.save(app, FilterSettings.of(true, 1000, 150, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            ViewGroup sky = (ViewGroup) star.getParent();
            RectF badge = star.stopsBox();
            assertNotNull("a badge by the per-stop pin", badge);
            assertEquals("hollow, with no limit", "≤∞", star.stopsWords());
            Ui ui = new Ui(app);
            RectF pin = star.stopsPinBox();
            assertNotNull("the per-stop pin stays, as the badge's anchor", pin);
            float dx = Math.max(0, Math.max(badge.left - pin.right, pin.left - badge.right));
            float dy = Math.max(0, Math.max(badge.top - pin.bottom, pin.top - badge.bottom));
            assertTrue("the badge stands right by its pin: " + badge + " / " + pin, Math.hypot(dx, dy) <= ui.dp(12));
            android.graphics.Rect target = new android.graphics.Rect();
            node(star, MinimumsStarView.STOPS_ID).getBoundsInParent(target);
            assertTrue("a full touch target: " + target, target.height() >= ui.dp(48) - 1
                    && target.width() >= ui.dp(48) - 1);
            for (int axis : MinimumsStarView.SPOKES) {
                float[] knob = star.knobAt(axis);
                float kx = Math.max(0, Math.max(badge.left - knob[0], knob[0] - badge.right));
                float ky = Math.max(0, Math.max(badge.top - knob[1], knob[1] - badge.bottom));
                assertTrue("clear of the knobs' reach", Math.hypot(kx, ky) >= ui.dp(24) - 1);
            }
            List<RectF> icons = new ArrayList<>();
            star.iconsAt(icons);
            assertTrue("the scene keeps its clouds and stars off it", containsPoint(icons, badge.centerX(),
                    badge.centerY()));

            tapStar(star, badge.centerX(), badge.centerY());
            assertEquals("off to 2 stops (one order)", 2, FilterStore.load(app).maxStops);
            assertEquals("≤2", star.stopsWords());
            assertEquals("Max stops, 2", star.lastSaid());
            tapStar(star, star.stopsBox().centerX(), star.stopsBox().centerY());
            assertEquals(3, FilterStore.load(app).maxStops);
            FilterSettings saved = FilterStore.load(app);
            assertTrue("still on", saved.enabled);
            assertArrayEquals("nothing else changes", new int[] {1000, 150, 0, 0, 0, 0}, saved.minimums());
            assertFalse("no page opens", settingsShown(content));

            // From 10 a tap turns it off again.
            FilterStore.save(app, FilterStore.load(app).withMaxStops(10));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            settleSky(content);
            assertEquals("≤10", star.stopsWords());
            tapStar(star, star.stopsBox().centerX(), star.stopsBox().centerY());
            assertEquals(0, FilterStore.load(app).maxStops);
            assertEquals("≤∞", star.stopsWords());
        }
    }

    @Test
    public void aDragAcrossTheBadgeStepsItEitherWayAndSavesWhenLetGo() {
        FilterStore.save(app, FilterSettings.of(false, 1000, 0, 0, 3));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            Ui ui = new Ui(app);
            float[] from = middle(star.stopsBox());
            // Three steps to the right: 3 → 6.
            dragThrough(content, new float[][] {from, {from[0] + ui.dp(18) * 3, from[1]}}, () -> {
                assertEquals("nothing is saved until it is let go", 3, FilterStore.load(app).maxStops);
                assertEquals("≤6", star.stopsWords());
            });
            assertEquals(6, FilterStore.load(app).maxStops);
            assertFalse("still paused", FilterStore.load(app).enabled);
            // Far to the left: down past 2 to off, and no further.
            settleSky(content);
            from = middle(star.stopsBox());
            dragThrough(content, new float[][] {from, {from[0] - ui.dp(18) * 9, from[1]}}, null);
            assertEquals(0, FilterStore.load(app).maxStops);
            // Far to the right: 10 at most.
            settleSky(content);
            from = middle(star.stopsBox());
            dragThrough(content, new float[][] {from, {from[0] + ui.dp(18) * 14, from[1]}}, null);
            assertEquals(MinimumsStarView.MOST_STOPS, FilterStore.load(app).maxStops);
            // A slide up or down from it is the page's: it sets nothing.
            settleSky(content);
            from = middle(star.stopsBox());
            dragThrough(content, new float[][] {from, {from[0], from[1] - ui.dp(60)}}, null);
            assertEquals(MinimumsStarView.MOST_STOPS, FilterStore.load(app).maxStops);
        }
    }

    @Test
    public void screenReadersAdjustMaxStopsAsOneControl() {
        FilterStore.save(app, FilterSettings.of(true, 1000, 0, 0, 2));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AccessibilityNodeInfo stops = node(star, MinimumsStarView.STOPS_ID);
            assertNotNull(stops);
            assertEquals("Max stops, 2", stops.getContentDescription().toString());
            assertEquals(SeekBar.class.getName(), stops.getClassName().toString());
            assertEquals(2f, stops.getRangeInfo().getCurrent(), 0.001f);
            assertEquals((float) MinimumsStarView.MOST_STOPS, stops.getRangeInfo().getMax(), 0.001f);

            assertTrue(act(star, MinimumsStarView.STOPS_ID, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
            assertEquals(3, FilterStore.load(app).maxStops);
            assertTrue(act(star, MinimumsStarView.STOPS_ID, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD));
            assertTrue(act(star, MinimumsStarView.STOPS_ID, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD));
            assertEquals("down from 2 is off", 0, FilterStore.load(app).maxStops);
            assertEquals("Max stops, off", star.lastSaid());
            assertFalse("an off badge steps only up", node(star, MinimumsStarView.STOPS_ID).getActionList().contains(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD));
            assertTrue("a double-tap steps on, as a tap does",
                    act(star, MinimumsStarView.STOPS_ID, AccessibilityNodeInfo.ACTION_CLICK));
            assertEquals(2, FilterStore.load(app).maxStops);
        }
    }

    @Test
    public void onAFreshPageTheBadgeSavesTheFirstRuleAndAutoDeclineStaysPaused() {
        FilterStore.save(app, FilterSettings.of(false, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            assertNotNull("with no rule, the start line", shownTextContaining(content, MainActivity.START_LINE));
            MinimumsStarView star = find(content, MinimumsStarView.class);
            RectF badge = star.stopsBox();
            tapStar(star, badge.centerX(), badge.centerY());
            FilterSettings saved = FilterStore.load(app);
            assertEquals(2, saved.maxStops);
            assertFalse(saved.enabled);
            assertFalse("max stops alone never turns Autopilot on", saved.autopilot);
            assertEquals("Rule saved. Tap the mascot to turn on auto-decline.", ShadowToast.getTextOfLatestToast());
            assertNull("the start line goes once there is a rule", shownTextContaining(content,
                    MainActivity.START_LINE));
            assertNotNull(shownTextContaining(content, "Paused"));
        }
    }

    @Test
    public void theAutopilotButtonTurnsAutopilotOnThroughTheGoalChooserAndOffAgainKeepingTheRules() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        // Set up (screen reading and notification access on): no setup line stands in the sky's lower half.
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            ViewGroup sky = (ViewGroup) star.getParent();
            RectF button = star.autopilotBox();
            assertNotNull("one round button by the chart", button);
            Ui ui = new Ui(app);
            assertTrue("a full touch target", button.width() >= ui.dp(48) - 1 && button.height() >= ui.dp(48) - 1);
            assertEquals("where the row of three had its middle: on the circle's upright line", star.skyX(),
                    button.centerX(), 1);
            assertTrue("below the middle, as the row stood: " + button + " / " + star.skyY(),
                    button.centerY() > star.skyY());
            for (int axis : MinimumsStarView.SPOKES) {
                float[] knob = star.knobAt(axis);
                assertTrue("clear of the knobs", Math.hypot(knob[0] - button.centerX(), knob[1] - button.centerY())
                        >= ui.dp(24) + button.width() / 2 - 1);
            }
            assertNull("the retired buttons are gone", node(star, MinimumsStarView.ADOPT_ID));
            assertNull(node(star, MinimumsStarView.ADAPTIVE_ID));

            // Off: a tap asks for the goal first, and asking changes nothing.
            tapStar(star, button.centerX(), button.centerY());
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("the goal chooser", chooser);
            assertEquals(AutopilotText.CHOOSER_TITLE, Shadows.shadowOf(chooser).getTitle().toString());
            assertFalse(FilterStore.load(app).autopilot);
            // One tap on a goal applies it and closes.
            Shadows.shadowOf(chooser).clickOnItem(1);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(chooser.isShowing());
            FilterSettings on = FilterStore.load(app);
            assertTrue("on, saved at once", on.autopilot);
            assertEquals(FilterSettings.GOAL_TIER, on.autopilotGoalPercent);
            assertEquals("Autopilot on · goal: acceptance rate 50% or more", ShadowToast.getTextOfLatestToast());
            assertTrue(on.enabled);
            assertArrayEquals("the minimums stay", new int[] {700, 150, 30, 0, 0, 0}, on.minimums());
            assertEquals(3, on.maxStops);
            assertEquals("the bar starts at exactly the minimums", 100, on.minimumScalePercent);
            assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("[autopilot] on; goal 50%"));
            assertFalse("no page opens", settingsShown(content));

            // On: its words follow ("Auto" over "100%"), and a tap turns it off, back to exactly the minimums.
            settleSky(content);
            assertEquals("Autopilot, on. Bar 100 percent of your minimums. Goal: keep a tier, acceptance rate 50 percent "
                    + "or more.", node(star, MinimumsStarView.SCORE_ID).getContentDescription().toString());
            button = star.autopilotBox();
            tapStar(star, button.centerX(), button.centerY());
            FilterSettings off = FilterStore.load(app);
            assertFalse(off.autopilot);
            assertEquals(100, off.minimumScalePercent);
            assertEquals("the goal is kept for next time", FilterSettings.GOAL_TIER, off.autopilotGoalPercent);
            assertEquals("Autopilot off · back to exactly your minimums", ShadowToast.getTextOfLatestToast());
            assertArrayEquals(new int[] {700, 150, 30, 0, 0, 0}, off.minimums());
            assertTrue(off.enabled);
            assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("[autopilot] off; bar back to 100%"));
        } finally {
            listener.destroy();
            service.destroy();
        }
    }

    @Test
    public void withSetupLinesInTheSkysLowerHalfTheButtonStandsAboveTheMiddleClearOfThem() {
        // Screen reading and notification access still to set up: their two lines stand at the sky's foot.
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            TextView setup = shownTextContaining(content, "Turn on Offer Filter in Accessibility");
            assertNotNull("a setup line in the sky", setup);
            RectF button = star.autopilotBox();
            assertNotNull(button);
            assertEquals("still on the circle's upright line", star.skyX(), button.centerX(), 1);
            assertTrue("above the middle, since the lines take the room below", button.centerY() < star.skyY());
            int[] starAt = new int[2];
            star.getLocationInWindow(starAt);
            for (String words : new String[] {"Turn on Offer Filter in Accessibility", "Allow notification access"}) {
                TextView line = shownTextContaining(content, words);
                if (line == null) continue;
                View row = (View) line.getParent();
                int[] rowAt = new int[2];
                row.getLocationInWindow(rowAt);
                RectF box = new RectF(rowAt[0] - starAt[0], rowAt[1] - starAt[1],
                        rowAt[0] - starAt[0] + row.getWidth(), rowAt[1] - starAt[1] + row.getHeight());
                assertFalse("clear of the setup line " + box + " / " + button, RectF.intersects(box, button));
            }
            ShadowAlertDialog.reset();
            tapStar(star, button.centerX(), button.centerY());
            assertEquals("the button takes its own taps there", AutopilotText.CHOOSER_TITLE,
                    Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getTitle().toString());
        }
    }

    @Test
    public void holdingTheAutopilotButtonChangesTheGoalAndTogglesNothing() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            ViewGroup sky = (ViewGroup) star.getParent();

            // Held: the chooser, with the stored goal checked; Not now leaves everything, and the lift is no tap.
            ShadowAlertDialog.reset();
            hold(star, star.autopilotBox());
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(chooser);
            assertEquals(AutopilotText.CHOOSER_TITLE, Shadows.shadowOf(chooser).getTitle().toString());
            assertEquals("the stored goal is checked", 0, chooser.getListView().getCheckedItemPosition());
            assertTrue("the hold does not turn it off", FilterStore.load(app).autopilot);
            chooser.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(FilterStore.load(app).autopilot);
            assertEquals(FilterSettings.GOAL_TOP_TIER, FilterStore.load(app).autopilotGoalPercent);

            // Held again: pay first, while it stays on.
            settleSky(content);
            hold(star, star.autopilotBox());
            chooser = ShadowAlertDialog.getLatestAlertDialog();
            Shadows.shadowOf(chooser).clickOnItem(2);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FilterSettings saved = FilterStore.load(app);
            assertTrue(saved.autopilot);
            assertEquals(FilterSettings.GOAL_PAY_FIRST, saved.autopilotGoalPercent);
            assertEquals("Autopilot goal: pay first", ShadowToast.getTextOfLatestToast());
            assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("[autopilot] pay first (was 70%)"));
            assertArrayEquals("the set minimums stay", new int[] {700, 150, 30, 0, 0, 0}, saved.minimums());

            // A finger that moves off the button is neither a tap nor a hold: the button has no drag.
            settleSky(content);
            RectF button = star.autopilotBox();
            ShadowAlertDialog.reset();
            long now = android.os.SystemClock.uptimeMillis();
            button.offset(star.getLeft(), star.getTop());
            sky.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, button.centerX(),
                    button.centerY(), 0));
            for (int step = 1; step <= 6; step++) {
                sky.dispatchTouchEvent(MotionEvent.obtain(now, now + step * 16L, MotionEvent.ACTION_MOVE,
                        button.centerX() + step * new Ui(app).dp(12), button.centerY(), 0));
            }
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                    Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100));
            sky.dispatchTouchEvent(MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP, button.centerX() + new Ui(app).dp(72), button.centerY(), 0));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull("no chooser", ShadowAlertDialog.getLatestAlertDialog());
            FilterSettings after = FilterStore.load(app);
            assertTrue("still on", after.autopilot);
            assertEquals("and the bar not dragged", 100, after.minimumScalePercent);
            assertEquals(FilterSettings.GOAL_PAY_FIRST, after.autopilotGoalPercent);
        }
    }

    @Test
    public void screenReadersHearTheAutopilotButtonAsASwitchWithItsActionsAndNoRange() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AccessibilityNodeInfo button = node(star, MinimumsStarView.SCORE_ID);
            assertNotNull(button);
            assertEquals("Autopilot, off. Offers are judged at exactly your minimums.",
                    button.getContentDescription().toString());
            assertEquals(Switch.class.getName(), button.getClassName().toString());
            assertTrue(button.isCheckable());
            assertFalse(button.isChecked());
            assertNull("nothing to drag: no range", button.getRangeInfo());
            List<String> actions = new ArrayList<>();
            for (AccessibilityNodeInfo.AccessibilityAction action : button.getActionList()) {
                if (action.getLabel() != null) actions.add(action.getId() + "=" + action.getLabel());
                assertTrue("no adjusting it: " + action, action.getId() != AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        && action.getId() != AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        && action.getId() != android.R.id.accessibilityActionSetProgress);
            }
            assertTrue(actions.toString(), actions.contains(AccessibilityNodeInfo.ACTION_CLICK + "=Turn Autopilot on"));
            assertTrue(actions.toString(), actions.contains(AccessibilityNodeInfo.ACTION_LONG_CLICK
                    + "=Change acceptance goal"));
            assertTrue(actions.toString(), actions.contains(0x4F460003 + "=Autopilot details"));
            assertEquals(0x4F460003, AutopilotText.DETAILS_ACTION_ID);
            assertEquals("no other node shows for the retired spokes", null, node(star, AreaScore.STOP));
            assertNull(node(star, AreaScore.HOTSPOT));
            assertNull(node(star, AreaScore.ITEM));

            // Its details action opens the details; a double-tap asks for the goal; choosing turns it on.
            ShadowAlertDialog.reset();
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AutopilotText.DETAILS_ACTION_ID));
            AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(details);
            assertEquals(AutopilotText.DETAILS_TITLE, Shadows.shadowOf(details).getTitle().toString());
            details.dismiss();
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_CLICK));
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.CHOOSER_TITLE, Shadows.shadowOf(chooser).getTitle().toString());
            Shadows.shadowOf(chooser).clickOnItem(0);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(FilterStore.load(app).autopilot);
            AccessibilityNodeInfo on = node(star, MinimumsStarView.SCORE_ID);
            assertTrue(on.isChecked());
            assertEquals("Autopilot, on. Bar 100 percent of your minimums. Goal: keep a top tier, acceptance rate 70 "
                    + "percent or more.", on.getContentDescription().toString());
            List<String> now = new ArrayList<>();
            for (AccessibilityNodeInfo.AccessibilityAction action : on.getActionList()) {
                if (action.getLabel() != null) now.add(action.getId() + "=" + action.getLabel());
            }
            assertTrue(now.toString(), now.contains(AccessibilityNodeInfo.ACTION_CLICK + "=Turn Autopilot off"));

            // A long click asks for the goal, the stored one checked.
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_LONG_CLICK));
            chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.CHOOSER_TITLE, Shadows.shadowOf(chooser).getTitle().toString());
            assertEquals(0, chooser.getListView().getCheckedItemPosition());
            chooser.dismiss();
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_CLICK));
            assertFalse("and a double-tap turns it off", FilterStore.load(app).autopilot);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherTheBadgeAndTheButtonFitClearOfEachOtherAndTheCounts() {
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        // The README's rules after the 0.5.0 update folded its $4.75 per stop into the $13.00 minimum pay.
        FilterStore.save(app, FilterSettings.of(true, 1300, 385, 41, 3));
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            RectF counts = new RectF();
            mascot.countsAt(counts);
            counts.offset(mascot.getLeft(), mascot.getTop());
            RectF[] controls = {star.stopsBox(), star.autopilotBox()};
            for (int i = 0; i < controls.length; i++) {
                assertNotNull("shown " + i, controls[i]);
                assertTrue("inside the page " + controls[i], controls[i].left >= 0
                        && controls[i].right <= star.getWidth() && controls[i].top >= 0
                        && controls[i].bottom <= star.getHeight());
                assertFalse("clear of the counts " + controls[i], RectF.intersects(controls[i], counts));
            }
            assertFalse("apart", RectF.intersects(controls[0], controls[1]));
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    /** A finger held on {@code box} (in {@code star}'s pixels) past Android's long-press time, then lifted. */
    private static void hold(MinimumsStarView star, RectF box) {
        ViewGroup sky = (ViewGroup) star.getParent();
        long now = android.os.SystemClock.uptimeMillis();
        float x = star.getLeft() + box.centerX();
        float y = star.getTop() + box.centerY();
        sky.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100));
        long later = android.os.SystemClock.uptimeMillis();
        sky.dispatchTouchEvent(MotionEvent.obtain(now, later, MotionEvent.ACTION_UP, x, y, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
