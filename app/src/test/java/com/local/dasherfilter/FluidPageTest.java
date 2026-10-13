package com.local.dasherfilter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The homepage's one fluid layout on the real page (the owner, 7 October 2026, on 0.5.1's split screen: "I do not like
 * how it only shows the map or the radar"; "it should be gradual and seamless"; and 12 October 2026: "at small split
 * ratios I still want to see all relevant infographic"). A window is resized from a sliver of a split screen to a tall
 * phone, {@link #STEP_DP} dp at a time, narrow and wide, in split screen and full screen, at the normal font and twice
 * it, the way dragging the divider resizes it: the page is laid out again and nothing else happens (the screen is
 * never made again, so the screen reader's work for Dasher never waits on it). At every size:
 *
 * <ul>
 * <li>the band along the top (0.6.3) is wholly there and on screen, and goes by the window's width alone: the mascot,
 * the latest offer's tile (its pay never cut; the whole offer where the tile has its room) and the dash's counts in
 * its first row; Autopilot's chip and the scoring mode's, never cut, with the status words after them; the header's
 * buttons beside them or on a row of their own; and the setup step still to do under the band;</li>
 * <li>from a stated height up, the radar (the constellation with its knobs), the skyline and the offer map are all
 * wholly there and on screen at a usable size, side by side in a short window, one above the other in the tallest
 * narrow one;</li>
 * <li>every target there is at least 48 dp each way, no two parts overlap, no words are cut off (but the status words,
 * which the band cuts to the room it leaves them, an ellipsis at their end, screen readers hearing them whole), and
 * nothing stands outside the page;</li>
 * <li>from one size to the next nothing jumps (no edge moves more than {@link FluidLayout#TURN_RATE} times the
 * window's change, give or take {@link #ROUNDING_DP}) and nothing pops in or out (no part fades by more than
 * {@link #MOST_FADE} in a step);</li>
 * <li>drawn, the hills never rise over the sun (by day) or the moon (by night), the header's day and night button,
 * even where the skyline has gone and the horizon comes up to the header.</li>
 * </ul>
 *
 * Three small windows of the design (0.6.3) are checked whole as well: A, a split screen 411 by 236 dp beside Dasher;
 * B, 360 by 220 dp; C, A at twice the font. The pictures go to {@code app/build/reports/fluid-layout/}.
 * {@link FluidLayoutTest} checks the arithmetic itself at every window size a pixel apart.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FluidPageTest extends AndroidAdapterTestBase {
    /** The window heights swept, in dp. */
    private static final int FROM_DP = 220;
    private static final int TO_DP = 900;
    private static final int STEP_DP = 20;
    /** Pixels lost to rounding between two sizes, in dp: each edge is rounded to a pixel. */
    private static final float ROUNDING_DP = 3;
    /**
     * The most any part fades in or out in one step: the quickest fade (the road's, over its 78 dp, or the stage's
     * over its least of 116 dp in three columns) takes three steps or more.
     */
    private static final float MOST_FADE = 0.4f;
    /** The heights pictured (on Android 15). */
    private static final int[] PICTURED = {220, 300, 400, 500, 600, 700, 800, 900};
    private static final String PICTURES = "build/reports/fluid-layout";
    /** The tile's labels with the whole offer shown: the pay, how long ago, the miles and the minutes. */
    private static final String WHOLE_OFFER = "\\$7\\.90 · (now|\\d+m) · 7\\.2 mi · 21 min";

    private ServiceController<OfferFilterService> screen;
    private ServiceController<OfferNotificationService> listener;

    @After
    public void normalFont() {
        if (listener != null && OfferNotificationService.isConnected()) listener.destroy();
        if (screen != null) screen.destroy();
        OfferFilterService.sawDasherBeside(0);
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.forgetCache();
        RuntimeEnvironment.setFontScale(1f);
    }

    /** Screen reading and background offers both on, as during a dash: no setup step to do. */
    private void servicesUp() {
        screen = Robolectric.buildService(OfferFilterService.class).create();
        screen.get().onServiceConnected();
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        settle();
    }

    // ---- A narrow phone: 360 dp wide ----

    @Test @Config(qualifiers = "w360dp-h220dp-xhdpi")
    public void aNarrowSplitScreenAtTheNormalFont() throws Exception {
        sweeps("narrow-split-font1", 360, true, 1f, 380, 280);
    }

    @Test @Config(qualifiers = "w360dp-h220dp-xhdpi")
    public void aNarrowFullScreenAtTheNormalFont() throws Exception {
        sweeps("narrow-full-font1", 360, false, 1f, 400, 300);
    }

    @Test @Config(qualifiers = "w360dp-h220dp-xhdpi")
    public void aNarrowSplitScreenAtTwiceTheFont() throws Exception {
        sweeps("narrow-split-font2", 360, true, 2f, 480, 300);
    }

    @Test @Config(qualifiers = "w360dp-h220dp-xhdpi")
    public void aNarrowFullScreenAtTwiceTheFont() throws Exception {
        sweeps("narrow-full-font2", 360, false, 2f, 480, 300);
    }

    // ---- A wide window: 840 dp, an unfolded phone or a tablet ----

    @Test @Config(qualifiers = "w840dp-h220dp-xhdpi")
    public void aWideSplitScreenAtTheNormalFont() throws Exception {
        sweeps("wide-split-font1", 840, true, 1f, 320, 240);
    }

    @Test @Config(qualifiers = "w840dp-h220dp-xhdpi")
    public void aWideFullScreenAtTheNormalFont() throws Exception {
        sweeps("wide-full-font1", 840, false, 1f, 320, 240);
    }

    @Test @Config(qualifiers = "w840dp-h220dp-xhdpi")
    public void aWideSplitScreenAtTwiceTheFont() throws Exception {
        sweeps("wide-split-font2", 840, true, 2f, 320, 240);
    }

    @Test @Config(qualifiers = "w840dp-h220dp-xhdpi")
    public void aWideFullScreenAtTwiceTheFont() throws Exception {
        sweeps("wide-full-font2", 840, false, 2f, 320, 240);
    }

    /**
     * The page {@code widthDp} wide, in split screen or not, at {@code fontScale}, swept twice: before setup is done
     * (a step still to do under the band), the radar, the skyline and the map all wholly there from
     * {@code setupFromDp}; then during a dash (everything set up, Autopilot on), all wholly there from
     * {@code dashFromDp}. The steps to do are lines of their own under the band (48 dp each, more where their words
     * wrap at twice the font in a narrow window), so the stage needs that much more of the window before setup is done.
     */
    private void sweeps(String name, int widthDp, boolean split, float fontScale, int setupFromDp, int dashFromDp)
            throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        AutopilotRuntime.executorForTests = Runnable::run;
        // Split screen by night (a dash after dark), the whole screen by day: both skies pictured.
        Appearance.choose(app, split ? Appearance.Mode.NIGHT : Appearance.Mode.DAY);
        dasherInstalled();
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 0));
        DecisionLog.Entry latest = declinedEntry();
        DecisionLog.record(app, latest);
        sweep(name + "-setup", widthDp, split, fontScale, latest, true, setupFromDp);
        servicesUp();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        sweep(name + "-dash", widthDp, split, fontScale, latest, false, dashFromDp);
    }

    /** One part of the page where it stands, in the page's pixels, and how much of it is there. */
    private static final class Part {
        final RectF box;
        final float shown;

        Part(RectF box, float shown) {
            this.box = box;
            this.shown = shown;
        }
    }

    /**
     * The page {@code widthDp} wide, in split screen or not, at {@code fontScale}, resized from {@link #FROM_DP} to
     * {@link #TO_DP}, {@code latest} the latest offer and a setup step still to do or not: the radar, the skyline and
     * the map all wholly there from {@code bothFromDp}.
     */
    private void sweep(String name, int widthDp, boolean split, float fontScale, DecisionLog.Entry latest,
                       boolean stepToDo, int bothFromDp) throws Exception {
        RuntimeEnvironment.setQualifiers("+w" + widthDp + "dp-h" + FROM_DP + "dp");
        RuntimeEnvironment.setFontScale(fontScale);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        if (split) Shadows.shadowOf(controller.get()).setInMultiWindowMode(true);
        controller.setup();
        MainActivity activity = controller.get();
        View content = activity.findViewById(android.R.id.content);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
        Ui ui = new Ui(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        ScenePage page = find(content, ScenePage.class);
        VerdictTile tile = verdictTile(content);
        AutopilotChip.Row row = chipRow(content);
        AutopilotChip chip = find(row, AutopilotChip.class);
        TextView status = statusLine(content);
        View header = pageHeader(content);
        FilterHeroView hero = find(page, FilterHeroView.class);
        MinimumsStarView star = find(page, MinimumsStarView.class);
        DecisionChartView chart = find(page, DecisionChartView.class);
        AreaMapView map = find(page, AreaMapView.class);
        assertSame("the latest offer's tile is the page's first part, as screen readers reach it", tile,
                page.getChildAt(0));
        assertSame("then the chips' row, the status words its last", row, page.getChildAt(1));
        assertSame(status, row.getChildAt(row.getChildCount() - 1));
        String said = MainActivity.verdict(latest, System.currentTimeMillis());
        assertTrue(said, said.startsWith("$7.90 · 7.2 mi · 21 min · Declined"));
        assertEquals("screen readers hear the latest offer's verdict from its tile, with its reason and its tap",
                said + ". " + MainActivity.verdictReason(latest) + ". " + VerdictTile.ACTION,
                String.valueOf(tile.getContentDescription()));
        // The header's buttons for this window: in split screen beside another app, Navigate, Split, the sun and
        // Settings; full screen, Open Dasher as well.
        List<View> buttons = headerButtons(header);
        assertEquals(name + ": the header's buttons", split ? 4 : 5, buttons.size());

        Map<String, Part> before = null;
        Map<String, RectF> band = null;
        boolean sawBesides = false;
        for (int heightDp = FROM_DP; heightDp <= TO_DP; heightDp += STEP_DP) {
            resize(controller, widthDp, heightDp, fontScale);
            assertSame("a resize lays the page out again, and only that: the screen is not made again", activity,
                    controller.get());
            int width = Math.round(widthDp * density);
            int height = Math.round(heightDp * density);
            int[] at = new int[2];
            content.getLocationInWindow(at);
            assertEquals("the page has the whole window", 0, at[1]);
            assertEquals("the page has the whole window", width, content.getWidth());
            assertEquals("the page has the whole window", height, content.getHeight());
            String where = name + " at " + heightDp + " dp";
            View scroller = (View) page.getParent();
            View focused = activity.getCurrentFocus();
            assertEquals(where + ": the page at its top (focus " + focused + ")", 0, scroller.getScrollY());
            Map<String, Part> now = parts(page, hero);

            // The band goes by the window's width alone: at every height its parts stand where they stood.
            Map<String, RectF> bandNow = new LinkedHashMap<>();
            for (String part : new String[] {"mascot", "tile", "counts", "chips", "header"}) {
                bandNow.put(part, now.get(part).box);
            }
            if (band == null) band = bandNow;
            else assertEquals(where + ": the band goes by the window's width alone", band, bandNow);

            // The latest offer's tile, wholly on screen at the top, at its one height: the pay never cut, the whole
            // offer where the tile has the room for it.
            assertTrue(where, tile.isShown());
            assertOnScreen(where + ": the tile", tile, height);
            assertEquals(where + ": the tile's one height", VerdictTile.heightFor(activity), tile.getHeight());
            String shown = tile.shownText();
            assertTrue(where + ": the pay never cut: " + shown, shown.startsWith("$7.90"));
            if (tile.getWidth() >= tile.templateWidth()) {
                assertTrue(where + ": the whole offer where the tile has its room: " + shown, shown.matches(WHOLE_OFFER));
            }
            // The band's targets: the mascot, the tile, Autopilot's chip and the header's buttons (0.6.6: the counts
            // are the chart's key, the mode the radar's middle word),
            // each wholly there and a whole target; on screen wherever the band fits the window, else a scroll away.
            boolean bandFits = now.get("header").box.bottom <= height && now.get("chips").box.bottom <= height;
            List<View> targets = new ArrayList<>();
            targets.add(hero.mascotControl());
            targets.add(tile);
            targets.add(chip);
            targets.addAll(buttons);
            for (View part : targets) {
                assertTrue(where + ": " + part, part.isShown() && whole(part, page) && part.isClickable());
                assertTrue(where + ": a whole target " + part + " " + part.getWidth() + "x" + part.getHeight(),
                        part.getWidth() >= ui.dp(48) - 1 && part.getHeight() >= ui.dp(48) - 1);
                if (bandFits) assertOnScreen(where + ": " + part, part, height);
            }
            assertEquals(where + ": Autopilot's chip never cut", 0, chip.getLayout().getEllipsisCount(0));
            // The status words, after the chips in their row, laid out inside their height: cut to the room the band
            // leaves them, an ellipsis at their end, screen readers hearing them whole; a tap only as a whole target.
            RectF words = box(status, page);
            RectF chips = now.get("chips").box;
            assertTrue(where + ": the status words in the chips' row " + words + " / " + chips,
                    words.left >= chips.left - 1 && words.right <= chips.right + 1 && words.top >= chips.top - 1
                            && words.bottom <= chips.bottom + 1 && words.left >= box(chip, page).right - 1);
            assertInsideItsHeight(where + ": the status words", status);
            if (stepToDo) {
                // Auto-decline on, Autopilot off: the awake mascot says it all, so no words, no tap.
                assertEquals(where + ": no status words", "", status.getText().toString());
                assertFalse(where + ": no tap on no words", status.isClickable());
            } else {
                AutopilotText.Status autopilot = AutopilotRuntime.status(app, System.currentTimeMillis(),
                        OfferFilterService.isConnected());
                assertEquals(where + ": the status words are Autopilot's", AutopilotText.statusLine(autopilot),
                        status.getText().toString());
                assertEquals(where + ": heard whole", AutopilotText.chipDescription(autopilot),
                        String.valueOf(status.getContentDescription()));
                assertEquals(where + ": a tap only where they are a whole target",
                        status.getWidth() >= ui.dp(48), status.isClickable());
            }
            // The setup step still to do, under the band, never gone.
            TextView step = shownTextContaining(page, "Accessibility");
            if (stepToDo) {
                assertNotNull(where + ": the setup step still to do", step);
                assertWhole(where + ": the setup step", step);
                assertTrue(where + ": under the band", box(step, page).top >= now.get("header").box.bottom - 1);
            } else {
                assertNull(where + ": nothing to set up during a dash", step);
            }

            // The radar, the skyline and the map, all wholly there and on screen at a usable size, from the stated
            // height up.
            if (heightDp >= bothFromDp) {
                assertEquals(where + ": the radar wholly there", 1f, page.shown(star), 0f);
                assertEquals(where + ": the skyline wholly there", 1f, page.shown(chart), 0f);
                assertEquals(where + ": the map wholly there", 1f, page.shown(map), 0f);
                assertTrue(where + ": the radar's knobs work", star.skyRadius() >= ui.dp(FluidLayout.RADIUS_LEAST_DP) - 1);
                assertTrue(where + ": the skyline reads", chart.getHeight() >= ui.dp(FluidLayout.CHART_LEAST_DP) - 1
                        && chart.getWidth() >= ui.dp(FluidLayout.COLUMN_LEAST_DP) - 1);
                assertTrue(where + ": the map reads", map.getHeight() >= ui.dp(mapLeast(page)) - 1
                        && map.getWidth() >= ui.dp(FluidLayout.COLUMN_LEAST_DP) - 1);
                assertTrue(where + ": all on screen", star.getBottom() <= height && chart.getBottom() <= height
                        && map.getBottom() <= height);
                assertTrue(where + ": the radar's knobs heard", star.getImportantForAccessibility()
                        != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                RectF radar = now.get("radar").box;
                RectF ground = now.get("map").box;
                boolean besides = radar.right <= ground.left + 1;
                sawBesides |= besides;
                if (heightDp == bothFromDp) assertTrue(where + ": side by side in a short window", besides);
            }
            if (heightDp == TO_DP && widthDp < 400 && !stepToDo && fontScale <= 1f) {
                RectF radar = now.get("radar").box;
                assertTrue(where + ": one above the other in a tall narrow window", radar.width() >= width - 1
                        && radar.bottom <= now.get("caption").box.top + 1);
            }

            // Targets, overlaps, cut words and the page's edges.
            assertTargets(where, page, ui);
            assertStarTargets(where, page, star, ui);
            assertApart(where, now);
            assertWords(where, page, status);
            for (Map.Entry<String, Part> part : now.entrySet()) {
                RectF box = part.getValue().box;
                assertTrue(where + ": " + part.getKey() + " inside the page " + box, box.left >= -1
                        && box.right <= width + 1 && box.top >= -1 && box.bottom <= page.getHeight() + 1);
            }
            // The page fills the window, scrolling only when what never goes needs more (everything else gone).
            if (page.getHeight() > height) {
                assertEquals(where + ": a page taller than its window has nothing on its way out", 0f,
                        page.shown(star), 0f);
            } else {
                assertEquals(where, height, page.getHeight());
            }

            // Nothing jumps, nothing pops in or out.
            if (before != null) {
                float most = (FluidLayout.TURN_RATE * STEP_DP + ROUNDING_DP) * density;
                for (Map.Entry<String, Part> part : now.entrySet()) {
                    Part was = before.get(part.getKey());
                    RectF a = was.box;
                    RectF b = part.getValue().box;
                    float moved = Math.max(Math.max(Math.abs(a.left - b.left), Math.abs(a.top - b.top)),
                            Math.max(Math.abs(a.right - b.right), Math.abs(a.bottom - b.bottom)));
                    assertTrue(where + ": " + part.getKey() + " jumped " + moved + " px, from " + a + " to " + b,
                            moved <= most);
                    assertTrue(where + ": " + part.getKey() + " popped from " + was.shown + " to "
                            + part.getValue().shown, Math.abs(was.shown - part.getValue().shown) <= MOST_FADE);
                }
            }
            before = now;

            // Drawn: the hills never rise over the sun (or moon) up by the header's buttons, whose button it is, even
            // where the skyline has gone and the horizon comes up to the header.
            Bitmap drawn = drawn(content);
            assertTrue(where + ": the hills under the sun, " + page.hillsTop() + " / " + page.sunBottom(),
                    page.sunBottom() > 0 && page.hillsTop() >= page.sunBottom() - 1);
            if (android.os.Build.VERSION.SDK_INT == 35 && contains(PICTURED, heightDp)) {
                picture(drawn, name + "-" + heightDp + "dp");
            }
            drawn.recycle();
        }
        assertTrue(name + ": side by side somewhere", sawBesides || widthDp < 400);
        controller.pause().stop().destroy();
    }

    // ---- Three small windows, whole ----

    /** A: half of a phone's split screen beside Dasher, 411 by 236 dp, during a dash. */
    @Test @Config(qualifiers = "w411dp-h236dp-420dpi")
    public void aShortSplitBesideDasherKeepsEveryPictureAndEveryControl() throws Exception {
        smallWindow("A 411x236", 1f);
    }

    /** B: a narrower and shorter half, 360 by 220 dp, beside Dasher during a dash. */
    @Test @Config(qualifiers = "w360dp-h220dp-420dpi")
    public void theSmallestSplitBesideDasherKeepsEveryPictureAndEveryControl() throws Exception {
        smallWindow("B 360x220", 1f);
    }

    /** C: A at twice the font. */
    @Test @Config(qualifiers = "w411dp-h236dp-420dpi")
    public void aShortSplitBesideDasherAtTwiceTheFontKeepsEveryPictureAndEveryControl() throws Exception {
        smallWindow("C 411x236 font 2", 2f);
    }

    /**
     * A small half of a split screen beside Dasher during a dash (Autopilot on, max stops set, nothing to set up), as
     * the window gives it: the band holds the mascot, the tile and the counts, then the chips, the status words and the
     * header's three buttons (beside Dasher, Split and Open Dasher go); the stage under it stands in three columns,
     * the radar, the skyline and the map, all wholly there. Every knob, the max stops badge and the three counts are
     * whole targets inside their parts, apart from one another, and Navigate is a whole target on screen. The Learn
     * button keeps its room rule: on a circle under 74 dp it is not there, and learning stays as set.
     */
    private void smallWindow(String where, float fontScale) throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        AutopilotRuntime.executorForTests = Runnable::run;
        Appearance.choose(app, Appearance.Mode.NIGHT);
        dasherInstalled();
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        DecisionLog.record(app, declinedEntry());
        servicesUp();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(controller.get()).setInMultiWindowMode(true);
        controller.setup();
        MainActivity activity = controller.get();
        View content = activity.findViewById(android.R.id.content);
        settleSky(content);
        Ui ui = new Ui(activity);
        int height = content.getResources().getDisplayMetrics().heightPixels;
        ScenePage page = find(content, ScenePage.class);
        FilterHeroView hero = find(page, FilterHeroView.class);
        MinimumsStarView star = find(page, MinimumsStarView.class);
        DecisionChartView chart = find(page, DecisionChartView.class);
        AreaMapView map = find(page, AreaMapView.class);
        View caption = page.getChildAt(6);
        assertNull(where + ": nothing to set up", shownTextContaining(page, SetupChecklist.ACCESSIBILITY));
        List<View> buttons = headerButtons(pageHeader(content));
        assertEquals(where + ": beside Dasher, Navigate, the sun and Settings", 3, buttons.size());
        assertEquals(where + ": the page fits the window", height, page.getHeight());

        // Every picture wholly there, in three columns: the radar, the skyline and the map.
        assertEquals(where + ": the radar wholly there", 1f, page.shown(star), 0f);
        assertEquals(where + ": the skyline's caption wholly there", 1f, page.shown(caption), 0f);
        assertEquals(where + ": the skyline wholly there", 1f, page.shown(chart), 0f);
        assertEquals(where + ": the map wholly there", 1f, page.shown(map), 0f);
        assertEquals(where + ": no counts in the band (0.6.6: the chart's key)", 0f, hero.countsShown(), 0f);
        assertEquals(where + ": three columns", 1f, page.inner(), 0f);
        assertEquals(where + ": the radar beside the ground", 1f, page.turn(), 0f);
        RectF radarBox = box(star, page);
        RectF chartBox = box(chart, page);
        RectF mapBox = box(map, page);
        assertTrue(where + ": the radar, the skyline and the map, left to right " + radarBox + " " + chartBox + " "
                + mapBox, radarBox.right <= chartBox.left + 1 && chartBox.right <= mapBox.left + 1
                && box(caption, page).right <= mapBox.left + 1);
        assertTrue(where + ": the radar's knobs work", star.skyRadius() >= ui.dp(FluidLayout.RADIUS_LEAST_DP) - 1);
        assertTrue(where + ": the skyline reads", chart.getHeight() >= ui.dp(FluidLayout.CHART_LEAST_DP) - 1
                && chart.getWidth() >= ui.dp(FluidLayout.COLUMN_LEAST_DP) - 1);
        assertTrue(where + ": the map reads", map.getHeight() >= ui.dp(mapLeast(page)) - 1
                && map.getWidth() >= ui.dp(FluidLayout.COLUMN_LEAST_DP) - 1);
        assertTrue(where + ": all on screen", star.getBottom() <= height && chart.getBottom() <= height
                && map.getBottom() <= height);

        // Every knob and the max stops badge, a whole target inside the radar, apart from one another.
        List<Rect> radarTargets = new ArrayList<>();
        List<String> radarNames = new ArrayList<>();
        for (int axis : MinimumsStarView.SPOKES) {
            assertNotNull(where + ": knob " + axis, star.knobAt(axis));
            radarTargets.add(starTarget(where, star, axis, ui));
            radarNames.add("knob " + axis);
        }
        assertNotNull(where + ": the max stops badge", star.stopsBox());
        radarTargets.add(starTarget(where, star, MinimumsStarView.STOPS_ID, ui));
        radarNames.add("the max stops badge");
        List<Integer> radarIds = new ArrayList<>();
        for (int axis : MinimumsStarView.SPOKES) radarIds.add(axis);
        radarIds.add(MinimumsStarView.STOPS_ID);
        assertApartOrNearestWins(where, star, ui, radarNames, radarIds, radarTargets);
        if (star.skyRadius() < ui.dp(74)) {
            assertNull(where + ": no Learn button on a circle under 74 dp", star.learnBox());
            assertNull(where + ": nor its node", node(star, MinimumsStarView.SCORE_ID));
        }

        // The three counts, the chart's key under its bars (0.6.6), each inside the chart and apart from one another,
        // and each an action screen readers reach on the chart.
        List<Rect> countTargets = new ArrayList<>();
        List<String> countNames = new ArrayList<>();
        for (DecisionLog.Tally tally : DecisionLog.Tally.values()) {
            RectF key = chart.keyBox(tally);
            assertFalse(where + ": the " + tally + " count drawn", key.isEmpty());
            assertTrue(where + ": the " + tally + " count inside the chart " + key, key.left >= -1
                    && key.right <= chart.getWidth() + 1 && key.bottom <= chart.getHeight() + 1);
            countTargets.add(new Rect(Math.round(key.left), Math.round(key.top), Math.round(key.right),
                    Math.round(key.bottom)));
            countNames.add("the " + tally + " count");
        }
        assertDisjoint(where, countNames, countTargets);

        // Navigate, a whole target on screen, clear of the band's other parts.
        View navigate = iconButton(content, "Navigate");
        assertTrue(where + ": Navigate", navigate.isShown() && navigate.isEnabled() && navigate.isClickable());
        assertTrue(where + ": Navigate a whole target", navigate.getWidth() >= ui.dp(48) - 1
                && navigate.getHeight() >= ui.dp(48) - 1);
        assertOnScreen(where + ": Navigate", navigate, height);
        RectF navigateBox = box(navigate, page);
        for (View other : new View[] {verdictTile(content), chipRow(content), hero.mascotControl()}) {
            assertFalse(where + ": Navigate clear of " + other, RectF.intersects(navigateBox, box(other, page)));
        }

        // And the page as a whole: targets, overlaps, cut words.
        assertTargets(where, page, ui);
        assertStarTargets(where, page, star, ui);
        assertApart(where, parts(page, hero));
        assertWords(where, page, statusLine(content));
        controller.pause().stop().destroy();
    }

    /** The node {@code id} of the radar, a whole target wholly inside it: its bounds in the radar's pixels. */
    private static Rect starTarget(String where, MinimumsStarView star, int id, Ui ui) {
        android.view.accessibility.AccessibilityNodeInfo info = node(star, id);
        assertNotNull(where + ": the radar's node " + id, info);
        Rect bounds = new Rect();
        info.getBoundsInParent(bounds);
        assertTrue(where + ": " + info.getContentDescription() + " a whole target " + bounds,
                bounds.width() >= ui.dp(48) - 1 && bounds.height() >= ui.dp(48) - 1);
        assertTrue(where + ": " + info.getContentDescription() + " inside the radar " + bounds + " in "
                + star.getWidth() + "x" + star.getHeight(), bounds.left >= -1 && bounds.top >= -1
                && bounds.right <= star.getWidth() + 1 && bounds.bottom <= star.getHeight() + 1);
        return bounds;
    }

    /**
     * The radar's targets apart from one another; where the minimums set put two knobs nearer than a target's width
     * (where a knob stands goes by its minimum, so no layout keeps every pair apart), the radar gives a touch to the
     * nearest: a touch at each one's middle is its own, and they share at most a quarter of a target.
     */
    private static void assertApartOrNearestWins(String where, MinimumsStarView star, Ui ui, List<String> names,
                                                 List<Integer> ids, List<Rect> targets) throws Exception {
        java.lang.reflect.Method target = MinimumsStarView.class.getDeclaredMethod("target", float.class, float.class);
        target.setAccessible(true);
        for (int i = 0; i < targets.size(); i++) {
            for (int j = i + 1; j < targets.size(); j++) {
                Rect a = targets.get(i);
                Rect b = targets.get(j);
                int across = Math.min(a.right, b.right) - Math.max(a.left, b.left);
                int down = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                if (across <= 1 || down <= 1) continue;
                String pair = where + ": " + names.get(i) + " " + a + " and " + names.get(j) + " " + b;
                assertTrue(pair + " share at most a quarter of a target", Math.min(across, down) <= ui.dp(12));
                assertEquals(pair + ": a touch at the first's middle is its own", ids.get(i),
                        target.invoke(star, a.exactCenterX(), a.exactCenterY()));
                assertEquals(pair + ": a touch at the second's middle is its own", ids.get(j),
                        target.invoke(star, b.exactCenterX(), b.exactCenterY()));
            }
        }
    }

    /** No two of {@code targets} overlap by more than a pixel's rounding. */
    private static void assertDisjoint(String where, List<String> names, List<Rect> targets) {
        for (int i = 0; i < targets.size(); i++) {
            for (int j = i + 1; j < targets.size(); j++) {
                Rect a = targets.get(i);
                Rect b = targets.get(j);
                int across = Math.min(a.right, b.right) - Math.max(a.left, b.left);
                int down = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                assertFalse(where + ": " + names.get(i) + " " + a + " overlaps " + names.get(j) + " " + b,
                        across > 1 && down > 1);
            }
        }
    }

    /**
     * The least the map is wholly there at, in dp: in three columns its column is the stage's least over the line
     * naming its area, the whole of it while that line is not shown.
     */
    private static float mapLeast(ScenePage page) {
        View area = page.getChildAt(9);
        return area.getVisibility() == View.GONE ? FluidLayout.TUCKED_DP : FluidLayout.MAP_TUCKED_DP;
    }

    /** The header's round buttons shown now, in order. */
    private static List<View> headerButtons(View header) {
        List<View> buttons = new ArrayList<>();
        ViewGroup row = (ViewGroup) header;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.isClickable()) buttons.add(child);
        }
        return buttons;
    }

    // ---- The minutes passing ----

    /**
     * A split screen beside another app during a dash, at twice the font: as the minutes pass after the latest offer
     * ("now", "1m", "12m", "59m", "1h" on its tile; "just now", "1 min ago", "12 min ago", "59 min ago", "1 h ago" for
     * screen readers) the tile at the top keeps its size, its place and its pay's size, and shows its age at every age
     * or at none, so nothing under it moves (0.5.1's words in its place: at a minute the verdict grew a third line, its
     * words a quarter larger, and the radar under it shrank away). The same offer at each age, the same window.
     */
    @Test @Config(qualifiers = "w411dp-h370dp-420dpi")
    public void theMinutesPassingNeverMoveThePage() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        AutopilotRuntime.executorForTests = Runnable::run;
        dasherInstalled();
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        servicesUp();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        String[] agos = {"just now", "1 min ago", "12 min ago", "59 min ago", "1 h ago"};
        String[] ages = {"now", "1m", "12m", "59m", "1h"};
        long[] times = {0, 61_000, 12 * 60_000 + 5_000, 59 * 60_000 + 5_000, 61 * 60_000};
        RectF tileWas = null;
        Float paySize = null;
        Boolean ageShown = null;
        RectF radar = null;
        RectF chips = null;
        Float shown = null;
        for (int i = 0; i < times.length; i++) {
            DecisionLog.forgetCache();
            DecisionLog.clear(app);
            DecisionLog.Entry offer = declinedEntry();
            DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() - times[i], offer.source,
                    offer.addOn, offer.facts, offer.requiredCents, offer.result, offer.reason, offer.action,
                    offer.autoDecline, offer.evidence));
            ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
            Shadows.shadowOf(controller.get()).setInMultiWindowMode(true);
            controller.setup();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
            View content = controller.get().findViewById(android.R.id.content);
            layOut(content);
            ScenePage page = find(content, ScenePage.class);
            VerdictTile tile = verdictTile(content);
            MinimumsStarView star = find(page, MinimumsStarView.class);
            String said = String.valueOf(tile.getContentDescription());
            assertTrue(said, said.startsWith("$7.90 · 7.2 mi · 21 min · Declined · " + agos[i] + ". "));
            String labels = tile.shownText();
            assertTrue(said + ": the pay never cut: " + labels, labels.startsWith("$7.90"));
            if (tile.ageShown()) assertTrue(said + ": its age " + labels, labels.contains(" · " + ages[i]));
            if (tileWas == null) {
                tileWas = box(tile, page);
                paySize = tile.payTextSize();
                ageShown = tile.ageShown();
                radar = box(star, page);
                chips = box(chipRow(content), page);
                shown = page.shown(star);
            } else {
                assertEquals(said + ": the tile where it was, the same size", tileWas, box(tile, page));
                assertEquals(said + ": the pay the same size", paySize, tile.payTextSize(), 0.01f);
                assertEquals(said + ": the age at every age or at none", ageShown, tile.ageShown());
                assertEquals(said + ": the chips where they were", chips, box(chipRow(content), page));
                assertEquals(said + ": the radar where it was", radar, box(star, page));
                assertEquals(said + ": as much of it there", shown, page.shown(star), 0f);
            }
            controller.pause().stop().destroy();
        }
    }

    // ---- The radar's own parts, 2 dp at a time ----

    /** The window heights the radar's own parts are swept over, in dp, and the step. */
    private static final int PARTS_FROM_DP = 260;
    private static final int PARTS_STEP_DP = 2;
    /** The most any of the radar's own parts fades in or out in one 2 dp step. */
    private static final float PARTS_MOST_FADE = 0.5f;

    @Test @Config(qualifiers = "w411dp-h260dp-xhdpi")
    public void theRadarsOwnPartsBesideAnotherAppMoveOnlyWithItAndFadeRatherThanJump() throws Exception {
        radarParts(411, 1f);
    }

    @Test @Config(qualifiers = "w360dp-h260dp-xhdpi")
    public void theRadarsOwnPartsAtTwiceTheFontMoveOnlyWithItAndFadeRatherThanJump() throws Exception {
        radarParts(360, 2f);
    }

    /** The radar's circle and box, and its own parts with how much of each is there, in the page's pixels. */
    private static final class Radar {
        float x;
        float y;
        float radius;
        RectF box;
        final Map<String, RectF> parts = new LinkedHashMap<>();
        final Map<String, Float> shown = new LinkedHashMap<>();
    }

    /**
     * The radar's own parts (the Learn button, the max stops badge, the spokes' names and the knobs) as a split
     * screen beside another app grows from {@link #PARTS_FROM_DP} to {@link #TO_DP}, {@link #PARTS_STEP_DP} dp at a
     * time, during a dash with Autopilot on and max stops set (0.5.1's search put each where it found room, so the
     * button leapt across the circle, the badge and the names hopped from side to side and came and went as the circle
     * grew a few dp). Wherever the radar is wholly there: each part moves only with the circle, never more in a step
     * than the circle's own middle and radius and the radar's box carry it; it comes in or goes only by fading, at most
     * {@link #PARTS_MOST_FADE} of it in a step; the button is used (and heard) only while it is wholly there, clear of
     * every knob's reach; no part wholly there overlaps another or leaves the radar; and two names that overlap only
     * cross-fade, together never more than wholly one, so no words are seen one over another.
     */
    private void radarParts(int widthDp, float fontScale) throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        AutopilotRuntime.executorForTests = Runnable::run;
        Appearance.choose(app, Appearance.Mode.NIGHT);
        dasherInstalled();
        FilterStore.save(app, FilterSettings.of(true, 400, 100, 25, 3));
        DecisionLog.record(app, declinedEntry());
        servicesUp();
        AutopilotRuntime.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        RuntimeEnvironment.setQualifiers("+w" + widthDp + "dp-h" + PARTS_FROM_DP + "dp");
        RuntimeEnvironment.setFontScale(fontScale);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(controller.get()).setInMultiWindowMode(true);
        controller.setup();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
        MainActivity activity = controller.get();
        Ui ui = new Ui(activity);
        ScenePage page = find(activity.findViewById(android.R.id.content), ScenePage.class);
        MinimumsStarView star = find(page, MinimumsStarView.class);
        Radar before = null;
        Map<String, Integer> whollyAt = new LinkedHashMap<>();
        for (int heightDp = PARTS_FROM_DP; heightDp <= TO_DP; heightDp += PARTS_STEP_DP) {
            resize(controller, widthDp, heightDp, fontScale);
            String where = widthDp + " dp wide at " + heightDp + " dp, font " + fontScale;
            if (page.shown(star) < 1) {
                before = null;
                continue;
            }
            Radar now = radar(page, star);
            for (Map.Entry<String, Float> part : now.shown.entrySet()) {
                if (part.getValue() >= 1 && !whollyAt.containsKey(part.getKey())) {
                    whollyAt.put(part.getKey(), heightDp);
                }
            }
            assertRadarParts(where, now, star, ui);
            if (before != null) {
                // The most the circle's own middle and radius, and the radar's box, carry a part in this step.
                float most = 2 * (Math.abs(now.x - before.x) + Math.abs(now.y - before.y)
                        + Math.abs(now.radius - before.radius)) + Math.abs(now.box.top - before.box.top)
                        + Math.abs(now.box.bottom - before.box.bottom) + ui.dp(3);
                Set<String> names = new java.util.LinkedHashSet<>(before.shown.keySet());
                names.addAll(now.shown.keySet());
                for (String name : names) {
                    float was = before.shown.containsKey(name) ? before.shown.get(name) : 0;
                    float is = now.shown.containsKey(name) ? now.shown.get(name) : 0;
                    assertTrue(where + ": the " + name + " popped from " + was + " to " + is,
                            Math.abs(is - was) <= PARTS_MOST_FADE);
                    RectF a = before.parts.get(name);
                    RectF b = now.parts.get(name);
                    if (a == null || b == null) continue;
                    float moved = (float) Math.hypot(a.centerX() - b.centerX(), a.centerY() - b.centerY());
                    assertTrue(where + ": the " + name + " jumped " + moved + " px from " + a + " to " + b
                            + " (the circle's own move allows " + most + ")", moved <= most);
                }
            }
            before = now;
        }
        for (String part : new String[] {"button", "badge", "name 0", "name 1", "name 2", "name 3"}) {
            assertTrue(widthDp + " dp wide, font " + fontScale + ": the " + part + " wholly there somewhere",
                    whollyAt.containsKey(part));
        }
        controller.pause().stop().destroy();
    }

    /** Where the radar's own parts stand now, in the page's pixels, with how much of each is there. */
    private static Radar radar(ScenePage page, MinimumsStarView star) {
        Radar radar = new Radar();
        float left = star.getLeft();
        float top = star.getTop();
        radar.x = left + star.skyX();
        radar.y = top + star.skyY();
        radar.radius = star.skyRadius();
        radar.box = new RectF(left, top, star.getRight(), star.getBottom());
        RectF button = star.learnDrawn();
        if (button != null) {
            button.offset(left, top);
            radar.parts.put("button", button);
            radar.shown.put("button", star.learnAmount());
        }
        RectF badge = star.stopsBox();
        if (badge != null) {
            badge.offset(left, top);
            radar.parts.put("badge", badge);
            radar.shown.put("badge", 1f);
        }
        for (int axis = 0; axis <= AreaScore.STOP; axis++) {
            RectF name = star.axisLabelBox(axis);
            if (name == null) continue;
            name.offset(left, top);
            radar.parts.put("name " + axis, name);
            radar.shown.put("name " + axis, star.axisLabelShown(axis));
        }
        for (int axis : MinimumsStarView.SPOKES) {
            float[] knob = star.knobAt(axis);
            if (knob == null) continue;
            radar.parts.put("knob " + axis, new RectF(left + knob[0], top + knob[1], left + knob[0], top + knob[1]));
        }
        return radar;
    }

    /**
     * The radar's own parts: those wholly there inside the radar and apart from one another; two names only
     * cross-fading where they overlap, so no words are seen one over another; the button clear of every knob's reach
     * and used (and heard) only while wholly there; and the badge's whole 48 dp target inside the radar.
     */
    private static void assertRadarParts(String where, Radar radar, MinimumsStarView star, Ui ui) {
        List<String> names = new ArrayList<>(radar.shown.keySet());
        for (int i = 0; i < names.size(); i++) {
            RectF a = radar.parts.get(names.get(i));
            float aShown = radar.shown.get(names.get(i));
            assertTrue(where + ": the " + names.get(i) + " inside the radar " + a + " / " + radar.box, aShown < 1
                    || a.left >= radar.box.left - 1 && a.right <= radar.box.right + 1 && a.top >= radar.box.top - 1
                            && a.bottom <= radar.box.bottom + 1);
            for (int j = i + 1; j < names.size(); j++) {
                RectF b = radar.parts.get(names.get(j));
                float bShown = radar.shown.get(names.get(j));
                // Two names only cross-fade, together never more than wholly one; a name fading may go under the badge
                // or the button (drawn over it), never while both are wholly there.
                boolean words = names.get(i).startsWith("name") && names.get(j).startsWith("name");
                assertFalse(where + ": the " + names.get(i) + " " + a + " (" + aShown + " there) overlaps the "
                        + names.get(j) + " " + b + " (" + bShown + " there)", overlap(names.get(i), a, names.get(j), b)
                        && (words ? aShown + bShown > 1.01f : aShown >= 1 && bShown >= 1));
            }
        }
        boolean used = radar.shown.containsKey("button") && radar.shown.get("button") >= 1;
        android.view.accessibility.AccessibilityNodeInfo node = node(star, MinimumsStarView.SCORE_ID);
        assertEquals(where + ": the button used (and heard) only while wholly there", used, star.learnBox() != null);
        assertEquals(where + ": the button heard only while wholly there", used, node != null);
        if (used) {
            RectF button = radar.parts.get("button");
            for (Map.Entry<String, RectF> part : radar.parts.entrySet()) {
                if (!part.getKey().startsWith("knob")) continue;
                float apart = (float) Math.hypot(part.getValue().centerX() - button.centerX(),
                        part.getValue().centerY() - button.centerY());
                assertTrue(where + ": the button clear of the " + part.getKey() + "'s reach, " + apart + " px",
                        apart >= ui.dp(48) - 1);
            }
        }
        android.view.accessibility.AccessibilityNodeInfo badge = node(star, MinimumsStarView.STOPS_ID);
        assertNotNull(where + ": the badge heard", badge);
        Rect bounds = new Rect();
        badge.getBoundsInParent(bounds);
        assertTrue(where + ": the badge's whole target inside the radar " + bounds, bounds.top >= -1
                && bounds.bottom <= star.getHeight() + 1 && bounds.height() >= ui.dp(48) - 1);
    }

    /** Whether two of the radar's parts overlap: the button by its round shape as drawn, the rest by their boxes. */
    private static boolean overlap(String aName, RectF a, String bName, RectF b) {
        if (aName.equals("button") || bName.equals("button")) {
            RectF round = aName.equals("button") ? a : b;
            RectF other = round == a ? b : a;
            float dx = Math.max(0, Math.max(other.left - round.centerX(), round.centerX() - other.right));
            float dy = Math.max(0, Math.max(other.top - round.centerY(), round.centerY() - other.bottom));
            return Math.hypot(dx, dy) < round.width() / 2 - 1;
        }
        float across = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        float down = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        return across > 1 && down > 1;
    }

    private static boolean contains(int[] values, int value) {
        for (int each : values) if (each == value) return true;
        return false;
    }

    /**
     * The window resized as the divider resizes it: Android tells the screen its new size, then its window's, and lays
     * the window out again.
     */
    private void resize(ActivityController<MainActivity> controller, int widthDp, int heightDp, float fontScale)
            throws Exception {
        RuntimeEnvironment.setQualifiers("+w" + widthDp + "dp-h" + heightDp + "dp");
        RuntimeEnvironment.setFontScale(fontScale);
        controller.configurationChange();
        View decor = controller.get().getWindow().getDecorView();
        Object root = View.class.getMethod("getViewRootImpl").invoke(decor);
        org.robolectric.shadows.ShadowViewRootImpl window = org.robolectric.shadow.api.Shadow.extract(root);
        window.callDispatchResized();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * The page's parts, where they stand in its pixels, with how much of each is there: its children in their order
     * (the band's tile, chips' row and header, the lines, then the stage's and the road), and the mascot and the counts
     * of the mascot's drawing, which spans the band's first row, in its place.
     */
    private static Map<String, Part> parts(ScenePage page, FilterHeroView hero) {
        Map<String, Part> parts = new LinkedHashMap<>();
        String[] names = {"tile", "chips", "hero", "header", "lines", "radar", "caption", "chart", "map", "area",
                "road"};
        assertEquals("the page's parts", names.length, page.getChildCount());
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child == hero) continue;
            parts.put(names[i], new Part(box(child, page), page.shown(child)));
        }
        parts.put("mascot", new Part(box(hero.mascotControl(), page), 1));
        RectF counts = new RectF();
        hero.countsAt(counts);
        counts.offset(hero.getLeft(), hero.getTop());
        parts.put("counts", new Part(counts, hero.countsShown()));
        return parts;
    }

    /** Where {@code view} stands in {@code page}'s pixels. */
    private static RectF box(View view, View page) {
        float left = 0;
        float top = 0;
        for (View at = view; at != page; at = (View) at.getParent()) {
            left += at.getLeft();
            top += at.getTop();
        }
        return new RectF(left, top, left + view.getWidth(), top + view.getHeight());
    }

    /** Every word of {@code text} laid out, inside its own height. */
    private static void assertWhole(String where, TextView text) {
        Layout layout = text.getLayout();
        assertNotNull(where, layout);
        int lines = text.getLineCount();
        assertEquals(where + ": every word", text.getText().length(), layout.getLineEnd(lines - 1));
        assertEquals(where + ": none cut short", 0, layout.getEllipsisCount(lines - 1));
        assertInsideItsHeight(where, text);
    }

    /** {@code text}'s lines laid out inside its own height. */
    private static void assertInsideItsHeight(String where, TextView text) {
        Layout layout = text.getLayout();
        assertNotNull(where, layout);
        assertTrue(where + ": inside its height", layout.getHeight()
                <= text.getHeight() - text.getTotalPaddingTop() - text.getTotalPaddingBottom() + 1);
    }

    /** {@code view} wholly on screen, without scrolling. */
    private static void assertOnScreen(String where, View view, int windowHeight) {
        Rect visible = new Rect();
        assertTrue(where + " on screen", view.getGlobalVisibleRect(visible));
        assertEquals(where + " wholly on screen " + visible, view.getHeight(), visible.height());
        assertTrue(where + " in the window", visible.bottom <= windowHeight);
    }

    /** Whether {@code view} is drawn wholly (not on its way out), up to the page. */
    private static boolean whole(View view, ScenePage page) {
        for (View at = view; at != page; at = (View) at.getParent()) {
            if (at.getVisibility() != View.VISIBLE || at.getAlpha() < 1) return false;
            if (at.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                return false;
            }
        }
        return true;
    }

    /** Every view a finger can use, wholly there, is at least 48 dp each way. */
    private static void assertTargets(String where, ScenePage page, Ui ui) {
        List<View> targets = new ArrayList<>();
        collectTargets(page, targets);
        for (View target : targets) {
            if (target == page || !target.isShown() || !whole(target, page)) continue;
            assertTrue(where + ": a whole target " + target + " " + target.getWidth() + "x" + target.getHeight(),
                    target.getWidth() >= ui.dp(48) - 1 && target.getHeight() >= ui.dp(48) - 1);
        }
    }

    private static void collectTargets(View view, List<View> out) {
        if (view.isClickable() || view.isLongClickable()) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectTargets(group.getChildAt(i), out);
        }
    }

    /**
     * The radar's own targets while it is wholly there: its knobs, its Learn button and the max stops badge, each at
     * least 48 dp each way inside the radar.
     */
    private static void assertStarTargets(String where, ScenePage page, MinimumsStarView star, Ui ui) {
        if (page.shown(star) < 1 || star.getHeight() <= 0) return;
        for (int id = 0; id <= MinimumsStarView.STOPS_ID; id++) {
            android.view.accessibility.AccessibilityNodeInfo info = node(star, id);
            if (info == null || !info.isVisibleToUser()) continue;
            Rect bounds = new Rect();
            info.getBoundsInParent(bounds);
            assertTrue(bounds.intersect(0, 0, star.getWidth(), star.getHeight()));
            assertTrue(where + ": a whole target on the radar, " + info.getContentDescription() + " " + bounds,
                    bounds.width() >= ui.dp(48) - 1 && bounds.height() >= ui.dp(48) - 1);
        }
    }

    /** No two parts overlap (the counts and the mascot as drawn; parts with no room left out). */
    private static void assertApart(String where, Map<String, Part> parts) {
        List<String> names = new ArrayList<>(parts.keySet());
        for (int i = 0; i < names.size(); i++) {
            RectF a = parts.get(names.get(i)).box;
            if (a.width() < 1 || a.height() < 1 || parts.get(names.get(i)).shown <= 0) continue;
            for (int j = i + 1; j < names.size(); j++) {
                RectF b = parts.get(names.get(j)).box;
                if (b.width() < 1 || b.height() < 1 || parts.get(names.get(j)).shown <= 0) continue;
                float across = Math.min(a.right, b.right) - Math.max(a.left, b.left);
                float down = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
                assertFalse(where + ": " + names.get(i) + " " + a + " overlaps " + names.get(j) + " " + b,
                        across > 1 && down > 1);
            }
        }
    }

    /**
     * No words cut off: each line of words wholly there is laid out inside its height, every word of it. The one
     * exception is {@code cut}, the status words: the band gives them only the room the chips and the buttons leave
     * (0.6.3), laid out inside their height but cut short there with an ellipsis, screen readers hearing them whole.
     */
    private static void assertWords(String where, ScenePage page, TextView cut) {
        List<TextView> texts = new ArrayList<>();
        collectTexts(page, texts);
        for (TextView text : texts) {
            if (!text.isShown() || !whole(text, page) || text.getLayout() == null || text.length() == 0) continue;
            Layout layout = text.getLayout();
            int lines = text.getLineCount();
            String what = where + ": \"" + text.getText() + "\"";
            assertTrue(what + " inside its height", layout.getHeight()
                    <= text.getHeight() - text.getTotalPaddingTop() - text.getTotalPaddingBottom() + 1);
            if (text == cut) continue;
            // Every word, none cut short with a mark: the skyline's caption takes a second line before it would cut
            // its outcome off in a narrow column at a large font.
            assertEquals(what + ": every word", text.getText().length(), layout.getLineEnd(lines - 1));
            assertEquals(what + ": none cut short", 0, layout.getEllipsisCount(lines - 1));
        }
    }

    private static void collectTexts(View view, List<TextView> out) {
        if (view instanceof TextView) out.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectTexts(group.getChildAt(i), out);
        }
    }

    /** The window as drawn. */
    private Bitmap drawn(View content) {
        Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(new Ui(app).page);
        content.draw(canvas);
        return bitmap;
    }

    /** The window as drawn, into {@link #PICTURES}. */
    private static void picture(Bitmap bitmap, String name) throws Exception {
        File directory = new File(PICTURES);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }
}
