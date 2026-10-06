package com.local.dasherfilter;

import android.Manifest;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.time.Duration;
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The header's charts through real Android adapters: the constellation of minimums with its knobs and adopted
 * minimums, and the decisions chart.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class AndroidAdapterChartTest extends AndroidAdapterTestBase {
    @Test
    public void tappingAChartColumnShowsWhatWasRead() {
        DecisionLog.record(app, declinedEntry());
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() + 60_000, DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(2500, 9.1, 30, 3), 2000, OfferRule.Result.KEEP, "meets enabled rules",
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

    /** A knob set to {@code dollars} as a screen reader sets it, saved at once. */
    private static void setKnob(MinimumsStarView star, int axis, float dollars) {
        android.os.Bundle value = new android.os.Bundle();
        value.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, dollars);
        assertTrue(star.getAccessibilityNodeProvider().performAction(axis,
                android.R.id.accessibilityActionSetProgress, value));
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void theStarShowsSetAgainstAdaptiveMinimumsAsTheyAreSet() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.isShown());
            // What the example offer needs is heard first; the page shows no words for it.
            assertEquals("An offer like 20 min · 5 mi · 2 stops needs $14.21. "
                    + "Minimums, set and adaptive now. Pay: set $7.00, adaptive more than $14.20. "
                    + "Minimum final-stop hotspot proximity, off. Final-stop distance to nearest hotspot unavailable. "
                    + "Automatic measurement unavailable: the app cannot read the final stop and actual hotspots. Tap for details. "
                    + "No adaptive minimum on this spoke. "
                    + "Per mile: set $1.50, adaptive $2.37. Per minute: set $0.30, adaptive $0.59. "
                    + "Minimum per item, off. Item rule not applicable: no shopping or items shown. "
                    + "Based on the observed total items, not unique products; no learned item minimum yet. "
                    + "Per stop: set $1.00, adaptive $7.10. "
                    + "The hotspot spoke uses inverse miles: closer is farther out; "
                    + "its 1 per mile shares the $10 ring radius for display only. "
                    + "Farther out means higher payout or pay rates, or a final stop nearer the hotspot. "
                    + "Solid blue is your set minimums; dashed purple is learned minimums; colored shapes are offers. "
                    + "Drag the percentage sideways to scale all minimums without changing those saved values.",
                    star.getContentDescription().toString());
            // No offers yet, so the example is a typical one; the largest ask is "more than $14.20".
            // The per-stop spoke holds the set minimum as the example's 2 stops × $1.00, beside the adaptive 2 × $7.10.
            assertEquals(200, star.setAsks(3), 0);
            assertEquals(1420, star.learnedAsks(3), 0);

            // A per-stop minimum is a floor like the others: $8.00 a stop makes the example need $16.00.
            setKnob(star, 3, 8f);
            assertEquals(800, FilterStore.load(app).perStopCents);
            assertEquals(1600, star.setAsks(3), 0);
            assertTrue(star.getContentDescription().toString().contains("Per stop: set $8.00, adaptive $7.10."));
            assertTrue(star.getContentDescription().toString()
                    .startsWith("An offer like 20 min · 5 mi · 2 stops needs $16.00."));
            setKnob(star, 3, 0f);
            assertTrue(Double.isNaN(star.setAsks(3)));
            assertTrue(star.getContentDescription().toString()
                    .contains("Per stop: no set minimum, adaptive $7.10."));

            setKnob(star, 1, 0f);
            assertTrue(star.getContentDescription().toString()
                    .contains("Per mile: no set minimum, adaptive $2.37."));
            // The adaptive minimum's toggle by the constellation turns it off, saved at once.
            assertTrue(act(star, MinimumsStarView.ADAPTIVE_ID,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            assertTrue(star.getContentDescription().toString()
                    .contains("Adaptive minimum is off, so the adaptive values are not applied."));
            assertFalse(FilterStore.load(app).risingOffers);
            // What it learned stays.
            assertEquals(0, FilterStore.load(app).perMileCents);
            assertEquals("$2.37", FilterStore.load(app).best.perMile());
        }
    }

    @Test
    public void theStarMarksRecentOffersAndWhatAManualDeclineTaught() {
        FilterStore.save(app, new FilterSettings(true, 700, 0, 0, 0, 0, true, 0));
        // Declined by hand: pay came closest to catching it, so later offers must beat its $9.00.
        FilterStore.learnFromDecline(app, new OfferSnapshot(900, 3.0, 12, 2));
        DecisionLog.record(app, declinedEntry());
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() + 60_000,
                DecisionLog.Source.SCREEN, false, new OfferSnapshot(2500, 9.1, 30, 3), 2000,
                OfferRule.Result.KEEP, "meets enabled rules", DecisionLog.Action.PASSES, true,
                Collections.emptyList()));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            String description = star.getContentDescription().toString();
            assertTrue(description, description.contains("Pay: set $7.00, adaptive more than $9.00."));
            assertTrue(description, description.endsWith("The constellation shows the latest or selected offer. "
                    + "Choose an older offer on the skyline."));
            DecisionChartView chart = findChart(content);
            assertTrue(chart.getContentDescription().toString().startsWith(
                    "Chart of the last 2 offers: 1 passed, 1 declined, 0 need review."));
            assertEquals(Integer.valueOf(2500), chart.selectedEntry().facts.payCents);
            chart.select(0);
            assertEquals("history stays selectable", Integer.valueOf(790), chart.selectedEntry().facts.payCents);
            assertEquals("selection preserves the learned payout floor", 901, star.learnedAsks(0), 0);
        }
    }

    @Test
    public void acceptedOffersOnlyRaiseTheAdaptiveMinimumsUntilReset() {
        // Nothing is learned while the adaptive minimum is off, or while auto-decline is paused.
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(3000, 6.0, 24, 2));
        FilterStore.save(app, new FilterSettings(false, 1000, 0, 0, 0, 0, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(3000, 6.0, 24, 2));
        assertTrue(FilterStore.load(app).best.isEmpty());
        assertEquals("nothing is learned while off or paused, the pay included", 0,
                FilterStore.load(app).lastAcceptedCents);

        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        FilterStore.recordAccepted(app, new OfferSnapshot(900, 6.0, 24, 2));
        FilterSettings saved = FilterStore.load(app);
        assertEquals("a lower offer accepted later does not lower the pay minimum", 1420, saved.lastAcceptedCents);
        assertEquals("nor any best rate", "$0.59/min, $2.37/mi, $7.10/stop", saved.best.summary());
        // Pausing, turning the adaptive minimum off and on, and accepting meanwhile keep what was learned.
        FilterStore.save(app, saved.withEnabled(false));
        FilterStore.recordAccepted(app, new OfferSnapshot(600, 6.0, 24, 2));
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, false, 0));
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        assertEquals(1420, FilterStore.load(app).lastAcceptedCents);
        assertEquals("$0.59/min, $2.37/mi, $7.10/stop", FilterStore.load(app).best.summary());

        // Saving rules keeps what was learned; Reset starts over.
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        assertEquals("$0.59/min, $2.37/mi, $7.10/stop", FilterStore.load(app).best.summary());
        FilterStore.resetAccepted(app);
        assertTrue(FilterStore.load(app).best.isEmpty());
        assertEquals(0, FilterStore.load(app).lastAcceptedCents);
    }

    @Test
    public void anOrderAcceptedWhileThePageIsOpenShowsOnTheStarWithoutWaitingForAnotherOffer() {
        FilterStore.save(app, new FilterSettings(true, 1000, 0, 0, 0, 0, true, 0));
        FilterStore.resetAccepted(app);
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            MinimumsStarView star = find(activity.get().findViewById(android.R.id.content), MinimumsStarView.class);
            assertTrue(star.getContentDescription().toString().contains("Per mile: no set minimum, no adaptive minimum yet"));

            // Accepted in Dasher while Offer Filter stays open behind it; no new offer has come in since.
            FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            String described = star.getContentDescription().toString();
            assertTrue(described, described.contains("Pay: set $10.00, adaptive more than $14.20"));
            assertTrue(described, described.contains("Per mile: no set minimum, adaptive $2.37."));
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherTheMascotAndTheConstellationBehindItEachTakeTheirOwnTaps() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        FilterStore.save(app, new FilterSettings(true, 2000, 150, 0, 0, 0));
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
            assertTrue("the mascot and its counts float over the constellation", mascot.placed());
            float middleX = star.skyX();
            float middleY = star.skyY();
            assertTrue("the upper-left mascot leaves the plot center clear",
                    Math.hypot(middleX - mascot.getLeft() - mascot.mascotX(),
                            middleY - mascot.getTop() - mascot.mascotY()) > mascot.mascotRadius());

            tap(sky, mascot.getLeft() + mascot.mascotX(), mascot.getTop() + mascot.mascotY());
            assertFalse("a tap on the mascot pauses", FilterStore.load(app).enabled);
            assertFalse(settingsShown(content));

            tap(sky, middleX, middleY);
            assertFalse("a tap on the constellation is the constellation's: it does not resume",
                    FilterStore.load(app).enabled);
            assertFalse("and opens no page", settingsShown(content));
            assertArrayEquals("nor sets anything", new int[] {2000, 150, 0, 0, 0, 0}, FilterStore.load(app).minimums());
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherSixSpokesFitTheHeightAndALineDoesNotShrinkThem() throws Exception {
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
            layOut(content);
            Ui ui = new Ui(app);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            ScenePage scene = find(content, ScenePage.class);
            View sky = (View) star.getParent();
            assertNull("nothing to fix yet", shownTextContaining(content, "Background offers are off"));
            float radius = star.skyRadius();
            int width = sky.getWidth();
            assertTrue("its middle a little right of the page's", star.skyX() > width / 2f);
            android.graphics.RectF counts = new android.graphics.RectF();
            mascot.countsAt(counts);
            counts.offset(mascot.getLeft(), mascot.getTop());
            // The upright fifth spoke makes height, not width, the limiting dimension in a short window.
            assertTrue("the full upright spoke fits below the counts",
                    star.skyY() - radius >= counts.bottom);
            assertNotNull("the fifth knob stays available", star.knobAt(AreaScore.HOTSPOT));
            List<android.graphics.RectF> icons = new ArrayList<>();
            star.iconsAt(icons);
            java.lang.reflect.Method iconAt = MinimumsStarView.class.getDeclaredMethod("skyIcon", int.class,
                    float.class, float.class, float.class, android.graphics.RectF.class);
            iconAt.setAccessible(true);
            for (int axis = 0; axis < AreaScore.AXES; axis++) {
                android.graphics.RectF box = new android.graphics.RectF();
                boolean visible = (Boolean) iconAt.invoke(star, axis, star.skyX(), star.skyY(), radius, box);
                assertTrue("axis " + axis + " absent at " + box + "; sky=" + star.getWidth() + "x"
                        + star.getHeight() + ", center=" + star.skyX() + "," + star.skyY()
                        + ", radius=" + radius + ", adaptive=" + star.adaptiveBox()
                        + ", stops=" + star.stopsBox(), visible);
            }
            assertEquals("the six icons, the max stops badge and the adaptive minimum's toggle; adaptive="
                    + star.adaptiveBox() + "; stops=" + star.stopsBox(), 8, icons.size());
            for (android.graphics.RectF icon : icons) {
                assertTrue("each icon inside the page: " + icon, icon.left >= 0 && icon.right <= width);
                assertFalse("and clear of the counts: " + icon, android.graphics.RectF.intersects(icon, counts));
            }
            assertFalse("the rings' dollars are on the page", star.levelWords().isEmpty());
            List<android.graphics.RectF> words = scene.words();
            assertTrue("the scene knows the counts are words, so no star lands on them",
                    containsPoint(words, counts.centerX() + sky.getLeft(), counts.centerY() + sky.getTop()));

            // Background offers go off: the line asking for a fix crosses the circle's lower part; the circle keeps
            // its size, and the mascot stays above the line.
            listener.destroy();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            layOut(content);
            TextView problem = shownTextContaining(content, "Background offers are off");
            assertNotNull(problem);
            View row = (View) problem.getParent();
            assertEquals("a line does not shrink the constellation", radius, star.skyRadius(), 1f);
            int[] rowAt = new int[2];
            int[] skyAt = new int[2];
            row.getLocationInWindow(rowAt);
            sky.getLocationInWindow(skyAt);
            float rowTop = rowAt[1] - skyAt[1];
            assertTrue("the mascot stands above the line", mascot.getTop() + mascot.mascotY() < rowTop);
            android.graphics.RectF rowBox = new android.graphics.RectF(rowAt[0] - skyAt[0], rowTop,
                    rowAt[0] - skyAt[0] + row.getWidth(), rowTop + row.getHeight());
            icons.clear();
            star.iconsAt(icons);
            assertEquals("every icon still shows, the lower ones stepped above their spokes' ends (and the badge and "
                    + "the toggle)", 8, icons.size());
            for (android.graphics.RectF icon : icons) {
                assertTrue("each icon inside the page: " + icon, icon.left >= 0 && icon.right <= width);
                assertFalse("and clear of the line: " + icon + " / " + rowBox,
                        android.graphics.RectF.intersects(icon, rowBox));
            }
            assertTrue("the scene knows the line is words too", containsPoint(scene.words(),
                    rowAt[0] + row.getWidth() / 2f - skyAt[0] + sky.getLeft(), rowTop + row.getHeight() / 2f
                            + sky.getTop()));
        } finally {
            if (OfferNotificationService.isConnected()) listener.destroy();
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherOnAFreshPageThreeLinesCrossTheConstellationAndTheMascotRisesAboveThem() {
        FilterStore.save(app, new FilterSettings(false, 0, 0, 0, 0, 0));
        Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class)).setNotificationsEnabled(false);
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            layOut(content);
            Ui ui = new Ui(app);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            View sky = (View) star.getParent();
            int[] skyAt = new int[2];
            sky.getLocationInWindow(skyAt);
            float linesTop = Float.MAX_VALUE;
            for (String line : new String[] {MainActivity.START_HINT, "Background offers are off",
                    "Alerts are blocked"}) {
                TextView shown = shownTextContaining(content, line);
                assertNotNull(line, shown);
                int[] at = new int[2];
                shown.getLocationInWindow(at);
                linesTop = Math.min(linesTop, at[1] - skyAt[1]);
            }
            assertNotNull("the fifth knob stays available with all three lines", star.knobAt(AreaScore.HOTSPOT));
            assertTrue("the upright spoke stays inside the short window", star.skyY() - star.skyRadius() >= 0);
            assertTrue("they cross its lower part", star.skyY() + star.skyRadius() > linesTop);
            float mascotY = mascot.getTop() + mascot.mascotY();
            float mascotX = mascot.getLeft() + mascot.mascotX();
            assertTrue("the mascot rises above them", mascotY + mascot.mascotRadius() <= linesTop);
            android.graphics.RectF counts = new android.graphics.RectF();
            mascot.countsAt(counts);
            counts.offset(mascot.getLeft(), mascot.getTop());
            assertTrue("and stays below the counts", mascotY - mascot.mascotRadius() >= counts.bottom);
            assertTrue("inside the page", mascotX - mascot.mascotRadius() >= 0);
            // Above and left of the plot, clear of the finite upper spoke actually drawn. Its extension beyond
            // the outer ring no longer forces the mascot back down beside the graph's center.
            double spread = Math.toRadians(MinimumsStarView.SPREAD);
            double dx = -star.skyRadius() * Math.cos(spread);
            double dy = -star.skyRadius() * Math.sin(spread);
            double projection = Math.max(0, Math.min(1,
                    ((mascotX - star.skyX()) * dx + (mascotY - star.skyY()) * dy) / (dx * dx + dy * dy)));
            double fromUpperSpoke = Math.hypot(mascotX - star.skyX() - projection * dx,
                    mascotY - star.skyY() - projection * dy);
            assertTrue("clear of the upper left spoke: " + fromUpperSpoke,
                    fromUpperSpoke >= mascot.mascotRadius() + ui.dp(8));
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void aKnobDraggedAlongItsSpokeSavesTheSnappedMinimum() {
        FilterStore.save(app, new FilterSettings(true, 1000, 80, 30, 100, 3, true, 0));
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
            assertArrayEquals("the other minimums untouched", new int[] {1000, expected, 30, 100, 0, 0}, saved.minimums());
            assertTrue(saved.risingOffers);
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
            tap(sky, middle[0] + star.skyRadius() * 0.85f, middle[1]);
            assertFalse(settingsShown(content));
            settleSky(content);
            float[] pay = star.knobAt(0);
            tap(sky, pay[0], pay[1]);
            assertFalse(settingsShown(content));
            assertEquals("a tap sets nothing", 1000, FilterStore.load(app).flatCents);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void theRingsHoldStillWhileAKnobMovesAndGrowToFitItWhenLetGo() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 0));
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
                        "meets enabled rules", DecisionLog.Action.PASSES, true, Collections.emptyList()));
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
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 0, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {700, 150, 30, 0}));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            android.view.accessibility.AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            assertNotNull("each knob is its own control", nodes);
            android.view.accessibility.AccessibilityNodeInfo host = nodes.createAccessibilityNodeInfo(
                    android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID);
            assertEquals("six knobs, the max stops badge, the adaptive minimum's toggle, the adopt button and the "
                    + "score by area toggle", 10, host.getChildCount());
            android.view.accessibility.AccessibilityNodeInfo mile = nodes.createAccessibilityNodeInfo(1);
            assertEquals("Minimum per mile, $1.50; adaptive $2.37, learned", mile.getContentDescription().toString());
            assertEquals(android.widget.SeekBar.class.getName(), mile.getClassName().toString());
            assertTrue("a screen reader stops on it, not taking it for part of the clickable chart",
                    mile.isFocusable());
            if (Build.VERSION.SDK_INT >= 28) assertTrue(mile.isScreenReaderFocusable());
            assertEquals(1.5f, mile.getRangeInfo().getCurrent(), 0.001f);
            assertEquals(1000f, mile.getRangeInfo().getMax(), 0.001f);
            assertTrue(mile.getActionList().contains(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD));
            assertEquals("Minimum per stop, off; adaptive $7.10, learned",
                    nodes.createAccessibilityNodeInfo(3).getContentDescription().toString());
            assertFalse("an off knob steps only up", nodes.createAccessibilityNodeInfo(3).getActionList().contains(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD));

            assertTrue(nodes.performAction(1, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    null));
            assertEquals("one $0.05 step, saved", 155, FilterStore.load(app).perMileCents);
            assertEquals("Minimum per mile, $1.55; adaptive $2.37, learned", star.lastSaid());
            assertTrue(nodes.performAction(1, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    null));
            assertTrue(nodes.performAction(1, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    null));
            assertEquals(145, FilterStore.load(app).perMileCents);
            assertTrue(nodes.performAction(0, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    null));
            assertEquals("pay steps $0.50", 750, FilterStore.load(app).flatCents);
            assertTrue(nodes.performAction(2, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    null));
            assertEquals("per minute steps $0.01", 29, FilterStore.load(app).perMinuteCents);
            assertTrue(nodes.performAction(3, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    null));
            assertEquals("per stop steps $0.25", 25, FilterStore.load(app).perStopCents);
            assertEquals("Minimum per stop, $0.25; adaptive $7.10, learned", star.lastSaid());
            FilterSettings saved = FilterStore.load(app);
            assertTrue(saved.enabled);
            assertEquals(3, saved.maxStops);
            assertEquals("what was learned stays", "$0.59/min, $2.37/mi, $7.10/stop", saved.best.summary());
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherOneButtonMakesTheLearnedMinimumsTheSetOnesAndUndoesIt() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
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
            assertNull("nothing learned yet, so no button", star.adoptBox());
            android.view.accessibility.AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            assertEquals("six knobs, the badge, the two toggles and the offer marked", 10,
                    nodes.createAccessibilityNodeInfo(
                            android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID).getChildCount());

            // An accepted offer teaches the adaptive minimums a best rate: now they ask more than the set ones.
            FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
            settleSky(content);
            android.graphics.RectF button = star.adoptBox();
            assertNotNull("the learned minimums can be made the set ones", button);
            assertEquals(MinimumsStarView.ADOPT_SAID,
                    nodes.createAccessibilityNodeInfo(MinimumsStarView.ADOPT_ID).getContentDescription().toString());
            assertTrue("a full touch target", button.width() >= new Ui(app).dp(48) - 1);
            for (int axis = 0; axis < AreaScore.AXES; axis++) {
                float[] knob = star.knobAt(axis);
                assertTrue("clear of the knobs", Math.hypot(knob[0] - button.centerX(),
                        knob[1] - button.centerY()) >= new Ui(app).dp(24) + button.width() / 2 - 1);
            }
            List<android.graphics.RectF> icons = new ArrayList<>();
            star.iconsAt(icons);
            assertTrue("the scene keeps its clouds and stars off it",
                    containsPoint(icons, button.centerX(), button.centerY()));

            ViewGroup sky = (ViewGroup) star.getParent();
            tap(sky, button.centerX(), button.centerY());
            FilterSettings adopted = FilterStore.load(app);
            assertArrayEquals("pay beats $14.20, the rates match $2.366…/mi, $0.591…/min and $7.10/stop",
                    new int[] {1421, 237, 60, 710, 0, 0}, adopted.minimums());
            assertTrue("still on", adopted.enabled);
            assertEquals(3, adopted.maxStops);
            assertTrue("the adaptive minimum stays on, keeping what it learned",
                    adopted.risingOffers && adopted.lastAcceptedCents == 1420);
            assertTrue(star.lastSaid(), star.lastSaid().contains(
                    "Pay $14.21, per mile $2.37, per minute $0.60, per stop $7.10"));
            assertFalse("no page opens", settingsShown(content));
            assertTrue("the button offers Undo", star.offeringUndo());
            assertEquals("Undo",
                    nodes.createAccessibilityNodeInfo(MinimumsStarView.ADOPT_ID).getContentDescription().toString());
            settleSky(content);
            assertEquals("Undo stays under the finger, though the set shape grew", button, star.adoptBox());

            // Undo puts the four back exactly.
            button = star.adoptBox();
            tap(sky, button.centerX(), button.centerY());
            assertArrayEquals(new int[] {700, 150, 30, 100, 0, 0}, FilterStore.load(app).minimums());
            assertFalse(star.offeringUndo());
            assertTrue(FilterStore.load(app).enabled);

            // Adopted again, by a screen reader; Undo is offered for eight seconds, then the button goes.
            assertTrue(nodes.performAction(MinimumsStarView.ADOPT_ID,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
            assertEquals(237, FilterStore.load(app).perMileCents);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(7000));
            assertTrue(star.offeringUndo());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
            assertFalse(star.offeringUndo());
            assertNull("set minimums no looser than the learned ones: no button", star.adoptBox());
            assertEquals("the knobs, the badge, the toggles and the offer", 10, nodes.createAccessibilityNodeInfo(
                    android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID).getChildCount());
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void undoIsWithdrawnOnceAKnobChangesTheAdoptedMinimums() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        try (ActivityController<MainActivity> activity =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            android.view.accessibility.AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            assertTrue(nodes.performAction(MinimumsStarView.ADOPT_ID,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
            assertTrue(star.offeringUndo());
            assertTrue(nodes.performAction(1, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    null));
            assertEquals("the next $0.05 step up from $2.37", 240, FilterStore.load(app).perMileCents);
            assertFalse("Undo would put back more than the adoption now", star.offeringUndo());
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void inAShortSplitWithAnotherAppTheHeaderChartHasNoKnobsOrButton() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        OfferFilterService.sawDasherBeside(0);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue("up in the header", star.beside());
            assertNull("no knobs", star.knobAt(1));
            assertNull("no button, though the learned minimums ask more", star.adoptBox());
            assertNull("no knobs for screen readers either", star.getAccessibilityNodeProvider());
            // A drag across it sets nothing; a tap spreads it across the sky, with its knobs.
            int[] at = new int[2];
            star.getLocationInWindow(at);
            float x = star.getWidth() * 0.6f;
            float y = star.getHeight() / 2f;
            long now = android.os.SystemClock.uptimeMillis();
            star.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
            star.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_MOVE, x + 60, y - 30, 0));
            star.dispatchTouchEvent(MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, x + 60, y - 30, 0));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertArrayEquals(new int[] {700, 150, 30, 100, 0, 0}, FilterStore.load(app).minimums());
            tap((ViewGroup) star.getParent(), star.getLeft() + x, star.getTop() + y);
            settleSky(content);
            assertFalse("no page opens", settingsShown(content));
            assertFalse("out of the header", star.beside());
            assertTrue("into the sky", star.backdrop());
            assertNotNull("with its knobs", star.knobAt(1));
            assertNotNull(star.adoptBox());
            assertEquals("the map makes room", View.GONE, find(content, AreaMapView.class).getVisibility());

            // A tap on its circle, away from everything on it, puts it back in the header with the map.
            ViewGroup sky = (ViewGroup) star.getParent();
            tap(sky, star.skyX() + star.skyRadius() * 0.5f, star.skyY());
            settleSky(content);
            assertTrue("back in the header", star.beside());
            assertEquals(View.VISIBLE, find(content, AreaMapView.class).getVisibility());
            assertArrayEquals(new int[] {700, 150, 30, 100, 0, 0}, FilterStore.load(app).minimums());
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void aKnobKeepsItsExactValueUntilMovedHalfAStepAlongItsSpoke() {
        // Adopted minimums sit between steps: $14.21, $2.37/mi, $0.60/min, $7.10/stop.
        int[] adopted = {1421, 237, 60, 710, 0, 0};
        FilterStore.save(app, new FilterSettings(true, 1421, 237, 60, 710, 3, true, 0));
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
                assertArrayEquals(adopted, FilterStore.load(app).minimums());
                assertFalse("nor a tap", settingsShown(content));
            }

            // Out along the spoke and back to where it was taken: $14.21 stays as it is, not snapped to $14.00.
            settleSky(content);
            float[] pay = star.knobAt(0);
            dragThrough(content, new float[][] {pay, alongSpoke(pay, 0, ui.dp(30), 0), pay},
                    () -> assertTrue(star.dragging()));
            assertArrayEquals(adopted, FilterStore.load(app).minimums());

            // In past half a step ($0.25) from $14.21: the step below, $14.00, is in reach.
            settleSky(content);
            pay = star.knobAt(0);
            float perCent = star.skyRadius() / (star.ringCents() * 3f);
            dragThrough(content, new float[][] {pay, alongSpoke(pay, 0, -ui.dp(30), 0),
                    alongSpoke(pay, 0, -31 * perCent, 0)}, null);
            assertEquals(1400, FilterStore.load(app).flatCents);
            assertArrayEquals("nothing else moved", new int[] {1400, 237, 60, 710, 0, 0}, FilterStore.load(app).minimums());
        }
    }

    @Test
    public void inAPageThatScrollsASwipeFromAKnobScrollsAndASlideAcrossItSetsNothing() {
        try (ActivityController<android.app.Activity> built =
                Robolectric.buildActivity(android.app.Activity.class).setup()) {
            LoneSky page = new LoneSky(built.get(), new FilterSettings(true, 725, 150, 30, 0, 0));
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
            // $1.00 a stop asks $2 of the example's 2 stops, on rings reaching $21: inside the resting place.
            LoneSky page = new LoneSky(built.get(), new FilterSettings(true, 2000, 0, 0, 100, 0));
            Ui ui = page.ui;
            MinimumsStarView star = page.star;
            float[] stop = star.knobAt(3);
            float[] middle = {star.skyX(), star.skyY()};
            assertTrue("it rests inside the resting place",
                    Math.hypot(stop[0] - middle[0], stop[1] - middle[1]) < ui.dp(MinimumsStarView.KNOB_REST_DP));

            // Sideways past the slop: nothing set, and the rule is not turned off.
            float past = page.slop + ui.dp(4);
            page.swipe(stop, alongSpoke(stop, 3, 0, past));
            assertTrue("nothing saved: " + page.saves, page.saves.isEmpty());
            page.scroll.scrollTo(0, 0);
            page.layOut();
            // Out along its spoke and back to where it was: still nothing.
            page.drag(new float[][] {stop, alongSpoke(stop, 3, past, 0), stop});
            assertTrue("nothing saved: " + page.saves, page.saves.isEmpty());

            // Out, then back in to 9 dp inside where it was (less than a clear 12 dp): it stays on, a step lower.
            page.drag(new float[][] {stop, alongSpoke(stop, 3, past, 0), alongSpoke(stop, 3, -ui.dp(9), 0)});
            assertEquals(1, page.saves.size());
            assertEquals("3=50", page.saves.get(0));

            // Pushed in through the middle: off.
            stop = star.knobAt(3);
            page.drag(new float[][] {stop, alongSpoke(middle, 3, -ui.dp(16), 0)});
            assertEquals("3=0", page.saves.get(page.saves.size() - 1));
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherAKnobNearPausedTakesItsOwnTouchesAndTheWordStillResumes() {
        // The sixth spoke can move the per-minute knob above the Paused line. The knob must stay clear of
        // its words, and touching or dragging either control must still reach only that control.
        FilterStore.save(app, new FilterSettings(false, 2000, 150, 75, 100, 0));
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
            ViewGroup sky = (ViewGroup) star.getParent();
            assertNotNull(shownTextContaining(content, "Background offers are off"));
            TextView paused = findText(content, "Paused");
            assertNotNull(paused);
            int[] wordAt = new int[2];
            int[] skyAt = new int[2];
            paused.getLocationInWindow(wordAt);
            sky.getLocationInWindow(skyAt);
            float wordLeft = wordAt[0] - skyAt[0];
            float wordTop = wordAt[1] - skyAt[1];
            float[] knob = star.knobAt(2);
            assertNotNull(knob);
            android.graphics.RectF wordBounds = new android.graphics.RectF(wordLeft, wordTop,
                    wordLeft + paused.getWidth(), wordTop + paused.getHeight());
            wordBounds.inset(-new Ui(app).dp(10), -new Ui(app).dp(10));
            assertFalse("the painted knob does not cover the Paused word", wordBounds.contains(knob[0], knob[1]));
            assertTrue("beside the word, not under it: " + knob[0] + " / " + wordLeft + "+" + paused.getWidth(),
                    knob[0] > wordLeft + paused.getWidth());

            tap(sky, knob[0], knob[1]);
            assertFalse("a tap on the knob does not resume", FilterStore.load(app).enabled);
            assertFalse("and opens no page", settingsShown(content));
            settleSky(content);
            knob = star.knobAt(2);
            dragKnob(content, star, knob, alongSpoke(knob, 2, new Ui(app).dp(30), 0), null);
            assertTrue("a drag from it sets the per-minute minimum: " + FilterStore.load(app).perMinuteCents,
                    FilterStore.load(app).perMinuteCents > 75);
            assertFalse("still paused", FilterStore.load(app).enabled);

            // The word itself still resumes.
            settleSky(content);
            paused = findText(content, "Paused");
            paused.getLocationInWindow(wordAt);
            tap(sky, wordAt[0] - skyAt[0] + paused.getWidth() / 2f, wordAt[1] - skyAt[1] + paused.getHeight() / 2f);
            assertTrue(FilterStore.load(app).enabled);
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void undoHoldsWhileAScreenReaderIsOnItAndItsFocusGoesWithTheButton() {
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        org.robolectric.shadows.ShadowAccessibilityManager reader = Shadows.shadowOf(
                app.getSystemService(android.view.accessibility.AccessibilityManager.class));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            reader.setEnabled(true);
            reader.setTouchExplorationEnabled(true);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            android.view.accessibility.AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            int adopt = MinimumsStarView.ADOPT_ID;
            assertTrue(nodes.performAction(adopt,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
            assertTrue(nodes.performAction(adopt, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                    null));
            assertTrue("what to do comes first: " + star.lastSaid(),
                    star.lastSaid().startsWith("Learned minimums set. Double-tap to undo."));
            assertTrue(star.lastSaid(), star.lastSaid().contains("Add-ons are held to these too."));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3 * MinimumsStarView.UNDO_MS));
            assertTrue("Undo holds while the screen reader is on it", star.offeringUndo());

            // Moved on: Undo's time starts over (Android before 10 has no timeout to ask, so it waits for a change).
            assertTrue(nodes.performAction(adopt,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MinimumsStarView.UNDO_MS + 500));
            assertEquals(Build.VERSION.SDK_INT < 29, star.offeringUndo());

            // Back on it, a knob changes the adopted minimums: Undo ends, the button goes, and so does the focus.
            if (star.offeringUndo()) {
                assertTrue(nodes.performAction(adopt,
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
                assertEquals(adopt, star.focusedNode());
                assertTrue(nodes.performAction(1,
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
                assertFalse(star.offeringUndo());
                assertNull(star.adoptBox());
                assertEquals("no focus left on a button that is gone", -1, star.focusedNode());
            }
        } finally {
            reader.setTouchExplorationEnabled(false);
            reader.setEnabled(false);
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void adoptingWorksFromTheSavedMinimumsAndRaisesOnlyThoseItBeats() {
        FilterStore.save(app, new FilterSettings(true, 1000, 300, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
        FilterStore.save(app, FilterStore.load(app).withMinimums(new int[] {1000, 300, 30, 100}));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            android.view.accessibility.AccessibilityNodeProvider nodes = star.getAccessibilityNodeProvider();
            assertTrue(nodes.performAction(MinimumsStarView.ADOPT_ID,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
            FilterSettings adopted = FilterStore.load(app);
            assertArrayEquals("pay from the saved $10 to beat $14.20; the saved $3.00/mi was already above $2.37",
                    new int[] {1421, 300, 60, 710, 0, 0}, adopted.minimums());

            assertTrue(nodes.performAction(MinimumsStarView.ADOPT_ID,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
            assertArrayEquals("Undo puts back the saved minimums it replaced", new int[] {1000, 300, 30, 100, 0, 0},
                    FilterStore.load(app).minimums());

            // With the saved adaptive minimum off there is no button: it follows the saved rules.
            FilterStore.save(app, FilterStore.load(app).withAdaptive(false));
            activity.get().onPause();
            activity.get().onResume();
            settleSky(content);
            assertNull("the saved adaptive minimum is off", star.adoptBox());
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    public void aFreshPageHasSixHollowKnobsAndAFirstRuleSetByOneStaysPaused() {
        FilterStore.save(app, new FilterSettings(false, 0, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.backdrop());
            float[] middle = {star.skyX(), star.skyY()};
            for (int axis = 0; axis < AreaScore.AXES; axis++) {
                float[] knob = star.knobAt(axis);
                assertNotNull("a hollow knob on every spoke", knob);
                assertEquals("resting just outside the middle", new Ui(app).dp(MinimumsStarView.KNOB_REST_DP),
                        Math.hypot(knob[0] - middle[0], knob[1] - middle[1]), 1);
            }
            assertEquals("screen readers find the six knobs, the badge and the adaptive minimum's toggle", 8,
                    star.getAccessibilityNodeProvider().createAccessibilityNodeInfo(
                            android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID).getChildCount());
            assertNotNull(shownTextContaining(content, MainActivity.START_HINT));

            // One dragged out saves the first rule.
            float[] mile = star.knobAt(1);
            dragKnob(content, star, mile, alongSpoke(mile, 1, new Ui(app).dp(60), 0), null);
            FilterSettings saved = FilterStore.load(app);
            assertTrue("the per-mile minimum is saved: " + saved.perMileCents, saved.perMileCents > 0);
            assertEquals("only it", 0, saved.flatCents);
            assertFalse("auto-decline stays paused", saved.enabled);
            assertEquals("Rule saved. Tap the mascot to turn on auto-decline.",
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
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
                Long.MAX_VALUE, OfferRule.Result.DECLINE, "dollars per minute", DecisionLog.Action.DECLINE_TAPPED,
                true, Collections.emptyList()));
        chart.setEntries(entries);
        chart.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, 1000, 400);
        chart.draw(new Canvas(Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)));
        assertTrue(chart.getContentDescription().toString().startsWith("Chart of the last 3 offers: 0 passed, 2 declined, 1 need review."));
    }
}
