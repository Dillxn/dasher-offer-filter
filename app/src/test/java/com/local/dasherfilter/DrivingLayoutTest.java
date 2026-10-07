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
 * well inside the dd app"; "the user isn't juggling multiple windows (dasher, filter, gps) and can just flow"): at
 * about a third of a split screen, one strip (the mascot, the latest verdict, Autopilot's chip as the layout's one
 * Autopilot control, and the filter's status), whole at the normal and twice the font; beside Dasher, once, how to
 * give its map more room; beside a map during a dash, that background offers need a tap there, with Swap; full screen,
 * Dasher one tap away. Which half Android gives Dasher on a swap, and how the divider feels, are for a handset.
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

    // ---- About a third of a split screen: the strip ----

    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void aThirdOfASplitScreenIsOneStripTheMascotTheLatestVerdictAndTheStatus() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        DecisionLog.record(app, declinedEntry());
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            layOut(content);
            DrivingStrip strip = find(content, DrivingStrip.class);
            assertNotNull("a third of the screen: the strip", strip);
            assertTrue(strip.isShown());
            assertFalse("nothing else: not the page", find(content, MinimumsStarView.class).isShown());
            assertEquals("Latest · $7.90 · Declined", strip.verdictText());
            assertEquals("Auto-decline is on", strip.statusText());
            View mascot = find(content, DrivingStrip.MascotButton.class);
            assertEquals("Pause auto-decline", String.valueOf(mascot.getContentDescription()));
            assertTrue("a full touch target", mascot.getHeight() >= new Ui(app).dp(48));
            List<AutopilotChip> chips = shownChips(content);
            assertEquals("Autopilot's chip: the layout's one Autopilot control", 1, chips.size());
            assertTrue("the strip's own", isDescendant(strip, chips.get(0)));
            assertEquals("off, it is the way in", "Auto off", chips.get(0).getText().toString());

            mascot.performClick();
            settle();
            assertFalse("the mascot pauses, as on the homepage", FilterStore.load(app).enabled);
            assertEquals("Paused · nothing is declined", strip.statusText());
            assertEquals("Resume auto-decline", String.valueOf(mascot.getContentDescription()));
            mascot.performClick();
            settle();
            assertTrue("and resumes", FilterStore.load(app).enabled);
            assertEquals("Auto-decline is on", strip.statusText());
            assertTrue("the divider was dragged already: no hint to come", SplitLines.hintShown(app));
        }
    }

    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void theStripSaysWhatNeedsTheUserAndItsTapIsTheFix() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        screen = Robolectric.buildService(OfferFilterService.class).create();
        screen.get().onServiceConnected();
        settle();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            DrivingStrip strip = find(content(activity), DrivingStrip.class);
            assertEquals("No offers yet", strip.verdictText());
            // The homepage's own first setup step, in its words (SetupChecklist): notification access is missing.
            assertEquals(SetupChecklist.NOTIFICATIONS, strip.statusText());
            TextView status = shownTextContaining(content(activity), SetupChecklist.NOTIFICATIONS);
            assertTrue("its tap goes to the fix", status.isClickable());
            status.performClick();
            Intent fix = started();
            assertNotNull("the step's own Fix: Android's notification access", fix);
            assertTrue(fix.getAction(), android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS
                    .equals(fix.getAction())
                    || android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS.equals(fix.getAction()));
        }
    }

    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void aThirdOfASplitScreenBesideAMapDuringADashSaysSoAndItsTapSwapsInDasher() {
        dasherInstalled();
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        servicesUp();
        Dashing.seen(app);
        try (ActivityController<MainActivity> activity = splitScreen()) {
            DrivingStrip strip = find(content(activity), DrivingStrip.class);
            assertEquals(SplitLines.LAYOUT_NOTE_SHORT, strip.statusText());
            shownTextContaining(content(activity), SplitLines.LAYOUT_NOTE_SHORT).performClick();
            Intent swap = started();
            assertNotNull(swap);
            assertEquals(DASHER_HOME, swap.getComponent());
            assertEquals("into this half: no adjacent launch, nothing cleared", Intent.FLAG_ACTIVITY_NEW_TASK,
                    swap.getFlags());
        }
    }

    /**
     * With no rule yet the strip's mascot offers the typical minimums, as the homepage's does. The strip has no knobs,
     * so "Set my own" says how to reach them and leaves the page it stands in for as it was: the constellation stays
     * in a short page's header (nothing chose the sky for it), and nothing is saved. "Use these" works from the strip
     * as from the page: the minimums saved with auto-decline paused, then the goal chooser, and the strip's mascot is
     * the one that turns it on.
     */
    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void withNoRuleYetTheStripsSetMyOwnSaysHowToReachTheKnobs() {
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            refreshed();
            DrivingStrip strip = find(content, DrivingStrip.class);
            View mascot = find(strip, DrivingStrip.MascotButton.class);
            assertEquals("Set up rules", String.valueOf(mascot.getContentDescription()));
            mascot.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog starter = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(starter);
            assertEquals(MainActivity.STARTER_TITLE, title(starter));
            assertOwnsItsTouches(starter);
            assertEquals(MainActivity.STARTER_OWN, starter.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            starter.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            refreshed();
            assertEquals("how to reach the knobs, which are not in the strip",
                    "Drag the divider for more room, then drag a knob.",
                    ShadowToast.getTextOfLatestToast());
            assertTrue(strip.isShown());
            assertFalse("nothing saved", FilterStore.load(app).hasAnyRule());
            assertSame("nothing else opens", starter, ShadowAlertDialog.getLatestAlertDialog());

            // The page this strip stands in for is as it was: made again in a short window (as a dragged divider
            // makes it), its constellation is in the header beside the map, not spread across the sky.
            RuntimeEnvironment.setQualifiers("w411dp-h380dp-420dpi");
            activity.recreate();
            View page = content(activity);
            refreshed();
            layOut(page);
            assertNull("the page now", find(page, DrivingStrip.class));
            assertEquals(380, activity.get().getResources().getConfiguration().screenHeightDp);
            MinimumsStarView star = find(page, MinimumsStarView.class);
            assertTrue(star.isShown());
            assertTrue("the constellation up in the header", star.beside());
            assertTrue("the map beside it: the sky was never chosen", find(page, AreaMapView.class).isShown());
        }

        // "Use these" from the strip: the typical minimums, paused, then the goal chooser.
        RuntimeEnvironment.setQualifiers("w411dp-h300dp-420dpi");
        ShadowAlertDialog.reset();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            refreshed();
            DrivingStrip strip = find(content, DrivingStrip.class);
            find(strip, DrivingStrip.MascotButton.class).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
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
            assertEquals("Resume auto-decline", String.valueOf(find(strip, DrivingStrip.MascotButton.class)
                    .getContentDescription()));
        }
    }

    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void halfOfASplitScreenKeepsTheWholePage() {
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            layOut(content);
            assertNull(find(content, DrivingStrip.class));
            assertTrue(find(content, MinimumsStarView.class).isShown());
        }
    }

    @Test @Config(qualifiers = "w700dp-h300dp-420dpi")
    public void aShortFullScreenKeepsTheWholePage() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull("only one half of a split screen gets the strip", find(content(activity), DrivingStrip.class));
        }
    }

    // ---- The strip's Autopilot chip ----

    /** The strip's line while Peek paused itself, its longest: Peek's own words for two launches that never came up. */
    private static final String LONGEST_PAUSE = "Dasher did not come up for 2 peeks in a row (the phone may block apps "
            + "opening from the background)";

    /**
     * In a third of a split screen Autopilot's chip is the layout's one Autopilot control (the page, with the
     * constellation's button and the page's own chip, is not shown there): at the status line's start, a whole 48 dp
     * target on one line, heard in words and never a live region, a tap opening Autopilot's details and a long press
     * the goal chooser, each through the app's own dialog window. Beside the status line it costs the strip no height,
     * and it follows Autopilot, on or off ("Auto off" being the way back in).
     */
    @Test @Config(qualifiers = "w411dp-h300dp-420dpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theStripsAutopilotChipIsTheLayoutsOneAutopilotControlAndCostsNoHeight() {
        AutopilotRuntime.executorForTests = Runnable::run;
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        DecisionLog.record(app, declinedEntry());
        servicesUp();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            refreshed();
            layOut(content);
            DrivingStrip strip = find(content, DrivingStrip.class);
            assertTrue(strip.isShown());
            List<AutopilotChip> chips = shownChips(content);
            assertEquals("one Autopilot control on screen", 1, chips.size());
            AutopilotChip chip = chips.get(0);
            assertTrue("the strip's own chip", isDescendant(strip, chip));
            assertFalse("no constellation, so no Autopilot button either", find(content, MinimumsStarView.class)
                    .isShown());
            AutopilotText.Status status = AutopilotRuntime.status(app, System.currentTimeMillis(), true);
            assertEquals("Auto learning", AutopilotText.chip(status));
            assertEquals(AutopilotText.chip(status), chip.getText().toString());
            assertEquals("heard in words, with what a tap does", AutopilotText.chipDescription(status),
                    String.valueOf(chip.getContentDescription()));
            assertEquals("never a live region", View.ACCESSIBILITY_LIVE_REGION_NONE, chip.getAccessibilityLiveRegion());
            Ui ui = new Ui(app);
            assertTrue("a 48 dp target", chip.getHeight() >= ui.dp(48) - 1 && chip.getWidth() >= ui.dp(48) - 1);
            assertEquals("one line, never cut short", 0, chip.getLayout().getEllipsisCount(0));
            Rect visible = new Rect();
            assertTrue(chip.getGlobalVisibleRect(visible));
            assertEquals("the whole chip on screen", chip.getHeight(), visible.height());
            assertEquals(chip.getWidth(), visible.width());

            // At the status line's start, costing the strip no height: the row is the line's own height, and nothing
            // shrinks at the normal size.
            TextView line = shownTextContaining(strip, "Auto-decline is on");
            assertNotNull(line);
            AutopilotChip.Row row = (AutopilotChip.Row) chip.getParent();
            assertSame("beside the status line", row, line.getParent());
            assertFalse("never on a line of its own here", row.stacked());
            View inner = strip.getChildAt(0);
            int rowWith = row.getHeight();
            int contentWith = inner.getHeight();
            float size = line.getTextSize();
            chip.setVisibility(View.GONE);
            layOut(content);
            int rowAlone = row.getHeight();
            chip.setVisibility(View.VISIBLE);
            layOut(content);
            assertEquals("no added height", rowAlone, rowWith);
            assertEquals(contentWith, inner.getHeight());
            assertEquals("the strip never scrolls here", strip.getHeight(), inner.getHeight());
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

            // Off: still there, the way back in, still at no height.
            AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
            refreshed();
            layOut(content);
            assertEquals("Auto off", chip.getText().toString());
            assertTrue(chip.isShown());
            assertEquals(rowAlone, row.getHeight());
            assertEquals(1, shownChips(content).size());
            assertNull("no live region anywhere in the strip", liveRegionIn(strip));
        }
    }

    /**
     * The strip's verdict is the homepage caption's: an offer Autopilot let through below the minimums never reads as
     * a full pass there either.
     */
    @Test @Config(qualifiers = "w411dp-h300dp-420dpi")
    public void theStripsVerdictNeverCallsAPassBelowTheMinimumsAFullPass() {
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
            DrivingStrip strip = find(content(activity), DrivingStrip.class);
            assertEquals("Latest · $5.75 · Passed below your minimums", strip.verdictText());
        }
    }

    @Test @Config(qualifiers = "w411dp-h300dp-420dpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTheNormalFontTheLayoutNoteAndTheChipShareTheLineAtNoHeight() {
        stripAt(1f, "note", false);
    }

    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTheNormalFontEvenTheLongestLineAndTheChipFitAStripAThirdOfAPhoneTall() {
        stripAt(1f, "peek", false);
    }

    @Test @Config(qualifiers = "w411dp-h300dp-420dpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheFontTheStripKeepsTheChipAndItsLineWhole() {
        stripAt(2f, "on", false);
    }

    /** Android 8's fonts grow linearly: there, and only there, the strip scrolls by a few dp at twice the font. */
    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheFontTheLayoutNoteKeepsEveryWordBesideTheChip() {
        stripAt(2f, "note", android.os.Build.VERSION.SDK_INT < 34);
    }

    /** At twice the font the longest line cannot fit a third of a phone, chip or none: the strip scrolls to it. */
    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheFontTheLongestLineIsNeverCutTheStripScrollsToItsEnd() {
        stripAt(2f, "peek", true);
    }

    /**
     * The strip at {@code fontScale}, Autopilot on, its line the filter's status ("on"), the layout note ("note", a
     * dash beside a map) or Peek paused with its longest reason ("peek"): the mascot, the latest verdict, the whole
     * chip (one line, a 48 dp target, never below three quarters of the user's size nor the default size) and every
     * word of the line are in the strip. It fills its window and scrolls only where {@code mayScroll} (a very large
     * font leaving no other way); scrolled to its end, the line's last words are on screen. At the normal size the chip
     * costs the line no height.
     */
    private void stripAt(float fontScale, String line, boolean mayScroll) {
        RuntimeEnvironment.setFontScale(fontScale);
        AutopilotRuntime.executorForTests = Runnable::run;
        dasherInstalled();
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        DecisionLog.record(app, declinedEntry());
        servicesUp();
        if (line.equals("note")) Dashing.seen(app);
        if (line.equals("peek")) Peek.pause(app, LONGEST_PAUSE);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        String words = line.equals("note") ? SplitLines.LAYOUT_NOTE_SHORT
                : line.equals("peek") ? "Peek paused: " + LONGEST_PAUSE + " · Resume" : "Auto-decline is on";
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            refreshed();
            layOut(content);
            DrivingStrip strip = find(content, DrivingStrip.class);
            assertEquals(words, strip.statusText());
            assertEquals("Latest · $7.90 · Declined", strip.verdictText());
            AutopilotChip chip = find(strip, AutopilotChip.class);
            AutopilotChip.Row row = (AutopilotChip.Row) chip.getParent();
            TextView status = (TextView) row.getChildAt(1);
            Ui ui = new Ui(app);
            String measured = "font " + fontScale + ", " + line + ": strip " + strip.getHeight() + ", content "
                    + strip.getChildAt(0).getHeight() + ", row " + row.getHeight() + " (stacked " + row.stacked()
                    + ", scale " + row.textScale() + ", tight " + row.tight() + "), line " + status.getLineCount()
                    + " lines";

            // The chip: whole, one line, a full target, its words no smaller than the least.
            assertTrue(chip.isShown());
            assertEquals("one line, never cut short: " + measured, 0, chip.getLayout().getEllipsisCount(0));
            assertEquals(1, chip.getLineCount());
            assertTrue(chip.getHeight() >= ui.dp(48) - 1 && chip.getWidth() >= ui.dp(48) - 1);
            assertTrue("the chip never below three quarters of the user's size nor its default size: " + measured,
                    chip.getTextSize() >= least(13) - 0.5f);
            assertTrue("nor the line's words: " + measured, status.getTextSize() >= least(14) - 0.5f);
            if (fontScale <= 1f) {
                assertEquals("at the normal size neither shrinks: " + measured, TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 13, app.getResources().getDisplayMetrics()), chip.getTextSize(),
                        0.01f);
            }

            // Every word of the line is laid out, inside the line's own height (nothing cut off).
            assertEquals("every word: " + measured, words.length(),
                    status.getLayout().getLineEnd(status.getLineCount() - 1));
            assertTrue("inside the line: " + measured, status.getLayout().getHeight()
                    <= status.getHeight() - status.getTotalPaddingTop() - status.getTotalPaddingBottom());
            assertTrue(status.getHeight() >= ui.dp(48) - 1);

            // One window, unless a very large font leaves no other way; then the line's end is a scroll away.
            View inner = strip.getChildAt(0);
            boolean scrolls = inner.getHeight() > strip.getHeight();
            if (!mayScroll) assertFalse("one window, no scrolling: " + measured, scrolls);
            Rect visible = new Rect();
            if (scrolls) {
                strip.scrollTo(0, inner.getHeight() - strip.getHeight());
                layOut(content);
            }
            assertTrue(status.getGlobalVisibleRect(visible));
            int[] at = new int[2];
            status.getLocationInWindow(at);
            assertEquals("the line's end on screen: " + measured, at[1] + status.getHeight(), visible.bottom);
            if (!scrolls) {
                for (View part : new View[] {find(strip, DrivingStrip.MascotButton.class), chip, status}) {
                    assertTrue(part.getGlobalVisibleRect(visible));
                    assertEquals("whole on screen: " + part + "; " + measured, part.getHeight(), visible.height());
                }
            }
            if (fontScale <= 1f && !line.equals("peek")) {
                chip.setVisibility(View.GONE);
                layOut(content);
                int alone = row.getHeight();
                chip.setVisibility(View.VISIBLE);
                layOut(content);
                assertEquals("at the normal size the chip costs the line no height: " + measured, alone,
                        row.getHeight());
            }

            // Its tap is still the line's own: Swap in Dasher, or Resume Peek.
            if (line.equals("peek")) {
                status.performClick();
                assertNull("Resume: Peek works again", Peek.pausedWhy(app));
            } else if (line.equals("note")) {
                status.performClick();
                Intent swap = started();
                assertNotNull(swap);
                assertEquals(DASHER_HOME, swap.getComponent());
            }
        }
    }

    // ---- The strip's verdict: every word, at any font ----

    /** The homepage caption's words are the verdict's since the chip came, so its longest say a whole phrase more. */
    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheFontAPassBelowTheMinimumsKeepsEveryWordOfItsVerdict() {
        verdictAt(2f, "below", true);
    }

    @Test @Config(qualifiers = "w320dp-h280dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void inANarrowStripAtTwiceTheFontAPassBelowTheMinimumsKeepsEveryWordOfItsVerdict() {
        verdictAt(2f, "below", true);
    }

    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheFontAnAutomaticAcceptanceKeepsEveryWordOfItsVerdict() {
        verdictAt(2f, "automatic", true);
    }

    /** An automatic Accept Dasher has not confirmed yet: the longest verdict, in the narrowest strip. */
    @Test @Config(qualifiers = "w320dp-h280dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTwiceTheFontEvenTheLongestVerdictKeepsEveryWord() {
        verdictAt(2f, "requested", true);
    }

    /** At the normal font the longest verdict and the status fit a strip a third of a phone tall: no scrolling. */
    @Test @Config(qualifiers = "w360dp-h240dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void atTheNormalFontTheLongestVerdictFitsAStripAThirdOfAPhoneTall() {
        verdictAt(1f, "requested", false);
    }

    /**
     * The strip at {@code fontScale} with Autopilot on and its latest offer one of the longest verdicts: one Autopilot
     * let through below the minimums ("below"), one the app accepted by itself ("automatic"), or an automatic Accept
     * Dasher has not confirmed ("requested"). Every word of the verdict is laid out inside its own height (no line
     * limit cuts its last words off without a mark, as two lines did at twice the font) and the whole verdict is on
     * screen; the strip scrolls only where {@code mayScroll}, and then the status line's end is a scroll away.
     */
    private void verdictAt(float fontScale, String kind, boolean mayScroll) {
        RuntimeEnvironment.setFontScale(fontScale);
        AutopilotRuntime.executorForTests = Runnable::run;
        long at = System.currentTimeMillis() - 5_000;
        String words;
        if (kind.equals("below")) {
            // $5.75 for 6.6 mi and 27 min asks $6.75 at the minimums, $5.54 at an 82% bar.
            OfferSnapshot facts = new OfferSnapshot(575, 6.6, 27, 2);
            OfferRule.Decision decision = OfferRule.evaluate(facts, FilterSettings.of(true, 400, 100, 25, 3)
                    .withAutopilot(true, 70).withMinimumScalePercent(82));
            DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision,
                    DecisionLog.Action.PASSES, true, Collections.singletonList("$5.75")).withTime(at));
            words = "Latest · $5.75 · Passed below your minimums";
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
            words = "Latest · $12.50 · " + (kind.equals("automatic") ? "Automatically accepted"
                    : "Accept requested, not confirmed");
        }
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        if (kind.equals("below")) assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
        servicesUp();
        try (ActivityController<MainActivity> activity = splitScreen()) {
            View content = content(activity);
            refreshed();
            layOut(content);
            DrivingStrip strip = find(content, DrivingStrip.class);
            assertEquals(words, strip.verdictText());
            TextView verdict = shownTextContaining(strip, "Latest · ");
            assertNotNull(verdict);
            TextView status = shownTextContaining(strip, "Auto-decline is on");
            assertNotNull(status);
            View inner = strip.getChildAt(0);
            String measured = "font " + fontScale + ", " + kind + ": strip " + strip.getHeight() + ", content "
                    + inner.getHeight() + ", verdict " + verdict.getHeight() + " (" + verdict.getLineCount()
                    + " lines, laid out " + verdict.getLayout().getHeight() + "), status " + status.getHeight();

            // Every word of the verdict, inside its own height: none cut off without a mark.
            assertEquals("every word: " + measured, words.length(),
                    verdict.getLayout().getLineEnd(verdict.getLineCount() - 1));
            assertEquals("never shortened with a mark either: " + measured, 0,
                    verdict.getLayout().getEllipsisCount(verdict.getLineCount() - 1));
            assertTrue("inside the verdict: " + measured, verdict.getLayout().getHeight()
                    <= verdict.getHeight() - verdict.getTotalPaddingTop() - verdict.getTotalPaddingBottom());

            // One window, unless a very large font leaves no other way; the whole verdict on screen as the strip
            // opens, and the status line's end a scroll away.
            boolean scrolls = inner.getHeight() > strip.getHeight();
            if (!mayScroll) assertFalse("one window, no scrolling: " + measured, scrolls);
            Rect visible = new Rect();
            assertTrue(verdict.getGlobalVisibleRect(visible));
            assertEquals("the whole verdict on screen: " + measured, verdict.getHeight(), visible.height());
            if (scrolls) {
                strip.scrollTo(0, inner.getHeight() - strip.getHeight());
                layOut(content);
            }
            assertTrue(status.getGlobalVisibleRect(visible));
            int[] where = new int[2];
            status.getLocationInWindow(where);
            assertEquals("the status line's end on screen: " + measured, where[1] + status.getHeight(),
                    visible.bottom);
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
