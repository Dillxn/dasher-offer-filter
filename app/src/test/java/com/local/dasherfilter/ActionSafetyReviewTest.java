package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
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
import java.util.Collections;
import java.util.List;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowNotificationListenerService;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSystem;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The action-safety review of the window flows (7 October 2026), its scenarios kept as tests: what the app does by
 * itself around Dasher's Navigate, an offer the user accepted, the user's End dash and a peek's decline, driven through
 * the real screen reader and notification listener. An offer the user may have accepted (a delivery or pickup screen
 * after it) is never pulled back over the map by Peek, nor declined through Dasher's notification; ending the dash in
 * Dasher lets the screen time out again at once; a peek goes back after its decline over the pickup screen's own
 * amount; with no rule set a notification is never a pass; and a decline whose question never comes reads Dasher's
 * own map at most once a second. Simulated Android only: whether DoorDash re-posts an accepted offer, and Dasher's real
 * end-of-dash wording, are for a handset.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, instrumentedPackages = {"com.local.dasherfilter"})
@LooperMode(LooperMode.Mode.PAUSED)
public class ActionSafetyReviewTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final String DASHER = "com.doordash.driverapp";
    private static final ComponentName DASHER_HOME = new ComponentName(DASHER, DASHER + ".Home");
    private static final ComponentName DASHER_OFFER = new ComponentName(DASHER, DASHER + ".OfferNotificationActivity");
    private static final String MAPS = "com.google.android.apps.maps";
    private static final ComponentName MAPS_HOME = new ComponentName(MAPS, MAPS + ".MapsActivity");
    private static final String LAUNCHER = "com.example.launcher";
    private static final ComponentName LAUNCHER_HOME = new ComponentName(LAUNCHER, LAUNCHER + ".Home");
    private static final int DASHER_WIN = 41;
    private static final int LAUNCHER_WIN = 70;
    private static final String TACO_BELL = "New Order: Go to Taco Bell";

    private Application app;
    private ServiceController<OfferFilterService> screen;
    private ServiceController<OfferNotificationService> listener;
    private int navWindow = 88;
    /** What the app started (Peek's starts, its returns): "package/.Class". */
    private final List<String> starts = new ArrayList<>();
    /** Dasher's own notification taps the app sent. */
    private final List<String> ownTaps = new ArrayList<>();

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
        Dashing.forgetCache();
        OfferNotificationService.forgetDeclineAction();
        OfferFilterService.forgetScreenState();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        installed(DASHER_HOME, Intent.CATEGORY_LAUNCHER);
        installed(DASHER_OFFER, Intent.CATEGORY_DEFAULT);
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

    // ---- Dasher's screens ----

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

    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo button = node(DASHER, null, true);
        Shadows.shadowOf(button).addChild(node(DASHER, label, false));
        return button;
    }

    private AccessibilityNodeInfo dasherScreen(Object... parts) {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        for (Object part : parts) {
            Shadows.shadowOf(root).addChild(part instanceof AccessibilityNodeInfo ? (AccessibilityNodeInfo) part
                    : node(DASHER, String.valueOf(part), false));
        }
        return root;
    }

    /** Google's map view in Dasher: its own description, and markers. */
    private AccessibilityNodeInfo map(int markers) {
        AccessibilityNodeInfo map = node(DASHER, null, false);
        map.setClassName("com.google.android.gms.maps.MapView");
        map.setContentDescription("Google Map");
        for (int i = 0; i < markers; i++) Shadows.shadowOf(map).addChild(node(DASHER, null, false));
        return map;
    }

    private AccessibilityNodeInfo app(String pkg) {
        AccessibilityNodeInfo root = node(pkg, null, false);
        Shadows.shadowOf(root).addChild(node(pkg, "Head north on Main St", false));
        Shadows.shadowOf(root).addChild(node(pkg, "0.3 mi", false));
        return root;
    }

    private AccessibilityNodeInfo decline;
    private AccessibilityNodeInfo accept;
    private AccessibilityNodeInfo navigate;

    private AccessibilityNodeInfo waiting() {
        return dasherScreen("Finding offers", "Zone offer wait", map(30));
    }

    private AccessibilityNodeInfo inDash() {
        return dasherScreen("This dash", "$0.00", "Dash preferences", "Safety tools", button("End dash"), map(30));
    }

    /** An offer as Dasher draws it; {@code pay} null for one whose pay Dasher's screen does not show. */
    private AccessibilityNodeInfo offer(String pay, String countdown) {
        decline = button("Decline");
        accept = button("Accept");
        return pay == null
                ? dasherScreen(decline, "incl. tips", "2 stops (7.2 mi) • 21 min", accept, countdown)
                : dasherScreen(decline, pay, "incl. tips", "2 stops (7.2 mi) • 21 min", accept, countdown);
    }

    private AccessibilityNodeInfo question(AccessibilityNodeInfo declineOffer) {
        return dasherScreen("Are you sure you want to decline this offer?", "Does not lower acceptance rate",
                declineOffer, button("View offer details"));
    }

    /** Dasher's pickup screen, with its own "$9.30 / this offer". */
    private AccessibilityNodeInfo pickup() {
        navigate = button("Navigate");
        return dasherScreen("Pick up by 7:30 PM", "Taco Bell", "$9.30", "this offer", navigate,
                button("Arrived at store"), map(80));
    }

    private AccessibilityNodeInfo dropoff() {
        navigate = button("Navigate");
        return dasherScreen("Deliver to Sam", "Arriving at 2:39 AM", navigate, "Complete delivery steps", map(80));
    }

    /** Dasher's own turn-by-turn map. */
    private AccessibilityNodeInfo inAppMap() {
        return dasherScreen(map(200), "Turn left onto Main St", "0.3 mi", "25 mph", "12 min", "3.4 mi",
                button("Recenter"), button("Exit"));
    }

    // ---- Windows and events ----

    private static AccessibilityWindowInfo window(int id, AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(id);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(true);
        shadow.setBoundsInScreen(SCREEN);
        shadow.setLayer(1);
        if (root != null) Shadows.shadowOf(root).setAccessibilityWindowInfo(window);
        return window;
    }

    /** {@code root}'s app fills the screen in window {@code id}, as Android lists it. No event is sent. */
    private void list(int id, AccessibilityNodeInfo root) {
        OfferFilterService service = screen.get();
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window(id, root)));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    private AccessibilityEvent event(int type, String pkg, String text) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        if (pkg != null) event.setPackageName(pkg);
        event.setEventTime(SystemClock.uptimeMillis());
        if (text != null) event.getText().add(text);
        ((ShadowAccessibilityRecord) Shadow.extract(event)).setWindowId(DASHER_WIN);
        return event;
    }

    private void send(AccessibilityEvent event) {
        screen.get().onAccessibilityEvent(event);
    }

    /** Dasher shows {@code root} full screen (its window change, and Android's list changing). */
    private void dasherShows(AccessibilityNodeInfo root) {
        list(DASHER_WIN, root);
        send(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, DASHER, null));
        idle();
    }

    private void dasherChanged() {
        send(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, DASHER, null));
    }

    /** Another app comes in front: Android tells the screen reader only that its list of windows changed. */
    private void otherAppInFront(int id, AccessibilityNodeInfo root) {
        list(id, root);
        send(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle();
    }

    private void mapsInFront() {
        otherAppInFront(navWindow++, app(MAPS));
    }

    private static String shortName(Intent intent) {
        ComponentName component = intent == null ? null : intent.getComponent();
        return component == null ? "?" : component.getPackageName() + "/" + component.getShortClassName();
    }

    private OfferFilterService connect(int id, AccessibilityNodeInfo root) {
        screen = Robolectric.buildService(OfferFilterService.class).create();
        list(id, root);
        OfferFilterService service = screen.get();
        service.peekStarter = intent -> {
            starts.add(shortName(intent));
            if (autoReact && intent.getComponent() != null) react(intent.getComponent().getPackageName());
        };
        service.ownTapSender = (intent, options) -> {
            ownTaps.add(shortName(Shadows.shadowOf(intent).getSavedIntent()));
            if (autoReact) react(DASHER);
        };
        service.onServiceConnected();
        idle();
        return service;
    }

    /** The user's finger lands: the touch watch hears it when it is up. */
    private void touch() {
        long at = SystemClock.uptimeMillis();
        for (View watch : touchWatches()) {
            watch.dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        }
        idle();
    }

    private List<View> touchWatches() {
        ShadowWindowManagerImpl windows = Shadow.extract(app.getSystemService(WindowManager.class));
        List<View> watches = new ArrayList<>();
        for (View view : windows.getViews()) {
            if (!(view instanceof DasherTab) && !(view instanceof DasherGuide) && !(view instanceof BackToMapChip)) {
                watches.add(view);
            }
        }
        return watches;
    }

    /** The user taps {@code target} on Dasher: the touch, then Dasher's click event naming it ("" for no words). */
    private void userTaps(AccessibilityNodeInfo target, String label) {
        touch();
        AccessibilityEvent click = event(AccessibilityEvent.TYPE_VIEW_CLICKED, DASHER, label.isEmpty() ? null : label);
        if (target != null) ((ShadowAccessibilityRecord) Shadow.extract(click)).setSourceNode(target);
        send(click);
        idle();
    }

    // ---- What the phone does by itself: an app started comes up 300 ms later ----

    private boolean autoReact;
    /** The screen Dasher shows when it is brought up (its task as it was). */
    private Supplier<AccessibilityNodeInfo> dasherNow;
    private final List<Object[]> reactions = new ArrayList<>();

    private void react(String pkg) {
        reactions.add(new Object[] {SystemClock.uptimeMillis() + 300, pkg});
    }

    private void runReactions() {
        for (int i = 0; i < reactions.size(); i++) {
            Object[] due = reactions.get(i);
            if ((Long) due[0] > SystemClock.uptimeMillis()) continue;
            reactions.remove(i--);
            String pkg = (String) due[1];
            if (DASHER.equals(pkg) && dasherNow != null) dasherShows(dasherNow.get());
            else if (MAPS.equals(pkg)) mapsInFront();
        }
    }

    // ---- Notifications ----

    private int key = 10001;
    private String subText;

    /**
     * The wall clock the app sees: with the app's package instrumented ({@link Config#instrumentedPackages}), its
     * {@code System.currentTimeMillis()} follows the simulated clock, as the screen-read pairing and post ages need
     * (this test's own calls do not, hence {@link ShadowSystem}).
     */
    private static long wall() {
        return ShadowSystem.currentTimeMillis();
    }

    private StatusBarNotification notification(String text, long postedAt, boolean withDecline) {
        Notification.Builder builder = new Notification.Builder(app, "dasher_offers")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText(text);
        if (subText != null) builder.setStyle(new Notification.BigTextStyle().bigText(subText));
        if (withDecline) {
            PendingIntent declineAction = PendingIntent.getBroadcast(app, 9, new Intent("dasher.DECLINE_OFFER"),
                    PendingIntent.FLAG_IMMUTABLE);
            Shadows.shadowOf(declineAction).setCreatorPackage(DASHER);
            builder.addAction(new Notification.Action.Builder(null, "Decline", declineAction).build());
        }
        Notification payload = builder.build();
        PendingIntent own = PendingIntent.getActivity(app, 7, new Intent().setComponent(DASHER_OFFER),
                PendingIntent.FLAG_IMMUTABLE);
        Shadows.shadowOf(own).setCreatorPackage(DASHER);
        payload.contentIntent = own;
        return new StatusBarNotification(DASHER, DASHER, 3, "NEW_ORDER", key, 0, 0, payload,
                android.os.Process.myUserHandle(), postedAt);
    }

    private ShadowNotificationListenerService nativeAlerts() {
        return Shadow.extract(listener.get());
    }

    /** Dasher posts (or re-posts, or updates) its offer notification now; Android lists it, alerting by itself. */
    private StatusBarNotification dasherPosts(String text, boolean withDecline) {
        return dasherPosts(text, withDecline, 0);
    }

    /** As {@link #dasherPosts(String, boolean)}, the post dated {@code agoMs} back on the app's clock. */
    private StatusBarNotification dasherPosts(String text, boolean withDecline, long agoMs) {
        StatusBarNotification source = notification(text, wall() - agoMs, withDecline);
        while (listed(source)) listener.get().cancelNotification(source.getKey());
        nativeAlerts().addActiveNotification(source);
        listener.get().onNotificationPosted(source, SameOfferAdapterTest.dashersOwnChannel(source));
        idle();
        return source;
    }

    private boolean listed(StatusBarNotification source) {
        return listener.get().getActiveNotifications(new String[] {source.getKey()}).length > 0;
    }

    // ---- The lock ----

    private void lockScreen() {
        OfferFilterService service = screen.get();
        Shadows.shadowOf(service.getSystemService(PowerManager.class)).setIsInteractive(false);
        Shadows.shadowOf(service.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        idle();
    }

    private void unlock() {
        OfferFilterService service = screen.get();
        Shadows.shadowOf(service.getSystemService(PowerManager.class)).setIsInteractive(true);
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        idle();
        Shadows.shadowOf(service.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        app.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        idle();
    }

    // ---- Time ----

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Lets {@code ms} pass in 50 ms slices; an app started comes up as the phone would bring it. */
    private void pass(long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            long slice = Math.min(50, end - SystemClock.uptimeMillis());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Math.max(1, slice)));
            runReactions();
        }
    }

    private void passUntil(long uptime) {
        long left = uptime - SystemClock.uptimeMillis();
        if (left > 0) pass(left);
    }

    // ---- What the app did ----

    private static String log() {
        return DiagnosticLog.read(RuntimeEnvironment.getApplication());
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    private boolean startedDasher() {
        for (String started : starts) if (started.startsWith(DASHER)) return true;
        return false;
    }

    private boolean startedMaps() {
        for (String started : starts) if (started.startsWith(MAPS)) return true;
        return false;
    }

    private static boolean leaseHeld() {
        PowerManager.WakeLock lease = ShadowPowerManager.getLatestWakeLock();
        return lease != null && lease.isHeld();
    }

    /** Offer Filter's cards showing now, as "channel:text". */
    private List<String> cards() {
        List<String> shown = new ArrayList<>();
        for (StatusBarNotification card : app.getSystemService(NotificationManager.class).getActiveNotifications()) {
            Notification payload = card.getNotification();
            shown.add(payload.getChannelId() + ":" + payload.extras.getCharSequence(Notification.EXTRA_TEXT));
        }
        return shown;
    }

    // ---- Shared flows ----

    /** On a delivery: Dasher's drop-off screen in front and read, a route stored. */
    private void onDeliveryDropoff() {
        ActiveRouteStore.save(app, new OfferSnapshot(900, 3.0, 12, 2));
        connect(DASHER_WIN, dropoff());
        dasherShows(dropoff());
        Dashing.seen(app);
        pass(1_000);
    }

    /** A dash under way: Dasher's wait for offers in front, read. */
    private void dashOnWaiting() {
        connect(DASHER_WIN, waiting());
        dasherShows(waiting());
        Dashing.seen(app);
        pass(1_000);
    }

    /**
     * The user taps Navigate on Dasher and Google Maps comes up; DoorDash re-posts the offer's notification unchanged
     * 16 s after its first post, then updates it 6 s later (a line added, the store unchanged).
     */
    private void navigateThenRepostsAndUpdates(long firstPostUptime) {
        userTaps(navigate, "Navigate");
        pass(400);
        mapsInFront();
        pass(1_000);
        passUntil(firstPostUptime + 16_000);
        dasherPosts(TACO_BELL, false);
        pass(6_000);
        subText = "1 item";
        dasherPosts(TACO_BELL, false);
        subText = null;
        pass(25_000);
    }

    /**
     * On a delivery, the next offer drawn over Dasher's drop-off screen passes; the user accepts it with a tap whose
     * click names nothing (no node, no words: Dasher reports no finger taps), and Dasher shows its pickup screen.
     *
     * @return when the offer's notification was first posted (uptime)
     */
    private long acceptedByAnUnreportedTapOnDelivery() {
        onDeliveryDropoff();
        autoReact = true;
        dasherNow = this::pickup;
        dasherShows(offer("$25.00", "0:35"));
        long firstPost = SystemClock.uptimeMillis();
        dasherPosts(TACO_BELL, false);
        pass(3_000);
        userTaps(null, "");
        pass(300);
        dasherShows(pickup());
        pass(2_000);
        return firstPost;
    }

    // ---- (1) A re-post of an offer the user may have accepted never pulls Dasher back over the map ----

    /**
     * The offer comes while Google Maps is in front; the user pulls the shade and accepts it from DoorDash's own
     * notification (no peek: the user touched), Dasher comes up on its pickup screen, the user taps Navigate and Maps
     * comes up. DoorDash's re-post of the offer never opens Dasher over the map: a pickup screen followed the offer.
     */
    @Test
    public void aRepostOfAnOfferAcceptedFromItsNotificationNeverOpensDasherOverTheMap() {
        connect(navWindow++, app(MAPS));
        Dashing.seen(app);
        autoReact = true;
        dasherNow = this::pickup;
        pass(1_000);
        long firstPost = SystemClock.uptimeMillis();
        dasherPosts(TACO_BELL, false);
        pass(200);
        // The shade pulled down, the notification's Accept tapped.
        touch();
        pass(1_500);
        dasherShows(pickup());
        pass(2_000);
        navigateThenRepostsAndUpdates(firstPost);
        assertFalse("Dasher is never pulled back over the map: " + starts + "\n" + log(), startedDasher());
        contains(log(), "the same store re-posted: a new offer (unchanged text");
        contains(log(), "[peek] skipped: " + OfferNotificationService.HELD_IN_DASHER);
    }

    /**
     * During a delivery, the user accepts the next offer with a tap Dasher reports with no node and no words; its
     * pickup screen shows, then Navigate and Maps. The screen saw the offer leave, but a pickup screen followed it:
     * its re-post is no new offer to peek at.
     */
    @Test
    public void aRepostOfAnOfferAcceptedByAnUnreportedTapNeverOpensDasherOverTheMap() {
        long firstPost = acceptedByAnUnreportedTapOnDelivery();
        navigateThenRepostsAndUpdates(firstPost);
        assertFalse("Dasher is never pulled back over the map: " + starts + "\n" + log(), startedDasher());
        contains(log(), "the same store re-posted: a new offer (the screen saw the last offer end)");
        contains(log(), "[peek] skipped: " + OfferNotificationService.HELD_IN_DASHER);
    }

    /** As above, the re-post arriving while the phone is locked: it is not kept for the unlock either. */
    @Test
    public void aRepostOfAnAcceptedOfferWhileLockedIsNotLookedAtAgainAfterTheUnlock() {
        long firstPost = acceptedByAnUnreportedTapOnDelivery();
        userTaps(navigate, "Navigate");
        pass(400);
        mapsInFront();
        pass(1_000);
        lockScreen();
        passUntil(firstPost + 16_000);
        dasherPosts(TACO_BELL, false);
        pass(5_000);
        unlock();
        pass(25_000);
        assertFalse("no peek after the unlock: " + starts + "\n" + log(), startedDasher());
        assertFalse(log().contains("unlocked in time: checking it"));
        contains(log(), "[peek] skipped: " + OfferNotificationService.HELD_IN_DASHER);
    }

    /**
     * Only the wait for offers (or the dash's end) says the offer was not the user's: a post on the same key after
     * Dasher showed it is a new offer, and is peeked at.
     */
    @Test
    public void afterThePickupScreenTheWaitForOffersLetsTheNextPostBePeekedAt() {
        connect(navWindow++, app(MAPS));
        Dashing.seen(app);
        pass(1_000);
        long firstPost = SystemClock.uptimeMillis();
        dasherPosts(TACO_BELL, false);
        pass(200);
        touch();
        pass(1_500);
        dasherShows(pickup());
        pass(2_000);
        // The delivery was not to be (the user unassigned it, say): Dasher waits for offers again.
        dasherShows(waiting());
        pass(1_000);
        mapsInFront();
        passUntil(firstPost + 16_000);
        dasherPosts(TACO_BELL, false);
        pass(Peek.QUIET_MS + 200);
        assertTrue("a new offer while the user is in Maps is peeked at: " + log(), startedDasher());
    }

    /**
     * AGENTS (Peek): an acceptance seen in the last 60 s does not start a peek. The user accepts an offer in Dasher and
     * Navigate brings up Maps; another store's offer posted 20 s later never pulls Dasher back over the map (the
     * owner's field report: Navigate's map does not stay).
     */
    @Test
    public void noPeekWithinAMinuteOfTheUsersAccept() {
        Dashing.seen(app);
        AccessibilityNodeInfo shown = offer("$25.00", "0:35");
        connect(DASHER_WIN, shown);
        dasherShows(shown);
        pass(500);
        userTaps(accept, "Accept");
        pass(1_000);
        mapsInFront();
        pass(20_000);
        dasherPosts("New Order: Go to Chipotle", false);
        pass(Peek.QUIET_WAIT_MS + 500);
        assertFalse("no peek within a minute of the user's Accept: " + starts + "\n" + log(), startedDasher());
        contains(log(), "[peek] skipped: you accepted an offer");
    }

    /**
     * AGENTS (Peek): an active takeover does not start a peek. The user touches the screen during a decline (the offer
     * is theirs), then goes to Maps; another store's offer posted 20 s later is not peeked at while the takeover holds.
     */
    @Test
    public void noPeekWhileTheUsersTakeoverHolds() {
        Dashing.seen(app);
        AccessibilityNodeInfo shown = offer("$7.90", "0:35");
        connect(DASHER_WIN, shown);
        dasherShows(shown);
        pass(400);
        touch();
        contains(log(), "touch during decline: the user's");
        pass(1_000);
        mapsInFront();
        pass(20_000);
        dasherPosts("New Order: Go to Chipotle", false);
        pass(Peek.QUIET_WAIT_MS + 500);
        assertFalse("no peek while the user's takeover holds: " + starts + "\n" + log(), startedDasher());
        contains(log(), "[peek] skipped: you took over an offer");
    }

    private int dasherStarts() {
        int count = 0;
        for (String started : starts) if (started.startsWith(DASHER)) count++;
        return count;
    }

    /**
     * A route screen read while a peek holds Dasher open is Dasher's screen before it draws the offer, never a sign the
     * user accepted it: Peek opens Dasher from Maps during a delivery, Dasher shows its drop-off screen and never draws
     * the offer (its own notification tap too), the peek goes back to the map. DoorDash's re-post of that offer is
     * peeked at again, as before.
     */
    @Test
    public void aRouteScreenWhileAPeekHoldsDasherOpenHoldsNothing() {
        ActiveRouteStore.save(app, new OfferSnapshot(900, 3.0, 12, 2));
        connect(navWindow++, app(MAPS));
        Dashing.seen(app);
        autoReact = true;
        dasherNow = this::dropoff;
        pass(1_000);
        long firstPost = SystemClock.uptimeMillis();
        dasherPosts(TACO_BELL, false);
        pass(Peek.QUIET_MS + 400);
        assertEquals("Peek opened Dasher: " + log(), 1, dasherStarts());
        pass(Peek.PRESENT_MS + Peek.OWN_TAP_WAIT_MS + 2_000);
        contains(log(), "[peek] offer never showed");
        assertTrue("back to the map: " + starts, startedMaps());
        passUntil(firstPost + 16_000);
        dasherPosts(TACO_BELL, false);
        pass(Peek.QUIET_MS + 400);
        assertEquals("the re-post of an offer Dasher never drew is peeked at again: " + log(), 2, dasherStarts());
    }

    // ---- (4) An update that changes the words after "Go to" keeps the hold ----

    /**
     * During a delivery the offer is notified while Dasher is in front (so the user has it in Dasher); the user taps
     * Navigate and Maps comes up; DoorDash updates the notification to "Go to Taco Bell · 1 item". Dasher is never
     * opened over the map, nor sent its own tap, and no "didn't show" card rings.
     */
    @Test
    public void anUpdateThatAddsToTheStoreKeepsTheHoldOverTheMap() {
        onDeliveryDropoff();
        autoReact = true;
        dasherNow = this::dropoff;
        long firstPost = SystemClock.uptimeMillis();
        dasherPosts(TACO_BELL, false);
        pass(2_000);
        userTaps(navigate, "Navigate");
        pass(400);
        mapsInFront();
        passUntil(firstPost + 6_000);
        dasherPosts(TACO_BELL + " · 1 item", false);
        pass(25_000);
        assertFalse("never over the map: " + starts + "\n" + log(), startedDasher());
        assertTrue("never Dasher's own tap: " + ownTaps, ownTaps.isEmpty());
        for (String card : cards()) assertFalse(card, card.contains(OfferNotificationService.UNSHOWN_TEXT));
        contains(log(), "[peek] skipped: " + OfferNotificationService.HELD_IN_DASHER);
    }

    /** Another store on the same key is another offer: it is peeked at as any. */
    @Test
    public void anotherStoreOnTheSameKeyIsANewOfferAndIsPeekedAt() {
        onDeliveryDropoff();
        long firstPost = SystemClock.uptimeMillis();
        dasherPosts(TACO_BELL, false);
        pass(2_000);
        userTaps(navigate, "Navigate");
        pass(400);
        mapsInFront();
        passUntil(firstPost + 6_000);
        dasherPosts("New Order: Go to Chipotle", false);
        pass(Peek.QUIET_MS + 200);
        assertTrue("another store's offer is peeked at: " + log(), startedDasher());
    }

    // ---- (2) The notification path never declines an offer the user accepted ----

    /** Whether Dasher's own Decline was sent through its notification, or its notification was hidden. */
    private boolean declinedThroughNotification(StatusBarNotification post) {
        return OfferNotificationService.declineActionWithin(120_000) || !listed(post);
    }

    /**
     * During a delivery a failing offer drawn over the drop-off screen: the app taps Decline, the user touches (takes
     * it over) and taps Accept; Dasher goes back to the drop-off screen (the takeover ends there), the user taps
     * Navigate. DoorDash's re-post with the offer's failing pay and its own Decline action is left alone: never Dasher's
     * Decline (from Android 12), never hidden (before it).
     */
    @Test
    public void aRepostWithFailingPayOfAnOfferTheUserTookOverAndAcceptedIsNeverDeclined() {
        onDeliveryDropoff();
        autoReact = true;
        dasherNow = this::dropoff;
        AccessibilityNodeInfo shown = offer("$7.90", "0:35");
        AccessibilityNodeInfo acceptNode = accept;
        dasherShows(shown);
        long firstPost = SystemClock.uptimeMillis();
        dasherPosts(TACO_BELL, false);
        pass(200);
        touch();
        pass(500);
        userTaps(acceptNode, "Accept");
        pass(300);
        dasherShows(dropoff());
        pass(2_000);
        contains(log(), "[takeover] ended: delivery");
        userTaps(navigate, "Navigate");
        pass(400);
        mapsInFront();
        pass(1_000);
        passUntil(firstPost + 16_000);
        subText = "$7.90 · 7.2 mi · 21 min · 2 stops";
        StatusBarNotification repost = dasherPosts(TACO_BELL, true);
        subText = null;
        pass(2_000);
        contains(log(), "DECLINE: required at least $20.00");
        assertFalse("the offer the user accepted is never declined through its notification: " + log(),
                declinedThroughNotification(repost));
        contains(log(), "an offer you may have accepted; notification left alone");
    }

    /**
     * As above, the user's Accept a tap Dasher reports with no node and no words (no acceptance is seen): the offer was
     * read in Dasher and a delivery screen followed it, so its re-post with failing pay is left alone all the same.
     */
    @Test
    public void aRepostWithFailingPayOfAnOfferAcceptedByAnUnreportedTapIsNeverDeclined() {
        onDeliveryDropoff();
        autoReact = true;
        dasherNow = this::dropoff;
        dasherShows(offer("$7.90", "0:35"));
        long firstPost = SystemClock.uptimeMillis();
        dasherPosts(TACO_BELL, false);
        pass(200);
        touch();
        pass(500);
        userTaps(null, "");
        pass(300);
        dasherShows(dropoff());
        pass(2_000);
        assertFalse(log().contains("[accept] Accept tap seen"));
        userTaps(navigate, "Navigate");
        pass(400);
        mapsInFront();
        pass(1_000);
        passUntil(firstPost + 16_000);
        subText = "$7.90 · 7.2 mi · 21 min · 2 stops";
        StatusBarNotification repost = dasherPosts(TACO_BELL, true);
        subText = null;
        pass(2_000);
        contains(log(), "DECLINE: required at least $20.00");
        assertFalse("the offer the user may have accepted is never declined through its notification: " + log(),
                declinedThroughNotification(repost));
        contains(log(), "an offer you may have accepted; notification left alone");
    }

    /**
     * An offer whose pay Dasher's screen does not show (left for review) is accepted by the user's tap; its pickup
     * screen shows; DoorDash's notification of it arrives late, with its failing pay and its own Decline action:
     * while Dasher is still in front, or after Navigate opened Maps.
     */
    private void acceptedReviewOfferThenLateNotificationWithPay(boolean afterNavigate) {
        dashOnWaiting();
        autoReact = true;
        dasherNow = this::pickup;
        AccessibilityNodeInfo shown = offer(null, "0:35");
        AccessibilityNodeInfo acceptNode = accept;
        dasherShows(shown);
        pass(2_000);
        userTaps(acceptNode, "Accept");
        pass(300);
        dasherShows(pickup());
        pass(1_500);
        if (afterNavigate) {
            userTaps(navigate, "Navigate");
            pass(400);
            mapsInFront();
            pass(1_000);
        }
        contains(log(), "[accept] Accept tap seen");
        subText = "$7.90 · 7.2 mi · 21 min · 2 stops";
        StatusBarNotification late = dasherPosts(TACO_BELL, true);
        subText = null;
        pass(2_000);
        contains(log(), "DECLINE: required at least $20.00");
        assertFalse("the offer the user accepted is never declined through its notification: " + log(),
                declinedThroughNotification(late));
        contains(log(), "an offer you may have accepted; notification left alone");
    }

    @Test
    public void aLateNotificationWithFailingPayOfAnAcceptedOfferIsNeverDeclinedWithDasherInFront() {
        acceptedReviewOfferThenLateNotificationWithPay(false);
    }

    @Test
    public void aLateNotificationWithFailingPayOfAnAcceptedOfferIsNeverDeclinedAfterNavigate() {
        acceptedReviewOfferThenLateNotificationWithPay(true);
    }

    /**
     * Another offer, whose figures are not the accepted one's, notified after an Accept the screen read: its failing
     * notification is declined (or, before Android 12, hidden) as before.
     */
    @Test
    public void aDifferentFailingOfferAfterAnAcceptanceIsStillDeclinedThroughItsNotification() {
        dashOnWaiting();
        AccessibilityNodeInfo shown = offer("$25.00", "0:35");
        AccessibilityNodeInfo acceptNode = accept;
        dasherShows(shown);
        pass(2_000);
        userTaps(acceptNode, "Accept");
        pass(300);
        dasherShows(pickup());
        pass(1_500);
        userTaps(navigate, "Navigate");
        pass(400);
        mapsInFront();
        pass(1_000);
        contains(log(), "[accept] Accept tap seen on Pay $25.00");
        key = 10002;
        subText = "$3.50 · 3.1 mi · 12 min · 2 stops";
        StatusBarNotification other = dasherPosts("New Order: Go to Chipotle", true);
        subText = null;
        pass(2_000);
        assertTrue("a different failing offer is still declined through its notification: " + log(),
                declinedThroughNotification(other));
    }

    // ---- (3) Ending the dash in Dasher lets the screen time out again at once ----

    /** In-dash screen, its End dash, Dasher's "End your current dash?" with {@code endIt} (End dash) on it. */
    private AccessibilityNodeInfo endQuestion;

    private AccessibilityNodeInfo dashThenEndQuestion() {
        connect(DASHER_WIN, inDash());
        AccessibilityNodeInfo dash = inDash();
        dasherShows(dash);
        Dashing.seen(app);
        pass(2_000);
        assertTrue("the screen is held during the dash", leaseHeld());
        userTaps(dash.getChild(4), "End dash");
        AccessibilityNodeInfo endIt = button("End dash");
        endQuestion = dasherScreen("End your current dash?", endIt, button("Go back"), "This dash", "$0.00");
        dasherShows(endQuestion);
        pass(1_000);
        return endIt;
    }

    /**
     * The user's End dash on Dasher's question, Dasher's end screen ("Total earned", "$0.00", in no words the app knows
     * for the dash's end), then the home screen: the dash is over, and the screen-on lease goes at once (never the
     * fifteen minutes nothing of the dash is seen).
     */
    @Test
    public void endingTheDashInDasherLetsTheScreenTimeOutAtOnce() {
        AccessibilityNodeInfo endIt = dashThenEndQuestion();
        userTaps(endIt, "End dash");
        dasherShows(dasherScreen("Total earned", "$0.00"));
        pass(3_000);
        assertFalse("released once the dash's screen gave way", leaseHeld());
        assertFalse("the dash is over", Dashing.awaitingEnd(app));
        contains(log(), "[dash] ended: a screen without the dash followed your End dash");
        otherAppInFront(LAUNCHER_WIN, node(LAUNCHER, "Home", false));
        pass(5_000);
        assertFalse(leaseHeld());
    }

    /** The user's End dash, then straight to the home screen before Dasher's end screen is read: the dash is over. */
    @Test
    public void leavingDasherRightAfterEndDashEndsTheDash() {
        AccessibilityNodeInfo endIt = dashThenEndQuestion();
        userTaps(endIt, "End dash");
        otherAppInFront(LAUNCHER_WIN, node(LAUNCHER, "Home", false));
        pass(2_000);
        assertFalse("released once Dasher left after the End dash", leaseHeld());
        assertFalse(Dashing.awaitingEnd(app));
        contains(log(), "[dash] ended: Dasher left the screen after your End dash");
    }

    /** "Go back" on Dasher's question, then Maps: the dash goes on, and the screen is still held whatever is in front. */
    @Test
    public void goBackOnTheQuestionKeepsTheDashOn() {
        dashThenEndQuestion();
        AccessibilityNodeInfo back = endQuestion.getChild(2);
        userTaps(back, "Go back");
        dasherShows(inDash());
        pass(1_000);
        mapsInFront();
        pass(3_000);
        assertTrue("the dash goes on: the screen is still held", leaseHeld());
        assertTrue(Dashing.awaitingEnd(app));
        assertFalse(log().contains("[dash] ended"));
    }

    /**
     * The dash screen's own End dash, its click handed over with the question's window change: a click made before the
     * question was read only asked it. Leaving Dasher then ends nothing.
     */
    @Test
    public void theDashScreensOwnEndDashArrivingWithTheQuestionEndsNothing() {
        connect(DASHER_WIN, inDash());
        AccessibilityNodeInfo dash = inDash();
        dasherShows(dash);
        Dashing.seen(app);
        pass(2_000);
        touch();
        AccessibilityEvent click = event(AccessibilityEvent.TYPE_VIEW_CLICKED, DASHER, "End dash");
        ((ShadowAccessibilityRecord) Shadow.extract(click)).setSourceNode(dash.getChild(4));
        send(click);
        list(DASHER_WIN, dasherScreen("End your current dash?", button("End dash"), button("Go back"), "This dash",
                "$0.00"));
        send(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, DASHER, null));
        idle();
        pass(1_000);
        otherAppInFront(LAUNCHER_WIN, node(LAUNCHER, "Home", false));
        pass(3_000);
        assertTrue("the dash goes on: the screen is still held", leaseHeld());
        assertTrue(Dashing.awaitingEnd(app));
        assertFalse(log().contains("[dash] ended"));
    }

    /** The dash's own End dash asks the question only: leaving Dasher with the question up ends nothing. */
    @Test
    public void leavingDasherWithTheQuestionUpEndsNothing() {
        dashThenEndQuestion();
        otherAppInFront(LAUNCHER_WIN, node(LAUNCHER, "Home", false));
        pass(3_000);
        assertTrue("the dash goes on: the screen is still held", leaseHeld());
        assertTrue(Dashing.awaitingEnd(app));
        assertFalse(log().contains("[dash] ended"));
    }

    // ---- (5) A peek goes back after its decline over the pickup screen's own amount ----

    /**
     * Google Maps in front with a delivery under way; a fresh offer: Peek opens Dasher, the app declines it and
     * confirms. Dasher goes back to its pickup screen, which shows its own "$9.30 / this offer": the peek goes back to
     * the map, as after a drop-off screen.
     */
    @Test
    public void aPeekGoesBackAfterItsDeclineOverThePickupScreensOwnAmount() {
        ActiveRouteStore.save(app, new OfferSnapshot(900, 3.0, 12, 2));
        connect(navWindow++, app(MAPS));
        Dashing.seen(app);
        pass(1_000);
        dasherPosts(TACO_BELL, false);
        pass(Peek.QUIET_MS + 100);
        assertTrue("Peek opens Dasher for the fresh offer: " + log(), startedDasher());
        dasherShows(offer("$3.50", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        dasherShows(question(confirm));
        pass(150);
        dasherShows(pickup());
        pass(1_500);
        assertTrue("back to the map: " + starts + "\n" + log(), startedMaps());
        contains(log(), "returned to a navigation app: the offer was declined");
    }

    /**
     * As above, the user taps Navigate on Dasher as its question closes, before the pickup screen is read: anything of
     * the user's leaves Dasher up, the pickup screen's own amount or not.
     */
    @Test
    public void aTapOnDasherBeforeThePickupScreenIsReadKeepsDasherUp() {
        ActiveRouteStore.save(app, new OfferSnapshot(900, 3.0, 12, 2));
        connect(navWindow++, app(MAPS));
        Dashing.seen(app);
        pass(1_000);
        dasherPosts(TACO_BELL, false);
        pass(Peek.QUIET_MS + 100);
        assertTrue(startedDasher());
        dasherShows(offer("$3.50", "0:35"));
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        dasherShows(question(confirm));
        pass(300);
        AccessibilityNodeInfo shown = pickup();
        userTaps(navigate, "Navigate");
        dasherShows(shown);
        pass(1_500);
        assertFalse("Dasher is left up for the user: " + starts + "\n" + log(), startedMaps());
        contains(log(), "[peek] left Dasher up because you");
    }

    /** The pickup screen's own amount is no offer's only after the app's own decline: before it, nothing goes back. */
    @Test
    public void theRouteScreensOwnAmountProvesNothingBeforeTheDecline() {
        ActiveRouteStore.save(app, new OfferSnapshot(900, 3.0, 12, 2));
        connect(navWindow++, app(MAPS));
        Dashing.seen(app);
        pass(1_000);
        dasherPosts(TACO_BELL, false);
        pass(Peek.QUIET_MS + 100);
        assertTrue(startedDasher());
        // Dasher comes up on its pickup screen and never draws the offer; its notification is still posted.
        dasherShows(pickup());
        pass(1_500);
        assertFalse("no return for want of an offer while its notification is posted: " + log(), startedMaps());
    }

    // ---- (6) With no rule set, a notification is never a pass ----

    /**
     * Auto-decline on but no rule set (older saved settings): Dasher's payless notification while Maps is in front is
     * left to the user, as a pause would leave it. Never "KEEP: meets enabled rules", never the pass chime, and no
     * payless card beside Dasher's own, which alerts by itself.
     */
    @Test
    public void withNoRuleSetAPaylessNotificationIsNeitherAPassNorACard() {
        FilterStore.save(app, FilterSettings.of(true, 0, 0, 0, 0));
        connect(navWindow++, app(MAPS));
        Dashing.seen(app);
        pass(1_000);
        dasherPosts(TACO_BELL, false);
        pass(Peek.QUIET_WAIT_MS);
        contains(log(), "REVIEW: " + OfferNotificationService.NO_RULE);
        assertFalse(log(), log().contains("KEEP: meets enabled rules"));
        assertFalse(log(), log().contains("[alert] posted KEEP"));
        assertTrue("no card beside Dasher's own: " + cards(), cards().isEmpty());
        assertFalse("no peek with no rule set", startedDasher());
    }

    // ---- (8) A decline whose question never comes reads Dasher's own map at most once a second ----

    /**
     * The app declines a failing offer; Dasher never asks its question and keeps the offer up 5 s, then shows its own
     * turn-by-turn map (no touch or click of the user's seen). While the decline still waits for its question, the map
     * is read for each change Dasher reports, and otherwise once a second at most (never five times a second), until
     * the offer's countdown runs out.
     */
    @Test
    public void aDeclineWhoseQuestionNeverComesReadsDashersMapAtMostOnceASecond() {
        OfferFilterService service = connect(DASHER_WIN, waiting());
        dasherShows(waiting());
        Dashing.seen(app);
        pass(1_000);
        dasherShows(offer("$3.50", "0:35"));
        for (int s = 34; s >= 30; s--) {
            list(DASHER_WIN, offer("$3.50", "0:" + s));
            dasherChanged();
            pass(1_000);
        }
        contains(log(), "first-step Decline REQUESTED");
        dasherShows(inAppMap());
        for (int i = 0; i < 40; i++) {
            dasherChanged();
            pass(250);
        }
        // Dasher stops sending events: what is read now is the decline's own timer.
        int before = service.contentReads;
        pass(20_000);
        int quietReads = service.contentReads - before;
        assertTrue("at most one read a second while Dasher says nothing: " + quietReads, quietReads <= 21);
        assertTrue("still read in case the question comes late: " + quietReads, quietReads >= 15);
        pass(15_000);
        contains(log(), "[confirm] authority ended: offer countdown expired");
    }

    /** While the declined offer still shows, its first-step retries go on as before, each after a fresh read of it. */
    @Test
    public void theFirstStepRetriesStillReadTheOfferAtOnce() {
        connect(DASHER_WIN, waiting());
        dasherShows(waiting());
        Dashing.seen(app);
        pass(1_000);
        List<Long> declines = new ArrayList<>();
        AccessibilityNodeInfo shown = offer("$3.50", "0:35");
        Shadows.shadowOf(decline).setOnPerformActionListener((action, args) -> {
            declines.add(SystemClock.uptimeMillis());
            return true;
        });
        dasherShows(shown);
        assertEquals("the first decline at once", 1, declines.size());
        pass(5_000);
        assertTrue("first-step retries, each after a fresh read: " + declines, declines.size() >= 2);
        contains(log(), "read after first-step retry");
    }
}
