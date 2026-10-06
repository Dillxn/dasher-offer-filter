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
 * Diagnostics and offer reports, updates, tips and split screen with Dasher, through real Android adapters.
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
    public void feedbackThatFoundNoNetworkWaitsForAnAndroidJobThatSendsIt() throws Exception {
        FakeFeedbackTransport service = FakeFeedbackTransport.installed();
        service.down = true;
        String token = Feedback.sendFeedback(app, Feedback.Category.BUG, "Sent once a network is back.", false, null);
        Feedback.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("tried once at once", 1, service.count());

        JobInfo job = app.getSystemService(JobScheduler.class).getPendingJob(FeedbackOutbox.JOB_ID);
        assertNotNull("a real Android job waits for a network", job);
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.getNetworkType());
        assertTrue("kept across a restart", job.isPersisted());
        assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, job.getBackoffPolicy());
        assertEquals(FeedbackOutbox.FIRST_RETRY_MS, job.getInitialBackoffMillis());

        service.down = false;
        ServiceController<FeedbackJobService> controller = Robolectric.buildService(FeedbackJobService.class).create();
        assertTrue("its work runs off the main thread", controller.get().onStartJob(null));
        FeedbackOutbox.flush();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(Shadows.shadowOf(controller.get()).getIsJobFinished());
        assertFalse("nothing left to retry", Shadows.shadowOf(controller.get()).getIsRescheduleNeeded());
        assertEquals(2, service.count());
        assertEquals("the same submission, never a second one", token,
                service.requests().get(1).getString("reportToken"));
        assertEquals(0, FeedbackOutbox.pending(app));
        controller.destroy();
    }

    /** Android 11 and later also keep their own record of the stop; earlier ones have only the app's note. */
    private void androidRecordsTheCrash() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        Shadows.shadowOf(app.getSystemService(android.app.ActivityManager.class)).addApplicationExitInfo(
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                        .setReason(android.app.ApplicationExitInfo.REASON_CRASH)
                        .setTimestamp(System.currentTimeMillis())
                        .setImportance(android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
                        .build());
    }

    @Test
    public void aCrashOffersOneReportOnTheHomepageThatOnlyTheUserSends() {
        IllegalStateException crash = new IllegalStateException("Deliver to Jane Doe, 100 Example St");
        crash.setStackTrace(new StackTraceElement[] {new StackTraceElement(
                "com.local.dasherfilter.OfferFilterService", "read", "OfferFilterService.java", 1200)});
        StopReports.handler(new java.io.File(app.getFilesDir(), StopReports.NOTE), "0.4.73", (thread, error) -> { })
                .uncaughtException(Thread.currentThread(), crash);
        androidRecordsTheCrash();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            StopReports.flush();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            View content = activity.get().findViewById(android.R.id.content);
            View line = shownIcon(content, AppName.NAME + " stopped unexpectedly last time. Send report.");
            assertNotNull("one line on the homepage", line);
            String kept = StopReports.section(app);
            assertTrue(kept, kept.contains("java.lang.IllegalStateException\n"
                    + "  at com.local.dasherfilter.OfferFilterService.read(OfferFilterService.java:1200)"));
            assertFalse("never the error's message", kept.contains("Jane"));

            line.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue("the feedback dialog, for the user to send", dialog.isShowing());
            assertNull("offered once", shownIcon(content, AppName.NAME + " stopped unexpectedly last time. Send report."));
            assertEquals("nothing is sent before Send", 0, FakeFeedbackTransport.installed().count());
        }
    }

    @Test
    public void installResultForAnotherSessionIsIgnored() {
        Updater.prefs(app).edit().putInt("session", 7).commit();
        Updater.status(app, "Installing 9.9.9");

        new UpdateReceiver().onReceive(app, installResult(6, PackageInstaller.STATUS_FAILURE));
        assertEquals("Installing 9.9.9", Updater.status(app));

        new UpdateReceiver().onReceive(app, installResult(7, PackageInstaller.STATUS_FAILURE));
        // Settings says it in plain words; Android's code and words stay in the log (BETA-20).
        assertEquals(Updater.INSTALL_FAILED, Updater.status(app));
        assertTrue(DiagnosticLog.read(app).contains("Android installation failed (" + PackageInstaller.STATUS_FAILURE
                + ")"));
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
    public void reportThisOfferIsAlwaysOnTheTicketAndSaysWhatItSends() throws Exception {
        FakeFeedbackTransport service = FakeFeedbackTransport.installed();
        DecisionLog.Entry declined = new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(790, 7.2, 21, 2), 1080, OfferRule.Result.DECLINE, "dollars per mile",
                DecisionLog.Action.DECLINE_TAPPED, true, Arrays.asList("$7.90", "Deliver to Sam P",
                "2 stops (7.2 mi) • 21 min"));
        DecisionLog.record(app, declined);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            openTicket(content);
            View report = shownButton(content, "Report this offer");
            assertNotNull("no account or switch needed first", report);
            report.performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(dialog.isShowing());
            String said = ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
            assertEquals("the dialog says what it sends, and Send is the consent", "Sends this offer's figures, "
                    + "decision and masked read lines, your current rules and what the adaptive minimum learned from "
                    + "offers you accepted or declined, the app's and Android's versions and the minute it was "
                    + "decided, with your note. No account. Masking can miss details.", said);
            View decor = dialog.getWindow().getDecorView();
            assertNotNull(findText(decor, "Don't include customer, payment or account details."));
            List<String> chips = new ArrayList<>();
            android.widget.RadioButton wrongDecline = null;
            List<android.widget.Button> buttons = new ArrayList<>();
            collectButtons(decor, buttons);
            for (android.widget.Button button : buttons) {
                if (!(button instanceof android.widget.RadioButton)) continue;
                chips.add(button.getText().toString());
                if (button.getText().toString().equals("Wrong decline")) {
                    wrongDecline = (android.widget.RadioButton) button;
                }
            }
            assertEquals(Arrays.asList("Misread", "Wrong decline", "Wrong accept", "Other"), chips);
            assertEquals("nothing leaves before Send", 0, service.count());

            wrongDecline.performClick();
            find(decor, EditText.class).setText("It declined an offer above my minimum.");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Feedback.flush();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals(1, service.count());
            org.json.JSONObject request = service.requests().get(0);
            assertEquals("problem", request.getString("kind"));
            assertEquals("bug", request.getString("category"));
            assertEquals("It declined an offer above my minimum.", request.getString("message"));
            assertTrue(request.getBoolean("diagnosticsConsented"));
            assertEquals(1, request.getInt("partCount"));
            org.json.JSONObject sent = new org.json.JSONObject(Feedback.unframed(request.getString("diagnostics")));
            assertEquals("offer", sent.getString("report"));
            assertEquals("wrong_decline", sent.getString("problem"));
            assertTrue(sent.getString("decided").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}Z"));
            assertTrue(sent.getString("android").startsWith("Android "));
            assertEquals("DECLINE — dollars per mile (" + DecisionLog.Action.DECLINE_TAPPED.label + ")",
                    sent.getString("decision"));
            org.json.JSONObject entry = sent.getJSONObject("entry");
            assertFalse("no learning steps", entry.has("steps"));
            assertFalse("no exact time", entry.has("at"));
            assertTrue(entry.has("outcome"));
            String evidence = entry.getJSONArray("evidence").toString();
            assertTrue(evidence, evidence.contains("$7.90"));
            assertFalse(evidence, evidence.contains("Sam"));
            org.json.JSONObject rules = sent.getJSONObject("rules");
            assertTrue(rules.has("inWords"));
            // What the dialog discloses: the learned minimums go with the rules.
            for (String learned : new String[] {"lastAcceptedCents", "declinedByHand", "bestAccepted"}) {
                assertTrue(learned, rules.has(learned));
            }
            assertFalse(request.toString().contains("Sam P"));

            android.app.AlertDialog done = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertFalse(dialog.isShowing());
            assertNotNull(findText(done.getWindow().getDecorView(),
                    "Reference: " + request.getString("reportToken").substring(0, 8)));
            assertNull("nothing is queued for the old GitHub outbox",
                    new java.io.File(app.getFilesDir(), "report-outbox").listFiles());
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
