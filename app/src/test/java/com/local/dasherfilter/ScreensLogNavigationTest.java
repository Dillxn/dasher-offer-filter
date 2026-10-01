package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

/**
 * The screens log keeps the pickup leg after an Accept: Dasher's turn-by-turn navigation changes its words at every
 * turn, so it once filled the log (and the user's 0.4.41 report) and pushed the pickup screens out. Navigation is
 * now kept at most once a minute on a budget of its own, and never pushes another screen of the last 30 minutes out.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class ScreensLogNavigationTest {
    private static final String[] TURNS = {"Turn left onto Elm Rd", "Turn right onto Oak Ave", "Keep left",
            "Continue onto Pine St", "Turn right", "Slight left onto Birch Ln"};
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS XXX";

    private Application app;
    private ServiceController<OfferFilterService> controller;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.forgetCache();
        ReportOutbox.forgetCache();
        OfferSilencer.forgetCache();
        // Reads run on the main looper, so each screen is read (and captured) before show() returns.
        OfferFilterService.scanLooperForTests = android.os.Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void stop() {
        controller.destroy();
        OfferFilterService.scanLooperForTests = null;
    }

    private AccessibilityNodeInfo node(String text) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
        node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        return node;
    }

    /** Dasher showing these labels, read at once as a window change. */
    private void show(String... labels) {
        AccessibilityNodeInfo root = node("");
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label));
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
    }

    /** One turn-by-turn update, as Dasher's navigation draws it: its distance, turn, speed and arrival change. */
    private void navigation(int update) {
        show((update % 9 + 1) * 100 + " ft", TURNS[update % TURNS.length], "Then", "Toward Exit " + update,
                String.valueOf(20 + update % 30), "mph", "• 2:" + (10 + update % 50) + " am", "12 min", "4.2 mi",
                "Re-center", "Overview", "Mute", "Report", "Exit");
    }

    /** Lets the log's writer catch up, as a report would (its queue drops what it cannot hold). */
    private String screens() {
        return DiagnosticLog.readScreens(app);
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    // Before: every turn was a new screen, logged at most once a second, and one just logged held the pickup screen
    // after it back for a second: about a hundred "mph" lines, and no pickup screen.
    @Test
    public void navigationIsKeptAtMostOnceAMinuteOnABudgetOfItsOwn() {
        for (int update = 0; update < 100; update++) {
            navigation(update);
            if (update == 40) {
                // Navigation was just kept (60 s after the first); the pickup screen half a second later still is.
                ShadowSystemClock.advanceBy(Duration.ofMillis(500));
                show("Pick up by 2:20 AM", "Taco Place", "Directions");
                ShadowSystemClock.advanceBy(Duration.ofMillis(1_000));
            } else {
                ShadowSystemClock.advanceBy(Duration.ofMillis(1_500));
            }
            if (update % 10 == 9) screens();
        }
        String kept = screens();
        // 150 s of navigation: kept at 0 s, 60 s and 120 s, whatever its words.
        assertEquals(kept, 3, count(kept, "mph"));
        assertEquals(kept, 3, count(kept, " [navigation] "));
        assertTrue(kept, kept.contains("Toward Exit 0,") && kept.contains("Toward Exit 40,")
                && kept.contains("Toward Exit 80,"));
        assertTrue(kept, kept.contains("labels=[Pick up by 2:20 AM, Taco Place, Directions]"));
        // A screen of its own is no navigation: "mph" with no distance, or a distance with no "mph".
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        show("Speed limit", "mph", "Taco Place");
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        show("Taco Place", "0.4 mi away");
        kept = screens();
        assertTrue(kept, kept.contains("[Speed limit, mph, Taco Place]") && kept.contains("[Taco Place, 0.4 mi away]"));
    }

    // Before: the log kept its newest 12 KB whatever they were, so a hundred minutes of navigation pushed the pickup
    // screens out of the log and the report.
    @Test
    public void thePickupScreensSurviveAHundredNavigationUpdates() {
        // The pickup leg after an Accept.
        show("Pick up by 2:20 AM", "Taco Place", "Directions");
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        show("Arrived at store", "Taco Place", "Confirm pickup");
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        show("Complete pickup steps", "Taco Place", "Order for Sam");
        // Then a hundred navigation updates, each a minute apart, so each is kept.
        for (int update = 0; update < 100; update++) {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(61));
            navigation(update);
            if (update % 10 == 9) screens();
        }
        String kept = screens();
        for (String pickup : new String[] {"[Pick up by 2:20 AM, Taco Place, Directions]",
                "[Arrived at store, Taco Place, Confirm pickup]", "[Complete pickup steps, Taco Place, Order for [name]]"}) {
            assertTrue(pickup + " in " + kept, kept.contains(pickup));
        }
        // Navigation filled the log all the same, within its budget, and is marked as such.
        assertTrue(kept, count(kept, "mph") >= 20);
        assertEquals(kept, count(kept, "mph"), count(kept, " [navigation] "));
        assertTrue(kept.length() <= 16 * 1024);
        assertTrue("the newest navigation stays", kept.contains("Toward Exit 99,"));
        assertFalse("the oldest navigation went first", kept.contains("Toward Exit 0,"));

        // The report's own cut keeps them too.
        String report = DiagnosticLog.report(app);
        String section = report.substring(report.indexOf("== Dasher's other screens (newest)\n"));
        assertTrue(section, section.length() <= 10_000 + 40);
        assertTrue(section, section.contains("[Pick up by 2:20 AM, Taco Place, Directions]")
                && section.contains("[Complete pickup steps, Taco Place, Order for [name]]"));
        assertTrue(section, section.contains("Toward Exit 99,"));
        assertTrue(section, section.startsWith("== Dasher's other screens (newest)\n[older entries omitted]\n20"));
    }

    // Before: there was no fitting by kind and age (DiagnosticLog.fitScreens), only the newest bytes.
    @Test
    public void navigationNeverPushesOutAnotherScreenOfTheLast30Minutes() {
        long now = System.currentTimeMillis();
        String oldScreen = line(now, 40, "screen", "other labels=[Old screen]");
        String oldNavigation = line(now, 35, "navigation", "other labels=[300 ft, mph, Old turn]");
        String pickup = line(now, 20, "screen", "after an offer left (3 s) labels=[Pick up by 2:20 AM, Taco Place]");
        StringBuilder navigation = new StringBuilder();
        for (int minute = 19; minute >= 2; minute--) {
            navigation.append(line(now, minute, "navigation", "other labels=[0.4 mi, mph, Turn " + minute + "]"));
        }
        String latest = line(now, 1, "screen", "other labels=[Verify correct order]");
        String log = oldScreen + oldNavigation + pickup + navigation + latest;
        String newestNavigation = line(now, 2, "navigation", "other labels=[0.4 mi, mph, Turn 2]");
        assertTrue(DiagnosticLog.navigationLine(oldNavigation));
        assertFalse(DiagnosticLog.navigationLine(pickup));

        // Room for the two recent screens and a little navigation: the old screen goes (it is past 30 minutes), and
        // navigation goes oldest first, however recent, before either recent screen.
        int budget = pickup.length() + latest.length() + 2 * newestNavigation.length();
        String fitted = DiagnosticLog.fitScreens(log, budget, false, now);
        assertTrue(fitted.length() <= budget);
        assertEquals(pickup + line(now, 3, "navigation", "other labels=[0.4 mi, mph, Turn 3]") + newestNavigation
                + latest, fitted);
        // Bytes or characters alike; a log that fits is kept whole.
        assertEquals(fitted, DiagnosticLog.fitScreens(log, budget, true, now));
        assertEquals(log, DiagnosticLog.fitScreens(log, log.length(), false, now));
        // When the recent screens alone are too many, they crowd each other out, oldest first.
        assertEquals(latest, DiagnosticLog.fitScreens(log, latest.length() + 10, false, now));
        // Past 30 minutes, a screen is no longer kept over newer navigation.
        assertEquals(pickup + newestNavigation + latest, DiagnosticLog.fitScreens(log,
                pickup.length() + newestNavigation.length() + latest.length(), false, now));
        String later = DiagnosticLog.fitScreens(log, newestNavigation.length() * 3 + latest.length(), false,
                now + 11 * 60_000L);
        assertFalse("the pickup screen is 31 minutes old now", later.contains("Pick up by"));
        assertTrue(later, later.endsWith(newestNavigation + latest));
    }

    /** A screens-log line written {@code minutesAgo} before {@code now}. */
    private static String line(long now, int minutesAgo, String source, String message) {
        String at = new SimpleDateFormat(TIME_PATTERN, Locale.US).format(new Date(now - minutesAgo * 60_000L));
        return at + " [" + source + "] " + message + "\n";
    }
}
