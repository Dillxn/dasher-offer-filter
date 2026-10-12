package com.local.dasherfilter;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The homepage's "Dasher stuck on its launch screen?" line (0.6.3): shown while the launch watch holds Dasher on its
 * launch screen after a start of this app's, among the lines that need the user. One 48 dp row a screen reader hears as
 * one button ("Dasher may be stuck on its launch screen. Recent apps"); its tap, the user's only, opens recent apps,
 * where the user picks Dasher's card with the dash, and the line goes. It also goes when a read recognises Dasher's
 * screen, after 30 minutes (deep sleep counted on a phone; the simulated clocks here move together), or when a dash
 * begins after it (the dash already under way keeps it). Nothing automatic is done to Dasher for it, and what is wrong
 * on the phone then is not established. Simulated Android only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityLaunchScreenLineTest extends AndroidAdapterTestBase {
    private static final String DASHER = "com.doordash.driverapp";

    @After
    public void forgetLine() {
        DasherSplit.forget();
        OfferFilterService.forgetScreenState();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    private ServiceController<OfferFilterService> connected() {
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        idle();
        return service;
    }

    private static View content(ActivityController<MainActivity> activity) {
        return activity.get().findViewById(android.R.id.content);
    }

    /** The line's row, found as a screen reader finds it (its spoken words), or null; shown or not. */
    private static View line(View content) {
        return iconDescribed(content, DasherSplit.LAUNCH_SCREEN_SPOKEN);
    }

    private static List<String> texts(View view) {
        List<String> found = new ArrayList<>();
        if (view instanceof TextView) found.add(String.valueOf(((TextView) view).getText()));
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) found.addAll(texts(((ViewGroup) view).getChildAt(i)));
        }
        return found;
    }

    /** Dasher's wait for offers in front, full screen, and its window change: a read recognises it. */
    private void dasherShowsTheWait(OfferFilterService service) {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain(new View(app));
        root.setPackageName(DASHER);
        root.setVisibleToUser(true);
        AccessibilityNodeInfo words = AccessibilityNodeInfo.obtain(new View(app));
        words.setPackageName(DASHER);
        words.setText("Finding offers");
        words.setVisibleToUser(true);
        Shadows.shadowOf(root).addChild(words);
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(970);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setActive(true);
        shadow.setBoundsInScreen(new Rect(0, 0, 1080, 2040));
        shadow.setRoot(root);
        Shadows.shadowOf(root).setAccessibilityWindowInfo(window);
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER);
        event.setClassName("android.widget.FrameLayout");
        event.setEventTime(SystemClock.uptimeMillis());
        ((ShadowAccessibilityRecord) Shadow.extract(event)).setWindowId(970);
        service.onAccessibilityEvent(event);
        idle();
    }

    @Test
    public void theLineIsOneButtonWhoseTapOpensRecentAppsAndGoes() {
        ServiceController<OfferFilterService> service = connected();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            assertTrue("no line while nothing holds", line(content) == null || !line(content).isShown());

            OfferFilterService.holdLaunchScreen(SystemClock.uptimeMillis());
            pass(1_100);
            View row = line(content);
            assertNotNull(row);
            assertTrue("shown while Dasher seems stuck", row.isShown());
            layOut(content);
            float density = app.getResources().getDisplayMetrics().density;
            assertTrue("a 48 dp target: " + row.getHeight(), row.getHeight() >= Math.round(48 * density));
            assertTrue(row.isClickable());
            assertEquals("heard as one button", DasherSplit.LAUNCH_SCREEN_SPOKEN, row.getContentDescription());
            AccessibilityNodeInfo heard = row.createAccessibilityNodeInfo();
            assertEquals(android.widget.Button.class.getName(), String.valueOf(heard.getClassName()));
            List<String> shown = texts(row);
            assertTrue(shown.toString(), shown.contains(DasherSplit.LAUNCH_SCREEN_LINE));
            assertTrue(shown.toString(), shown.contains(DasherSplit.LAUNCH_SCREEN_ACTION));
            contains(DiagnosticLog.read(app), "[split] launch-screen line shown");

            row.performClick();
            idle();
            assertEquals("recent apps, at the user's tap only",
                    Collections.singletonList(AccessibilityService.GLOBAL_ACTION_RECENTS),
                    Shadows.shadowOf(service.get()).getGlobalActionsPerformed());
            assertEquals(DasherSplit.PICK_DASHER_CARD, ShadowToast.getTextOfLatestToast());
            assertEquals(0, OfferFilterService.launchScreenHeldSince(app));
            assertTrue("the line goes", !line(content).isShown());
            assertNull("nothing of Dasher's is started", Shadows.shadowOf(app).getNextStartedActivity());
            contains(DiagnosticLog.read(app), "[split] launch-screen line: Recent apps tapped");

            // A later hold shows it again, and logs it again.
            DiagnosticLog.clear(app);
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
            OfferFilterService.holdLaunchScreen(SystemClock.uptimeMillis());
            pass(1_100);
            assertTrue(line(content).isShown());
            contains(DiagnosticLog.read(app), "[split] launch-screen line shown");
        } finally {
            service.destroy();
        }
    }

    /**
     * The line held, then lifted by {@code lift} (Dasher's screen read and recognised, half an hour, or a new dash):
     * gone from the homepage, logged once with why, nothing done to Dasher. Each case starts from a fresh phone, so no
     * case inherits another's dash, clock or log.
     */
    private void theLineGoesFor(String lift, java.util.function.Consumer<OfferFilterService> lifting) {
        ServiceController<OfferFilterService> service = connected();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            OfferFilterService.holdLaunchScreen(SystemClock.uptimeMillis());
            pass(1_100);
            assertTrue(lift, line(content).isShown());
            lifting.accept(service.get());
            pass(1_100);
            assertEquals(lift, 0, OfferFilterService.launchScreenHeldSince(app));
            assertTrue(lift + ": the line goes", !line(content).isShown());
            String log = DiagnosticLog.read(app);
            contains(log, "[split] launch-screen line gone: " + lift);
            assertEquals(log, 1, count(log, "launch-screen line gone"));
            if (lift.equals("Dasher drew")) contains(log, "[launch] Dasher past its launch screen ");
            assertTrue(lift + ": nothing automatic", Shadows.shadowOf(service.get()).getGlobalActionsPerformed().isEmpty());
            assertNull(lift + ": nothing started", Shadows.shadowOf(app).getNextStartedActivity());
        } finally {
            service.destroy();
            OfferFilterService.forgetLaunchScreen();
        }
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) found++;
        return found;
    }

    @Test
    public void theLineGoesWhenAReadRecognisesDashersScreen() {
        // Auto-decline on with a rule, so Dasher's screen is read.
        FilterStore.save(app, FilterSettings.of(true, 400, 0, 0, 0));
        theLineGoesFor("Dasher drew", this::dasherShowsTheWait);
    }

    @Test
    public void theLineGoesAfterHalfAnHour() {
        theLineGoesFor("30 minutes",
                screen -> ShadowSystemClock.advanceBy(Duration.ofMillis(OfferFilterService.LAUNCH_HOLD_MS)));
    }

    @Test
    public void theLineGoesWhenANewDashBegins() {
        theLineGoesFor("a new dash", screen -> {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
            Dashing.seen(app);
        });
    }

    /**
     * A dash already under way when Dasher was judged on its launch screen is no new dash: its next sightings keep the
     * line (the user has not picked Dasher's card yet), and nothing says it went.
     */
    @Test
    public void aDashBegunBeforeTheHoldKeepsTheLine() {
        ServiceController<OfferFilterService> service = connected();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = content(activity);
            Dashing.seen(app);
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
            OfferFilterService.holdLaunchScreen(SystemClock.uptimeMillis());
            pass(1_100);
            assertTrue("shown", line(content).isShown());
            // The same dash seen again, 40 s and then two minutes on.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(40));
            Dashing.seen(app);
            pass(1_100);
            ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
            Dashing.seen(app);
            pass(1_100);
            assertNotEquals("the dash under way is no new dash", 0, OfferFilterService.launchScreenHeldSince(app));
            assertTrue("the line stays", line(content).isShown());
            String log = DiagnosticLog.read(app);
            assertFalse(log, log.contains("launch-screen line gone"));
        } finally {
            service.destroy();
            OfferFilterService.forgetLaunchScreen();
        }
    }
}
