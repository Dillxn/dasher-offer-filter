package com.local.dasherfilter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The homepage's one fluid layout on the real page (the owner, 7 October 2026, on 0.5.1's split screen: "I do not like
 * how it only shows the map or the radar"; "it should be gradual and seamless"). A window is resized from a sliver of
 * a split screen to a tall phone, {@link #STEP_DP} dp at a time, narrow and wide, in split screen and full screen, at
 * the normal font and twice it, the way dragging the divider resizes it: the page is laid out again and nothing else
 * happens (the screen is never made again, so the screen reader's work for Dasher never waits on it). At every size:
 *
 * <ul>
 * <li>the latest offer's verdict is wholly on screen at the top, every word (on one line where it fits), with the
 * mascot, Autopilot's chip and the status line in the strip with it, and the setup step still to do under the
 * header;</li>
 * <li>from a stated height up, the radar (the constellation with its knobs) and the offer map are both wholly there
 * and on screen at a usable size, side by side in the wide windows, one above the other in the tallest;</li>
 * <li>every target there is at least 48 dp each way, no two parts overlap, no words are cut off, and nothing stands
 * outside the page;</li>
 * <li>from one size to the next nothing jumps (no edge moves more than {@link FluidLayout#TURN_RATE} times the
 * window's change, give or take {@link #ROUNDING_DP}) and nothing pops in or out (no part fades by more than
 * {@link #MOST_FADE} in a step);</li>
 * <li>drawn, the hills never rise over the sun (by day) or the moon (by night), the header's day and night button,
 * even where the skyline has gone and the horizon comes up to the header.</li>
 * </ul>
 *
 * The pictures go to {@code app/build/reports/fluid-layout/}. {@link FluidLayoutTest} checks the arithmetic itself at
 * every window size a pixel apart.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
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
     * The most any part fades in or out in one step: the quickest fade (the counts', over their row's 66 dp, or the
     * skyline's over its 104 dp as the ground's column turns twice as fast as the window) takes three steps or more.
     */
    private static final float MOST_FADE = 0.4f;
    /** The heights pictured (on Android 15). */
    private static final int[] PICTURED = {220, 300, 400, 500, 600, 700, 800, 900};
    private static final String PICTURES = "build/reports/fluid-layout";

    private ServiceController<OfferFilterService> screen;
    private ServiceController<OfferNotificationService> listener;

    @After
    public void normalFont() {
        if (listener != null && OfferNotificationService.isConnected()) listener.destroy();
        if (screen != null) screen.destroy();
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
        sweeps("narrow-split-font1", 360, true, 1f, 400, 300);
    }

    @Test @Config(qualifiers = "w360dp-h220dp-xhdpi")
    public void aNarrowFullScreenAtTheNormalFont() throws Exception {
        sweeps("narrow-full-font1", 360, false, 1f, 400, 300);
    }

    @Test @Config(qualifiers = "w360dp-h220dp-xhdpi")
    public void aNarrowSplitScreenAtTwiceTheFont() throws Exception {
        sweeps("narrow-split-font2", 360, true, 2f, 620, 500);
    }

    @Test @Config(qualifiers = "w360dp-h220dp-xhdpi")
    public void aNarrowFullScreenAtTwiceTheFont() throws Exception {
        sweeps("narrow-full-font2", 360, false, 2f, 620, 500);
    }

    // ---- A wide window: 840 dp, an unfolded phone or a tablet ----

    @Test @Config(qualifiers = "w840dp-h220dp-xhdpi")
    public void aWideSplitScreenAtTheNormalFont() throws Exception {
        sweeps("wide-split-font1", 840, true, 1f, 380, 280);
    }

    @Test @Config(qualifiers = "w840dp-h220dp-xhdpi")
    public void aWideFullScreenAtTheNormalFont() throws Exception {
        sweeps("wide-full-font1", 840, false, 1f, 380, 280);
    }

    @Test @Config(qualifiers = "w840dp-h220dp-xhdpi")
    public void aWideSplitScreenAtTwiceTheFont() throws Exception {
        sweeps("wide-split-font2", 840, true, 2f, 460, 340);
    }

    @Test @Config(qualifiers = "w840dp-h220dp-xhdpi")
    public void aWideFullScreenAtTwiceTheFont() throws Exception {
        sweeps("wide-full-font2", 840, false, 2f, 460, 340);
    }

    /**
     * The page {@code widthDp} wide, in split screen or not, at {@code fontScale}, swept twice: before setup is done
     * (a step still to do under the header), the radar and the map both wholly there from {@code setupFromDp}; then
     * during a dash (everything set up, Autopilot on), both wholly there from {@code dashFromDp}.
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
     * {@link #TO_DP}, {@code latest} the latest offer and a setup step still to do or not: the radar and the map both
     * wholly there from {@code bothFromDp}.
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
        LinearLayout words = (LinearLayout) page.getChildAt(0);
        TextView verdict = (TextView) words.getChildAt(0);
        AutopilotChip.Row row = (AutopilotChip.Row) words.getChildAt(1);
        AutopilotChip chip = find(row, AutopilotChip.class);
        TextView status = (TextView) row.getChildAt(1);
        FilterHeroView hero = find(page, FilterHeroView.class);
        MinimumsStarView star = find(page, MinimumsStarView.class);
        AreaMapView map = find(page, AreaMapView.class);
        String said = MainActivity.verdict(latest, System.currentTimeMillis());
        assertEquals("the latest offer's verdict, in the app's words", said, verdict.getText().toString());
        assertTrue(said, said.startsWith("Declined $7.90 · 7.2 mi: "));

        Map<String, Part> before = null;
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

            // The verdict, wholly on screen at the top, every word; on one line where it fits.
            assertTrue(where, verdict.isShown());
            assertWhole(where + ": the verdict", verdict);
            assertOnScreen(where + ": the verdict", verdict, height);
            float oneLine = verdict.getPaint().measureText(said);
            if (oneLine <= verdict.getWidth() - verdict.getTotalPaddingLeft() - verdict.getTotalPaddingRight()) {
                assertEquals(where + ": one line where it fits", 1, verdict.getLineCount());
            }
            // The strip's other parts: the mascot, the chip and the status line, each a whole target; on screen with
            // the verdict wherever the strip fits the window, else a scroll away.
            boolean stripFits = words.getBottom() <= height;
            for (View part : new View[] {hero.mascotControl(), chip, status}) {
                assertTrue(where + ": " + part, part.isShown());
                assertTrue(where + ": a whole target " + part, part.getWidth() >= ui.dp(48) - 1
                        && part.getHeight() >= ui.dp(48) - 1);
                if (stripFits) assertOnScreen(where + ": " + part, part, height);
            }
            assertWhole(where + ": the status line", status);
            assertEquals(where + ": the chip on one line", 0, chip.getLayout().getEllipsisCount(0));
            // The setup step still to do, under the header, never gone.
            TextView step = shownTextContaining(page, "Accessibility");
            if (stepToDo) {
                assertNotNull(where + ": the setup step still to do", step);
                assertWhole(where + ": the setup step", step);
            } else {
                assertEquals(where + ": nothing to set up during a dash", null, step);
            }

            // The radar and the map, both wholly there and on screen at a usable size, from the stated height up.
            if (heightDp >= bothFromDp) {
                assertEquals(where + ": the radar wholly there", 1f, page.shown(star), 0f);
                assertEquals(where + ": the map wholly there", 1f, page.shown(map), 0f);
                assertTrue(where + ": the radar's knobs work", star.skyRadius() >= ui.dp(FluidLayout.RADIUS_LEAST_DP) - 1);
                assertTrue(where + ": the map reads", map.getHeight() >= ui.dp(FluidLayout.MAP_LEAST_DP) - 1
                        && map.getWidth() >= ui.dp(120));
                assertTrue(where + ": both on screen", star.getBottom() <= height && map.getBottom() <= height);
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
            assertWords(where, page);
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
            if (android.os.Build.VERSION.SDK_INT >= 35 && contains(PICTURED, heightDp)) {
                picture(drawn, name + "-" + heightDp + "dp");
            }
            drawn.recycle();
        }
        assertTrue(name + ": side by side somewhere", sawBesides || widthDp < 400);
        controller.pause().stop().destroy();
    }

    // ---- The minutes passing ----

    /**
     * A split screen beside another app during a dash, at twice the font: as the minutes pass after the latest offer
     * ("just now", "1 min ago", "12 min ago", "59 min ago", "1 h ago") the verdict at the top keeps its size and its
     * height, so nothing under it moves (0.5.1's words in its place: at a minute the verdict grew a third line, its words
     * a quarter larger, and the radar under it shrank away). The same offer at each age, the same window.
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
        long[] ages = {0, 61_000, 12 * 60_000 + 5_000, 59 * 60_000 + 5_000, 61 * 60_000};
        Float size = null;
        Integer height = null;
        RectF radar = null;
        Float shown = null;
        for (int i = 0; i < ages.length; i++) {
            DecisionLog.forgetCache();
            DecisionLog.clear(app);
            DecisionLog.Entry offer = declinedEntry();
            DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() - ages[i], offer.source,
                    offer.addOn, offer.facts, offer.requiredCents, offer.result, offer.reason, offer.action,
                    offer.autoDecline, offer.evidence));
            ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
            Shadows.shadowOf(controller.get()).setInMultiWindowMode(true);
            controller.setup();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
            View content = controller.get().findViewById(android.R.id.content);
            layOut(content);
            ScenePage page = find(content, ScenePage.class);
            TextView verdict = (TextView) ((LinearLayout) page.getChildAt(0)).getChildAt(0);
            MinimumsStarView star = find(page, MinimumsStarView.class);
            String said = verdict.getText().toString();
            assertTrue(said, said.startsWith("Declined $7.90 · 7.2 mi: ") && said.endsWith(" · " + agos[i]));
            assertWhole(said, verdict);
            if (size == null) {
                size = verdict.getTextSize();
                height = verdict.getHeight();
                radar = box(star, page);
                shown = page.shown(star);
            } else {
                assertEquals(said + ": the same size", size, verdict.getTextSize(), 0.01f);
                assertEquals(said + ": the same height", (int) height, verdict.getHeight());
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
     * The radar's own parts (the Autopilot button, the max stops badge, the spokes' names and the knobs) as a split
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
        RectF button = star.autopilotDrawn();
        if (button != null) {
            button.offset(left, top);
            radar.parts.put("button", button);
            radar.shown.put("button", star.autopilotAmount());
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
        assertEquals(where + ": the button used (and heard) only while wholly there", used, star.autopilotBox() != null);
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

    /** The page's parts, where they stand in its pixels, with how much of each is there. */
    private static Map<String, Part> parts(ScenePage page, FilterHeroView hero) {
        Map<String, Part> parts = new LinkedHashMap<>();
        String[] names = {"words", "hero", "header", "lines", "radar", "caption", "chart", "map", "area", "road"};
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
     * The radar's own targets while it is wholly there: its knobs, its Autopilot button and the max stops badge, each
     * at least 48 dp each way inside the radar.
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

    /** No words cut off: each line of words wholly there is laid out inside its height, every word of it. */
    private static void assertWords(String where, ScenePage page) {
        List<TextView> texts = new ArrayList<>();
        collectTexts(page, texts);
        for (TextView text : texts) {
            if (!text.isShown() || !whole(text, page) || text.getLayout() == null || text.length() == 0) continue;
            Layout layout = text.getLayout();
            int lines = text.getLineCount();
            String what = where + ": \"" + text.getText() + "\"";
            assertTrue(what + " inside its height", layout.getHeight()
                    <= text.getHeight() - text.getTotalPaddingTop() - text.getTotalPaddingBottom() + 1);
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
