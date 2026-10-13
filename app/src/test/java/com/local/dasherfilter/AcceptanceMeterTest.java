package com.local.dasherfilter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Looper;
import android.text.Layout;
import android.view.View;
import android.widget.TextView;
import java.util.Collections;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The acceptance meter on the status words' one line (0.6.11; the owner, 13 October 2026, of 0.6.10's track under the
 * words in his split screen: "the acceptance rating bar width is causing a line break in the layout"). Wherever its
 * track shows, it stands after the words on their line, its middle with theirs and with Autopilot's chip, from
 * {@link AcceptanceMeter#GAP_DP} past their end towards the row's end, never longer than the room their line leaves nor
 * {@link AcceptanceMeter#MOST_DP}, and nothing is drawn under the words; where they take more than a line, or are cut,
 * there is none. Swept across window widths, in split screen and full screen, at the normal font and twice it, with
 * Autopilot on and off; and at the owner's window, 411 dp beside Dasher with Autopilot off, the track is wholly there
 * and reaches the row's end.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AcceptanceMeterTest extends AndroidAdapterTestBase {
    /** The widths swept, in dp, and the step. */
    private static final int FROM_DP = 220;
    private static final int TO_DP = 900;
    private static final int STEP_DP = 8;
    private static final int HEIGHT_DP = 416;

    private ServiceController<OfferFilterService> screen;
    private ServiceController<OfferNotificationService> listener;

    @After
    public void down() {
        if (listener != null && OfferNotificationService.isConnected()) listener.destroy();
        if (screen != null) screen.destroy();
        OfferFilterService.sawDasherBeside(0);
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.forgetCache();
        RuntimeEnvironment.setFontScale(1f);
    }

    // ---- The owner's window ----

    /**
     * His split screen's top half beside Dasher, 411 dp wide, Autopilot off: "Auto off", "ACCEPTANCE 8%", then the
     * track on that line to the row's end, drawn there and nowhere under the words.
     */
    @Test @Config(qualifiers = "w411dp-h416dp-420dpi")
    public void besideDasherTheTrackStandsOnTheWordsLineToTheRowsEnd() {
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> controller = homepage(true, false, 1f);
        View content = controller.get().findViewById(android.R.id.content);
        Ui ui = new Ui(app);
        TextView words = statusLine(content);
        assertEquals("ACCEPTANCE  8%", words.getText().toString());
        assertEquals("heard as before", "Acceptance rate 8 percent", String.valueOf(words.getContentDescription()));
        AcceptanceMeter meter = (AcceptanceMeter) words.getBackground();
        RectF track = new RectF();
        assertEquals("wholly there", 1f, meter.track(track), 0f);
        assertOnTheWordsLine("411 beside Dasher", content, ui, track);
        View row = chipRow(content);
        assertSame(row, words.getParent());
        assertEquals("to the row's end", words.getWidth() - ui.dp(AcceptanceMeter.END_DP), track.right, 1f);
        assertEquals("the row is the words' to its end", row.getWidth() - row.getPaddingRight(), words.getRight());

        // As drawn: the track along the words' line, and nothing under it (0.6.10's track ran there).
        Bitmap drawn = Bitmap.createBitmap(words.getWidth(), words.getHeight(), Bitmap.Config.ARGB_8888);
        words.draw(new Canvas(drawn));
        int middle = Math.round(track.centerY());
        for (int x = Math.round(track.left + track.height()); x < Math.round(track.right - track.height()); x++) {
            assertTrue("the track drawn at " + x, Color.alpha(drawn.getPixel(x, middle)) > 0);
        }
        Layout layout = words.getLayout();
        int under = Math.round(words.getTotalPaddingTop() + layout.getLineBottom(0)) + ui.dp(1);
        for (int y = under; y < drawn.getHeight(); y++) {
            for (int x = 0; x < drawn.getWidth(); x++) {
                assertEquals("nothing under the words at " + x + "," + y, 0, Color.alpha(drawn.getPixel(x, y)));
            }
        }
        drawn.recycle();
        controller.pause().stop().destroy();
    }

    /**
     * The forms at the owner's width (0.6.11): beside Dasher with Autopilot off, "ACCEPTANCE 8%" with its track; on (the
     * default), "AR 8% / 70%" there and "AR 8%" on his full screen, whose chips' row ends early; and in a wide window
     * "ACCEPTANCE 8% / 70%" with its longest track.
     */
    @Test @Config(qualifiers = "w411dp-h416dp-420dpi")
    public void besideDasherWithAutopilotOnTheLabelShortensBeforeTheGoalGoes() throws Exception {
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> split = homepage(true, true, 1f);
        View content = split.get().findViewById(android.R.id.content);
        assertEquals("411 beside Dasher, on", "AR  8% / 70%", statusLine(content).getText().toString());
        resize(split, 900, 1f);
        assertEquals("900, on", "ACCEPTANCE  8% / 70%", statusLine(content).getText().toString());
        RectF track = new RectF();
        ((AcceptanceMeter) statusLine(content).getBackground()).track(track);
        assertEquals("its longest", new Ui(app).dp(AcceptanceMeter.MOST_DP), track.width(), 1f);
        split.pause().stop().destroy();
    }

    /**
     * His own full screen, 411 dp, Autopilot on: 0.6.11's chips' row ended early there ("AR 8%"); since 0.6.12 (the owner:
     * "still awkward negative space gaps") it keeps the row's whole width, so the whole label and its goal.
     */
    @Test @Config(qualifiers = "w411dp-h891dp-420dpi")
    public void onHisFullScreenWithAutopilotOnTheGoalIsTheTicksAlone() {
        ActivityController<MainActivity> full = homepage(false, true, 1f);
        View content = full.get().findViewById(android.R.id.content);
        assertEquals("411 full screen, on", "ACCEPTANCE  8% / 70%", statusLine(content).getText().toString());
        full.pause().stop().destroy();
    }

    // ---- Every width ----

    @Test @Config(qualifiers = "w220dp-h416dp-420dpi")
    public void inSplitScreenWithAutopilotOff() throws Exception {
        sweep("split, off", true, false, 1f);
    }

    @Test @Config(qualifiers = "w220dp-h416dp-420dpi")
    public void inSplitScreenWithAutopilotOn() throws Exception {
        sweep("split, on", true, true, 1f);
    }

    @Test @Config(qualifiers = "w220dp-h416dp-420dpi")
    public void fullScreenWithAutopilotOff() throws Exception {
        sweep("full, off", false, false, 1f);
    }

    @Test @Config(qualifiers = "w220dp-h416dp-420dpi")
    public void fullScreenWithAutopilotOn() throws Exception {
        sweep("full, on", false, true, 1f);
    }

    @Test @Config(qualifiers = "w220dp-h416dp-420dpi")
    public void inSplitScreenAtTwiceTheFont() throws Exception {
        sweep("split, off, font 2", true, false, 2f);
    }

    @Test @Config(qualifiers = "w220dp-h416dp-420dpi")
    public void fullScreenAtTwiceTheFontWithAutopilotOn() throws Exception {
        sweep("full, on, font 2", false, true, 2f);
    }

    /**
     * The homepage resized from {@link #FROM_DP} to {@link #TO_DP} wide a step at a time, as a dragged divider resizes
     * it: at every width the track, where it shows, stands on the words' one line; where the words wrap or are cut
     * there is none; and from one width to the next it moves and fades no faster than the band (three times the
     * window's edge) carries it. In the widest window it is wholly there, at its longest at the normal font.
     */
    private void sweep(String name, boolean split, boolean autopilot, float font) throws Exception {
        ActivityController<MainActivity> controller = homepage(split, autopilot, font);
        View content = controller.get().findViewById(android.R.id.content);
        Ui ui = new Ui(app);
        float density = app.getResources().getDisplayMetrics().density;
        RectF track = new RectF();
        RectF before = null;
        float shownBefore = -1;
        String textBefore = null;
        float most = 0;
        for (int widthDp = FROM_DP; widthDp <= TO_DP; widthDp += STEP_DP) {
            resize(controller, widthDp, font);
            String where = name + " at " + widthDp + " dp";
            TextView words = statusLine(content);
            assertTrue(where + ": the meter", words.getBackground() instanceof AcceptanceMeter);
            Layout layout = words.getLayout();
            float shown = ((AcceptanceMeter) words.getBackground()).track(track);
            if (layout.getLineCount() != 1 || layout.getEllipsisCount(0) > 0) {
                assertEquals(where + ": no track beside words that wrap or are cut", 0f, shown, 0f);
            }
            // The words on one line, never cut, where the row leaves the shortest its width: the first of their forms
            // that leaves the track its room (0.6.11).
            AcceptanceMeter.Words forms = (AcceptanceMeter.Words) words;
            int form = forms.form();
            assertTrue(where + ": one of its forms, \"" + words.getText() + "\"", form >= 0);
            boolean last = form == (autopilot ? 2 : 1);
            assertMeterForm(where, forms, last);
            if (!last) {
                assertTrue(where + ": a longer form leaves the track its room, " + track,
                        shown == 1f && track.width() >= ui.dp(AcceptanceMeter.ROOM_DP) - 1);
            }
            String text = words.getText().toString();
            if (!text.equals(textBefore)) {
                // Another form: the track starts where it ends, so it is compared from this width on.
                before = null;
                shownBefore = -1;
            }
            textBefore = text;
            if (shown > 0) {
                assertOnTheWordsLine(where, content, ui, track);
                assertTrue(where + ": no shorter than half its least " + track,
                        track.width() >= ui.dp(AcceptanceMeter.LEAST_DP) / 2f - 1);
                most = Math.max(most, track.width());
            }
            // From the last width: moved and faded no faster than the band carries it, three times the window's edge.
            float edge = 3 * STEP_DP * density + 2;
            if (before != null && shown > 0) {
                assertTrue(where + ": moved from " + before + " to " + track, Math.abs(track.left - before.left)
                        <= edge && Math.abs(track.right - before.right) <= edge);
            }
            if (shownBefore >= 0) {
                assertTrue(where + ": faded from " + shownBefore + " to " + shown, Math.abs(shown - shownBefore)
                        <= edge / (ui.dp(AcceptanceMeter.LEAST_DP) / 2f) + 1e-4f);
            }
            before = shown > 0 ? new RectF(track) : null;
            shownBefore = shown;
        }
        assertEquals(name + ": wholly there in the widest window", 1f, shownBefore, 0f);
        if (font <= 1f) {
            assertEquals(name + ": at its longest there", ui.dp(AcceptanceMeter.MOST_DP), before.width(), 1f);
        }
        assertTrue(name + ": never longer than its most, " + most, most <= ui.dp(AcceptanceMeter.MOST_DP) + 1);
        controller.pause().stop().destroy();
    }

    // ---- Fixtures ----

    /** The window {@code widthDp} wide, as a dragged divider resizes it (FluidPageTest's way), at {@code font}. */
    private static void resize(ActivityController<MainActivity> controller, int widthDp, float font) throws Exception {
        RuntimeEnvironment.setQualifiers("+w" + widthDp + "dp-h" + HEIGHT_DP + "dp");
        RuntimeEnvironment.setFontScale(font);
        controller.configurationChange();
        View decor = controller.get().getWindow().getDecorView();
        Object root = View.class.getMethod("getViewRootImpl").invoke(decor);
        org.robolectric.shadows.ShadowViewRootImpl window = org.robolectric.shadow.api.Shadow.extract(root);
        window.callDispatchResized();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * The track {@code track} (in the status words' pixels) on the words' one line: after their end by the gap, its
     * middle with their line's and within a pixel of Autopilot's chip's, inside the row and no longer than its most.
     */
    private static void assertOnTheWordsLine(String where, View content, Ui ui, RectF track) {
        TextView words = statusLine(content);
        Layout layout = words.getLayout();
        assertEquals(where + ": the words on one line", 1, layout.getLineCount());
        assertEquals(where + ": none cut", 0, layout.getEllipsisCount(0));
        float end = words.getCompoundPaddingLeft() + layout.getLineRight(0);
        assertTrue(where + ": after the words " + end + ", " + track,
                track.left >= end + ui.dp(AcceptanceMeter.GAP_DP) - 1);
        assertTrue(where + ": inside the row " + track + " in " + words.getWidth(),
                track.right <= words.getWidth() - ui.dp(AcceptanceMeter.END_DP) + 1);
        assertTrue(where + ": no longer than its most " + track, track.width() <= ui.dp(AcceptanceMeter.MOST_DP) + 1);
        float line = words.getTotalPaddingTop() + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f;
        assertEquals(where + ": its middle the words' line's", line, track.centerY(), 1f);
        AutopilotChip chip = find(content, AutopilotChip.class);
        float chipMiddle = chip.getTop() + chip.getHeight() / 2f;
        float trackMiddle = words.getTop() + track.centerY();
        assertEquals(where + ": its middle the chip's", chipMiddle, trackMiddle, ui.dp(1));
    }

    /**
     * The homepage during a dash with a known acceptance rate (8%, Dasher's, a minute ago): Autopilot off, the meter
     * shows the rate alone; on, after 22 offers, the rate below its 70% goal with the goal's tick.
     */
    private ActivityController<MainActivity> homepage(boolean split, boolean autopilot, float font) {
        RuntimeEnvironment.setFontScale(font);
        AutopilotRuntime.executorForTests = Runnable::run;
        dasherInstalled();
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        long wall = System.currentTimeMillis();
        FilterSettings rules = FilterStore.load(app);
        for (int i = 21; i >= 0; i--) {
            OfferSnapshot facts = new OfferSnapshot(300 + 50 * i, (double) (3 + i % 5), 15 + i, 2);
            OfferRule.Decision decision = OfferRule.evaluate(facts, rules);
            DecisionLog.Action action = decision.result == OfferRule.Result.DECLINE
                    ? DecisionLog.Action.CONFIRMATION_TAPPED
                    : decision.result == OfferRule.Result.KEEP ? DecisionLog.Action.PASSES
                    : DecisionLog.Action.NEEDS_REVIEW;
            DecisionLog.record(app, DecisionLog.Entry.of(DecisionLog.Source.SCREEN, false, facts, decision, action,
                    true, Collections.<String>emptyList()).withTime(wall - (2 + 3L * i) * 60_000L));
        }
        assertTrue(AutopilotStore.recordReading(app, 8, "", wall - 60_000L));
        screen = Robolectric.buildService(OfferFilterService.class).create();
        screen.get().onServiceConnected();
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AutopilotRuntime.setAutopilot(app, autopilot, FilterSettings.GOAL_TOP_TIER);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        if (split) Shadows.shadowOf(controller.get()).setInMultiWindowMode(true);
        controller.setup();
        View content = controller.get().findViewById(android.R.id.content);
        settleSky(content);
        TextView words = statusLine(content);
        String rate = autopilot ? "8% / 70%" : "8%";
        String shown = words.getText().toString();
        assertTrue("\"" + shown + "\"", shown.equals("ACCEPTANCE  " + rate) || shown.equals("AR  " + rate) || shown.equals("AR  8%"));
        return controller;
    }
}
