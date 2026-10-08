package com.local.dasherfilter;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayList;
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
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The homepage's Autopilot (finalSpec.ui and autopilot.explainability), on the real page:
 * <ul>
 *   <li>the constellation's button, drawn "Auto" over its bar or "Off", heard as a Switch that names the goal;</li>
 *   <li>"What matters more?": the top tier checked at first, the stored goal after; one tap applies it; About explains
 *       in the owner's words without closing it; Not now changes nothing;</li>
 *   <li>the strip's status line in "Next match"'s place while Autopilot is on, in every state, quiet when the bar
 *       moves, and on a 640 dp phone still one screen (the road gives way);</li>
 *   <li>the details, and their buttons doing only what they say; typical minimums putting the bar back at exactly 100
 *       first (D1);</li>
 *   <li>in a 360 dp-tall split pane, beside another app or Dasher, the chip at the status line's start, costing no
 *       height, at 320 to 411 dp wide, by day and night, at the normal and twice the font, the radar's button agreeing
 *       with it wherever the radar is wholly there;</li>
 *   <li>the new-install starter and the first-enable chooser; Clear history forgetting Autopilot's reading and change
 *       note; the ticket's Autopilot lines; and Settings: no new row, the auto-accept confirmation's new words.</li>
 * </ul>
 * Plans run on the calling thread ({@code executorForTests}); nothing in this branch commits the bar but the tests,
 * through the runtime's own commit, as the screen reader will at a safe point.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AutopilotUiTest extends AndroidAdapterTestBase {
    private static final long MIN = 60_000L;

    /** The worked example's 20 offers, newest first (as AutopilotRuntimeTest): pay in cents, miles, minutes. */
    private static final int[] PAY = {1625, 975, 700, 1350, 575, 800, 2250, 600, 1100, 350, 925, 1490, 400, 1050,
            725, 1700, 875, 500, 1225, 650};
    private static final double[] MILES = {6.2, 11.8, 2.2, 7.0, 6.6, 3.0, 12.6, 7.9, 5.1, 4.8, 6.3, 9.9, 2.6, 4.4,
            5.5, 8.8, 7.4, 3.2, 6.0, 4.1};
    private static final int[] MINUTES = {26, 41, 14, 28, 27, 16, 44, 29, 23, 19, 25, 38, 15, 20, 24, 33, 31, 18, 26,
            22};

    private long wall;
    private ServiceController<OfferFilterService> reader;
    private ServiceController<OfferNotificationService> notifications;

    @Before public void plansOnThisThread() {
        wall = System.currentTimeMillis();
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.executorForTests = Runnable::run;
        ShadowAlertDialog.reset();
    }

    @After public void plansOnTheirOwnThread() {
        if (reader != null) reader.destroy();
        reader = null;
        if (notifications != null) notifications.destroy();
        notifications = null;
        OfferFilterService.sawDasherBeside(0);
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.forgetCache();
        RuntimeEnvironment.setFontScale(1f);
        Peek.resumeNow(app);
    }

    // ---- Fixtures ----

    /** The screen reader running, as Autopilot needs to commit. */
    private void connectReader() {
        reader = Robolectric.buildService(OfferFilterService.class).create();
        reader.get().onServiceConnected();
    }

    /**
     * Set up as while dashing: the screen reader and background offers both on, so no line under the header needs the
     * user.
     */
    private void setUpForDashing() {
        connectReader();
        notifications = Robolectric.buildService(OfferNotificationService.class).create();
        notifications.get().onListenerConnected();
    }

    /** These minimums, auto-decline on, no max stops; Autopilot as it was. */
    private void minimums(int flat, int mile, int minute) {
        FilterStore.save(app, FilterSettings.of(true, flat, mile, minute, 0));
    }

    /**
     * The worked example's offers {@code from} (newest) to {@code to} (exclusive), three minutes apart from two minutes
     * ago, decided by the saved rules as the screen reader records them: declined (its confirmation tapped), passed or
     * left to the user.
     */
    private void history(int from, int to) {
        FilterSettings rules = FilterStore.load(app);
        for (int i = to - 1; i >= from; i--) {
            OfferSnapshot facts = new OfferSnapshot(PAY[i], MILES[i], MINUTES[i], 2);
            OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
            DecisionLog.Action action = decision.result == OfferRule.Result.DECLINE
                    ? DecisionLog.Action.CONFIRMATION_TAPPED
                    : decision.result == OfferRule.Result.KEEP ? DecisionLog.Action.PASSES
                    : DecisionLog.Action.NEEDS_REVIEW;
            DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision, action,
                    true, Collections.<String>emptyList()).withTime(wall - (2 + 3L * i) * MIN));
        }
    }

    /** Dasher's decline question showed {@code percent}, that many minutes ago. */
    private void reading(int percent, long minutesAgo) {
        assertTrue(AutopilotStore.recordReading(app, percent, "", wall - minutesAgo * MIN));
    }

    private static View page(ActivityController<MainActivity> activity) {
        return activity.get().findViewById(android.R.id.content);
    }

    /** Lets the page refresh (it does each second, and when Autopilot tells it). */
    private static void refreshed() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
    }

    /** The shown line whose words start with {@code start}, or null. */
    private static TextView shownLineStarting(View view, String start) {
        if (view instanceof TextView && !(view instanceof Button) && view.isShown()
                && ((TextView) view).getText().toString().startsWith(start)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = shownLineStarting(group.getChildAt(i), start);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Autopilot's status line on the page, checked to be the strip's status line (in "Next match"'s place) with the
     * chip at its start, and its words.
     */
    private String autopilotLine(View content) {
        refreshed();
        TextView line = shownLineStarting(content, "Autopilot ");
        assertNotNull("Autopilot's status line", line);
        assertSame("the strip's status line", statusLine(content), line);
        Ui ui = new Ui(app);
        assertEquals("14 sp", TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                app.getResources().getDisplayMetrics()), line.getTextSize(), 0.01f);
        assertTrue("a 48 dp target", line.getMinHeight() >= ui.dp(48));
        assertTrue("a tap opens the details", line.isClickable());
        assertEquals("never a live region", View.ACCESSIBILITY_LIVE_REGION_NONE, line.getAccessibilityLiveRegion());
        String words = line.getText().toString();
        AutopilotText.Status status = AutopilotRuntime.status(app, System.currentTimeMillis(),
                OfferFilterService.isConnected());
        assertEquals("the line as drawn", AutopilotText.statusLine(status), words);
        assertHeardInWords(String.valueOf(line.getContentDescription()));
        assertEquals("heard whole, in words, with what a tap does", AutopilotText.chipDescription(status),
                String.valueOf(line.getContentDescription()));
        assertNull("in place of the wait for a matching offer", shownLineStarting(content, "Next match"));
        AutopilotChip chip = find(content, AutopilotChip.class);
        assertTrue("the chip at its start", chip.isShown() && chip.getParent() == line.getParent());
        return words;
    }

    /** What a screen reader hears: whole sentences, no symbol it would spell out, and what a tap does. */
    private static void assertHeardInWords(String said) {
        for (String symbol : new String[] {"→", "·", "~", "AR ", "%", "▲"}) {
            assertFalse("heard in words, not \"" + symbol + "\": " + said, said.contains(symbol));
        }
        assertTrue(said, said.startsWith("Autopilot ") && said.endsWith(". Opens Autopilot details."));
    }

    /** What {@code view} draws as text. */
    private static List<String> drawnWords(View view) {
        List<String> words = new ArrayList<>();
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap) {
            @Override public void drawText(String text, float x, float y, Paint paint) {
                words.add(text);
                super.drawText(text, x, y, paint);
            }
        });
        bitmap.recycle();
        return words;
    }

    private static String title(AlertDialog dialog) {
        return String.valueOf(Shadows.shadowOf(dialog).getTitle());
    }

    /** A dialog's message; for Autopilot's details, the words over its switch (MainActivity.DETAILS_WORDS_ID). */
    private static String message(AlertDialog dialog) {
        CharSequence message = Shadows.shadowOf(dialog).getMessage();
        if (message != null && message.length() > 0) return String.valueOf(message);
        TextView words = dialog.findViewById(MainActivity.DETAILS_WORDS_ID);
        return String.valueOf(words == null ? null : words.getText());
    }

    /** Shown through OwnWindowTouches.show: a touch on it stays this app's own in split screen. */
    private static void assertOwnsItsTouches(AlertDialog dialog) {
        assertTrue(dialog.getWindow().getCallback().getClass().getName(),
                dialog.getWindow().getCallback().getClass().getName().endsWith("OwnWindowTouches$TrackedCallback"));
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** WCAG contrast ratio of two opaque colors. */
    private static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int color) {
        double[] c = {Color.red(color) / 255.0, Color.green(color) / 255.0, Color.blue(color) / 255.0};
        for (int i = 0; i < 3; i++) c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    // ---- The button ----

    @Test
    public void theButtonSaysAutoOverItsBarOrOffAndItsSwitchNamesTheGoal() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            List<String> drawn = drawnWords(star);
            assertTrue(drawn.toString(), drawn.contains(AutopilotText.BUTTON_TITLE) && drawn.contains("82%"));
            assertFalse(drawn.toString(), drawn.contains("Off"));
            AccessibilityNodeInfo button = node(star, MinimumsStarView.SCORE_ID);
            assertTrue(button.isCheckable());
            assertTrue(button.isChecked());
            assertNull("nothing to drag", button.getRangeInfo());
            assertEquals("Autopilot, on. Bar 82 percent of your minimums. Goal: keep a tier, acceptance rate 50 "
                    + "percent or more.", button.getContentDescription().toString());

            // Another goal, chosen while it is on: the bar stays until Autopilot moves it, and the Switch says so.
            AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
            settleSky(content);
            assertEquals(82, FilterStore.load(app).minimumScalePercent);
            assertEquals("Autopilot, on. Bar 82 percent of your minimums. Goal: pay first.",
                    node(star, MinimumsStarView.SCORE_ID).getContentDescription().toString());
            AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
            settleSky(content);
            assertEquals("Autopilot, on. Bar 82 percent of your minimums. Goal: keep a top tier, acceptance rate 70 "
                    + "percent or more.", node(star, MinimumsStarView.SCORE_ID).getContentDescription().toString());

            // A tap turns it off: "Auto" over "Off", offers judged at exactly the minimums again.
            RectF box = star.autopilotBox();
            tapStar(star, box.centerX(), box.centerY());
            settleSky(content);
            drawn = drawnWords(star);
            assertTrue(drawn.toString(), drawn.contains(AutopilotText.BUTTON_TITLE) && drawn.contains("Off"));
            assertFalse(drawn.toString(), drawn.contains("82%"));
            AccessibilityNodeInfo off = node(star, MinimumsStarView.SCORE_ID);
            assertFalse(off.isChecked());
            assertEquals("Autopilot, off. Offers are judged at exactly your minimums.",
                    off.getContentDescription().toString());
            assertEquals(AutopilotText.TOAST_OFF, ShadowToast.getTextOfLatestToast());
            FilterSettings saved = FilterStore.load(app);
            assertFalse(saved.autopilot);
            assertEquals(100, saved.minimumScalePercent);
            assertArrayEquals("the minimums stay", new int[] {700, 150, 30, 0, 0, 0}, saved.minimums());
            assertNull("off: no status line", shownLineStarting(content, "Autopilot "));
        }
    }

    // ---- The goal chooser ----

    @Test
    public void aTapWhileOffAsksWhatMattersMoreWithTheTopTierCheckedAndOneTapTurnsItOn() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            RectF box = star.autopilotBox();
            tapStar(star, box.centerX(), box.centerY());
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("the goal chooser", chooser);
            assertOwnsItsTouches(chooser);
            assertEquals("What matters more?", title(chooser));
            ListView list = chooser.getListView();
            assertEquals("a native single-choice list", ListView.CHOICE_MODE_SINGLE, list.getChoiceMode());
            assertEquals(3, list.getCount());
            assertEquals("Keep a top tier — acceptance rate 70% or more", String.valueOf(list.getItemAtPosition(0)));
            assertEquals("Keep a tier — acceptance rate 50% or more", String.valueOf(list.getItemAtPosition(1)));
            assertEquals("Pay first — no acceptance-rate goal", String.valueOf(list.getItemAtPosition(2)));
            assertEquals("the top tier preselected", 0, list.getCheckedItemPosition());
            assertEquals("About acceptance rate", chooser.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            assertEquals("Not now", chooser.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            assertTrue("no OK: one tap applies", chooser.getButton(AlertDialog.BUTTON_POSITIVE) == null
                    || chooser.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility() != View.VISIBLE);
            // While off, a line under the choices names Autopilot and says an answer turns it on.
            TextView turnsOn = findTextContaining(chooser.getWindow().getDecorView(), AutopilotText.CHOOSER_TURNS_ON);
            assertNotNull(turnsOn);
            assertTrue(turnsOn.isShown());
            assertTrue("readable: " + Integer.toHexString(turnsOn.getCurrentTextColor()),
                    contrast(turnsOn.getCurrentTextColor(), new Ui(activity.get()).surface) >= 4.5);
            assertFalse("asking changes nothing", FilterStore.load(app).autopilot);

            Shadows.shadowOf(chooser).clickOnItem(1);
            idle();
            assertFalse("one tap applies and closes", chooser.isShowing());
            FilterSettings on = FilterStore.load(app);
            assertTrue(on.autopilot);
            assertEquals(FilterSettings.GOAL_TIER, on.autopilotGoalPercent);
            assertEquals("from exactly the minimums", 100, on.minimumScalePercent);
            assertTrue(FilterStore.goalAsked(app));
            assertEquals("Autopilot on · goal: acceptance rate 50% or more", ShadowToast.getTextOfLatestToast());
            assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("[autopilot] on; goal 50%"));
            // Screen reading still needs setting up: its step stands under the header, and the strip's status line and
            // its chip say Autopilot waits.
            refreshed();
            assertNotNull(shownTextContaining(content, SetupChecklist.ACCESSIBILITY));
            assertEquals("Autopilot waits for screen reading", statusLine(content).getText().toString());
            assertEquals("Auto waits", find(content, AutopilotChip.class).getText().toString());
            assertTrue(find(content, AutopilotChip.class).isShown());

            // Off, then on again with pay first.
            box = star.autopilotBox();
            tapStar(star, box.centerX(), box.centerY());
            assertFalse(FilterStore.load(app).autopilot);
            settleSky(content);
            box = star.autopilotBox();
            tapStar(star, box.centerX(), box.centerY());
            chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("the stored goal is checked now", 1, chooser.getListView().getCheckedItemPosition());
            Shadows.shadowOf(chooser).clickOnItem(2);
            idle();
            assertEquals(FilterSettings.GOAL_PAY_FIRST, FilterStore.load(app).autopilotGoalPercent);
            assertEquals("Autopilot on · pay first", ShadowToast.getTextOfLatestToast());
        }

        // Max stops alone: Autopilot has nothing to scale, so a tap says what it needs and asks nothing.
        AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
        FilterStore.save(app, FilterSettings.of(true, 0, 0, 0, 3));
        ShadowAlertDialog.reset();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_CLICK));
            idle();
            assertNull("no chooser", ShadowAlertDialog.getLatestAlertDialog());
            assertEquals("Set a pay, per-mile or hourly minimum first.", ShadowToast.getTextOfLatestToast());
            assertFalse(FilterStore.load(app).autopilot);
        }
    }

    @Test
    public void aboutAcceptanceRateExplainsInTheOwnersWordsAndLeavesTheChoiceOpen() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TIER);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            // A long press (here a screen reader's) changes the goal at any time.
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_LONG_CLICK));
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.CHOOSER_TITLE, title(chooser));
            assertEquals("the stored goal is checked", 1, chooser.getListView().getCheckedItemPosition());

            chooser.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            idle();
            AlertDialog about = ShadowAlertDialog.getLatestAlertDialog();
            assertNotSame("a dialog of its own", chooser, about);
            assertOwnsItsTouches(about);
            assertEquals("About acceptance rate", title(about));
            assertEquals("DoorDash computes acceptance rate over your recent offers (it reports the last 100). Its "
                    + "rewards tiers use acceptance-rate minimums — check the Dasher app for your current "
                    + "requirements.", message(about));
            assertTrue("the choice stays open", chooser.isShowing());
            about.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            assertTrue(chooser.isShowing());

            // Not now (and Back) leave it as it was.
            chooser.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_LONG_CLICK));
            ShadowAlertDialog.getLatestAlertDialog().cancel();
            idle();
            FilterSettings kept = FilterStore.load(app);
            assertTrue(kept.autopilot);
            assertEquals(FilterSettings.GOAL_TIER, kept.autopilotGoalPercent);

            // A goal chosen while on says it changed.
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_LONG_CLICK));
            Shadows.shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0);
            idle();
            assertEquals(FilterSettings.GOAL_TOP_TIER, FilterStore.load(app).autopilotGoalPercent);
            assertEquals("Autopilot goal: acceptance rate 70% or more", ShadowToast.getTextOfLatestToast());
            assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("[autopilot] goal 70% (was 50%)"));
        }
    }

    // ---- The status line ----

    @Test
    public void theStatusLineTakesNextMatchsPlaceAndSaysEveryStateInTheSpecsOrder() {
        minimums(400, 100, 25);
        List<Runnable> queued = new ArrayList<>();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertNull("off: the line is the wait's, as before", shownLineStarting(content, "Autopilot "));

            // Without screen reading a setup step needs the user, under the header; the strip's status line still
            // says what Autopilot is doing (0.5.1 gave the step the room and said it in the chip alone), and so does
            // its chip, heard in words.
            FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
            refreshed();
            assertNotNull("setup to do", shownTextContaining(content, SetupChecklist.ACCESSIBILITY));
            assertEquals("Autopilot waits for screen reading", autopilotLine(content));
            assertNull(shownLineStarting(content, "Next match"));
            AutopilotChip waits = find(content, AutopilotChip.class);
            assertTrue(waits.isShown());
            assertEquals("Auto waits", waits.getText().toString());
            assertEquals("Autopilot waits for screen reading. Opens Autopilot details.",
                    String.valueOf(waits.getContentDescription()));

            // From here plans wait to be worked out, the one screen reading asks for as it connects included.
            AutopilotRuntime.executorForTests = queued::add;
            connectReader();
            FilterStore.save(app, FilterSettings.of(true, 0, 0, 0, 3));
            assertEquals("Autopilot needs a pay, per-mile or hourly minimum", autopilotLine(content));

            FilterStore.save(app, FilterSettings.of(false, 400, 100, 25, 0));
            assertEquals("Autopilot 100% · auto-decline paused", autopilotLine(content));

            // No plan yet for these rules: the connection's still waits to be worked out.
            minimums(400, 100, 25);
            AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
            assertEquals("Autopilot 100% · checking your offers", autopilotLine(content));

            history(8, 20);
            assertEquals("one plan waits, however often one was asked for", 1, queued.size());
            queued.remove(0).run();
            assertEquals("Autopilot 100% · learning (12 of 20 offers)", autopilotLine(content));
            AutopilotRuntime.executorForTests = Runnable::run;

            history(0, 8);
            AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.USER);
            assertEquals("Autopilot 100% · goal 70% · AR not seen yet", autopilotLine(content));

            // Four offers came after Dasher's 55% and none was accepted: about 53%, 17 accepts short of 70%.
            reading(55, 12);
            AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.READING);
            assertEquals("Autopilot 100% · AR 53% → 70%: about 17 more accepts", autopilotLine(content));

            reading(74, 1);
            AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.READING);
            assertEquals("Autopilot 100% · AR 74%, goal 70%", autopilotLine(content));

            AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
            assertEquals("Autopilot 100% · pay first", autopilotLine(content));

            // Minimums so high that fewer than one in five of these offers meet them: pay first keeps one in five.
            minimums(2040, 400, 48);
            AutopilotRuntime.rulesChanged(app);
            assertEquals("Autopilot 100% · pay first · keeping 1 in 5 offers", autopilotLine(content));

            // With a goal, even the lowest bar passes too few: pinned.
            reading(9, 1);
            AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
            assertEquals("Autopilot 100% · AR 9% → 70%: about 61 more accepts", autopilotLine(content));

            // Autopilot moves the bar by itself at its next safe point: the line, the chip, the button and the dashed
            // shape follow, and nothing is announced.
            AccessibilityManager accessibility = app.getSystemService(AccessibilityManager.class);
            Shadows.shadowOf(accessibility).setEnabled(true);
            int sent = Shadows.shadowOf(accessibility).getSentAccessibilityEvents().size();
            assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
            assertEquals(50, FilterStore.load(app).minimumScalePercent);
            assertEquals("Autopilot 50% (lowest) · AR 9% → 70%: about 61 more accepts", autopilotLine(content));
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(drawnWords(star).contains("50%"));
            assertFalse("a dashed shape at the bar", star.autopilotShape().isEmpty());
            List<AccessibilityEvent> events = Shadows.shadowOf(accessibility).getSentAccessibilityEvents();
            for (AccessibilityEvent event : events.subList(sent, events.size())) {
                assertTrue("no announcement: " + event, event.getEventType() != AccessibilityEvent.TYPE_ANNOUNCEMENT);
            }
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, star.getAccessibilityLiveRegion());
            AutopilotChip chip = find(content, AutopilotChip.class);
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, chip.getAccessibilityLiveRegion());
            assertTrue("the chip at the line's start at every size, on a whole screen too", chip.isShown());
            assertEquals("its words follow the bar", "Auto 50% lowest", chip.getText().toString());
        } finally {
            listener.destroy();
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    public void onASmallPhoneTheStatusLineKeepsThePageOneScreenAsTheStageGivesWay() {
        statusLineOnASmallPhone(1f);
    }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    public void onA360By640PhoneTheStatusLineKeepsThePageOneScreenAsTheStageGivesWay() {
        statusLineOnASmallPhone(1f);
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    public void atTwiceTheFontSizeOnASmallPhoneTheStatusLineStaysWholeAndThePageOneScreen() {
        statusLineOnASmallPhone(2f);
    }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    public void atOneAndAHalfTimesTheFontSizeTheStatusLineStaysWholeAndThePageOneScreen() {
        statusLineOnASmallPhone(1.5f);
    }

    /**
     * A 640 dp phone, set up, Autopilot on (0.5.1 gave its status line a row of the ground, the road giving way, and at
     * a very large font said the status in the chip instead): at every font size the status line is the strip's, the
     * chip at its start, both whole on screen, and the page is one screen, the stage under them sharing the rest: each
     * of its parts wholly there at no less than it reads at, or on its way out, least important first (the road, the
     * counts, the skyline, then the radar and the map together, which this phone keeps). Off again, the shorter status
     * line gives the stage its room back: nothing is fainter for it.
     */
    private void statusLineOnASmallPhone(float fontScale) {
        RuntimeEnvironment.setFontScale(fontScale);
        connectReader();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        minimums(400, 100, 25);
        history(0, 20);
        reading(55, 12);
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            refreshed();
            layOut(content);
            Ui ui = new Ui(activity.get());
            ScenePage scene = find(content, ScenePage.class);
            View window = (View) scene.getParent();
            String words = "Autopilot 100% · AR 53% → 70%: about 17 more accepts";
            if (fontScale <= 1f) assertEquals(words, autopilotLine(content));
            layOut(content);
            TextView line = statusLine(content);
            assertEquals(words, line.getText().toString());
            assertNull("never Next match while Autopilot is on", shownLineStarting(content, "Next match"));
            AutopilotChip chip = find(content, AutopilotChip.class);
            assertEquals("Auto 100% ▲", chip.getText().toString());
            assertEquals("Autopilot bar 100 percent. Acceptance rate 53 percent, goal 70 percent: about 17 more "
                    + "accepts. Opens Autopilot details.", String.valueOf(chip.getContentDescription()));
            assertSame("at the line's start", line.getParent(), chip.getParent());
            Rect visible = new Rect();
            for (View part : new View[] {verdictLine(content), chip, line}) {
                assertTrue(part.getGlobalVisibleRect(visible));
                assertEquals("whole on screen: " + part, part.getHeight(), visible.height());
            }
            assertEquals("every word of the line", words.length(), line.getLayout().getLineEnd(line.getLineCount() - 1));
            assertTrue("inside its height", line.getLayout().getHeight()
                    <= line.getHeight() - line.getPaddingTop() - line.getPaddingBottom());
            assertTrue("one screen: " + scene.getHeight() + " in " + window.getHeight(),
                    scene.getHeight() <= window.getHeight());
            float[] on = stageAsItStands(content, ui);

            // Off: no Autopilot line, the chip the way back in, and the stage has at least the room it had.
            AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
            refreshed();
            layOut(content);
            assertNull(shownLineStarting(content, "Autopilot "));
            assertEquals("Auto off", chip.getText().toString());
            assertTrue(chip.isShown());
            assertTrue(scene.getHeight() <= window.getHeight());
            float[] off = stageAsItStands(content, ui);
            for (int i = 0; i < on.length; i++) {
                assertTrue("nothing fainter with the shorter line: " + java.util.Arrays.toString(on) + " / "
                        + java.util.Arrays.toString(off), off[i] >= on[i] - 0.001f);
            }

            // A tap on the chip opens the details, as the status line's would.
            AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
            refreshed();
            chip.performClick();
            idle();
            assertEquals(AutopilotText.DETAILS_TITLE, title(ShadowAlertDialog.getLatestAlertDialog()));
        } finally {
            listener.destroy();
        }
    }

    /**
     * How much of each part of the stage is there, {road, counts, skyline, radar and map}, each checked whole at no
     * less than it reads at or on its way out in its order.
     */
    private static float[] stageAsItStands(View content, Ui ui) {
        ScenePage scene = find(content, ScenePage.class);
        View road = scene.getChildAt(scene.getChildCount() - 1);
        DecisionChartView chart = find(content, DecisionChartView.class);
        MinimumsStarView star = find(content, MinimumsStarView.class);
        AreaMapView map = find(content, AreaMapView.class);
        float[] shown = {scene.shown(road), scene.countsShown(), scene.shown(chart), scene.shown(star)};
        String said = java.util.Arrays.toString(shown);
        for (int i = 1; i < shown.length; i++) {
            if (shown[i] < 1) assertEquals("the " + i + "th fades only once those before it are gone: " + said, 0f,
                    shown[i - 1], 0f);
        }
        assertEquals("the radar and the map go together", shown[3], scene.shown(map), 0f);
        assertTrue("this phone keeps the radar and the map: " + said, shown[3] >= 1);
        if (shown[2] >= 1) {
            assertTrue("the skyline at its least at least: " + chart.getHeight(),
                    chart.getHeight() >= ui.dp(FluidLayout.CHART_LEAST_DP) - 1);
        }
        assertTrue("the radar's knobs at their least circle at least: " + star.skyRadius(),
                star.skyRadius() >= ui.dp(FluidLayout.RADIUS_LEAST_DP) - 1);
        assertTrue("the map at its least at least: " + map.getHeight(),
                map.getHeight() >= ui.dp(FluidLayout.MAP_LEAST_DP) - 1);
        return shown;
    }

    /**
     * A whole screen with a line that needs the user (here notification access): the line stands under the header and
     * takes its room from the stage, and Autopilot's status line stays in the strip with its chip (0.5.1 gave the line
     * the status line's room, the status in the chip alone); the page stays one screen. Once the line goes, the stage
     * has its room back, and the status line and the chip are as they were.
     */
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    public void aLineToFixStandsUnderTheHeaderAndAutopilotsStatusLineStaysInTheStrip() {
        connectReader();
        minimums(400, 100, 25);
        history(0, 20);
        reading(55, 12);
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            refreshed();
            TextView fix = shownTextContaining(content, SetupChecklist.NOTIFICATIONS);
            assertNotNull("background offers are off: a line to fix", fix);
            assertTrue("under the header", inWindow((View) fix.getParent()).top
                    >= inWindow(pageHeader(content)).bottom - 1);
            assertEquals("Autopilot 100% · AR 53% → 70%: about 17 more accepts", autopilotLine(content));
            AutopilotChip chip = find(content, AutopilotChip.class);
            assertEquals("Auto 100% ▲", chip.getText().toString());
            layOut(content);
            ScenePage scene = find(content, ScenePage.class);
            assertTrue("one screen", scene.getHeight() <= ((View) scene.getParent()).getHeight());
            float[] withLine = stageAsItStands(content, new Ui(activity.get()));

            listener.get().onListenerConnected();
            refreshed();
            layOut(content);
            assertNull(shownTextContaining(content, SetupChecklist.NOTIFICATIONS));
            assertEquals("Autopilot 100% · AR 53% → 70%: about 17 more accepts", autopilotLine(content));
            assertTrue("one screen", scene.getHeight() <= ((View) scene.getParent()).getHeight());
            float[] without = stageAsItStands(content, new Ui(activity.get()));
            for (int i = 0; i < withLine.length; i++) {
                assertTrue("the line's room back: " + java.util.Arrays.toString(withLine) + " / "
                        + java.util.Arrays.toString(without), without[i] >= withLine[i] - 0.001f);
            }
        } finally {
            listener.destroy();
        }
    }

    /**
     * The window flows' own line, "Peek paused: …" with Resume, needs the user as a setup line does: it stands under
     * the header, and Autopilot's status line stays in the strip with its chip (no Next match), the page one screen
     * (0.5.1 gave the line the status line's room); Resume takes the line away and the status line is as it was.
     */
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    public void aPeekPausedLineStandsUnderTheHeaderUntilResumeAndTheStatusLineStays() {
        setUpForDashing();
        minimums(400, 100, 25);
        history(0, 20);
        reading(55, 12);
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        Peek.pause(app, "3 offers in a row were gone by the time Dasher showed");
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            refreshed();
            TextView paused = shownTextContaining(content, "Peek paused: ");
            assertNotNull("Peek paused itself: a line that needs the user", paused);
            assertTrue("under the header", inWindow((View) paused.getParent()).top
                    >= inWindow(pageHeader(content)).bottom - 1);
            assertEquals("Autopilot 100% · AR 53% → 70%: about 17 more accepts", autopilotLine(content));
            assertNull("and never Next match while Autopilot is on", shownLineStarting(content, "Next match"));
            AutopilotChip chip = find(content, AutopilotChip.class);
            assertEquals("Auto 100% ▲", chip.getText().toString());
            layOut(content);
            assertFalse("beside the status line", ((AutopilotChip.Row) chip.getParent()).stacked());
            ScenePage scene = find(content, ScenePage.class);
            assertTrue("one screen: " + scene.getHeight() + " in " + ((View) scene.getParent()).getHeight(),
                    scene.getHeight() <= ((View) scene.getParent()).getHeight());

            // Resume: the line goes, and the status line is as it was.
            ((View) paused.getParent()).performClick();
            refreshed();
            assertNull(Peek.pausedWhy(app));
            assertNull(shownTextContaining(content, "Peek paused: "));
            assertEquals("Autopilot 100% · AR 53% → 70%: about 17 more accepts", autopilotLine(content));
        }
    }

    @Test @Config(qualifiers = "w360dp-h360dp-xhdpi")
    public void inA360DpTallSplitBesideAMapTheLayoutNoteStandsUnderTheHeaderAndTheChipAddsNoHeightOfItsOwn() {
        layoutNoteInAShortPane(1f);
    }

    @Test @Config(qualifiers = "w360dp-h360dp-xhdpi")
    public void atTwiceTheFontTheLayoutNoteAndTheChipCostNoMoreThanTheChipsRowAtItsLeast() {
        layoutNoteInAShortPane(2f);
    }

    /**
     * A 360 dp-tall split beside a map during a dash: the window flows' layout note (with Swap) is a line under the
     * header (0.5.1 put it in the sky, the constellation up in the header), and Autopilot's chip stands at the start of
     * its status line in the strip (no Next match). The chip adds no height of its own: the row is the chip's 48 dp or
     * the status line's words beside it, whichever is taller, and what the chip costs the page is only what its row
     * grows: Autopilot's long status wrapping once more beside it in a narrow window at the normal size, nothing
     * shrinking, and at twice the font only once the words are at their least size. The note's tap is still Swap.
     */
    private void layoutNoteInAShortPane(float fontScale) {
        RuntimeEnvironment.setFontScale(fontScale);
        setUpForDashing();
        dasherInstalled();
        minimums(400, 100, 25);
        history(0, 20);
        reading(55, 12);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        Dashing.seen(app);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = page(activity);
            settleSky(content);
            refreshed();
            layOut(content);
            TextView note = shownTextContaining(content, "need a tap in this layout");
            assertNotNull("during a dash beside a map: the layout note", note);
            AutopilotChip chip = find(content, AutopilotChip.class);
            assertTrue("the chip", chip.isShown());
            assertEquals("Auto 100% ▲", chip.getText().toString());
            TextView line = statusLine(content);
            assertTrue("beside Autopilot's status line", line.getText().toString().startsWith("Autopilot "));
            assertNull(shownLineStarting(content, "Next match"));
            AutopilotChip.Row row = (AutopilotChip.Row) chip.getParent();
            assertSame(row, line.getParent());
            assertFalse("never a line of its own", row.stacked());
            View strip = stripWords(content);
            int withChip = strip.getHeight();
            int rowWith = row.getHeight();
            float shrunk = line.getTextSize();
            chip.setVisibility(View.GONE);
            layOut(content);
            int withoutChip = strip.getHeight();
            int rowAlone = row.getHeight();
            float full = line.getTextSize();
            chip.setVisibility(View.VISIBLE);
            layOut(content);
            String measured = "strip " + withChip + " (" + withoutChip + " without the chip), row " + rowWith + " ("
                    + rowAlone + "), line " + shrunk + " of " + full;
            assertEquals("the row is the chip's or its words', whichever is taller: " + measured,
                    Math.max(chip.getHeight(), line.getHeight()), rowWith);
            assertTrue("the chip costs the page no more than its row grows: " + measured,
                    withChip - withoutChip <= Math.max(0, rowWith - rowAlone));
            float least = Math.min(full, Math.max(SetupRow.LEAST_SCALE * full, TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 14, app.getResources().getDisplayMetrics())));
            if (fontScale <= 1f) {
                assertEquals("at the normal size nothing shrinks: " + measured, full, shrunk, 0.01f);
            } else if (rowWith > rowAlone) {
                assertEquals("taller than the words alone only at their least size: " + measured, least, shrunk, 0.5f);
            }

            // The note's tap is still Swap: Dasher into this half, so the map beside it stays.
            ((View) note.getParent()).performClick();
            android.content.Intent swap = Shadows.shadowOf(app).getNextStartedActivity();
            assertNotNull(swap);
            assertEquals("com.doordash.driverapp", swap.getComponent().getPackageName());
        } finally {
            DasherSplit.forget();
        }
    }

    // ---- The details ----

    @Test
    public void theDetailsSayWhatAutopilotSeesAndTheirButtonsDoOnlyWhatTheySay() {
        setUpForDashing();
        minimums(400, 100, 25);
        history(0, 20);
        reading(55, 12);
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        // Its last move, four minutes ago, as the screen reader's commit noted it.
        AutopilotStore.recordChange(app, 82, 100, Autopilot.Reason.RECOVERY.name(), wall - 4 * MIN, true);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertEquals("Autopilot 100% · AR 53% → 70%: about 17 more accepts", autopilotLine(content));

            TextView status = shownLineStarting(content, "Autopilot ");
            status.performClick();
            idle();
            AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
            assertOwnsItsTouches(details);
            assertEquals("Autopilot", title(details));
            String said = message(details);
            List<String> lines = java.util.Arrays.asList(said.split("\n\n"));
            assertEquals(said, "Bar: 100% of your minimums (100% = exactly your minimums).", lines.get(0));
            assertEquals(said, "Goal: Keep a top tier — acceptance rate 70% or more.", lines.get(1));
            assertEquals(said, "Acceptance rate: about 53% (Dasher showed 55% 12 min ago; 4 offers since).",
                    lines.get(2));
            assertEquals(said, "Below your goal: about 17 more accepts to reach 70% (an estimate — DoorDash counts "
                    + "your last 100 offers, so older accepts also roll off).", lines.get(3));
            assertTrue(said, lines.get(4).startsWith("Of your last 20 offers, 16 would pass at 100% (your goal "
                    + "needs "));
            assertTrue(said, lines.contains(AutopilotText.DETAILS_NO_WAITING));
            assertTrue(said, lines.contains("Last change 4 min ago: 82% → 100% (acceptance rate below your goal)."));
            assertTrue(said, lines.contains("Auto-accept only takes offers that meet 100% of your minimums."));
            for (String each : lines) {
                if (each.startsWith("Offers passing at ")) {
                    assertTrue("history, never a promise: " + each, each.contains("paid about $")
                            && each.endsWith("This is history, not a promise: waiting and the drive back are not "
                            + "included."));
                }
            }
            for (String each : lines) {
                assertFalse("no tier rule stated as fact: " + each, each.contains("tier") && !each.startsWith("Goal:"));
            }
            assertEquals("Change goal", details.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            assertEquals("Turn off", details.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            assertEquals("Close", details.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());

            // Close changes nothing.
            details.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            FilterSettings same = FilterStore.load(app);
            assertTrue(same.autopilot);
            assertEquals(100, same.minimumScalePercent);
            assertEquals(FilterSettings.GOAL_TOP_TIER, same.autopilotGoalPercent);

            // Change goal opens the chooser (Autopilot is on: no line about turning it on); Not now leaves the goal.
            status.performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            idle();
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.CHOOSER_TITLE, title(chooser));
            assertNull(findTextContaining(chooser.getWindow().getDecorView(), AutopilotText.CHOOSER_TURNS_ON));
            chooser.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            assertEquals(FilterSettings.GOAL_TOP_TIER, FilterStore.load(app).autopilotGoalPercent);
            assertTrue(FilterStore.load(app).autopilot);

            // Turn off turns it off, back to exactly the minimums; off, the details say so and offer Turn on.
            status.performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            FilterSettings off = FilterStore.load(app);
            assertFalse(off.autopilot);
            assertEquals(100, off.minimumScalePercent);
            assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, off.minimums());
            assertEquals(AutopilotText.TOAST_OFF, ShadowToast.getTextOfLatestToast());
            assertNull("off: the line goes", shownLineStarting(content, "Autopilot "));
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AutopilotText.DETAILS_ACTION_ID));
            AlertDialog offDetails = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(message(offDetails), message(offDetails).startsWith(
                    "Autopilot is off: offers are judged at exactly your minimums."));
            assertEquals("Turn on", offDetails.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            assertEquals("Close", offDetails.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            Button neutral = offDetails.getButton(AlertDialog.BUTTON_NEUTRAL);
            assertTrue("off: no Change goal, which would turn Autopilot on unawares",
                    neutral == null || neutral.getVisibility() != View.VISIBLE);
            offDetails.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            AlertDialog asked = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Turn on asks for the goal first", AutopilotText.CHOOSER_TITLE, title(asked));
            assertNotNull("and says an answer turns Autopilot on", findTextContaining(asked.getWindow().getDecorView(),
                    AutopilotText.CHOOSER_TURNS_ON));
            assertFalse(FilterStore.load(app).autopilot);
        }
    }

    /**
     * Owner's decision D1: typical minimums put the bar back at exactly 100 first, so Autopilot's plan for them starts
     * from 100 and never from a bar set for the old minimums.
     */
    @Test
    public void whenEvenTheLowestBarPassesTooFewTheDetailsOfferTypicalMinimumsFromExactlyTheMinimums() {
        setUpForDashing();
        FilterStore.save(app, FilterSettings.of(true, 2040, 400, 48, 3));
        history(0, 20);
        reading(9, 1);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals("pinned at the lowest bar", 50, FilterStore.load(app).minimumScalePercent);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertEquals("Autopilot 50% (lowest) · AR 9% → 70%: about 61 more accepts", autopilotLine(content));
            shownLineStarting(content, "Autopilot ").performClick();
            idle();
            AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
            String said = message(details);
            assertTrue(said, said.endsWith("Even at the lowest bar (50%) only 4 of your last 20 offers pass. Lower a "
                    + "minimum, turn off max stops, or use typical minimums."));
            assertEquals("Use typical minimums", details.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());

            details.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            idle();
            FilterSettings typical = FilterStore.load(app);
            assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, typical.minimums());
            assertEquals("max stops stay", 3, typical.maxStops);
            assertTrue("and auto-decline", typical.enabled);
            assertTrue("and Autopilot", typical.autopilot);
            assertEquals("the bar back at exactly the minimums", 100, typical.minimumScalePercent);
            assertEquals("Typical minimums set: $4 · $1/mi · $15/hr", ShadowToast.getTextOfLatestToast());
            AutopilotStore.Change change = AutopilotStore.lastChange(app);
            assertEquals(50, change.from);
            assertEquals(100, change.to);
            assertEquals("RULES_CHANGED", change.why);
            String log = DiagnosticLog.read(app);
            assertTrue(log, log.contains("[autopilot] commit 50% -> 100% (you changed your minimums)"));
            assertTrue(log, log.contains("[rules] typical minimums chosen from Autopilot's details"));
            assertTrue("the bar went back before the rules changed", log.indexOf("commit 50% -> 100%")
                    < log.indexOf("typical minimums chosen"));
            Autopilot.Plan plan = AutopilotRuntime.latest();
            assertNotNull(plan);
            assertEquals("its plan for them starts from 100", 100, plan.current);
            assertEquals(typical.rulesKey(), plan.rulesKey);
        }
    }

    /**
     * Pinned with the typical minimums already ($4.00, $1.00 a mile, $15 an hour; max stops 2 against three-stop
     * offers): the details offer the goal, not typical minimums, whose tap would only have put the bar back at 100%
     * mid-dash with a "you changed your minimums" note the user never made. Nothing moves until the user changes
     * something.
     */
    @Test
    public void pinnedWithTheTypicalMinimumsAlreadyTheDetailsOfferTheGoalAndTheBarStays() {
        setUpForDashing();
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 2));
        FilterSettings rules = FilterStore.load(app);
        for (int i = PAY.length - 1; i >= 0; i--) {
            OfferSnapshot facts = new OfferSnapshot(PAY[i], MILES[i], MINUTES[i], 3);
            DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts,
                    OfferRule.evaluate(facts, rules), DecisionLog.Action.CONFIRMATION_TAPPED, true,
                    Collections.<String>emptyList()).withTime(wall - (2 + 3L * i) * MIN));
        }
        reading(9, 1);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        assertEquals("pinned at the lowest bar", 50, FilterStore.load(app).minimumScalePercent);
        AutopilotStore.Change committed = AutopilotStore.lastChange(app);
        String logBefore = DiagnosticLog.read(app);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertEquals("Autopilot 50% (lowest) · AR 9% → 70%: about 61 more accepts", autopilotLine(content));
            shownLineStarting(content, "Autopilot ").performClick();
            idle();
            AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
            String said = message(details);
            assertTrue(said, said.endsWith("Even at the lowest bar (50%) only 0 of your last 20 offers pass. Lower a "
                    + "minimum or turn off max stops."));
            assertFalse("no typical minimums: they are these already", said.contains("typical"));
            assertEquals("Change goal", details.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());

            details.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            idle();
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.CHOOSER_TITLE, title(chooser));
            chooser.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            FilterSettings after = FilterStore.load(app);
            assertEquals("the bar stays where Autopilot put it", 50, after.minimumScalePercent);
            assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, after.minimums());
            AutopilotStore.Change last = AutopilotStore.lastChange(app);
            assertEquals("no change noted that the user never made", committed.at, last.at);
            assertEquals(committed.why, last.why);
            assertEquals(committed.to, last.to);
            assertNull("and no jump pending", AutopilotStore.jump(app));
            String log = DiagnosticLog.read(app);
            assertFalse(log, log.substring(Math.min(log.length(), logBefore.length())).contains("typical minimums"));
        }
    }

    // ---- A short split pane: the chip at the status line's start ----

    @Test @Config(qualifiers = "w411dp-h360dp-420dpi")
    public void inA360DpTallSplitPaneTheChipStandsAtTheStatusLinesStartAtNoHeight() {
        chipInAShortPane(false, 1f, false);
    }

    @Test @Config(qualifiers = "w360dp-h360dp-xhdpi")
    public void atThreeSixtyWideAndAtNightTheChipStillFitsAndReads() {
        chipInAShortPane(true, 1f, false);
    }

    @Test @Config(qualifiers = "w320dp-h360dp-xhdpi")
    public void atThreeTwentyWideTheChipAndItsLineStillFit() {
        chipInAShortPane(false, 1f, false);
    }

    @Test @Config(qualifiers = "w360dp-h360dp-xhdpi")
    public void atOneAndAHalfTimesTheFontSizeTheChipCostsThePageNoHeight() {
        chipInAShortPane(false, 1.5f, false);
    }

    @Test @Config(qualifiers = "w360dp-h360dp-xhdpi")
    public void atTwiceTheFontSizeTheChipStaysWholeAndOnScreen() {
        chipInAShortPane(false, 2f, false);
    }

    @Test @Config(qualifiers = "w411dp-h360dp-420dpi")
    public void atTwiceTheFontSizeOnAWiderPaneThePageStaysOneScreen() {
        chipInAShortPane(false, 2f, false);
    }

    @Test @Config(qualifiers = "w320dp-h360dp-xhdpi")
    public void atTwiceTheFontSizeAtNightOnANarrowPaneTheChipStaysWholeAndOnScreen() {
        chipInAShortPane(true, 2f, false);
    }

    // ---- Beside Dasher: the same strip (0.5.1 swapped the chip for the radar's button there) ----

    @Test @Config(qualifiers = "w360dp-h360dp-xhdpi")
    public void besideDasherTheChipStaysAndTheRadarsButtonAgreesWithIt() {
        chipInAShortPane(false, 1f, true);
    }

    @Test @Config(qualifiers = "w360dp-h360dp-xhdpi")
    public void besideDasherAtALargerFontTheChipAndItsLineKeepTheirRoom() {
        chipInAShortPane(false, 1.3f, true);
    }

    @Test @Config(qualifiers = "w360dp-h360dp-xhdpi")
    public void besideDasherAtOneAndAHalfTimesTheFontNothingIsPushedOffScreen() {
        chipInAShortPane(false, 1.5f, true);
    }

    @Test @Config(qualifiers = "w360dp-h360dp-xhdpi")
    public void besideDasherAtTwiceTheFontAtNightTheChipAndItsLineStayWhole() {
        chipInAShortPane(true, 2f, true);
    }

    @Test @Config(qualifiers = "w320dp-h360dp-xhdpi")
    public void besideDasherOnANarrowPaneAtTwiceTheFontThePageIsOneScreen() {
        chipInAShortPane(false, 2f, true);
    }

    @Test @Config(qualifiers = "w411dp-h360dp-420dpi")
    public void besideDasherOnAWiderPaneAtTwiceTheFontThePageIsOneScreen() {
        chipInAShortPane(false, 2f, true);
    }

    /**
     * Beside Dasher in a half with room for the radar's Autopilot button beside its knobs (in a shorter one the chip
     * is Autopilot's control alone, as in 0.5.1's short windows): the button and the chip agree, amber below the goal,
     * and both say Off once Autopilot is off.
     */
    @Test @Config(qualifiers = "w411dp-h600dp-420dpi")
    public void besideDasherWhereTheRadarHasRoomItsButtonAgreesWithTheChip() {
        setUpForDashing();
        minimums(400, 100, 25);
        history(0, 20);
        reading(55, 12);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = page(activity);
            OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
            refreshed();
            settleSky(content);
            Ui ui = new Ui(activity.get());
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertEquals("the radar wholly there", 1f, find(content, ScenePage.class).shown(star), 0f);
            assertTrue("with its Autopilot button", star.autopilotShown());
            AutopilotChip chip = find(content, AutopilotChip.class);
            assertTrue(chip.isShown());
            assertEquals("Auto 100% ▲", chip.getText().toString());
            assertEquals("amber below the goal, the chip", AutopilotChip.amber(ui), chip.getCurrentTextColor());
            assertEquals("and the button", AutopilotChip.amber(ui), star.buttonRing());

            AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
            OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
            refreshed();
            settleSky(content);
            assertEquals("Auto off", chip.getText().toString());
            assertTrue("the button says Off", drawnWords(star).contains("Off"));
        } finally {
            OfferFilterService.sawDasherBeside(0);
        }
    }

    /**
     * Half of a split screen, 360 dp tall, set up as while dashing (screen reading and background offers on), with
     * Autopilot on below its goal, beside another app or beside Dasher (the page is the same either way; 0.5.1 put the
     * constellation in the header beside another app, the chip beside the latest offer's caption): Autopilot's chip at
     * the start of its status line in the strip, whole, on screen, a 48 dp target, readable by day and night, heard in
     * words, a tap opening the details and a long press the chooser. It never stands on a line of its own and adds no
     * height of its own: the row is the chip's 48 dp or the status line's words beside it, whichever is taller; beside
     * it the words shrink only as far as keeping to the lines they have alone needs, never below three quarters of the
     * user's size nor below the default size (at the normal size they keep it, Autopilot's long status wrapping beside
     * the chip in a narrow pane). The verdict is whole on screen as the page opens, and the chip and the status line
     * too wherever the strip fits the pane; the page is one screen, or at twice the font, where the strip and the
     * header need more than the pane, it scrolls by the difference with the stage gone. No Next match takes a row while Autopilot is on; the map, wherever it is wholly there, keeps its
     * readable least; wherever the radar has room for its Autopilot button beside its knobs, the button agrees with the
     * chip, amber below the goal. Off, the chip stays as the way back in.
     */
    private void chipInAShortPane(boolean night, float fontScale, boolean besideDasher) {
        Appearance.choose(app, night ? Appearance.Mode.NIGHT : Appearance.Mode.DAY);
        RuntimeEnvironment.setFontScale(fontScale);
        setUpForDashing();
        minimums(400, 100, 25);
        history(0, 20);
        reading(55, 12);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        if (besideDasher) OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = page(activity);
            settleSky(content);
            refreshed();
            if (besideDasher) OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
            layOut(content);
            Ui ui = new Ui(activity.get());
            AutopilotChip chip = find(content, AutopilotChip.class);
            assertNotNull(chip);
            assertTrue("in the strip", isDescendant(stripWords(content), chip));
            assertTrue("shown", chip.isShown());
            assertEquals("Auto 100% ▲", chip.getText().toString());
            assertEquals("Autopilot bar 100 percent. Acceptance rate 53 percent, goal 70 percent: about 17 more accepts. "
                    + "Opens Autopilot details.", String.valueOf(chip.getContentDescription()));
            assertTrue("a 48 dp target", chip.getHeight() >= ui.dp(48) - 1 && chip.getWidth() >= ui.dp(48) - 1);
            assertEquals("one line, never cut short", 0, chip.getLayout().getEllipsisCount(0));
            assertTrue("readable: " + Integer.toHexString(chip.getCurrentTextColor()),
                    contrast(chip.getCurrentTextColor(), ui.surface) >= 4.5);
            assertEquals("amber below the goal", AutopilotChip.amber(ui), chip.getCurrentTextColor());
            TextView line = statusLine(content);
            assertEquals("Autopilot's status beside it", AutopilotText.statusLine(AutopilotRuntime.status(app,
                    System.currentTimeMillis(), true)), line.getText().toString());
            assertEquals("every word of it", line.getText().length(),
                    line.getLayout().getLineEnd(line.getLineCount() - 1));
            assertNull("never Next match while Autopilot is on", shownLineStarting(content, "Next match"));

            // The verdict whole on screen as the page opens; the chip and its line too, wherever the strip fits the
            // pane (at twice the font on Android 8, whose fonts grow linearly, it can run past a 360 dp pane: then
            // they are a scroll away).
            ScenePage scene = find(content, ScenePage.class);
            android.widget.ScrollView window = (android.widget.ScrollView) scene.getParent();
            Rect visible = new Rect();
            assertTrue(verdictLine(content).getGlobalVisibleRect(visible));
            assertEquals("the whole verdict on screen", verdictLine(content).getHeight(), visible.height());
            boolean stripFits = inWindow(stripWords(content)).bottom <= window.getHeight();
            if (!stripFits) {
                assertTrue("only at twice the font", fontScale >= 2f);
                window.scrollTo(0, window.getChildAt(0).getHeight() - window.getHeight());
                layOut(content);
            }
            assertTrue(chip.getGlobalVisibleRect(visible));
            assertEquals("the whole chip is on screen", chip.getHeight(), visible.height());
            assertEquals(chip.getWidth(), visible.width());
            assertTrue(line.getGlobalVisibleRect(visible));
            assertEquals("the whole line on screen", line.getHeight(), visible.height());
            window.scrollTo(0, 0);
            layOut(content);

            // What the chip costs the page: beside the words, never more than their own height there; on a line of its
            // own above them only where that is shorter still, a chip at twice the font (Android 8's linear fonts)
            // leaving the words beside it too little of a 320 dp pane (0.5.1's chip had the whole width, beside the
            // caption).
            AutopilotChip.Row row = (AutopilotChip.Row) chip.getParent();
            assertSame(row, line.getParent());
            int rowWith = row.getHeight();
            float shrunk = line.getTextSize();
            boolean stacked = row.stacked();
            String before = "scale " + row.textScale() + ", tight " + row.tight() + ", chip " + chip.getWidth() + "x"
                    + chip.getHeight() + ", line " + line.getWidth() + "x" + line.getHeight() + " in "
                    + line.getLineCount() + " lines '" + line.getText() + "', row " + rowWith;
            assertTrue("a line of its own only at twice the font: " + before, !stacked || fontScale >= 2f);
            chip.setVisibility(View.GONE);
            layOut(content);
            int rowAlone = row.getHeight();
            float full = line.getTextSize();
            String gone = "line " + line.getWidth() + "x" + line.getHeight() + " in " + line.getLineCount()
                    + " lines at full size, row " + rowAlone;
            chip.setVisibility(View.VISIBLE);
            layOut(content);
            assertEquals("the same row again: " + before + " / " + gone, rowWith, row.getHeight());
            assertEquals("beside: the chip's or the words' height, whichever is taller; above: the two: " + before,
                    stacked ? chip.getHeight() + line.getHeight() : Math.max(chip.getHeight(), line.getHeight()),
                    rowWith);
            assertTrue(verdictLine(content).getGlobalVisibleRect(visible));
            assertEquals("the whole verdict on screen", verdictLine(content).getHeight(), visible.height());
            MinimumsStarView star = find(content, MinimumsStarView.class);
            if (scene.getHeight() > window.getHeight()) {
                assertTrue("scrolling only at a very large font: " + scene.getHeight() + " in " + window.getHeight()
                        + "; " + before + " / " + gone, fontScale >= 2f);
                assertEquals("with the stage gone", 0f, scene.shown(star), 0f);
            }
            android.util.DisplayMetrics metrics = app.getResources().getDisplayMetrics();
            float least = Math.min(full, Math.max(SetupRow.LEAST_SCALE * full,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14, metrics)));
            assertTrue("never below three quarters of the user's size nor the default size: " + shrunk + " of " + full,
                    shrunk >= least - 0.5f);
            float chipFull = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, AutopilotChip.SP, metrics);
            assertTrue(chip.getTextSize() >= Math.min(chipFull, Math.max(SetupRow.LEAST_SCALE * chipFull,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, AutopilotChip.SP, metrics))) - 0.5f);
            if (rowWith > rowAlone && !stacked) {
                assertEquals("taller than the words alone only once they are at their least size: " + before + " / "
                        + gone, least, shrunk, 0.5f);
            }
            if (fontScale <= 1f) assertEquals("at the normal size nothing shrinks", full, shrunk, 0.01f);
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue("the map keeps its readable least wherever it is wholly there",
                    scene.shown(map) < 1 || map.getHeight() >= ui.dp(84) - 1);
            boolean radar = scene.shown(star) >= 1 && star.autopilotShown();
            if (radar) {
                assertEquals("amber below the goal, as the chip is", AutopilotChip.amber(ui), star.buttonRing());
            }

            // A tap opens the details; a long press asks for the goal.
            chip.performClick();
            idle();
            AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.DETAILS_TITLE, title(details));
            details.dismiss();
            chip.performLongClick();
            idle();
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.CHOOSER_TITLE, title(chooser));
            assertEquals(0, chooser.getListView().getCheckedItemPosition());
            chooser.dismiss();
            AccessibilityNodeInfo said = chip.createAccessibilityNodeInfo();
            List<String> actions = new ArrayList<>();
            for (AccessibilityNodeInfo.AccessibilityAction action : said.getActionList()) {
                if (action.getLabel() != null) actions.add(action.getId() + "=" + action.getLabel());
            }
            assertTrue(actions.toString(), actions.contains(AccessibilityNodeInfo.ACTION_CLICK + "=Autopilot details"));
            assertTrue(actions.toString(), actions.contains(AccessibilityNodeInfo.ACTION_LONG_CLICK
                    + "=Change acceptance goal"));

            // Off, the chip stays as the way back in, at no cost either.
            int rowHeight = row.getHeight();
            AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
            if (besideDasher) OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
            refreshed();
            layOut(content);
            assertEquals("Auto off", chip.getText().toString());
            assertTrue(chip.isShown());
            assertTrue(contrast(chip.getCurrentTextColor(), ui.surface) >= 4.5);
            assertTrue("a line of its own only at twice the font", !row.stacked() || fontScale >= 2f);
            assertTrue("one screen, or only what never goes", scene.getHeight() <= window.getHeight()
                    || scene.shown(star) == 0);
            if (radar && star.autopilotShown()) assertTrue("the button says Off", drawnWords(star).contains("Off"));

            // On again: its words take their earlier width, and the row decides as it did then.
            AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
            if (besideDasher) OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
            refreshed();
            layOut(content);
            assertEquals("Auto 100% ▲", chip.getText().toString());
            assertEquals(stacked, row.stacked());
            assertEquals(rowHeight, row.getHeight());
        } finally {
            OfferFilterService.sawDasherBeside(0);
        }
    }

    /**
     * Pinned with pay first (no goal to fall below): the button's ring and the chip agree, both Autopilot's purple, as
     * they agree in amber below a goal.
     */
    @Test
    public void theButtonAndTheChipAgreeInColorWhenPinnedWithNoGoal() {
        setUpForDashing();
        FilterStore.save(app, FilterSettings.of(true, 4000, 800, 96, 3));
        history(0, 20);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        AutopilotRuntime.commitIfDue(app);
        AutopilotRuntime.commitIfDue(app);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            AutopilotChip hosted = find(content, AutopilotChip.class);
            refreshed();
            AutopilotText.Status status = AutopilotRuntime.status(app, System.currentTimeMillis(), true);
            assertTrue("pinned", status.pinned());
            assertFalse("pay first has no goal to fall below", status.belowGoal());
            Ui ui = new Ui(activity.get());
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertEquals(AutopilotChip.purple(ui), star.buttonRing());
            assertEquals(AutopilotChip.purple(ui), hosted.getCurrentTextColor());
        }
    }

    /** The strip's chip follows Autopilot's status with the line beside it, paused included, and its tap is theirs. */
    @Test @Config(qualifiers = "w411dp-h360dp-420dpi")
    public void theStripsChipFollowsTheSameStatusAsItsLine() {
        connectReader();
        minimums(400, 100, 25);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            AutopilotChip hosted = find(content, AutopilotChip.class);
            refreshed();
            assertEquals("Auto learning", hosted.getText().toString());
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, hosted.getAccessibilityLiveRegion());
            FilterStore.save(app, FilterSettings.of(false, 400, 100, 25, 0));
            refreshed();
            assertEquals("Autopilot bar 100 percent. Auto-decline paused. Opens Autopilot details.",
                    String.valueOf(hosted.getContentDescription()));
            hosted.performClick();
            idle();
            assertEquals(AutopilotText.DETAILS_TITLE, title(ShadowAlertDialog.getLatestAlertDialog()));
        }
    }

    // ---- A new install ----

    @Test
    public void theStarterSavesTypicalMinimumsPausedAndThenAsksForTheGoal() {
        FilterStore.save(app, FilterSettings.of(false, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            TextView start = shownTextContaining(content, "Tap to start with typical minimums");
            assertNotNull("the start line", start);
            assertTrue(start.getHeight() >= new Ui(app).dp(48) - 1);
            start.performClick();
            idle();
            AlertDialog starter = ShadowAlertDialog.getLatestAlertDialog();
            assertOwnsItsTouches(starter);
            assertEquals("Start with typical minimums?", title(starter));
            assertEquals("$4.00 per offer · $1.00 per mile · $15 per hour of trip time. These are typical of "
                    + "Cincinnati offers seen in October 2026. Drag any knob to change them.", message(starter));
            assertEquals("Use these", starter.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("Set my own", starter.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            assertFalse("asking saves nothing", FilterStore.load(app).hasAnyRule());

            starter.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            FilterSettings saved = FilterStore.load(app);
            assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, saved.minimums());
            assertEquals(0, saved.maxStops);
            assertFalse("auto-decline stays paused: the mascot turns it on", saved.enabled);
            assertFalse("Autopilot waits for the user's answer", saved.autopilot);
            assertEquals("Rule saved. Tap the mascot to turn on auto-decline.", ShadowToast.getTextOfLatestToast());
            assertTrue(DiagnosticLog.read(app), DiagnosticLog.read(app).contains(
                    "[rules] typical minimums chosen from the start line"));
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("then the goal chooser", AutopilotText.CHOOSER_TITLE, title(chooser));
            assertEquals("Not now", chooser.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            chooser.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            assertFalse(FilterStore.load(app).autopilot);
            assertTrue("answered: never asked again on its own", FilterStore.goalAsked(app));
            settleSky(content);
            assertNull("the start line goes", shownTextContaining(content, "Tap to start with typical minimums"));
            assertNotNull(shownTextContaining(content, "Paused"));

            // The mascot turns auto-decline on, and the goal is not asked again.
            ShadowAlertDialog.reset();
            find(content, FilterHeroView.class).performClick();
            idle();
            assertTrue(FilterStore.load(app).enabled);
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    /** Owner's decision D1 from the starter too: "Use these" puts Autopilot's bar back at exactly 100 first. */
    @Test
    public void useTheseWithAutopilotOnPutsItsBarBackAtExactlyTheMinimumsFirst() {
        FilterStore.save(app, FilterSettings.of(false, 0, 0, 0, 0));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            shownTextContaining(content, MainActivity.START_LINE).performClick();
            idle();
            AlertDialog starter = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(MainActivity.STARTER_TITLE, title(starter));
            starter.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            FilterSettings saved = FilterStore.load(app);
            assertArrayEquals(new int[] {400, 100, 25, 0, 0, 0}, saved.minimums());
            assertFalse(saved.enabled);
            assertTrue(saved.autopilot);
            assertEquals("the bar back at exactly the minimums", 100, saved.minimumScalePercent);
            AutopilotStore.Change change = AutopilotStore.lastChange(app);
            assertEquals(82, change.from);
            assertEquals(100, change.to);
            assertEquals("RULES_CHANGED", change.why);
            String log = DiagnosticLog.read(app);
            int back = log.indexOf("[autopilot] commit 82% -> 100% (you changed your minimums)");
            int chosen = log.indexOf("[rules] typical minimums chosen from the start line");
            assertTrue(log, back >= 0 && chosen > back);
            assertEquals("its plan for them starts from 100", 100, AutopilotRuntime.latest().current);
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(AutopilotText.CHOOSER_TITLE, title(chooser));
            assertEquals("its stored goal checked", 1, chooser.getListView().getCheckedItemPosition());
        }
    }

    @Test
    public void setMyOwnMakesTheKnobsBeckonAndSavesNothing() {
        FilterStore.save(app, FilterSettings.of(false, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            find(content, FilterHeroView.class).performClick();
            idle();
            AlertDialog starter = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("the mascot offers the starter too", MainActivity.STARTER_TITLE, title(starter));
            starter.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            assertFalse(FilterStore.load(app).hasAnyRule());
            assertFalse(FilterStore.load(app).enabled);
            assertTrue("the hollow knobs beckon", find(content, MinimumsStarView.class).beckoned());
        }
    }

    @Test
    public void theFirstTimeTheMascotTurnsAutoDeclineOnWithAMoneyMinimumTheGoalIsAskedOnce() {
        FilterStore.save(app, FilterSettings.of(false, 700, 150, 30, 0));
        assertFalse(FilterStore.goalAsked(app));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            mascot.performClick();
            idle();
            assertTrue(FilterStore.load(app).enabled);
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("asked once", chooser);
            assertEquals(AutopilotText.CHOOSER_TITLE, title(chooser));
            assertEquals(0, chooser.getListView().getCheckedItemPosition());
            chooser.cancel();
            idle();
            assertFalse(FilterStore.load(app).autopilot);
            assertTrue(FilterStore.goalAsked(app));

            ShadowAlertDialog.reset();
            mascot.performClick();
            idle();
            assertFalse("paused", FilterStore.load(app).enabled);
            mascot.performClick();
            idle();
            assertTrue(FilterStore.load(app).enabled);
            assertNull("never again on its own", ShadowAlertDialog.getLatestAlertDialog());
        }

        // Max stops alone gives Autopilot nothing to scale: auto-decline goes on and nothing asks.
        FilterStore.setGoalAsked(app, false);
        FilterStore.save(app, FilterSettings.of(false, 0, 0, 0, 3));
        ShadowAlertDialog.reset();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            find(content, FilterHeroView.class).performClick();
            idle();
            assertTrue(FilterStore.load(app).enabled);
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test
    public void resumingShowsAutopilotsStatusAtOnceAndNeitherItNorPausingTellsAutopilotTheRulesChanged() {
        setUpForDashing();
        minimums(400, 100, 25);
        history(0, 20);
        reading(74, 1);
        FilterStore.save(app, FilterSettings.of(false, 400, 100, 25, 0));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            assertEquals("Autopilot 100% · auto-decline paused", autopilotLine(content));
            assertNull("no change pending", AutopilotStore.jump(app));
            FilterHeroView mascot = find(content, FilterHeroView.class);
            mascot.performClick();
            idle();
            assertTrue(FilterStore.load(app).enabled);
            assertEquals("not left checking until the next visit", "Autopilot 100% · AR 74%, goal 70%",
                    autopilotLine(content));
            mascot.performClick();
            idle();
            assertEquals("Autopilot 100% · auto-decline paused", autopilotLine(content));
            mascot.performClick();
            idle();
            assertEquals("Autopilot 100% · AR 74%, goal 70%", autopilotLine(content));
            assertNull("a pause and a resume make no jump", AutopilotStore.jump(app));
            assertFalse(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("commit "));
        }
    }

    // ---- Clear history ----

    @Test
    public void clearHistoryForgetsAutopilotsReadingAndChangeNoteAndKeepsItsSettings() {
        setUpForDashing();
        minimums(2040, 400, 48);
        history(0, 20);
        reading(9, 1);
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertEquals(AutopilotRuntime.Commit.COMMITTED, AutopilotRuntime.commitIfDue(app));
        int bar = FilterStore.load(app).minimumScalePercent;
        assertEquals(50, bar);
        assertNotNull(AutopilotStore.reading(app, System.currentTimeMillis()));
        assertNotNull(AutopilotStore.lastChange(app));
        assertTrue(AutopilotStore.recovering(app));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            iconDescribed(content, "Settings").performClick();
            idle();
            shownButton(content, "Clear history").performClick();
            idle();
            AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
            assertOwnsItsTouches(confirm);
            assertEquals(MainActivity.CLEAR_HISTORY, message(confirm));
            assertTrue("it names what of Autopilot's goes", message(confirm).contains("Autopilot's "
                    + "acceptance-rate reading and last change from this phone, with its note that your minimums grew. "
                    + "Your rules and Autopilot settings stay."));
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            assertNull("no acceptance-rate reading is left", AutopilotStore.reading(app, System.currentTimeMillis()));
            assertNull("nor the last change's note", AutopilotStore.lastChange(app));
            assertFalse(AutopilotStore.recovering(app));
            assertTrue(DecisionLog.recent(app, 10).isEmpty());
            FilterSettings kept = FilterStore.load(app);
            assertTrue("Autopilot stays on", kept.autopilot);
            assertEquals(FilterSettings.GOAL_TOP_TIER, kept.autopilotGoalPercent);
            assertEquals("its bar moves only at its next safe point", bar, kept.minimumScalePercent);
            assertArrayEquals(new int[] {2040, 400, 48, 0, 0, 0}, kept.minimums());
            assertEquals("and then back to exactly the minimums: an empty history is learning", "CLEARED",
                    AutopilotStore.jump(app));
            Autopilot.Plan plan = AutopilotRuntime.latest();
            assertNotNull(plan);
            assertEquals(Autopilot.Mode.LEARNING, plan.mode);
            iconDescribed(content, "Back").performClick();
            idle();
            assertEquals("Autopilot " + bar + "% · learning (0 of 20 offers)", autopilotLine(content));
        }
    }

    // ---- The ticket ----

    @Test
    public void theTicketSaysWhenAnOfferPassedOnlyByAutopilotsBarAndWhenDecliningWasFree() {
        FilterSettings rules = FilterSettings.of(true, 400, 100, 25, 3).withAutopilot(true, 70)
                .withMinimumScalePercent(82);
        // The spec's example: $5.75 for 6.6 mi and 27 min asks $6.75 at the minimums, $5.54 at an 82% bar.
        OfferSnapshot facts = new OfferSnapshot(575, 6.6, 27, 2);
        OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
        assertEquals(OfferRule.Result.KEEP, decision.result);
        assertEquals("below your minimums; passes the 82% bar", decision.reason);
        long at = System.currentTimeMillis() - 5_000;
        DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision,
                DecisionLog.Action.PASSES, true, Collections.singletonList("$5.75")).withTime(at)
                .withStep(new DecisionLog.Step(DecisionLog.StepKind.AR_EXEMPT, at + 10, "")));
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            // On the homepage it never reads as a full pass: its line, its building and its marks say so.
            TextView caption = shownTextContaining(content, "Latest · ");
            assertEquals("Latest · $5.75 · Passed below your minimums", caption.getText().toString());
            assertTrue(String.valueOf(caption.getContentDescription()),
                    String.valueOf(caption.getContentDescription()).startsWith(
                            "Latest · $5.75 · Passed below your minimums."));
            String skyline = String.valueOf(find(content, DecisionChartView.class).getContentDescription());
            assertTrue(skyline, skyline.contains("1 passed (1 below your minimums)")
                    && skyline.contains(": $5.75, 6.6 mi"));
            assertTrue(skyline, skyline.contains(", passed below your minimums, score 85% of your minimums."));
            MinimumsStarView star = find(content, MinimumsStarView.class);
            String mark = String.valueOf(star.getAccessibilityNodeProvider()
                    .createAccessibilityNodeInfo(MinimumsStarView.OFFER_ID).getContentDescription());
            assertTrue(mark, mark.startsWith("Offer $5.75, 6.6 mi, 27 min, 2 stops, passed below your minimums"));
            openTicket(content);
            idle();
            layOut(content);
            assertNotNull(shownTextContaining(content, "Score 85% of your minimums · bar 82%"));
            assertNotNull(shownTextContaining(content,
                    "Below your minimums: passed by Autopilot's 82% bar. Never auto-accepted."));
            assertNotNull(shownTextContaining(content,
                    "Dasher said declining it does not lower your acceptance rate."));
            assertNotNull(shownTextContaining(content, "Below your minimums · passed by Autopilot's 82% bar"));
            assertNotNull("the key to how it was judged", shownButton(content, "How this offer was judged"));
        }
    }

    @Test
    public void anOfferThatMetTheMinimumsSaysOnlyItsScore() {
        FilterSettings rules = FilterSettings.of(true, 400, 100, 25, 3);
        OfferSnapshot facts = new OfferSnapshot(900, 6.6, 27, 2);
        OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
        assertEquals("meets your minimums", decision.reason);
        DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision,
                DecisionLog.Action.PASSES, true, Collections.singletonList("$9.00"))
                .withTime(System.currentTimeMillis() - 5_000));
        FilterStore.save(app, rules);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            openTicket(content);
            idle();
            layOut(content);
            TextView score = shownTextContaining(content, "Score 133% of your minimums");
            assertNotNull(score);
            assertEquals("no bar at exactly the minimums", "Score 133% of your minimums", score.getText().toString());
            assertNull(shownTextContaining(content, "passed by Autopilot"));
            assertNull(shownTextContaining(content, "declining it does not lower"));
            assertNotNull(shownTextContaining(content, "Meets your minimums"));
        }
    }

    // ---- Settings ----

    @Test
    public void settingsGainNoAutopilotRowAndAutoAcceptAsksInItsNewWords() {
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = page(activity);
            settleSky(content);
            iconDescribed(content, "Settings").performClick();
            idle();
            assertTrue(settingsShown(content));
            assertNull("Autopilot lives on the homepage", shownTextContaining(content, "Autopilot"));
            assertNull(shownTextContaining(content, "acceptance rate"));
            assertNull(shownTextContaining(content, "goal"));

            View row = findButton(content, "Auto-accept matching offers");
            assertNotNull(row);
            row.performClick();
            idle();
            AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
            assertOwnsItsTouches(confirm);
            assertEquals("Auto-accept matching offers?", title(confirm));
            assertEquals("This can commit you to a delivery without another tap. It accepts only complete standalone "
                    + "offers that meet 100% of your minimums (and Autopilot's bar when that is higher). Offers "
                    + "Autopilot lets through below your minimums are always left to you. Add-ons and unclear offers "
                    + "stay yours. Your touch stops the current attempt.\n\nThe app can misread an offer. Enable this "
                    + "only if you accept that risk.", message(confirm));
            assertEquals("Not now", confirm.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            assertEquals("Enable auto-accept", confirm.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            assertFalse(FilterStore.autoAcceptEnabled(app));
        }
    }
}
