package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
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
import org.robolectric.shadows.ShadowNotificationListenerService;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The owner, 8 October 2026: "last time it happened it was when trying to deliver, every time actually ... No matter how
 * many times I click Directions it just keeps going back to the same screen" (earlier: "it tries to go to the map and
 * then goes back to the dasher offer details screen"). DoorDash posts the notification of the order being delivered
 * again as Dasher leaves the screen for the map its Directions opened, minutes after the offer: past the minute's
 * hold on an offer's re-posts, that post was a fresh offer to Peek, which pulled Dasher (its launcher: the same
 * delivery screen) back over the map, sent Dasher's own notification tap 2.5 s later (its offer screen), and went back
 * to the map with a "Dasher didn't show this offer" card 5 s after that. A post naming the store of the order being
 * delivered is never peeked at now, until the wait for offers says the delivery is over; another store's offer is.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class DirectionsDuringDeliveryTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final String DASHER = "com.doordash.driverapp";
    private static final ComponentName DASHER_HOME = new ComponentName(DASHER, DASHER + ".Home");
    private static final ComponentName DASHER_OFFER = new ComponentName(DASHER, DASHER + ".OfferNotificationActivity");
    private static final String MAPS = "com.google.android.apps.maps";
    private static final ComponentName MAPS_HOME = new ComponentName(MAPS, MAPS + ".MapsActivity");

    private Application app;
    private ServiceController<OfferFilterService> screen;
    private ServiceController<OfferNotificationService> listener;
    private final List<PendingIntent> ownTaps = new CopyOnWriteArrayList<>();

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
        installed(DASHER_OFFER, Intent.CATEGORY_DEFAULT);
        installed(MAPS_HOME, Intent.CATEGORY_LAUNCHER);
        installed(new ComponentName("com.example.launcher", "com.example.launcher.Home"), Intent.CATEGORY_HOME);
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

    private AccessibilityNodeInfo offer(String pay) {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        Shadows.shadowOf(root).addChild(button("Decline"));
        Shadows.shadowOf(root).addChild(node(DASHER, pay, false));
        Shadows.shadowOf(root).addChild(node(DASHER, "incl. tips", false));
        Shadows.shadowOf(root).addChild(node(DASHER, "2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(button("Accept"));
        Shadows.shadowOf(root).addChild(node(DASHER, "0:35", false));
        return root;
    }

    private AccessibilityNodeInfo dasherScreen(String... labels) {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        for (String label : labels) {
            if (label.equals("Directions")) Shadows.shadowOf(root).addChild(button(label));
            else Shadows.shadowOf(root).addChild(node(DASHER, label, false));
        }
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

    private void inFront(AccessibilityNodeInfo root) {
        OfferFilterService service = screen.get();
        Shadows.shadowOf(service).setWindows(Collections.singletonList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, root, true, SCREEN)));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    private OfferFilterService connect(AccessibilityNodeInfo front) {
        screen = Robolectric.buildService(OfferFilterService.class).create();
        inFront(front);
        screen.get().ownTapSender = (intent, options) -> ownTaps.add(intent);
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

    /** The map Dasher's Directions opened comes up in front: Android's list of windows changes. */
    private void mapsComesUp() {
        inFront(app(MAPS));
        screen.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle();
    }

    private StatusBarNotification offerNotification(String store, long postedAt) {
        Notification payload = new Notification.Builder(app, "dasher_offers")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText("New Order: Go to " + store)
                .build();
        payload.contentIntent = dashersOwn();
        return new StatusBarNotification(DASHER, DASHER, 3, "NEW_ORDER", 10001, 0, 0, payload,
                android.os.Process.myUserHandle(), postedAt);
    }

    private PendingIntent dashersOwn() {
        PendingIntent own = PendingIntent.getActivity(app, 7, new Intent().setComponent(DASHER_OFFER),
                PendingIntent.FLAG_IMMUTABLE);
        Shadows.shadowOf(own).setCreatorPackage(DASHER);
        return own;
    }

    private ShadowNotificationListenerService nativeAlerts() {
        return Shadow.extract(listener.get());
    }

    /** Dasher posts (or re-posts) its offer notification, listed by Android as it comes. */
    private StatusBarNotification posted(String store) {
        StatusBarNotification source = offerNotification(store, System.currentTimeMillis());
        while (listener.get().getActiveNotifications(new String[] {source.getKey()}).length > 0) {
            listener.get().cancelNotification(source.getKey());
        }
        nativeAlerts().addActiveNotification(source);
        listener.get().onNotificationPosted(source, null);
        idle();
        return source;
    }

    /** Dasher takes its notification down (it does so as it comes back in front, say). */
    private void removed(StatusBarNotification source) {
        while (listener.get().getActiveNotifications(new String[] {source.getKey()}).length > 0) {
            listener.get().cancelNotification(source.getKey());
        }
        listener.get().onNotificationRemoved(source);
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

    private static String log() {
        return DiagnosticLog.read(RuntimeEnvironment.getApplication());
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    private AccessibilityNodeInfo dropOff() {
        return dasherScreen("Deliver to Sam", "123 Main St", "Directions");
    }

    /**
     * An offer accepted in Dasher (its notification came while Dasher showed it), picked up, and minutes later the
     * user taps Directions on the drop-off screen: the map comes up, and Dasher posts its offer notification again as
     * it leaves the screen.
     */
    private StatusBarNotification deliveringThenDirections(boolean removedBetween) {
        return deliveringThenDirections(removedBetween, false);
    }

    /** @param removedAtAccept Dasher takes the offer's notification down at the Accept, before its pickup screen */
    private StatusBarNotification deliveringThenDirections(boolean removedBetween, boolean removedAtAccept) {
        connect(dasherScreen("Finding offers"));
        dasherShows(dasherScreen("Finding offers"));
        dasherShows(offer("$25.00"));
        StatusBarNotification first = posted("Taco Bell");
        // The user's Accept (Dasher reports no finger taps): the pickup screen.
        pass(3_000);
        if (removedAtAccept) removed(first);
        dasherShows(dasherScreen("Pick up by 2:20 AM", "Taco Bell", "Directions"));
        pass(6_000);
        if (removedBetween) removed(first);
        // Driving to the store, picking up: minutes on Dasher's delivery screens.
        for (int i = 0; i < 18; i++) {
            pass(10_000);
            dasherShows(i < 9 ? dasherScreen("Pick up by 2:20 AM", "Taco Bell", "Directions") : dropOff());
        }
        assertNotNull("the acceptance is seen and its route kept", ActiveRouteStore.load(app));
        // Directions: the map comes up in front, and Dasher (now in the background) posts its notification again.
        mapsComesUp();
        pass(300);
        return posted("Taco Bell");
    }

    @Test
    public void directionsDuringADeliveryBringsDasherBackOverTheMap() {
        deliveringThenDirections(false);
        pass(Peek.QUIET_WAIT_MS + 500);
        assertNull("Dasher is never pulled back over the map Directions opened: " + log(), started());
        contains(log(), "[peek] skipped: Dasher's post for the order you are delivering (the same store)");
        assertTrue("no own tap either", ownTaps.isEmpty());
    }

    @Test
    public void directionsDuringADeliveryAfterDasherTookItsNotificationDown() {
        deliveringThenDirections(true);
        pass(Peek.QUIET_WAIT_MS + 500);
        assertNull("Dasher is never pulled back over the map Directions opened: " + log(), started());
        contains(log(), "[peek] skipped: Dasher's post for the order you are delivering (the same store)");
    }

    @Test
    public void directionsDuringADeliveryWhoseOfferNotificationWentAtTheAccept() {
        deliveringThenDirections(false, true);
        pass(Peek.QUIET_WAIT_MS + 500);
        assertNull("Dasher is never pulled back over the map Directions opened: " + log(), started());
        contains(log(), "[peek] skipped: Dasher's post for the order you are delivering (the same store)");
    }

    /** Another store's offer during the delivery (an add-on, say) is still peeked at from the map. */
    @Test
    public void anotherStoresOfferDuringTheDeliveryIsStillPeekedAt() {
        deliveringThenDirections(false);
        pass(Peek.QUIET_WAIT_MS + 500);
        assertNull(started());
        pass(Peek.GAP_MS + 1_000);
        posted("Burger Barn");
        pass(Peek.QUIET_MS + 300);
        Intent opened = started();
        assertNotNull("a new store's offer is peeked at: " + log(), opened);
        assertEquals(DASHER_HOME, opened.getComponent());
    }

    /** Once Dasher shows its wait for offers again, the delivery is over: the same store's next offer is peeked at. */
    @Test
    public void theSameStoresOfferAfterTheDeliveryIsPeekedAt() {
        StatusBarNotification repost = deliveringThenDirections(false);
        pass(Peek.QUIET_WAIT_MS + 500);
        assertNull(started());
        dasherShows(dasherScreen("Finding offers"));
        removed(repost);
        pass(2_000);
        mapsComesUp();
        // A new offer from that store (its own notification: the last one went).
        pass(Peek.GAP_MS + 1_000);
        posted("Taco Bell");
        pass(Peek.QUIET_MS + 300);
        Intent opened = started();
        assertNotNull("after the delivery, the same store's offer is an offer of its own: " + log(), opened);
        assertEquals(DASHER_HOME, opened.getComponent());
    }
}
