package com.local.dasherfilter;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

/** Waiting is cheap; leaving the wait always returns to the normal feed and verification path. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdaterHeldReadyTest {
    private Application app;
    private File apk;
    private final AtomicInteger reads = new AtomicInteger();

    @Before public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        Updater.awaitIdle(5_000);
        Updater.prefs(app).edit().clear().commit();
        Updater.clearReady(app);
        Updater.setEnabled(app, true);
        GitHubConnect.disconnect(app);
        Shadows.shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(true);
        app.getSharedPreferences("dashing", 0).edit().clear().commit();
        Dashing.forgetCache();
        Dashing.seen(app);
        PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        long code = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        JSONObject current = feed(code, installed.versionName);
        Updater.feedReader = (channel, address, token, out, limit) -> {
            reads.incrementAndGet();
            out.write(current.toString().getBytes(StandardCharsets.UTF_8));
        };
        File dir = new File(app.getFilesDir(), "updates");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        apk = new File(dir, "OfferFilter.apk");
        Files.write(apk.toPath(), new byte[] {1, 2, 3});
        Updater.Release release = new Updater.Release(UpdatePolicy.Channel.RENDER, feed(code + 1, "99.0.0"), null);
        // Seed only the private receipt produced after verifiedApk succeeds. This test exercises waiting, not an
        // APK or signer fixture; the receipt is never accepted by the installation path as verification evidence.
        Class<?> receipt = Class.forName(Updater.class.getName() + "$VerifiedReady");
        Constructor<?> constructor = receipt.getDeclaredConstructor(File.class, Updater.Release.class, long.class);
        constructor.setAccessible(true);
        ReflectionHelpers.setStaticField(Updater.class, "verifiedReady", constructor.newInstance(apk, release, code));
    }

    @After public void cleanup() throws Exception {
        Updater.awaitIdle(5_000);
        Updater.feedReader = UpdateTransport::download;
        Updater.clearReady(app);
        Updater.setEnabled(app, false);
        Dashing.ended(app);
    }

    private static JSONObject feed(long code, String version) throws Exception {
        return new JSONObject().put("packageName", "com.local.dasherfilter").put("versionCode", code)
                .put("versionName", version).put("sha256", "a".repeat(64)).put("size", 3)
                .put("encoding", "raw").put("apkUrl", "https://dash-offer-filter-build.onrender.com/OfferFilter.apk");
    }

    private void check(boolean manual) throws Exception {
        Updater.prefs(app).edit().putLong("attempt_at", 0).putLong("next_check_at", 0).commit();
        Updater.check(app, manual, null).get(5, TimeUnit.SECONDS);
        Updater.awaitIdle(5_000);
    }

    @Test public void repeatedAutomaticRetriesDuringTheDashDoNotReadEitherFeed() throws Exception {
        check(false);
        check(false);
        assertEquals(0, reads.get());
        assertEquals(Updater.AFTER_DASH, Updater.status(app));
        assertTrue(apk.isFile());
        assertFalse(Updater.installing(app));
    }

    @Test public void aQuietDisconnectedDashStillWaitsWithoutNetwork() throws Exception {
        app.getSharedPreferences("dashing", 0).edit()
                .putLong("seen_at", System.currentTimeMillis() - 2 * Dashing.WINDOW_MS).commit();
        assertFalse(Dashing.now(app));
        check(false);
        assertEquals(0, reads.get());
        assertEquals(Updater.AFTER_DASH, Updater.status(app));
    }

    @Test public void aManualCheckStillReadsTheFeedMidDash() throws Exception {
        check(true);
        assertEquals(1, reads.get());
        assertTrue(Updater.status(app), Updater.status(app).startsWith("Up to date:"));
    }

    @Test public void aPositiveDashEndRequiresAFreshFeedBeforeAnyInstall() throws Exception {
        Dashing.ended(app);
        check(false);
        assertEquals(1, reads.get());
        assertFalse("the latest feed says no update, so the older ready file is removed", apk.exists());
        assertFalse(Updater.installing(app));
    }

    @Test public void aChangedOrMissingCacheCannotKeepTheReadyReceipt() throws Exception {
        Files.write(apk.toPath(), new byte[] {4, 5, 6, 7});
        check(false);
        assertEquals(1, reads.get());
        assertFalse(apk.exists());
    }

    @Test public void aProcessRestartDoesNotTreatTheCachedFileAsVerified() throws Exception {
        ReflectionHelpers.setStaticField(Updater.class, "verifiedReady", null);
        check(false);
        assertEquals(1, reads.get());
        assertFalse(apk.exists());
    }
}
