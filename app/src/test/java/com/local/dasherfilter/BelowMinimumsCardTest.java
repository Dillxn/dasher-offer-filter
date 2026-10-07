package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
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
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * An offer that passes only because Autopilot's bar is below 100% is the user's to accept (the owner, 6 October 2026):
 * its card, whether from Dasher's notification or from a peek that went back to the map (an add-on's with what the
 * add-on itself adds), is a card to check. It goes on "Offers to check" ({@link OfferAlerts#REVIEW_CHANNEL_ID}), rings
 * as such a card rings (once), is titled for checking and says why, and never sounds the pass chime of "Offers that
 * pass" ({@link OfferAlerts#CHANNEL_ID}). An
 * offer meeting the minimums under the same bar keeps the pass chime. Through the real notification listener and screen
 * reader; synthetic Android, no handset claimed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public final class BelowMinimumsCardTest {
    /** The starter minimums: $4 an offer, $1 a mile, $0.25 a minute ($15 an hour). */
    private static final FilterSettings STARTER = FilterSettings.of(true, 400, 100, 25, 0);
    /** 6.6 mi and 27 min ask $6.75 at 100%: per hour asks the most. */
    private static final String ROUTE = "2 stops (6.6 mi) • 27 min";
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final String DASHER = "com.doordash.driverapp";
    private static final ComponentName DASHER_HOME = new ComponentName(DASHER, DASHER + ".Home");
    private static final String MAPS = "com.google.android.apps.maps";
    private static final ComponentName MAPS_HOME = new ComponentName(MAPS, MAPS + ".MapsActivity");

    private Application app;
    private ServiceController<OfferNotificationService> listener;
    private ServiceController<OfferFilterService> screen;
    private AccessibilityNodeInfo decline;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        FilterStore.save(app, STARTER);
        DiagnosticLog.setEnabled(app, true);
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        AreaMap.forgetCache();
        OfferSilencer.forgetCache();
        OfferFilterService.forgetScreenState();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        installed(DASHER_HOME);
        installed(MAPS_HOME);
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        idle();
    }

    @After public void tearDown() {
        listener.destroy();
        if (screen != null) screen.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.forgetScreenState();
    }

    /** Autopilot on, its bar where a commit between offers left it. */
    private void autopilotBarAt(int bar) {
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, FilterStore.load(app).minimumScalePercent, bar));
        assertEquals(bar, FilterStore.load(app).minimumScalePercent);
    }

    // ---- Dasher's notification ----

    @Test public void aBackgroundPassBelowTheMinimumsIsACardToCheckNeverThePassChime() {
        autopilotBarAt(82);
        // $5.75 is 85% of the $6.75 the minimums ask: under the 82% bar it passes, below the minimums.
        listener.get().onNotificationPosted(withFigures("New Delivery!", "$5.75"), null);
        idle();
        List<Notification> posted = cards();
        assertEquals(1, posted.size());
        Notification card = posted.get(0);
        assertEquals("Offers to check, never the pass chime", OfferAlerts.REVIEW_CHANNEL_ID, card.getChannelId());
        assertTrue("it rings once, as a card to check does",
                card.getGroupAlertBehavior() != Notification.GROUP_ALERT_SUMMARY);
        assertEquals("Store A offer: open Dasher to check it", title(card));
        assertEquals("Pay $5.75, miles 6.6, minutes 27, stops 2; below your minimums (85%), passed by Autopilot's 82% "
                + "bar. Yours to accept.", text(card));
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.Source.NOTIFICATION, line.source);
        assertEquals(OfferRule.Result.KEEP, line.result);
        assertEquals("below your minimums; passes the 82% bar", line.reason);
        assertEquals(85, line.scorePercent);
        assertEquals(82, line.barPercent);
        assertTrue(line.autopilot);
        assertEquals(DecisionLog.Action.CHECK_BELL, line.action);
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("posted REVIEW audibleRequested=true"));
        assertFalse("the pass chime's card is never posted for it", log.contains("posted KEEP"));
    }

    @Test public void anUpdateOfTheSameOfferDoesNotRingAgainAndStaysACardToCheck() {
        autopilotBarAt(82);
        listener.get().onNotificationPosted(withFigures("New Delivery!", "$5.75"), null);
        idle();
        // DoorDash changes its title as the offer ages: the same offer, its one ring spent.
        listener.get().onNotificationPosted(withFigures("New Delivery! 0:45 left", "$5.75"), null);
        idle();
        List<Notification> posted = cards();
        assertEquals(1, posted.size());
        Notification update = posted.get(0);
        assertEquals(OfferAlerts.REVIEW_CHANNEL_ID, update.getChannelId());
        assertEquals("no second ring", Notification.GROUP_ALERT_SUMMARY, update.getGroupAlertBehavior());
        assertEquals("Store A offer: open Dasher to check it", title(update));
        assertFalse(DiagnosticLog.read(app).contains("posted KEEP"));
    }

    @Test public void anOfferMeetingTheMinimumsUnderTheSameBarKeepsThePassChime() {
        autopilotBarAt(82);
        // $7.00 is 103% of the $6.75 the minimums ask: it meets them.
        listener.get().onNotificationPosted(withFigures("New Delivery!", "$7.00"), null);
        idle();
        List<Notification> posted = cards();
        assertEquals(1, posted.size());
        Notification card = posted.get(0);
        assertEquals(OfferAlerts.CHANNEL_ID, card.getChannelId());
        assertTrue(card.getGroupAlertBehavior() != Notification.GROUP_ALERT_SUMMARY);
        assertEquals("Store A offer meets your rules", title(card));
        // In the words Peek's cards use, never the engine's ("KEEP: required at least $6.75 (meets your minimums)").
        assertTrue(text(card), text(card).startsWith("Passes: $7.00 · "));
        assertFalse(text(card), text(card).contains("KEEP") || text(card).contains("required at least"));
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(OfferRule.Result.KEEP, line.result);
        assertEquals(OfferRule.MEETS_MINIMUMS, line.reason);
        assertEquals(DecisionLog.Action.BELL, line.action);
    }

    // ---- A peek that goes back to the map ----

    @Test public void aPeekedPassBelowTheMinimumsGoesBackToTheMapOnACardToCheck() {
        connect(app(MAPS));
        autopilotBarAt(82);
        post("Taco Bell");
        dasherOpened();
        AccessibilityNodeInfo shown = offer("$5.75");
        List<Long> declines = taps(decline);
        dasherShows(shown);
        Intent back = started();
        assertNotNull("back to the map: the offer is the user's decision", back);
        assertEquals(MAPS_HOME, back.getComponent());
        assertTrue("never declined", declines.isEmpty());
        List<Notification> posted = cards();
        assertEquals("the peeked offer's own card", 1, posted.size());
        Notification card = posted.get(0);
        assertEquals("Offers to check, never the pass chime", OfferAlerts.REVIEW_CHANNEL_ID, card.getChannelId());
        assertTrue("it rings once, as a card to check does",
                card.getGroupAlertBehavior() != Notification.GROUP_ALERT_SUMMARY);
        assertEquals("Taco Bell offer: open Dasher to check it", title(card));
        assertEquals("$5.75 · 6.6 mi · 27 min · 2 stops; below your minimums (85%), passed by Autopilot's 82% bar. "
                + "Yours to accept.", text(card));
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("the offer passes only Autopilot's bar, below your minimums; you are navigating, "
                + "so its card carries it"));
        assertTrue(log, log.contains("posted REVIEW audibleRequested=true"));
        assertFalse(log, log.contains("posted KEEP"));
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertEquals(OfferRule.Result.KEEP, line.result);
        assertEquals("below your minimums; passes the 82% bar", line.reason);
        assertEquals(82, line.barPercent);
        assertTrue(line.peeked);
    }

    /**
     * The main case while driving: an add-on, peeked from the map, that passes only under the 82% bar. Its card carries
     * what the add-on itself adds (never the standalone parse, and never "unclear": every figure was read) and says it
     * is below the minimums and the user's to accept, on a card to check.
     */
    @Test public void aPeekedAddOnPassingBelowTheMinimumsGoesBackOnACardToCheckWithItsOwnFigures() {
        // $10 an offer, $1 a mile, $15 an hour.
        FilterStore.save(app, FilterSettings.of(true, 1000, 100, 25, 0));
        connect(app(MAPS));
        autopilotBarAt(82);
        // On a delivery: $15.00 for 5 mi and 25 min.
        ActiveRouteStore.save(app, new OfferSnapshot(1500, 5.0, 25, 2));
        post("Taco Bell");
        dasherOpened();
        // $1.80 more for 2 more miles and 8 more minutes: the $2.00 those ask at the minimums it misses, the $1.64 they
        // ask at 82% it meets (the whole route, $16.80 for 7 mi and 33 min, meets both).
        AccessibilityNodeInfo addOn = node(DASHER, null, false);
        decline = button("Decline");
        Shadows.shadowOf(addOn).addChild(node(DASHER, "Add to route", false));
        Shadows.shadowOf(addOn).addChild(node(DASHER, "+$1.80", false));
        Shadows.shadowOf(addOn).addChild(node(DASHER, "+2 mi", false));
        Shadows.shadowOf(addOn).addChild(node(DASHER, "+8 min", false));
        Shadows.shadowOf(addOn).addChild(decline);
        Shadows.shadowOf(addOn).addChild(button("Accept"));
        Shadows.shadowOf(addOn).addChild(node(DASHER, "0:30", false));
        List<Long> declines = taps(decline);
        dasherShows(addOn);
        assertEquals("on a route: back to the map with its card", MAPS_HOME, started().getComponent());
        assertTrue("never declined", declines.isEmpty());
        DecisionLog.Entry line = DecisionLog.recent(app, 1).get(0);
        assertTrue(line.addOn);
        assertEquals(OfferRule.Result.KEEP, line.result);
        assertEquals("combined route and add-on below your minimums; pass the 82% bar", line.reason);
        assertEquals(82, line.barPercent);
        List<Notification> posted = cards();
        assertEquals("the peeked offer's own card", 1, posted.size());
        Notification card = posted.get(0);
        assertEquals("Offers to check, never the pass chime", OfferAlerts.REVIEW_CHANNEL_ID, card.getChannelId());
        assertTrue("it rings once, as a card to check does",
                card.getGroupAlertBehavior() != Notification.GROUP_ALERT_SUMMARY);
        assertEquals("Taco Bell offer: open Dasher to check it", title(card));
        assertEquals("+$1.80 · +2 mi · +8 min; below your minimums, passed by Autopilot's 82% bar. Yours to accept.",
                text(card));
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("posted REVIEW audibleRequested=true"));
        assertFalse(log, log.contains("posted KEEP"));
    }

    @Test public void aPeekedOfferMeetingTheMinimumsKeepsThePassChime() {
        connect(app(MAPS));
        autopilotBarAt(82);
        post("Taco Bell");
        dasherOpened();
        AccessibilityNodeInfo shown = offer("$7.00");
        List<Long> declines = taps(decline);
        dasherShows(shown);
        assertEquals(MAPS_HOME, started().getComponent());
        assertTrue(declines.isEmpty());
        List<Notification> posted = cards();
        assertEquals(1, posted.size());
        Notification card = posted.get(0);
        assertEquals(OfferAlerts.CHANNEL_ID, card.getChannelId());
        assertEquals("Taco Bell offer meets your rules", title(card));
        assertEquals("Passes: $7.00 · 6.6 mi · 27 min · 2 stops", text(card));
    }

    // ---- The phone ----

    private void installed(ComponentName activity) {
        ShadowPackageManager packages = Shadows.shadowOf(app.getPackageManager());
        android.content.pm.ActivityInfo declared = packages.addActivityIfNotPresent(activity);
        declared.enabled = true;
        declared.exported = true;
        IntentFilter entry = new IntentFilter(Intent.ACTION_MAIN);
        entry.addCategory(Intent.CATEGORY_LAUNCHER);
        entry.addCategory(Intent.CATEGORY_DEFAULT);
        packages.addIntentFilterForActivity(activity, entry);
    }

    /** Dasher's notification of a new offer with its pay and route on it, as its expanded post shows them. */
    private StatusBarNotification withFigures(String title, String pay) {
        Notification payload = new Notification.Builder(app, "dasher_offers")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText("New Order: Go to Store A")
                .setStyle(new Notification.InboxStyle().addLine(pay).addLine(ROUTE))
                .build();
        return new StatusBarNotification(DASHER, DASHER, 3, "NEW_ORDER", 10001, 0, 0, payload,
                android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    /** Dasher's background notification of a new offer: the store only, no pay, miles or time. */
    private void post(String store) {
        Notification payload = new Notification.Builder(app, "dasher_offers")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText("New Order: Go to " + store)
                .build();
        listener.get().onNotificationPosted(new StatusBarNotification(DASHER, DASHER, 3, "NEW_ORDER", 10001, 0, 0,
                payload, android.os.Process.myUserHandle(), System.currentTimeMillis()), null);
        idle();
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

    /** An offer as Dasher draws it. */
    private AccessibilityNodeInfo offer(String pay) {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        decline = button("Decline");
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node(DASHER, pay, false));
        Shadows.shadowOf(root).addChild(node(DASHER, "incl. tips", false));
        Shadows.shadowOf(root).addChild(node(DASHER, ROUTE, false));
        Shadows.shadowOf(root).addChild(button("Accept"));
        Shadows.shadowOf(root).addChild(node(DASHER, "0:35", false));
        return root;
    }

    /** {@code root}'s app fills the screen and is the one in front. */
    private void inFront(AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(true);
        shadow.setBoundsInScreen(SCREEN);
        Shadows.shadowOf(screen.get()).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(screen.get()).setRootInActiveWindow(root);
    }

    /** Screen reading connects with {@code front} in front. */
    private void connect(AccessibilityNodeInfo front) {
        screen = Robolectric.buildService(OfferFilterService.class).create();
        inFront(front);
        screen.get().onServiceConnected();
        idle();
    }

    /** Dasher comes up showing {@code root}, and says so with a window change. */
    private void dasherShows(AccessibilityNodeInfo root) {
        inFront(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER);
        event.setEventTime(SystemClock.uptimeMillis());
        screen.get().onAccessibilityEvent(event);
        idle();
    }

    /** The peek brings Dasher up once the phone is quiet. */
    private void dasherOpened() {
        pass(Peek.QUIET_MS);
        Intent opened = started();
        assertNotNull("Dasher is brought up for the payless offer", opened);
        assertEquals(DASHER_HOME, opened.getComponent());
    }

    private Intent started() {
        return Shadows.shadowOf(app).getNextStartedActivity();
    }

    private static List<Long> taps(AccessibilityNodeInfo button) {
        List<Long> at = new CopyOnWriteArrayList<>();
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            at.add(SystemClock.uptimeMillis());
            return true;
        });
        return at;
    }

    private List<Notification> cards() {
        return Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getAllNotifications();
    }

    private static String title(Notification card) {
        return String.valueOf(card.extras.getCharSequence(Notification.EXTRA_TITLE));
    }

    private static String text(Notification card) {
        return String.valueOf(card.extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }
}
