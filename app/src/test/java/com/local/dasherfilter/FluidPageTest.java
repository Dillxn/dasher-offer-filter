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

    /** No words cut off: each line of words wholly there is laid out inside its height, every word or a mark. */
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
            if (text.getEllipsize() == null) {
                assertEquals(what + ": every word", text.getText().length(), layout.getLineEnd(lines - 1));
            }
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
