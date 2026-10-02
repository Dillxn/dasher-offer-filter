package com.local.dasherfilter;

import android.app.Notification;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Looper;
import android.view.View;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.time.Duration;
import java.util.List;
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
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Diagnostics and problem reports, updates, GitHub sign-in, tips and split screen with Dasher, through real Android
 * adapters.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class AndroidAdapterReportsAndUpdatesTest extends AndroidAdapterTestBase {
    @Test
    public void retryCreatesAnActualAndroidOneShotJob() {
        Updater.setEnabled(app, true);
        Updater.retry(app, 60000L);

        JobInfo job = app.getSystemService(JobScheduler.class).getPendingJob(UPDATE_RETRY_JOB_ID);
        assertNotNull(job);
        assertFalse(job.isPeriodic());
        assertEquals(60000L, job.getMinLatencyMillis());
    }

    @Test
    public void aFewMinutesOfShoppingNeverPushTheOffersOutOfAReport() {
        DiagnosticLog.log(app, "screen", "offer|OFFER-MARKER $7.90 7.2 mi 21 min");
        String big = String.join("", Collections.nCopies(3900, "z"));
        for (int i = 0; i < 40; i++) DiagnosticLog.logScreen(app, "other labels=[Item " + i + " " + big + "]");
        String report = DiagnosticLog.report(app);
        assertTrue("the offer is still in the report", report.contains("OFFER-MARKER"));
        assertTrue(report.contains("== Dasher's other screens (newest)"));
        assertTrue("the newest screen is there", report.contains("Item 39 "));
        assertTrue(String.valueOf(report.length()), report.length() <= 60_000 + 40);
        // Each section's window starts at a whole line.
        String screens = report.substring(report.indexOf("== Dasher's other screens (newest)\n") + 35);
        assertTrue(screens, screens.startsWith("[older entries omitted]\n20") || screens.startsWith("20"));
    }

    @Test
    public void screenCaptureIsAutomaticAndKeepsOnlyTheLastDay() throws Exception {
        assertTrue("on from the start, with no switch", DiagnosticLog.isEnabled(app));

        // An entry from two days ago is dropped; today's stays.
        String old = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", java.util.Locale.US)
                .format(new java.util.Date(System.currentTimeMillis() - 2 * DiagnosticLog.KEEP_MS));
        java.io.File log = new java.io.File(app.getFilesDir(), "offer-filter-diagnostics.log");
        java.nio.file.Files.write(log.toPath(),
                (old + " [screen] other labels=[Two days old]\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DiagnosticLog.log(app, "screen", "other labels=[Aisle 12]");
        String kept = DiagnosticLog.read(app);
        assertTrue(kept, kept.contains("Aisle 12"));
        assertFalse(kept, kept.contains("Two days old"));

        // The diagnostic report still carries updater status, and says capture is automatic.
        Updater.status(app, "Synthetic updater error for test");
        String report = DiagnosticLog.report(app);
        assertTrue(report.contains("Synthetic updater error for test"));
        assertTrue(report.contains("Screen text capture: automatic"));
    }

    @Test
    public void installResultForAnotherSessionIsIgnored() {
        Updater.prefs(app).edit().putInt("session", 7).commit();
        Updater.status(app, "Installing 9.9.9");

        new UpdateReceiver().onReceive(app, installResult(6, PackageInstaller.STATUS_FAILURE));
        assertEquals("Installing 9.9.9", Updater.status(app));

        new UpdateReceiver().onReceive(app, installResult(7, PackageInstaller.STATUS_FAILURE));
        assertTrue(Updater.status(app).startsWith("Android installation failed"));
    }

    @Test
    public void reportIncludesEverySavedRuleAndNotificationAccess() {
        FilterStore.save(app, new FilterSettings(true, 2000, 150, 30, 100, 3, true, 0));
        FilterStore.recordAccepted(app, new OfferSnapshot(2500, 6.0, 25, 2));
        String report = DiagnosticLog.report(app);
        for (String field : Arrays.asList("Auto-decline saved: true", "flat cents=2000", "per-mile cents=150",
                "per-minute cents=30", "per-stop cents=100", "max stops=3", "rising offers=true",
                "highest accepted cents=2500", "hotspot proximity hundredths/mi=0", "minimum scale percent=100",
                "learned from manual declines=none", "score by area=false")) {
            assertTrue("saved rule missing: " + field, report.contains(field));
        }
        assertTrue(report.contains("Current when this report was generated; not a reconstructed historical baseline."));
        assertTrue(report.contains("learning now=on"));
        assertTrue(report.contains("$1.00 per stop"));
        assertTrue(report.contains("best accepted=$1.00/min, $4.17/mi, $12.50/stop"));
        assertTrue(report.contains("Notification access granted: false"));
    }

    @Test
    public void shareReportCarriesTheDecisionHistory() throws Exception {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            findButton(activity.get().findViewById(android.R.id.content), "Share report").performClick();

            Intent chooser = null;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (chooser == null && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                chooser = Shadows.shadowOf(app).getNextStartedActivity();
                if (chooser == null) Thread.sleep(5);
            }
            assertNotNull("the background report opens the chooser when ready", chooser);
            assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
            Intent sent = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
            assertEquals(Intent.ACTION_SEND, sent.getAction());
            assertTrue(sent.getStringExtra(Intent.EXTRA_SUBJECT).startsWith("Offer Filter diagnostics"));
            String body = sent.getStringExtra(Intent.EXTRA_TEXT);
            assertTrue(body.contains("== Decision history"));
            assertTrue(body.contains("DECLINE | pay $7.90 | needed $10.80"));
        }
    }

    @Test
    public void reportingNeedsSendProblemReportsAndThenSendsTheOfferWithTheUsersNote() throws Exception {
        DecisionLog.record(app, declinedEntry());
        // Signed in to GitHub, as after Connect GitHub: reports are still off until the user turns them on.
        app.getSharedPreferences("github", Context.MODE_PRIVATE).edit().putString("access_token", "ghu_test").commit();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            openTicket(content);
            assertNull("no report button until reports are on", shownButton(content, "Report this offer"));
            activity.get().onBackPressed();

            iconButton(content, "Settings").performClick();
            settle();
            android.widget.Switch reports = (android.widget.Switch) findButton(content, "Send problem reports");
            assertTrue("shown once GitHub is connected", reports.isShown());
            assertFalse("off by default", reports.isChecked());
            assertFalse(ReportOutbox.enabled(app));
            reports.setChecked(true);
            settle();
            assertTrue(ReportOutbox.enabled(app));
            assertNotNull(shownTextContaining(content, "On · no reports sent yet"));
            iconButton(content, "Back").performClick();
            settle();

            openTicket(content);
            shownButton(content, "Report this offer").performClick();
            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            findEditText(dialog.getWindow().getDecorView()).setText("It paid $12, not $7.90");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ReportOutbox.flush();

            assertEquals(1, ReportOutbox.queued(app));
            java.io.File[] queued = new java.io.File(app.getFilesDir(), "report-outbox").listFiles();
            String report = new String(java.nio.file.Files.readAllBytes(queued[0].toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject item = new org.json.JSONObject(report);
            assertTrue(item.getString("title").startsWith("[offer-report] You reported: Declined $7.90"));
            assertTrue(item.getString("body").contains("It paid $12, not $7.90"));
            assertTrue(item.getString("body").contains("2 stops (7.2 mi)"));
        }
    }

    @Test
    public void gitHubIsOneRowThatConnectsWithACodeAndAsksBeforeDisconnecting() {
        String shipped = GitHubConnect.clientId;
        try {
            GitHubConnect.clientId = "";
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                assertNull(findTextContaining(activity.get().findViewById(android.R.id.content), "GitHub"));
            }

            GitHubConnect.clientId = "Iv1.test";
            GitHubConnect.disconnect(app);
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                View content = activity.get().findViewById(android.R.id.content);
                android.widget.Button row = (android.widget.Button) findButton(content, "Connect GitHub");
                assertNotNull("one row, saying what a tap does", row);
                assertNull("nothing else about GitHub", findButton(content, "Disconnect GitHub"));
                assertNull(findTextContaining(content, "Not connected"));

                // GitHub sent a code: the row shows it, and a tap opens GitHub with it copied, or stops.
                app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                        .putString("device_code", "device").putString("user_code", "WDJB-MJHT")
                        .putLong("code_expires_at", System.currentTimeMillis() + 600_000L).commit();
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
                assertTrue(row.getText().toString(), row.getText().toString().contains("Enter WDJB-MJHT on GitHub"));
                row.performClick();
                android.app.AlertDialog dialog = (android.app.AlertDialog)
                        org.robolectric.shadows.ShadowDialog.getLatestDialog();
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                android.content.ClipboardManager clipboard = app.getSystemService(android.content.ClipboardManager.class);
                assertEquals("WDJB-MJHT", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
                assertEquals(Intent.ACTION_VIEW, opened.getAction());
                assertEquals("https://github.com/login/device", opened.getDataString());

                // Stopping forgets the code.
                row.performClick();
                dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
                dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(GitHubConnect.State.OFF, GitHubConnect.state(app));
                assertEquals("Connect GitHub", row.getText().toString());

                // Connected: says as whom; a tap asks before disconnecting.
                app.getSharedPreferences("github", Context.MODE_PRIVATE).edit()
                        .putString("access_token", "ghu_test").putString("login", "Dillxn").commit();
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
                assertEquals("GitHub\nConnected as Dillxn", row.getText().toString());
                row.performClick();
                dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
                assertEquals("asks first", GitHubConnect.State.CONNECTED, GitHubConnect.state(app));
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(GitHubConnect.State.OFF, GitHubConnect.state(app));
                assertNull(app.getSharedPreferences("github", Context.MODE_PRIVATE).getString("access_token", null));
                assertEquals("Connect GitHub", row.getText().toString());
            }
        } finally {
            GitHubConnect.clientId = shipped;
            GitHubConnect.disconnect(app);
        }
    }

    @Test
    public void tipsShowOnlyWithTheAuthorsNamesAndOpenTheirServiceFromOneRow() {
        String cashApp = Support.cashApp;
        String venmo = Support.venmo;
        String payPal = Support.payPal;
        try {
            Support.cashApp = "";
            Support.venmo = "";
            Support.payPal = "";
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                assertNull(findButton(activity.get().findViewById(android.R.id.content), "Tip"));
            }

            Support.cashApp = "OfferFilterDev";
            Support.venmo = "Offer-Filter";
            try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
                View content = activity.get().findViewById(android.R.id.content);
                View tip = findButton(content, "Tip");
                assertNotNull("one row", tip);
                assertNull(findButton(content, "Tip with Cash App"));
                tip.performClick();
                org.robolectric.shadows.ShadowAlertDialog chooser = Shadows.shadowOf(
                        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
                List<String> offered = new ArrayList<>();
                for (CharSequence item : chooser.getItems()) offered.add(item.toString());
                assertEquals("a service without a name is not offered", Arrays.asList("Cash App", "Venmo"), offered);
                assertNull("nothing opens before a choice", Shadows.shadowOf(app).getNextStartedActivity());
                chooser.clickOnItem(0);
                Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
                assertEquals(Intent.ACTION_VIEW, opened.getAction());
                assertEquals("https://cash.app/$OfferFilterDev", opened.getDataString());
                tip.performClick();
                Shadows.shadowOf(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1);
                assertEquals("https://venmo.com/Offer-Filter?txn=pay&note=Offer%20Filter%20tip",
                        Shadows.shadowOf(app).getNextStartedActivity().getDataString());
            }
        } finally {
            Support.cashApp = cashApp;
            Support.venmo = venmo;
            Support.payPal = payPal;
        }
    }

    @Test
    public void turningProblemReportsOffStopsThemAndDiscardsWhatWaits() {
        reportsOn();
        assertTrue(ReportOutbox.fileByUser(app, declinedEntry(), "wrong"));
        ReportOutbox.flush();
        assertEquals(1, ReportOutbox.queued(app));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            iconButton(content, "Settings").performClick();
            settle();
            android.widget.Switch reports = (android.widget.Switch) findButton(content, "Send problem reports");
            assertTrue(reports.isChecked());
            reports.setChecked(false);
            settle();
            ReportOutbox.flush();
            assertFalse(ReportOutbox.enabled(app));
            assertEquals("what was waiting is discarded", 0, ReportOutbox.queued(app));
            assertFalse(reports.isChecked());
        }
    }

    @Test
    public void splitWithDasherSplitsTheScreenAtATapThenOpensDasherBelow() throws Exception {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull("no Dasher, no button", shownIcon(content, "Split screen with Dasher"));
        }
        dasherInstalled();
        DasherSplit.forget();
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            View split = shownIcon(content, "Split screen with Dasher");
            assertNotNull(split);

            // Screen reading is how Android is asked; without it, the tap only says so.
            split.performClick();
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().startsWith("Turn on screen reading"));
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());

            service.get().onServiceConnected();
            split.performClick();
            assertEquals(Collections.singletonList(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN),
                    Shadows.shadowOf(service.get()).getGlobalActionsPerformed());
            assertNull("Dasher waits for the split", Shadows.shadowOf(app).getNextStartedActivity());

            // The screen splits: Dasher opens in the other half, once.
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            activity.get().onMultiWindowModeChanged(true, activity.get().getResources().getConfiguration());
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertNotNull(opened);
            assertEquals("com.doordash.driverapp", opened.getComponent() != null
                    ? opened.getComponent().getPackageName() : opened.getPackage());
            assertTrue((opened.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0);
            activity.get().onMultiWindowModeChanged(true, activity.get().getResources().getConfiguration());
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            assertNull("already split: no button", shownIcon(content, "Split screen with Dasher"));
        } finally {
            service.destroy();
        }
    }

    @Test
    public void aPhoneThatWillNotSplitOpensRecentAppsAndDasherStillFollows() {
        dasherInstalled();
        DasherSplit.forget();
        List<String> asked = new ArrayList<>();
        DasherSplit.split = () -> {
            asked.add("split");
            return false;
        };
        DasherSplit.recents = () -> {
            asked.add("recents");
            return true;
        };
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            service.get().onServiceConnected();
            View content = activity.get().findViewById(android.R.id.content);
            shownIcon(content, "Split screen with Dasher").performClick();
            assertEquals("asked to split, then opened recent apps", Arrays.asList("split", "recents"), asked);
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
                    .startsWith("Tap Offer Filter's icon above its card and choose split screen."));
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());

            // The user splits it from recent apps half a minute later: Dasher still opens in the other half.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            activity.get().onMultiWindowModeChanged(true, activity.get().getResources().getConfiguration());
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertNotNull(opened);
            assertTrue((opened.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0);
        } finally {
            service.destroy();
            DasherSplit.forget();
        }
    }

    @Test
    public void theOnceASecondRefreshAsksAndroidOnlyEveryHalfMinute() {
        dasherInstalled();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNotNull(shownIcon(content, "Split screen with Dasher"));

            // Dasher goes from the phone while the page is open. Before, each second's refresh asked Android again
            // (with whether alerts and installs are allowed, and the location permissions), on the main thread.
            Shadows.shadowOf(app.getPackageManager()).removeActivity(
                    new android.content.ComponentName("com.doordash.driverapp", "com.doordash.driverapp.Home"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
            assertNotNull("not asked again yet", shownIcon(content, "Split screen with Dasher"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MainActivity.ASK_EVERY_MS));
            assertNull("asked again after half a minute", shownIcon(content, "Split screen with Dasher"));

            // Back from Android's settings, the page asks at once.
            dasherInstalled();
            activity.pause().resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(shownIcon(content, "Split screen with Dasher"));
        }
    }

    @Test
    public void aSplitLongAfterTheTapOpensNothing() {
        dasherInstalled();
        DasherSplit.forget();
        DasherSplit.split = () -> false;
        DasherSplit.recents = () -> true;
        ServiceController<OfferFilterService> service = Robolectric.buildService(OfferFilterService.class).create();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            service.get().onServiceConnected();
            View content = activity.get().findViewById(android.R.id.content);
            shownIcon(content, "Split screen with Dasher").performClick();
            ShadowSystemClock.advanceBy(Duration.ofMillis(DasherSplit.BY_HAND_MS + 1000));
            Shadows.shadowOf(activity.get()).setInMultiWindowMode(true);
            activity.get().onMultiWindowModeChanged(true, activity.get().getResources().getConfiguration());
            assertNull("an unrelated split later never opens Dasher", Shadows.shadowOf(app).getNextStartedActivity());
        } finally {
            service.destroy();
            DasherSplit.forget();
        }
    }

    @Test
    public void whileUpdatingTheScreenSaysSoAndTakesNoInputUntilItFails() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertNull(shownTextContaining(content, "Updating Offer Filter"));

            Updater.prefs(app).edit().putInt("session", 5).commit();
            Updater.installStarted(app, true);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            TextView title = shownTextContaining(content, "Updating Offer Filter");
            assertNotNull(title);
            View cover = (View) title.getParent();
            assertTrue("it takes every touch", cover.dispatchTouchEvent(
                    MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10, 10, 0)));
            activity.get().onBackPressed();
            assertFalse("Back does not interrupt it", activity.get().isFinishing());
            assertTrue(FilterStore.load(app).enabled);

            Updater.installationFailed(app, 1, "test");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertNull("a failed install gives the screen back", shownTextContaining(content, "Updating Offer Filter"));
        }
    }

    @Test
    public void afterAnUpdateStartedOnScreenOfferFilterOpensAgainOnce() {
        // Not on screen when the update began: nothing opens afterwards.
        Updater.installStarted(app, false);
        assertFalse(Updater.relaunchAfterUpdate(app));

        // On screen, but this is still the version that began the update: not yet.
        Updater.installStarted(app, true);
        assertFalse(Updater.relaunchAfterUpdate(app));

        // The new version is running: Offer Filter opens, and asking again brings back the same screen.
        Updater.prefs(app).edit().putLong("relaunch_from_code", 1).commit();
        assertTrue(Updater.relaunchAfterUpdate(app));
        Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
        assertEquals(MainActivity.class.getName(), opened.getComponent().getClassName());
        assertTrue((opened.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);

        // Once the screen is open, nothing more opens.
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertFalse(Updater.relaunchAfterUpdate(app));
        }
    }

    @Test
    public void anUpdateWaitsOnlyWhileTheUserIsMidTask() {
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            assertFalse("the main page does not hold an update back", activity.get().midTask());
            openTicket(content);
            assertTrue("reading a ticket does", activity.get().midTask());
            activity.get().onBackPressed();
            iconButton(content, "Settings").performClick();
            assertTrue("typing rules in Settings does", activity.get().midTask());
            iconButton(content, "Back").performClick();
            assertFalse(activity.get().midTask());
        }
    }
}
