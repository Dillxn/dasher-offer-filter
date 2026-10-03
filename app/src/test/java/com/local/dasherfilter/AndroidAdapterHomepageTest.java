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
            assertNull("no line of words under the skyline", shownTextContaining(content, "Below your per-mile rate"));
            assertNull("the ticket stays folded until asked for", find(content, OfferCardView.class));

            openTicket(content);
            OfferCardView card = find(content, OfferCardView.class);
            assertTrue(card.isShown());
            assertEquals("Paid $7.90, needed $10.80. 7.2 mi · 21 min · 2 stops",
                    card.getContentDescription().toString());
            assertEquals("Declined", find(content, Decor.Stamp.class).getContentDescription().toString());
            assertNotNull(shownTextContaining(content, "Below your per-mile rate"));

            // While no older offer is picked, the ticket follows each new one.
            DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() + 60_000,
                    DecisionLog.Source.SCREEN, false, new OfferSnapshot(2500, 9.1, 30, 3), 2000,
                    OfferRule.Result.KEEP, "meets enabled rules", DecisionLog.Action.PASSES, true,
                    Collections.emptyList()));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1100));
            assertEquals("Passed", find(content, Decor.Stamp.class).getContentDescription().toString());
            assertNotNull(shownTextContaining(content, "Meets your rules"));

            // Back folds the ticket away.
            activity.get().onBackPressed();
            assertNull(find(content, OfferCardView.class));
        }
    }

    @Test
    public void theFilterPictureShowsThisDashWithAllTimeTotalsAndTheState() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
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
                false, new OfferSnapshot(2500, 9.1, 30, 3), 2000, OfferRule.Result.KEEP, "meets enabled rules",
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
            assertFalse("a whole screen keeps it in the page, drawn in full", star.beside());
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
            assertNotNull("the ticket is up", shownTextContaining(content, "Below your per-mile rate"));
            activity.get().onBackPressed();
            assertFalse(activity.get().isFinishing());
            assertNull("folded again", shownTextContaining(content, "Below your per-mile rate"));
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h360dp-xxhdpi")
    public void aShortWindowKeepsTheConstellationTheMascotItsCountsTheSkylineAndTheMap() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(mascot.isShown());
            assertTrue("the map stays", find(content, AreaMapView.class).isShown());
            assertTrue("the constellation stays, up in the sky", star.isShown());
            int[] starAt = new int[2];
            int[] mascotAt = new int[2];
            star.getLocationInWindow(starAt);
            mascot.getLocationInWindow(mascotAt);
            assertTrue("beside the sun, above the mascot", starAt[1] < mascotAt[1]);
            assertNotNull("in the header, with the sun and Settings",
                    iconDescribed((View) star.getParent(), "Settings"));
            assertTrue("drawn with its icons beside the circle", star.beside());
            View title = iconDescribed(content, "Offer Filter");
            assertTrue("the page's name keeps room, so screen readers reach it", title != null && title.getWidth() > 0);
            assertTrue("a tap on it is a button there", star.isClickable());
            star.performClick();
            settle();
            assertFalse("a tap spreads it across the sky, where its knobs are", star.beside());
            assertFalse("and opens no page", settingsShown(content));
            assertTrue("the skyline stays", findChart(content).isShown());
            star.performClick();
            settle();
            assertTrue("a tap on its circle puts it back in the header", star.beside());
            assertTrue(find(content, AreaMapView.class).isShown());
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void halfOfASplitScreenFitsOneScreenWithEverythingOnIt() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        DecisionLog.record(app, declinedEntry());
        // Screen reading on, as while dashing; background offers still off, so one line asks for a fix. The other
        // half is not Dasher, so the page keeps its map.
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
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertTrue(star.beside());
            assertTrue(findChart(content).isShown());
            AreaMapView map = find(content, AreaMapView.class);
            assertTrue(map.isShown() && map.getHeight() >= new Ui(app).dp(96));
            DecisionChartView chart = findChart(content);
            assertEquals("the skyline keeps a fixed height rather than being squeezed", new Ui(app).dp(56),
                    chart.getHeight());
            assertTrue("its flags stay whole inside it", chart.highestWithin(0, chart.getWidth()) >= 0);
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

    @Test
    @Config(qualifiers = "w411dp-h410dp-420dpi")
    public void besideDasherThePageShowsNoSecondMapAndTheSkyTakesItsRoom() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
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
            AreaMapView map = find(content, AreaMapView.class);
            MinimumsStarView star = find(content, MinimumsStarView.class);
            assertFalse("Dasher's map is right below: no second one", map.isShown());
            assertTrue("the constellation stands in the sky at full size", star.isShown() && !star.beside());
            assertNotSame("not in the header", iconDescribed(content, "Settings").getParent(), star.getParent());
            android.graphics.RectF counts = new android.graphics.RectF();
            FilterHeroView mascot = find(content, FilterHeroView.class);
            mascot.countsAt(counts);
            counts.offset(mascot.getLeft(), mascot.getTop());
            float radius = star.skyRadius();
            float available = star.getHeight() - counts.bottom;
            float occupied = star.backdropAbove(radius) + star.backdropBelow(radius);
            assertTrue("both vertical spokes stay below the counts and inside the sky",
                    star.skyY() - radius >= counts.bottom && star.skyY() + radius <= star.getHeight());
            assertTrue("the six-spoke constellation uses the available height: " + occupied + " of " + available,
                    occupied <= available && occupied >= available - new Ui(app).dp(12));
            assertTrue("the skyline stays", findChart(content).isShown());
            ScenePage scene = find(content, ScenePage.class);
            android.widget.ScrollView page = (android.widget.ScrollView) scene.getParent();
            assertTrue("no scrolling: " + scene.getHeight() + " in " + page.getHeight(),
                    scene.getHeight() <= page.getHeight());
            assertTrue("the constellation gets the room the map left: " + star.getHeight(),
                    star.getHeight() >= new Ui(app).dp(110));

            // Dasher leaves the other half: our map comes back, and the constellation moves up to make room.
            OfferFilterService.sawDasherBeside(0);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertEquals(View.VISIBLE, map.getVisibility());
            assertTrue("in the header again", star.beside());
            assertNotNull(iconDescribed((View) star.getParent(), "Settings"));
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
                    pay >= 1000 ? "meets enabled rules" : "flat minimum", DecisionLog.Action.PASSES, true,
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
        RuntimeEnvironment.setFontScale(2f);
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        AreaMap.setEnabled(app, true);
        // Areas enough for a best one, so the line naming it stands under the map.
        double[][] spots = {{37.7749, -122.4194}, {37.8149, -122.3794}, {37.7549, -122.4494}};
        for (double[] spot : spots) {
            for (int pay : new int[] {1000, 1500, 1200}) noteOfferAt(spot[0], spot[1], pay, 5.0);
        }
        setLocation(37.7749, -122.4194);
        FilterStore.save(app, new FilterSettings(true, 700, 150, 30, 100, 4, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(1420, 6.0, 24, 2));
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
                    "Background offers are off"));
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
        } finally {
            service.destroy();
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    @Test
    public void theMascotIsTheOneButtonOnTheMainPage() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            FilterHeroView mascot = find(content, FilterHeroView.class);
            assertTrue(mascot.isClickable());
            assertTrue(mascot.isFocusable());
            // Screen readers hear a button that says what a tap does, with the state and the day's counts.
            android.view.accessibility.AccessibilityNodeInfo node = mascot.createAccessibilityNodeInfo();
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
            TextView problem = findText(content, "Screen reading is off");
            assertNotNull(problem);
            assertEquals(View.VISIBLE, ((View) problem.getParent()).getVisibility());
        }
    }
}
