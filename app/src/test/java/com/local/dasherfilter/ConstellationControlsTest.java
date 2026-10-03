package com.local.dasherfilter;

import android.graphics.RectF;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import android.widget.Switch;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The rules' new homes on the constellation, now that Settings has none: the max stops badge by the per-stop spoke and
 * the adaptive minimum's toggle (Reset on a long press, with a confirm), each saved at once through FilterStore, by
 * touch and by screen readers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class ConstellationControlsTest extends AndroidAdapterTestBase {
    private static float[] middle(RectF box) {
        return new float[] {box.centerX(), box.centerY()};
    }

    @Test
    public void theMaxStopsBadgeStepsOffTwoToTenByTapAndSavesAtOnce() {
        FilterStore.save(app, new FilterSettings(true, 1000, 150, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            ViewGroup sky = (ViewGroup) star.getParent();
            RectF badge = star.stopsBox();
            assertNotNull("a badge by the per-stop spoke", badge);
            assertEquals("hollow, with no limit", "≤∞", star.stopsWords());
            Ui ui = new Ui(app);
            android.graphics.Rect target = new android.graphics.Rect();
            node(star, MinimumsStarView.STOPS_ID).getBoundsInParent(target);
            assertTrue("a full touch target: " + target, target.height() >= ui.dp(48) - 1
                    && target.width() >= ui.dp(48) - 1);
            for (int axis = 0; axis < 5; axis++) {
                float[] knob = star.knobAt(axis);
                float dx = Math.max(0, Math.max(badge.left - knob[0], knob[0] - badge.right));
                float dy = Math.max(0, Math.max(badge.top - knob[1], knob[1] - badge.bottom));
                assertTrue("clear of the knobs' reach", Math.hypot(dx, dy) >= ui.dp(24) - 1);
            }
            List<RectF> icons = new ArrayList<>();
            star.iconsAt(icons);
            assertTrue("the scene keeps its clouds and stars off it", containsPoint(icons, badge.centerX(),
                    badge.centerY()));

            tap(sky, badge.centerX(), badge.centerY());
            assertEquals("off to 2 stops (one order)", 2, FilterStore.load(app).maxStops);
            assertEquals("≤2", star.stopsWords());
            assertEquals("Max stops, 2", star.lastSaid());
            tap(sky, star.stopsBox().centerX(), star.stopsBox().centerY());
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
            tap(sky, star.stopsBox().centerX(), star.stopsBox().centerY());
            assertEquals(0, FilterStore.load(app).maxStops);
            assertEquals("≤∞", star.stopsWords());
        }
    }

    @Test
    public void aDragAcrossTheBadgeStepsItEitherWayAndSavesWhenLetGo() {
        FilterStore.save(app, new FilterSettings(false, 1000, 0, 0, 0, 3));
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
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 2));
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
        FilterStore.save(app, new FilterSettings(false, 0, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            RectF badge = star.stopsBox();
            tap((ViewGroup) star.getParent(), badge.centerX(), badge.centerY());
            FilterSettings saved = FilterStore.load(app);
            assertEquals(2, saved.maxStops);
            assertFalse(saved.enabled);
            assertEquals("Rule saved. Tap the mascot to turn on auto-decline.",
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            assertNull("the start hint goes once there is a rule", shownTextContaining(content,
                    MainActivity.START_HINT));
            assertNotNull(shownTextContaining(content, "Paused"));
        }
    }

    @Test
    public void theAdaptiveToggleTurnsTheMinimumOnAndOffKeepingWhatItLearned() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            ViewGroup sky = (ViewGroup) star.getParent();
            RectF toggle = star.adaptiveBox();
            assertNotNull("an icon-only toggle by the chart", toggle);
            Ui ui = new Ui(app);
            assertTrue("a full touch target", toggle.width() >= ui.dp(48) - 1);
            RectF adopt = star.adoptBox();
            RectF score = star.scoreToggleBox();
            assertTrue("in the row of round buttons, between score by area and adopt",
                    score.centerX() < toggle.centerX() && toggle.centerX() < adopt.centerX()
                            && Math.abs(toggle.centerY() - adopt.centerY()) < 1);
            for (int axis = 0; axis < 5; axis++) {
                float[] knob = star.knobAt(axis);
                assertTrue("clear of the knobs", Math.hypot(knob[0] - toggle.centerX(), knob[1] - toggle.centerY())
                        >= ui.dp(24) + toggle.width() / 2 - 1);
            }

            tap(sky, toggle.centerX(), toggle.centerY());
            FilterSettings saved = FilterStore.load(app);
            assertFalse("off, saved at once", saved.risingOffers);
            assertEquals("what it learned is kept", 1420, saved.lastAcceptedCents);
            assertEquals("$0.59/min, $2.37/mi, $7.10/stop", saved.best.summary());
            assertTrue(saved.enabled);
            assertArrayEquals(new int[] {700, 150, 30, 100, 0, 0}, saved.minimums());
            assertEquals(3, saved.maxStops);
            assertTrue(star.lastSaid(), star.lastSaid().startsWith("Adaptive minimum off."));
            assertTrue(star.getContentDescription().toString()
                    .contains("Adaptive minimum is off, so the adaptive values are not applied."));
            assertNull("nothing to adopt with it off", star.adoptBox());
            assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("[rules] adaptive minimum off"));

            settleSky(content);
            toggle = star.adaptiveBox();
            tap(sky, toggle.centerX(), toggle.centerY());
            assertTrue("on again", FilterStore.load(app).risingOffers);
            assertTrue(star.lastSaid(), star.lastSaid().startsWith("Adaptive minimum on."));
            assertFalse("no page opens", settingsShown(content));
        }
    }

    @Test
    public void holdingTheAdaptiveToggleAsksBeforeResettingWhatItLearned() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            ViewGroup sky = (ViewGroup) star.getParent();

            // Held: a confirm asks first; Cancel leaves everything; the finger lifting toggles nothing.
            hold(sky, star.adaptiveBox());
            ShadowAlertDialog asked = Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog());
            assertEquals("Reset learned minimums?", asked.getTitle().toString());
            assertTrue("the hold does not toggle it", FilterStore.load(app).risingOffers);
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("cancelled: nothing forgotten", 1420, FilterStore.load(app).lastAcceptedCents);

            settleSky(content);
            hold(sky, star.adaptiveBox());
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FilterSettings saved = FilterStore.load(app);
            assertEquals("reset: the highest accepted pay forgotten", 0, saved.lastAcceptedCents);
            assertTrue("and every best rate", saved.best.isEmpty());
            assertTrue("the switch stays as it was", saved.risingOffers);
            assertArrayEquals("the set minimums stay", new int[] {700, 150, 30, 100, 0, 0}, saved.minimums());
            assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("[rules] adaptive minimum reset"));
            assertTrue(FilterStore.learningTimes(app)[2] > 0);
        }
    }

    @Test
    public void screenReadersHearTheAdaptiveToggleAsASwitchWithResetAmongItsActions() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AccessibilityNodeInfo toggle = node(star, MinimumsStarView.ADAPTIVE_ID);
            assertNotNull(toggle);
            assertEquals("Adaptive minimum", toggle.getContentDescription().toString());
            assertEquals(Switch.class.getName(), toggle.getClassName().toString());
            assertTrue(toggle.isCheckable());
            assertTrue(toggle.isChecked());
            List<String> actions = new ArrayList<>();
            for (AccessibilityNodeInfo.AccessibilityAction action : toggle.getActionList()) {
                if (action.getLabel() != null) actions.add(action.getId() + "=" + action.getLabel());
            }
            assertTrue(actions.toString(), actions.contains(MinimumsStarView.TOGGLE_ACTION
                    + "=Turn adaptive minimum off"));
            assertTrue(actions.toString(), actions.contains(MinimumsStarView.RESET_ACTION
                    + "=Reset learned minimums"));

            assertTrue(act(star, MinimumsStarView.ADAPTIVE_ID, MinimumsStarView.TOGGLE_ACTION));
            assertFalse(FilterStore.load(app).risingOffers);
            assertFalse(node(star, MinimumsStarView.ADAPTIVE_ID).isChecked());
            assertTrue(act(star, MinimumsStarView.ADAPTIVE_ID, AccessibilityNodeInfo.ACTION_CLICK));
            assertTrue(FilterStore.load(app).risingOffers);

            // Reset asks first, as the hold does.
            assertTrue(act(star, MinimumsStarView.ADAPTIVE_ID, MinimumsStarView.RESET_ACTION));
            assertEquals(1420, FilterStore.load(app).lastAcceptedCents);
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, FilterStore.load(app).lastAcceptedCents);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherTheBadgeAndTheButtonsFitClearOfEachOtherAndTheCounts() {
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        FilterStore.save(app, new FilterSettings(true, 1300, 385, 41, 475, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(2250, 5.2, 28, 2));
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
            RectF[] controls = {star.stopsBox(), star.adaptiveBox(), star.scoreToggleBox(), star.adoptBox()};
            for (int i = 0; i < controls.length; i++) {
                assertNotNull("shown " + i, controls[i]);
                assertTrue("inside the page " + controls[i], controls[i].left >= 0
                        && controls[i].right <= star.getWidth() && controls[i].top >= 0
                        && controls[i].bottom <= star.getHeight());
                assertFalse("clear of the counts " + controls[i], RectF.intersects(controls[i], counts));
                for (int j = i + 1; j < controls.length; j++) {
                    assertFalse("apart " + i + "/" + j, RectF.intersects(controls[i], controls[j]));
                }
            }
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    /** A finger held on {@code box} past Android's long-press time, then lifted. */
    private static void hold(ViewGroup sky, RectF box) {
        long now = android.os.SystemClock.uptimeMillis();
        float x = box.centerX();
        float y = box.centerY();
        sky.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100));
        long later = android.os.SystemClock.uptimeMillis();
        sky.dispatchTouchEvent(MotionEvent.obtain(now, later, MotionEvent.ACTION_UP, x, y, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
