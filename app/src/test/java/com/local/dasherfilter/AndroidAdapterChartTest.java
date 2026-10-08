package com.local.dasherfilter;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.time.Duration;
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
 * The page's charts through real Android adapters: the constellation of the three minimums (pay, per mile, per hour)
 * with its knobs, the max stops badge and the Autopilot button, and the decisions chart.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AndroidAdapterChartTest extends AndroidAdapterTestBase {
    @Before public void plansOnThisThread() {
        AutopilotRuntime.forgetCache();
        AutopilotRuntime.executorForTests = Runnable::run;
    }

    @After public void plansOnTheirOwnThread() {
        AutopilotRuntime.executorForTests = null;
        AutopilotRuntime.forgetCache();
        RuntimeEnvironment.setFontScale(1f);
    }

    @Test
    public void tappingAChartColumnShowsWhatWasRead() {
        DecisionLog.record(app, declinedEntry());
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() + 60_000, DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(2500, 9.1, 30, 3), 2000, OfferRule.Result.KEEP, "meets your minimums",
                DecisionLog.Action.PASSES, true, Collections.emptyList()));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            DecisionChartView chart = findChart(content);
            assertEquals("the newest offer is shown first", Integer.valueOf(2500),
                    chart.selectedEntry().facts.payCents);
            assertNull(shownTextContaining(content, "Read: $7.90"));

            // The older offer is the second building from the right; tapping it opens its ticket.
            float density = app.getResources().getDisplayMetrics().density;
            float left = DecisionChartView.SIDE_DP * density;
            float slot = (chart.getWidth() - DecisionChartView.SIDE_DP * density - left) / DecisionChartView.SLOTS;
            float x = left + slot * (DecisionChartView.SLOTS - 1.5f);
            chart.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, 40, 0));
            chart.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x, 40, 0));

            assertEquals(Integer.valueOf(790), chart.selectedEntry().facts.payCents);
            assertNotNull(shownTextContaining(content, "Read: $7.90"));
        }
    }

    /** A knob set to {@code dollars} (dollars an hour for the per-hour knob) as a screen reader sets it, saved at once. */
    private static void setKnob(MinimumsStarView star, int axis, float dollars) {
        android.os.Bundle value = new android.os.Bundle();
        value.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, dollars);
        assertTrue(star.getAccessibilityNodeProvider().performAction(axis,
                android.R.id.accessibilityActionSetProgress, value));
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void theStarShowsTheSetMinimumsAndAutopilotsBarAsTheyAreSet() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.isShown());
            // What the example offer needs is heard first; the page shows no words for it. No offers yet, so the
            // example is a typical one: $7.00, $1.50 × 5 mi = $7.50 and 20 min at $18 an hour = $6.00.
            assertEquals("An offer like 20 min · 5 mi · 2 stops needs $7.50 at your minimums. Your minimums: pay "
                    + "$7.00, per mile $1.50, per hour of trip time 18 dollars per hour; at most 3 stops. Farther out "
                    + "means higher pay or pay rates. Solid blue is your minimums; while Autopilot's bar is not 100%, a "
                    + "dashed purple shape shows what it asks now; colored shapes are offers.",
                    star.getContentDescription().toString());
            assertEquals(700, star.setAsks(AreaScore.PAY), 0);
            assertEquals(750, star.setAsks(AreaScore.MILE), 0);
            assertEquals(600, star.setAsks(AreaScore.MINUTE), 0);
            assertTrue("no dashed shape at exactly the minimums", star.autopilotShape().isEmpty());

            // The per-hour knob is set in dollars an hour and kept in cents a minute: $24 an hour is 40¢ a minute.
            setKnob(star, AreaScore.MINUTE, 24f);
            assertEquals(40, FilterStore.load(app).perMinuteCents);
            assertEquals("20 min at $24 an hour", 800, star.setAsks(AreaScore.MINUTE), 0);
            assertTrue(star.getContentDescription().toString()
                    .startsWith("An offer like 20 min · 5 mi · 2 stops needs $8.00 at your minimums."));
            assertTrue(star.getContentDescription().toString().contains("per hour of trip time 24 dollars per hour"));
            setKnob(star, AreaScore.MILE, 0f);
            assertTrue(Double.isNaN(star.setAsks(AreaScore.MILE)));
            assertTrue(star.getContentDescription().toString().contains("per mile off"));

            // Autopilot moves its bar between offers: its dashed shape stands at that share of each minimum.
            FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
            assertTrue(FilterStore.commitAutopilotBar(app, 100, 80));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            settleSky(content);
            assertEquals("80% of $7.00", 560, star.barAsks(AreaScore.PAY), 0.001);
            assertEquals("80% of $8.00", 640, star.barAsks(AreaScore.MINUTE), 0.001);
            assertTrue("no minimum, no point", Double.isNaN(star.barAsks(AreaScore.MILE)));
            assertFalse("dashed", star.autopilotShape().isEmpty());
            assertArrayEquals("the bar never rewrites a minimum", new int[] {700, 0, 40, 0, 0, 0},
                    FilterStore.load(app).minimums());

            // Off again: back to exactly the minimums, and the dashed shape goes.
            AutopilotRuntime.setAutopilot(app, false, FilterSettings.GOAL_TOP_TIER);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            settleSky(content);
            assertTrue(Double.isNaN(star.barAsks(AreaScore.PAY)));
            assertTrue(star.autopilotShape().isEmpty());
            assertEquals(3, FilterStore.load(app).maxStops);
        }
    }

    @Test
    public void theStarMarksTheLatestOfferAndTheSkylineChoosesOthers() {
        FilterStore.save(app, FilterSettings.of(true, 700, 0, 0, 0));
        DecisionLog.record(app, declinedEntry());
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() + 60_000,
                DecisionLog.Source.SCREEN, false, new OfferSnapshot(2500, 9.1, 30, 3), 2000,
                OfferRule.Result.KEEP, "meets your minimums", DecisionLog.Action.PASSES, true,
                Collections.emptyList()).withScore(357));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            String description = star.getContentDescription().toString();
            assertTrue(description, description.contains("Your minimums: pay $7.00, per mile off, per hour of trip "
                    + "time off; no max stops."));
            assertTrue("$25.00 against $7.00: " + description,
                    description.contains("The newest offer scores 357% of your minimums."));
            assertTrue(description, description.endsWith("The constellation shows the latest or selected offer. "
                    + "Choose an older offer on the skyline."));
            DecisionChartView chart = findChart(content);
            assertTrue(chart.getContentDescription().toString().startsWith(
                    "Chart of the last 2 offers: 1 passed, 1 declined, 0 need review."));
            assertEquals(Integer.valueOf(2500), chart.selectedEntry().facts.payCents);
            chart.select(0);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("history stays selectable", Integer.valueOf(790), chart.selectedEntry().facts.payCents);
            assertTrue("the chosen offer is worked out against the minimums as they are now: $7.90 against $7.00",
                    star.getContentDescription().toString().contains("The chosen offer scores 112% of your minimums."));
        }
    }

    @Test
    public void acceptedOffersAreOutcomesOnTheirLinesAndNeverChangeTheMinimums() {
        // Nothing is learned from an acceptance any more (0.5.0): what the user accepted is a step on its line, its
        // outcome and tally follow, and the minimums stay exactly as set, high or low, paused or on.
        FilterSettings rules = FilterSettings.of(true, 1000, 0, 0, 0);
        FilterStore.save(app, rules);
        long now = System.currentTimeMillis();
        OfferSnapshot high = new OfferSnapshot(3000, 6.0, 24, 2);
        OfferSnapshot low = new OfferSnapshot(1420, 6.0, 24, 2);
        DecisionLog.record(app, kept(now - 60_000, high));
        assertTrue(DecisionLog.markStep(app, high, DecisionLog.StepKind.ACCEPTED,
                "you tapped Accept, then Dasher showed a delivery", 120_000));
        DecisionLog.Entry first = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(first));
        assertTrue(DecisionLog.accepted(first));
        assertArrayEquals("a high accepted pay raises nothing", rules.minimums(), FilterStore.load(app).minimums());

        FilterStore.save(app, rules.withEnabled(false));
        DecisionLog.record(app, kept(now, low));
        assertTrue(DecisionLog.markStep(app, low, DecisionLog.StepKind.ACCEPTED, "", 120_000));
        assertEquals(DecisionLog.Outcome.ACCEPTED, DecisionLog.outcome(DecisionLog.recent(app, 1).get(0)));
        FilterStore.save(app, FilterStore.load(app).withEnabled(true));
        FilterSettings saved = FilterStore.load(app);
        assertArrayEquals("nor does a lower one, or pausing between them", rules.minimums(), saved.minimums());
        assertEquals(rules.rulesKey(), saved.rulesKey());
        java.util.Map<String, ?> stored = app.getSharedPreferences("offer_filter", android.content.Context.MODE_PRIVATE)
                .getAll();
        for (String retired : FilterStore.RETIRED_KEYS) {
            assertFalse("no learned value is kept: " + retired, stored.containsKey(retired));
        }
    }

    @Test
    public void anOrderAcceptedWhileThePageIsOpenShowsOnTheStarWithoutWaitingForAnotherOffer() {
        FilterStore.save(app, FilterSettings.of(true, 1000, 0, 0, 0));
        OfferSnapshot facts = new OfferSnapshot(1420, 6.0, 24, 2);
        DecisionLog.record(app, kept(System.currentTimeMillis(), facts).withScore(142));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            String before = node(star, MinimumsStarView.OFFER_ID).getContentDescription().toString();
            assertTrue(before, before.startsWith("Offer $14.20, 6 mi, 24 min, 2 stops, passed"));

            // Accepted in Dasher while Offer Filter stays open behind it; no new offer has come in since.
            assertTrue(DecisionLog.markStep(app, facts, DecisionLog.StepKind.ACCEPTED,
                    "you tapped Accept, then Dasher showed a delivery", 60_000));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            String after = node(star, MinimumsStarView.OFFER_ID).getContentDescription().toString();
            assertTrue(after, after.startsWith("Offer $14.20, 6 mi, 24 min, 2 stops, accepted, rules said pass"));
            assertArrayEquals("it changes no minimum", new int[] {1000, 0, 0, 0, 0, 0},
                    FilterStore.load(app).minimums());
        }
    }

    /** Beside Dasher the mascot, at the strip's start, and the constellation under it each take their own taps. */
    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherTheMascotAndTheConstellationEachTakeTheirOwnTaps() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        FilterStore.save(app, FilterSettings.of(true, 2000, 150, 0, 0));
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            int width = content.getResources().getDisplayMetrics().widthPixels;
            int height = content.getResources().getDisplayMetrics().heightPixels;
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, width, height);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            ViewGroup sky = (ViewGroup) star.getParent();
            assertTrue("the page placed the mascot", mascot.placed());
            float middleX = star.skyX();
            float middleY = star.skyY();
            assertTrue("the upper-left mascot leaves the plot center clear",
                    Math.hypot(star.getLeft() + middleX - mascot.getLeft() - mascot.mascotX(),
                            star.getTop() + middleY - mascot.getTop() - mascot.mascotY()) > mascot.mascotRadius());

            tap(sky, mascot.getLeft() + mascot.mascotX(), mascot.getTop() + mascot.mascotY());
            assertFalse("a tap on the mascot pauses", FilterStore.load(app).enabled);
            assertFalse(settingsShown(content));

            tapStar(star, middleX, middleY);
            assertFalse("a tap on the constellation is the constellation's: it does not resume",
                    FilterStore.load(app).enabled);
            assertFalse("and opens no page", settingsShown(content));
            assertArrayEquals("nor sets anything", new int[] {2000, 150, 0, 0, 0, 0}, FilterStore.load(app).minimums());
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    /**
     * Half of a split screen beside Dasher (0.5.1 showed only the constellation there, no map): the radar and the map
     * side by side under the strip and the header, the radar's three spokes with their knobs and icons, the max stops
     * pin and badge and the Autopilot button all inside its own box, and the strip's words kept clear of the scene's
     * stars. A line asking for a fix stands under the header, above the radar and the map, which give it their room:
     * it never crosses a knob or an icon (0.5.1's line crossed the constellation's lower part).
     */
    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherTheRadarAndTheMapShareTheHalfAndALineToFixStandsAboveThem() throws Exception {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AreaMapView map = find(content, AreaMapView.class);
            ScenePage scene = find(content, ScenePage.class);
            assertNull("nothing to fix yet", shownTextContaining(content, SetupChecklist.NOTIFICATIONS));
            assertEquals("the radar wholly there", 1f, scene.shown(star), 0f);
            assertEquals("and the map beside it", 1f, scene.shown(map), 0f);
            assertTrue("side by side, the radar on the left", star.getRight() <= map.getLeft());
            android.graphics.RectF strip = inWindow(stripWords(content));
            android.graphics.RectF header = inWindow(pageHeader(content));
            assertTrue("both under the strip and the header", inWindow(star).top >= header.bottom - 1
                    && inWindow(map).top >= header.bottom - 1 && header.top >= strip.bottom - 1);
            float radius = star.skyRadius();
            assertTrue("a circle the knobs work at: " + radius,
                    radius >= new Ui(app).dp(FluidLayout.RADIUS_LEAST_DP) - 1);
            for (int axis : MinimumsStarView.SPOKES) assertNotNull("knob " + axis, star.knobAt(axis));
            for (int axis : new int[] {AreaScore.STOP, AreaScore.HOTSPOT, AreaScore.ITEM}) {
                assertNull("no knob on a retired spoke: " + axis, star.knobAt(axis));
            }
            java.lang.reflect.Method iconAt = MinimumsStarView.class.getDeclaredMethod("skyIcon", int.class,
                    float.class, float.class, float.class, android.graphics.RectF.class);
            iconAt.setAccessible(true);
            for (int axis = 0; axis < AreaScore.AXES; axis++) {
                android.graphics.RectF box = new android.graphics.RectF();
                boolean visible = (Boolean) iconAt.invoke(star, axis, star.skyX(), star.skyY(), radius, box);
                boolean shown = axis != AreaScore.HOTSPOT && axis != AreaScore.ITEM;
                assertEquals("axis " + axis + " at " + box + "; radar=" + star.getWidth() + "x" + star.getHeight()
                        + ", center=" + star.skyX() + "," + star.skyY() + ", radius=" + radius + ", stops="
                        + star.stopsBox(), shown, visible);
            }
            List<android.graphics.RectF> icons = new ArrayList<>();
            star.iconsAt(icons);
            assertEquals("the three spokes' icons, the max stops pin and its badge, and the Autopilot button; button="
                    + star.autopilotBox() + "; stops=" + star.stopsBox(), 6, icons.size());
            for (android.graphics.RectF icon : icons) {
                assertTrue("each icon inside the radar's own box: " + icon + " in " + star.getWidth() + "x"
                        + star.getHeight(), icon.left >= 0 && icon.top >= 0 && icon.right <= star.getWidth()
                        && icon.bottom <= star.getHeight());
            }
            assertFalse("the rings' dollars are on the page", star.levelWords().isEmpty());
            android.graphics.RectF page = inWindow(scene);
            android.graphics.RectF verdict = inWindow(verdictLine(content));
            assertTrue("the scene knows the verdict is words, so no star lands on it", containsPoint(scene.words(),
                    verdict.centerX() - page.left, verdict.centerY() - page.top));

            // Background offers go off: the line asking for a fix stands under the header, above the radar and the
            // map, which give it their room; the radar keeps its knobs and every icon, none under the line.
            listener.destroy();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            settleSky(content);
            TextView problem = shownTextContaining(content, SetupChecklist.NOTIFICATIONS);
            assertNotNull(problem);
            android.graphics.RectF row = inWindow((View) problem.getParent());
            assertTrue("the line under the header: " + row + " / " + inWindow(pageHeader(content)),
                    row.top >= inWindow(pageHeader(content)).bottom - 1);
            android.graphics.RectF radar = inWindow(star);
            assertTrue("above the radar and the map: " + row + " / " + radar, row.bottom <= radar.top + 1
                    && row.bottom <= inWindow(map).top + 1);
            assertEquals("the radar still wholly there, a little smaller at most", 1f, scene.shown(star), 0f);
            assertTrue("never larger for the line", star.skyRadius() <= radius + 1);
            for (int axis : MinimumsStarView.SPOKES) assertNotNull("knob " + axis, star.knobAt(axis));
            icons.clear();
            star.iconsAt(icons);
            assertEquals("every icon still shows (the badge and the button too)", 6, icons.size());
            for (android.graphics.RectF icon : icons) {
                assertTrue("each icon inside the radar's own box, below the line: " + icon, icon.left >= 0
                        && icon.top >= 0 && icon.right <= star.getWidth() && icon.bottom <= star.getHeight());
            }
            assertTrue("the scene knows the line is words too", containsPoint(scene.words(),
                    row.centerX() - page.left, row.centerY() - page.top));
        } finally {
            if (OfferNotificationService.isConnected()) listener.destroy();
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    /**
     * A fresh page beside Dasher with two setup steps to do (0.5.1 crossed the constellation with them and put the
     * start on the ground): the start is the strip's status line at the top, the two steps stand under the header,
     * and the radar under them keeps every hollow knob, wholly there with the map beside it. The mascot stands in the
     * strip above them all.
     */
    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherOnAFreshPageTheStartTopsThePageAndTwoStepsStandAboveTheRadar() {
        FilterStore.save(app, FilterSettings.of(false, 0, 0, 0, 0));
        Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class)).setNotificationsEnabled(false);
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
            ScenePage scene = find(content, ScenePage.class);
            android.graphics.RectF header = inWindow(pageHeader(content));
            float linesTop = Float.MAX_VALUE;
            float linesBottom = 0;
            for (String line : new String[] {SetupChecklist.NOTIFICATIONS, SetupChecklist.ALERTS}) {
                TextView shown = shownTextContaining(content, line);
                assertNotNull(line, shown);
                android.graphics.RectF row = inWindow((View) shown.getParent());
                linesTop = Math.min(linesTop, row.top);
                linesBottom = Math.max(linesBottom, row.bottom);
            }
            assertTrue("the steps under the header", linesTop >= header.bottom - 1);
            // With no rule yet, the start is the strip's status line, at the top of the page.
            TextView start = statusLine(content);
            assertEquals(MainActivity.START_LINE, start.getText().toString());
            assertTrue("the start above the header", inWindow(start).bottom <= header.top + 1);
            assertEquals("the radar wholly there", 1f, scene.shown(star), 0f);
            assertTrue("under the steps", inWindow(star).top >= linesBottom - 1);
            for (int axis : MinimumsStarView.SPOKES) {
                assertNotNull("every hollow knob stays with both steps: " + axis, star.knobAt(axis));
            }
            assertTrue("the upper spokes inside the radar's box", star.skyY() - star.skyRadius() >= 0);
            AreaMapView map = find(content, AreaMapView.class);
            assertEquals("the map beside it", 1f, scene.shown(map), 0f);
            android.graphics.RectF page = inWindow(scene);
            float mascotY = page.top + mascot.getTop() + mascot.mascotY();
            float mascotX = page.left + mascot.getLeft() + mascot.mascotX();
            assertTrue("the mascot in the strip, above the header", mascotY + mascot.mascotRadius() <= header.top + 1);
            assertTrue("inside the page", mascotX - mascot.mascotRadius() >= page.left);
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    public void atTwiceTheFontSizeEveryKnobStaysInReachOfTheSetupLinesAndTheStartLine() {
        everyKnobStaysInReachOfTheLines(false);
    }

    /**
     * Beside Dasher in a half tall enough for the radar at twice the font with its steps to do (in a shorter one the
     * strip, the header and the steps take the whole half and the radar waits for room: FluidPageTest).
     */
    @Test
    @Config(qualifiers = "w411dp-h640dp-420dpi")
    public void besideDasherAtTwiceTheFontSizeEveryKnobStaysInReachOfTheLines() {
        everyKnobStaysInReachOfTheLines(true);
    }

    /**
     * A fresh page at twice the font size with setup still to do (0.5.1 crossed the constellation with the setup lines,
     * so a set knob could stand under one, and put the start on the ground): the start is the strip's status line at
     * the top, the setup lines stand under the header and the radar under them, wholly there, so no line covers a
     * knob, hollow or set, anywhere along its spoke; each hollow knob takes a drag along its spoke, and the per-hour
     * knob takes one from wherever it stands.
     */
    private void everyKnobStaysInReachOfTheLines(boolean beside) {
        RuntimeEnvironment.setFontScale(2f);
        FilterStore.save(app, FilterSettings.of(false, 0, 0, 0, 0));
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        if (beside) {
            service.get().onServiceConnected();
            OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        }
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        if (beside) Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.backdrop());
            Ui ui = new Ui(app);
            View line = lineAt(content, star, null, SETUP_LINES);
            assertNotNull("a setup line to do", line);
            TextView start = statusLine(content);
            assertEquals("the start is the strip's status line", MainActivity.START_LINE, start.getText().toString());
            assertTrue("at the top, above the header",
                    inWindow(start).bottom <= inWindow(pageHeader(content)).top + 1);
            assertEquals("the radar wholly there", 1f, find(content, ScenePage.class).shown(star), 0f);
            assertTrue("under the lines", inWindow(star).top >= inWindow(line).bottom - 1);
            for (int axis : MinimumsStarView.SPOKES) {
                float[] knob = star.knobAt(axis);
                assertNotNull(knob);
                assertNull("no line covers hollow knob " + axis, lineAt(content, star, knob, SETUP_LINES));
            }
            // Each hollow knob in turn, from where it rests, along its spoke: saved.
            for (int axis : MinimumsStarView.SPOKES) {
                stillBesideDasher(beside);
                settleSky(content);
                float[] knob = star.knobAt(axis);
                dragKnob(content, star, knob, alongSpoke(knob, axis, ui.dp(40), 0), null);
                assertTrue("knob " + axis + " took its drag: " + java.util.Arrays.toString(
                        FilterStore.load(app).minimums()), FilterStore.load(app).minimums()[axis] > 0);
            }
            assertFalse("auto-decline stays paused", FilterStore.load(app).enabled);

            // A per-hour minimum anywhere from $6 to $240 an hour: its knob is never under a setup line.
            for (int cents = 10; cents <= 400; cents += 10) {
                stillBesideDasher(beside);
                FilterStore.save(app, FilterSettings.of(false, 0, 0, cents, 0));
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
                settleSky(content);
                float[] knob = star.knobAt(AreaScore.MINUTE);
                assertNotNull("the per-hour knob shows at " + cents + " cents a minute", knob);
                assertNull("no line over it at " + cents + " cents a minute",
                        lineAt(content, star, knob, SETUP_LINES));
            }
            // From where it stands, at $240 an hour, it takes a drag in along its spoke.
            stillBesideDasher(beside);
            settleSky(content);
            int before = FilterStore.load(app).perMinuteCents;
            float[] knob = star.knobAt(AreaScore.MINUTE);
            dragKnob(content, star, knob, alongSpoke(knob, AreaScore.MINUTE, -ui.dp(40), 0), null);
            int after = FilterStore.load(app).perMinuteCents;
            assertTrue("the knob took its drag: " + before + " -> " + after, after > 0 && after < before);
            assertFalse("still paused", FilterStore.load(app).enabled);
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    private static final String[] SETUP_LINES = {SetupChecklist.ACCESSIBILITY, SetupChecklist.NOTIFICATIONS,
            SetupChecklist.ALERTS};

    /** The screen reader sees Dasher beside again, as it does with each of Dasher's events (else it lapses in 20 s). */
    private static void stillBesideDasher(boolean beside) {
        if (beside) OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
    }

    /**
     * The shown line (one of {@code words}) whose row covers {@code at} (the star's pixels), or null; with {@code at}
     * null, any one of them that shows.
     */
    private static View lineAt(View content, MinimumsStarView star, float[] at, String[] words) {
        for (String each : words) {
            TextView text = shownTextContaining(content, each);
            if (text == null) continue;
            View row = (View) text.getParent();
            if (at == null) return row;
            float[] point = inContent(content, star, at);
            int[] rowAt = new int[2];
            int[] contentAt = new int[2];
            row.getLocationInWindow(rowAt);
            content.getLocationInWindow(contentAt);
            float left = rowAt[0] - contentAt[0];
            float top = rowAt[1] - contentAt[1];
            if (point[0] >= left && point[0] <= left + row.getWidth() && point[1] >= top
                    && point[1] <= top + row.getHeight()) {
                return row;
            }
        }
        return null;
    }

    /** {@code at} in the star's pixels, as a point in the page's own. */
    private static float[] inContent(View content, MinimumsStarView star, float[] at) {
        int[] starAt = new int[2];
        int[] contentAt = new int[2];
        star.getLocationInWindow(starAt);
        content.getLocationInWindow(contentAt);
        return new float[] {at[0] + starAt[0] - contentAt[0], at[1] + starAt[1] - contentAt[1]};
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void aKnobDraggedAlongItsSpokeSavesTheSnappedMinimum() {
        FilterStore.save(app, FilterSettings.of(true, 1000, 80, 30, 3));
        // The example offer: 7.2 mi, 21 min, 2 stops.
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue("a whole screen draws the constellation as the sky", star.backdrop());
            float[] knob = star.knobAt(1);
            assertNotNull("the per-mile spoke has a knob", knob);
            float[] middle = {star.skyX(), star.skyY()};
            long ring = star.ringCents();
            Ui ui = new Ui(app);

            // Out along the per-mile spoke, the finger straying a little off it: only the distance along it counts.
            float[] to = alongSpoke(knob, 1, ui.dp(60), ui.dp(9));
            dragKnob(content, star, knob, to, () -> {
                assertTrue(star.dragging());
                assertEquals("nothing is saved until it is let go", 80, FilterStore.load(app).perMileCents);
            });
            double reach = along(middle, 1, to) / star.skyRadius() * ring * 3;
            int expected = (int) (Math.round(reach / 7.2 / 5) * 5);
            FilterSettings saved = FilterStore.load(app);
            assertTrue("higher than $0.80: " + expected, expected > 80);
            assertEquals("snapped to $0.05 on the chart's scale for 7.2 mi", expected, saved.perMileCents);
            assertTrue("still on", saved.enabled);
            assertEquals("max stops untouched", 3, saved.maxStops);
            assertArrayEquals("the other minimums untouched", new int[] {1000, expected, 30, 0, 0, 0},
                    saved.minimums());
            assertFalse("a drag opens no page", settingsShown(content));

            // Into the middle: the rule is off.
            settleSky(content);
            float[] now = star.knobAt(1);
            dragKnob(content, star, now, middle, null);
            assertEquals(0, FilterStore.load(app).perMileCents);
            assertTrue("other rules remain, so it stays on", FilterStore.load(app).enabled);
            settleSky(content);
            float[] resting = star.knobAt(1);
            assertEquals("its hollow knob rests just outside the middle", ui.dp(MinimumsStarView.KNOB_REST_DP),
                    Math.hypot(resting[0] - middle[0], resting[1] - middle[1]), 1);

            // A tap on the circle away from the knobs and the offer's shape, and a tap on a knob, open nothing (the
            // minimums are all here) and set nothing.
            ViewGroup sky = (ViewGroup) star.getParent();
            tapStar(star, middle[0] + star.skyRadius() * 0.85f, middle[1]);
            assertFalse(settingsShown(content));
            settleSky(content);
            float[] pay = star.knobAt(0);
            tapStar(star, pay[0], pay[1]);
            assertFalse(settingsShown(content));
            assertEquals("a tap sets nothing", 1000, FilterStore.load(app).flatCents);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void theRingsHoldStillWhileAKnobMovesAndGrowToFitItWhenLetGo() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 0));
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            long ring = star.ringCents();
            float[] knob = star.knobAt(0);
            // Pushed out past the outer ring.
            float[] to = alongSpoke(knob, 0, star.skyRadius() * 1.4f, 0);
            dragKnob(content, star, knob, to, () -> {
                assertEquals("the scale holds still under the finger", ring, star.ringCents());
                // A new offer while the knob is held does not move the rings either.
                DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                        false, new OfferSnapshot(4800, 9.0, 30, 2), 1000, OfferRule.Result.KEEP,
                        "meets your minimums", DecisionLog.Action.PASSES, true, Collections.emptyList()));
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
                assertTrue(star.dragging());
                assertEquals(ring, star.ringCents());
            });
            int pay = FilterStore.load(app).flatCents;
            assertTrue("past the outer ring's " + ring * 3 + ": " + pay, pay > ring * 3);
            assertEquals("in $0.50 steps", 0, pay % 50);
            assertTrue("the rings grew to fit it: " + star.ringCents(), star.ringCents() > ring);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void screenReadersAdjustEachKnobByOneStepAndHearTheValue() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            assertNotNull("each knob is its own control", nodes);
            AccessibilityNodeInfo host = nodes.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID);
            assertEquals("three knobs, the max stops badge and the Autopilot button", 5, host.getChildCount());
            AccessibilityNodeInfo mile = nodes.createAccessibilityNodeInfo(1);
            assertEquals("Minimum per mile, $1.50", mile.getContentDescription().toString());
            assertEquals(android.widget.SeekBar.class.getName(), mile.getClassName().toString());
            assertTrue("a screen reader stops on it, not taking it for part of the clickable chart",
                    mile.isFocusable());
            if (Build.VERSION.SDK_INT >= 28) assertTrue(mile.isScreenReaderFocusable());
            assertEquals(1.5f, mile.getRangeInfo().getCurrent(), 0.001f);
            assertEquals(1000f, mile.getRangeInfo().getMax(), 0.001f);
            assertTrue(mile.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD));
            AccessibilityNodeInfo hour = nodes.createAccessibilityNodeInfo(AreaScore.MINUTE);
            assertEquals("Minimum per hour of trip time, 18 dollars per hour",
                    hour.getContentDescription().toString());
            assertEquals("in dollars an hour", 18f, hour.getRangeInfo().getCurrent(), 0.001f);
            for (int retired : new int[] {AreaScore.STOP, AreaScore.HOTSPOT, AreaScore.ITEM}) {
                assertNull("no knob for a retired spoke: " + retired, nodes.createAccessibilityNodeInfo(retired));
                assertFalse(nodes.performAction(retired, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
            }

            assertTrue(nodes.performAction(1, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
            assertEquals("one $0.05 step, saved", 155, FilterStore.load(app).perMileCents);
            assertEquals("Minimum per mile, $1.55", star.lastSaid());
            assertTrue(nodes.performAction(1, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null));
            assertTrue(nodes.performAction(1, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null));
            assertEquals(145, FilterStore.load(app).perMileCents);
            assertTrue(nodes.performAction(0, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
            assertEquals("pay steps $0.50", 750, FilterStore.load(app).flatCents);
            assertTrue(nodes.performAction(2, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null));
            assertEquals("per hour steps 60 cents (a cent a minute)", 29, FilterStore.load(app).perMinuteCents);
            assertEquals("Minimum per hour of trip time, 17 dollars 40 cents per hour", star.lastSaid());
            FilterSettings saved = FilterStore.load(app);
            assertTrue(saved.enabled);
            assertEquals(3, saved.maxStops);
            assertFalse("a knob never turns Autopilot on", saved.autopilot);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherOneAutopilotButtonStandsWhereTheRowStoodAndTakesItsOwnTaps() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
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
            assertTrue(star.backdrop());
            AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            assertEquals("three knobs, the badge, the Autopilot button and the offer marked", 6,
                    nodes.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID).getChildCount());
            for (int retired : new int[] {MinimumsStarView.ADOPT_ID, MinimumsStarView.ADAPTIVE_ID}) {
                assertNull("the retired buttons' ids are never shown", nodes.createAccessibilityNodeInfo(retired));
            }

            android.graphics.RectF button = star.autopilotBox();
            assertNotNull("one round button", button);
            Ui ui = new Ui(app);
            assertTrue("a full touch target", button.width() >= ui.dp(48) - 1 && button.height() >= ui.dp(48) - 1);
            for (int axis : MinimumsStarView.SPOKES) {
                float[] knob = star.knobAt(axis);
                assertTrue("clear of the knobs", Math.hypot(knob[0] - button.centerX(),
                        knob[1] - button.centerY()) >= ui.dp(24) + button.width() / 2 - 1);
            }
            List<android.graphics.RectF> icons = new ArrayList<>();
            star.iconsAt(icons);
            assertTrue("the scene keeps its clouds and stars off it",
                    containsPoint(icons, button.centerX(), button.centerY()));
            assertEquals("Autopilot, off. Offers are judged at exactly your minimums.",
                    nodes.createAccessibilityNodeInfo(MinimumsStarView.SCORE_ID).getContentDescription().toString());

            // While off, its tap asks for the goal; one tap on a goal turns Autopilot on with it.
            ViewGroup sky = (ViewGroup) star.getParent();
            tapStar(star, button.centerX(), button.centerY());
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(chooser);
            assertEquals(AutopilotText.CHOOSER_TITLE, Shadows.shadowOf(chooser).getTitle().toString());
            assertFalse("asking changes nothing", FilterStore.load(app).autopilot);
            Shadows.shadowOf(chooser).clickOnItem(0);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FilterSettings on = FilterStore.load(app);
            assertTrue(on.autopilot);
            assertEquals(70, on.autopilotGoalPercent);
            assertEquals("Autopilot on · goal: acceptance rate 70% or more", ShadowToast.getTextOfLatestToast());
            assertArrayEquals("the minimums stay", new int[] {700, 150, 30, 0, 0, 0}, on.minimums());
            assertTrue("still on", on.enabled);
            assertEquals(3, on.maxStops);
            assertFalse("no page opens", settingsShown(content));

            // While on, its tap turns it off, back to exactly the minimums.
            settleSky(content);
            button = star.autopilotBox();
            tapStar(star, button.centerX(), button.centerY());
            FilterSettings off = FilterStore.load(app);
            assertFalse(off.autopilot);
            assertEquals(100, off.minimumScalePercent);
            assertEquals("Autopilot off · back to exactly your minimums", ShadowToast.getTextOfLatestToast());
            assertArrayEquals(new int[] {700, 150, 30, 0, 0, 0}, off.minimums());
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void aKnobChangeMovesAutopilotsDashedShapeWithItAndAsksForANewPlan() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, 100, 80));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            // The typical example offer: 5 mi.
            assertEquals("80% of $1.50 × 5 mi", 600, star.barAsks(AreaScore.MILE), 0.001);
            assertTrue(act(star, AreaScore.MILE, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            settleSky(content);
            assertEquals(155, FilterStore.load(app).perMileCents);
            assertEquals("the dashed shape follows the minimum at once: 80% of $1.55 × 5 mi", 620,
                    star.barAsks(AreaScore.MILE), 0.001);
            assertEquals("a knob never moves the bar itself", 80, FilterStore.load(app).minimumScalePercent);
            assertEquals("Autopilot works out the new minimums: its next commit goes straight to their bar",
                    Autopilot.Reason.RULES_CHANGED.name(), AutopilotStore.jump(app));
        }
    }

    /**
     * In a short split screen with another app the radar is the same radar beside the map (0.5.1 put it in the header
     * there without its knobs, and a tap swapped it with the map): its knobs and its Autopilot button work there, for
     * fingers and screen readers, with the strip's chip still on screen and the map still beside it.
     */
    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void inAShortSplitWithAnotherAppTheRadarKeepsItsKnobsAndButtonBesideTheMap() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        OfferFilterService.sawDasherBeside(0);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertEquals("wholly there", 1f, find(content, ScenePage.class).shown(star), 0f);
            assertTrue("in the sky", star.backdrop());
            assertNotNull("with its knobs", star.knobAt(1));
            assertNotNull("and the Autopilot button", star.autopilotBox());
            assertTrue("the strip's chip as well", find(content, AutopilotChip.class).isShown());
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue("the map beside it", map.isShown() && star.getRight() <= map.getLeft());
            assertTrue(act(star, AreaScore.MILE, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertEquals("a knob sets its minimum here as anywhere", 155, FilterStore.load(app).perMileCents);
            assertFalse("no page opens", settingsShown(content));
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void aKnobKeepsItsExactValueUntilMovedHalfAStepAlongItsSpoke() {
        // Minimums between steps (the 0.5.0 update built an old buffer into them): $14.21 and $2.37 a mile.
        int[] between = {1421, 237, 48, 0, 0, 0};
        FilterStore.save(app, FilterSettings.of(true, 1421, 237, 48, 3));
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            Ui ui = new Ui(app);
            // A finger that slides off the pay or per-mile knob sideways, well past the touch slop, takes nothing.
            for (int axis : new int[] {0, 1}) {
                float[] knob = star.knobAt(axis);
                dragKnob(content, star, knob, alongSpoke(knob, axis, 0, ui.dp(20)),
                        () -> assertFalse("a slide across the spoke is not a drag", star.dragging()));
                assertArrayEquals(between, FilterStore.load(app).minimums());
                assertFalse("nor a tap", settingsShown(content));
            }

            // Out along the spoke and back to where it was taken: $14.21 stays as it is, not snapped to $14.00.
            settleSky(content);
            float[] pay = star.knobAt(0);
            dragThrough(content, new float[][] {pay, alongSpoke(pay, 0, ui.dp(30), 0), pay},
                    () -> assertTrue(star.dragging()));
            assertArrayEquals(between, FilterStore.load(app).minimums());

            // In past half a step ($0.25) from $14.21: the step below, $14.00, is in reach.
            settleSky(content);
            pay = star.knobAt(0);
            float perCent = star.skyRadius() / (star.ringCents() * 3f);
            dragThrough(content, new float[][] {pay, alongSpoke(pay, 0, -ui.dp(30), 0),
                    alongSpoke(pay, 0, -31 * perCent, 0)}, null);
            assertEquals(1400, FilterStore.load(app).flatCents);
            assertArrayEquals("nothing else moved", new int[] {1400, 237, 48, 0, 0, 0},
                    FilterStore.load(app).minimums());
        }
    }

    @Test
    public void inAPageThatScrollsASwipeFromAKnobScrollsAndASlideAcrossItSetsNothing() {
        try (ActivityController<android.app.Activity> built =
                Robolectric.buildActivity(android.app.Activity.class).setup()) {
            LoneSky page = new LoneSky(built.get(), FilterSettings.of(true, 725, 150, 30, 0));
            Ui ui = page.ui;
            MinimumsStarView star = page.star;

            // A finger landing on the pay knob grows it, before anything is claimed.
            float[] pay = star.knobAt(0);
            long now = android.os.SystemClock.uptimeMillis();
            page.scroll.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, pay[0], pay[1], 0));
            assertEquals(0, star.pressedKnob());
            page.scroll.dispatchTouchEvent(MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, pay[0], pay[1], 0));
            assertEquals(-1, star.pressedKnob());
            assertEquals("a tap on a knob is a tap", 1, page.clicks);

            // A swipe up the page that starts on the pay knob scrolls the page and sets nothing.
            page.swipe(pay, new float[] {pay[0], pay[1] - ui.dp(120)});
            assertTrue("the page scrolled: " + page.scroll.getScrollY(), page.scroll.getScrollY() > 0);
            assertTrue("nothing saved: " + page.saves, page.saves.isEmpty());
            assertEquals(1, page.clicks);
            page.scroll.scrollTo(0, 0);
            page.layOut();

            // A slide across the spoke from the $7.25 knob, either way, past the touch slop (12 dp would do on a
            // phone, whose slop is 8 dp): not a drag, not a tap.
            float across = Math.max(ui.dp(12), page.slop * 1.4f);
            for (int side = -1; side <= 1; side += 2) {
                pay = star.knobAt(0);
                page.swipe(pay, alongSpoke(pay, 0, 0, side * across));
                assertFalse(star.dragging());
                assertTrue("nothing saved: " + page.saves, page.saves.isEmpty());
                assertEquals(1, page.clicks);
                page.scroll.scrollTo(0, 0);
                page.layOut();
            }

            // A drag along the spoke still sets it, and the page holds still meanwhile.
            pay = star.knobAt(0);
            page.swipe(pay, alongSpoke(pay, 0, ui.dp(40), 0));
            assertEquals(1, page.saves.size());
            assertTrue(page.saves.toString(), page.saves.get(0).startsWith("0="));
            assertEquals("the page held still", 0, page.scroll.getScrollY());
        }
    }

    @Test
    public void aKnobSetSoLowItRestsNearTheMiddleTurnsOffOnlyWhenPushedClearlyIn() {
        try (ActivityController<android.app.Activity> built =
                Robolectric.buildActivity(android.app.Activity.class).setup()) {
            // $0.10 a mile asks $0.50 of the example's 5 mi, on rings reaching $21: inside the resting place.
            LoneSky page = new LoneSky(built.get(), FilterSettings.of(true, 2000, 10, 0, 0));
            Ui ui = page.ui;
            MinimumsStarView star = page.star;
            float[] mile = star.knobAt(AreaScore.MILE);
            float[] middle = {star.skyX(), star.skyY()};
            assertTrue("it rests inside the resting place",
                    Math.hypot(mile[0] - middle[0], mile[1] - middle[1]) < ui.dp(MinimumsStarView.KNOB_REST_DP));

            // Sideways past the slop: nothing set, and the rule is not turned off.
            float past = page.slop + ui.dp(4);
            page.swipe(mile, alongSpoke(mile, AreaScore.MILE, 0, past));
            assertTrue("nothing saved: " + page.saves, page.saves.isEmpty());
            page.scroll.scrollTo(0, 0);
            page.layOut();
            // Out along its spoke and back to where it was: still nothing.
            page.drag(new float[][] {mile, alongSpoke(mile, AreaScore.MILE, past, 0), mile});
            assertTrue("nothing saved: " + page.saves, page.saves.isEmpty());

            // Out, then back in to 9 dp inside where it was (less than a clear 12 dp): it stays on, a step lower.
            page.drag(new float[][] {mile, alongSpoke(mile, AreaScore.MILE, past, 0),
                    alongSpoke(mile, AreaScore.MILE, -ui.dp(9), 0)});
            assertEquals(1, page.saves.size());
            assertEquals("1=5", page.saves.get(0));

            // Pushed in through the middle: off.
            mile = star.knobAt(AreaScore.MILE);
            page.drag(new float[][] {mile, alongSpoke(middle, AreaScore.MILE, -ui.dp(16), 0)});
            assertEquals("1=0", page.saves.get(page.saves.size() - 1));
        }
    }

    /**
     * Paused beside Dasher with a step to do: the line saying so is the strip's status line at the top of the page, and
     * the radar's knobs stand under the header and the step, so neither covers the other (0.5.1's "Paused" stood in
     * the sky among the knobs). A tap on the per-hour knob does not resume and a drag from it sets its minimum; a tap
     * on the line still resumes.
     */
    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherThePausedLineTopsThePageAndAKnobTakesItsOwnTouches() {
        FilterStore.save(app, FilterSettings.of(false, 2000, 150, 75, 0));
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
            assertNotNull(shownTextContaining(content, SetupChecklist.NOTIFICATIONS));
            TextView paused = statusLine(content);
            assertEquals(MainActivity.PAUSED_LINE, paused.getText().toString());
            assertEquals("the radar wholly there", 1f, find(content, ScenePage.class).shown(star), 0f);
            float[] knob = star.knobAt(2);
            assertNotNull(knob);
            android.graphics.RectF line = inWindow(paused);
            android.graphics.RectF radar = inWindow(star);
            assertTrue("the line at the top, the radar under the header: " + line + " / " + radar,
                    line.bottom <= inWindow(pageHeader(content)).top + 1
                            && radar.top >= inWindow(pageHeader(content)).bottom - 1);
            assertFalse("the knob nowhere near the line", line.contains(radar.left + knob[0], radar.top + knob[1]));

            tapStar(star, knob[0], knob[1]);
            assertFalse("a tap on the knob does not resume", FilterStore.load(app).enabled);
            assertFalse("and opens no page", settingsShown(content));
            settleSky(content);
            knob = star.knobAt(2);
            dragKnob(content, star, knob, alongSpoke(knob, 2, new Ui(app).dp(30), 0), null);
            assertTrue("a drag from it sets the per-hour minimum: " + FilterStore.load(app).perMinuteCents,
                    FilterStore.load(app).perMinuteCents > 75);
            assertFalse("still paused", FilterStore.load(app).enabled);

            // The line itself still resumes.
            settleSky(content);
            android.graphics.RectF at = inWindow(statusLine(content));
            android.graphics.RectF page = inWindow(content);
            tap((ViewGroup) content, at.centerX() - page.left, at.centerY() - page.top);
            assertTrue(FilterStore.load(app).enabled);
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    // The window is shown, so every frame is drawn: really, as on a phone, rather than as legacy draw descriptions,
    // which grow with every frame.
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aScreenReaderOnTheAutopilotButtonKeepsItsFocusAndHearsNothingWhenAutopilotMovesTheBar() {
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 3));
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        org.robolectric.shadows.ShadowAccessibilityManager reader = Shadows.shadowOf(
                app.getSystemService(android.view.accessibility.AccessibilityManager.class));
        // Autopilot commits only through the screen reading, which is running; background offers too, so no setup line
        // takes the status line's room.
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            showWindow(content);
            settleSky(content);
            reader.setEnabled(true);
            reader.setTouchExplorationEnabled(true);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            int button = MinimumsStarView.SCORE_ID;
            assertTrue(nodes.performAction(button, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
            assertEquals(button, star.focusedNode());
            assertEquals("Autopilot, on. Bar 100 percent of your minimums. Goal: keep a top tier, acceptance rate 70 "
                    + "percent or more.", nodes.createAccessibilityNodeInfo(button).getContentDescription().toString());
            String said = star.lastSaid();
            int sent = reader.getSentAccessibilityEvents().size();

            // Autopilot moves the bar between offers, by itself: the button follows quietly and keeps the focus.
            assertTrue(FilterStore.commitAutopilotBar(app, 100, 82));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            settleSky(content);
            assertEquals("the focus stays", button, star.focusedNode());
            assertEquals("Autopilot, on. Bar 82 percent of your minimums. Goal: keep a top tier, acceptance rate 70 "
                    + "percent or more.", nodes.createAccessibilityNodeInfo(button).getContentDescription().toString());
            assertEquals("nothing is said", said, star.lastSaid());
            // Its words change on screen (Android tells screen readers so their view of the page stays current), but
            // nothing is announced, and no part of the page is a live region a screen reader would read out.
            for (AccessibilityEvent event : reader.getSentAccessibilityEvents().subList(sent,
                    reader.getSentAccessibilityEvents().size())) {
                assertTrue("no announcement while Autopilot moves the bar: " + event,
                        event.getEventType() != AccessibilityEvent.TYPE_ANNOUNCEMENT);
            }
            TextView status = shownTextContaining(content, "Autopilot 82%");
            assertNotNull("the status line follows too", status);
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, status.getAccessibilityLiveRegion());
            assertNull("no live region anywhere on the page", liveRegionIn(content));
        } finally {
            reader.setTouchExplorationEnabled(false);
            reader.setEnabled(false);
            listener.destroy();
            service.destroy();
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void withNoMoneyMinimumTheAutopilotButtonSaysWhatItNeedsAndTurnsNothingOn() {
        // Max stops alone is a rule, but Autopilot moves a bar on money minimums.
        FilterStore.save(app, FilterSettings.of(true, 0, 0, 0, 3));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            ShadowAlertDialog.reset();
            android.graphics.RectF button = star.autopilotBox();
            assertNotNull(button);
            tapStar(star, button.centerX(), button.centerY());
            assertEquals("Set a pay, per-mile or hourly minimum first.", ShadowToast.getTextOfLatestToast());
            assertNull("no chooser", ShadowAlertDialog.getLatestAlertDialog());
            assertTrue(act(star, MinimumsStarView.SCORE_ID, AccessibilityNodeInfo.ACTION_LONG_CLICK));
            assertNull("held, still no chooser", ShadowAlertDialog.getLatestAlertDialog());
            FilterSettings saved = FilterStore.load(app);
            assertFalse(saved.autopilot);
            assertEquals(100, saved.minimumScalePercent);
            assertArrayEquals(new int[] {0, 0, 0, 0, 0, 0}, saved.minimums());
            assertEquals(3, saved.maxStops);
            assertTrue(saved.enabled);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void aFreshPageHasThreeHollowKnobsAndAFirstRuleSetByOneStaysPaused() {
        FilterStore.save(app, FilterSettings.of(false, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.backdrop());
            float[] middle = {star.skyX(), star.skyY()};
            for (int axis : MinimumsStarView.SPOKES) {
                float[] knob = star.knobAt(axis);
                assertNotNull("a hollow knob on every spoke", knob);
                assertEquals("resting just outside the middle", new Ui(app).dp(MinimumsStarView.KNOB_REST_DP),
                        Math.hypot(knob[0] - middle[0], knob[1] - middle[1]), 1);
            }
            assertEquals("screen readers find the three knobs, the badge and the Autopilot button", 5,
                    star.getAccessibilityNodeProvider().createAccessibilityNodeInfo(
                            AccessibilityNodeProvider.HOST_VIEW_ID).getChildCount());
            assertNotNull(shownTextContaining(content, MainActivity.START_LINE));

            // One dragged out saves the first rule.
            float[] mile = star.knobAt(1);
            dragKnob(content, star, mile, alongSpoke(mile, 1, new Ui(app).dp(60), 0), null);
            FilterSettings saved = FilterStore.load(app);
            assertTrue("the per-mile minimum is saved: " + saved.perMileCents, saved.perMileCents > 0);
            assertEquals("only it", 0, saved.flatCents);
            assertFalse("auto-decline stays paused", saved.enabled);
            assertEquals("Rule saved. Tap the mascot to turn on auto-decline.", ShadowToast.getTextOfLatestToast());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertNull("the start goes once there is a rule", shownTextContaining(content, MainActivity.START_LINE));
        }
    }

    @Test
    public void chartDrawsUnknownPayAndSaturatedRequirements() {
        DecisionChartView chart = new DecisionChartView(app, new Ui(app));
        List<DecisionLog.Entry> entries = new ArrayList<>();
        entries.add(declinedEntry());
        entries.add(new DecisionLog.Entry(2000, DecisionLog.Source.NOTIFICATION, false, OfferSnapshot.UNKNOWN, 0,
                OfferRule.Result.REVIEW, "pay not found", DecisionLog.Action.SILENT_CARD, true,
                Collections.emptyList()));
        entries.add(new DecisionLog.Entry(3000, DecisionLog.Source.SCREEN, false, new OfferSnapshot(100, 1.0, 999, 2),
                Long.MAX_VALUE, OfferRule.Result.DECLINE, "dollars per hour", DecisionLog.Action.DECLINE_TAPPED,
                true, Collections.emptyList()));
        chart.setEntries(entries);
        chart.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, 1000, 400);
        chart.draw(new Canvas(Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)));
        assertTrue(chart.getContentDescription().toString().startsWith("Chart of the last 3 offers: 0 passed, 2 declined, 1 need review."));
    }

    /**
     * The page's window shown as a phone shows it. Robolectric adds the window without Android's app-visible flag, so
     * its window stays GONE, and Android lets no view in a window that is not visible keep a screen reader's focus: its
     * next layout pass clears it whatever the page does. Visible, the focus stays unless the page itself drops it.
     */
    /** The first view under {@code view} that is a live region, or null. */
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

    private static void showWindow(View content) {
        try {
            Object root = View.class.getMethod("getViewRootImpl").invoke(content);
            root.getClass().getMethod("dispatchAppVisibility", boolean.class).invoke(root, true);
        } catch (ReflectiveOperationException unavailable) {
            throw new AssertionError(unavailable);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the window shows", View.VISIBLE, content.getWindowVisibility());
    }

    /** A screen line that passed, recorded {@code at}. */
    private static DecisionLog.Entry kept(long at, OfferSnapshot facts) {
        return new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false, facts, 1000, OfferRule.Result.KEEP,
                "meets your minimums", DecisionLog.Action.PASSES, true, Collections.emptyList());
    }
}
