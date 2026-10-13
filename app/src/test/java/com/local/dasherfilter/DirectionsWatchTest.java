package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

/**
 * The Directions watch (0.6.11, diagnostics only; the owner, 13 October 2026: "never shows the map just shows multiple
 * pages opening and then drops back"): the classes Dasher's window changes name after the user's tap, the looks at
 * Android's list of windows, and one verdict, telling a screen Dasher re-created (a new window) from one that closed
 * itself; at most one watch a minute, and never another app's name.
 */
public class DirectionsWatchTest {
    private static final long T = 1_000_000L;

    private static DirectionsWatch armed() {
        DirectionsWatch watch = new DirectionsWatch();
        assertNotNull(watch.arm(T, "DeliveryActivity", new HashSet<>(Arrays.asList(7, 9)), true));
        return watch;
    }

    @Test
    public void itNamesEachClassFromTheEventAloneAndTheDropBackInAWindowDasherRecreated() {
        DirectionsWatch watch = armed();
        assertEquals(Collections.singletonList("+0.4 s NavigationActivity (new window)"),
                watch.window(T + 400, "NavigationActivity", 12, false));
        assertEquals(Collections.singletonList("+1.2 s WebViewActivity (new window)"),
                watch.window(T + 1_200, "WebViewActivity", 13, false));
        List<String> back = watch.window(T + 3_100, "DeliveryActivity", 14, false);
        assertEquals(Arrays.asList("+3.1 s DeliveryActivity (new window)",
                "dropped back to DeliveryActivity after 3 s (window new)"), back);
        // Once: later changes are named, the verdict is not said again, nor at the last look.
        assertEquals(1, watch.window(T + 4_000, "DeliveryActivity", 14, false).size());
        List<String> last = watch.look(T, 20_000, "win=split/bottom/dasher/68 screen=on locked=no", "Dasher "
                + "DeliveryActivity", true);
        assertEquals(Collections.singletonList("+20 s: win=split/bottom/dasher/68 screen=on locked=no; in front: Dasher "
                + "DeliveryActivity"), last);
    }

    @Test
    public void aPageThatClosedItselfDropsBackInTheKnownWindow() {
        DirectionsWatch watch = armed();
        watch.window(T + 500, "NavigationActivity", 12, false);
        List<String> back = watch.window(T + 2_000, "DeliveryActivity", 9, false);
        assertEquals("dropped back to DeliveryActivity after 2 s (window known)", back.get(1));
    }

    @Test
    public void androidsOwnClassesAreNamedButNeverMakeTheVerdict() {
        DirectionsWatch watch = armed();
        assertEquals(1, watch.window(T + 300, "FrameLayout", 9, true).size());
        // No other screen of Dasher's yet: the class it showed before is no drop back.
        assertEquals(1, watch.window(T + 600, "DeliveryActivity", 9, false).size());
    }

    @Test
    public void withNoDropBackTheLastLookSaysWhereItStayedOrWhatIsInFront() {
        DirectionsWatch watch = armed();
        watch.window(T + 500, "NavigationActivity", 12, false);
        assertEquals(1, watch.look(T, 2_000, "win=x", "Dasher NavigationActivity", true).size());
        List<String> last = watch.look(T, 20_000, "win=x", "Dasher NavigationActivity", true);
        assertEquals("stayed on NavigationActivity (window new)", last.get(1));

        DirectionsWatch other = armed();
        other.window(T + 500, "NavigationActivity", 12, false);
        List<String> away = other.look(T, 20_000, "win=full/-/other/100", "a navigation app", false);
        assertEquals("a navigation app in front", away.get(1));
    }

    @Test
    public void itNamesTwelveClassesAtMostAndNothingAfterTwentySeconds() {
        DirectionsWatch watch = armed();
        int lines = 0;
        for (int i = 0; i < 20; i++) lines += watch.window(T + 100 + i * 100, "Screen" + i, 20 + i, false).size();
        assertEquals(DirectionsWatch.LINES, lines);
        DirectionsWatch late = armed();
        assertTrue(late.window(T + DirectionsWatch.WATCH_MS + 1, "NavigationActivity", 12, false).isEmpty());
        assertFalse(late.watching(T + DirectionsWatch.WATCH_MS + 1));
    }

    @Test
    public void atMostOneWatchAMinuteAndALookOfAnOlderWatchSaysNothing() {
        DirectionsWatch watch = armed();
        assertNull(watch.arm(T + 30_000, "Home", Collections.<Integer>emptySet(), false));
        assertNull(watch.arm(T + DirectionsWatch.ARM_EVERY_MS - 1, "Home", Collections.<Integer>emptySet(), false));
        String again = watch.arm(T + DirectionsWatch.ARM_EVERY_MS, "Home", Collections.<Integer>emptySet(), false);
        assertEquals("watching 20 s after your tap on Dasher during a delivery; on Home", again);
        assertTrue(watch.look(T, 2_000, "win=x", "home", false).isEmpty());
    }

    @Test
    public void noLineNamesAnotherAppsPackage() {
        DirectionsWatch watch = armed();
        List<String> lines = new ArrayList<>();
        lines.addAll(watch.window(T + 500, "NavigationActivity", 12, false));
        for (long after : DirectionsWatch.LOOKS_MS) {
            lines.addAll(watch.look(T, after, "win=full/-/other/100 screen=on locked=no", "another app", false));
        }
        for (String line : lines) {
            assertFalse(line, line.contains("com.") || line.contains("google") || line.contains("waze"));
        }
        assertEquals("another app in front", lines.get(lines.size() - 1));
    }

    @Test
    public void onlyTheWordDirectionsItself() {
        assertTrue(DirectionsWatch.isDirections("Directions"));
        assertTrue(DirectionsWatch.isDirections("  DIRECTIONS "));
        assertFalse(DirectionsWatch.isDirections("Get directions"));
        assertFalse(DirectionsWatch.isDirections(null));
    }

    @Test
    public void aDeliveryScreenWithDirectionsIsKeptMaskedButIsNoScene() {
        List<String> dropOff = Arrays.asList("Deliver to Jane D.", "123 Main St", "Directions", "Hand it to customer");
        assertTrue(PersonalText.namesDirections(dropOff));
        assertFalse(PersonalText.namesDirections(Arrays.asList("Get directions", "Home")));
        // DasherScene is unchanged for it: the Directions label makes no scene.
        List<String> without = Arrays.asList("Deliver to Jane D.", "123 Main St", "Hand it to customer");
        assertEquals(DasherScene.showsRoute(without), DasherScene.showsRoute(dropOff));
        assertEquals(DasherScene.showsNavigation(without), DasherScene.showsNavigation(dropOff));
        assertEquals(PersonalText.recognizedDashScreen(without), PersonalText.recognizedDashScreen(dropOff));
    }
}
