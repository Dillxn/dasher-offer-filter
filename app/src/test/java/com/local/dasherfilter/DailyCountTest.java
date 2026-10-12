package com.local.dasherfilter;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.SharedPreferences;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The anonymous daily count (DailyCount): the request is the protocol's six fields in order and nothing else (Android's
 * org.json, so here and not in the plain DailyCountProtocolTest), nothing before the current notice is accepted, at
 * most one count a UTC day (also with the phone's date set forward and back, and with the switch turned off and on
 * after a try whose answer may have been lost), "first" until the service has accepted one from this install, the
 * function from before counts (which answers 400) given up on for the day without marking the install counted, a
 * server error or no connection retried by the job alone the same day with the same token (a trigger never sets its
 * backoff back) and a new one the next day, a token never kept past its day, the switch in Settings (after the
 * diagnostics after each dash) that stops it and cancels its job, a job that retries through Android's backoff, never
 * a request on the main thread, and never a field the protocol does not name. The service is a fake
 * (ConsentedTestApp), whose count rules every request here is held to.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class DailyCountTest extends AndroidAdapterTestBase {
    /** Noon UTC on 12 October 2026. */
    private static final long NOON = 20_738L * DailyCount.DAY_MS + DailyCount.DAY_MS / 2;
    private static final Set<String> PROTOCOL = new HashSet<>(Arrays.asList("kind", "event", "countToken", "first",
            "appVersion", "appVersionCode"));

    private FakeFeedbackTransport service;
    private final long[] now = {NOON};

    @Before
    public void countsOn() {
        service = FakeFeedbackTransport.installed();
        DailyCount.clock = () -> now[0];
        // Real time passing moves both clocks; only a test that sets the date by hand moves the wall clock alone.
        DailyCount.elapsed = () -> now[0];
        DailyCount.automatic = true;
    }

    @After
    public void countsOff() {
        DailyCount.flush();
        DailyCount.automatic = false;
        DailyCount.clock = System::currentTimeMillis;
        DailyCount.elapsed = DailyCount.BOOT_CLOCK;
        DailyCountJobService.forgetCache();
        for (JSONObject count : service.counts()) assertProtocol(count);
    }

    /** Every count is the protocol's six fields, never anything else. */
    private static void assertProtocol(JSONObject count) {
        Set<String> keys = new HashSet<>();
        for (Iterator<String> each = count.keys(); each.hasNext(); ) keys.add(each.next());
        assertEquals("only the protocol's fields: " + count, PROTOCOL, keys);
        assertEquals("count", count.optString("kind"));
        assertEquals("daily", count.optString("event"));
    }

    private void countNow() {
        DailyCount.trigger(app);
        DailyCount.flush();
    }

    private JobInfo job() {
        return app.getSystemService(JobScheduler.class).getPendingJob(DailyCountJobService.JOB_ID);
    }

    private SharedPreferences prefs() {
        return DailyCount.prefs(app);
    }

    private String token(int index) throws Exception {
        return service.counts().get(index).getString("countToken");
    }

    private boolean first(int index) throws Exception {
        return service.counts().get(index).getBoolean("first");
    }

    /** One run of the job, as Android starts it; returns whether it asked Android to retry with its backoff. */
    private boolean runJob() {
        ServiceController<DailyCountJobService> run = Robolectric.buildService(DailyCountJobService.class).create();
        assertTrue("its work runs off the main thread", run.get().onStartJob(null));
        DailyCount.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(Shadows.shadowOf(run.get()).getIsJobFinished());
        boolean retry = Shadows.shadowOf(run.get()).getIsRescheduleNeeded();
        run.destroy();
        return retry;
    }

    @Test
    public void theRequestIsTheSixFieldsOfTheProtocolInOrderAndNothingElse() throws Exception {
        String token = "0123456789abcdef0123456789abcdef";
        JSONObject body = DailyCount.body(token, true, "0.6.3", 95);
        assertEquals("{\"kind\":\"count\",\"event\":\"daily\",\"countToken\":\"" + token + "\",\"first\":true,"
                + "\"appVersion\":\"0.6.3\",\"appVersionCode\":95}", body.toString());
        assertProtocol(body);

        JSONObject later = DailyCount.body(token, false, "0.6.3", 95);
        assertFalse("counted before: no longer first", later.getBoolean("first"));
        assertTrue("a real boolean, never the word", later.get("first") instanceof Boolean);
        assertEquals("a version name Android does not give is sent as the service's own word for it", "unknown",
                DailyCount.body(token, true, null, 1).getString("appVersion"));
        for (String never : new String[] {"message", "diagnostics", "category", "reportToken", "partIndex",
                "partCount", "diagnosticsConsented", "device", "android", "maker", "locale", "timeZone"}) {
            assertFalse(never, body.has(never));
        }
    }

    @Test
    public void nothingIsSentOrScheduledBeforeTheNoticeIsAcceptedAndAcceptingItSendsTodaysCount() throws Exception {
        ConsentedTestApp.forget(app);
        countNow();
        assertEquals("not a request before the notice", 0, service.counts().size());
        assertNull("nor a job", job());
        assertFalse(prefs().contains(DailyCount.TOKEN));

        Consent.accept(app);
        DailyCount.flush();
        Feedback.flush();
        StopReports.flush();
        assertEquals("accepting the notice sends today's count", 1, service.counts().size());
        assertTrue(first(0));
        assertTrue(service.counted(token(0)));
        assertNotNull("and keeps the next day's waiting", job());
    }

    @Test
    public void oneCountAUtcDayFirstUntilTheServiceHasCountedThisInstall() throws Exception {
        countNow();
        assertEquals(1, service.counts().size());
        JSONObject count = service.counts().get(0);
        assertTrue("never counted before", count.getBoolean("first"));
        assertTrue(DailyCount.validToken(count.getString("countToken")));
        assertEquals(Updater.version(app), count.getString("appVersion"));
        assertEquals(Feedback.versionCode(app), count.getLong("appVersionCode"));
        assertTrue(prefs().getBoolean(DailyCount.COUNTED, false));
        assertFalse("the token goes once the day's count is finished", prefs().contains(DailyCount.TOKEN));
        assertTrue("never among the feedback submissions", service.requests().isEmpty());

        // The app opening, a service connecting: nothing more today, even an hour before the day ends.
        countNow();
        now[0] = NOON + DailyCount.DAY_MS / 2 - 3_600_000L;
        countNow();
        assertEquals("one count a UTC day", 1, service.counts().size());

        JobInfo job = job();
        assertNotNull("the next day's job waits", job);
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.getNetworkType());
        assertTrue("kept across a restart", job.isPersisted());
        assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, job.getBackoffPolicy());
        assertEquals(DailyCount.FIRST_RETRY_MS, job.getInitialBackoffMillis());
        assertTrue("not before the next UTC day: " + job.getMinLatencyMillis(),
                job.getMinLatencyMillis() >= DailyCount.DAY_MS / 2);
        assertTrue("within its first hours: " + job.getMinLatencyMillis(),
                job.getMinLatencyMillis() <= DailyCount.DAY_MS / 2 + DailyCount.NEXT_DAY_SPREAD_MS);

        // The next UTC day: one more, no longer first, under a token of its own.
        now[0] = NOON + DailyCount.DAY_MS;
        countNow();
        assertEquals(2, service.counts().size());
        assertFalse("counted before: no longer first", first(1));
        assertNotEquals("a fresh token each day", token(0), token(1));

        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[" + DailyCount.LOG + "] daily count sent"));
        assertFalse("never the token", log.contains(token(0)) || log.contains(token(1)));
    }

    @Test
    public void theFunctionFromBeforeCountsGivesUpForTheDayAndKeepsFirstForTheNext() throws Exception {
        // The deployed function answers 400 to every count.
        service.answer(400, "{\"ok\":false,\"error\":\"invalid_feedback\"}");
        countNow();
        assertEquals(1, service.counts().size());
        assertTrue(first(0));
        countNow();
        assertEquals("not retried today", 1, service.counts().size());
        assertFalse("not counted", prefs().getBoolean(DailyCount.COUNTED, false));
        assertFalse(prefs().contains(DailyCount.TOKEN));
        assertTrue(DiagnosticLog.read(app).contains("daily count not accepted (status 400): not retried today"));

        now[0] += DailyCount.DAY_MS;
        countNow();
        assertEquals(2, service.counts().size());
        assertTrue("still first: the service never counted this install", first(1));
        assertNotEquals(token(0), token(1));
        assertTrue("the new function counts it", prefs().getBoolean(DailyCount.COUNTED, false));
    }

    @Test
    public void everyRefusalTheProtocolNamesEndsTheDayWithoutARetry() throws Exception {
        int day = 0;
        for (int status : new int[] {400, 413, 415, 422, 429}) {
            now[0] = NOON + day++ * DailyCount.DAY_MS;
            int before = service.counts().size();
            service.answer(status, "{\"ok\":false}", status == 429 ? 600_000L : 0);
            countNow();
            countNow();
            assertEquals(status + ": one request, not retried today", before + 1, service.counts().size());
            assertTrue(status + ": the install stays uncounted", first(before));
            assertFalse(prefs().getBoolean(DailyCount.COUNTED, false));
        }
    }

    @Test
    public void aServerErrorOrNoConnectionIsRetriedByTheJobTheSameDayWithTheSameTokenAndANewOneTheNextDay()
            throws Exception {
        service.answer(503, "{\"ok\":false}", 60_000L);
        countNow();
        assertEquals(1, service.counts().size());
        assertTrue(prefs().contains(DailyCount.TOKEN));
        JobInfo retry = job();
        assertNotNull("a job to try again", retry);
        assertEquals(DailyCount.FIRST_RETRY_MS, retry.getMinLatencyMillis());
        assertTrue(DiagnosticLog.read(app).contains("daily count will retry: status 503"));

        // The app opening, a service connecting: the retry is the job's, and its backoff is never set back.
        now[0] += 60_000L;
        countNow();
        countNow();
        assertEquals("a trigger leaves a waiting retry to the job", 1, service.counts().size());
        assertSame("the waiting retry is not replaced", retry, job());

        // The job retries the same day under the same token; counted then, and no more today.
        now[0] += 3_600_000L;
        assertFalse("counted: nothing to retry", runJob());
        assertEquals(2, service.counts().size());
        assertEquals("the same day's token", token(0), token(1));
        assertTrue(first(1));
        countNow();
        assertEquals(2, service.counts().size());

        // No connection, then the day ends before any try gets through: the next day has a token of its own.
        now[0] = NOON + DailyCount.DAY_MS;
        service.offline();
        countNow();
        assertEquals("a new day's first try is the trigger's", 3, service.counts().size());
        assertTrue(DiagnosticLog.read(app).contains("daily count will retry: no connection"));
        service.down = true;
        countNow();
        assertEquals("left to the job", 3, service.counts().size());
        assertTrue("Android tries again with its backoff", runJob());
        assertEquals(4, service.counts().size());
        assertEquals("the same day, the same token", token(2), token(3));
        service.down = false;
        now[0] = NOON + 2 * DailyCount.DAY_MS;
        countNow();
        assertEquals(5, service.counts().size());
        assertNotEquals("a new day, a new token", token(3), token(4));
        assertFalse("counted the first day", first(4));
    }

    @Test
    public void aTokenNeverOutlivesItsDayEvenWhileARaisedNoticeHoldsCountsBack() {
        service.answer(503, "{\"ok\":false}", 0);
        countNow();
        assertTrue(prefs().contains(DailyCount.TOKEN));

        // An update raises the notice. That day the token stays, only to retry the day's count once it is accepted.
        ConsentedTestApp.forget(app);
        countNow();
        assertTrue(prefs().contains(DailyCount.TOKEN));
        // The next UTC day a service connects, the notice still waiting: the old token goes, and nothing is sent.
        now[0] += DailyCount.DAY_MS;
        countNow();
        assertFalse("a code never outlives its UTC day", prefs().contains(DailyCount.TOKEN));
        assertFalse(prefs().contains(DailyCount.TOKEN_DAY));
        assertEquals("nothing before the notice", 1, service.counts().size());
    }

    @Test
    public void thePhonesDateSetForwardOrBackSendsNoSecondCountTheSameDay() throws Exception {
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        long[] booted = {5_000_000L};
        DailyCount.elapsed = () -> booted[0];
        countNow();
        assertEquals(1, service.counts().size());

        // The date set a day forward and the app opened, then set back past today and opened again, then right.
        for (long wall : new long[] {NOON + DailyCount.DAY_MS, NOON - DailyCount.DAY_MS, NOON}) {
            now[0] = wall;
            countNow();
            assertEquals("still the day that really is: done (" + wall + ")", 1, service.counts().size());
            assertTrue(DailyCount.finishedToday(app));
        }
        JobInfo next = job();
        assertNotNull(next);
        assertTrue("the next day's job is half a real day away: " + next.getMinLatencyMillis(),
                next.getMinLatencyMillis() >= DailyCount.DAY_MS / 2
                        && next.getMinLatencyMillis() <= DailyCount.DAY_MS / 2 + DailyCount.NEXT_DAY_SPREAD_MS);

        // A day of real time later the next count goes, whatever the date says.
        booted[0] += DailyCount.DAY_MS;
        now[0] = NOON - DailyCount.DAY_MS;
        countNow();
        assertEquals(2, service.counts().size());
        assertFalse(first(1));
        assertNotEquals(token(0), token(1));
        countNow();
        assertEquals(2, service.counts().size());

        // After a reboot the boot's own clock starts again: the wall clock is all there is, and its day is not done.
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 8);
        booted[0] = 1_000L;
        now[0] = NOON + 3 * DailyCount.DAY_MS;
        countNow();
        assertEquals(3, service.counts().size());
        // Nor can a date set forward after it, in that boot, send another.
        now[0] += DailyCount.DAY_MS;
        countNow();
        assertEquals(3, service.counts().size());
    }

    @Test
    public void theJobRetriesThroughAndroidsBackoffAndThenWaitsForTheNextDay() throws Exception {
        service.answer(503, "{\"ok\":false}", 0);
        ServiceController<DailyCountJobService> first = Robolectric.buildService(DailyCountJobService.class).create();
        assertTrue("its work runs off the main thread", first.get().onStartJob(null));
        DailyCount.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, service.counts().size());
        assertTrue(Shadows.shadowOf(first.get()).getIsJobFinished());
        assertTrue("Android tries again with its backoff", Shadows.shadowOf(first.get()).getIsRescheduleNeeded());

        ServiceController<DailyCountJobService> second = Robolectric.buildService(DailyCountJobService.class).create();
        assertTrue(second.get().onStartJob(null));
        DailyCount.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, service.counts().size());
        assertEquals("the same day's token", token(0), token(1));
        assertTrue(Shadows.shadowOf(second.get()).getIsJobFinished());
        assertFalse("counted: nothing to retry", Shadows.shadowOf(second.get()).getIsRescheduleNeeded());
        JobInfo next = job();
        assertNotNull("the next day's run", next);
        assertTrue(next.getMinLatencyMillis() >= DailyCount.DAY_MS / 2);

        // A run the same day sends nothing and keeps the next day's.
        ServiceController<DailyCountJobService> third = Robolectric.buildService(DailyCountJobService.class).create();
        assertTrue(third.get().onStartJob(null));
        DailyCount.flush();
        assertEquals(2, service.counts().size());
        assertFalse(Shadows.shadowOf(third.get()).getIsRescheduleNeeded());
        assertNotNull(job());
        first.destroy();
        second.destroy();
        third.destroy();
    }

    @Test
    public void theSwitchOffSendsNothingCancelsTheJobAndFinishesADayWhoseCountWasTried() throws Exception {
        assertTrue("on by default", DailyCount.on(app));
        service.answer(503, "{\"ok\":false}", 0);
        countNow();
        assertNotNull(job());
        assertTrue(prefs().contains(DailyCount.TOKEN));

        DailyCount.setOn(app, false);
        assertFalse("off at once", DailyCount.on(app));
        DailyCount.flush();
        assertNull("its job is cancelled", job());
        assertFalse("the day's token is discarded", prefs().contains(DailyCount.TOKEN));
        assertTrue("a day whose count was tried is finished: the service may have counted it, its answer lost",
                DailyCount.finishedToday(app));
        assertTrue(DiagnosticLog.read(app).contains("daily count turned off"));

        countNow();
        ServiceController<DailyCountJobService> stale = Robolectric.buildService(DailyCountJobService.class).create();
        assertTrue(stale.get().onStartJob(null));
        DailyCount.flush();
        assertEquals("nothing more is sent while it is off", 1, service.counts().size());
        assertFalse("a run that finds it off asks for no other", Shadows.shadowOf(stale.get()).getIsRescheduleNeeded());
        assertNull("and schedules none", job());
        stale.destroy();

        // On again the same day: never a second count that day, only the next day's job.
        DailyCount.setOn(app, true);
        DailyCount.flush();
        assertEquals("one count a UTC day at most", 1, service.counts().size());
        JobInfo next = job();
        assertNotNull("the next day's job waits", next);
        assertTrue(next.getMinLatencyMillis() >= DailyCount.DAY_MS / 2);

        // The next UTC day: its count, under a token of its own, still first (the service never accepted one).
        now[0] += DailyCount.DAY_MS;
        countNow();
        assertEquals(2, service.counts().size());
        assertNotEquals(token(0), token(1));
        assertTrue(first(1));

        // Off and on again before any try that day: that day's count still goes.
        now[0] += DailyCount.DAY_MS;
        DailyCount.setOn(app, false);
        DailyCount.flush();
        assertFalse(DailyCount.finishedToday(app));
        DailyCount.setOn(app, true);
        DailyCount.flush();
        assertEquals(3, service.counts().size());
        assertFalse(first(2));
    }

    @Test
    public void nothingOfACountRunsOnTheMainThread() {
        List<Thread> threads = new ArrayList<>();
        service.during = () -> {
            synchronized (threads) {
                threads.add(Thread.currentThread());
            }
        };
        // The test runs on the main thread: the trigger hands the work over at once.
        assertTrue(Looper.getMainLooper().isCurrentThread());
        DailyCount.trigger(app);
        DailyCount.flush();
        now[0] += DailyCount.DAY_MS;
        ServiceController<DailyCountJobService> job = Robolectric.buildService(DailyCountJobService.class).create();
        assertTrue(job.get().onStartJob(null));
        DailyCount.flush();
        service.during = null;
        assertEquals("one from the trigger, one from the job", 2, threads.size());
        for (Thread thread : threads) assertNotSame(Looper.getMainLooper().getThread(), thread);
        job.destroy();
    }

    /** The Settings switch whose first line is {@code title}, or null. */
    private static Switch switchNamed(View view, String title) {
        if (view instanceof Switch) {
            String text = ((Switch) view).getText().toString();
            if (text.equals(title) || text.startsWith(title + "\n")) return (Switch) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Switch found = switchNamed(group.getChildAt(i), title);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void theAppOpeningCountsAndSettingsHasItsSwitchRightAfterTheDiagnosticsAfterEachDash() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            DailyCount.flush();
            assertEquals("the app opening sends today's count", 1, service.counts().size());

            View content = activity.get().findViewById(android.R.id.content);
            iconButton(content, "Settings").performClick();
            settle();
            Switch afterDash = switchNamed(content, "Share anonymous diagnostics after each dash");
            Switch count = switchNamed(content, DailyCount.SWITCH);
            assertNotNull(afterDash);
            assertNotNull("one switch for the daily count", count);
            assertEquals(DailyCount.SWITCH + "\n" + DailyCount.SWITCH_LINE, count.getText().toString());
            assertEquals("Send an anonymous daily count\n"
                    + "The app's version and whether it was counted before, once a day", count.getText().toString());
            ViewGroup group = (ViewGroup) afterDash.getParent();
            assertEquals("in the same group", group, count.getParent());
            assertEquals("right after the diagnostics after each dash", group.indexOfChild(afterDash) + 1,
                    group.indexOfChild(count));
            assertTrue("on by default", count.isChecked());
            assertTrue("a 48 dp target", count.getMinHeight() >= new Ui(activity.get()).dp(48));

            count.performClick();
            assertFalse(count.isChecked());
            DailyCount.flush();
            assertFalse(DailyCount.on(app));
            assertNull("turning it off cancels its job", job());
            assertTrue(DiagnosticLog.read(app).contains("daily count turned off"));

            // Off, the app opening again sends nothing.
            now[0] += DailyCount.DAY_MS;
            try (ActivityController<MainActivity> again = Robolectric.buildActivity(MainActivity.class).setup()) {
                DailyCount.flush();
                assertEquals(1, service.counts().size());
                assertFalse(switchNamed(again.get().findViewById(android.R.id.content), DailyCount.SWITCH)
                        .isChecked());
            }
        }
    }
}
