package com.local.dasherfilter;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
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
 * The minimums grew (0.5.1), on the real homepage: the one-time note among the page's lines, "Your minimums grew 10%"
 * over one line of what grew and why, with Undo and OK; it stays until a button is tapped (a recreated screen shows it
 * again), and goes with its Undo once the minimums change any other way or Clear history runs; Undo puts the minimums
 * back as the user's change; nothing is announced and no live region is used; Autopilot's details say when the minimums
 * last grew; Settings hold "Let my minimums grow", on by default (0.6.21; in Autopilot's details before).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, qualifiers = "w411dp-h914dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class MinimumsGrowthUiTest extends AndroidAdapterTestBase {
    private static final long HOUR = 3_600_000L;
    private static final String TITLE = "Your minimums grew 10%";
    private static final String LINE = "1 in 5 of your last 30 offers, over 2 days, paid 10% or more above your "
            + "minimums, so they grew: $4.00/stop → $4.40/stop · $1.00/mi → $1.10/mi · $15/hr → $16.80/hr.";
    private static final String SAID = "Your minimums grew 10 percent. 1 in 5 of your last 30 offers, over 2 days, "
            + "paid 10 percent or more above your minimums, so they grew: pay per stop $4.00 to $4.40, per mile $1.00 to "
            + "$1.10, per hour $15 to $16.80.";
    private static final int[] GROWN = {440, 110, 28, 0, 0, 0};

    private long grewAt;
    /** Midday now, whatever hour the suite runs at, so the offers' days are the same days ({@link MiddayZone}). */
    private MiddayZone zone;

    @Before public void grown() {
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.executorForTests = Runnable::run;
        ShadowAlertDialog.reset();
        DecisionLog.clear(app);
        DiagnosticLog.clear(app);
        AutopilotStore.clear(app);
        // The typical minimums, set three days ago; Autopilot on at 108%; 30 offers since, over two days, every one
        // paying 87% or more above the minimums; then the commit at a safe point (as the screen reader makes it) grows
        // them the most it can, 10%, and the bar comes down to 100%.
        long wall = System.currentTimeMillis();
        zone = new MiddayZone(wall);
        FilterStore.wallClock = () -> wall - 3 * 24 * HOUR;
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        FilterStore.wallClock = System::currentTimeMillis;
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 108));
        FilterSettings scoringRules = FilterStore.load(app);
        for (int i = 29; i >= 0; i--) {
            long at = wall - 10 * 60_000L - 7 * 60_000L * (i % 15) - (i >= 15 ? 24 * HOUR : 0);
            DecisionLog.record(app, new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false,
                    new OfferSnapshot(1500 + i, 6.6, 27, 2), 729, OfferRule.Result.KEEP, "passes your rules",
                    DecisionLog.Action.PASSES, true, Collections.<String>emptyList()).withBar(108, true)
                    .withScoringRules(scoringRules));
        }
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.CONNECT);
        assertEquals(AutopilotRuntime.Commit.GREW, AutopilotRuntime.commitIfDue(app));
        assertArrayEquals(GROWN, FilterStore.load(app).minimums());
        assertEquals(100, FilterStore.load(app).minimumScalePercent);
        grewAt = AutopilotStore.lastGrowth(app).at;
    }

    @After public void plansOnTheirOwnThread() {
        if (zone != null) zone.restore();
        RuntimeEnvironment.setFontScale(1f);
        FilterStore.wallClock = System::currentTimeMillis;
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.forgetCache();
    }

    // ---- The note ----

    @Test public void theNoteSaysWhatGrewAndWhyQuietlyAndStaysUntilOkClosesItForGood() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            TextView words = note(content);
            assertNotNull("the note is on the homepage", words);
            assertEquals(TITLE + "\n" + LINE, words.getText().toString());
            assertEquals("heard in words, not symbols", SAID, String.valueOf(words.getContentDescription()));
            for (String symbol : new String[] {"→", "·"}) assertFalse(SAID.contains(symbol));
            assertEquals("never a live region", View.ACCESSIBILITY_LIVE_REGION_NONE,
                    words.getAccessibilityLiveRegion());
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, ((View) words.getParent()).getAccessibilityLiveRegion());
            assertNotNull(shownButton(content, AutopilotText.GROWTH_UNDO));
            assertNotNull(shownButton(content, AutopilotText.GROWTH_OK));
        }
        // A screen made again shows it again: only a button closes it.
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertNotNull(note(content));
            shownButton(content, AutopilotText.GROWTH_OK).performClick();
            settleSky(content);
            assertNull("OK closes it", note(content));
            assertNull(shownButton(content, AutopilotText.GROWTH_UNDO));
            assertArrayEquals("the minimums stay grown", GROWN, FilterStore.load(app).minimums());
        }
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertNull("for good", note(content));
            // Autopilot's details still say when the minimums last grew.
            AutopilotText.Status status = AutopilotRuntime.status(app, System.currentTimeMillis(), false);
            assertTrue(AutopilotText.detailsLines(status).toString(), AutopilotText.detailsLines(status).contains(
                    "Your minimums last grew 10%, less than a minute ago: $4.00/stop → $4.40/stop · $1.00/mi → $1.10/mi · "
                            + "$15/hr → $16.80/hr."));
        }
    }

    @Test public void aGrowthWhileThePageIsUpShowsTheNoteWithoutAnnouncingIt() {
        asBeforeTheGrowth();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertNull(note(content));
            AccessibilityManager accessibility = app.getSystemService(AccessibilityManager.class);
            Shadows.shadowOf(accessibility).setEnabled(true);
            int sent = Shadows.shadowOf(accessibility).getSentAccessibilityEvents().size();
            AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
            assertEquals(AutopilotRuntime.Commit.GREW, AutopilotRuntime.commitIfDue(app));
            settleSky(content);
            assertNotNull("the page follows", note(content));
            List<AccessibilityEvent> events = Shadows.shadowOf(accessibility).getSentAccessibilityEvents();
            for (AccessibilityEvent event : events.subList(sent, events.size())) {
                assertTrue("no announcement: " + event, event.getEventType() != AccessibilityEvent.TYPE_ANNOUNCEMENT);
            }
        }
    }

    @Test public void undoPutsTheMinimumsBackAsTheUsersChange() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertNotNull(note(content));
            shownButton(content, AutopilotText.GROWTH_UNDO).performClick();
            settleSky(content);
            FilterSettings undone = FilterStore.load(app);
            assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, undone.minimums());
            assertEquals("the bar is Autopilot's to move, at its next safe point", 100, undone.minimumScalePercent);
            assertEquals("RULES_CHANGED", AutopilotStore.jump(app));
            assertNull("the note goes with its Undo", note(content));
            assertNull(AutopilotStore.lastGrowth(app));
            assertEquals("Minimums back to $4.00/stop · $1.00/mi · $15/hr", ShadowToast.getTextOfLatestToast());
            assertTrue(DiagnosticLog.read(app).contains("[autopilot] minimums growth undone: $4.40/stop -> $4.00/stop, "
                    + "$1.10/mi -> $1.00/mi, $16.80/hr -> $15.00/hr"));
        }
    }

    @Test public void theNoteGoesWithItsUndoOnceAMinimumIsSetAnyOtherWay() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertNotNull(note(content));
            // The pay knob, a step up (as a screen reader adjusts it).
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(act(star, AreaScore.PAY, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
            settleSky(content);
            int[] set = FilterStore.load(app).minimums();
            assertTrue("a step up from $4.40: " + set[0], set[0] > 440);
            assertEquals("the knob's own minimum only", 110, set[1]);
            assertEquals(28, set[2]);
            assertNull("the note goes", note(content));
            assertNull(shownButton(content, AutopilotText.GROWTH_UNDO));
            assertFalse("and its Undo with it", AutopilotRuntime.undoGrowth(app, grewAt));
            assertEquals(set[0], FilterStore.load(app).minimums()[0]);
        }
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertNull("for good", note(content));
        }
    }

    @Test public void clearHistoryRemovesTheNoteAndItsUndoAndKeepsTheMinimums() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertNotNull(note(content));
            iconDescribed(content, "Settings").performClick();
            idle();
            shownButton(content, "Clear history").performClick();
            idle();
            AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(MainActivity.CLEAR_HISTORY, String.valueOf(Shadows.shadowOf(confirm).getMessage()));
            assertTrue(MainActivity.CLEAR_HISTORY.contains("its note that your minimums grew"));
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            iconDescribed(content, "Back").performClick();
            settleSky(content);
            assertNull(note(content));
            assertNull(AutopilotStore.lastGrowth(app));
            assertFalse(AutopilotRuntime.undoGrowth(app, grewAt));
            assertArrayEquals("the rules stay", GROWN, FilterStore.load(app).minimums());
            assertTrue(FilterStore.load(app).autopilot);
        }
    }

    // ---- The switch ----

    @Test public void letMyMinimumsGrowIsInSettingsOnByDefaultAndNoLongerInAutopilotsDetails() {
        asBeforeTheGrowth();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            AlertDialog details = details(content);
            assertNull("no switch in Autopilot's details", find(details.getWindow().getDecorView(), Switch.class));
            details.dismiss();
            idle();
            Switch grow = settingsSwitch(content);
            assertTrue("on by default", grow.isChecked());
            assertTrue(grow.getText().toString().startsWith(AutopilotText.GROW_SWITCH + "\n"));
            assertTrue(grow.getText().toString().endsWith(AutopilotText.GROW_ABOUT));
            grow.performClick();
            idle();
            assertFalse(FilterStore.minimumsGrow(app));
            assertTrue(DiagnosticLog.read(app).contains("[autopilot] minimums growth off"));

            // Off: no growth, and nothing else changes.
            AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
            assertNotNull(AutopilotRuntime.latest().growth);
            assertFalse(AutopilotRuntime.commitIfDue(app) == AutopilotRuntime.Commit.GREW);
            FilterSettings kept = FilterStore.load(app);
            assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, kept.minimums());
            assertTrue(kept.autopilot);

            // On again in Settings: the growth is made at the next commit.
            Switch again = settingsSwitch(content);
            assertFalse(again.isChecked());
            again.performClick();
            idle();
            assertTrue(FilterStore.minimumsGrow(app));
            assertEquals(AutopilotRuntime.Commit.GREW, AutopilotRuntime.commitIfDue(app));
            assertArrayEquals(GROWN, FilterStore.load(app).minimums());
        }
    }

    /**
     * In a short window at the largest font, Autopilot's details keep their buttons whole and in the dialog: a long
     * details text never pushes "Turn off" or "Close" out of the window.
     */
    @Test @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void inAShortWindowAtLargeFontTheDetailsKeepTheirButtons() {
        RuntimeEnvironment.setFontScale(2f);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            AlertDialog details = details(content);
            View decor = details.getWindow().getDecorView();
            int height = layOutDialog(decor);
            int touch = new Ui(app).dp(48);
            int buttons = 0;
            for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
                    AlertDialog.BUTTON_NEUTRAL}) {
                Button button = details.getButton(which);
                if (button == null || button.getVisibility() != View.VISIBLE) continue;
                buttons++;
                int top = topIn(button, decor);
                assertTrue(button.getText() + " is whole: " + button.getHeight() + " px",
                        button.getHeight() >= touch);
                assertTrue(button.getText() + " is in the dialog: " + top + ".." + (top + button.getHeight())
                        + " of " + height + " px", top >= 0 && top + button.getHeight() <= height);
            }
            assertTrue("Close and Turn off at least", buttons >= 2);
        }
    }

    // ---- Helpers ----

    /**
     * As just before the growth: the minimums back to the typical ones in effect since three days ago, the growth and
     * the jump its Undo left forgotten, and the bar at 108% again, so the same evidence makes the growth due.
     */
    private void asBeforeTheGrowth() {
        assertTrue(AutopilotRuntime.undoGrowth(app, grewAt));
        AutopilotStore.clear(app);
        long wall = System.currentTimeMillis();
        FilterStore.wallClock = () -> wall - 3 * 24 * HOUR;
        FilterStore.save(app, FilterSettings.of(true, 401, 100, 25, 0));
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        FilterStore.wallClock = System::currentTimeMillis;
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 108));
        assertEquals(wall - 3 * 24 * HOUR, FilterStore.minimumsSince(app));
    }

    private static View page(ActivityController<MainActivity> activity) {
        return activity.get().findViewById(android.R.id.content);
    }

    /** The note's words on screen, or null. */
    private static TextView note(View content) {
        return shownTextContaining(content, "Your minimums grew");
    }

    /** Opens Autopilot's details by a tap on the strip's chip, at every size. */
    private AlertDialog details(View content) {
        // The strip's chip opens Autopilot's details at every size (the radar's round button is the learner's).
        AutopilotChip chip = find(content, AutopilotChip.class);
        assertTrue("the strip's chip", chip.isShown());
        chip.performClick();
        idle();
        AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals(AutopilotText.DETAILS_TITLE, String.valueOf(Shadows.shadowOf(details).getTitle()));
        return details;
    }

    /** Settings' "Let my minimums grow", the Settings page opened first where it is not up. */
    private Switch settingsSwitch(View content) {
        if (growSwitch(content) == null) {
            iconDescribed(content, "Settings").performClick();
            idle();
        }
        Switch grow = growSwitch(content);
        assertNotNull("Let my minimums grow, in Settings", grow);
        return grow;
    }

    /** The shown switch whose words start "Let my minimums grow", or null. */
    private static Switch growSwitch(View view) {
        if (view instanceof Switch && view.isShown()
                && ((Switch) view).getText().toString().startsWith(AutopilotText.GROW_SWITCH)) {
            return (Switch) view;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                Switch found = growSwitch(((ViewGroup) view).getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Lays the dialog out as its window would be, at most the display less 16 dp a side; its height. */
    private int layOutDialog(View decor) {
        idle();
        int width = app.getResources().getDisplayMetrics().widthPixels - new Ui(app).dp(32);
        int most = app.getResources().getDisplayMetrics().heightPixels - new Ui(app).dp(32);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(most, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        return decor.getHeight();
    }

    /** {@code view}'s top within {@code ancestor}, after any scrolling between them. */
    private static int topIn(View view, View ancestor) {
        int top = 0;
        for (View at = view; at != null && at != ancestor;
                at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            top += at.getTop();
            if (at.getParent() instanceof View) top -= ((View) at.getParent()).getScrollY();
        }
        return top;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    }
}
