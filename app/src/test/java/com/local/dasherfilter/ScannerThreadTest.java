package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowNotificationListenerService;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The screen reader's own thread: Dasher's events are only noted on the main thread (which Offer Filter's screen
 * shares) and read, decided and tapped on the scanner thread. Most tests here run the service's real scanner thread
 * and wait for it with {@code shadowOf(looper).idle()}; a "slow phone" is a window list that takes its time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class ScannerThreadTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final Rect TOP_HALF = new Rect(0, 0, 1080, 1000);
    private static final Rect DIVIDER = new Rect(0, 1000, 1080, 1040);
    private static final Rect BOTTOM_HALF = new Rect(0, 1040, 1080, 2040);

    private Application app;
    private ServiceController<OfferFilterService> controller;
    private ServiceController<OfferNotificationService> listener;
    private AccessibilityNodeInfo accept;
    private AccessibilityNodeInfo decline;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.forgetCache();
        AreaMap.forgetCache();
        ReportOutbox.forgetCache();
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = null;
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void tearDown() {
        if (listener != null) listener.destroy();
        if (controller != null) controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.takeoverBeginsForTests = null;
        RefusingWindowManager.refuse = false;
    }

    /** The service on its own scanner thread (or, with {@code onMain}, reading on the main looper). */
    private OfferFilterService service(boolean onMain) {
        OfferFilterService.scanLooperForTests = onMain ? Looper.getMainLooper() : null;
        controller = Robolectric.buildService(OfferFilterService.class).create();
        OfferFilterService service = controller.get();
        assertEquals(onMain, service.scanLooper() == Looper.getMainLooper());
        service.onServiceConnected();
        settle(service);
        return service;
    }

    /** Lets the scanner finish what is due, then the main thread take what it handed over. */
    private void settle(OfferFilterService service) {
        if (service.scanLooper() != Looper.getMainLooper()) Shadows.shadowOf(service.scanLooper()).idle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
        node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        if (clickable) {
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(node).setOnPerformActionListener((action, args) -> true);
        }
        return node;
    }

    private AccessibilityNodeInfo offer(String money) {
        AccessibilityNodeInfo root = node("", false);
        accept = node("Accept", true);
        decline = node("Decline", true);
        Shadows.shadowOf(root).addChild(node(money, false));
        Shadows.shadowOf(root).addChild(node("2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(accept);
        Shadows.shadowOf(root).addChild(decline);
        return root;
    }

    private AccessibilityNodeInfo idle() {
        AccessibilityNodeInfo root = node("", false);
        Shadows.shadowOf(root).addChild(node("Finding offers", false));
        return root;
    }

    private AccessibilityNodeInfo confirmation(AccessibilityNodeInfo button) {
        AccessibilityNodeInfo root = node("Are you sure you want to decline this offer?", false);
        Shadows.shadowOf(root).addChild(button);
        return root;
    }

    private static AccessibilityWindowInfo window(int type, AccessibilityNodeInfo root, boolean active, Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(type);
        if (root != null) shadow.setRoot(root);
        shadow.setActive(active);
        shadow.setBoundsInScreen(bounds);
        return window;
    }

    private AccessibilityNodeInfo appRoot(String packageName) {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain(new View(app));
        root.setPackageName(packageName);
        root.setVisibleToUser(true);
        return root;
    }

    /** Dasher fills the screen showing {@code root}; no event is sent. */
    private void dasherShows(OfferFilterService service, AccessibilityNodeInfo root) {
        Shadows.shadowOf(service).setWindows(Collections.singletonList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, root, true, SCREEN)));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    private static AccessibilityEvent event(int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName("com.doordash.driverapp");
        return event;
    }

    /** Dasher shows {@code root} full screen and says so with a window change; read at once. */
    private void show(OfferFilterService service, AccessibilityNodeInfo root) {
        dasherShows(service, root);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        settle(service);
    }

    /** Wraps the service's window list: counts each look, and holds it while {@code gate} is shut. */
    private static Supplier<List<AccessibilityWindowInfo>> slowWindows(OfferFilterService service,
                                                                      AtomicInteger looks, CountDownLatch entered,
                                                                      CountDownLatch gate) {
        return () -> {
            looks.incrementAndGet();
            if (entered != null) entered.countDown();
            if (gate != null) {
                try {
                    // Bounded, so an implementation reading on the calling thread fails rather than hangs.
                    gate.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return service.getWindows();
        };
    }

    private List<View> touchWatches() {
        ShadowWindowManagerImpl windows = Shadow.extract(app.getSystemService(WindowManager.class));
        List<View> watches = new ArrayList<>();
        for (View view : windows.getViews()) {
            if (!(view instanceof DasherTab) && !(view instanceof DasherGuide)) watches.add(view);
        }
        return watches;
    }

    private boolean tabShown() {
        ShadowWindowManagerImpl windows = Shadow.extract(app.getSystemService(WindowManager.class));
        for (View view : windows.getViews()) if (view instanceof DasherTab) return true;
        return false;
    }

    /** A finger landing anywhere on the screen, as Android reports it to the watching overlay (main thread). */
    private void touchScreen() {
        assertEquals("one touch watch while declining", 1, touchWatches().size());
        touchWatches().get(0).dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
    }

    /** Runs {@code work} on the scanner thread, as Android calls back on the handler it was given, and waits. */
    private static void onScanner(OfferFilterService service, Runnable work) {
        new Handler(service.scanLooper()).post(work);
        Shadows.shadowOf(service.scanLooper()).idle();
    }

    private void idleMainFor(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private AudioManager audio() {
        return app.getSystemService(AudioManager.class);
    }

    /** Players now running, by usage; {@code notify} tells the playback callbacks, on the calling thread. */
    private void playing(boolean notify, int... usages) {
        List<AudioAttributes> players = new ArrayList<>();
        for (int usage : usages) players.add(new AudioAttributes.Builder().setUsage(usage).build());
        ShadowAudioManager shadow = Shadows.shadowOf(audio());
        shadow.setActivePlaybackConfigurationsFor(players, notify);
    }

    private int alarmFloor() {
        return Build.VERSION.SDK_INT >= 28 ? audio().getStreamMinVolume(AudioManager.STREAM_ALARM) : 0;
    }

    private static boolean rings(Notification card) {
        return card.getGroupAlertBehavior() != Notification.GROUP_ALERT_SUMMARY;
    }

    /** A window list that fails as it is gone through, as a look at the windows can on a real phone. */
    private static List<AccessibilityWindowInfo> unreadableWindows() {
        return new AbstractList<AccessibilityWindowInfo>() {
            @Override public AccessibilityWindowInfo get(int index) {
                throw new IllegalStateException("window gone");
            }

            @Override public int size() {
                return 1;
            }
        };
    }

    /** The window manager, refusing to add the touch watch while {@link #refuse} is set (as Android can). */
    @Implements(className = "android.view.WindowManagerImpl", isInAndroidSdk = false)
    public static class RefusingWindowManager extends ShadowWindowManagerImpl {
        static volatile boolean refuse;

        @Implementation
        @Override
        public void addView(View view, ViewGroup.LayoutParams params) {
            if (refuse && !(view instanceof DasherTab) && !(view instanceof DasherGuide)) {
                throw new WindowManager.BadTokenException("refused");
            }
            super.addView(view, params);
        }
    }

    // ---- The real scanner thread ----

    @Test
    public void onItsOwnThreadAnOfferIsDeclinedAndItsConfirmationTapped() {
        OfferFilterService service = service(false);
        assertNotEquals("reads have a thread of their own", Looper.getMainLooper(), service.scanLooper());
        HandlerThread thread = (HandlerThread) service.scanLooper().getThread();
        assertEquals("above background work: a read and a decline are what the user waits on",
                android.os.Process.THREAD_PRIORITY_FOREGROUND, android.os.Process.getThreadPriority(thread.getThreadId()));

        show(service, offer("$7.90"));
        assertEquals("declined at once", 1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertTrue(Shadows.shadowOf(accept).getPerformedActions().isEmpty());
        assertTrue("Dasher is on screen, as the notification path reads it", OfferFilterService.isDasherForeground());
        assertEquals("the touch watch is up, placed on the main thread", 1, touchWatches().size());
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertTrue(FilterStore.lastStatus(app).contains("Decline requested"));

        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(service, confirmation(confirm));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals("one offer, one line", 1, recent.size());
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, recent.get(0).action);

        ShadowSystemClock.advanceBy(Duration.ofMillis(1200));
        show(service, idle());
        assertTrue("the watch goes once the decline is done", touchWatches().isEmpty());
    }

    @Test
    public void aSlowReadNeverHoldsUpTheMainThread() throws Exception {
        OfferFilterService service = service(false);
        AtomicInteger looks = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        service.windowSource = slowWindows(service, looks, entered, gate);
        dasherShows(service, offer("$7.90"));

        // A phone that takes its time listing the windows. Before, the event waited for the read on the main thread.
        long started = System.nanoTime();
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertTrue("the scanner is in the slow read", entered.await(2, TimeUnit.SECONDS));
        // Dasher's map keeps changing meanwhile: each change is only noted.
        for (int i = 0; i < 50; i++) {
            service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
            service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        }
        long handedOverMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue("101 events handed over in " + handedOverMs + " ms", handedOverMs < 500);

        // The main thread is free while the read is under way: Offer Filter's own screen keeps working.
        AtomicBoolean drawn = new AtomicBoolean();
        new Handler(Looper.getMainLooper()).post(() -> drawn.set(true));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(drawn.get());
        assertTrue("nothing tapped before the read finished", Shadows.shadowOf(decline).getPerformedActions().isEmpty());

        gate.countDown();
        settle(service);
        assertEquals("declined once the read finished", 1, Shadows.shadowOf(decline).getPerformedActions().size());
        // The slow read, and one more for all hundred changes noted meanwhile: never one read per event.
        assertEquals(2, looks.get());
        assertEquals(1, DecisionLog.recent(app, 10).size());
    }

    /**
     * A measurement more than a check: how long the main thread is held per event of Dasher's when each look at the
     * windows takes 200 ms (the user's phone took 0.2 to 0.7 s a read). Reading on the main looper is where every
     * read happened before; the scanner thread is where they happen now.
     */
    @Test
    public void mainThreadTimePerEventWithASlowWindowList() {
        long inline = mainThreadMsPerEvent(true);
        controller.destroy();
        controller = null;
        long threaded = mainThreadMsPerEvent(false);
        System.out.println("main thread per event, 200 ms window list: on the main looper " + inline
                + " ms, on the scanner thread " + threaded + " ms");
        assertTrue("on the main looper each event waits for the read: " + inline, inline >= 200);
        assertTrue("on the scanner thread it is only handed over: " + threaded, threaded < 20);
    }

    private long mainThreadMsPerEvent(boolean onMain) {
        OfferFilterService service = service(onMain);
        CountDownLatch never = new CountDownLatch(1);
        service.windowSource = () -> {
            try {
                // Bounded like a slow phone, not a gate: each look takes 200 ms.
                never.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return service.getWindows();
        };
        dasherShows(service, idle());
        int events = 5;
        long started = System.nanoTime();
        for (int i = 0; i < events; i++) service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        long perEvent = (System.nanoTime() - started) / 1_000_000L / events;
        never.countDown();
        settle(service);
        return perEvent;
    }

    @Test
    public void aTouchDuringAReadStopsTheTapThatReadWouldMake() throws Exception {
        OfferFilterService service = service(false);
        show(service, offer("$7.90"));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertEquals(1, touchWatches().size());

        // Dasher's "Are you sure?" comes up, and the phone is slow to read it.
        AtomicInteger looks = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        service.windowSource = slowWindows(service, looks, entered, gate);
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        dasherShows(service, confirmation(confirm));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        // The user touches the screen while that read is under way.
        touchWatches().get(0).dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        assertTrue("the offer is the user's from the touch on",
                OfferFilterService.userHasOffer(new OfferSnapshot(790, null, null, null)));
        gate.countDown();
        settle(service);

        assertTrue("the read in flight taps nothing more", Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertEquals("Offer Filter stopped tapping this offer", ShadowToast.getTextOfLatestToast());
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, DecisionLog.recent(app, 1).get(0).action);
        assertTrue(touchWatches().isEmpty());

        // Later reads of the same confirmation tap nothing either.
        show(service, confirmation(confirm));
        assertTrue(Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
    }

    @Test
    public void theTouchWatchGoesUpAheadOfTheMainThreadsQueueAndTheConfirmationWaitsForIt() {
        OfferFilterService service = service(false);
        // Offer Filter's own screen already has work queued on the main thread when the offer comes.
        AtomicInteger watchesWhenQueuedWorkRan = new AtomicInteger(-1);
        new Handler(Looper.getMainLooper()).post(() -> watchesWhenQueuedWorkRan.set(touchWatches().size()));

        dasherShows(service, offer("$7.90"));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        Shadows.shadowOf(service.scanLooper()).idle();
        assertEquals("declined at once", 1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertTrue("the main thread has not put the watch up yet", touchWatches().isEmpty());

        // Dasher's "Are you sure?" is read before then: a touch now would go unnoticed, so it is not tapped yet.
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        dasherShows(service, confirmation(confirm));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        Shadows.shadowOf(service.scanLooper()).idle();
        assertTrue("before, it was tapped with no watch up", Shadows.shadowOf(confirm).getPerformedActions().isEmpty());

        // The main thread runs: the watch goes up ahead of the work queued before it (before, after it)...
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, watchesWhenQueuedWorkRan.get());
        // ...and the confirmation is tapped the moment it is up, with no wait for the next read.
        Shadows.shadowOf(service.scanLooper()).idle();
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
        settle(service);
        assertEquals("one offer, one line", 1, DecisionLog.recent(app, 10).size());
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test
    @Config(shadows = RefusingWindowManager.class)
    public void aTouchWatchAndroidWouldNotAddIsAskedForAgainAndTheDeclineGoesOn() {
        RefusingWindowManager.refuse = true;
        OfferFilterService service = service(false);
        show(service, offer("$7.90"));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertTrue(touchWatches().isEmpty());
        assertEquals(1, count(DiagnosticLog.read(app), "touch watch unavailable"));

        // Without the watch the decline goes on, as it always has: the confirmation is not held for it.
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(service, confirmation(confirm));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
        assertEquals("asked again at the next read", 2, count(DiagnosticLog.read(app), "touch watch unavailable"));

        // Android takes it now: the next read asks again and the watch comes up (before, it was never asked again).
        RefusingWindowManager.refuse = false;
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        settle(service);
        assertEquals(1, touchWatches().size());

        ShadowSystemClock.advanceBy(Duration.ofMillis(1200));
        show(service, idle());
        assertTrue("the watch goes once the decline is done", touchWatches().isEmpty());
    }

    @Test
    public void aTouchIsTheUsersFromTheMomentItLandsWhileTheScannerHandsTheOfferBack() {
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        OfferFilterService service = service(false);
        show(service, offer("$7.90"));
        OfferSnapshot declined = new OfferSnapshot(790, null, null, null);
        AtomicReference<Boolean> asTheHandBackBegins = new AtomicReference<>();
        OfferFilterService.takeoverBeginsForTests =
                () -> asTheHandBackBegins.set(OfferFilterService.userHasOffer(declined));

        // The user touches the screen; the scanner has not taken the touch yet.
        touchScreen();
        assertTrue(OfferFilterService.userHasOffer(declined));
        // Dasher's notification of that offer, which fails the rules, comes now: it is left to the user.
        StatusBarNotification source = doorDashOffer("New Order!", "$7.90 • 2 stops (7.2 mi) • 21 min");
        ShadowNotificationListenerService dasher = Shadow.extract(listener.get());
        dasher.addActiveNotification(source);
        listener.get().onNotificationPosted(source, null);
        assertTrue(DiagnosticLog.read(app).contains("offer taken over by the user; notification left alone"));

        settle(service);
        // Before, the touch was marked taken before the takeover was published: for that moment, the notification
        // path saw neither and could decline the offer.
        assertEquals(Boolean.TRUE, asTheHandBackBegins.get());
        assertTrue(OfferFilterService.userHasOffer(declined));
        assertFalse(OfferFilterService.userHasOffer(new OfferSnapshot(610, null, null, null)));
        assertEquals("Offer Filter stopped tapping this offer", ShadowToast.getTextOfLatestToast());
        assertTrue(touchWatches().isEmpty());
        assertEquals("Dasher's own notification is never touched", 1,
                listener.get().getActiveNotifications().length);
    }

    @Test
    public void stoppingInTheMiddleOfAReadTapsNothing() throws Exception {
        OfferFilterService service = service(false);
        AtomicInteger looks = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        service.windowSource = slowWindows(service, looks, entered, gate);
        dasherShows(service, offer("$7.90"));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        // Accessibility is turned off (or an update replaces the app) while that read is under way.
        Thread scanner = service.scanLooper().getThread();
        controller.destroy();
        controller = null;
        gate.countDown();
        scanner.join(2000);
        assertFalse("the scanner thread ends", scanner.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue("the read under way taps nothing", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        assertTrue(DecisionLog.recent(app, 10).isEmpty());
        assertTrue(touchWatches().isEmpty());
        assertFalse(OfferFilterService.isConnected());
    }

    @Test
    public void onItsOwnThreadTheRingGoesDownAndANextOffersNotificationBringsItBackAtOnce() {
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        audio().setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        OfferFilterService service = service(false);
        show(service, offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        // Dasher's ring starts on the media stream too. Android says so on the scanner thread, where it goes down.
        onScanner(service, () -> playing(true, AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_MEDIA));
        assertTrue(audio().isStreamMute(AudioManager.STREAM_MUSIC));

        // A next offer's notification, on the main thread: the sound comes back at once, before any read.
        listener.get().onNotificationPosted(doorDashOffer(), null);
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_MUSIC));

        // A ring starting after that, perhaps the next offer's own, is not turned down again.
        onScanner(service, () -> playing(true, AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_MEDIA));
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_MUSIC));
        settle(service);
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
        assertFalse(audio().isStreamMute(AudioManager.STREAM_MUSIC));
        assertEquals(8, audio().getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    @Test
    public void aNextOfferAnnouncedAsTheScannerGoesToTurnTheRingDownKeepsItUp() throws Exception {
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        OfferFilterService service = service(false);
        show(service, idle());

        Object soundLock = ReflectionHelpers.getStaticField(OfferSilencer.class, "LOCK");
        Thread scanner = service.scanLooper().getThread();
        synchronized (soundLock) {
            // The scanner declines an offer and goes to turn its ring down, just as the main thread takes the sound's
            // lock for a next offer's notification.
            dasherShows(service, offer("$7.90"));
            service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
            waitUntilBlocked(scanner);
            listener.get().onNotificationPosted(doorDashOffer(), null);
        }
        settle(service);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        // Asked again under the lock, the declined offer is no longer the newest: before, it went down anyway (and
        // came back only at the next read).
        assertFalse("never turned down over a next offer", DiagnosticLog.read(app).contains("silenced alarm"));
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    /**
     * Until the scanner waits for the sound's lock inside the silencer. Blocked anywhere else (a moment on Robolectric's
     * message queue as the event wakes it, say) is not there yet: the read has not even begun.
     */
    private static void waitUntilBlocked(Thread thread) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.BLOCKED || !inSilencer(thread)) {
            assertTrue("the scanner reaches the sound's lock", System.nanoTime() < until);
            Thread.sleep(1);
        }
    }

    private static boolean inSilencer(Thread thread) {
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (OfferSilencer.class.getName().equals(frame.getClassName())) return true;
        }
        return false;
    }

    @Test
    public void stoppingPutsTheSoundBackAtOnceEvenWithTheScannerBusy() throws Exception {
        audio().setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        playing(false, AudioAttributes.USAGE_ALARM);
        OfferFilterService service = service(false);
        show(service, offer("$7.90"));
        assertEquals(alarmFloor(), audio().getStreamVolume(AudioManager.STREAM_ALARM));

        // The scanner is in a slow read when the service stops.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        service.windowSource = slowWindows(service, new AtomicInteger(), entered, gate);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread scanner = service.scanLooper().getThread();
        controller.destroy();
        controller = null;
        assertEquals("back at once, on the main thread", 5, audio().getStreamVolume(AudioManager.STREAM_ALARM));

        // The read finishes, and Dasher's ring is still playing: nothing goes down again.
        finishHeldRead(gate, scanner);
        assertEquals(5, audio().getStreamVolume(AudioManager.STREAM_ALARM));
    }

    /** Lets a read held at {@code gate} finish, and the stopped scanner thread end. */
    private static void finishHeldRead(CountDownLatch gate, Thread scanner) throws InterruptedException {
        gate.countDown();
        scanner.join(2000);
        assertFalse("the scanner thread ends", scanner.isAlive());
    }

    // ---- Whether Dasher is on screen, for the notification path ----

    @Test
    public void aNotificationJustAfterLeavingDasherRingsWithoutWaitingForTheWindowWatch() {
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        OfferFilterService service = service(false);
        show(service, idle());
        assertTrue(OfferFilterService.isDasherForeground());
        assertTrue("the tab is over Dasher", tabShown());

        // The user switches to another app. Dasher sends no event for that, and no time passes: the scanner's last
        // look still says Dasher is on screen.
        AccessibilityNodeInfo launcher = appRoot("com.google.android.apps.nexuslauncher");
        Shadows.shadowOf(service).setWindows(Collections.singletonList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, launcher, true, SCREEN)));
        Shadows.shadowOf(service).setRootInActiveWindow(launcher);
        assertTrue(OfferFilterService.isDasherForeground());

        // An offer's notification now: the notification path looks for itself, and rings at once.
        listener.get().onNotificationPosted(doorDashOffer(), null);
        List<Notification> cards = Shadows.shadowOf(app.getSystemService(NotificationManager.class))
                .getAllNotifications();
        assertEquals(1, cards.size());
        assertTrue("before, the last look kept it silent", rings(cards.get(0)));

        // The scanner is asked to look too: the snapshot and the tab follow at once.
        settle(service);
        assertFalse(OfferFilterService.isDasherForeground());
        assertFalse("the tab left with Dasher", tabShown());
    }

    @Test
    public void theNotificationPathsOwnLookOnlyEverFindsDasherGone() {
        OfferFilterService service = service(false);
        AtomicInteger looks = new AtomicInteger();
        service.windowSource = slowWindows(service, looks, null, null);

        // The scanner last saw no Dasher; Dasher comes on screen with no event yet. Nothing is asked: off screen.
        dasherShows(service, idle());
        assertFalse(OfferFilterService.isDasherOnScreenNow());
        assertEquals(0, looks.get());

        // Once the scanner has seen it, the notification path confirms it with a look that writes nothing.
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        settle(service);
        looks.set(0);
        OfferFilterService.sawDasherBeside(0);
        assertTrue(OfferFilterService.isDasherOnScreenNow());
        assertEquals(1, looks.get());
        settle(service);
        assertEquals("no look owed to the scanner when they agree", 1, looks.get());

        // A look that fails counts as off screen, there and on the scanner thread, which is asked to look too.
        service.windowSource = ScannerThreadTest::unreadableWindows;
        assertFalse(OfferFilterService.isDasherOnScreenNow());
        settle(service);
        assertFalse(OfferFilterService.isDasherForeground());
        assertFalse(tabShown());
    }

    @Test
    public void whetherDasherIsOnScreenIsReadFromTheLastLookAndAsksAndroidNothing() throws Exception {
        OfferFilterService service = service(false);
        AtomicInteger looks = new AtomicInteger();
        service.windowSource = slowWindows(service, looks, null, null);
        // Dasher's half of a split screen, with Offer Filter's half active; the scanner has not looked since.
        AccessibilityNodeInfo ours = appRoot("com.local.dasherfilter");
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, idle(), false, BOTTOM_HALF)));
        Shadows.shadowOf(service).setRootInActiveWindow(ours);
        OfferFilterService.sawDasherBeside(0);

        // Asked on the main thread (a notification) and on another (the updater): before, each asked Android for the
        // windows, and wrote down a sighting of Dasher beside, from whatever thread asked.
        assertFalse(OfferFilterService.isDasherForeground());
        boolean[] fromWorker = new boolean[1];
        Thread worker = new Thread(() -> fromWorker[0] = OfferFilterService.isDasherForeground());
        worker.start();
        worker.join();
        assertFalse(fromWorker[0]);
        assertEquals("nothing asked of Android", 0, looks.get());
        assertFalse("and nothing written", OfferFilterService.dasherBeside());

        // Dasher's next event: the scanner looks, and publishes what it saw.
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        settle(service);
        assertTrue(OfferFilterService.isDasherForeground());
        assertTrue(OfferFilterService.dasherBeside());
        assertTrue(looks.get() > 0);
    }

    @Test
    public void leavingDasherIsSeenByTheWindowWatchWithinHalfASecond() {
        OfferFilterService service = service(false);
        show(service, idle());
        assertTrue(OfferFilterService.isDasherForeground());

        // Dasher sends no event when another app comes in front of it.
        AccessibilityNodeInfo launcher = appRoot("com.google.android.apps.nexuslauncher");
        Shadows.shadowOf(service).setWindows(Collections.singletonList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, launcher, true, SCREEN)));
        Shadows.shadowOf(service).setRootInActiveWindow(launcher);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OfferFilterService.WINDOW_WATCH_MS));
        settle(service);
        assertFalse(OfferFilterService.isDasherForeground());
    }

    @Test
    public void aNotificationHandledBetweenAReadAndItsHistoryLineIsStillOneOffer() throws Exception {
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        OfferFilterService service = service(false);
        show(service, idle());

        // The scanner reads and declines the offer; its history line is on its way to the main thread...
        dasherShows(service, offer("$7.90"));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        Shadows.shadowOf(service.scanLooper()).idle();
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        Thread.sleep(5);
        // ...when Dasher's notification of it is handled there, first.
        listener.get().onNotificationPosted(doorDashOffer(), null);
        Thread.sleep(5);
        settle(service);

        NotificationManager manager = app.getSystemService(NotificationManager.class);
        assertEquals("no card left for an offer the screen read", 0,
                Shadows.shadowOf(manager).getAllNotifications().size());
        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals("one offer, one line", 1, recent.size());
        assertEquals(DecisionLog.Source.SCREEN, recent.get(0).source);
        assertTrue("the notification is folded into it", recent.get(0).notification != null);
        assertArrayEquals(new int[] {0, 1, 0}, DecisionLog.totals(app));
        assertFalse(FilterStore.lastStatus(app).contains("Background offer requires review"));
    }

    private StatusBarNotification doorDashOffer() {
        return doorDashOffer("New Delivery!", "New Order: Go to Store A");
    }

    private StatusBarNotification doorDashOffer(String title, String text) {
        Notification payload = new Notification.Builder(app, "source")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(text)
                .build();
        return new StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp", 3, "NEW_ORDER", 10001, 0,
                0, payload, android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    // ---- Content-change bursts while nothing is up (read on the main looper, so time is the test's) ----

    @Test
    public void whileNothingIsUpTheFirstContentChangeIsReadAtOnceAndTheRestOfItsBurstTogether() {
        OfferFilterService service = service(true);
        AtomicInteger looks = new AtomicInteger();
        service.windowSource = slowWindows(service, looks, null, null);
        show(service, idle());
        idleMainFor(OfferFilterService.QUIET_SCAN_GAP_MS);
        looks.set(0);

        // Dasher's map redraws twenty times in a moment. The first change after a quiet spell is read at once...
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        assertEquals(1, looks.get());
        // ...and the other nineteen together, once, 150 ms after that read (before, each was read at once).
        for (int i = 0; i < 19; i++) service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        assertEquals(1, looks.get());
        idleMainFor(OfferFilterService.QUIET_SCAN_GAP_MS - 1);
        assertEquals(1, looks.get());
        idleMainFor(1);
        assertEquals(2, looks.get());

        // A change after another quiet spell is read at once again.
        idleMainFor(OfferFilterService.QUIET_SCAN_GAP_MS);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        assertEquals(3, looks.get());
    }

    @Test
    public void anOfferDrawnByAContentChange20MsAfterAnIdleReadIsDeclinedAtTheEndOfTheGap() {
        OfferFilterService service = service(true);
        show(service, idle());
        idleMainFor(OfferFilterService.QUIET_SCAN_GAP_MS);
        // Dasher's map moves while nothing is up: read at once.
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));

        // 20 ms later Dasher draws an offer with a content change alone, no window change. This is the one wait a
        // first decline can have: until 150 ms after the idle read (before, up to a quarter second after the last
        // change).
        idleMainFor(20);
        dasherShows(service, offer("$7.90"));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        idleMainFor(OfferFilterService.QUIET_SCAN_GAP_MS - 20 - 1);
        assertTrue(Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        idleMainFor(1);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        // The decline line says what started its read and how long that read waited.
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.matches("(?s).*first-step Decline REQUESTED: [^\\n]*; read after content burst \\(waited "
                + (OfferFilterService.QUIET_SCAN_GAP_MS - 20) + " ms\\).*"));

        // With its decline under way, every change is read at once: its confirmation is tapped with no wait.
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        dasherShows(service, confirmation(confirm));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
    }

    @Test
    public void anyPartOfAnOfferOnTheLastReadMakesTheNextContentChangeReadAtOnce() {
        OfferFilterService service = service(true);
        show(service, idle());
        idleMainFor(OfferFilterService.QUIET_SCAN_GAP_MS);

        // Dasher starts drawing an offer: its pay and route first, no buttons yet (read at once: nothing was up).
        AccessibilityNodeInfo partial = node("", false);
        Shadows.shadowOf(partial).addChild(node("$7.90", false));
        Shadows.shadowOf(partial).addChild(node("2 stops (7.2 mi) • 21 min", false));
        dasherShows(service, partial);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));

        // 20 ms later its buttons are drawn: read and declined at once, since the last read showed part of an offer.
        idleMainFor(20);
        dasherShows(service, offer("$7.90"));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("; read after content (waited 0 ms)"));
    }

    @Test
    public void whileReadsLookAnywayTheWindowWatchAddsNoLookOfItsOwn() {
        OfferFilterService service = service(true);
        AtomicInteger looks = new AtomicInteger();
        service.windowSource = slowWindows(service, looks, null, null);
        show(service, idle());
        looks.set(0);

        // A read 300 ms after the last look: the watch due 500 ms after that look has nothing to add then.
        idleMainFor(300);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        assertEquals(1, looks.get());
        idleMainFor(200);
        assertEquals("before, the watch looked again 200 ms after a read", 1, looks.get());
        // It looks 500 ms after the read instead.
        idleMainFor(300);
        assertEquals(2, looks.get());
    }

    @Test
    public void aWindowChangeIsReadAtOnceEvenInABurst() {
        OfferFilterService service = service(true);
        show(service, idle());
        for (int i = 0; i < 5; i++) service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));

        // The offer screen opens: a window change, read and declined with no wait.
        dasherShows(service, offer("$7.90"));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
    }

    // ---- What a look asks of Android ----

    @Test
    public void fullScreenAsksOnlyForTheActiveRootAndOurOwnHalfIsAskedForOnce() {
        OfferFilterService service = service(true);
        // Dasher full screen, with other windows listed behind and over it (the launcher, the status bar).
        AccessibilityNodeInfo dasherScreen = idle();
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, dasherScreen, true, SCREEN),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, appRoot("com.google.android.apps.nexuslauncher"),
                        false, SCREEN),
                window(AccessibilityWindowInfo.TYPE_SYSTEM, appRoot("com.android.systemui"), false,
                        new Rect(0, 0, 1080, 50))));
        Shadows.shadowOf(service).setRootInActiveWindow(dasherScreen);
        int before = service.rootFetches;
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertTrue(OfferFilterService.isDasherForeground());
        assertEquals("the active window's root alone", 1, service.rootFetches - before);

        // Split screen with Offer Filter's half active: its window is known by its ID once its root was seen, and
        // never asked for again (only Offer Filter's own main thread could answer, and it may be busy drawing).
        AccessibilityNodeInfo ours = appRoot("com.local.dasherfilter");
        AccessibilityWindowInfo top = window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, TOP_HALF);
        AccessibilityNodeInfo dasherHalf = idle();
        AccessibilityWindowInfo bottom = window(AccessibilityWindowInfo.TYPE_APPLICATION, dasherHalf, false,
                BOTTOM_HALF);
        withId(top, 7, ours);
        withId(bottom, 9, dasherHalf);
        Shadows.shadowOf(service).setWindows(Arrays.asList(top,
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER), bottom));
        Shadows.shadowOf(service).setRootInActiveWindow(ours);
        before = service.rootFetches;
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertTrue(OfferFilterService.isDasherForeground());
        assertEquals("ours, the first time, then Dasher's", 2, service.rootFetches - before);
        before = service.rootFetches;
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        assertTrue(OfferFilterService.isDasherForeground());
        assertEquals("Dasher's alone from then on", 1, service.rootFetches - before);
    }

    /** Gives a window and its root Android's window ID. */
    private static void withId(AccessibilityWindowInfo window, int id, AccessibilityNodeInfo root) {
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(id);
        Shadows.shadowOf(root).setAccessibilityWindowInfo(window);
    }

    // ---- The slow-read line ----

    @Test
    public void aSlowReadIsLoggedOnceAMinuteWithItsCost() {
        OfferFilterService service = service(true);
        // Each look at the windows takes a fifth of a second, as on the user's phone.
        service.windowSource = () -> {
            try {
                Thread.sleep(OfferFilterService.SLOW_SCAN_MS + 50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return service.getWindows();
        };
        show(service, offer("$25.00"));
        show(service, idle());
        String log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "[scan] slow read: "));
        assertTrue(log, log.matches("(?s).*\\[scan\\] slow read: \\d+ ms, \\d+ nodes, 1 windows, after change "
                + "\\(waited \\d+ ms\\).*"));

        ShadowSystemClock.advanceBy(Duration.ofMillis(OfferFilterService.SLOW_SCAN_LOG_EVERY_MS));
        show(service, offer("$25.00"));
        assertEquals(2, count(DiagnosticLog.read(app), "[scan] slow read: "));
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}
