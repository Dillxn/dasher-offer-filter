package com.local.dasherfilter;

import android.Manifest;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.time.Duration;
import java.util.List;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The main page through real Android adapters: its scene and layout at each window size and beside Dasher, the
 * ticket, the map and its best area, and the mascot.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AndroidAdapterHomepageTest extends AndroidAdapterTestBase {
    @Test
    public void theNewestOfferIsABuildingThatUnfoldsIntoAStampedTicket() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull("no line of words under the skyline", shownTextContaining(content, "Below your per-mile minimum"));
            assertNull("the ticket stays folded until asked for", find(content, OfferCardView.class));

            openTicket(content);
            OfferCardView card = find(content, OfferCardView.class);
            assertTrue(card.isShown());
            assertEquals("Paid $7.90, needed $10.80. 7.2 mi · 21 min · 2 stops",
                    card.getContentDescription().toString());
            assertEquals("Declined", find(content, Decor.Stamp.class).getContentDescription().toString());
            assertNotNull(shownTextContaining(content, "Below your per-mile minimum"));

            // While no older offer is picked, the ticket follows each new one.
            DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() + 60_000,
                    DecisionLog.Source.SCREEN, false, new OfferSnapshot(2500, 9.1, 30, 3), 2000,
                    OfferRule.Result.KEEP, "meets your minimums", DecisionLog.Action.PASSES, true,
                    Collections.emptyList()));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
            assertEquals("Passed", find(content, Decor.Stamp.class).getContentDescription().toString());
            assertNotNull(shownTextContaining(content, "Meets your minimums"));

            // Back folds the ticket away.
            activity.get().onBackPressed();
            assertNull(find(content, OfferCardView.class));
        }
    }

    @Test
    public void theFilterPictureShowsThisDashWithAllTimeTotalsAndTheState() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        // One offer from hours before this dash began.
        Dashing.forgetCache();
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() - 3 * 3_600_000L,
                DecisionLog.Source.SCREEN, false, new OfferSnapshot(900, 4.0, 15, 2), 1200,
                OfferRule.Result.DECLINE, "flat minimum", DecisionLog.Action.DECLINE_TAPPED, true,
                Collections.emptyList()));
        // This dash: one declined, one passed.
        Dashing.seen(app);
        DecisionLog.record(app, declinedEntry());
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(2500, 9.1, 30, 3), 2000, OfferRule.Result.KEEP, "meets your minimums",
                DecisionLog.Action.PASSES, true, Collections.emptyList()));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView hero = find(content, FilterHeroView.class);
            assertEquals("Auto-decline on. This dash: 2 offers, 1 passed, 1 filtered, 0 to review. "
                    + "In all: 1 passed, 2 filtered, 0 to review.", hero.getContentDescription().toString());
            assertEquals("the mascot is cheerful while on", Mascot.Mood.HAPPY, hero.mood());

            assertEquals(FilterHeroView.State.ON, hero.state());
            hero.performClick();
            assertTrue(hero.getContentDescription().toString().startsWith("Auto-decline paused."));
            assertEquals(FilterHeroView.State.PAUSED, hero.state());
            assertEquals("and asleep while paused", Mascot.Mood.SLEEPY, hero.mood());
        }
    }

    @Test
    public void theMapIsAlwaysOnTheGroundAndAsksOnlyForApproximateLocation() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue("the map is part of the page from the start", map.isShown());
            assertTrue(map.getContentDescription().toString().startsWith("Tap to allow location"));
            assertNull("nothing is asked before a tap", Shadows.shadowOf(activity.get()).getLastRequestedPermission());
            map.performClick();
            org.robolectric.shadows.ShadowActivity.PermissionsRequest request =
                    Shadows.shadowOf(activity.get()).getLastRequestedPermission();
            assertEquals(Arrays.asList(Manifest.permission.ACCESS_COARSE_LOCATION),
                    Arrays.asList(request.requestedPermissions));

            // Turned off in Settings, the ground stays, and says a tap turns it back on.
            iconButton(content, "Settings").performClick();
            ((Switch) findButton(content, "Offer map")).setChecked(false);
            iconButton(content, "Back").performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertTrue(map.isShown());
            assertTrue(map.getContentDescription().toString().startsWith("Tap to map where offers pay best"));
            map.performClick();
            assertTrue(AreaMap.enabled(app));
        }
    }

    @Test
    public void theTreasureMapRanksAreasByPayPerMileAndShowsTheBest() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        AreaMap.setEnabled(app, true);
        // Three offers near one spot at $2/mi, three near another at $3/mi.
        noteOfferAt(37.7749, -122.4194, 1000, 5.0);
        noteOfferAt(37.7749, -122.4194, 1200, 6.0);
        noteOfferAt(37.7749, -122.4194, 800, 4.0);
        noteOfferAt(37.8149, -122.3794, 1500, 5.0);
        noteOfferAt(37.8149, -122.3794, 1800, 6.0);
        noteOfferAt(37.8149, -122.3794, 1200, 4.0);
        setLocation(37.7749, -122.4194);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue("on the page, no sheet or switch", map.isShown());
            assertTrue(map.getContentDescription().toString().startsWith(
                    "Best paying areas by pay per mile: 1, 3.6 mi NE of you, $3.00/mi over 3 offers; "
                    + "2, Around you, $2.00/mi over 3 offers."));
            TextView best = shownTextContaining(content, "3.6 mi NE · $3.00/mi · 3 offers  ›");
            assertNotNull("the best area is shown until another is picked", best);
            assertTrue(areaLineSaid(content), areaLineSaid(content)
                    .startsWith("#1 · 3.6 mi NE of you · $3.00/mi · 3 offers. Average"));
            assertTrue(areaLineSaid(content).endsWith("Opens it in Maps."));
            best.performClick();
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertTrue(opened.getDataString(), opened.getDataString().startsWith("geo:37.81"));
        }
    }

    @Test
    public void theBestAreaIsFollowedUntilOneIsPicked() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        AreaMap.setEnabled(app, true);
        noteOfferAt(37.7749, -122.4194, 1000, 5.0);
        noteOfferAt(37.7749, -122.4194, 1200, 6.0);
        noteOfferAt(37.7749, -122.4194, 800, 4.0);
        setLocation(37.7749, -122.4194);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertTrue(areaLineSaid(content).startsWith("#1 · Around you"));

            // A better area turns up while the map is open: nothing was picked, so the details follow it.
            noteOfferAt(37.8149, -122.3794, 1500, 5.0);
            noteOfferAt(37.8149, -122.3794, 1800, 6.0);
            noteOfferAt(37.8149, -122.3794, 1200, 4.0);
            setLocation(37.7749, -122.4194);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
            assertTrue(areaLineSaid(content).startsWith("#1 · 3.6 mi NE of you"));
        }
    }

    @Test
    public void aPickedAreaStaysPickedAsTheRankingChanges() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        AreaMap.setEnabled(app, true);
        noteOfferAt(37.7749, -122.4194, 1000, 5.0);
        noteOfferAt(37.7749, -122.4194, 1200, 6.0);
        noteOfferAt(37.7749, -122.4194, 800, 4.0);
        noteOfferAt(37.8149, -122.3794, 1500, 5.0);
        noteOfferAt(37.8149, -122.3794, 1800, 6.0);
        noteOfferAt(37.8149, -122.3794, 1200, 4.0);
        setLocation(37.7749, -122.4194);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            AreaMapView map = find(content, AreaMapView.class);
            map.pick(AreaMap.ranked(AreaMap.cells(app)).get(1));
            assertTrue(areaLineSaid(content).startsWith("#2 · Around you"));

            // An even better area turns up: the picked one stays, now third.
            noteOfferAt(37.7349, -122.4594, 3000, 5.0);
            noteOfferAt(37.7349, -122.4594, 3600, 6.0);
            noteOfferAt(37.7349, -122.4594, 2400, 4.0);
            setLocation(37.7749, -122.4194);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
            assertTrue(areaLineSaid(content).startsWith("#3 · Around you"));
        }
    }

    @Test
    public void theHomepageShowsItIsWatchingOnlyWhileDashing() {
        Dashing.forgetCache();
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            ScenePage scene = find(content, ScenePage.class);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            assertFalse(scene.watching());
            assertFalse(mascot.getContentDescription().toString().contains("watching"));

            // Dasher seen mid-dash, but nothing can watch it yet: not live.
            Dashing.seen(app);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertFalse(scene.watching());

            service.get().onServiceConnected();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertTrue("the searchlights sweep", scene.watching());
            assertTrue("screen readers hear it: " + mascot.getContentDescription(),
                    mascot.getContentDescription().toString().contains(", watching for offers. "));
            assertNull("no words on the page for it", shownTextContaining(content, "Watching for offers"));

            Dashing.ended(app);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertFalse(scene.watching());
            assertFalse(mascot.getContentDescription().toString().contains("watching"));
        } finally {
            service.destroy();
        }
    }

    @Test
    public void theMainPageIsOneSceneWithItsHorizonAtTheSkylinesStreet() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            ScenePage scene = find(content, ScenePage.class);
            DecisionChartView chart = find(content, DecisionChartView.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.AT_MOST));
            content.layout(0, 0, 1080, 2340);
            int[] sceneAt = new int[2];
            int[] chartAt = new int[2];
            int[] starAt = new int[2];
            scene.getLocationInWindow(sceneAt);
            chart.getLocationInWindow(chartAt);
            star.getLocationInWindow(starAt);
            float street = chartAt[1] - sceneAt[1] + chart.getHeight() - new Ui(app).dp(11);
            assertEquals(street, scene.horizonY(), 1f);
            assertTrue("the constellation is in the sky, above the skyline", starAt[1] < chartAt[1]);
            assertEquals("a whole screen has it wholly there", 1f, scene.shown(star), 0f);
            assertNotSame("not in the header", iconDescribed(content, "Settings").getParent(), star.getParent());
            assertTrue("drawn as the sky itself, behind the mascot", star.backdrop());

            // Bitmap drawing of the whole scene works in both themes.
            android.graphics.Bitmap page = android.graphics.Bitmap.createBitmap(1080, Math.max(1, scene.getHeight()),
                    android.graphics.Bitmap.Config.ARGB_8888);
            scene.draw(new android.graphics.Canvas(page));
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    public void theMainPageFitsOneScreenAndTheTicketSlidesUpOverIt() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            int width = content.getResources().getDisplayMetrics().widthPixels;
            int height = content.getResources().getDisplayMetrics().heightPixels;
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, width, height);
            ScenePage scene = find(content, ScenePage.class);
            android.widget.ScrollView page = (android.widget.ScrollView) scene.getParent();
            assertTrue("the whole scene fits: " + scene.getHeight() + " in " + page.getHeight(),
                    scene.getHeight() <= page.getHeight());
            assertTrue(find(content, AreaMapView.class).isShown());

            openTicket(content);
            assertNotNull("the ticket is up", shownTextContaining(content, "Below your per-mile minimum"));
            activity.get().onBackPressed();
            assertFalse(activity.get().isFinishing());
            assertNull("folded again", shownTextContaining(content, "Below your per-mile minimum"));
        }
    }

    /**
     * A short window (a phone on its side, a small split screen) keeps the mascot, the constellation and the map, side
     * by side under the strip: the same page as a tall one, with nothing to tap to swap one for the other (0.5.1 put
     * the constellation in the header there, and a tap traded the map for it).
     */
    @Test
    @Config(qualifiers = "w360dp-h360dp-xxhdpi")
    public void aShortWindowKeepsTheMascotTheConstellationAndTheMapSideBySide() {
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        service.get().onServiceConnected();
        listener.get().onListenerConnected();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            layOut(content);
            ScenePage scene = find(content, ScenePage.class);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue(mascot.isShown());
            assertEquals("the constellation wholly there", 1f, scene.shown(star), 0f);
            assertEquals("the map with it", 1f, scene.shown(map), 0f);
            assertTrue("side by side", star.getRight() <= map.getLeft());
            assertTrue("under the strip and the header",
                    star.getTop() >= iconDescribed(content, "Settings").getBottom());
            assertTrue("its knobs at their least circle at least",
                    star.skyRadius() >= new Ui(app).dp(FluidLayout.RADIUS_LEAST_DP) - 1);
            View title = iconDescribed(content, "Offer Filter");
            assertTrue("the page's name keeps room, so screen readers reach it", title != null && title.getWidth() > 0);
            assertFalse("a tap on it is no button that swaps it", star.isClickable());
        } finally {
            listener.destroy();
            service.destroy();
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void halfOfASplitScreenFitsOneScreenWithEverythingOnIt() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        DecisionLog.record(app, declinedEntry());
        // Screen reading on, as while dashing; background offers still off, so one line asks for a fix. The other
        // half is not Dasher.
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(0);
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            int width = content.getResources().getDisplayMetrics().widthPixels;
            int height = content.getResources().getDisplayMetrics().heightPixels;
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, width, height);
            ScenePage scene = find(content, ScenePage.class);
            android.widget.ScrollView page = (android.widget.ScrollView) scene.getParent();
            assertTrue("no scrolling: " + scene.getHeight() + " in " + page.getHeight(),
                    scene.getHeight() <= page.getHeight());
            assertNotNull("the line asking for a fix", shownTextContaining(content, SetupChecklist.NOTIFICATIONS));
            MinimumsStarView star = find(content, MinimumsStarView.class);
            AreaMapView map = find(content, AreaMapView.class);
            assertEquals("the constellation wholly there", 1f, scene.shown(star), 0f);
            assertEquals("the map with it", 1f, scene.shown(map), 0f);
            assertTrue("the map keeps its readable least", map.getHeight() >= new Ui(app).dp(84));
            DecisionChartView chart = findChart(content);
            assertTrue("the skyline is either whole at its least or on its way out, never squeezed in use",
                    scene.shown(chart) < 1 || chart.getHeight() >= new Ui(app).dp(FluidLayout.CHART_LEAST_DP) - 1);
            // The skyline's street is still the horizon, above the map.
            int[] mapAt = new int[2];
            int[] sceneAt = new int[2];
            map.getLocationInWindow(mapAt);
            scene.getLocationInWindow(sceneAt);
            assertTrue("the horizon is above the map", scene.horizonY() <= mapAt[1] - sceneAt[1]);
        } finally {
            service.destroy();
        }
    }

    /**
     * Beside Dasher the page keeps its own map with the constellation (0.5.1 hid the map there, and the owner did not
     * like that it showed only one of them), and Dasher leaving the other half changes nothing: the page depends on
     * its size alone.
     */
    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherThePageKeepsTheConstellationAndTheMapTogether() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        OfferFilterService.sawDasherBeside(android.os.SystemClock.uptimeMillis());
        ActivityController<MainActivity> built = Robolectric.buildActivity(MainActivity.class);
        Shadows.shadowOf(built.get()).setInMultiWindowMode(true);
        try (ActivityController<MainActivity> activity = built.setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            layOut(content);
            ScenePage scene = find(content, ScenePage.class);
            AreaMapView map = find(content, AreaMapView.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertEquals("the map, beside Dasher too", 1f, scene.shown(map), 0f);
            assertEquals("with the constellation", 1f, scene.shown(star), 0f);
            assertTrue(map.isShown() && star.isShown());
            android.graphics.Rect starWas = new android.graphics.Rect(star.getLeft(), star.getTop(), star.getRight(),
                    star.getBottom());
            android.graphics.Rect mapWas = new android.graphics.Rect(map.getLeft(), map.getTop(), map.getRight(),
                    map.getBottom());

            // Dasher leaves the other half: nothing moves.
            OfferFilterService.sawDasherBeside(0);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            layOut(content);
            assertEquals(starWas, new android.graphics.Rect(star.getLeft(), star.getTop(), star.getRight(),
                    star.getBottom()));
            assertEquals(mapWas, new android.graphics.Rect(map.getLeft(), map.getTop(), map.getRight(),
                    map.getBottom()));
        } finally {
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    public void theTicketDrawerClosesWhenDraggedDownAndSpringsBackOtherwise() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            layOut(content);
            openTicket(content);
            layOut(content);
            DrawerCard card = find(content, DrawerCard.class);
            View sheet = (View) card.getParent();
            assertEquals(View.VISIBLE, sheet.getVisibility());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));

            // A short, slow pull: it follows the finger, then springs back.
            drag(card, 0.1f, 400);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertEquals(View.VISIBLE, sheet.getVisibility());
            assertEquals(0f, card.getTranslationY(), 0.5f);

            // Pulled past a quarter of its height: it closes.
            drag(card, 0.5f, 400);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertEquals(View.GONE, sheet.getVisibility());

            // Screen readers close it with an action.
            openTicket(content);
            layOut(content);
            assertTrue(card.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS, null));
            assertEquals(View.GONE, sheet.getVisibility());
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void theSignpostStandsOverAFullSkylineAndClearOfTheSkysWordsAndIcons() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        long now = System.currentTimeMillis();
        // A full skyline, its oldest (leftmost) offers the tallest, where the signpost stands.
        for (int i = 0; i < DecisionChartView.SLOTS; i++) {
            int pay = i < 4 ? 3000 : 900 + i * 50;
            DecisionLog.record(app, new DecisionLog.Entry(now - (DecisionChartView.SLOTS - i) * 60_000L,
                    DecisionLog.Source.SCREEN, false, new OfferSnapshot(pay, 5.0, 20, 2), 1000,
                    pay >= 1000 ? OfferRule.Result.KEEP : OfferRule.Result.DECLINE,
                    pay >= 1000 ? "meets your minimums" : "flat minimum", DecisionLog.Action.PASSES, true,
                    Collections.emptyList()));
        }
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
            ScenePage scene = find(content, ScenePage.class);
            DecisionChartView chart = findChart(content);
            scene.setPlace("Mission District");
            scene.draw(new Canvas(Bitmap.createBitmap(scene.getWidth(), scene.getHeight(), Bitmap.Config.ARGB_8888)));
            android.graphics.RectF sign = scene.signBoard();
            assertNotNull("it rises over the buildings rather than being left out", sign);
            int[] chartAt = new int[2];
            int[] sceneAt = new int[2];
            chart.getLocationInWindow(chartAt);
            scene.getLocationInWindow(sceneAt);
            float chartLeft = chartAt[0] - sceneAt[0];
            float highest = chartAt[1] - sceneAt[1] + chart.highestWithin(sign.left - chartLeft,
                    sign.right - chartLeft);
            assertTrue("above the buildings and flags under it: " + sign + " / " + highest, sign.bottom <= highest);
            for (android.graphics.RectF box : scene.words()) {
                assertFalse("clear of the sky's words: " + box, android.graphics.RectF.intersects(sign, box));
            }
            List<android.graphics.RectF> icons = new ArrayList<>();
            MinimumsStarView star = find(content, MinimumsStarView.class);
            star.iconsAt(icons);
            int[] starAt = new int[2];
            star.getLocationInWindow(starAt);
            for (android.graphics.RectF icon : icons) {
                icon.offset(starAt[0] - sceneAt[0], starAt[1] - sceneAt[1]);
                assertFalse("clear of the constellation's icons: " + icon,
                        android.graphics.RectF.intersects(sign, icon));
            }
        } finally {
            listener.destroy();
            service.destroy();
            OfferFilterService.sawDasherBeside(0);
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aLargeFontAndALineToFixKeepTheSkylineAndTheMapAtTheirLeast() {
        largeFontAndALineToFix(false);
    }

    /**
     * The same with Autopilot on: the line to fix takes its room first, so Autopilot's status stands in its chip beside
     * the latest offer's line (no status line in the ground), and the page is still one screen.
     */
    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aLargeFontALineToFixAndAutopilotOnStillFitOneScreen() {
        largeFontAndALineToFix(true);
    }

    private void largeFontAndALineToFix(boolean autopilot) {
        RuntimeEnvironment.setFontScale(2f);
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        AreaMap.setEnabled(app, true);
        // Areas enough for a best one, so the line naming it stands under the map.
        double[][] spots = {{37.7749, -122.4194}, {37.8149, -122.3794}, {37.7549, -122.4494}};
        for (double[] spot : spots) {
            for (int pay : new int[] {1000, 1500, 1200}) noteOfferAt(spot[0], spot[1], pay, 5.0);
        }
        setLocation(37.7749, -122.4194);
        // The busiest constellation the old rules come to: three minimums (the old $1.00 per stop folded into the $7.00
        // minimum pay, the learning retired) and max stops, with the Autopilot button by them.
        FilterStore.save(app, FilterSettings.of(true, 700, 150, 30, 4));
        if (autopilot) FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        DecisionLog.record(app, declinedEntry());
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        // On a dash, so the counts above the constellation are this dash's, with the totals under them.
        Dashing.forgetCache();
        Dashing.seen(app);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            settleSky(content);
            assertNotNull("background offers are off: a line to fix", shownTextContaining(content,
                    SetupChecklist.NOTIFICATIONS));
            Ui ui = new Ui(activity.get());
            DecisionChartView chart = findChart(content);
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue("the skyline keeps its least: " + chart.getHeight(), chart.getHeight() >= ui.dp(64));
            assertTrue("the map keeps its least: " + map.getHeight(), map.getHeight() >= ui.dp(96));
            assertNotNull("the line naming the best area stands under it",
                    shownTextContaining(content, "/mi"));
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue("the constellation is still the sky", star.backdrop());
            android.widget.ScrollView page = (android.widget.ScrollView) ((View) star.getParent().getParent())
                    .getParent();
            View column = page.getChildAt(0);
            assertTrue("one screen: " + column.getHeight() + " in " + page.getHeight(),
                    column.getHeight() <= page.getHeight());
            AutopilotChip chip = find(content, AutopilotChip.class);
            if (autopilot) {
                assertNull("no status line: the line to fix takes the room first",
                        shownTextContaining(content, "Autopilot 100%"));
                assertTrue("the chip says it, beside the latest offer's line", chip.isShown());
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue(chip.getGlobalVisibleRect(visible));
                assertEquals("whole", chip.getHeight(), visible.height());
                TextView caption = shownTextContaining(content, "Latest · ");
                assertTrue(caption.getGlobalVisibleRect(visible));
                assertEquals("the latest offer's line whole", caption.getHeight(), visible.height());
            } else {
                assertFalse("off on a whole screen: the button in the sky is the way in", chip.isShown());
            }
        } finally {
            service.destroy();
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    @Test
    public void theMascotAloneOwnsThePauseActionOnTheMainPage() {
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            assertFalse("the drawn container is not an extra action", mascot.isClickable());
            assertFalse(mascot.isFocusable());
            // Screen readers reach a native button precisely over the mascot, separate from the counts.
            assertTrue(mascot.mascotControl().isClickable());
            assertTrue(mascot.mascotControl().isFocusable());
            android.view.accessibility.AccessibilityNodeInfo node = mascot.mascotControl().createAccessibilityNodeInfo();
            assertEquals(Button.class.getName(), node.getClassName().toString());
            boolean labeled = false;
            for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                labeled |= action.getId() == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        && "Pause auto-decline".contentEquals(action.getLabel());
            }
            assertTrue("the tap is labeled", labeled);
            assertTrue(mascot.getContentDescription().toString().startsWith("Auto-decline on. "));

            // No other pause or resume control competes with it on the main page.
            List<Button> buttons = new ArrayList<>();
            collectButtons(content, buttons);
            for (Button button : buttons) {
                String label = button.getText().toString();
                assertFalse(label, button.isShown() && (label.contains("Pause") || label.contains("Resume")));
            }
        }
    }

    @Test
    public void setupProblemsShowOnlyWhileSomethingIsOff() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            // No service is connected in this test, so screen reading is reported off with a Fix button.
            TextView problem = findText(content, SetupChecklist.ACCESSIBILITY);
            assertNotNull(problem);
            assertEquals(View.VISIBLE, ((View) problem.getParent()).getVisibility());
        }
    }
}
