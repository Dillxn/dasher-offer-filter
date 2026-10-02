package com.local.dasherfilter;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** Lifecycle and setup recovery through the actual Activity, without a real share target or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivityRobustnessTest extends AndroidAdapterTestBase {
    @After public void resetBuilder() { ReportShare.builder = ReportShare.DIAGNOSTICS; }

    @Test public void reportBuildUsesApplicationOffMainAndRepeatedTapSharesOnlyOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger builds = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<android.content.Context> context = new AtomicReference<>();
        ReportShare.builder = given -> {
            worker.set(Thread.currentThread());
            context.set(given);
            builds.incrementAndGet();
            entered.countDown();
            await(release);
            return new ReportShare.Report("Synthetic report", "No private fixtures");
        };
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = settings(activity);
            View share = findButton(content, "Share report");
            share.performClick();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse("the same action is busy until ready", share.isEnabled());
            share.performClick();
            assertEquals(1, builds.get());
            assertSame(app, context.get());
            assertNotSame(Looper.getMainLooper().getThread(), worker.get());
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
            release.countDown();
            Intent chooser = awaitChooser();
            assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
            assertTrue(share.isEnabled());
            assertNull("only one chooser", Shadows.shadowOf(app).getNextStartedActivity());
        } finally { release.countDown(); }
    }

    @Test public void leavingSettingsDiscardsAnOldReportBeforeANewShare() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger builds = new AtomicInteger();
        ReportShare.builder = context -> {
            if (builds.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
                return new ReportShare.Report("Old report", "discard me");
            }
            return new ReportShare.Report("Fresh report", "fresh");
        };
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = settings(activity);
            findButton(content, "Share report").performClick();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            iconButton(content, "Back").performClick();
            settings(activity);
            findButton(content, "Share report").performClick();
            release.countDown();
            Intent sent = awaitChooser().getParcelableExtra(Intent.EXTRA_INTENT);
            assertEquals("Fresh report", sent.getStringExtra(Intent.EXTRA_SUBJECT));
            assertEquals(2, builds.get());
            assertNull("the stale result never opens", Shadows.shadowOf(app).getNextStartedActivity());
        } finally { release.countDown(); }
    }

    @Test public void pausingDropsTheShareCallbackAndCanShareFreshAfterResume() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger builds = new AtomicInteger();
        ReportShare.builder = context -> {
            if (builds.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
                return new ReportShare.Report("Paused report", "discard me");
            }
            return new ReportShare.Report("Resumed report", "fresh");
        };
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            View content = settings(activity);
            findButton(content, "Share report").performClick();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            activity.pause().resume();
            findButton(content, "Share report").performClick();
            release.countDown();
            Intent sent = awaitChooser().getParcelableExtra(Intent.EXTRA_INTENT);
            assertEquals("Resumed report", sent.getStringExtra(Intent.EXTRA_SUBJECT));
            assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        } finally { release.countDown(); }
    }

    @Test public void clearHistoryRemovesCachedPlaceNamesWhileKeepingRulesAndPreference() {
        Places.forget(app);
        Places.setEnabled(app, false);
        app.getSharedPreferences("places", 0).edit()
                .putString("name:" + Places.key(40, -87), "Synthetic area")
                .putString("recent_names", Places.key(40, -87)).commit();
        FilterSettings rules = new FilterSettings(true, 2000, 150, 30, 100, 3);
        FilterStore.save(app, rules);
        DecisionLog.record(app, declinedEntry());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            findButton(settings(activity), "Clear history").performClick();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            // AlertController posts the button's listener to the main Handler.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(app.getSharedPreferences("places", 0).contains("name:" + Places.key(40, -87)));
            assertFalse(app.getSharedPreferences("places", 0).contains("recent_names"));
            assertFalse(Places.enabled(app));
            assertTrue(DecisionLog.recent(app, 1).isEmpty());
            assertEquals(2000, FilterStore.load(app).flatCents);
        }
    }

    @Test public void reportStatusIsUntouchedAtHomeAndRefreshesWhenSettingsOpens() throws Exception {
        reportsOn();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            TextView status = field(activity.get(), "reportStatus");
            status.setText("not scanned while hidden");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            assertEquals("not scanned while hidden", status.getText().toString());
            settings(activity);
            assertEquals(ReportOutbox.status(app), status.getText().toString());
        }
    }

    @Test @Config(sdk = {26, 28})
    public void visiblePausedOlderAndroidKeepsHistoryCurrentUntilStopped() throws Exception {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            settle();
            activity.pause(); // The visible, unfocused half of split screen on Android 8 and 9.
            DecisionLog.record(app, declinedEntry());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertEquals(DecisionLog.version(), (long) field(activity.get(), "shownHistoryVersion"));
            activity.stop();
            long shown = field(activity.get(), "shownHistoryVersion");
            DecisionLog.clear(app);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals("a hidden Activity has no periodic refresh", shown,
                    (long) field(activity.get(), "shownHistoryVersion"));
        }
    }

    @Test public void enabledButDisconnectedReaderSaysStoppedAndOpensItsServiceSettings() {
        ComponentName component = new ComponentName(app, OfferFilterService.class);
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                component.flattenToString());
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            settle();
            View content = activity.get().findViewById(android.R.id.content);
            View fix = shownIcon(content, "Screen reading stopped. Fix.");
            assertNotNull("being enabled does not imply a running connection", fix);
            fix.performClick();
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            if (Build.VERSION.SDK_INT >= 31) {
                assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", opened.getAction());
                assertEquals(component.flattenToString(), opened.getStringExtra(Intent.EXTRA_COMPONENT_NAME));
            } else {
                assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, opened.getAction());
            }
        }
    }

    @Test @Config(sdk = 35)
    public void missingOemServiceDetailsFallsBackToThePublicAccessibilityList() {
        ComponentName settings = new ComponentName("android.settings", "android.settings.Accessibility");
        org.robolectric.shadows.ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        packages.addActivityIfNotPresent(settings);
        android.content.IntentFilter filter = new android.content.IntentFilter(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        packages.addIntentFilterForActivity(settings, filter);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            settle();
            Shadows.shadowOf(app).checkActivities(true);
            View content = activity.get().findViewById(android.R.id.content);
            shownIcon(content, "Screen reading is off. Fix.").performClick();
            Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
            assertNotNull(opened);
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, opened.getAction());
        } finally {
            Shadows.shadowOf(app).checkActivities(false);
        }
    }

    @Test @Config(sdk = 35)
    public void unsuccessfulAccessibilityVisitOffersRestrictedSettingsGuidanceOnlyOnTap() {
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            settle();
            View content = activity.get().findViewById(android.R.id.content);
            shownIcon(content, "Screen reading is off. Fix.").performClick();
            assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS",
                    Shadows.shadowOf(app).getNextStartedActivity().getAction());
            activity.pause().resume();
            settle();
            assertNull("returning opens nothing automatically", Shadows.shadowOf(app).getNextStartedActivity());
            shownIcon(content, "Screen reading is off · switch greyed out?. Fix.").performClick();
            AlertDialog help = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(help);
            help.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Intent info = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, info.getAction());
            assertEquals("package:" + app.getPackageName(), info.getDataString());
        }
    }

    @Test @Config(sdk = 35)
    public void notificationsDeniedWithoutAnotherPromptLeadToAppNotificationSettings() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            settle();
            View content = activity.get().findViewById(android.R.id.content);
            shownIcon(content, "Alerts are blocked. Fix.").performClick();
            org.robolectric.shadows.ShadowActivity.PermissionsRequest request =
                    Shadows.shadowOf(activity.get()).getLastRequestedPermission();
            assertEquals(Manifest.permission.POST_NOTIFICATIONS, request.requestedPermissions[0]);
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",
                    Shadows.shadowOf(app).getNextStartedActivity().getAction());
            activity.get().onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[] {android.content.pm.PackageManager.PERMISSION_DENIED});
            shownIcon(content, "Alerts are blocked. Fix.").performClick();
            Intent settings = Shadows.shadowOf(app).getNextStartedActivity();
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.getAction());
            assertEquals(app.getPackageName(), settings.getStringExtra(Settings.EXTRA_APP_PACKAGE));
        }
    }

    private View settings(ActivityController<MainActivity> activity) {
        View content = activity.get().findViewById(android.R.id.content);
        iconButton(content, "Settings").performClick();
        settle();
        return content;
    }

    private Intent awaitChooser() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Intent next = Shadows.shadowOf(app).getNextStartedActivity();
            if (next != null) return next;
            Thread.sleep(5);
        } while (System.nanoTime() < end);
        throw new AssertionError("report did not finish");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test build timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @SuppressWarnings("unchecked") private static <T> T field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(object);
    }
}
