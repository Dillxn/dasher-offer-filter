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
import java.util.concurrent.atomic.AtomicLong;
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
        OfferFilterService.nodeFetchForTests = null;
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
        static final AtomicInteger refusedAttempts = new AtomicInteger();

        @Implementation
        @Override
        public void addView(View view, ViewGroup.LayoutParams params) {
            if (refuse && !(view instanceof DasherTab) && !(view instanceof DasherGuide)) {
                refusedAttempts.incrementAndGet();
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
        int roots = service.rootFetches;

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
        // The slow read, and one more for all hundred changes noted meanwhile: never one read per event. Each read asks
        // for the active window's root once; the pre-tap check uses metadata without fetching another root. (The decline's poll for its
        // question may list the windows once more meanwhile; that asks Android, not Dasher, and reads nothing.)
        assertEquals("pre-tap check uses window metadata, no extra root", 2, service.rootFetches - roots);
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
        RefusingWindowManager.refusedAttempts.set(0);
        OfferFilterService service = service(false);
        show(service, offer("$7.90"));
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        assertTrue(touchWatches().isEmpty());
        assertEquals(1, count(DiagnosticLog.read(app), "touch watch unavailable"));
        int firstRefusals = RefusingWindowManager.refusedAttempts.get();

        // Without the watch the decline goes on, as it always has: the confirmation is not held for it.
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(service, confirmation(confirm));
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
        assertTrue("asked again at the next read", RefusingWindowManager.refusedAttempts.get() > firstRefusals);
        assertEquals("unchanged refusal does not flood the log", 1, count(DiagnosticLog.read(app), "touch watch unavailable"));

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
        listener.get().onNotificationPosted(doorDashOffer("New Order!", "$25.00 • 2 stops (4.2 mi) • 18 min"), null);
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
            listener.get().onNotificationPosted(doorDashOffer("New Order!", "$25.00 • 2 stops (4.2 mi) • 18 min"), null);
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
        // Exercise the immediate card path: default-on Peek intentionally starts with a silent card.
        FilterStore.setPeek(app, false);
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
    public void everySlowReadAroundAnOfferIsLoggedWithWhereItsTimeWentAndAnIdleOneOnceAMinute() {
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
        String log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "[scan] slow read: "));
        assertTrue(log, log.matches("(?s).*\\[scan\\] slow read: \\d+ ms, \\d+ nodes, 1 windows, after change "
                + "\\(waited \\d+ ms\\); windows \\d{3,} ms, root \\d+ ms, traversal \\d+ ms; remote fetches \\d+ \\(≥2 ms\\); offer up\\n.*"));
        // While the offer is up, every slow read is logged: before, once a minute at most, so a report showed one.
        show(service, offer("$25.00"));
        show(service, idle());
        assertEquals(3, count(DiagnosticLog.read(app), "[scan] slow read: "));

        // Nothing up for a while: once a minute.
        ShadowSystemClock.advanceBy(Duration.ofMillis(OfferFilterService.HOT_MS));
        show(service, idle());
        show(service, idle());
        log = DiagnosticLog.read(app);
        assertEquals(log, 4, count(log, "[scan] slow read: "));
        assertFalse(log, lastLine(log, "[scan] slow read: ").endsWith("; offer up"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(OfferFilterService.SLOW_SCAN_LOG_EVERY_MS));
        show(service, idle());
        assertEquals(5, count(DiagnosticLog.read(app), "[scan] slow read: "));
    }

    // ---- Offer reads first ----

    /** A window list that takes {@code msEach} to give each of {@code size} windows, as a slow phone might. */
    private static List<AccessibilityWindowInfo> slowList(List<AccessibilityWindowInfo> real, int size, long msEach,
                                                          CountDownLatch entered) {
        return new AbstractList<AccessibilityWindowInfo>() {
            @Override public AccessibilityWindowInfo get(int index) {
                entered.countDown();
                try {
                    Thread.sleep(msEach);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return real.get(index % real.size());
            }

            @Override public int size() {
                return size;
            }
        };
    }

    @Test
    public void anOffersReadStartsAtOnceWhileTheWindowWatchIsInASlowLook() throws Exception {
        OfferFilterService service = service(false);
        show(service, idle());
        // The window watch's next look finds a phone slow to list its windows: a second for the lot.
        AtomicBoolean slow = new AtomicBoolean(true);
        AtomicLong offerReadAt = new AtomicLong();
        CountDownLatch watching = new CountDownLatch(1);
        service.windowSource = () -> {
            List<AccessibilityWindowInfo> real = service.getWindows();
            if (slow.get()) return slowList(real, 100, 10, watching);
            offerReadAt.compareAndSet(0, System.nanoTime());
            return real;
        };
        Handler scanner = new Handler(service.scanLooper());
        ShadowSystemClock.advanceBy(Duration.ofMillis(OfferFilterService.WINDOW_WATCH_MS));
        scanner.post(() -> { });
        assertTrue("the watch is looking", watching.await(2, TimeUnit.SECONDS));
        // Something the scanner was asked to do before the offer came waits in its queue.
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        scanner.post(() -> order.add("queued before the offer"));

        // The offer opens as a new screen.
        slow.set(false);
        dasherShows(service, offer("$7.90"));
        AtomicLong declinedAt = new AtomicLong();
        Shadows.shadowOf(decline).setOnPerformActionListener((action, args) -> {
            declinedAt.set(System.nanoTime());
            order.add("decline");
            return true;
        });
        long sentAt = System.nanoTime();
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        settle(service);

        assertEquals("declined", 1, Shadows.shadowOf(decline).getPerformedActions().size());
        long startedMs = (offerReadAt.get() - sentAt) / 1_000_000L;
        long declinedMs = (declinedAt.get() - sentAt) / 1_000_000L;
        System.out.println("offer read began " + startedMs + " ms, declined " + declinedMs + " ms after its event");
        // Before, the read waited for the watch's whole second, and for what was queued before it.
        assertTrue("the watch stopped at its next step: read " + startedMs + " ms after", startedMs < 100);
        assertEquals(Arrays.asList("decline", "queued before the offer"), order);
    }

    @Test
    public void readingAroundAClickStopsForAnOffer() throws Exception {
        OfferFilterService service = service(false);
        show(service, idle());
        // The user taps a button on Dasher's idle screen. Reading around it, Dasher answers slowly: 100 ms a node.
        AccessibilityNodeInfo button = node(null, true);
        for (int i = 0; i < 10; i++) Shadows.shadowOf(button).addChild(node("Label " + i, false));
        AtomicBoolean slow = new AtomicBoolean(true);
        CountDownLatch walking = new CountDownLatch(1);
        OfferFilterService.nodeFetchForTests = () -> {
            if (!slow.get() || !onStack("readClick")) return;
            walking.countDown();
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        AccessibilityEvent tap = event(AccessibilityEvent.TYPE_VIEW_CLICKED);
        ((org.robolectric.shadows.ShadowAccessibilityRecord) Shadow.extract(tap)).setSourceNode(button);
        service.onAccessibilityEvent(tap);
        assertTrue("reading around the click", walking.await(2, TimeUnit.SECONDS));

        // An offer opens meanwhile, a moment after the click.
        ShadowSystemClock.advanceBy(Duration.ofMillis(10));
        dasherShows(service, offer("$7.90"));
        AtomicLong declinedAt = new AtomicLong();
        Shadows.shadowOf(decline).setOnPerformActionListener((action, args) -> {
            declinedAt.set(System.nanoTime());
            return true;
        });
        long sentAt = System.nanoTime();
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        settle(service);
        slow.set(false);
        settle(service);

        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        long declinedMs = (declinedAt.get() - sentAt) / 1_000_000L;
        // Before, the click's reading went on for its whole second first.
        assertTrue("declined " + declinedMs + " ms after the offer's event", declinedMs < 400);
        // The click is still noted after the offer's read, named by what its event says (an offer is up now: nothing
        // is read around it), and never as an echo of the app's Decline tap, which came after it.
        String screens = DiagnosticLog.readScreens(app);
        assertTrue(screens, screens.contains("tap (not Offer Filter's) "));
        assertFalse(screens, screens.contains("tap (Offer Filter's own) "));
    }

    private static boolean onStack(String method) {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (OfferFilterService.class.getName().equals(frame.getClassName())
                    && method.equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    // ---- Dasher's question after the first Decline tap ----

    /** Lets {@code ms} of the scanner's time pass, and the scanner and the main thread do what fell due. */
    private void pass(OfferFilterService service, long ms) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms));
        settle(service);
    }

    /** Records when (uptime) {@code button} is tapped. */
    private static AtomicLong tapTime(AccessibilityNodeInfo button) {
        AtomicLong at = new AtomicLong(-1);
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            at.set(android.os.SystemClock.uptimeMillis());
            return true;
        });
        return at;
    }

    @Test
    public void dashersQuestionIsFoundByThePollWithNoEventAndTappedAtOnce() {
        OfferFilterService service = service(false);
        AccessibilityNodeInfo offerScreen = offer("$7.90");
        AtomicLong declineTapAt = tapTime(decline);
        show(service, offerScreen);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());

        // The offer stays as it was: the poll asks Android for the windows, sees nothing new, and reads nothing.
        // Before, the whole screen was read again every 200 ms.
        int roots = service.rootFetches;
        pass(service, 100);
        pass(service, 100);
        pass(service, 50);
        assertEquals("nothing changed, nothing read", roots, service.rootFetches);

        // Then Dasher's question opens in a window of its own; no event of it reaches the service.
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        AtomicLong confirmTapAt = tapTime(confirm);
        AccessibilityNodeInfo question = confirmation(confirm);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, question, true, BOTTOM_HALF),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, offerScreen, false, SCREEN)));
        Shadows.shadowOf(service).setRootInActiveWindow(question);
        long appearedAt = android.os.SystemClock.uptimeMillis();
        pass(service, 50);
        pass(service, 50);

        // Before, it waited for the next full read, 200 ms after the last one.
        assertEquals("tapped by the next poll", 1, Shadows.shadowOf(confirm).getPerformedActions().size());
        long afterAppearing = confirmTapAt.get() - appearedAt;
        assertTrue("tapped " + afterAppearing + " ms after it appeared",
                afterAppearing <= OfferFilterService.CONFIRM_POLL_MS);
        String log = DiagnosticLog.read(app);
        long sinceFirstTap = confirmTapAt.get() - declineTapAt.get();
        assertTrue(log, log.contains("confirmation found " + sinceFirstTap + " ms and tapped " + sinceFirstTap
                + " ms after the first Decline tap (read after confirmation poll)"));
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, DecisionLog.recent(app, 1).get(0).action);

        // The poll ends with the question tapped; Dasher closing it is read as before, and nothing is tapped twice.
        dasherShows(service, idle());
        pass(service, OfferFilterService.CONFIRM_POLL_WINDOW_MS);
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
    }

    @Test
    public void aReadOfTheOfferWaitingForItsQuestionStopsWhenTheQuestionsWindowOpens() throws Exception {
        OfferFilterService service = service(false);
        AccessibilityNodeInfo offerScreen = offer("$7.90");
        for (int i = 0; i < 20; i++) Shadows.shadowOf(offerScreen).addChild(node("Detail " + i, false));
        show(service, offerScreen);
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());

        // Dasher's countdown ticks: the offer is read again, and Dasher, animating, answers 50 ms a node.
        CountDownLatch reading = new CountDownLatch(1);
        OfferFilterService.nodeFetchForTests = () -> {
            reading.countDown();
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        assertTrue(reading.await(2, TimeUnit.SECONDS));

        // Dasher's question opens in a window of its own, with its window change.
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        AtomicLong tappedAt = new AtomicLong();
        Shadows.shadowOf(confirm).setOnPerformActionListener((action, args) -> {
            tappedAt.set(System.nanoTime());
            return true;
        });
        AccessibilityNodeInfo question = confirmation(confirm);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, question, true, BOTTOM_HALF),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, offerScreen, false, SCREEN)));
        Shadows.shadowOf(service).setRootInActiveWindow(question);
        long sentAt = System.nanoTime();
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        settle(service);

        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
        long tappedMs = (tappedAt.get() - sentAt) / 1_000_000L;
        System.out.println("question tapped " + tappedMs + " ms after its window opened");
        // Before, the read of the offer went on node by node (over a second), then the offer was read again.
        assertTrue("tapped " + tappedMs + " ms after its window opened", tappedMs < 600);
        assertEquals(1, count(DiagnosticLog.read(app), "[scan] read cut short by a window change while the question is "
                + "awaited, after "));
    }

    @Test
    public void whileItsQuestionIsAwaitedTheOffersWindowIsReadOnceARead() {
        OfferFilterService service = service(true);
        show(service, offer("$7.90"));
        int before = service.rootFetches;
        // Dasher's countdown ticks while the question is awaited.
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
        // Before, the window just read was asked for again and read a second time, looking for the question.
        assertEquals("the active window's root alone", 1, service.rootFetches - before);
    }

    @Test
    public void theConfirmationWaitsForTheTouchWatchFiftyMillisecondsAtMost() {
        OfferFilterService service = service(false);
        // The main thread is busy: it does not put the touch watch up.
        AccessibilityNodeInfo offerScreen = offer("$7.90");
        AtomicLong declineTapAt = tapTime(decline);
        dasherShows(service, offerScreen);
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        Shadows.shadowOf(service.scanLooper()).idle();
        assertEquals(1, Shadows.shadowOf(decline).getPerformedActions().size());
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        AtomicLong confirmTapAt = tapTime(confirm);
        dasherShows(service, confirmation(confirm));
        service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        Shadows.shadowOf(service.scanLooper()).idle();
        assertTrue("held for the watch at first", Shadows.shadowOf(confirm).getPerformedActions().isEmpty());

        ShadowSystemClock.advanceBy(Duration.ofMillis(OfferFilterService.WATCH_HOLD_MS));
        Shadows.shadowOf(service.scanLooper()).idle();
        // Before, it waited until the main thread put the watch up, however long that took.
        assertEquals("tapped 50 ms on all the same", 1, Shadows.shadowOf(confirm).getPerformedActions().size());
        assertTrue(touchWatches().isEmpty());
        assertEquals(OfferFilterService.WATCH_HOLD_MS, confirmTapAt.get() - declineTapAt.get());
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("confirmation found 0 ms and tapped 50 ms after the first Decline tap (read after "
                + "touch watch not up after 50 ms; touch watch not up)"));

        // The main thread gets to it: the watch comes up, and nothing is tapped twice.
        settle(service);
        assertEquals(1, touchWatches().size());
        assertEquals(1, Shadows.shadowOf(confirm).getPerformedActions().size());
    }

    // ---- Touches on the watch that are the app's own taps coming back ----

    /** A touch Android reports to the watch as landing at {@code at} (uptime). */
    private void touchAt(long at) {
        assertEquals("one touch watch while declining", 1, touchWatches().size());
        touchWatches().get(0).dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
    }

    @Test
    public void theAppsOwnTapsEchoingOnTheTouchWatchHandNothingBack() {
        OfferFilterService service = service(true);
        AccessibilityNodeInfo offerScreen = offer("$7.90");
        AtomicLong declineTapAt = tapTime(decline);
        show(service, offerScreen);
        // Android reports Offer Filter's own Decline tap to the watch as a touch, 15 ms after it.
        touchAt(declineTapAt.get() + 15);
        settle(service);
        assertFalse(OfferFilterService.userHasOffer(new OfferSnapshot(790, null, null, null)));

        AccessibilityNodeInfo confirm = node("Decline offer", true);
        AtomicLong confirmTapAt = tapTime(confirm);
        show(service, confirmation(confirm));
        assertEquals("the decline goes on", 1, Shadows.shadowOf(confirm).getPerformedActions().size());
        // Its confirmation tap comes back 20 ms later too. Before, this was "screen touched; automatic decline stopped
        // after its confirmation was tapped" and a toast, as both of the user's 0.4.41 declines showed.
        touchAt(confirmTapAt.get() + 20);
        settle(service);

        String log = DiagnosticLog.read(app);
        assertEquals(log, 1, count(log, "touch ignored: own-action echo, 15 ms after Offer Filter's tap"));
        assertEquals(log, 1, count(log, "touch ignored: own-action echo, 20 ms after Offer Filter's tap"));
        assertFalse(log, log.contains("screen touched"));
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, DecisionLog.recent(app, 1).get(0).action);
        assertEquals(null, ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aTouchLaterThanAnEchoIsTheUsersAndHandsTheOfferBack() {
        OfferFilterService service = service(true);
        AccessibilityNodeInfo offerScreen = offer("$7.90");
        AtomicLong declineTapAt = tapTime(decline);
        show(service, offerScreen);
        ShadowSystemClock.advanceBy(Duration.ofMillis(400));
        touchAt(declineTapAt.get() + OfferFilterService.OWN_ACTION_ECHO_MS + 1);
        settle(service);

        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(service, confirmation(confirm));
        assertTrue("handed back", Shadows.shadowOf(confirm).getPerformedActions().isEmpty());
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("touch during decline: the user's, 151 ms after Offer Filter's last tap"));
        assertTrue(log, log.contains("screen touched (151 ms after Offer Filter's last tap); automatic decline stopped"));
        assertEquals(DecisionLog.Action.USER_TOOK_OVER, DecisionLog.recent(app, 1).get(0).action);
        assertEquals("Offer Filter stopped tapping this offer", ShadowToast.getTextOfLatestToast());
    }

    private static String lastLine(String text, String part) {
        String last = "";
        for (String line : text.split("\n")) if (line.contains(part)) last = line;
        return last;
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}
