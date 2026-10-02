package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
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
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Automatic timeout prevention through Android's APIs; no claim about an OEM's real power policy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class ScreenAwakeTest {
    private Application app;
    private ScreenAwake awake;
    private ServiceController<OfferFilterService> service;
    private final AtomicBoolean visible = new AtomicBoolean(true);

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        ConsentedTestApp.accept(app);
        Updater.setEnabled(app, false);
        FilterStore.save(app, new FilterSettings(true, 500, 0, 0, 0, 0));
        Dashing.forgetCache();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        Dashing.seen(app);
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(true);
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        app.getSystemService(AudioManager.class).setMode(AudioManager.MODE_NORMAL);
        ShadowPowerManager.clearWakeLocks();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        awake = new ScreenAwake(app, visible::get);
    }

    @After public void teardown() {
        awake.stop();
        if (service != null) service.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.forgetScreenState();
    }

    @Test public void visibleFilteringRenewsABoundedScreenLeaseWithoutWakeUpFlags() {
        awake.start();
        PowerManager.WakeLock lock = lock();
        assertTrue(lock.isHeld());
        assertFalse(Shadows.shadowOf(lock).isReferenceCounted());
        int flags = ReflectionHelpers.getField(lock, "mFlags");
        assertEquals("screen only: neither wake-up nor after-release activity flag", PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                flags);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ScreenAwake.LEASE_MS * 2));
        assertTrue("a continuing visible dash stays awake beyond one lease", lock.isHeld());
        awake.stop();
        assertFalse(lock.isHeld());
        advanceCheck();
        assertFalse("stopped leases are not renewed", lock.isHeld());
    }

    @Test public void anUnrenewedLeaseExpiresUnderAndroidsTimeoutModel() {
        awake.start();
        PowerManager.WakeLock lock = lock();
        // Robolectric models timeout expiry on its clock; this does not simulate a stalled real Android handler.
        ShadowSystemClock.advanceBy(Duration.ofMillis(ScreenAwake.LEASE_MS + 1));
        assertFalse("the lease was acquired with a finite timeout", lock.isHeld());
    }

    @Test public void anotherAppOrUncertainWindowsReleaseWithoutReadingTheirContent() {
        awake.start();
        PowerManager.WakeLock lock = lock();
        visible.set(false);
        advanceCheck();
        assertFalse(lock.isHeld());
        visible.set(true);
        advanceCheck();
        assertTrue(lock.isHeld());
        awake.stop();
        awake = new ScreenAwake(app, () -> { throw new IllegalStateException("windows unavailable"); });
        awake.start();
        assertFalse("a window lookup failure cannot leave a hold", lock.isHeld());
    }

    @Test public void pauseNoRulesDashEndAndPendingConsentEachRelease() {
        awake.start();
        PowerManager.WakeLock lock = lock();
        FilterStore.save(app, new FilterSettings(false, 500, 0, 0, 0, 0));
        advanceCheck();
        assertFalse("paused filter", lock.isHeld());
        FilterStore.save(app, new FilterSettings(true, 0, 0, 0, 0, 0));
        advanceCheck();
        assertFalse("no filtering rule", lock.isHeld());
        FilterStore.save(app, new FilterSettings(true, 500, 0, 0, 0, 0));
        advanceCheck();
        assertTrue(lock.isHeld());
        ConsentedTestApp.forget(app);
        advanceCheck();
        assertFalse("a new notice has not been accepted", lock.isHeld());
        ConsentedTestApp.accept(app);
        advanceCheck();
        assertTrue("accepted again during the same dash", lock.isHeld());
        Dashing.ended(app);
        advanceCheck();
        assertFalse("dash paused or ended", lock.isHeld());
    }

    @Test public void pendingConsentNeverEvenAsksWhichWindowIsVisible() {
        AtomicInteger windowReads = new AtomicInteger();
        awake = new ScreenAwake(app, () -> { windowReads.incrementAndGet(); return true; });
        ConsentedTestApp.forget(app);
        awake.start();
        advanceCheck();
        assertEquals(0, windowReads.get());
        assertNull(ShadowPowerManager.getLatestWakeLock());
    }

    @Test public void lockingScreenOffOrACallEndsTheHold() {
        awake.start();
        PowerManager.WakeLock lock = lock();
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        advanceCheck();
        assertFalse("locked", lock.isHeld());
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        advanceCheck();
        assertTrue(lock.isHeld());
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(false);
        advanceCheck();
        assertFalse("noninteractive", lock.isHeld());
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(true);
        app.getSystemService(AudioManager.class).setMode(AudioManager.MODE_IN_COMMUNICATION);
        advanceCheck();
        assertFalse("a voice call", lock.isHeld());
    }

    @Test public void powerOffBroadcastWinsOverQueuedEventsAndScreenOnStillNeedsUnlock() {
        awake.start();
        PowerManager.WakeLock lock = lock();
        // Even when the power-state query briefly lags the broadcast, no event may renew after screen-off.
        awake.screenOff();
        awake.start();
        advanceCheck();
        assertFalse(lock.isHeld());
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        awake.screenOn();
        advanceCheck();
        assertFalse("screen on is not the same as unlocked", lock.isHeld());
        Shadows.shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        advanceCheck();
        assertTrue("the user unlocked back into a visible dash", lock.isHeld());
    }

    @Test public void anUnreadablePhoneStateDoesNotCrashOrAcquire() {
        Context unavailable = new android.content.ContextWrapper(app) {
            @Override public Object getSystemService(String name) {
                if (Context.POWER_SERVICE.equals(name)) throw new SecurityException("not available");
                return super.getSystemService(name);
            }
        };
        awake = new ScreenAwake(unavailable, visible::get);
        awake.start();
        advanceCheck();
        assertNull(ShadowPowerManager.getLatestWakeLock());
    }

    @Test public void theServiceReleasesOnInterruptionPowerOffAndDisconnect() {
        service = Robolectric.buildService(OfferFilterService.class).create();
        OfferFilterService reader = service.get();
        reader.onServiceConnected();
        showWaiting(reader);
        advanceCheck();
        PowerManager.WakeLock lock = lock();
        assertTrue(lock.isHeld());
        showWindow(reader, "com.example.reader", "An unrelated app");
        advanceCheck();
        assertFalse("Dasher only in the background must not hold the screen", lock.isHeld());
        showWaiting(reader);
        advanceCheck();
        assertTrue(lock.isHeld());
        reader.onInterrupt();
        assertFalse("interrupt releases immediately", lock.isHeld());
        advanceCheck();
        assertFalse("an interrupt is not restarted by the renewal timer", lock.isHeld());
        showWaiting(reader);
        advanceCheck();
        assertTrue("a new readable event resumes", lock.isHeld());
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(lock.isHeld());
        showWaiting(reader);
        advanceCheck();
        assertFalse("an event while off must not wake the screen", lock.isHeld());
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        advanceCheck();
        assertTrue(lock.isHeld());
        service.destroy();
        service = null;
        assertFalse("disconnect releases immediately", lock.isHeld());
    }

    @Test public void homepageKeepsOnlyItsVisibleActiveFilteringWindowOn() {
        service = Robolectric.buildService(OfferFilterService.class).create();
        service.get().onServiceConnected();
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(keepsScreenOn(activity));
            activity.pause();
            assertFalse("background activity must not own a screen hold", keepsScreenOn(activity));
            activity.resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(keepsScreenOn(activity));
            FilterStore.save(app, new FilterSettings(false, 500, 0, 0, 0, 0));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertFalse("pause releases the visible activity flag", keepsScreenOn(activity));
            FilterStore.save(app, new FilterSettings(true, 500, 0, 0, 0, 0));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertTrue(keepsScreenOn(activity));
            ConsentedTestApp.forget(app);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertFalse("pending consent clears the flag too", keepsScreenOn(activity));
        }
    }

    @Test public void manifestDeclaresTheNormalWakeLockPermission() {
        assertEquals(PackageManager.PERMISSION_GRANTED,
                app.getPackageManager().checkPermission(Manifest.permission.WAKE_LOCK, app.getPackageName()));
    }

    private static boolean keepsScreenOn(ActivityController<MainActivity> activity) {
        return (activity.get().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
    }

    private void showWaiting(OfferFilterService reader) {
        showWindow(reader, "com.doordash.driverapp", "Looking for offers");
    }

    private void showWindow(OfferFilterService reader, String owner, String label) {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain(new View(app));
        root.setPackageName(owner);
        root.setText(label);
        root.setVisibleToUser(true);
        TestWindows.full(reader, root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(owner);
        reader.onAccessibilityEvent(event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void advanceCheck() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ScreenAwake.CHECK_MS));
    }

    private static PowerManager.WakeLock lock() {
        PowerManager.WakeLock lock = ShadowPowerManager.getLatestWakeLock();
        assertNotNull("a bounded screen lease was acquired", lock);
        return lock;
    }
}
