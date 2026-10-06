package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowAppTask;
import org.robolectric.shadows.ShadowNotificationListenerService;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Peek, the user's choice ("peek mode should have a toggle and be on by default"): Dasher's background notification
 * names only the store, so with Dasher hidden and the phone unlocked Offer Filter brings Dasher up for a moment, reads
 * the offer, declines it if it fails the rules, and takes the user back to the app they were in (Maps, the home screen
 * or Offer Filter). Passing or unclear offers stay up; anything of the user's leaves Dasher up; a peek that cannot run
 * says why in one "[peek] skipped" line and changes nothing else. Written against what the app shows and logs only, so
 * it compiles (and fails) on the code before Peek. Simulated Android only: the launch and the return on a real phone,
 * and Dasher brought up mid-offer, are for a handset.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class PeekTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final Rect TOP_HALF = new Rect(0, 0, 1080, 1000);
    private static final Rect DIVIDER = new Rect(0, 1000, 1080, 1040);
    private static final Rect BOTTOM_HALF = new Rect(0, 1040, 1080, 2040);
    private static final String DASHER = "com.doordash.driverapp";
    private static final ComponentName DASHER_HOME = new ComponentName(DASHER, DASHER + ".Home");
    private static final String MAPS = "com.google.android.apps.maps";
    private static final ComponentName MAPS_HOME = new ComponentName(MAPS, MAPS + ".MapsActivity");
    private static final String LAUNCHER = "com.example.launcher";
    private static final ComponentName LAUNCHER_HOME = new ComponentName(LAUNCHER, LAUNCHER + ".Home");
    private static final String OTHER = "com.example.reader";
    private static final ComponentName OTHER_HOME = new ComponentName(OTHER, OTHER + ".Main");
    private static final String OURS = "com.local.dasherfilter";
    private static final int AS_A_LAUNCHER_DOES =
            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION;

    private Application app;
    private ServiceController<OfferFilterService> screen;
    private ServiceController<OfferNotificationService> listener;
    private AccessibilityNodeInfo decline;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        AreaMap.forgetCache();
        OfferSilencer.forgetCache();
        OfferFilterService.forgetScreenState();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        installed(DASHER_HOME, Intent.CATEGORY_LAUNCHER);
        installed(MAPS_HOME, Intent.CATEGORY_LAUNCHER);
        installed(OTHER_HOME, Intent.CATEGORY_LAUNCHER);
        installed(LAUNCHER_HOME, Intent.CATEGORY_HOME);
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        idle();
    }

    @After
    public void tearDown() {
        listener.destroy();
        if (screen != null) screen.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.forgetScreenState();
    }

    // ---- The phone ----

    /** An app on the simulated phone, with a launcher (or home) entry. */
    private void installed(ComponentName activity, String category) {
        ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        android.content.pm.ActivityInfo declared = packages.addActivityIfNotPresent(activity);
        declared.enabled = true;
        declared.exported = true;
        IntentFilter entry = new IntentFilter(Intent.ACTION_MAIN);
        entry.addCategory(category);
        entry.addCategory(Intent.CATEGORY_DEFAULT);
        packages.addIntentFilterForActivity(activity, entry);
    }

    private AccessibilityNodeInfo node(String pkg, String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName(pkg);
        if (text != null) node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        if (clickable) {
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(node).setOnPerformActionListener((action, args) -> true);
        }
        return node;
    }

    /** Another app's screen: its root and a label. */
    private AccessibilityNodeInfo app(String pkg) {
        AccessibilityNodeInfo root = node(pkg, null, false);
        Shadows.shadowOf(root).addChild(node(pkg, "Head north on Main St", false));
        return root;
    }

    /** A Compose button of Dasher's: a clickable node without text, holding its label. */
    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo button = node(DASHER, null, true);
        Shadows.shadowOf(button).addChild(node(DASHER, label, false));
        return button;
    }

    /** An offer as Dasher draws it; {@code pay} null for one whose pay does not show. */
    private AccessibilityNodeInfo offer(String pay) {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        decline = button("Decline");
        Shadows.shadowOf(root).addChild(decline);
        if (pay != null) {
            Shadows.shadowOf(root).addChild(node(DASHER, pay, false));
            Shadows.shadowOf(root).addChild(node(DASHER, "incl. tips", false));
        }
        Shadows.shadowOf(root).addChild(node(DASHER, "2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(button("Accept"));
        Shadows.shadowOf(root).addChild(node(DASHER, "0:35", false));
        return root;
    }

    /** Dasher's question after a Decline tap. */
    private AccessibilityNodeInfo question(AccessibilityNodeInfo declineOffer) {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        Shadows.shadowOf(root).addChild(node(DASHER, "Are you sure you want to decline this offer?", false));
        Shadows.shadowOf(root).addChild(node(DASHER, "Does not lower acceptance rate", false));
        Shadows.shadowOf(root).addChild(declineOffer);
        Shadows.shadowOf(root).addChild(button("View offer details"));
        return root;
    }

    private AccessibilityNodeInfo finding() {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        Shadows.shadowOf(root).addChild(node(DASHER, "Finding offers", false));
        return root;
    }

    private static AccessibilityWindowInfo window(int type, AccessibilityNodeInfo root, boolean active, Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(type);
        shadow.setRoot(root);
        shadow.setActive(active);
        shadow.setBoundsInScreen(bounds);
        return window;
    }

    /** {@code root}'s app fills the screen and is the one in front. */
    private void inFront(AccessibilityNodeInfo root) {
        OfferFilterService service = screen.get();
        Shadows.shadowOf(service).setWindows(Collections.singletonList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, root, true, SCREEN)));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    /** Screen reading connects with {@code front} in front. */
    private OfferFilterService connect(AccessibilityNodeInfo front) {
        screen = Robolectric.buildService(OfferFilterService.class).create();
        inFront(front);
        screen.get().onServiceConnected();
        idle();
        return screen.get();
    }

    /** Dasher comes up showing {@code root}, and says so with a window change. */
    private void dasherShows(AccessibilityNodeInfo root) {
        inFront(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER);
        // As Android stamps every event it sends.
        event.setEventTime(SystemClock.uptimeMillis());
        screen.get().onAccessibilityEvent(event);
        idle();
    }

    /** Dasher's background notification of a new offer: the store only, no pay, miles or time. */
    private StatusBarNotification offerNotification(String store) {
        return notification("New Delivery!", "New Order: Go to " + store, System.currentTimeMillis());
    }

    private StatusBarNotification notification(String title, String text, long postedAt) {
        Notification payload = new Notification.Builder(app, "dasher_offers")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(text)
                .build();
        return new StatusBarNotification(DASHER, DASHER, 3, "NEW_ORDER", 10001, 0, 0, payload,
                android.os.Process.myUserHandle(), postedAt);
    }

    private void post(String store) {
        listener.get().onNotificationPosted(offerNotification(store), null);
        idle();
    }

    private StatusBarNotification postTappableNative(boolean sounded) throws Exception {
        StatusBarNotification source = offerNotification("Taco Bell");
        source.getNotification().contentIntent = android.app.PendingIntent.getActivity(app, 7,
                new Intent().setComponent(DASHER_HOME), android.app.PendingIntent.FLAG_IMMUTABLE);
        ShadowNotificationListenerService nativeAlerts = Shadow.extract(listener.get());
        nativeAlerts.addActiveNotification(source);
        listener.get().onNotificationPosted(source, sounded ? SameOfferAdapterTest.ranking(source,
                new android.app.NotificationChannel("dasher_offers", "Dasher", NotificationManager.IMPORTANCE_HIGH),
                NotificationManager.IMPORTANCE_HIGH, true, source.getPostTime()) : null);
        idle();
        return source;
    }

    @Test public void aTappableNativeAlertCanPeekWithoutADuplicateQuietCard() throws Exception {
        connect(app(MAPS));
        postTappableNative(false);
        assertEquals("the native alert provides the tap while Peek waits", 0, cards());
        dasherOpened();
        assertEquals("the native alert was never dismissed", 1, listener.get().getActiveNotifications().length);
    }

    @Test public void skippedQuietPeekStillRingsOnceWhenNativeWasSilent() throws Exception {
        connect(app(MAPS));
        postTappableNative(false);
        assertEquals(0, cards());
        for (int i = 0; i < 4; i++) { pass(600); touchNow(); }
        pass(600);
        assertNull(started());
        assertEquals("silent native cannot consume the app's necessary fallback bell", 1, cards());
        assertEquals(1, listener.get().getActiveNotifications().length);
    }

    @Test public void skippedQuietPeekDoesNotDuplicateAnAlreadyHeardNativeAlert() throws Exception {
        connect(app(MAPS));
        postTappableNative(true);
        for (int i = 0; i < 4; i++) { pass(600); touchNow(); }
        pass(600);
        assertNull(started());
        assertEquals(0, cards());
        assertEquals(1, listener.get().getActiveNotifications().length);
    }

    @Test
    @Config(shadows = NotificationConsolidationTest.NativeNotifications.class)
    public void skippedQuietPeekKeepsItsSilentNativeFallbackWhenListingBecomesUnknown() throws Exception {
        connect(app(MAPS));
        postTappableNative(false);
        NotificationConsolidationTest.NativeNotifications nativeAlerts = Shadow.extract(listener.get());
        nativeAlerts.failList = true;
        for (int i = 0; i < 4; i++) { pass(600); touchNow(); }
        pass(600);
        assertNull(started());
        assertEquals("unknown listing is not proof that the offer disappeared", 1, cards());
        assertEquals(0, nativeAlerts.cancellations);
    }

    @Test public void aRemovedNativeCoveredOfferCannotReviveItsFallback() throws Exception {
        connect(app(MAPS));
        StatusBarNotification source = postTappableNative(false);
        listener.get().onNotificationRemoved(source);
        pass(3_000);
        assertNull(started());
        assertEquals(0, cards());
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private Intent started() {
        return Shadows.shadowOf(app).getNextStartedActivity();
    }

    /** Asserts that Dasher was opened as its launcher icon opens it, nothing cleared or reset. */
    private void dasherOpened() {
        pass(Peek.QUIET_MS);
        Intent opened = started();
        assertNotNull("Dasher is brought up", opened);
        assertEquals(DASHER_HOME, opened.getComponent());
        assertEquals("as its launcher icon opens it: nothing cleared or reset", AS_A_LAUNCHER_DOES,
                opened.getFlags());
        assertNull("explicit component only, like the launcher", opened.getPackage());
    }

    private List<Integer> globalActions() {
        return Shadows.shadowOf(screen.get()).getGlobalActionsPerformed();
    }

    private static List<Long> taps(AccessibilityNodeInfo button) {
        List<Long> at = new CopyOnWriteArrayList<>();
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            at.add(SystemClock.uptimeMillis());
            return true;
        });
        return at;
    }

    private List<View> touchWatches() {
        ShadowWindowManagerImpl windows = Shadow.extract(app.getSystemService(WindowManager.class));
        List<View> watches = new ArrayList<>();
        for (View view : windows.getViews()) {
            if (!(view instanceof DasherTab) && !(view instanceof DasherGuide)) watches.add(view);
        }
        return watches;
    }

    /** A finger lands now; Android reports it to the touch watch with its own time. */
    private void touchNow() {
        long at = SystemClock.uptimeMillis();
        assertEquals("the touch watch is up", 1, touchWatches().size());
        touchWatches().get(0).dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        idle();
    }

    private int cards() {
        return Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getAllNotifications().size();
    }

    private static String log(Context context) {
        return DiagnosticLog.read(context);
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    /** Declines the offer Dasher shows (its Decline, then its question's), as on any offer on screen. */
    private void declinedOnScreen() {
        List<Long> declines = taps(decline);
        AccessibilityNodeInfo shown = offerRoot;
        dasherShows(shown);
        assertEquals("declined at once, as any offer on screen", 1, declines.size());
        assertNull("not back while the decline is unconfirmed", started());
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm);
        dasherShows(question(confirm));
        assertEquals("its question confirmed", 1, confirms.size());
        assertNull("not back while Dasher still asks", started());
        // Dasher closes its question a moment later (before any retry of it is due).
        pass(100);
    }

    private AccessibilityNodeInfo offerRoot;

    // ---- A peek that declines, and goes back ----

    @Test
    public void aFailingBackgroundOfferIsPeekedAtDeclinedAndTheUserIsTakenBackToMaps() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        contains(log(app), "[peek] opening Dasher for Taco Bell (was in front: a navigation app)");
        assertEquals("its card is posted as today", 1, cards());

        offerRoot = offer("$7.90");
        declinedOnScreen();
        assertFalse("the touch watch is up for the whole peek", touchWatches().isEmpty());
        dasherShows(finding());

        Intent back = started();
        assertNotNull("back to Maps once the decline completed", back);
        assertEquals(MAPS_HOME, back.getComponent());
        assertEquals("as a launcher opens it, so its navigation comes back as it was", AS_A_LAUNCHER_DOES,
                back.getFlags());
        assertNull(started());
        contains(log(app), "[peek] returned to a navigation app: the offer was declined");
        assertEquals("the screen's reading cleared the card", 0, cards());
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.Action.CONFIRMATION_TAPPED, line.action);
        assertEquals("one offer, one line", 1, DecisionLog.recent(app, 10).size());
        contains(DecisionLog.report(app, 1), "| screen (peeked) |");
        assertTrue("the touch watch is down", touchWatches().isEmpty());

        // Nothing more happens: no second return, and the peek is over.
        pass(25_000);
        assertNull(started());
        assertTrue(globalActions().isEmpty());
    }

    @Test
    public void fromTheHomeScreenItGoesBackHome() {
        connect(app(LAUNCHER));
        post("Taco Bell");
        dasherOpened();
        contains(log(app), "(was in front: the home screen)");
        offerRoot = offer("$7.90");
        declinedOnScreen();
        dasherShows(finding());

        assertEquals("Android's Home action", Collections.singletonList(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME), globalActions());
        assertNull("no app is opened for it", started());
        contains(log(app), "[peek] returned to the home screen: the offer was declined");
    }

    @Test
    public void fromOfferFiltersOwnScreenItGoesBackToOfferFilter() {
        Appearance.choose(app, Appearance.Mode.NIGHT);
        connect(app(OURS));
        post("Taco Bell");
        dasherOpened();
        offerRoot = offer("$7.90");
        declinedOnScreen();
        dasherShows(finding());

        Intent back = started();
        assertNotNull(back);
        assertEquals(LauncherAppearance.NIGHT, back.getComponent().getClassName());
        assertEquals(Intent.ACTION_MAIN, back.getAction());
        assertTrue(back.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(AS_A_LAUNCHER_DOES, back.getFlags());
        contains(log(app), "[peek] returned to " + AppName.NAME + ": the offer was declined");
    }

    @Test
    public void whenNoOfferShowsWithin4SecondsAndItsNotificationIsGoneItGoesBack() {
        connect(app(MAPS));
        // Dasher withdrew the offer: its notification is no longer listed.
        post("Taco Bell");
        dasherOpened();
        // The offer expired before Dasher came up: Dasher shows the wait for offers.
        dasherShows(finding());
        pass(3_800);
        assertNull("not before 4 s", started());
        pass(400);
        Intent back = started();
        assertNotNull("back after 4 s with no offer", back);
        assertEquals(MAPS_HOME, back.getComponent());
        contains(log(app), "[peek] returned to a navigation app: offer withdrawn: no offer showed within 4 s");
        contains(log(app), "offer notification=gone");
    }

    @Test
    public void aLoadingScreenRestartsTheEmptyWaitBeforeALateOffer() {
        connect(app(OTHER));
        post("Taco Bell");
        dasherOpened();
        dasherShows(finding());
        pass(3_000);
        dasherShows(node(DASHER, null, false));
        pass(800);
        dasherShows(finding());
        pass(400);
        assertNull("the interrupted empty interval cannot return over a late-loading offer", started());

        dasherShows(offer("$25.00"));
        pass(4_500);
        assertNull("the late passing offer stays visible to the user", started());
        assertTrue(globalActions().isEmpty());
        contains(log(app), "[peek] left Dasher up because the offer passes your rules");
    }

    @Test
    public void anItemOnlyOfferRestartsTheEmptyWait() {
        connect(app(OTHER));
        post("Taco Bell");
        dasherOpened();
        dasherShows(finding());
        pass(3_000);
        AccessibilityNodeInfo partial = finding();
        Shadows.shadowOf(partial).addChild(node(DASHER, "3 items", false));
        dasherShows(partial);
        pass(1_200);
        assertNull("a partial shopping offer is not an empty screen", started());

        dasherShows(finding());
        pass(3_800);
        assertNull("the full empty interval must start again after the partial offer", started());
        pass(400);
        assertNotNull("a later complete empty interval still permits returning", started());
    }

    @Test
    public void anUnavailableFinalReadCannotReuseAnEarlierEmptyScreen() {
        OfferFilterService service = connect(app(OTHER));
        post("Taco Bell");
        dasherOpened();
        AccessibilityNodeInfo readable = finding();
        dasherShows(readable);
        java.util.function.Supplier<List<AccessibilityWindowInfo>> windows = service.windowSource;
        java.util.concurrent.atomic.AtomicBoolean unavailable = new java.util.concurrent.atomic.AtomicBoolean();
        service.windowSource = () -> {
            List<AccessibilityWindowInfo> listed = windows.get();
            boolean finalRead = Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("peekStep"))
                    && Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("checkReadableOffer"));
            if (finalRead && unavailable.compareAndSet(false, true)) {
                // Android briefly cannot supply the active root during the deadline's required fresh read.
                for (AccessibilityWindowInfo window : listed) Shadows.shadowOf(window).setRoot(null);
                Shadows.shadowOf(service).setRootInActiveWindow(null);
            } else if (unavailable.get()) {
                // Metadata and the root recover before the old code's return guards look again.
                for (AccessibilityWindowInfo window : listed) Shadows.shadowOf(window).setRoot(readable);
                Shadows.shadowOf(service).setRootInActiveWindow(readable);
            }
            return listed;
        };
        pass(4_200);
        assertTrue("the deadline performed its fresh read", unavailable.get());
        assertNull("an unavailable final read cannot authorize a return from stale empty evidence", started());
        service.windowSource = windows;
        dasherShows(offer("$25.00"));
        assertNull("the later readable passing offer remains visible", started());
        assertTrue(globalActions().isEmpty());
    }

    @Test
    public void aSlowFinalReadCannotReturnAfterThePeekDeadline() {
        assertSlowPeekReturnStopsAtDeadline("checkReadableOffer");
    }

    @Test
    public void aSlowFinalReturnCheckCannotOutliveThePeekDeadline() {
        assertSlowPeekReturnStopsAtDeadline("returnPeekOnMain");
    }

    private void assertSlowPeekReturnStopsAtDeadline(String delayedMethod) {
        OfferFilterService service = connect(app(OTHER));
        post("Taco Bell");
        dasherOpened();
        dasherShows(finding());
        java.util.function.Supplier<List<AccessibilityWindowInfo>> windows = service.windowSource;
        java.util.concurrent.atomic.AtomicBoolean delayed = new java.util.concurrent.atomic.AtomicBoolean();
        service.windowSource = () -> {
            boolean returnPath = Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("peekStep"))
                    && Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals(delayedMethod));
            if (returnPath && delayed.compareAndSet(false, true)) {
                // A platform window/root query can finish after the original whole-peek deadline.
                ShadowSystemClock.advanceBy(Duration.ofMillis(Peek.MAX_MS));
            }
            return windows.get();
        };
        pass(4_200);
        assertTrue("the final return path crossed the deadline", delayed.get());
        assertNull("late platform work must leave Dasher visible after the whole-peek deadline", started());
        assertTrue(globalActions().isEmpty());
    }

    // ---- What leaves Dasher up ----

    @Test
    public void aPassingOfferStaysUpForTheUser() {
        connect(app(OTHER));
        post("Taco Bell");
        dasherOpened();
        offerRoot = offer("$25.00");
        List<Long> declines = taps(decline);
        dasherShows(offerRoot);
        assertTrue(declines.isEmpty());
        pass(25_000);
        assertNull("Dasher stays up: nothing else is opened", started());
        assertTrue(globalActions().isEmpty());
        contains(log(app), "[peek] left Dasher up because the offer passes your rules");
        contains(DecisionLog.report(app, 1), "| screen (peeked) |");
        assertEquals(DecisionLog.Action.PASSES, DecisionLog.recent(app, 1).get(0).action);
    }

    @Test
    public void anUnclearOfferStaysUpForTheUser() {
        connect(app(OTHER));
        post("Taco Bell");
        dasherOpened();
        // Dasher shows the offer, but not its pay: unknown is review, never a decline.
        offerRoot = offer(null);
        List<Long> declines = taps(decline);
        dasherShows(offerRoot);
        assertTrue(declines.isEmpty());
        pass(25_000);
        assertNull("Dasher stays up for the user", started());
        assertTrue(globalActions().isEmpty());
        contains(log(app), "[peek] left Dasher up because the offer needs your review");
    }

    @Test
    public void aTouchOnlyBeforeDasherAppearedDoesNotCancelTheReturnAfterTheAutomaticDecline() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        // The user touches the screen as Dasher opens, before its window appeared: the touch was meant for Maps.
        pass(100);
        touchNow();
        pass(50);

        // The offer is declined at once, as any offer on screen, and the user is taken back (the user's approval, A5).
        offerRoot = offer("$7.90");
        List<Long> declines = taps(decline);
        dasherShows(offerRoot);
        assertEquals(1, declines.size());
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm);
        dasherShows(question(confirm));
        assertEquals(1, confirms.size());
        pass(300);
        dasherShows(finding());
        Intent back = started();
        assertNotNull("back to Maps after the completed automatic decline: " + log(app), back);
        assertEquals(MAPS_HOME, back.getComponent());
        contains(log(app), "a touch before Dasher appeared was meant for a navigation app");
    }

    @Test
    public void aTouchAfterDasherAppearedLeavesItUpAfterTheDecline() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        // Dasher's window appears (its event), and only then the user touches, before Peek read it.
        AccessibilityEvent appeared = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        appeared.setPackageName(DASHER);
        pass(100);
        appeared.setEventTime(SystemClock.uptimeMillis());
        Shadows.shadowOf(screen.get()).setWindows(Collections.emptyList());
        screen.get().onAccessibilityEvent(appeared);
        pass(50);
        touchNow();

        offerRoot = offer("$7.90");
        List<Long> declines = taps(decline);
        dasherShows(offerRoot);
        assertEquals("still declined at once, as any offer on screen", 1, declines.size());
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        dasherShows(question(confirm));
        pass(300);
        dasherShows(finding());
        pass(25_000);
        assertNull("never back after the user touched Dasher", started());
        assertTrue(globalActions().isEmpty());
        contains(log(app), "[peek] left Dasher up because you touched the screen as it opened");
    }

    @Test
    public void aTouchAfterTheConfirmationStillLeavesDasherUp() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        offerRoot = offer("$7.90");
        declinedOnScreen();
        // 200 ms after the confirmation tap (past its echo window), the user touches the screen.
        pass(100);
        touchNow();
        dasherShows(finding());
        pass(25_000);
        assertNull("never back after anything of the user's", started());
        contains(log(app), "[peek] left Dasher up because you touched the screen");
    }

    // ---- Why a peek does not start ----

    /** Maps in front, the offer's notification: no peek, the card as today, and one line saying why. */
    private void notPeeked(String why) {
        post("Taco Bell");
        assertNull("Dasher is not opened", started());
        assertTrue(globalActions().isEmpty());
        contains(log(app), "[peek] skipped: " + why);
    }

    @Test
    public void notWhileThePhoneIsLocked() {
        OfferFilterService service = connect(app(MAPS));
        Shadows.shadowOf(service.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        notPeeked("the phone is locked");
        assertEquals("its card, as today", 1, cards());
    }

    @Test
    public void notWhileTheScreenIsOff() {
        OfferFilterService service = connect(app(MAPS));
        Shadows.shadowOf(service.getSystemService(PowerManager.class)).setIsInteractive(false);
        notPeeked("the screen is off");
    }

    @Test
    public void notOnACall() {
        OfferFilterService service = connect(app(MAPS));
        service.getSystemService(AudioManager.class).setMode(AudioManager.MODE_IN_CALL);
        notPeeked("a call is under way");
    }

    @Test
    public void notWithTheSwitchOff() {
        connect(app(MAPS));
        app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE).edit()
                .putBoolean("peek_background_offers", false).commit();
        notPeeked("Peek is off in Settings");
    }

    @Test
    public void notWhileAutoDeclineIsPaused() {
        connect(app(MAPS));
        FilterStore.save(app, new FilterSettings(false, 2000, 0, 0, 0, 0));
        notPeeked("auto-decline is paused");
    }

    @Test
    public void notBeforeTheNoticeIsAccepted() {
        connect(app(MAPS));
        ConsentedTestApp.forget(app);
        notPeeked("the notice isn't accepted yet");
        assertTrue("nothing decided", DecisionLog.recent(app, 10).isEmpty());
    }

    @Test
    public void notWhileDasherIsOnScreen() {
        connect(finding());
        notPeeked("Dasher is on screen");
    }

    @Test
    public void notInSplitScreen() {
        OfferFilterService service = connect(app(MAPS));
        AccessibilityNodeInfo maps = app(MAPS);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, maps, true, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, app("com.example.music"), false, BOTTOM_HALF)));
        Shadows.shadowOf(service).setRootInActiveWindow(maps);
        notPeeked("the screen is split");
    }

    @Test
    public void notWhenTheAppInFrontIsUnknown() {
        OfferFilterService service = connect(app(MAPS));
        // The notification shade is down: no app's window is the active one.
        AccessibilityNodeInfo shade = node("com.android.systemui", "Notifications", false);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, app(MAPS), false, SCREEN),
                window(AccessibilityWindowInfo.TYPE_SYSTEM, shade, true, SCREEN)));
        Shadows.shadowOf(service).setRootInActiveWindow(shade);
        notPeeked("the app in front is unknown");
    }

    @Test
    public void neverOnAReplay() {
        connect(app(MAPS));
        StatusBarNotification shown = offerNotification("Taco Bell");
        ShadowNotificationListenerService dasher = Shadow.extract(listener.get());
        dasher.addActiveNotification(shown);
        // The listener reconnects: what Android still shows is replayed.
        listener.get().onListenerConnected();
        idle();
        assertNull(started());
        contains(log(app), "[peek] skipped: a replay after a reconnect or a rules change, not a fresh post");
    }

    @Test
    public void atLeastFiveSecondsFromTheEndOfThePreviousPeek() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        // No offer by the time Dasher is up: back after 4 s.
        dasherShows(finding());
        pass(4_200);
        assertEquals(MAPS_HOME, started().getComponent());

        // The user is in Maps again; another offer comes 5 s after the last peek began.
        inFront(app(MAPS));
        pass(800);
        post("Store B");
        assertNull("too soon", started());
        String log = log(app);
        contains(log, "[peek] skipped: the last peek ended 1.");
        contains(log, " s ago (5 s apart at least)");

        // 15 s after the last peek began, the next offer is peeked at.
        pass(4_100);
        post("Store C");
        dasherOpened();
        contains(log(app), "[peek] opening Dasher for Store C (was in front: a navigation app)");
    }

    @Test
    public void anOfferAPeekLeftWithTheUserIsNotPeekedAtAgainOnItsKeyUntilTheScreenSeesItEnd() {
        connect(app(OTHER));
        post("Taco Bell");
        dasherOpened();
        offerRoot = offer("$25.00");
        dasherShows(offerRoot);
        contains(log(app), "[peek] left Dasher up because the offer passes your rules");

        // The user goes back to Maps without taking it, and Dasher posts on its one key again 16 s later.
        inFront(app(MAPS));
        pass(16_000);
        post("Store B");
        assertNull("not brought up again over an offer it left with the user", started());
        contains(log(app),
                "[peek] skipped: the offer the last peek left with you may still be up on this notification");

        // Once the screen sees that offer end (Dasher waiting for offers), the key is free again.
        dasherShows(finding());
        inFront(app(MAPS));
        pass(1_000);
        post("Store C");
        dasherOpened();
    }

    @Test
    public void theSameSkipAgainIsCountedNotRepeated() {
        OfferFilterService service = connect(app(MAPS));
        Shadows.shadowOf(service.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        post("Store A");
        post("Store B");
        post("Store C");
        Shadows.shadowOf(service.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Shadows.shadowOf(service.getSystemService(PowerManager.class)).setIsInteractive(false);
        post("Store D");
        String log = log(app);
        assertEquals("one line", log.indexOf("[peek] skipped: the phone is locked"),
                log.lastIndexOf("[peek] skipped: the phone is locked"));
        contains(log, "[peek] (the same skip for 2 more offers)");
        contains(log, "[peek] skipped: the screen is off");
        assertTrue(log.indexOf("2 more offers") < log.indexOf("skipped: the screen is off"));
    }

    @Test
    public void quietWaitDefersOpeningAndTypingFallsBackToTheCard() {
        connect(app(MAPS));
        post("Taco Bell");
        assertNull(started());
        pass(600);
        touchNow();
        pass(600);
        assertNull("quiet restarted after the touch", started());
        touchNow();
        pass(600);
        touchNow();
        pass(600);
        touchNow();
        pass(600);
        assertNull("no background opening while the user keeps touching", started());
        contains(log(app), "[peek] skipped: you were using the phone");
        assertEquals(1, cards());
    }

    @Test
    public void keyboardBeforeAnOfferSkipsPeek() {
        OfferFilterService service = connect(app(MAPS));
        AccessibilityNodeInfo maps = app(MAPS);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, maps, true, SCREEN),
                window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, null, false, BOTTOM_HALF)));
        notPeeked("the keyboard is up");
    }

    @Test
    public void disablingPeekDuringTheQuietWaitDoesNotOpenDasher() {
        connect(app(MAPS));
        post("Taco Bell");
        FilterStore.setPeek(app, false);
        pass(1_000);
        assertNull(started());
        assertTrue(touchWatches().isEmpty());
        contains(log(app), "[peek] skipped: Peek is off in Settings");
    }

    @Test
    public void passingOfferReturnsToNavigationWithObservedFigures() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        AccessibilityNodeInfo shown = offer("$25.00");
        List<Long> declined = taps(decline);
        dasherShows(shown);
        assertEquals(MAPS_HOME, started().getComponent());
        assertTrue(declined.isEmpty());
        List<Notification> posted = Shadows.shadowOf(app.getSystemService(NotificationManager.class))
                .getAllNotifications();
        assertEquals(1, posted.size());
        contains(posted.get(0).extras.getCharSequence(Notification.EXTRA_TEXT).toString(), "Passes: $25.00");
        contains(log(app), "you are navigating, so its card carries it");
    }

    @Test
    public void unclearOfferReturnsToNavigationWithoutInventingPay() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        dasherShows(offer(null));
        assertEquals(MAPS_HOME, started().getComponent());
        Notification card = Shadows.shadowOf(app.getSystemService(NotificationManager.class))
                .getAllNotifications().get(0);
        contains(card.extras.getCharSequence(Notification.EXTRA_TEXT).toString(), "Unclear: pay not read");
    }

    @Test
    public void startingAndUnknownScreensDoNotReturnAfterFourSeconds() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        AccessibilityNodeInfo starting = node(DASHER, "Starting…", false);
        dasherShows(starting);
        pass(6_000);
        assertNull(started());
        pass(14_100);
        assertNull("deadline leaves Dasher where it is", started());
        contains(log(app), "[peek] ended because 20 s passed");
    }

    @Test
    public void blockedBackgroundLaunchFallsBackAtSixSeconds() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        pass(5_900);
        assertFalse(log(app).contains("did not come up within"));
        pass(200);
        contains(log(app), "did not come up within 6 s");
        assertTrue(touchWatches().isEmpty());
        assertEquals(1, cards());
    }

    @Test
    public void partialNextOfferAfterConfirmationDoesNotSendTheUserAway() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        offerRoot = offer("$7.90");
        declinedOnScreen();
        AccessibilityNodeInfo partial = node(DASHER, null, false);
        Shadows.shadowOf(partial).addChild(node(DASHER, "$18.00", false));
        Shadows.shadowOf(partial).addChild(node(DASHER, "Finding offers", false));
        dasherShows(partial);
        assertNull("offer facts block a return even beside waiting words", started());
    }

    @Test
    public void peekLinesDescribeKindsAndNeverThePrivateFrontPackage() {
        connect(app(OTHER));
        post("Taco Bell");
        dasherOpened();
        dasherShows(offer("$25.00"));
        for (String line : log(app).split("\n")) {
            if (line.contains("[peek]")) assertFalse(line, line.contains(OTHER));
        }
    }

    @Test
    public void addonDeclineReturnsToTheMapWithoutWaitingForAQuestion() {
        connect(app(MAPS));
        ActiveRouteStore.save(app, new OfferSnapshot(500, 3.0, 12, 2));
        post("Taco Bell");
        dasherOpened();
        AccessibilityNodeInfo addon = node(DASHER, null, false);
        decline = button("Decline");
        Shadows.shadowOf(addon).addChild(node(DASHER, "Add to route", false));
        Shadows.shadowOf(addon).addChild(node(DASHER, "+$1.00", false));
        Shadows.shadowOf(addon).addChild(node(DASHER, "+2 mi", false));
        Shadows.shadowOf(addon).addChild(decline);
        Shadows.shadowOf(addon).addChild(button("Accept"));
        List<Long> taps = taps(decline);
        dasherShows(addon);
        assertEquals("explicit add-on increments must reach the normal rules: " + log(app), 1, taps.size());
        assertNull("no return until the delivery screen is observed", started());
        dasherShows(node(DASHER, "Arrived at store", false));
        Intent returned = started();
        assertNotNull("the observed delivery screen completes the add-on decline", returned);
        assertEquals(MAPS_HOME, returned.getComponent());
        assertTrue(touchWatches().isEmpty());
        contains(log(app), "[peek] returned to a navigation app: the offer was declined");
    }

    @Test
    public void quietCardDoesNotYieldTheSilencerAndPeekNeverMutesMedia() {
        connect(app(MAPS));
        AudioManager audio = app.getSystemService(AudioManager.class);
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0);
        Shadows.shadowOf(audio).setActivePlaybackConfigurationsFor(Arrays.asList(
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()), false);
        post("Taco Bell");
        dasherOpened();
        offerRoot = offer("$7.90");
        dasherShows(offerRoot);
        int floor = Build.VERSION.SDK_INT >= 28 ? audio.getStreamMinVolume(AudioManager.STREAM_ALARM) : 0;
        assertEquals("silent pre-peek card must not suppress the decline silencer", floor,
                audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC));
        pass(300);
        dasherShows(question(button("Decline offer")));
        pass(100);
        dasherShows(finding());
        assertEquals(MAPS_HOME, started().getComponent());
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_ALARM));
        assertEquals(8, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertTrue(touchWatches().isEmpty());
    }

    @Test
    public void ownExistingTaskReturnsByMoveToFrontWithoutCreatingAnActivity() {
        ownExistingTaskReturns(new ComponentName(app, MainActivity.class));
    }

    @Test public void ownDayAliasTaskReturnsWithoutStackingAnotherActivity() {
        Appearance.choose(app, Appearance.Mode.DAY);
        ownExistingTaskReturns(new ComponentName(app, LauncherAppearance.DAY));
    }

    @Test public void ownNightAliasTaskReturnsWithoutStackingAnotherActivity() {
        Appearance.choose(app, Appearance.Mode.NIGHT);
        ownExistingTaskReturns(new ComponentName(app, LauncherAppearance.NIGHT));
    }

    private void ownExistingTaskReturns(ComponentName base) {
        connect(app(OURS));
        ActivityManager.AppTask task = ShadowAppTask.newInstance();
        ShadowAppTask taskShadow = Shadow.extract(task);
        ActivityManager.RecentTaskInfo info = new ActivityManager.RecentTaskInfo();
        info.baseActivity = base;
        taskShadow.setTaskInfo(info);
        Shadows.shadowOf(app.getSystemService(ActivityManager.class)).setAppTasks(Collections.singletonList(task));
        post("Taco Bell");
        dasherOpened();
        offerRoot = offer("$7.90");
        declinedOnScreen();
        dasherShows(finding());
        assertTrue(taskShadow.hasMovedToFront());
        assertNull("no extra MainActivity is stacked", started());
    }

    @Test
    public void ownPopupIsNotAFullScreenCandidate() {
        OfferFilterService service = connect(app(OURS));
        AccessibilityNodeInfo own = app(OURS);
        Shadows.shadowOf(service).setWindows(Collections.singletonList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, own, true, new Rect(100, 100, 300, 400))));
        Shadows.shadowOf(service).setRootInActiveWindow(own);
        notPeeked("the app in front does not fill the screen");
    }

    @Test
    public void noPeekWhileTheScreenIsPinned() {
        connect(app(MAPS));
        Shadows.shadowOf(app.getSystemService(ActivityManager.class))
                .setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_PINNED);
        notPeeked("the screen is pinned");
    }

    @Test
    public void guestPermissionScreenIsNeverOpenedOver() {
        installed(new ComponentName("com.android.permissioncontroller", "Permission"), Intent.CATEGORY_LAUNCHER);
        connect(app("com.android.permissioncontroller"));
        notPeeked("a system screen is in front");
    }

    @Test
    public void aRemovedNotificationCannotOpenDasherDuringTheQuietWait() {
        connect(app(MAPS));
        StatusBarNotification notice = offerNotification("Taco Bell");
        listener.get().onNotificationPosted(notice, null);
        idle();
        pass(300);
        listener.get().onNotificationRemoved(notice);
        pass(500);
        assertNull(started());
        assertTrue(touchWatches().isEmpty());
        contains(log(app), "the offer notification was removed or replaced");
    }

    @Test
    public void aReplacedNotificationCancelsTheEarlierArmingRequest() {
        connect(app(MAPS));
        post("Taco Bell");
        pass(300);
        post("Another Store");
        pass(500);
        assertNull("the first incarnation no longer authorises opening", started());
        contains(log(app), "the offer notification was removed or replaced");
    }

    @Test
    public void aNonOfferReplacementCancelsTheEarlierArmingRequest() {
        assertReplacementCancelsPeek(notification("Dash paused", "Resume your dash", System.currentTimeMillis()));
    }

    @Test
    public void aStaleReplacementCancelsTheEarlierArmingRequest() {
        assertReplacementCancelsPeek(notification("New Delivery!", "New Order: Go to Taco Bell",
                System.currentTimeMillis() - OfferAlertState.LIFETIME_MS - 1_000));
    }

    @Test
    public void aFutureReplacementCancelsTheEarlierArmingRequest() {
        assertReplacementCancelsPeek(notification("New Delivery!", "New Order: Go to Taco Bell",
                System.currentTimeMillis() + 60_000));
    }

    @Test
    public void aNowPassingReplacementCancelsPeekAndKeepsItsObservedFigures() {
        assertReplacementCancelsPeek(notification("$25.00", "New Order: Go to Taco Bell",
                System.currentTimeMillis()));
        Notification card = app.getSystemService(NotificationManager.class).getActiveNotifications()[0].getNotification();
        contains(card.extras.getCharSequence(Notification.EXTRA_TEXT).toString(), "$25.00");
    }

    @Test
    public void aNowFailingReplacementCancelsPeekWithoutRestoringItsOldReviewCard() {
        assertReplacementCancelsPeek(notification("$5.00", "New Order: Go to Taco Bell",
                System.currentTimeMillis()));
        assertEquals(0, cards());
    }

    private void assertReplacementCancelsPeek(StatusBarNotification replacement) {
        connect(app(MAPS));
        post("Taco Bell");
        pass(300);
        listener.get().onNotificationPosted(replacement, null);
        idle();
        pass(500);
        assertNull("the replacement no longer authorises the payless request", started());
        assertTrue(touchWatches().isEmpty());
        contains(log(app), "the offer notification was removed or replaced");
    }

    @Test
    public void anUnchangedReviewUpdateKeepsTheExistingQuietWaitValid() {
        connect(app(MAPS));
        post("Taco Bell");
        pass(300);
        post("Taco Bell");
        pass(400);
        Intent opened = started();
        assertNotNull(opened);
        assertEquals(DASHER_HOME, opened.getComponent());
        assertNull(started());
    }

    @Test
    public void deferredCardDoesNotReviveDecisionFromBeforeItemMinimumChanged() {
        FilterStore.save(app, new FilterSettings(true, 0, 0, 10, 0, 0).withPerItem(50));
        connect(app(MAPS));
        listener.get().onNotificationPosted(notification("$25.00", "New Order: 10 items",
                System.currentTimeMillis()), null);
        idle();
        NotificationManager notifications = app.getSystemService(NotificationManager.class);
        android.service.notification.StatusBarNotification card = notifications.getActiveNotifications()[0];
        // Initially REVIEW for unread time; the new item floor is a known failure. The queued rules callback
        // has not run yet, so the old quiet review card must not ring when Peek is cancelled.
        FilterStore.save(app, FilterStore.load(app).withPerItem(300));
        int historyBefore = DecisionLog.recent(app, 200).size();
        OfferNotificationService.peekNotTaken(card.getTag());
        idle();
        assertEquals("obsolete quiet card must not be announced", 0, cards());
        assertEquals("no stale decision appended", historyBefore, DecisionLog.recent(app, 200).size());
    }

    @Test
    public void deferredCardsNeverPostAfterConsentWasRevoked() {
        connect(app(MAPS));
        post("Taco Bell");
        NotificationManager notifications = app.getSystemService(NotificationManager.class);
        android.service.notification.StatusBarNotification card = notifications.getActiveNotifications()[0];
        ConsentedTestApp.forget(app);
        notifications.cancelAll();
        OfferNotificationService.peekNotTaken(card.getTag());
        OfferNotificationService.peekCard(app, new OfferSnapshot(2500, 7.2, 21, 2), OfferRule.Result.KEEP,
                "Passes: $25.00");
        idle();
        assertEquals("neither fallback nor navigation card may post", 0, cards());
        pass(1_000);
        assertNull(started());
        assertEquals(0, cards());
    }

    @Test
    public void aTouchInsideTheLaunchCallIsNotSwallowedByTheOpeningBaseline() {
        OfferFilterService service = connect(app(MAPS));
        java.util.function.Consumer<Intent> realStart = service.peekStarter;
        service.peekStarter = intent -> {
            realStart.accept(intent);
            long at = SystemClock.uptimeMillis();
            assertEquals(1, touchWatches().size());
            touchWatches().get(0).dispatchTouchEvent(MotionEvent.obtain(at, at,
                    MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        };
        post("Taco Bell");
        dasherOpened();
        service.peekStarter = realStart;
        dasherShows(offer("$25.00"));
        assertNull("the user touched during opening, so no automatic return", started());
        contains(log(app), "you touched the screen as it opened");
    }

    @Test
    public void aTouchDuringTheFinalReturnWindowCheckStillCancelsTheReturn() {
        OfferFilterService service = connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        offerRoot = offer("$7.90");
        declinedOnScreen();
        pass(100); // Beyond the confirmation tap's own-action echo window.
        java.util.function.Supplier<List<AccessibilityWindowInfo>> windows = service.windowSource;
        java.util.concurrent.atomic.AtomicBoolean touched = new java.util.concurrent.atomic.AtomicBoolean();
        service.windowSource = () -> {
            // Inject at the platform window-query boundary after scanner cleanup, before the activity request.
            boolean finalCheck = Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("returnPeekOnMain"));
            if (finalCheck && touched.compareAndSet(false, true)) {
                assertEquals("watch remains raised through the final check", 1, touchWatches().size());
                long at = SystemClock.uptimeMillis();
                touchWatches().get(0).dispatchTouchEvent(MotionEvent.obtain(at, at,
                        MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
            }
            return windows.get();
        };
        dasherShows(finding());
        assertTrue(touched.get());
        assertNull("touch wins even after the completed decline was cleaned up", started());
        contains(log(app), "return cancelled");
    }

    @Test
    public void aScreenReadingDuringTheQuietWaitCancelsThePendingPeek() {
        connect(app(MAPS));
        post("Taco Bell");
        android.service.notification.StatusBarNotification card =
                app.getSystemService(NotificationManager.class).getActiveNotifications()[0];
        OfferNotificationService.readOnScreen(card.getTag(), new OfferSnapshot(2500, 7.2, 21, 2));
        pass(800);
        assertNull("the user already saw the offer while Peek waited", started());
        assertTrue(touchWatches().isEmpty());
    }

}
