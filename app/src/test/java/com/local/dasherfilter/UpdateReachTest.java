package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowSigningInfo;
import org.robolectric.util.ReflectionHelpers;

/**
 * BETA-04 and BETA-25: updates must reach strangers' phones. A dash whose end was never seen holds an automatic update
 * only up to a ceiling; a held update offers "Update ready · Install now" when no dash is on; an update that waits for
 * "Install unknown apps" says so once per version; the updating cover can be left after a minute; Settings speaks in
 * plain words. Every verification step stays.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdateReachTest extends AndroidAdapterTestBase {
    private static final long HOUR = 3_600_000L;
    private final AtomicInteger reads = new AtomicInteger();
    private PowerManager power;

    @Before public void prepare() throws Exception {
        Updater.awaitIdle(5_000);
        Updater.prefs(app).edit().clear().commit();
        // No check reaches the network unless a test hands it a feed (and turns checks on).
        Updater.setEnabled(app, false);
        Updater.clearReady(app);
        UpdatingCover.forget();
        app.getSharedPreferences("dashing", 0).edit().clear().commit();
        ActiveRouteStore.clear(app);
        Dashing.forgetCache();
        power = app.getSystemService(PowerManager.class);
        Shadows.shadowOf(power).setIsInteractive(true);
    }

    @After public void restore() throws Exception {
        Updater.awaitIdle(5_000);
        Updater.feedReader = UpdateTransport::download;
        Updater.clearReady(app);
        Updater.setEnabled(app, false);
        UpdatingCover.forget();
        ConsentedTestApp.accept(app);
    }

    /** A dash open since {@code hoursAgo}, its end never seen. */
    private void unendedDash(double hoursAgo) {
        long seen = System.currentTimeMillis() - (long) (hoursAgo * HOUR);
        app.getSharedPreferences("dashing", 0).edit().putLong("seen_at", seen).putLong("started_at", seen)
                .putBoolean("open", true).commit();
    }

    // ---- The dash hold's ceiling ----

    @Test public void anUnendedDashQuietForEightHoursWithTheScreenOffNoLongerHoldsTheUpdate() {
        unendedDash(9);
        assertTrue(Dashing.awaitingEnd(app));
        assertEquals("the screen on still holds it", Updater.AFTER_DASH, Updater.heldBack(app, false));
        Shadows.shadowOf(power).setIsInteractive(false);
        assertFalse(UpdateHold.holds(app));
        assertNull("the hold reached its ceiling", Updater.heldBack(app, false));
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("dash hold reached its ceiling: nothing of a dash seen for 9 h (no offer, waiting "
                + "or delivery screen), Dasher not in front, screen off"));
        assertTrue("the dash itself is not ended by it", Dashing.awaitingEnd(app));
    }

    @Test public void anySignOfADashOrAnOnScreenKeepsTheHold() {
        Shadows.shadowOf(power).setIsInteractive(false);
        unendedDash(7.9);
        assertEquals("under eight hours", Updater.AFTER_DASH, Updater.heldBack(app, false));
        unendedDash(12);
        ActiveRouteStore.save(app, new OfferSnapshot(900, 4.0, 18, 2));
        assertTrue("a route under way", UpdateHold.holds(app));
        assertEquals(Updater.AFTER_DASH, Updater.heldBack(app, false));
        ActiveRouteStore.clear(app);
        long future = System.currentTimeMillis() + HOUR;
        app.getSharedPreferences("dashing", 0).edit().putLong("seen_at", future).commit();
        assertTrue("a clock moved back is no basis for a ceiling", UpdateHold.holds(app));
        unendedDash(12);
        assertFalse(UpdateHold.holds(app));
        Shadows.shadowOf(power).setIsInteractive(true);
        assertTrue("the screen on", UpdateHold.holds(app));
        assertNull("the user's own check was never held by the dash", Updater.heldBack(app, true));
    }

    @Test public void pastTheCeilingTheHeldUpdateTakesTheFullFreshPathAgain() throws Exception {
        Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(true);
        Updater.setEnabled(app, true);
        PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        long code = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        JSONObject current = feed(code, installed.versionName);
        Updater.feedReader = (address, out, limit) -> {
            reads.incrementAndGet();
            out.write(current.toString().getBytes(StandardCharsets.UTF_8));
        };
        File apk = seedVerifiedReceipt(code);
        unendedDash(9);
        automaticCheck();
        assertEquals("screen on: the cheap wait, no feed read", 0, reads.get());
        assertEquals(Updater.AFTER_DASH, Updater.status(app));
        assertEquals("held, for the homepage", "99.0.0", Updater.heldVersion(app));

        Shadows.shadowOf(power).setIsInteractive(false);
        seedVerifiedReceipt(code);
        automaticCheck();
        assertEquals("past the ceiling the feed is read again", 1, reads.get());
        assertFalse("and its answer (nothing newer) removes the older ready file", apk.exists());
        assertNull(Updater.heldVersion(app));
    }

    // ---- "Update ready · Install now" ----

    @Test public void aHeldUpdateOffersInstallNowOnlyWhileNoDashIsOn() throws Exception {
        Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(true);
        Updater.prefs(app).edit().putString("ready_version", "99.0.0").commit();
        Dashing.seen(app);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull("a dash is on: it waits, as the automatic install does",
                    shownIcon(content, "Update ready. Install now."));
            unendedDash(1);
            tick();
            View line = shownIcon(content, "Update ready. Install now.");
            assertNotNull("no dash on for half an hour, its end never seen", line);
            Updater.setEnabled(app, true);
            Updater.feedReader = (address, out, limit) -> {
                reads.incrementAndGet();
                throw new java.net.UnknownHostException("offline");
            };
            line.performClick();
            Updater.awaitIdle(5_000);
            assertEquals("the tap is the user's own check: the feed is read again", 1, reads.get());
            tick();
            assertEquals("Couldn't reach the update server (no connection?). Tries again in 1 min.",
                    Updater.status(app));

            Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(false);
            activity.pause().resume();
            tick();
            assertNull("installs not allowed: the setup step asks for that instead",
                    shownIcon(content, "Update ready. Install now."));
        }
    }

    @Test public void settingsUpdatesRowOpensTheInstallSwitchWhileAnUpdateWaitsForIt() {
        Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(false);
        Updater.prefs(app).edit().putString("ready_version", "99.0.0").putString("status", Updater.BLOCKED).commit();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            iconButton(content, "Settings").performClick();
            tick();
            Button updates = shownButtonStarting(content, "Updates");
            assertNotNull(updates);
            assertTrue(updates.getText().toString(), updates.getText().toString().contains(Updater.BLOCKED));
            updates.performClick();
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, opened.getAction());
            assertEquals("package:" + app.getPackageName(), opened.getDataString());
        }
    }

    // ---- The notice when installs are not allowed ----

    @Test public void aBlockedUpdateIsAnnouncedOncePerVersionQuietly() {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadow = Shadows.shadowOf(manager);
        assertTrue(UpdateNotices.installsBlocked(app, 80));
        Notification posted = shadow.getNotification(UpdateNotices.BLOCKED_ID);
        assertNotNull(posted);
        assertEquals(UpdateNotices.BLOCKED_TITLE, Shadows.shadowOf(posted).getContentTitle().toString());
        assertEquals("Allow updates from " + AppName.NAME + " to install it.",
                Shadows.shadowOf(posted).getContentText().toString());
        NotificationChannel channel = manager.getNotificationChannel(UpdateNotices.CHANNEL_ID);
        assertEquals("low: no sound, no pop-up", NotificationManager.IMPORTANCE_LOW, channel.getImportance());
        assertEquals(OfferAlerts.smallIcon(app), posted.getSmallIcon().getResId());
        Intent tap = Shadows.shadowOf(posted.contentIntent).getSavedIntent();
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, tap.getAction());
        assertEquals("package:" + app.getPackageName(), tap.getDataString());
        assertTrue("immutable", Shadows.shadowOf(posted.contentIntent).isImmutable());

        manager.cancelAll();
        assertFalse("once per version, even after it was swiped away", UpdateNotices.installsBlocked(app, 80));
        assertNull(shadow.getNotification(UpdateNotices.BLOCKED_ID));
        shadow.setNotificationsEnabled(false);
        assertFalse(UpdateNotices.installsBlocked(app, 81));
        shadow.setNotificationsEnabled(true);
        assertTrue("a newer version, once notifications are on again", UpdateNotices.installsBlocked(app, 81));
        Updater.clearReady(app);
        assertNull("the update is in: the notice goes", shadow.getNotification(UpdateNotices.BLOCKED_ID));
    }

    // ---- The updating cover ----

    @Test public void theUpdatingCoverOffersAWayOutAfterAMinuteAndLeavesTheInstallToAndroid() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            Updater.prefs(app).edit().putInt("session", 5).commit();
            Updater.installStarted(app, true);
            tick();
            assertNotNull(shownTextContaining(content, "Updating " + AppName.NAME));
            assertNull("not in the first minute", shownButton(content, UpdatingCover.ESCAPE));
            activity.get().onBackPressed();
            assertFalse(activity.get().isFinishing());

            long committed = System.currentTimeMillis() - UpdatingCover.ESCAPE_AFTER_MS - 1_000;
            Updater.prefs(app).edit().putLong("committed_at", committed).commit();
            tick();
            Button escape = shownButton(content, UpdatingCover.ESCAPE);
            assertNotNull(escape);
            escape.performClick();
            tick();
            assertNull("the screen is given back", shownTextContaining(content, "Updating " + AppName.NAME));
            assertTrue("Android's installation is left alone", Updater.installing(app));
            assertTrue(DiagnosticLog.read(app).contains("updating cover lifted by the user"));
            assertNotNull("the page works again", shownIcon(content, "Settings"));

            Updater.prefs(app).edit().putLong("committed_at", System.currentTimeMillis()).commit();
            tick();
            assertNotNull("a new installation covers again", shownTextContaining(content, "Updating " + AppName.NAME));
        }
    }

    // ---- Plain words ----

    @Test public void settingsNamesNoExceptionWhenACheckFails() throws Exception {
        assertEquals("Couldn't reach the update server (no connection?). Tries again in 2 min.",
                Updater.failedInPlainWords(new java.net.UnknownHostException("dash-offer-filter-build.onrender.com"),
                        120_000));
        assertEquals("The update check didn't finish; nothing was installed. Tries again in 15 min.",
                Updater.failedInPlainWords(new java.io.IOException("Update checksum mismatch"), 900_000));
        Updater.setEnabled(app, true);
        Updater.feedReader = (address, out, limit) -> {
            throw new java.net.SocketTimeoutException("read timed out");
        };
        Updater.check(app, true, null).get(5, TimeUnit.SECONDS);
        Updater.awaitIdle(5_000);
        String shown = Updater.status(app);
        assertTrue(shown, shown.startsWith("Couldn't reach the update server"));
        assertFalse(shown, shown.contains("Exception"));
        assertTrue("the log keeps the detail", DiagnosticLog.read(app).contains("Update failed: SocketTimeoutException"));
    }

    // ---- Review fixes: the notice waits for acceptance, goes once allowed; Install now is never swallowed ----

    @Test public void aBlockedUpdateBeforeTheNoticeIsAcceptedPostsNothingThenOnceAfterIt() throws Exception {
        Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(false);
        ShadowNotificationManager notices = Shadows.shadowOf(app.getSystemService(NotificationManager.class));
        verifiedUpdate("99.0.0");
        ConsentedTestApp.forget(app);
        automaticCheck();
        assertEquals("held, as the blocked update", "99.0.0", Updater.heldVersion(app));
        assertEquals(Updater.BLOCKED, Updater.status(app));
        assertNull("before acceptance the paused reminder is the one thing posted",
                notices.getNotification(UpdateNotices.BLOCKED_ID));
        assertTrue(notices.getAllNotifications().isEmpty());

        ConsentedTestApp.accept(app);
        automaticCheck();
        assertNotNull("once accepted, it says so", notices.getNotification(UpdateNotices.BLOCKED_ID));
        app.getSystemService(NotificationManager.class).cancelAll();
        automaticCheck();
        assertNull("once per version", notices.getNotification(UpdateNotices.BLOCKED_ID));
    }

    @Test public void allowingUpdatesTakesTheNoticeAwayAndSettingsStopsAskingForTheSwitch() throws Exception {
        Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(false);
        ShadowNotificationManager notices = Shadows.shadowOf(app.getSystemService(NotificationManager.class));
        verifiedUpdate("99.0.0");
        automaticCheck();
        assertNotNull(notices.getNotification(UpdateNotices.BLOCKED_ID));
        assertEquals(Updater.BLOCKED, Updater.status(app));

        // Allowed from the notice itself: back on the homepage, it goes, and Settings' line follows. (Automatic
        // checks off here, so the one the page's opening starts does not install it meanwhile.)
        Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(true);
        Updater.setEnabled(app, false);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNull("no \"allow updates\" notice once they are allowed",
                    notices.getNotification(UpdateNotices.BLOCKED_ID));
            assertEquals(Updater.READY, Updater.status(app));
            assertTrue(DiagnosticLog.read(app).contains("updates from this app allowed; the held 99.0.0 can install"));
            iconButton(content, "Settings").performClick();
            tick();
            Button updates = shownButtonStarting(content, "Updates");
            assertTrue(updates.getText().toString(), updates.getText().toString().contains(Updater.READY));
            Shadows.shadowOf(app).clearNextStartedActivities();
            updates.performClick();
            assertNull("its tap checks now; no switch to open", Shadows.shadowOf(app).getNextStartedActivity());
            Updater.awaitIdle(5_000);
            assertTrue(DiagnosticLog.read(app).contains("check start manual=true"));
        }
    }

    @Test public void aTapOnInstallNowWhileTheOpeningCheckRunsWaitsForItAndThenChecksAfresh() throws Exception {
        Updater.setEnabled(app, true);
        PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        long code = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        JSONObject current = feed(code, installed.versionName);
        CountDownLatch slow = new CountDownLatch(1);
        Updater.feedReader = (address, out, limit) -> {
            if (reads.incrementAndGet() == 1) {
                try {
                    // The automatic check the app's opening started, on a cold server.
                    slow.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            }
            out.write(current.toString().getBytes(StandardCharsets.UTF_8));
        };
        Updater.prefs(app).edit().putLong("attempt_at", 0).putLong("next_check_at", 0).commit();
        Future<?> opening = Updater.check(app, UpdateCadence.Trigger.OPENED, null);
        long waited = 0;
        while (reads.get() == 0 && waited < 5_000) {
            Thread.sleep(10);
            waited += 10;
        }
        assertEquals("the opening check is reading the feed", 1, reads.get());
        Future<?> tapped = Updater.check(app, true, null);
        assertFalse("waits for the check under way", tapped.isDone());
        slow.countDown();
        opening.get(5, TimeUnit.SECONDS);
        tapped.get(5, TimeUnit.SECONDS);
        assertEquals("then the user's own check reads the feed afresh", 2, reads.get());
        assertTrue(DiagnosticLog.read(app).contains("check start manual=true"));
        Future<?> automatic = Updater.check(app, false, null);
        automatic.get(5, TimeUnit.SECONDS);
        assertEquals("an automatic request is still absorbed or spaced as before", 2, reads.get());
    }

    // ---- Review fixes: a confirmation still offers Install now; a blocked install is named, not looped ----

    @Test public void anUpdateWaitingForAndroidsConfirmationStaysOnTheHomepage() throws Exception {
        verifiedUpdate("99.0.0");
        automaticCheck();
        int session = Updater.prefs(app).getInt("session", -1);
        assertTrue("handed to Android", session >= 0);
        assertNull("handed over: no longer held", Updater.heldVersion(app));

        // A browser sideload's first self-update: the system installer is the installer of record, so Android asks.
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        new UpdateReceiver().onReceive(app, installResult(session, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, new Intent("synthetic.install.confirmation")));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("held again, for the user's confirmation", "99.0.0", Updater.heldVersion(app));
        assertNull(Shadows.shadowOf(app.getSystemService(NotificationManager.class))
                .getNotification(AndroidAdapterTestBase.UPDATE_NOTICE_ID));
        assertEquals("no notification posted: none is mentioned", Updater.CONFIRM, Updater.status(app));
        assertFalse(Updater.status(app).contains("notification"));
        // Android keeps its session sealed while it waits for the user.
        ReflectionHelpers.setField(app.getPackageManager().getPackageInstaller().getSessionInfo(session), "sealed",
                true);
        automaticCheck();
        assertEquals("the pending session's words say the same", Updater.CONFIRM, Updater.status(app));
        assertEquals("not a second session", session, Updater.prefs(app).getInt("session", -1));

        Updater.setEnabled(app, false);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = activity.get().findViewById(android.R.id.content);
            tick();
            assertNotNull("Install now opens it again", shownIcon(content, "Update ready. Install now."));
        }

        // With notifications allowed, its notice is posted and Settings points to it.
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        Updater.confirmation(app, new Intent("synthetic.install.confirmation"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(Shadows.shadowOf(app.getSystemService(NotificationManager.class))
                .getNotification(AndroidAdapterTestBase.UPDATE_NOTICE_ID));
        assertEquals(Updater.CONFIRM_NOTICED, Updater.status(app));
    }

    @Test public void anInstallationAndroidBlockedIsNamedAndNotOfferedAgainUntilTheUserRetries() throws Exception {
        verifiedUpdate("99.0.0");
        automaticCheck();
        int session = Updater.prefs(app).getInt("session", -1);
        assertTrue(session >= 0);
        new UpdateReceiver().onReceive(app, installResult(session, PackageInstaller.STATUS_FAILURE_BLOCKED)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "blocked by policy"));
        assertEquals(Updater.INSTALL_BLOCKED, Updater.status(app));
        assertTrue(Updater.INSTALL_BLOCKED.contains("Auto Blocker"));
        assertTrue("Android's words stay in the log", DiagnosticLog.read(app).contains(
                "Android installation failed (" + PackageInstaller.STATUS_FAILURE_BLOCKED + "): blocked by policy"));

        automaticCheck();
        assertEquals("the next automatic check does not loop it", Updater.INSTALL_BLOCKED, Updater.status(app));
        assertNull("nor offer Install now again", Updater.heldVersion(app));
        Updater.setEnabled(app, false);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            tick();
            assertNull(shownIcon(activity.get().findViewById(android.R.id.content), "Update ready. Install now."));
        }

        // The user's own retry (Updates in Settings) tries again, from the fresh feed.
        int before = reads.get();
        Updater.check(app, true, null).get(5, TimeUnit.SECONDS);
        Updater.awaitIdle(5_000);
        assertEquals(before + 1, reads.get());
        assertTrue(Updater.status(app), Updater.status(app).startsWith("Installing 99.0.0"));
    }

    @Test public void aGenericFailureStillOffersInstallNowInPlainWords() throws Exception {
        verifiedUpdate("99.0.0");
        automaticCheck();
        new UpdateReceiver().onReceive(app, installResult(Updater.prefs(app).getInt("session", -1),
                PackageInstaller.STATUS_FAILURE_ABORTED));
        automaticCheck();
        assertEquals(Updater.INSTALL_FAILED, Updater.status(app));
        assertEquals("a retry may work: it is offered", "99.0.0", Updater.heldVersion(app));
    }

    // ---- Review fixes: the ceiling counts on the boot's own clock ----

    @Test public void theCeilingCountsTheQuietOnTheBootsOwnClockSoAWallClockSetForwardCannotReachIt() {
        Shadows.shadowOf(power).setIsInteractive(false);
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        Dashing.forgetCache();
        try {
            Dashing.seen(app);
            // Seen a moment ago on this boot's clock, though the wall clock now says nine hours (set forward by hand).
            app.getSharedPreferences("dashing", 0).edit()
                    .putLong("seen_at", System.currentTimeMillis() - 9 * HOUR).commit();
            assertTrue(Dashing.awaitingEnd(app));
            assertTrue("the boot's own clock says moments, not hours", UpdateHold.holds(app));
            assertEquals(Updater.AFTER_DASH, Updater.heldBack(app, false));

            // Nine hours on this boot's clock: the ceiling.
            app.getSharedPreferences("dashing", 0).edit()
                    .putLong("seen_elapsed", SystemClock.elapsedRealtime() - 9 * HOUR).commit();
            assertFalse(UpdateHold.holds(app));

            // After a reboot (a new process) only the wall clock can say: nine hours by it reach the ceiling.
            Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 8);
            Dashing.forgetCache();
            app.getSharedPreferences("dashing", 0).edit()
                    .putLong("seen_elapsed", SystemClock.elapsedRealtime()).commit();
            assertFalse("across a reboot, by the wall clock", UpdateHold.holds(app));
            app.getSharedPreferences("dashing", 0).edit()
                    .putLong("seen_at", System.currentTimeMillis() - HOUR).commit();
            assertTrue(UpdateHold.holds(app));
        } finally {
            Dashing.forgetCache();
        }
    }

    // ---- Helpers ----

    /** A second and a bit: the page's once-a-second refresh runs. */
    private static void tick() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
    }

    private void automaticCheck() throws Exception {
        Updater.prefs(app).edit().putLong("attempt_at", 0).putLong("next_check_at", 0).commit();
        Updater.check(app, false, null).get(5, TimeUnit.SECONDS);
        Updater.awaitIdle(5_000);
    }

    private static JSONObject feed(long code, String version) throws Exception {
        return new JSONObject().put("packageName", "com.local.dasherfilter").put("versionCode", code)
                .put("versionName", version).put("sha256", "a".repeat(64)).put("size", 3)
                .put("encoding", "raw").put("apkUrl", "https://dash-offer-filter-build.onrender.com/OfferFilter.apk");
    }

    /**
     * Only the private in-process receipt verifiedApk leaves after it succeeded, as UpdaterHeldReadyTest seeds it: the
     * receipt can only skip the wait's feed read, never authorize an installation.
     */
    private File seedVerifiedReceipt(long code) throws Exception {
        File dir = new File(app.getFilesDir(), "updates");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        File apk = new File(dir, "OfferFilter.apk");
        if (!apk.isFile()) Files.write(apk.toPath(), new byte[] {1, 2, 3});
        Updater.Release release = new Updater.Release(feed(code + 1, "99.0.0"));
        Class<?> receipt = Class.forName(Updater.class.getName() + "$VerifiedReady");
        Constructor<?> constructor = receipt.getDeclaredConstructor(File.class, Updater.Release.class, long.class);
        constructor.setAccessible(true);
        ReflectionHelpers.setStaticField(Updater.class, "verifiedReady", constructor.newInstance(apk, release, code));
        return apk;
    }

    /**
     * A real verified update to {@code versionName} (versionCode one above this app's): its file in the updater's
     * folder, with the size and SHA-256 the feed names, and package, version and signer as Android reads them from
     * the archive, signed like this app. Every check of the APK runs on it; checks are turned on.
     */
    private File verifiedUpdate(String versionName) throws Exception {
        Updater.setEnabled(app, true);
        Signature signer = new Signature("3082011a30820102a003020102");
        SigningInfo signing = ReflectionHelpers.callConstructor(SigningInfo.class);
        ((ShadowSigningInfo) Shadow.extract(signing)).setSignatures(new Signature[] {signer});
        PackageInfo installed = Shadows.shadowOf(app.getPackageManager())
                .getInternalMutablePackageInfo(app.getPackageName());
        installed.signingInfo = signing;
        installed.signatures = new Signature[] {signer};
        long code = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        byte[] bytes = ("Offer Filter " + versionName).getBytes(StandardCharsets.UTF_8);
        File dir = new File(app.getFilesDir(), "updates");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        File apk = new File(dir, "OfferFilter.apk");
        Files.write(apk.toPath(), bytes);
        PackageInfo archive = new PackageInfo();
        archive.packageName = app.getPackageName();
        archive.versionName = versionName;
        archive.versionCode = (int) (code + 1);
        if (Build.VERSION.SDK_INT >= 28) archive.setLongVersionCode(code + 1);
        archive.signingInfo = signing;
        archive.signatures = new Signature[] {signer};
        Shadows.shadowOf(app.getPackageManager()).setPackageArchiveInfo(apk.getAbsolutePath(), archive);
        StringBuilder sha = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sha.append(String.format("%02x", b));
        JSONObject update = new JSONObject().put("packageName", app.getPackageName()).put("versionCode", code + 1)
                .put("versionName", versionName).put("sha256", sha.toString()).put("size", bytes.length)
                .put("encoding", "raw").put("apkUrl", "https://dash-offer-filter-build.onrender.com/OfferFilter.apk");
        Updater.feedReader = (address, out, limit) -> {
            reads.incrementAndGet();
            out.write(update.toString().getBytes(StandardCharsets.UTF_8));
        };
        return apk;
    }

    private static Button shownButtonStarting(View root, String start) {
        java.util.List<Button> buttons = new java.util.ArrayList<>();
        collectButtons(root, buttons);
        for (Button button : buttons) {
            if (button.isShown() && button.getText().toString().startsWith(start)) return button;
        }
        return null;
    }
}
