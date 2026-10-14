package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.job.JobScheduler;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.Switch;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * Two 0.6.18 changes the notice carries, through the real homepage. The notice ends with one unchecked switch asking
 * for diagnostics after each dash (notes/0.6.18/diagnostics-ask.md; it stays opt-in): accepted with it on, Settings'
 * switch is on; accepted with it off, nothing changes. And no update check is scheduled or run before the current notice is
 * accepted (pilot safety Q8); accepting it runs the first.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class NoticeAskAndUpdateGateTest extends AndroidAdapterTestBase {
    private final AtomicInteger reads = new AtomicInteger();
    private CountDownLatch read;

    @Before public void notYetAccepted() throws Exception {
        Updater.awaitIdle(5_000);
        Updater.prefs(app).edit().clear().commit();
        Feedback.setAfterDash(app, false);
        ConsentedTestApp.forget(app);
        read = new CountDownLatch(1);
        // A feed that only counts: any read is a check that reached the network.
        Updater.feedReader = (address, out, limit) -> {
            reads.incrementAndGet();
            read.countDown();
            throw new java.io.IOException("no network in tests");
        };
        // Checks on, as on a phone (setEnabled schedules, which says no before acceptance).
        Updater.setEnabled(app, true);
        app.getSystemService(JobScheduler.class).cancelAll();
    }

    @After public void restore() throws Exception {
        Updater.awaitIdle(5_000);
        Updater.feedReader = UpdateTransport::download;
        Updater.setEnabled(app, false);
        Feedback.setAfterDash(app, false);
        ConsentedTestApp.accept(app);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static Switch ask(View content) {
        Switch ask = content.findViewById(NoticePage.DIAGNOSTICS_ID);
        assertNotNull("the notice's diagnostics switch", ask);
        assertEquals(Consent.DIAGNOSTICS_ASK, String.valueOf(ask.getText()));
        assertEquals("Share anonymous diagnostics after each dash (helps fix it for your phone)", Consent.DIAGNOSTICS_ASK);
        return ask;
    }

    // ---- The diagnostics ask ----

    @Test public void theSwitchShowsUncheckedAndAcceptingWithItOnTurnsSettingsSwitchOn() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            Switch ask = ask(content);
            assertTrue(ask.isShown());
            assertFalse("unchecked: it stays opt-in", ask.isChecked());
            ask.setChecked(true);
            assertFalse("nothing changes before the notice is accepted", Feedback.afterDashOn(app));
            shownButton(content, Consent.ACCEPT).performClick();
            idle();
            assertTrue(Consent.accepted(app));
            assertTrue("Settings' switch is on", Feedback.afterDashOn(app));
        }
    }

    @Test public void acceptingWithItOffChangesNothing() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertFalse(ask(content).isChecked());
            shownButton(content, Consent.ACCEPT).performClick();
            idle();
            assertTrue(Consent.accepted(app));
            assertFalse("still off", Feedback.afterDashOn(app));
        }
        // A switch already on stays on: the notice's off never turns it off.
        Feedback.setAfterDash(app, true);
        app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, Consent.VERSION - 1).commit();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertFalse("unchecked every time the notice shows", ask(content).isChecked());
            shownButton(content, Consent.ACCEPT).performClick();
            idle();
            assertTrue(Feedback.afterDashOn(app));
        }
    }

    // ---- The update check waits for the notice ----

    @Test public void noUpdateCheckIsScheduledOrRunBeforeTheNoticeIsAcceptedAndAcceptingRunsTheFirst()
            throws Exception {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            activity.resume();
            idle();
            // Every path that checks: opening, resuming, a connection, the jobs' own trigger.
            Updater.check(app, UpdateCadence.Trigger.OPENED, null).get(5, TimeUnit.SECONDS);
            Updater.check(app, UpdateCadence.Trigger.PERIODIC, null).get(5, TimeUnit.SECONDS);
            Updater.check(app, true, null).get(5, TimeUnit.SECONDS);
            Updater.schedule(app);
            Updater.awaitIdle(5_000);
            assertEquals("nothing reached the network", 0, reads.get());
            assertTrue("nothing scheduled", app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty());

            View content = activity.get().findViewById(android.R.id.content);
            shownButton(content, Consent.ACCEPT).performClick();
            idle();
            assertTrue("accepting runs the first check", read.await(5, TimeUnit.SECONDS));
            Updater.awaitIdle(5_000);
            assertTrue(reads.get() >= 1);
            assertFalse("and schedules the periodic one",
                    app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty());
        }
    }

    /**
     * An install that accepted the notice before (25) and not yet this one (26): no check either, as nothing of
     * Dasher's is read until then.
     */
    @Test public void anEarlierNoticeAcceptedIsNotEnough() throws Exception {
        app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, Consent.VERSION - 1).commit();
        assertFalse(Consent.accepted(app));
        Updater.check(app, UpdateCadence.Trigger.OPENED, null).get(5, TimeUnit.SECONDS);
        Updater.schedule(app);
        Updater.awaitIdle(5_000);
        assertEquals(0, reads.get());
        assertTrue(app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty());
    }
}
