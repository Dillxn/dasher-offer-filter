package com.local.dasherfilter;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The homepage in the layouts of a dash (the owner: "split screen at 50/50 doesn't leave enough room for seeing gps
 * well inside the dd app"; "the user isn't juggling multiple windows (dasher, filter, gps) and can just flow"; and on
 * 0.5.1's split screen: "I do not like how it only shows the map or the radar"). One page at every size: along its top
 * the mascot, the latest offer's verdict, Autopilot's chip and the filter's status, whole at the normal and twice the
 * font; a setup step still to do under the header; the radar and the map together wherever they fit. Beside Dasher,
 * once, how to give its map more room; beside a map during a dash, that background offers need a tap there, with Swap;
 * full screen, Dasher one tap away. Which half Android gives Dasher on a swap, and how the divider feels, are for a
 * handset; how the page changes as the divider moves is FluidPageTest's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class DrivingLayoutTest extends AndroidAdapterTestBase {
    private static final ComponentName DASHER_HOME =
            new ComponentName("com.doordash.driverapp", "com.doordash.driverapp.Home");

    private ServiceController<OfferFilterService> screen;
    private ServiceController<OfferNotificationService> listener;

    @After
    public void stopServices() {
        if (listener != null && OfferNotificationService.isConnected()) listener.destroy();
        if (screen != null) screen.destroy();
        OfferFilterService.sawDasherBeside(0);
        DasherSplit.forget();
        Peek.resumeNow(app);
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.forgetCache();
        RuntimeEnvironment.setFontScale(1f);
    }

    /** Screen reading and background offers both on, as during a dash. */
    private void servicesUp() {
        screen = Robolectric.buildService(OfferFilterService.class).create();
        screen.get().onServiceConnected();
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        settle();
    }

    private static ActivityController<MainActivity> splitScreen() {
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        return built.setup();
    }

    private static View content(ActivityController<MainActivity> activity) {
        return activity.get().findViewById(android.R.id.content);
    }

    private Intent started() {
        return Shadows.shadowOf(app).getNextStartedActivity();
    }

    private void logSays(String part) {
        String log = DiagnosticLog.read(app);
        assertTrue(part + " in:\n" + log, log.contains(part));
    }

    /**
     * The least words of {@code sp} may shrink to beside the chip: three quarters of the user's size, never below their
     * default size (as a setup line's words, SetupRow.Words).
     */
    private float least(float sp) {
        android.util.DisplayMetrics metrics = app.getResources().getDisplayMetrics();
        float full = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics);
        return Math.min(full, Math.max(SetupRow.LEAST_SCALE * full,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sp, metrics)));
    }

    /** Lets the page refresh (it does each second, and when Autopilot tells it). */
    private static void refreshed() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
    }

    /** Every Autopilot chip on screen under {@code view}. */
    private static List<AutopilotChip> shownChips(View view) {
        List<AutopilotChip> found = new ArrayList<>();
        if (view instanceof AutopilotChip && view.isShown()) found.add((AutopilotChip) view);
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                found.addAll(shownChips(((ViewGroup) view).getChildAt(i)));
            }
        }
        return found;
    }

    /** A view whose words a screen reader would read out by itself (a live region), or null. */
    private static View liveRegionIn(View view) {
        if (view.getAccessibilityLiveRegion() != View.ACCESSIBILITY_LIVE_REGION_NONE) return view;
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = liveRegionIn(((ViewGroup) view).getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String title(AlertDialog dialog) {
        return String.valueOf(Shadows.shadowOf(dialog).getTitle());
    }

    /** Shown through OwnWindowTouches.show: a touch on it stays this app's own in split screen. */
    private static void assertOwnsItsTouches(AlertDialog dialog) {
        assertTrue(dialog.getWindow().getCallback().getClass().getName(),
                dialog.getWindow().getCallback().getClass().getName().endsWith("OwnWindowTouches$TrackedCallback"));
    }

    /** Where {@code view}'s bottom stands in its window, in pixels. */
    private static int inWindowBottom(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1] + view.getHeight();
    }

    /** {@code view} wholly on screen as the page opens (without scrolling). */
    private static void assertWhollyOnScreen(String what, View view) {
        Rect visible = new Rect();
        assertTrue(what + " on screen", view.getGlobalVisibleRect(visible));
        assertEquals(what + " wholly on screen", view.getHeight(), visible.height());
        assertEquals(what + " wholly on screen", view.getWidth(), visible.width());
    }

    // ---- About a third of a split screen: the page, its strip along the top ----

    /**
     * A third of a split screen is the homepage, not a page of its own: along its top the mascot (which pauses and
     * resumes), the latest offer's verdict in the app's words with how long ago, Autopilot's chip and the status line;
     * under them, the radar and the offer map together, as much of them as the room holds.
     */
    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void aThirdOfASplitScreenIsTheHomepageItsStripAlongTheTop() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        DecisionLog.Entry latest = declinedEntry();
        DecisionLog.record(app, latest);
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            layOut(content);
            ScenePage page = find(content, ScenePage.class);
            assertTrue("one page at every size", page.isShown());
            TextView verdict = verdictLine(content);
            assertEquals(MainActivity.verdict(latest, System.currentTimeMillis()), verdict.getText().toString());
            assertTrue(verdict.getText().toString(), verdict.getText().toString().startsWith("Declined $7.90 · 7.2 mi: ")
                    && verdict.getText().toString().endsWith(" · just now"));
            assertWhollyOnScreen("the verdict", verdict);
            assertEquals(MainActivity.ON_LINE, statusLine(content).getText().toString());
            assertWhollyOnScreen("the status line", statusLine(content));
            View mascot = find(content, FilterHeroView.class).mascotControl();
            assertTrue(String.valueOf(mascot.getContentDescription()),
                    String.valueOf(mascot.getContentDescription()).endsWith("Pause auto-decline."));
            assertTrue("a full touch target", mascot.getHeight() >= new Ui(app).dp(48));
            assertWhollyOnScreen("the mascot", mascot);
            List<AutopilotChip> chips = shownChips(content);
            assertEquals("Autopilot's chip", 1, chips.size());
            assertTrue("the strip's own", isDescendant(stripWords(content), chips.get(0)));
            assertEquals("off, it is the way in", "Auto off", chips.get(0).getText().toString());
            assertWhollyOnScreen("the chip", chips.get(0));
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue("the radar and the map on the same page", isDescendant(page, star) && isDescendant(page, map));
            assertEquals("the radar and the map wholly there together", 1f, page.shown(star), 0f);
            assertEquals(1f, page.shown(map), 0f);
            assertTrue("side by side in a short window", star.getRight() <= map.getLeft());

            mascot.performClick();
            settle();
            assertFalse("the mascot pauses", FilterStore.load(app).enabled);
            assertEquals(MainActivity.PAUSED_LINE, statusLine(content).getText().toString());
            assertTrue(String.valueOf(mascot.getContentDescription()).endsWith("Resume auto-decline."));
            mascot.performClick();
            settle();
            assertTrue("and resumes", FilterStore.load(app).enabled);
            assertEquals(MainActivity.ON_LINE, statusLine(content).getText().toString());
        }
    }

    /**
     * A setup step still to do stays on the page at every size, under the header, and its tap is its Fix: here
     * notification access, in the homepage's own words (SetupChecklist).
     */
    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void aSetupStepStaysOnThePageAndItsTapIsTheFix() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        screen = Robolectric.buildService(OfferFilterService.class).create();
        screen.get().onServiceConnected();
        settle();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            layOut(content);
            assertEquals("no offer yet, and not ready for one while a step is to do", "No offers yet",
                    verdictLine(content).getText().toString());
            TextView step = shownTextContaining(content, SetupChecklist.NOTIFICATIONS);
            assertNotNull("the step to do, on the page", step);
            assertWhollyOnScreen("the step", step);
            ((View) step.getParent()).performClick();
            Intent fix = started();
            assertNotNull("the step's own Fix: Android's notification access", fix);
            assertTrue(fix.getAction(), android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS
                    .equals(fix.getAction())
                    || android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS.equals(fix.getAction()));
        }
    }

    /**
     * With no rule yet the mascot and the status line offer the typical minimums. "Set my own" points at the knobs
     * where the radar is wholly there; in a window too short for it, it says how to make room, saves nothing, and the
     * page is the same page once the divider gives it room: the radar there with its knobs, no mode chosen. "Use these"
     * saves the minimums with auto-decline paused, then asks for Autopilot's goal, and the mascot turns it on.
     */
    @Test @Config(qualifiers = "w411dp-h220dp-420dpi")
    public void withNoRuleYetSetMyOwnSaysHowToReachTheKnobs() {
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            refreshed();
            layOut(content);
            ScenePage page = find(content, ScenePage.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue("too short for the radar", page.shown(star) < 1);
            assertEquals(MainActivity.START_LINE, statusLine(content).getText().toString());
            View mascot = find(content, FilterHeroView.class).mascotControl();
            assertTrue(String.valueOf(mascot.getContentDescription()).endsWith("Set up rules."));
            mascot.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog starter = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(starter);
            assertEquals(MainActivity.STARTER_TITLE, title(starter));
            assertOwnsItsTouches(starter);
            assertEquals(MainActivity.STARTER_OWN, starter.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            starter.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            refreshed();
            assertEquals("how to reach the knobs, which need more room", MainActivity.KNOBS_NEED_ROOM,
                    ShadowToast.getTextOfLatestToast());
            assertFalse("nothing saved", FilterStore.load(app).hasAnyRule());
            assertSame("nothing else opens", starter, ShadowAlertDialog.getLatestAlertDialog());

            // The divider dragged: the same page, laid out again, with the radar and its knobs.
            resizeWindow(activity, "w411dp-h420dp-420dpi");
            refreshed();
            assertSame("laid out again, not made again", page, find(content(activity), ScenePage.class));
            assertEquals("the radar wholly there", 1f, page.shown(star), 0f);
            assertTrue(page.stageShown());
        }

        // "Use these": the typical minimums, paused, then the goal chooser.
        RuntimeEnvironment.setQualifiers("w411dp-h300dp-420dpi");
        ShadowAlertDialog.reset();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            refreshed();
            statusLine(content).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the status line offers the same", MainActivity.STARTER_TITLE,
                    title(ShadowAlertDialog.getLatestAlertDialog()));
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            refreshed();
            FilterSettings saved = FilterStore.load(app);
            assertEquals(400, saved.flatCents);
            assertEquals(100, saved.perMileCents);
            assertEquals(25, saved.perMinuteCents);
            assertFalse("auto-decline stays paused: the mascot turns it on", saved.enabled);
            assertEquals(MainActivity.FIRST_RULE, ShadowToast.getTextOfLatestToast());
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.CHOOSER_TITLE, title(chooser));
            assertOwnsItsTouches(chooser);
            chooser.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            refreshed();
            assertTrue(String.valueOf(find(content, FilterHeroView.class).mascotControl().getContentDescription())
                    .endsWith("Resume auto-decline."));
        }
    }

    /** Half of a split screen beside Dasher: the radar and the map together (0.5.1 showed only one of them there). */
    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void halfOfASplitScreenBesideDasherShowsTheRadarAndTheMapTogether() {
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        DecisionLog.record(app, declinedEntry());
        servicesUp();
        OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            refreshed();
            layOut(content);
            ScenePage page = find(content, ScenePage.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue(star.isShown() && map.isShown());
            assertEquals(1f, page.shown(star), 0f);
            assertEquals(1f, page.shown(map), 0f);
            assertTrue("side by side", star.getRight() <= map.getLeft());
            assertWhollyOnScreen("the radar", star);
            assertWhollyOnScreen("the map", map);
        }
    }

    /** A phone on its side during a dash: the radar and the map side by side under the strip (0.5.1 kept them too). */
    @Test @Config(qualifiers = "w700dp-h300dp-420dpi")
    public void aShortFullScreenShowsTheRadarAndTheMapSideBySide() {
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        DecisionLog.record(app, declinedEntry());
        servicesUp();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            refreshed();
            layOut(content);
            ScenePage page = find(content, ScenePage.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AreaMapView map = find(content, AreaMapView.class);
            assertEquals(1f, page.shown(star), 0f);
            assertEquals(1f, page.shown(map), 0f);
            assertTrue("side by side", star.getRight() <= map.getLeft());
        }
    }

    // ---- The strip's Autopilot chip ----

    /** The longest Peek pause: Peek's own words for two launches that never came up. */
    private static final String LONGEST_PAUSE = "Dasher did not come up for 2 peeks in a row (the phone may block apps "
            + "opening from the background)";

    /**
     * Autopilot's chip at the status line's start, at every size: a whole 48 dp target on one line, heard in words and
     * never a live region, a tap opening Autopilot's details and a long press the goal chooser, each through the app's
     * own dialog window. Beside the status line it costs the strip no height, and it follows Autopilot, on or off
     * ("Auto off" being the way back in). While the radar is wholly there its own Auto button is there too.
     */
    @Test @Config(qualifiers = "w411dp-h300dp-420dpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theStripsAutopilotChipIsAWholeTargetAtNoHeight() {
        AutopilotRuntime.executorForTests = Runnable::run;
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        DecisionLog.record(app, declinedEntry());
        servicesUp();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            refreshed();
            layOut(content);
            List<AutopilotChip> chips = shownChips(content);
            assertEquals("one chip on screen", 1, chips.size());
            AutopilotChip chip = chips.get(0);
            assertTrue("the strip's own chip", isDescendant(stripWords(content), chip));
            AutopilotText.Status status = AutopilotRuntime.status(app, System.currentTimeMillis(), true);
            assertEquals("Auto learning", AutopilotText.chip(status));
            assertEquals(AutopilotText.chip(status), chip.getText().toString());
            assertEquals("heard in words, with what a tap does", AutopilotText.chipDescription(status),
                    String.valueOf(chip.getContentDescription()));
            assertEquals("never a live region", View.ACCESSIBILITY_LIVE_REGION_NONE, chip.getAccessibilityLiveRegion());
            Ui ui = new Ui(app);
            assertTrue("a 48 dp target", chip.getHeight() >= ui.dp(48) - 1 && chip.getWidth() >= ui.dp(48) - 1);
            assertEquals("one line, never cut short", 0, chip.getLayout().getEllipsisCount(0));
            assertWhollyOnScreen("the chip", chip);

            // At the status line's start, costing the strip no height: the row is the line's own height, and nothing
            // shrinks at the normal size.
            TextView line = statusLine(content);
            assertEquals(AutopilotText.statusLine(status), line.getText().toString());
            AutopilotChip.Row row = (AutopilotChip.Row) chip.getParent();
            assertSame("beside the status line", row, line.getParent());
            assertFalse("never on a line of its own here", row.stacked());
            int rowWith = row.getHeight();
            float size = line.getTextSize();
            chip.setVisibility(View.GONE);
            layOut(content);
            int rowAlone = row.getHeight();
            chip.setVisibility(View.VISIBLE);
            layOut(content);
            assertEquals("no added height", rowAlone, rowWith);
            assertEquals("at the normal size nothing shrinks", TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    14, app.getResources().getDisplayMetrics()), size, 0.01f);

            // A tap opens the details; a long press asks for the goal.
            ShadowAlertDialog.reset();
            chip.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(details);
            assertEquals(AutopilotText.DETAILS_TITLE, title(details));
            assertOwnsItsTouches(details);
            details.dismiss();
            chip.performLongClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.CHOOSER_TITLE, title(chooser));
            assertOwnsItsTouches(chooser);
            chooser.dismiss();

            // The radar is wholly there in this window, at its least, its knobs working; its own Auto button needs
            // more room beside them, so here, as in 0.5.1's short windows, the chip is Autopilot's control.
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertEquals(1f, find(content, ScenePage.class).shown(star), 0f);
            for (int axis : MinimumsStarView.SPOKES) assertNotNull("knob " + axis, star.knobAt(axis));

            // Off: still there, the way back in, still at no height.
            AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
            refreshed();
            layOut(content);
            assertEquals("Auto off", chip.getText().toString());
            assertTrue(chip.isShown());
            assertEquals(rowAlone, row.getHeight());
            assertEquals(1, shownChips(content).size());
            assertNull("no live region anywhere in the strip", liveRegionIn(stripWords(content)));
        }
    }

    /**
     * The verdict's words are the caption's: an offer Autopilot let through below the minimums never reads as a full
     * pass, and says why without saying "below your minimums" twice.
     */
    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void theVerdictNeverCallsAPassBelowTheMinimumsAFullPass() {
        FilterSettings rules = FilterSettings.of(true, 400, 100, 25, 3).withAutopilot(true, 70)
                .withMinimumScalePercent(82);
        // $5.75 for 6.6 mi and 27 min asks $6.75 at the minimums, $5.54 at an 82% bar.
        OfferSnapshot facts = new OfferSnapshot(575, 6.6, 27, 2);
        OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
        assertEquals("below your minimums; passes the 82% bar", decision.reason);
        DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision,
                DecisionLog.Action.PASSES, true, Collections.singletonList("$5.75"))
                .withTime(System.currentTimeMillis() - 5_000));
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            String said = verdictLine(content(activity)).getText().toString();
            assertTrue(said, said.startsWith(AutopilotText.PASSED_BELOW_MINIMUMS + " $5.75 · 6.6 mi: "));
            assertFalse("once", said.substring(AutopilotText.PASSED_BELOW_MINIMUMS.length())
                    .contains("elow your minimums"));
            assertTrue(said, said.endsWith(" · just now"));
        }
    }

    @Test @Config(qualifiers = "w411dp-h300dp-420dpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTheNormalFontAPeekPauseAndTheChipFitAThirdOfASplitScreen() {
        stripAt(1f, "peek");
    }

    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTheNormalFontTheChipAndItsLineFitAThirdOfASmallPhone() {
        stripAt(1f, "on");
    }

    @Test @Config(qualifiers = "w411dp-h300dp-420dpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheFontTheStripKeepsTheChipAndItsLineWhole() {
        stripAt(2f, "on");
    }

    /** At twice the font the longest pause cannot fit a third of a phone: the page scrolls to it, nothing cut. */
    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheFontTheLongestPauseIsNeverCutThePageScrollsToItsEnd() {
        stripAt(2f, "peek");
    }

    /**
     * The strip at {@code fontScale}, Autopilot on, and Peek paused with its longest reason ("peek") or not ("on"):
     * the mascot, the whole verdict, the whole chip (one line, a 48 dp target, never below three quarters of the user's
     * size nor the default size) and every word of Autopilot's status line; the mascot and the verdict on screen as
     * the page opens, and the chip and the status line too wherever the strip fits the window (at twice the font in a
     * third of a phone it may not: they are then a scroll away, as 0.5.1's strip scrolled to its line). The line
     * saying why Peek paused is whole on the page, a scroll away at most. The chip adds no height of its own: the row
     * is the chip's 48 dp or the status line's words beside it, whichever is taller, and at the normal size nothing
     * shrinks.
     */
    private void stripAt(float fontScale, String line) {
        RuntimeEnvironment.setFontScale(fontScale);
        AutopilotRuntime.executorForTests = Runnable::run;
        dasherInstalled();
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        DecisionLog.record(app, declinedEntry());
        servicesUp();
        if (line.equals("peek")) Peek.pause(app, LONGEST_PAUSE);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            refreshed();
            layOut(content);
            AutopilotChip chip = find(stripWords(content), AutopilotChip.class);
            AutopilotChip.Row row = (AutopilotChip.Row) chip.getParent();
            TextView status = statusLine(content);
            String words = AutopilotText.statusLine(AutopilotRuntime.status(app, System.currentTimeMillis(), true));
            assertEquals(words, status.getText().toString());
            String measured = "font " + fontScale + ", " + line + ": strip " + stripWords(content).getHeight()
                    + ", row " + row.getHeight() + " (stacked " + row.stacked() + ", scale " + row.textScale()
                    + ", tight " + row.tight() + "), line " + status.getLineCount() + " lines";

            // The chip: whole, one line, a full target, its words no smaller than the least.
            assertTrue(chip.isShown());
            assertEquals("one line, never cut short: " + measured, 0, chip.getLayout().getEllipsisCount(0));
            assertEquals(1, chip.getLineCount());
            Ui ui = new Ui(app);
            assertTrue(chip.getHeight() >= ui.dp(48) - 1 && chip.getWidth() >= ui.dp(48) - 1);
            assertTrue("the chip never below three quarters of the user's size nor its default size: " + measured,
                    chip.getTextSize() >= least(13) - 0.5f);
            assertTrue("nor the line's words: " + measured, status.getTextSize() >= least(14) - 0.5f);
            if (fontScale <= 1f) {
                assertEquals("at the normal size neither shrinks: " + measured, TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 13, app.getResources().getDisplayMetrics()), chip.getTextSize(),
                        0.01f);
            }

            // Every word of the line, inside its own height, and the strip on screen as the page opens.
            assertEquals("every word: " + measured, words.length(),
                    status.getLayout().getLineEnd(status.getLineCount() - 1));
            assertTrue("inside the line: " + measured, status.getLayout().getHeight()
                    <= status.getHeight() - status.getTotalPaddingTop() - status.getTotalPaddingBottom());
            assertTrue(status.getHeight() >= ui.dp(48) - 1);
            assertWhollyOnScreen("the mascot; " + measured, find(content, FilterHeroView.class).mascotControl());
            assertWhollyOnScreen("the verdict; " + measured, verdictLine(content));
            android.widget.ScrollView scroller = (android.widget.ScrollView) find(content, ScenePage.class).getParent();
            int windowHeight = scroller.getHeight();
            boolean stripFits = inWindowBottom(stripWords(content)) <= windowHeight;
            if (fontScale <= 1f) assertTrue("at the normal size the strip fits: " + measured, stripFits);
            if (!stripFits) {
                scroller.scrollTo(0, Math.max(0, scroller.getChildAt(0).getHeight() - windowHeight));
                layOut(content);
            }
            for (View part : new View[] {chip, status}) assertWhollyOnScreen(part + "; " + measured, part);
            scroller.scrollTo(0, 0);
            layOut(content);
            assertEquals("the row is the chip's or its words', whichever is taller: " + measured,
                    Math.max(chip.getHeight(), status.getHeight()), row.getHeight());

            // Why Peek paused: whole on the page, its Resume its own; a scroll away at most.
            if (line.equals("peek")) {
                TextView paused = shownTextContaining(content, LONGEST_PAUSE);
                assertNotNull(paused);
                assertEquals("every word", paused.getText().length(),
                        paused.getLayout().getLineEnd(paused.getLineCount() - 1));
                scroller.scrollTo(0, Math.max(0, scroller.getChildAt(0).getHeight() - scroller.getHeight()));
                layOut(content);
                // At twice the font in a third of a phone the line can be taller than the window: its end, then.
                Rect visible = new Rect();
                assertTrue(paused.getGlobalVisibleRect(visible));
                int[] at = new int[2];
                paused.getLocationInWindow(at);
                assertEquals("its end on screen, scrolled to: " + measured, at[1] + paused.getHeight(), visible.bottom);
                if (paused.getHeight() <= scroller.getHeight()) {
                    assertEquals("whole, where the window holds it: " + measured, paused.getHeight(),
                            visible.height());
                }
                ((View) paused.getParent()).performClick();
                assertNull("Resume: Peek works again", Peek.pausedWhy(app));
            }
        }
    }

    // ---- The verdict: every word, at any font ----

    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheFontAPassBelowTheMinimumsKeepsEveryWordOfItsVerdict() {
        verdictAt(2f, "below");
    }

    @Test @Config(qualifiers = "w320dp-h280dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void inANarrowWindowAtTwiceTheFontAPassBelowTheMinimumsKeepsEveryWordOfItsVerdict() {
        verdictAt(2f, "below");
    }

    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheFontAnAutomaticAcceptanceKeepsEveryWordOfItsVerdict() {
        verdictAt(2f, "automatic");
    }

    /** An automatic Accept Dasher has not confirmed yet: the longest verdict, in the narrowest window. */
    @Test @Config(qualifiers = "w320dp-h280dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheFontEvenTheLongestVerdictKeepsEveryWord() {
        verdictAt(2f, "requested");
    }

    /** At the normal font the longest verdict is on one line where a wide window has room for it. */
    @Test @Config(qualifiers = "w731dp-h240dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTheNormalFontTheLongestVerdictIsOneLineWhereItFits() {
        verdictAt(1f, "requested");
    }

    /**
     * The verdict at {@code fontScale} with Autopilot on and its latest offer one of the longest verdicts: one
     * Autopilot let through below the minimums ("below"), one the app accepted by itself ("automatic"), or an automatic
     * Accept Dasher has not confirmed ("requested"). Every word of it is laid out inside its own height (on as many
     * lines as it takes, one where it fits) and the whole verdict is on screen at the top of the page.
     */
    private void verdictAt(float fontScale, String kind) {
        RuntimeEnvironment.setFontScale(fontScale);
        AutopilotRuntime.executorForTests = Runnable::run;
        long at = System.currentTimeMillis() - 5_000;
        String outcome;
        if (kind.equals("below")) {
            // $5.75 for 6.6 mi and 27 min asks $6.75 at the minimums, $5.54 at an 82% bar.
            OfferSnapshot facts = new OfferSnapshot(575, 6.6, 27, 2);
            OfferRule.Decision decision = OfferRule.evaluate(facts, FilterSettings.of(true, 400, 100, 25, 3)
                    .withAutopilot(true, 70).withMinimumScalePercent(82));
            DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision,
                    DecisionLog.Action.PASSES, true, Collections.singletonList("$5.75")).withTime(at));
            outcome = AutopilotText.PASSED_BELOW_MINIMUMS + " $5.75";
        } else {
            DecisionLog.Entry offer = new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false,
                    new OfferSnapshot(1250, 3.0, 15, 2), 1000, OfferRule.Result.KEEP, "meets your minimums",
                    DecisionLog.Action.PASSES, true, Collections.singletonList("$12.50"))
                    .withStep(new DecisionLog.Step(DecisionLog.StepKind.AUTO_ACCEPT_REQUESTED, at + 10,
                            "automatic Accept requested"));
            if (kind.equals("automatic")) {
                offer = offer.withStep(new DecisionLog.Step(DecisionLog.StepKind.ACCEPTED_AUTOMATIC, at + 500,
                        "automatic Accept was requested, and Dasher showed a delivery screen"));
            }
            DecisionLog.record(app, offer);
            outcome = (kind.equals("automatic") ? "Automatically accepted" : "Accept requested, not confirmed")
                    + " $12.50";
        }
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        if (kind.equals("below")) assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            refreshed();
            layOut(content);
            TextView verdict = verdictLine(content);
            String words = verdict.getText().toString();
            assertTrue(words, words.startsWith(outcome) && words.endsWith(" · just now"));
            String measured = "font " + fontScale + ", " + kind + ": verdict " + verdict.getHeight() + " ("
                    + verdict.getLineCount() + " lines, laid out " + verdict.getLayout().getHeight() + ")";

            // Every word of the verdict, inside its own height: none cut off, with a mark or without.
            assertEquals("every word: " + measured, words.length(),
                    verdict.getLayout().getLineEnd(verdict.getLineCount() - 1));
            assertEquals("never shortened with a mark either: " + measured, 0,
                    verdict.getLayout().getEllipsisCount(verdict.getLineCount() - 1));
            assertTrue("inside the verdict: " + measured, verdict.getLayout().getHeight()
                    <= verdict.getHeight() - verdict.getTotalPaddingTop() - verdict.getTotalPaddingBottom());
            if (verdict.getPaint().measureText(words) <= verdict.getWidth() - verdict.getTotalPaddingLeft()
                    - verdict.getTotalPaddingRight()) {
                assertEquals("one line where it fits: " + measured, 1, verdict.getLineCount());
            }
            assertWhollyOnScreen("the whole verdict: " + measured, verdict);
        }
    }

    // ---- Beside Dasher: the divider hint, once ----

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherTheDividerHintShowsOnceOverThePageAndGoesByItself() {
        servicesUp();
        OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            TextView hint = shownTextContaining(content, SplitLines.HINT);
            assertNotNull("beside Dasher: how to give its map more room", hint);
            assertEquals("Drag the divider toward Offer Filter to give Dasher's map more room",
                    hint.getText().toString());
            assertFalse("words only: a touch goes to the page under them", hint.isClickable());
            assertFalse("over the page, not in it", isDescendant(find(content, ScenePage.class), hint));
            assertEquals("read out as it appears: a live region while it is up",
                    View.ACCESSIBILITY_LIVE_REGION_POLITE, hint.getAccessibilityLiveRegion());
            assertTrue(SplitLines.hintShown(app));
            logSays("[split] divider hint shown (once)");

            iconButton(content, "Settings").performClick();
            settle();
            assertNull("only over the homepage", shownTextContaining(content, SplitLines.HINT));
            activity.get().onBackPressed();
            settle();
            assertNotNull(shownTextContaining(content, SplitLines.HINT));

            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SplitLines.HINT_MS));
            assertNull("gone by itself", shownTextContaining(content, SplitLines.HINT));
            assertEquals("gone, it is no live region", View.ACCESSIBILITY_LIVE_REGION_NONE,
                    hint.getAccessibilityLiveRegion());
            assertNull("so nothing on the page can speak by itself (Autopilot's quiet rule)", liveRegionIn(content));
        }
        OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
        try (ActivityController<MainActivity> again = splitScreen()) {
            assertNull("never again", shownTextContaining(content(again), SplitLines.HINT));
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void noDividerHintBesideAnotherApp() {
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            assertNull(shownTextContaining(content(activity), SplitLines.HINT));
            assertFalse(SplitLines.hintShown(app));
        }
    }

    // ---- Beside a map during a dash: the layout note and Swap ----

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void duringADashBesideAMapTheLayoutNoteOffersToSwapInDasherSoTheMapStays() {
        dasherInstalled();
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            assertNull("no dash: nothing to say", shownTextContaining(content, "need a tap in this layout"));
            assertNotNull("outside a dash the button puts Dasher beside",
                    shownIcon(content, DasherSplit.BESIDE_LABEL));
        }
        Dashing.seen(app);
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            TextView note = shownTextContaining(content, "need a tap in this layout");
            assertNotNull("during a dash beside another app", note);
            assertEquals("Background offers need a tap in this layout — use Dasher full screen, or Maps with Dasher "
                    + "beside", note.getText().toString());
            assertNotNull("the header's button says what it does now", shownIcon(content, DasherSplit.SWAP_LABEL));
            ((View) note.getParent()).performClick();
            Intent swap = started();
            assertNotNull(swap);
            assertEquals(DASHER_HOME, swap.getComponent());
            assertEquals("Dasher into this half, as its launcher opens it: no adjacent launch, nothing cleared",
                    Intent.FLAG_ACTIVITY_NEW_TASK, swap.getFlags());
            logSays("[split] tap in split screen during a dash: Dasher's launch intent into this half (the other half "
                    + "stays)");

            shownIcon(content, DasherSplit.SWAP_LABEL).performClick();
            Intent again = started();
            assertNotNull(again);
            assertEquals("the header's button does the same", 0,
                    again.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void noLayoutNoteWithPeekOffOrBesideDasher() {
        dasherInstalled();
        servicesUp();
        Dashing.seen(app);
        FilterStore.setPeek(app, false);
        try (ActivityController<MainActivity> activity = splitScreen()) {
            assertNull("Peek off: a tap is the way in anyway",
                    shownTextContaining(content(activity), "need a tap in this layout"));
        }
        FilterStore.setPeek(app, true);
        OfferFilterService.sawDasherBeside(SystemClock.uptimeMillis());
        try (ActivityController<MainActivity> activity = splitScreen()) {
            assertNull("beside Dasher, offers show there", shownTextContaining(content(activity),
                    "need a tap in this layout"));
        }
    }

    // ---- Full screen: Open Dasher ----

    @Test @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void openDasherIsOneHeaderTapToDasherFullScreen() {
        dasherInstalled();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View open = shownIcon(content(activity), DasherSplit.OPEN_LABEL);
            assertNotNull("in the header, full screen", open);
            assertTrue(open.getWidth() >= new Ui(app).dp(48) || open.getLayoutParams().width >= new Ui(app).dp(48));
            open.performClick();
            Intent opened = started();
            assertNotNull(opened);
            assertEquals(DASHER_HOME, opened.getComponent());
            assertTrue(opened.hasCategory(Intent.CATEGORY_LAUNCHER));
            assertEquals("as its launcher icon opens it: its task as it was, never beside", Intent.FLAG_ACTIVITY_NEW_TASK,
                    opened.getFlags());
            logSays("[split] Open Dasher tapped: Dasher's launch intent");
        }
    }

    @Test @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void noOpenDasherWithoutDasher() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull(shownIcon(content(activity), DasherSplit.OPEN_LABEL));
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void noOpenDasherInASplitScreen() {
        dasherInstalled();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            assertNull("the split button puts Dasher on screen there", shownIcon(content(activity),
                    DasherSplit.OPEN_LABEL));
        }
    }
}
