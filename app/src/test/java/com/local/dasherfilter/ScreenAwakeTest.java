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
        FilterStore.save(app, FilterSettings.of(true, 500, 0, 0, 0));
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

    @Test public void aStoppedScreenReaderOrAFailedCheckReleases() {
        awake.start();
        PowerManager.WakeLock lock = lock();
        visible.set(false);
        advanceCheck();
        assertFalse(lock.isHeld());
        visible.set(true);
        advanceCheck();
        assertTrue(lock.isHeld());
        awake.stop();
        awake = new ScreenAwake(app, () -> { throw new IllegalStateException("state unavailable"); });
        awake.start();
        assertFalse("a failed check cannot leave a hold", lock.isHeld());
    }

    /** The owner chose "Don't let it lock mid-dash", unless battery saver is on or the battery runs low. */
    @Test public void batterySaverOrALowBatteryNotChargingLetsTheScreenTimeOut() {
        awake.start();
        PowerManager.WakeLock lock = lock();
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsPowerSaveMode(true);
        advanceCheck();
        assertFalse("battery saver", lock.isHeld());
        Shadows.shadowOf(app.getSystemService(PowerManager.class)).setIsPowerSaveMode(false);
        advanceCheck();
        assertTrue(lock.isHeld());

        android.os.BatteryManager battery = app.getSystemService(android.os.BatteryManager.class);
        Shadows.shadowOf(battery).setIsCharging(false);
        Shadows.shadowOf(battery).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY,
                ScreenAwake.LOW_BATTERY_PERCENT + 1);
        advanceCheck();
        assertTrue("above the low mark", lock.isHeld());
        Shadows.shadowOf(battery).setIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY,
                ScreenAwake.LOW_BATTERY_PERCENT);
        advanceCheck();
        assertFalse("20 % and not charging", lock.isHeld());
        Shadows.shadowOf(battery).setIsCharging(true);
        advanceCheck();
        assertTrue("charging at 20 % holds again", lock.isHeld());
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[awake] screen timeout released: battery saver is on"));
        assertTrue(log, log.contains("[awake] screen timeout released: battery low"));
        assertTrue(log, log.contains("[awake] screen timeout held: a dash is under way (any app in front)"));
    }

    @Test public void aDashPausedInDasherReleases() {
        awake.start();
        PowerManager.WakeLock lock = lock();
        Dashing.paused(app);
        advanceCheck();
        assertFalse(lock.isHeld());
        Dashing.forgetCache();
        Dashing.seen(app);
        advanceCheck();
        assertTrue("the dash goes on", lock.isHeld());
    }

    /**
     * A stale route or a dash whose end was never seen must never keep an unlocked phone on for long: with no sign of
     * the dash for 15 minutes (an offer or its notification, Dasher's wait for offers, a pickup or delivery screen), the
     * hold lets go though the dash itself still counts as on (its route stored); a new sign holds it again.
     */
    @Test public void aStoredRouteAloneNeverHoldsTheScreenOnceNothingOfTheDashWasSeenFor15Minutes() {
        ActiveRouteStore.save(app, new OfferSnapshot(800, 3.0, 12, 2));
        awake.start();
        PowerManager.WakeLock lock = lock();
        assertTrue(lock.isHeld());
        ShadowSystemClock.advanceBy(Duration.ofMillis(ScreenAwake.FRESH_MS - 3_000));
        advanceCheck();
        assertTrue("within 15 minutes of the last sign", lock.isHeld());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4));
        advanceCheck();
        assertTrue("the dash itself is still on (a route is stored)", Dashing.on(app));
        assertFalse("a route alone holds nothing", lock.isHeld());
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("[awake] screen timeout released: nothing of the dash seen for 15 minutes"));
        Dashing.sawDash();
        advanceCheck();
        assertTrue("a fresh sign of the dash holds it again", lock.isHeld());
        // Hours later, the route still stored and never ended: let go well before.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(150));
        advanceCheck();
        assertFalse(lock.isHeld());
        ActiveRouteStore.clear(app);
    }

    @Test public void dashersHomeBeforeADashLetsGoUntilTheDashIsSeenAgain() {
        awake.start();
        PowerManager.WakeLock lock = lock();
        Dashing.homeSeen();
        advanceCheck();
        assertFalse(lock.isHeld());
        assertTrue(DiagnosticLog.read(app).contains("[awake] screen timeout released: Dasher's home shows no dash"));
        Dashing.seen(app);
        advanceCheck();
        assertTrue("the dash seen again", lock.isHeld());
    }

    /**
     * With Dasher in front showing the dash (its wait for offers, read once and unchanged since), the hold goes on
     * however long ago the dash was last seen to change; with another app in front, only within 15 minutes of a sign of
     * the dash.
     */
    @Test public void dasherShowingTheDashHoldsAndAnotherAppNeedsAFreshSignOfIt() {
        service = Robolectric.buildService(OfferFilterService.class).create();
        OfferFilterService reader = service.get();
        reader.onServiceConnected();
        showWaiting(reader);
        advanceCheck();
        PowerManager.WakeLock lock = lock();
        assertTrue(lock.isHeld());
        ShadowSystemClock.advanceBy(Duration.ofMillis(ScreenAwake.FRESH_MS + 60_000));
        advanceCheck();
        assertTrue("Dasher in front still shows the dash's wait for offers", lock.isHeld());
        showWindow(reader, "com.google.android.apps.maps", "Head north on Main St");
        advanceCheck();
        assertFalse("another app in front, nothing of the dash seen for 15 minutes", lock.isHeld());
        Dashing.seen(app);
        advanceCheck();
        assertTrue("an offer's notification (a sign of the dash) holds it again", lock.isHeld());
        // Dasher's own earnings page (figures, no offer or dash screen) is no sign of the dash.
        ShadowSystemClock.advanceBy(Duration.ofMillis(ScreenAwake.FRESH_MS + 60_000));
        showWindow(reader, "com.doordash.driverapp", "Earnings this week $412.50");
        advanceCheck();
        assertFalse(lock.isHeld());
    }

    @Test public void pauseNoRulesDashEndAndPendingConsentEachRelease() {
        awake.start();
        PowerManager.WakeLock lock = lock();
        FilterStore.save(app, FilterSettings.of(false, 500, 0, 0, 0));
        advanceCheck();
        assertFalse("paused filter", lock.isHeld());
        FilterStore.save(app, FilterSettings.of(true, 0, 0, 0, 0));
        advanceCheck();
        assertFalse("no filtering rule", lock.isHeld());
        FilterStore.save(app, FilterSettings.of(true, 500, 0, 0, 0));
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

    @Test public void pendingConsentNeverEvenAsksWhetherTheScreenReaderIsUp() {
        AtomicInteger readerChecks = new AtomicInteger();
        awake = new ScreenAwake(app, () -> { readerChecks.incrementAndGet(); return true; });
        ConsentedTestApp.forget(app);
        awake.start();
        advanceCheck();
        assertEquals(0, readerChecks.get());
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
        // The owner: "Don't let it lock mid-dash", whatever app is in front (a map, say), so Peek can still work.
        showWindow(reader, "com.google.android.apps.maps", "Head north on Main St");
        advanceCheck();
        assertTrue("another app in front during the dash still holds off the timeout", lock.isHeld());
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
            FilterStore.save(app, FilterSettings.of(false, 500, 0, 0, 0));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertFalse("pause releases the visible activity flag", keepsScreenOn(activity));
            FilterStore.save(app, FilterSettings.of(true, 500, 0, 0, 0));
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
