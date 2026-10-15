package com.local.dasherfilter;

import android.app.Application;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityWindowInfo;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 0.6.24: the owner, 15 October 2026, 00:51 EDT: "freakin nav, man. still broken", and at 04:33 "report sent", after
 * more than an hour of dashing had rolled the log past it (it keeps about an hour). A navigation watch that went wrong
 * goes last when the log is trimmed and when a report is cut, for the day the log keeps; one whose page stayed goes
 * with the rest. Each look when Dasher is not in front lists Android's windows in fixed words: on 13 October Android
 * listed no active window 4 s after the page came up, and the report could not say what was there instead.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
public class DirectionsKeptTest {
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS XXX";
    private static final String PAGE = "GoToLocationInAppNavigationActivity";
    private static final long AT = 1_792_043_460_000L;

    private Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.forgetCache();
        DiagnosticLog.clear(app);
        DiagnosticLog.setEnabled(app, true);
        DiagnosticLog.cleanUpOnce(app);
    }

    private static String line(long at, String source, String message) {
        return new SimpleDateFormat(TIME_PATTERN, Locale.US).format(new Date(at)) + " [" + source + "] " + message
                + "\n";
    }

    /** A Directions line {@code ms} after {@code at}. */
    private static String directions(long at, long ms, String message) {
        return line(at + ms, DirectionsWatch.SOURCE, message);
    }

    /** The 13 October drop-off, as 0.6.24 logs it: the tap's watch, then the page's, which dropped back. */
    private static String droppedBack(long at) {
        return directions(at, -16_200, "watching 20 s after your tap on Dasher during a delivery; on OnADashActivity")
                + directions(at, -3_700, "+12.5 s OnADashActivity (new window)")
                + directions(at, 0, DirectionsWatch.PAGE_WATCH + PAGE + "; on OnADashActivity")
                + directions(at, 1, "+0.0 s " + PAGE + " (new window)")
                + directions(at, 2_000, "+2 s: win=full/-/dasher/100 screen=on locked=no; in front: Dasher " + PAGE)
                + directions(at, 2_300, "+2.3 s FrameLayout (new window)")
                + directions(at, 5_000, "+5 s: win=hidden/-/none/0 screen=on locked=no windows=app:dasher@0,0-100,100"
                + " | overlay:ours@0,40-4,60; in front: unknown")
                + directions(at, 5_800, "+5.8 s OnADashActivity (new window)")
                + directions(at, 5_801, "dropped back to OnADashActivity after 5 s (window new)")
                + directions(at, 10_000, "+10 s: win=full/-/dasher/100 screen=on locked=no; in front: Dasher "
                + "OnADashActivity")
                + directions(at, 20_000, "+20 s: win=full/-/dasher/100 screen=on locked=no; in front: Dasher "
                + "OnADashActivity");
    }

    /** A drop-off whose page worked: it stayed up. */
    private static String stayed(long at) {
        return directions(at, 0, DirectionsWatch.PAGE_WATCH + PAGE + "; on OnADashActivity")
                + directions(at, 1, "+0.0 s " + PAGE + " (new window)")
                + directions(at, 2_000, "+2 s: win=full/-/dasher/100 screen=on locked=no; in front: Dasher " + PAGE)
                + directions(at, 20_000, "+20 s: win=full/-/dasher/100 screen=on locked=no; in front: Dasher " + PAGE)
                + directions(at, 20_001, DirectionsWatch.STAYED + PAGE + " (window new)");
    }

    /** An hour of other lines after {@code at}: a dash's offers, Autopilot's plans. */
    private static String dashing(long at, int lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            out.append(line(at + 60_000L + i * 12_000L, i % 2 == 0 ? "status" : "autopilot",
                    "Pay $" + (i % 9 + 2) + ".45, miles 3.3, minutes 17, stops 2 declined: below the 55% bar " + i));
        }
        return out.toString();
    }

    // Before 0.6.24: the oldest lines went first, whatever they were, so the watch went within the hour.
    @Test
    public void aWatchWhosePageDroppedBackGoesLastWhenTheLogIsTrimmed() {
        String watch = droppedBack(AT);
        String rest = dashing(AT, 300);
        String log = watch + rest;
        for (boolean bytes : new boolean[] {false, true}) {
            int budget = 9_000;
            String kept = DiagnosticLog.fitLog(log, budget, bytes);
            assertTrue(kept.getBytes(StandardCharsets.UTF_8).length <= budget);
            assertTrue("the whole watch, the tap's lead-in too, in order: " + kept, kept.startsWith(watch));
            // The rest is the newest of the other lines.
            assertTrue(kept.endsWith(rest.substring(rest.length() - 2_000)));
            assertFalse(kept.contains("below the 55% bar 0\n"));
        }
    }

    @Test
    public void aWatchWhosePageStayedGoesWithTheRest() {
        String log = stayed(AT) + dashing(AT, 300);
        String kept = DiagnosticLog.fitLog(log, 9_000, false);
        assertFalse(kept, kept.contains(DirectionsWatch.PAGE_WATCH));
        // A tap's watch alone, with no page, is not kept either.
        String tap = directions(AT, 0, "watching 20 s after your tap on Directions during a delivery; on "
                + "OnADashActivity") + directions(AT, 20_000, "unknown in front");
        assertFalse(DiagnosticLog.fitLog(tap + dashing(AT, 300), 9_000, false).contains("unknown in front"));
    }

    @Test
    public void theNewestWatchesThatWentWrongFitTheirOwnBudgetOlderOnesFirstToGo() {
        StringBuilder log = new StringBuilder();
        List<String> watches = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String watch = droppedBack(AT + i * 600_000L);
            watches.add(watch);
            log.append(watch).append(dashing(AT + i * 600_000L, 40));
        }
        int one = watches.get(0).length();
        assertTrue("a watch fits several times: " + one, one * 2 < DiagnosticLog.TROUBLE_WATCH_CHARS);
        int fit = DiagnosticLog.TROUBLE_WATCH_CHARS / one;
        String kept = DiagnosticLog.fitLog(log.toString(), DiagnosticLog.TROUBLE_WATCH_CHARS + 1_000, false);
        for (int i = 0; i < watches.size(); i++) {
            assertEquals("watch " + i, i >= watches.size() - fit, kept.contains(watches.get(i)));
        }
        // Too small even for them: they too go oldest first, the newest left.
        assertEquals(watches.get(watches.size() - 1), DiagnosticLog.fitLog(log.toString(), one, false));
    }

    // Before 0.6.24, a page's watch was gone from the disk log, and so from Share report, after about an hour.
    @Test
    public void theDiskLogAndTheReportStillHaveItAfterAnHourOfDashing() {
        DiagnosticLog.log(app, DirectionsWatch.SOURCE, DirectionsWatch.PAGE_WATCH + PAGE + "; on OnADashActivity");
        DiagnosticLog.log(app, DirectionsWatch.SOURCE, "+0.0 s " + PAGE + " (new window)");
        DiagnosticLog.log(app, DirectionsWatch.SOURCE, "+5 s: win=hidden/-/none/0 screen=on locked=no windows=none; "
                + "in front: unknown");
        DiagnosticLog.log(app, DirectionsWatch.SOURCE, "dropped back to OnADashActivity after 5 s (window new)");
        DiagnosticLog.read(app);
        for (int i = 0; i < 400; i++) {
            DiagnosticLog.log(app, "status", "Pay $3.45, miles 3.3, minutes 17, stops 2 declined: below the bar " + i
                    + " " + "1234567890".repeat(6));
            if (i % 20 == 0) DiagnosticLog.read(app);
        }
        String disk = DiagnosticLog.read(app);
        assertFalse("the hour of lines rolled over: " + disk.length(), disk.contains("below the bar 0 "));
        assertTrue(disk.contains(DirectionsWatch.PAGE_WATCH + PAGE));
        assertTrue(disk.contains("dropped back to OnADashActivity after 5 s (window new)"));
        String report = DiagnosticLog.fullReport(app);
        assertTrue(report.contains(DirectionsWatch.PAGE_WATCH + PAGE));
        assertTrue(report.contains("windows=none; in front: unknown"));
        assertTrue(report.contains("dropped back to OnADashActivity after 5 s (window new)"));
    }

    private static AccessibilityWindowInfo window(int id, int type, Rect bounds, boolean active, boolean focused,
                                                  CharSequence title) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(id);
        shadow.setType(type);
        shadow.setBoundsInScreen(bounds);
        shadow.setActive(active);
        shadow.setFocused(focused);
        if (title != null) shadow.setTitle(title);
        return window;
    }

    @Test
    public void eachLookWithoutDasherInFrontListsTheWindowsInFixedWords() {
        Rect display = new Rect(0, 0, 1080, 2400);
        List<AccessibilityWindowInfo> listed = Arrays.asList(
                window(41, AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 0, 1080, 1152), false, false,
                        "Dasher"),
                window(5, AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, new Rect(0, 1152, 1080, 1248), false,
                        false, null),
                window(42, AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 1248, 1080, 2400), true, true,
                        "Offer Filter"),
                window(7, AccessibilityWindowInfo.TYPE_SYSTEM, new Rect(0, 0, 1080, 72), false, false, "Status bar"),
                window(8, AccessibilityWindowInfo.TYPE_SYSTEM, new Rect(90, 900, 990, 1500), false, false,
                        "Application Not Responding: com.doordash.driverapp"),
                window(9, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, new Rect(0, 960, 43, 1440), false,
                        false, null),
                window(10, AccessibilityWindowInfo.TYPE_INPUT_METHOD, new Rect(0, 1600, 1080, 2400), false, false,
                        "Gboard"),
                window(11, AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 0, 1080, 2400), false, false,
                        "Messages with Jane Doe"));
        String list = SplitWindows.list(listed, w -> w.getId() == 41 ? SplitWindows.Owner.DASHER
                : w.getId() == 42 || w.getId() == 9 ? SplitWindows.Owner.OURS : SplitWindows.Owner.OTHER, display);
        assertEquals("windows=app:dasher@0,0-100,48 | divider@0,48-100,52 | app:ours@0,52-100,100:active,focused"
                + " | system@0,0-100,3 | system@8,38-92,63:not-responding+dasher | overlay:ours@0,40-4,60"
                + " | keyboard@0,67-100,100 | app:other@0,0-100,100", list);
        // Never a window's own words, nor another app's name.
        assertFalse(list.contains("Jane") || list.contains("Gboard") || list.contains("Status")
                || list.contains("com."));
        assertEquals("windows=none", SplitWindows.list(Collections.<AccessibilityWindowInfo>emptyList(),
                w -> SplitWindows.Owner.OTHER, display));
        assertEquals("windows=unknown", SplitWindows.list(null, w -> SplitWindows.Owner.OTHER, display));
    }

    @Test
    public void aLongListSaysHowManyMore() {
        List<AccessibilityWindowInfo> listed = new ArrayList<>();
        for (int i = 0; i < SplitWindows.LIST_MAX + 3; i++) {
            listed.add(window(100 + i, AccessibilityWindowInfo.TYPE_SYSTEM, new Rect(0, 0, 10, 10), false, false,
                    null));
        }
        String list = SplitWindows.list(listed, w -> SplitWindows.Owner.OTHER, new Rect(0, 0, 1080, 2400));
        assertTrue(list, list.endsWith(" | +3 more"));
        assertEquals(SplitWindows.LIST_MAX, list.split(" \\| system").length);
    }
}
