package com.local.dasherfilter;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** H2-9 / H2-10 updater scheduling (Robolectric API 26 and 35; the network is a fake that must not be reached). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdaterAdapterTest {
    private Application app;
    private final AtomicInteger fetches = new AtomicInteger();
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication(); Updater.setEnabled(app, true);
        Updater.fetcher = (address, out, limit) -> { fetches.incrementAndGet(); throw new IOException("offline test: the network must not be used here"); };
    }
    @After public void restore() { Updater.fetcher = UpdateTransport::download; Updater.setEnabled(app, false); ActiveRouteStore.clear(app); }

    @SuppressWarnings("deprecation")
    private long installedCode() throws Exception {
        PackageInfo p = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }
    /** A release that was downloaded and verified earlier and now waits in app-private storage. */
    private File verifiedWaitingUpdate() throws Exception {
        File dir = new File(app.getFilesDir(), "updates"); assertTrue(dir.isDirectory() || dir.mkdirs());
        File apk = new File(dir, "OfferFilter.apk");
        try (FileOutputStream out = new FileOutputStream(apk)) { out.write(new byte[1234]); }   // not a real APK: a re-hash would fail
        JSONObject release = new JSONObject().put("packageName", UpdatePolicy.PACKAGE).put("versionCode", installedCode() + 1).put("versionName", "9.9.9")
                .put("apkUrl", "https://dash-offer-filter-build.onrender.com/OfferFilter.apk").put("sha256", "a".repeat(64)).put("size", 1234);
        Updater.prefs(app).edit().putString("ready", release.toString()).putLong("ready_size", apk.length()).putLong("ready_modified", apk.lastModified())
                .putLong("checked_at", System.currentTimeMillis() - 60_000L).putLong("next_check_at", 0).commit();
        return apk;
    }

    @Test public void deferredUpdateSkipsDownloadAndRehashWhileADeliveryIsActive() throws Exception {
        File apk = verifiedWaitingUpdate();
        ActiveRouteStore.save(app, new OfferSnapshot(1000, 3.0, 20, 2));   // a delivery is active: automatic install waits
        Future<?> check = Updater.check(app, false, null); check.get(5, TimeUnit.SECONDS);
        assertEquals("no feed or APK download while waiting", 0, fetches.get());
        assertTrue(Updater.status(app), Updater.status(app).contains("deferred"));
        JobInfo retry = app.getSystemService(JobScheduler.class).getPendingJob(7243);
        assertNotNull(retry); assertEquals(UpdatePolicy.DEFER_RETRY_MS, retry.getMinLatencyMillis());

        // The waiting file changed: it is no longer trusted, so the full download-and-verify path runs again.
        assertTrue(apk.setLastModified(apk.lastModified() - 10_000L));
        Updater.check(app, false, null).get(5, TimeUnit.SECONDS);
        assertEquals(1, fetches.get()); assertTrue(Updater.status(app).startsWith("Update failed"));
    }

    @Test public void retryRequestedFromInsideTheRetryJobNeverReschedulesThatJob() throws Exception {
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        Updater.check(app, false, null, 7243).get(5, TimeUnit.SECONDS);   // fails (offline) inside retry job 7243
        assertEquals(1, fetches.get());
        assertNull("rescheduling the running job id would make Android stop this very check", jobs.getPendingJob(7243));
        assertNotNull(jobs.getPendingJob(7244));
        Updater.prefs(app).edit().putLong("next_check_at", 0).commit();
        Updater.check(app, false, null, 7244).get(5, TimeUnit.SECONDS);
        assertNotNull(jobs.getPendingJob(7243));
    }

    @Test public void manualCheckDuringARunningCheckIsQueuedNotDropped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Updater.fetcher = (address, out, limit) -> {
            if (fetches.incrementAndGet() == 1) { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            throw new IOException("offline test");
        };
        Future<?> automatic = Updater.check(app, false, null);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Future<?> manual = Updater.check(app, true, null);
        assertFalse(manual.isDone()); assertTrue(Updater.status(app), Updater.status(app).contains("already running"));
        assertTrue("a second manual tap while one is queued is not queued twice", Updater.check(app, true, null).isDone());
        release.countDown();
        automatic.get(5, TimeUnit.SECONDS); manual.get(5, TimeUnit.SECONDS);
        assertEquals("the queued manual check ran after the automatic one", 2, fetches.get());
    }
}
