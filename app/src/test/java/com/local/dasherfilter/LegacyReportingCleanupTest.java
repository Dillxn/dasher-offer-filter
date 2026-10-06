package com.local.dasherfilter;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What the retired GitHub connection and its report queues left on a phone goes once, whatever version it skipped
 * from, and a fresh install never gets one of their settings files. Simulated Android only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
public class LegacyReportingCleanupTest {
    private static final String TOKEN = "ghu_retiredConnectionSecret123";
    private static final String PASTED = "github_pat_retiredPastedSecret456";
    private static final String LOGIN = "retired-login-name";
    private static final String CODE = "WDJB-MJHT";

    private Application app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        // No update check reaches the network from these screens.
        Updater.setEnabled(app, false);
        LegacyReportingCleanup.forgetCache();
        LegacyReportingCleanup.delete = File::delete;
        DiagnosticLog.clear(app);
    }

    @After
    public void restore() {
        LegacyReportingCleanup.delete = File::delete;
        LegacyReportingCleanup.forgetCache();
    }

    /** Every key the retired stack kept, as an older version left them. */
    private void seedEverything() throws IOException {
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                .putString("device_code", "device-code-value").putString("user_code", CODE)
                .putLong("code_expires_at", 1).putInt("interval_s", 5).putString("access_token", TOKEN)
                .putLong("access_expires_at", 2).putString("refresh_token", "ghr_refreshSecret")
                .putLong("refresh_expires_at", 3).putString("login", LOGIN).putString("note", "a note").commit();
        app.getSharedPreferences("github_history", Context.MODE_PRIVATE).edit()
                .putLong("ended_at", 4).putString("ended_why", "disconnected in Settings").commit();
        app.getSharedPreferences("reports", Context.MODE_PRIVATE).edit()
                .putString("token", PASTED).putBoolean("via_github", true).putLong("day", 5)
                .putInt("automatic_today", 1).putInt("by_user_today", 2).putInt("diagnostics_today", 3)
                .putInt("last_diagnostics", 41).putString("seen", "{\"abc\":1}").putLong("last_sent_at", 6)
                .putInt("last_issue", 40).putString("last_error", "GitHub refused the report").putLong(
                        "dropped_reports", 7).commit();
        app.getSharedPreferences("dash_diagnostics", Context.MODE_PRIVATE).edit()
                .putBoolean("on", true).putLong("started_at", 8).putLong("last_offer_at", 9)
                .putString("last_dash", "2026-10-01 18:02–21:47").commit();
        File outbox = new File(app.getFilesDir(), LegacyReportingCleanup.OUTBOX);
        assertTrue(outbox.mkdirs());
        write(new File(outbox, "0000000001000-000001.json"), "{\"title\":\"[offer-report] x\",\"body\":\"Order for Jane\"}");
        write(new File(outbox, "0000000001000-000002-d.json"), "{\"token\":\"" + TOKEN + "\"}");
        write(new File(outbox, "0000000001000-000003.json.tmp"), "{\"body\":\"half written\"}");
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        ComponentName retired = new ComponentName(app, "com.local.dasherfilter.ReportJobService");
        jobs.schedule(new JobInfo.Builder(7244, retired).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).build());
        jobs.schedule(new JobInfo.Builder(7245, retired).setMinimumLatency(60_000).setPersisted(true).build());
        Updater.prefs(app).edit().putString("advertised_via", "GitHub").putString("advertised", "0.4.72").commit();
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void assertNothingLeft() {
        for (String name : LegacyReportingCleanup.PREFS) {
            assertFalse(name + " settings file", LegacyReportingCleanup.prefsFile(app, name).exists());
            assertTrue(name + " in memory", app.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty());
        }
        assertFalse(new File(app.getFilesDir(), LegacyReportingCleanup.OUTBOX).exists());
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        assertNull(jobs.getPendingJob(7244));
        assertNull(jobs.getPendingJob(7245));
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    public void everyRetiredSettingQueuedReportAndJobGoesOnceWithOneGenericLine() throws IOException {
        seedEverything();
        assertTrue(LegacyReportingCleanup.prefsFile(app, "github").exists());

        assertTrue(LegacyReportingCleanup.run(app));

        assertNothingLeft();
        assertFalse(Updater.prefs(app).contains("advertised_via"));
        assertEquals("the rest of the updater's state stays", "0.4.72",
                Updater.prefs(app).getString("advertised", ""));
        assertTrue(app.getSharedPreferences(LegacyReportingCleanup.STATE, Context.MODE_PRIVATE)
                .getBoolean(LegacyReportingCleanup.DONE, false));
        String log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "retired GitHub reporting removed: 4 settings files, 3 unsent report "
                + "files, 2 scheduled jobs"));
        for (String secret : new String[] {TOKEN, PASTED, LOGIN, CODE, "ghr_refreshSecret", "Jane"}) {
            assertFalse(secret, log.contains(secret));
        }

        // Idempotent: a second trigger in this process, and after a restart, changes and logs nothing more.
        assertTrue(LegacyReportingCleanup.run(app));
        LegacyReportingCleanup.forgetCache();
        assertTrue(LegacyReportingCleanup.run(app));
        assertNothingLeft();
        log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "retired GitHub reporting removed"));
    }

    @Test
    public void aFailedStepLeavesTheFlagUnsetAndTheNextTriggerTriesAgain() throws IOException {
        seedEverything();
        LegacyReportingCleanup.delete = file -> false;

        assertFalse(LegacyReportingCleanup.run(app));
        assertFalse("no durable flag after a failure", app.getSharedPreferences(LegacyReportingCleanup.STATE,
                Context.MODE_PRIVATE).getBoolean(LegacyReportingCleanup.DONE, false));
        assertTrue("the queued report it could not delete is still there",
                new File(app.getFilesDir(), LegacyReportingCleanup.OUTBOX).exists());
        assertFalse("the steps that could run did", LegacyReportingCleanup.prefsFile(app, "github").exists());
        assertFalse(DiagnosticLog.read(app).contains("retired GitHub reporting removed"));

        LegacyReportingCleanup.delete = File::delete;
        assertTrue(LegacyReportingCleanup.run(app));
        assertNothingLeft();
        assertTrue(app.getSharedPreferences(LegacyReportingCleanup.STATE, Context.MODE_PRIVATE)
                .getBoolean(LegacyReportingCleanup.DONE, false));
    }

    @Test
    public void aFreshInstallGetsNoRetiredSettingsFileFromOpeningTheApp() {
        try (ActivityController<MainActivity> screen = Robolectric.buildActivity(MainActivity.class).setup()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            LegacyReportingCleanup.flush();
            screen.recreate();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            LegacyReportingCleanup.flush();
        }
        for (String name : LegacyReportingCleanup.PREFS) {
            assertFalse(name, LegacyReportingCleanup.prefsFile(app, name).exists());
        }
        assertFalse(new File(app.getFilesDir(), LegacyReportingCleanup.OUTBOX).exists());
        assertTrue("it ran, and keeps only its own flag", app.getSharedPreferences(LegacyReportingCleanup.STATE,
                Context.MODE_PRIVATE).getBoolean(LegacyReportingCleanup.DONE, false));
        assertFalse(DiagnosticLog.read(app).contains("retired GitHub reporting removed"));
    }

    @Test
    public void anArrivingUpdateCleansUpWithoutTheAppBeingOpened() throws IOException {
        seedEverything();
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        LegacyReportingCleanup.flush();
        assertNothingLeft();
    }

    @Test
    public void theOneTimePrivacyCleanupAlsoRemovesHalfWrittenReportFiles() throws IOException {
        File outbox = new File(app.getFilesDir(), LegacyReportingCleanup.OUTBOX);
        assertTrue(outbox.mkdirs());
        write(new File(outbox, "0000000001000-000001.json.tmp"), "{\"body\":\"Deliver to Sam P\"}");
        app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE).edit()
                .remove(DiagnosticLog.CLEANED_UP).commit();
        DiagnosticLog.forgetCache();
        try {
            assertTrue(LegacyReportingCleanup.outboxPresent(app));
            assertTrue(DiagnosticLog.cleanUpOnce(app));
            assertFalse(outbox.exists());
            assertFalse(LegacyReportingCleanup.outboxPresent(app));
        } finally {
            app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE).edit()
                    .putBoolean(DiagnosticLog.CLEANED_UP, true).commit();
            DiagnosticLog.forgetCache();
        }
    }
}
