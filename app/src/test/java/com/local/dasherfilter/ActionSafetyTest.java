package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.ArrayList;
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
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What the app does by itself must never pull the user out of the map they opened from Dasher (the owner: Navigate's
 * map does not stay): an unchanged re-post of an offer the user had in Dasher never starts a peek, and an aging re-post
 * while they are away never ends their takeover; after an update Offer Filter opens again only over its own window or
 * the home screen; Peek's quiet wait never opens Dasher in the middle of a gesture; and the tab never lingers over the
 * app a tap on Dasher opened. Simulated Android only: DoorDash's real re-post timing, the update's reconnect and how
 * long a real gesture lasts are for a handset.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class ActionSafetyTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final String DASHER = "com.doordash.driverapp";
    private static final ComponentName DASHER_HOME = new ComponentName(DASHER, DASHER + ".Home");
    private static final String MAPS = "com.google.android.apps.maps";
    private static final ComponentName MAPS_HOME = new ComponentName(MAPS, MAPS + ".MapsActivity");
    private static final String LAUNCHER = "com.example.launcher";
    private static final ComponentName LAUNCHER_HOME = new ComponentName(LAUNCHER, LAUNCHER + ".Home");

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
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        AreaMap.forgetCache();
        OfferSilencer.forgetCache();
        ActiveRouteStore.clear(app);
        OfferFilterService.forgetScreenState();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        installed(DASHER_HOME, Intent.CATEGORY_LAUNCHER);
        installed(MAPS_HOME, Intent.CATEGORY_LAUNCHER);
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

    private AccessibilityNodeInfo app(String pkg) {
        AccessibilityNodeInfo root = node(pkg, null, false);
        Shadows.shadowOf(root).addChild(node(pkg, "Head north on Main St", false));
        return root;
    }

    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo button = node(DASHER, null, true);
        Shadows.shadowOf(button).addChild(node(DASHER, label, false));
        return button;
    }

    /** An offer as Dasher draws it, its countdown at {@code countdown}. */
    private AccessibilityNodeInfo offer(String pay, String countdown) {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        decline = button("Decline");
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node(DASHER, pay, false));
        Shadows.shadowOf(root).addChild(node(DASHER, "incl. tips", false));
        Shadows.shadowOf(root).addChild(node(DASHER, "2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(button("Accept"));
        Shadows.shadowOf(root).addChild(node(DASHER, countdown, false));
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

    /** {@code root}'s app fills the screen, as Android lists it: no event of Dasher's says so. */
    private void inFront(AccessibilityNodeInfo root) {
        OfferFilterService service = screen.get();
        Shadows.shadowOf(service).setWindows(Collections.singletonList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, root, true, SCREEN)));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    private OfferFilterService connect(AccessibilityNodeInfo front) {
        screen = Robolectric.buildService(OfferFilterService.class).create();
        inFront(front);
        screen.get().onServiceConnected();
        idle();
        return screen.get();
    }

    private void dasherShows(AccessibilityNodeInfo root) {
        inFront(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER);
        event.setEventTime(SystemClock.uptimeMillis());
        screen.get().onAccessibilityEvent(event);
        idle();
    }

    /** The user's tap on Dasher, as Dasher reports it (no node: nothing of the app's own was tapped). */
    private void userTapsDasher(String label) {
        AccessibilityEvent click = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        click.setPackageName(DASHER);
        click.setEventTime(SystemClock.uptimeMillis());
        click.getText().add(label);
        screen.get().onAccessibilityEvent(click);
    }

    /** Dasher's payless offer notification on its one key, posted at {@code postedAt} (wall clock). */
    private StatusBarNotification offerNotification(String store, long postedAt) {
        Notification payload = new Notification.Builder(app, "dasher_offers")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText("New Order: Go to " + store)
                .build();
        return new StatusBarNotification(DASHER, DASHER, 3, "NEW_ORDER", 10001, 0, 0, payload,
                android.os.Process.myUserHandle(), postedAt);
    }

    private void posted(String store, long postedAt) {
        listener.get().onNotificationPosted(offerNotification(store, postedAt), null);
        idle();
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

    private List<View> overlays() {
        ShadowWindowManagerImpl windows = Shadow.extract(app.getSystemService(WindowManager.class));
        return windows.getViews();
    }

    private List<View> touchWatches() {
        List<View> watches = new ArrayList<>();
        for (View view : overlays()) {
            if (!(view instanceof DasherTab) && !(view instanceof DasherGuide) && !(view instanceof BackToMapChip)) {
                watches.add(view);
            }
        }
        return watches;
    }

    private DasherTab tab() {
        for (View view : overlays()) if (view instanceof DasherTab) return (DasherTab) view;
        return null;
    }

    /** Whether the tab is over the screen, in view and taking touches. */
    private boolean tabUp() {
        DasherTab tab = tab();
        if (tab == null || tab.getVisibility() != View.VISIBLE) return false;
        return (((WindowManager.LayoutParams) tab.getLayoutParams()).flags
                & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
    }

    private void touchNow() {
        long at = SystemClock.uptimeMillis();
        assertEquals("the touch watch is up", 1, touchWatches().size());
        touchWatches().get(0).dispatchTouchEvent(TestTouches.finger(at));
        idle();
    }

    private static List<Long> taps(AccessibilityNodeInfo button) {
        List<Long> at = new CopyOnWriteArrayList<>();
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            at.add(SystemClock.uptimeMillis());
            return true;
        });
        return at;
    }

    private static String log() {
        return DiagnosticLog.read(RuntimeEnvironment.getApplication());
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    // ---- A re-post of an offer the user had in Dasher never starts a peek ----

    /**
     * The owner's field symptom: an offer in Dasher, the user taps Navigate and Maps comes up; DoorDash re-posts the
     * offer's notification unchanged as it ages (16 s after its first post). Peek never pulls Dasher back over the map.
     */
    @Test
    public void aRepostOfAnOfferReadInFrontNeverPeeksAfterTheUserLeftForMaps() {
        AccessibilityNodeInfo shown = offer("$25.00", "0:35");
        connect(shown);
        dasherShows(shown);
        // Robolectric's wall clock does not follow the looper: the posts carry their own times.
        posted("Taco Bell", System.currentTimeMillis() - 16_000);
        contains(log(), "same offer the screen read");
        inFront(app(MAPS));
        pass(16_000);
        posted("Taco Bell", System.currentTimeMillis());
        contains(log(), "the same store re-posted: a new offer (unchanged text");
        pass(Peek.QUIET_WAIT_MS + 500);
        assertNull("Dasher is never pulled back over the map: " + log(), started());
        contains(log(), "[peek] skipped: " + OfferNotificationService.HELD_IN_DASHER);
        assertFalse(log().contains("[peek] opening Dasher"));
    }

    /**
     * Dasher in front on a delivery screen as the offer's notification came, the offer never drawn (Dasher's thread
     * starved): the user had it in Dasher all the same. Its re-post, after Directions opened the map, is not peeked at.
     */
    @Test
    public void aRepostOfAnOfferNotifiedWhileDasherWasInFrontNeverPeeks() {
        AccessibilityNodeInfo delivery = node(DASHER, null, false);
        Shadows.shadowOf(delivery).addChild(node(DASHER, "Deliver to Sam", false));
        Shadows.shadowOf(delivery).addChild(button("Directions"));
        connect(delivery);
        dasherShows(delivery);
        posted("Taco Bell", System.currentTimeMillis() - 16_000);
        contains(log(), "[peek] skipped: Dasher is on screen");
        inFront(app(MAPS));
        pass(16_000);
        posted("Taco Bell", System.currentTimeMillis());
        pass(Peek.QUIET_WAIT_MS + 500);
        assertNull(log(), started());
        contains(log(), "[peek] skipped: " + OfferNotificationService.HELD_IN_DASHER);
    }

    /** The screen saw the last offer end (Dasher back to its wait for offers): the next post is a new offer, peeked. */
    @Test
    public void aNewOfferAfterTheScreenSawTheLastOneEndIsPeekedAt() {
        AccessibilityNodeInfo shown = offer("$25.00", "0:35");
        connect(shown);
        dasherShows(shown);
        posted("Taco Bell", System.currentTimeMillis() - 16_000);
        dasherShows(finding());
        inFront(app(MAPS));
        pass(16_000);
        posted("Taco Bell", System.currentTimeMillis());
        contains(log(), "the same store re-posted: a new offer (the screen saw the last offer end)");
        pass(Peek.QUIET_MS);
        Intent opened = started();
        assertNotNull("a new offer while the user is in Maps is peeked at: " + log(), opened);
        assertEquals(DASHER_HOME, opened.getComponent());
    }

    /** The hold lasts a minute from the offer's first post at most (no offer outlives that): later re-posts peek. */
    @Test
    public void aRepostMoreThanAMinuteAfterTheFirstPostIsPeekedAt() {
        AccessibilityNodeInfo shown = offer("$25.00", "0:35");
        connect(shown);
        dasherShows(shown);
        long now = System.currentTimeMillis();
        posted("Taco Bell", now - 70_000);
        inFront(app(MAPS));
        pass(16_000);
        // An aging re-post, too old to peek at anyway.
        posted("Taco Bell", now - 54_000);
        pass(OfferPairing.OFFER_MS - 16_000 + 2_000);
        posted("Taco Bell", System.currentTimeMillis());
        pass(Peek.QUIET_MS);
        Intent opened = started();
        assertNotNull("a minute after the first post, the re-post is an offer of its own: " + log(), opened);
        assertEquals(DASHER_HOME, opened.getComponent());
    }

    // ---- An aging re-post while the user is away never ends their takeover ----

    private List<Long> takenOverThenAwayInMaps(boolean repost) {
        AccessibilityNodeInfo shown = offer("$7.90", "0:35");
        List<Long> first = taps(decline);
        connect(shown);
        dasherShows(shown);
        assertEquals("first-step Decline at once", 1, first.size());
        posted("Taco Bell", System.currentTimeMillis() - 16_000);
        // The user touches the offer: it is theirs now.
        pass(300);
        touchNow();
        contains(log(), "touch during decline: the user's");
        inFront(app(MAPS));
        pass(15_000);
        if (repost) {
            posted("Taco Bell", System.currentTimeMillis());
            contains(log(), "the same store re-posted: a new offer (unchanged text");
        }
        pass(Peek.QUIET_WAIT_MS);
        assertNull("no peek over the map", started());
        return first;
    }

    /**
     * The user took over an offer and looks at Maps; DoorDash re-posts the offer's notification as it ages. Back in
     * Dasher, the same offer with its countdown going on is still theirs: never declined again.
     */
    @Test
    public void anAgingRepostWhileAwayKeepsTheTakeoverAndTheOfferIsNotDeclinedAgain() {
        takenOverThenAwayInMaps(true);
        AccessibilityNodeInfo again = offer("$7.90", "0:19");
        List<Long> second = taps(decline);
        dasherShows(again);
        assertTrue("the offer the user took over is never declined again: " + log(), second.isEmpty());
        contains(log(), "[takeover] kept: a notification came while Dasher was away");
        assertFalse(log().contains("[takeover] ended: fresh notification"));
    }

    /** A re-offer, its countdown started afresh, is a new instance: declined as any (the approved re-offer rule). */
    @Test
    public void aReOfferWithAFreshCountdownAfterANotificationIsDeclined() {
        takenOverThenAwayInMaps(true);
        AccessibilityNodeInfo again = offer("$7.90", "0:45");
        List<Long> second = taps(decline);
        dasherShows(again);
        assertEquals("a new instance of the offer is declined", 1, second.size());
        contains(log(), "[takeover] ended: new instance (countdown)");
    }

    // ---- After an update, Offer Filter opens again only over its own window or the home screen ----

    private void reopenArmed() {
        Updater.prefs(app).edit().putLong("relaunch_at", System.currentTimeMillis())
                .putLong("relaunch_from_code", 1).commit();
        assertTrue(Updater.relaunchPending(app));
    }

    private void screenReadingSwitchedOn() {
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new ComponentName(app, OfferFilterService.class).flattenToString());
    }

    private boolean reopened() {
        Intent opened = started();
        while (opened != null && !MainActivity.class.getName().equals(opened.getComponent().getClassName())) {
            opened = started();
        }
        return opened != null;
    }

    @Test
    public void withScreenReadingOnTheUpdateWaitsForItAndNeverOpensOverAMap() {
        screenReadingSwitchedOn();
        reopenArmed();
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        idle();
        assertFalse("nothing says which app is in front yet", reopened());
        contains(log(), "[update] reopen after update: waiting for screen reading");
        // Screen reading reconnects after the update with Maps in front.
        connect(app(MAPS));
        assertFalse("never over the map", reopened());
        contains(log(), "[update] reopen after update: dropped (not over a navigation app)");
        assertFalse("a later reconnect opens nothing", Updater.relaunchPending(app));
    }

    @Test
    public void afterAnUpdateOfferFilterOpensAgainOverTheHomeScreen() {
        screenReadingSwitchedOn();
        reopenArmed();
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        idle();
        connect(app(LAUNCHER));
        assertTrue("back to Offer Filter over the home screen: " + log(), reopened());
    }

    @Test
    public void afterAnUpdateOfferFilterNeverOpensOverDasher() {
        connect(finding());
        reopenArmed();
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        idle();
        assertFalse(reopened());
        contains(log(), "[update] reopen after update: dropped (Dasher is on screen)");
        assertFalse(Updater.relaunchPending(app));
    }

    /** With screen reading off nothing can say which app is in front, and nothing of Dasher's is read: as before. */
    @Test
    public void withScreenReadingOffTheUpdateOpensOfferFilterAsBefore() {
        reopenArmed();
        new UpdateReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        idle();
        assertTrue(reopened());
    }

    // ---- Peek's quiet wait never opens Dasher in the middle of a gesture ----

    /**
     * The watch hears a gesture's first finger only (a pan of the map, a pinch, a long press go on unheard): Dasher
     * opens only once the gesture could have ended ({@link Peek#GESTURE_MS}) and the quiet after it passed.
     */
    @Test
    public void aTouchWhileArmingHoldsTheOpeningUntilTheGestureCouldHaveEnded() {
        connect(app(MAPS));
        posted("Taco Bell", System.currentTimeMillis());
        touchNow();
        pass(Peek.QUIET_MS + 200);
        assertNull("never 700 ms after a finger landed: it may still be down", started());
        pass(Peek.GESTURE_MS - 500);
        assertNull(started());
        pass(500);
        Intent opened = started();
        assertNotNull("quiet after the gesture: " + log(), opened);
        assertEquals(DASHER_HOME, opened.getComponent());
    }

    /** A touch half a second in: its gesture and the quiet after it would end past the 3 s wait, so no peek. */
    @Test
    public void aTouchLateInTheQuietWaitIsNoPeek() {
        connect(app(MAPS));
        posted("Taco Bell", System.currentTimeMillis());
        pass(500);
        touchNow();
        pass(Peek.QUIET_WAIT_MS);
        assertNull(started());
        contains(log(), "[peek] skipped: you were using the phone");
    }

    // ---- The tab never lingers over the app a tap on Dasher opened ----

    @Test
    public void aTapOnDasherPutsTheTabAwayAtOnceAndItNeverShowsOverTheMapItOpened() {
        connect(finding());
        dasherShows(finding());
        assertTrue("the tab over Dasher's wait for offers", tabUp());
        // The user taps Navigate: Maps comes up, of which Android tells the screen reader nothing.
        userTapsDasher("Navigate");
        assertFalse("put away at the tap itself", tabUp());
        idle();
        pass(200);
        inFront(app(MAPS));
        assertFalse(tabUp());
        pass(OfferFilterService.TAP_SETTLE_MS + OfferFilterService.WINDOW_WATCH_MS * 2);
        assertFalse("never over the map: " + log(), tabUp());
    }

    @Test
    public void aTapOnDasherThatStaysInDasherBringsTheTabBackOnceItSettled() {
        connect(finding());
        dasherShows(finding());
        assertTrue(tabUp());
        userTapsDasher("Dash preferences");
        idle();
        assertFalse(tabUp());
        pass(OfferFilterService.TAP_SETTLE_MS + 100);
        assertTrue("Dasher still in front: the tab is back", tabUp());
    }

    /**
     * Paused (auto-decline off), nothing of Dasher's is read, not even a click's node: the user's tap on Dasher is told
     * by its own time, and still puts the tab away at once, so none lingers over the map it opened.
     */
    @Test
    public void pausedATapOnDasherStillPutsTheTabAwayAndReadsNothingOfDasher() {
        OfferFilterService service = connect(finding());
        dasherShows(finding());
        assertTrue(tabUp());
        FilterStore.save(app, FilterStore.load(app).withEnabled(false));
        OfferFilterService.requestCheckForRules();
        idle();
        assertTrue("paused with nothing changed since the last read: the tab still takes the tap that resumes",
                tabUp());
        int reads = service.contentReads;
        userTapsDasher("Navigate");
        assertFalse("put away at the tap itself", tabShown());
        idle();
        pass(200);
        inFront(app(MAPS));
        pass(OfferFilterService.TAP_SETTLE_MS + OfferFilterService.WINDOW_WATCH_MS * 2);
        assertFalse("never over the map: " + log(), tabShown());
        assertEquals("nothing of Dasher's read while paused", reads, service.contentReads);
    }

    /**
     * Paused, a tap that stays in Dasher brings the tab back only once it settled, by Android's list of windows alone,
     * and only as the slim peek that takes no touches (Dasher changed its screen unread: an offer may be up).
     */
    @Test
    public void pausedATapThatStaysInDasherBringsBackOnlyTheUntouchablePeekOnceItSettled() {
        OfferFilterService service = connect(finding());
        dasherShows(finding());
        FilterStore.save(app, FilterStore.load(app).withEnabled(false));
        OfferFilterService.requestCheckForRules();
        idle();
        int reads = service.contentReads;
        userTapsDasher("Dash preferences");
        idle();
        assertFalse("put away at the tap", tabShown());
        // Dasher's window changes as its page opens: the look at Android's list it brings waits for the tap to settle.
        pass(100);
        dasherShows(finding());
        assertFalse("not at Dasher's window change before the tap settled", tabShown());
        pass(OfferFilterService.TAP_SETTLE_MS - 200);
        assertFalse("not before the tap settled", tabShown());
        pass(OfferFilterService.WINDOW_WATCH_MS);
        assertTrue("Dasher still in front: back", tabShown());
        assertEquals("the slim peek", DasherTab.Look.PEEK, tab().look());
        assertFalse("which takes no touches", tabUp());
        assertEquals("nothing of Dasher's read while paused", reads, service.contentReads);
    }

    /**
     * The read that brings the tab back after the user's tap is a timer's (never starve Dasher: the tap may be Navigate,
     * Dasher busy handing over to the map): on the read budget, and Dasher may break off the prefetch of its nodes.
     */
    @Test
    public void theReadAfterTheUsersTapLetsDasherBreakOffItsPrefetch() {
        if (Build.VERSION.SDK_INT < 33) return; // Prefetch strategies are Android 13's.
        OfferFilterService service = connect(finding());
        dasherShows(finding());
        userTapsDasher("Dash preferences");
        idle();
        int uninterruptible = service.uninterruptibleRootFetches;
        int reads = service.contentReads;
        pass(OfferFilterService.TAP_SETTLE_MS + 100);
        assertTrue("Dasher still in front: the tab is back", tabUp());
        assertTrue("a read brought it back", service.contentReads > reads);
        assertEquals("never with uninterruptible prefetch", uninterruptible, service.uninterruptibleRootFetches);
    }

    private boolean tabShown() {
        DasherTab tab = tab();
        return tab != null && tab.getVisibility() == View.VISIBLE;
    }

    @Test
    public void aTapOnTheTabWithAnotherAppInFrontPausesNothing() {
        connect(finding());
        dasherShows(finding());
        DasherTab tab = tab();
        assertTrue(tabUp());
        // Another app came in front; the window watch has not looked yet.
        inFront(app(MAPS));
        tab.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10, 1000, 0));
        tab.dispatchTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, 10, 1000, 0));
        idle();
        assertTrue("auto-decline is not paused from over another app", FilterStore.load(app).enabled);
        assertFalse(tabUp());
    }
}
