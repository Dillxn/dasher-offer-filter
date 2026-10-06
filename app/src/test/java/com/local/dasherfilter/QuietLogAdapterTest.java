package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The raw log keeps what changed, not what repeats: automatic update checks start at most every five minutes
 * (opening the app and the user's own check at once), log their result only when it changes or fails, and are
 * counted in the report otherwise; DoorDash's alert-channel settings are logged once per change. The feed is a
 * local stand-in; the APK path is never reached. Simulated Android only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class QuietLogAdapterTest {
    private static final long MINUTE = 60_000L;

    private Application app;
    private long versionCode;
    private String versionName;
    private final AtomicInteger reads = new AtomicInteger();
    private volatile boolean older;
    private final AtomicBoolean offline = new AtomicBoolean();

    @Before
    public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        Updater.awaitIdle(5_000);
        PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        versionCode = Build.longVersionCode(info);
        versionName = info.versionName;
        assertTrue(versionCode > 1);
        Updater.feedReader = (address, out, limit) -> {
            reads.incrementAndGet();
            if (offline.get()) throw new IOException("offline");
            String feed = "{\"packageName\":\"com.local.dasherfilter\",\"versionCode\":"
                    + (older ? versionCode - 1 : versionCode) + ",\"versionName\":\""
                    + (older ? "0.0.1" : versionName) + "\",\"sha256\":\"" + repeat('a', 64) + "\",\"size\":1000,"
                    + "\"encoding\":\"raw\",\"apkUrl\":\"https://dash-offer-filter-build.onrender.com/OfferFilter.apk\"}";
            out.write(feed.getBytes(StandardCharsets.UTF_8));
        };
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
    }

    @After
    public void restore() throws Exception {
        Updater.awaitIdle(5_000);
        Updater.feedReader = UpdateTransport::download;
        Updater.setEnabled(app, false);
    }

    private static String repeat(char c, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(c);
        return out.toString();
    }

    /** Small stand-in for PackageInfo's version code across Android 8 and 15. */
    private static final class Build {
        @SuppressWarnings("deprecation")
        static long longVersionCode(PackageInfo info) {
            return android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        }
    }

    /** The last check was {@code ago} ms ago and its cooldown ended a minute ago. */
    private void lastCheck(long ago) {
        long now = System.currentTimeMillis();
        Updater.prefs(app).edit().putLong("attempt_at", ago < 0 ? 0 : now - ago)
                .putLong("next_check_at", now - MINUTE).commit();
    }

    /**
     * An automatic check, run to its end. The worker is waited for too: a check marks itself done after its result is
     * ready, so a check right after could otherwise be absorbed by the one finishing.
     */
    private void automatic() throws Exception {
        Updater.check(app, false, null).get(5, TimeUnit.SECONDS);
        Updater.awaitIdle(5_000);
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    public void anAutomaticCheckWithinFiveMinutesOfTheLastIsNotStartedAndIsCounted() throws Exception {
        lastCheck(2 * MINUTE);
        long before = Updater.prefs(app).getLong("attempt_at", 0);
        automatic();
        assertEquals(0, reads.get());
        assertEquals(before, Updater.prefs(app).getLong("attempt_at", 0));
        String log = DiagnosticLog.read(app);
        assertEquals(log, 0, count(log, "check start"));
        assertEquals(log, 0, count(log, "Checking for updates"));
        String report = DiagnosticLog.report(app);
        assertTrue(report, report.contains("too soon to start 1 (automatic 1)"));
    }

    @Test
    public void automaticChecksLogOnlyAChangedResultButManualChecksLogInFull() throws Exception {
        lastCheck(-1);
        automatic();
        lastCheck(6 * MINUTE);
        automatic();
        older = true;
        lastCheck(6 * MINUTE);
        automatic();
        Updater.check(app, true, null).get(5, TimeUnit.SECONDS);
        Updater.awaitIdle(5_000);

        assertEquals(4, reads.get());
        String log = DiagnosticLog.read(app);
        assertEquals(log, 0, count(log, "check start manual=false"));
        assertEquals(log, 1, count(log, "check start manual=true"));
        assertEquals(log, 1, count(log, "Checking for updates…"));
        assertEquals(log, 1, count(log, "Up to date: "));
        assertTrue(log, log.contains("[update] automatic check (automatic): Up to date: " + versionName
                + " (installed " + versionName + "; newest feed " + versionName + " via Render)"));
        assertEquals(log, 2, count(log, "Feed is older than this installation"));
        assertEquals("Feed is older than this installation; no downgrade attempted.", Updater.status(app));
    }

    @Test
    public void automaticFailuresAreAlwaysLoggedAndSoIsTheRecovery() throws Exception {
        offline.set(true);
        lastCheck(-1);
        automatic();
        lastCheck(6 * MINUTE);
        automatic();
        offline.set(false);
        lastCheck(6 * MINUTE);
        automatic();

        String log = DiagnosticLog.read(app);
        assertEquals(log, 2, count(log, "automatic check (automatic): Update failed: IOException: offline"));
        assertEquals(log, 1, count(log, "Up to date: "));
        assertEquals(log, 0, count(log, "check start manual=false"));
    }

    @Test
    public void theReportCountsQuietChecksByWhatStartedThemAndNamesTheFeed() throws Exception {
        lastCheck(-1);
        automatic();
        lastCheck(6 * MINUTE);
        automatic();
        String report = DiagnosticLog.report(app);
        assertTrue(report, report.contains("Automatic checks since the last logged update line: same result 1 "
                + "(automatic 1); too soon to start 0"));
        assertTrue(report, report.contains("Latest advertised version: " + versionName + " (Render)"));
        assertFalse("no retired GitHub connection line", report.contains("GitHub connection"));
        int states = report.indexOf("== Latest states");
        assertTrue(report, states >= 0 && report.indexOf("[update] Up to date: " + versionName, states) > states);
    }

    @Test
    public void comingBackOrRecreatingWithinFiveMinutesDoesNotCheckButOpeningDoes() throws Exception {
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        try {
            lastCheck(2 * MINUTE);
            ActivityController<MainActivity> screen = Robolectric.buildActivity(MainActivity.class).setup();
            Updater.awaitIdle(5_000);
            assertEquals("opening the app checks at once", 1, reads.get());

            lastCheck(2 * MINUTE);
            screen.pause().resume();
            Updater.awaitIdle(5_000);
            assertEquals("coming back two minutes after a check waits", 1, reads.get());
            screen.recreate();
            Updater.awaitIdle(5_000);
            assertEquals("a resize or day and night is coming back, not opening", 1, reads.get());

            lastCheck(6 * MINUTE);
            screen.pause().resume();
            Updater.awaitIdle(5_000);
            assertEquals(2, reads.get());
            screen.pause().stop().destroy();

            lastCheck(2 * MINUTE);
            Robolectric.buildActivity(MainActivity.class).setup();
            Updater.awaitIdle(5_000);
            assertEquals(3, reads.get());
        } finally {
            OfferFilterService.scanLooperForTests = null;
        }
    }

    @Test
    public void aReconnectedListenerDoesNotCheckWithinFiveMinutes() throws Exception {
        lastCheck(2 * MINUTE);
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        Updater.awaitIdle(5_000);
        assertEquals(0, reads.get());
        listener.destroy();
    }

    /** DoorDash's offer notification on {@code channel}, as a real phone showed it. */
    private StatusBarNotification doorDashOffer(String channel, String store, int id) {
        Notification payload = new Notification.Builder(app, channel)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText("New Order: Go to " + store)
                .build();
        return new StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp", id, "NEW_ORDER" + id,
                10001, 0, 0, payload, android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    @Test
    public void doorDashChannelSettingsAreLoggedOncePerChangeAndKeptInTheReport() {
        Updater.setEnabled(app, false);
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        ServiceController<OfferNotificationService> listener =
                Robolectric.buildService(OfferNotificationService.class).create();
        try {
            listener.get().onNotificationPosted(doorDashOffer("source", "Store A", 1), null);
            listener.get().onNotificationPosted(doorDashOffer("source", "Store B", 2), null);
            String log = DiagnosticLog.read(app);
            assertEquals(log, 1, count(log, "[notification-channel] id=source "));
            assertEquals(log, 1, count(log, "[notification-meta] "));

            listener.get().onNotificationPosted(doorDashOffer("source2", "Store C", 3), null);
            log = DiagnosticLog.read(app);
            assertEquals(log, 1, count(log, "[notification-channel] id=source2 "));
            assertEquals(log, 2, count(log, "[notification-meta] "));
            String report = DiagnosticLog.report(app);
            int states = report.indexOf("== Latest states");
            assertTrue(report, report.indexOf("id=source actualSound", states) > states
                    && report.indexOf("id=source2 actualSound", states) > states);
            // Every offer still has its line.
            assertEquals(3, DecisionLog.recent(app, 10).size());

            // A copy a day old is logged again; Clear history forgets them all.
            app.getSharedPreferences("offer_filter_diagnostics", android.content.Context.MODE_PRIVATE).edit()
                    .putLong("state_at:notification-channel|source", System.currentTimeMillis() - 25 * 60 * MINUTE)
                    .commit();
            listener.get().onNotificationPosted(doorDashOffer("source", "Store D", 4), null);
            assertEquals(2, count(DiagnosticLog.read(app), "[notification-channel] id=source "));
            DiagnosticLog.clear(app);
            report = DiagnosticLog.report(app);
            assertTrue(report, !report.substring(report.indexOf("== Latest states")).contains("id=source actualSound"));
            listener.get().onNotificationPosted(doorDashOffer("source", "Store E", 5), null);
            assertEquals(1, count(DiagnosticLog.read(app), "[notification-channel] id=source "));
        } finally {
            listener.destroy();
        }
    }
}
