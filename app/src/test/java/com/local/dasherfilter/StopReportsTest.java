package com.local.dasherfilter;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Switch;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowDialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * After the app stopped unexpectedly (a crash, or Android found it not responding): on the next start, Android's
 * record of the last day's stops is read (API 30+; before that, the app's own crash note), and each is kept for a day
 * as its kind and code locations only, never an exception's message or a trace's other text. With diagnostics after
 * each dash on, they go with the next summary; otherwise the homepage shows one line offering a report, and only the
 * user's Send sends it. Clear history removes them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class StopReportsTest extends AndroidAdapterTestBase {
    private static final String LINE = AppName.NAME + " stopped unexpectedly last time";

    /** A crash whose messages name a customer and an address, as an exception's message could. */
    private static RuntimeException crash() {
        IllegalArgumentException cause = new IllegalArgumentException("Deliver to Jane Doe, 100 Example St");
        cause.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.local.dasherfilter.OfferParser", "parse", "OfferParser.java", 412)});
        RuntimeException crash = new RuntimeException("crashed reading Jane Doe's order", cause);
        crash.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.local.dasherfilter.OfferFilterService", "read", "OfferFilterService.java",
                        1200),
                new StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 99)});
        return crash;
    }

    private File note() {
        return new File(app.getFilesDir(), StopReports.NOTE);
    }

    /** The crash handler as the crashing process runs it: the note, then Android's own handler. */
    private List<Throwable> crashNow(RuntimeException crash) {
        List<Throwable> handedOn = new ArrayList<>();
        StopReports.handler(note(), "0.4.73", (thread, error) -> handedOn.add(error))
                .uncaughtException(Thread.currentThread(), crash);
        return handedOn;
    }

    /** Android's record of how a process of the app ended. */
    private void exited(int reason, long at, int importance, String trace) {
        ShadowActivityManager.ApplicationExitInfoBuilder exit = ShadowActivityManager.ApplicationExitInfoBuilder
                .newBuilder().setReason(reason).setTimestamp(at).setImportance(importance)
                .setProcessName(app.getPackageName()).setPid(4321);
        if (trace != null) exit.setTraceInputStream(new ByteArrayInputStream(trace.getBytes(StandardCharsets.UTF_8)));
        Shadows.shadowOf(app.getSystemService(ActivityManager.class)).addApplicationExitInfo(exit.build());
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }

    @Test
    public void aCrashIsKeptAsItsTypesAndCodeLocationsNeverItsMessage() throws Exception {
        RuntimeException crash = crash();
        assertEquals("Android's own handler still ends the process", Collections.singletonList(crash),
                crashNow(crash));
        String written = new String(Files.readAllBytes(note().toPath()), StandardCharsets.UTF_8);
        assertFalse(written, written.contains("Jane"));
        exited(ApplicationExitInfo.REASON_CRASH, System.currentTimeMillis(),
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND, null);

        StopReports.check(app);
        String section = StopReports.section(app);
        assertTrue(section, section.contains("== Recent stops (last 24 hours; types and code locations only)"));
        assertTrue(section, section.contains("crash, app in front\njava.lang.RuntimeException\n"
                + "  at com.local.dasherfilter.OfferFilterService.read(OfferFilterService.java:1200)\n"
                + "  at android.os.Handler.dispatchMessage(Handler.java:99)\n"
                + "  caused by java.lang.IllegalArgumentException at "
                + "com.local.dasherfilter.OfferParser.parse(OfferParser.java:412)"));
        assertTrue("the minute, in UTC", section.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}Z · crash.*"));
        for (String personal : new String[] {"Jane", "Example", "crashed reading", "Deliver to"}) {
            assertFalse(personal, section.contains(personal));
        }
        assertFalse("the note goes once it was read", note().exists());
        assertTrue(StopReports.unacknowledged(app));
        assertTrue("Share report and attached diagnostics carry it too",
                DiagnosticLog.fullReport(app).contains("crash, app in front"));

        // Read once: the next start adds nothing.
        StopReports.check(app);
        assertEquals(1, occurrences(StopReports.section(app), "crash, app in front"));
    }

    @Test
    public void anAnrKeepsOnlyTheMainThreadsCodeLocations() {
        String trace = "----- pid 4321 at 2026-10-06 10:00:00.000 -----\n"
                + "Cmd line: com.local.dasherfilter\n"
                + "\"Signal Catcher\" daemon prio=10 tid=2 Runnable\n"
                + "  at com.local.dasherfilter.Elsewhere.run(Elsewhere.java:1)\n"
                + "\n"
                + "\"main\" prio=5 tid=1 Blocked\n"
                + "  | group=\"main\" sCount=1 ucsCount=0 flags=1 obj=0x72a1 self=0xb400\n"
                + "  at com.local.dasherfilter.MainActivity.refresh(MainActivity.java:1350)\n"
                + "  - waiting to lock <0x0abc> (a java.lang.Object) held by thread 12\n"
                + "  at com.local.dasherfilter.MainActivity$1.run(MainActivity.java:79)\n"
                + "  at android.os.Handler.handleCallback(Handler.java:958)\n"
                + "\n"
                + "\"Binder:4321_2\" prio=5 tid=12 Native\n"
                + "  at com.local.dasherfilter.Secret.leak(Secret.java:1)\n";
        exited(ApplicationExitInfo.REASON_ANR, System.currentTimeMillis() - 60_000L,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED, trace);

        StopReports.check(app);
        String section = StopReports.section(app);
        assertTrue(section, section.contains("not responding (ANR), app in the background\nmain thread\n"
                + "  at com.local.dasherfilter.MainActivity.refresh(MainActivity.java:1350)\n"
                + "  at com.local.dasherfilter.MainActivity$1.run(MainActivity.java:79)\n"
                + "  at android.os.Handler.handleCallback(Handler.java:958)\n"));
        for (String other : new String[] {"waiting to lock", "Secret.leak", "Elsewhere", "Cmd line", "group=",
                "pid 4321"}) {
            assertFalse(other, section.contains(other));
        }
    }

    @Test
    public void onlyCrashesAndAnrsOfTheLastDayAreKept() {
        long now = System.currentTimeMillis();
        int front = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        exited(ApplicationExitInfo.REASON_CRASH, now - 25 * 3_600_000L, front, null);
        exited(ApplicationExitInfo.REASON_USER_REQUESTED, now - 3_600_000L, front, null);
        exited(ApplicationExitInfo.REASON_LOW_MEMORY, now - 3_600_000L, front, null);
        exited(ApplicationExitInfo.REASON_EXIT_SELF, now - 60_000L, front, null);
        StopReports.check(app);
        assertEquals("", StopReports.section(app));
        assertFalse(StopReports.unacknowledged(app));

        // A later one is still read: what was seen before it stays seen.
        exited(ApplicationExitInfo.REASON_CRASH_NATIVE, now - 30_000L, front, null);
        StopReports.check(app);
        assertTrue(StopReports.section(app).contains("native crash, app in front"));
    }

    @Test
    @Config(sdk = 26)
    public void beforeAndroid11TheAppsOwnCrashNoteIsTheRecord() {
        crashNow(crash());
        StopReports.check(app);
        String section = StopReports.section(app);
        assertTrue(section, section.contains("crash\njava.lang.RuntimeException\n"
                + "  at com.local.dasherfilter.OfferFilterService.read(OfferFilterService.java:1200)"));
        assertFalse(section.contains("Jane"));
        assertFalse(note().exists());
    }

    @Test
    @Config(sdk = 26)
    public void aCrashNoteOlderThanADayIsDiscarded() throws Exception {
        crashNow(crash());
        JSONObject old = new JSONObject(new String(Files.readAllBytes(note().toPath()), StandardCharsets.UTF_8));
        old.put("at", System.currentTimeMillis() - 25 * 3_600_000L);
        Files.write(note().toPath(), old.toString().getBytes(StandardCharsets.UTF_8));
        StopReports.check(app);
        assertEquals("", StopReports.section(app));
        assertFalse(note().exists());
    }

    // ---- The homepage ----

    private static View withContentDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = withContentDescription(((ViewGroup) view).getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collect(View view, Class<? extends View> type, List<View> out) {
        if (type.isInstance(view)) out.add(view);
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                collect(((ViewGroup) view).getChildAt(i), type, out);
            }
        }
    }

    private static void refreshed() {
        StopReports.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
    }

    @Test
    public void withTheOptInOffTheHomepageOffersOneReportThatOnlyTheUsersSendSends() throws Exception {
        FakeFeedbackTransport service = FakeFeedbackTransport.installed();
        crashNow(crash());
        exited(ApplicationExitInfo.REASON_CRASH, System.currentTimeMillis(),
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND, null);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            refreshed();
            View line = withContentDescription(content, LINE + ". Send report.");
            assertNotNull("one line on the homepage", line);
            assertTrue(line.isShown());
            assertNotNull(findText(content, LINE));
            assertEquals("nothing is sent by itself", 0, service.count());

            line.performClick();
            settle();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(dialog.isShowing());
            View decor = dialog.getWindow().getDecorView();
            List<View> chips = new ArrayList<>();
            collect(decor, RadioButton.class, chips);
            for (View chip : chips) {
                RadioButton one = (RadioButton) chip;
                assertEquals(one.getText().toString(), one.getText().toString().equals("Bug"), one.isChecked());
            }
            Switch attach = find(decor, Switch.class);
            assertEquals("Attach masked diagnostics", attach.getText().toString());
            assertTrue("diagnostics attached in advance", attach.isChecked());
            refreshed();
            assertFalse("offered once", line.isShown());
            assertEquals("still nothing sent before Send", 0, service.count());

            EditText words = find(decor, EditText.class);
            words.setText("It closed while I was declining.");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Feedback.flush();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(service.count() >= 1);
            StringBuilder sent = new StringBuilder();
            for (JSONObject request : service.requests()) {
                assertEquals("feedback", request.getString("kind"));
                assertEquals("bug", request.getString("category"));
                assertTrue(request.getBoolean("diagnosticsConsented"));
                sent.append(Feedback.unframed(request.getString("diagnostics")));
            }
            assertEquals("It closed while I was declining.", service.requests().get(0).getString("message"));
            assertTrue(sent.toString().contains("== Recent stops (last 24 hours; types and code locations only)"));
            assertTrue(sent.toString().contains("OfferFilterService.read(OfferFilterService.java:1200)"));
            assertFalse(sent.toString().contains("Jane"));
        }
        // The next start offers it no more.
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            refreshed();
            View line = withContentDescription(activity.get().findViewById(android.R.id.content),
                    LINE + ". Send report.");
            assertTrue(line == null || !line.isShown());
        }
    }

    @Test
    public void withTheOptInOnNoLineShowsAndTheStopGoesWithTheNextSummary() throws Exception {
        FakeFeedbackTransport service = FakeFeedbackTransport.installed();
        Feedback.setAfterDash(app, true);
        crashNow(crash());
        exited(ApplicationExitInfo.REASON_CRASH, System.currentTimeMillis(),
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND, null);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            refreshed();
            View line = withContentDescription(activity.get().findViewById(android.R.id.content),
                    LINE + ". Send report.");
            assertTrue("no line: the summary carries it", line == null || !line.isShown());
        }
        assertTrue(StopReports.unacknowledged(app));
        long start = System.currentTimeMillis() - 3_600_000L;
        DashSummary.summarize(app, start, start + 3_000_000L, DashSummary.End.DASH_OVER);
        Feedback.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, service.count());
        String summary = Feedback.unframed(service.requests().get(0).getString("diagnostics"));
        assertTrue(summary, summary.contains("== Recent stops (last 24 hours; types and code locations only)"));
        assertTrue(summary, summary.contains("crash, app in front"));
        assertFalse(summary.contains("Jane"));
        assertFalse("sent with the summary: not offered again", StopReports.unacknowledged(app));
    }

    @Test
    public void clearHistoryRemovesStopSummariesAndUnsentAutomaticDiagnostics() throws Exception {
        crashNow(crash());
        exited(ApplicationExitInfo.REASON_CRASH, System.currentTimeMillis(),
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND, null);
        StopReports.check(app);
        assertFalse(StopReports.section(app).isEmpty());
        // A later crash's note, not yet read, and an automatic summary waiting for a network.
        crashNow(crash());
        Feedback.setAfterDash(app, true);
        FakeFeedbackTransport.installed().down = true;
        FeedbackOutbox.submitAutomatic(app, FeedbackOutbox.item(Feedback.newToken(), Feedback.Kind.DIAGNOSTICS,
                "general", true, FeedbackOutbox.TEXT, "", true, Collections.singletonList("summary\n")));
        FeedbackOutbox.flush();
        File outbox = new File(app.getFilesDir(), FeedbackOutbox.DIR);
        assertEquals(1, outbox.listFiles((dir, name) -> name.endsWith("-a.json")).length);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            StopReports.flush();
            View content = activity.get().findViewById(android.R.id.content);
            iconButton(content, "Settings").performClick();
            settle();
            shownButton(content, "Clear history").performClick();
            settle();
            AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle();
            StopReports.flush();
        }
        assertEquals("", StopReports.section(app));
        assertFalse(StopReports.unacknowledged(app));
        assertFalse(note().exists());
        assertEquals(0, outbox.listFiles((dir, name) -> name.endsWith(".json")).length);
        assertTrue("the opt-in itself stays the user's", Feedback.afterDashOn(app));
        assertNull(app.getSharedPreferences("stop_reports", Context.MODE_PRIVATE).getString("kept", null));
    }
}
