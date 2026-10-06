package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
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
import org.robolectric.shadows.ShadowNotificationManager;
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

    private static Button shownButtonStarting(View root, String start) {
        java.util.List<Button> buttons = new java.util.ArrayList<>();
        collectButtons(root, buttons);
        for (Button button : buttons) {
            if (button.isShown() && button.getText().toString().startsWith(start)) return button;
        }
        return null;
    }
}
