package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
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
import org.robolectric.shadows.ShadowNotificationListenerService;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * One offer, one line: Dasher's notification and the screen's reading of the same offer, through both real services.
 * App timestamps are the wall clock (Robolectric does not shift it), so any spacing between history lines comes from
 * lines recorded with an explicit time; the shadow clock only moves uptime, which gives each notification incarnation
 * its own card tag.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class SameOfferAdapterTest {
    private static final String STORE_A = "New Order: Go to Store A";
    private static final String STORE_B = "New Order: Go to Store B";

    private Application app;
    private ServiceController<OfferFilterService> filter;
    private ServiceController<OfferNotificationService> listener;
    /** The Decline button of the offer screen built last. */
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
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        filter = Robolectric.buildService(OfferFilterService.class).create();
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void stop() {
        listener.destroy();
        filter.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
    }

    // ---- Screens (as in AccessibilityAdapterTest) ----

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

    /** An offer screen showing {@code money}, a route line, and Accept/Decline. */
    private AccessibilityNodeInfo offer(String money) {
        return partialOffer(money, "2 stops (7.2 mi) • 21 min");
    }

    private AccessibilityNodeInfo partialOffer(String... labels) {
        AccessibilityNodeInfo root = node("", false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false));
        Shadows.shadowOf(root).addChild(node("Accept", true));
        decline = node("Decline", true);
        Shadows.shadowOf(root).addChild(decline);
        return root;
    }

    /** Dasher's "Are you sure?" dialog after a Decline tap, with its own Decline button. */
    private AccessibilityNodeInfo confirmation(AccessibilityNodeInfo button) {
        AccessibilityNodeInfo root = node("Are you sure you want to decline this offer?", false);
        Shadows.shadowOf(root).addChild(button);
        return root;
    }

    private AccessibilityNodeInfo idle() {
        AccessibilityNodeInfo root = node("", false);
        Shadows.shadowOf(root).addChild(node("Finding offers", false));
        return root;
    }

    /**
     * Dasher leaves the screen: the launcher is the active window. Dasher sends no event for that, and no time
     * passes: the screen reader has not looked since, but the notification path checks the windows itself whenever
     * the last look still says Dasher is on screen.
     */
    private void dasherLeaves() {
        AccessibilityNodeInfo launcher = AccessibilityNodeInfo.obtain(new View(app));
        launcher.setPackageName("com.google.android.apps.nexuslauncher");
        launcher.setVisibleToUser(true);
        TestWindows.full(filter.get(), launcher);
    }

    /** Makes {@code root} Dasher's active window and delivers a window-state event, read at once. */
    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(filter.get(), root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        filter.get().onAccessibilityEvent(event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    // ---- Notifications (as in AndroidAdapterTest) ----

    private StatusBarNotification doorDashOffer(String text) {
        return doorDashOffer("New Delivery!", text, System.currentTimeMillis());
    }

    /** A DoorDash offer notification on its one reused key, posted at {@code postTime}. */
    private StatusBarNotification doorDashOffer(String title, String text, long postTime) {
        Notification payload = new Notification.Builder(app, "source")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(text)
                .build();
        return new StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp", 3, "NEW_ORDER", 10001, 0,
                0, payload, android.os.Process.myUserHandle(), postTime);
    }

    private void post(StatusBarNotification source) {
        listener.get().onNotificationPosted(source, null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private ShadowNotificationManager notifications() {
        return Shadows.shadowOf(app.getSystemService(NotificationManager.class));
    }

    private static boolean rings(Notification card) {
        return card.getGroupAlertBehavior() != Notification.GROUP_ALERT_SUMMARY;
    }

    /** A notification of an earlier offer, recorded {@code agoMs} before now. */
    private void earlierNotification(long agoMs) {
        DecisionLog.record(app, new DecisionLog.Entry(System.currentTimeMillis() - agoMs,
                DecisionLog.Source.NOTIFICATION, false, OfferSnapshot.UNKNOWN, 0, OfferRule.Result.REVIEW,
                "pay not found", DecisionLog.Action.CHECK_BELL, true, Collections.emptyList()));
    }

    private boolean anyLine(DecisionLog.Source source, String action) {
        for (DecisionLog.Entry entry : DecisionLog.recent(app, 50)) {
            if (entry.source == source && entry.action.name().equals(action)) return true;
        }
        return false;
    }

    // ---- One offer, one line ----

    @Test
    public void dashersNotificationOfAnOfferJustDeclinedOnScreenGetsNoCard() {
        filter.get().onServiceConnected();
        show(offer("$7.90"));
        post(doorDashOffer(STORE_A));

        assertEquals("no card for an offer the screen already declined", 0, notifications().size());
        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(1, recent.size());
        assertEquals(DecisionLog.Source.SCREEN, recent.get(0).source);
        assertArrayEquals(new int[] {0, 1, 0}, DecisionLog.totals(app));
        assertFalse("the screen's status stands", FilterStore.lastStatus(app).contains("Background offer requires review"));
    }

    @Test
    public void aReviewCardIsClearedWhenTheScreenReadsItsOffer() {
        listener.get().onListenerConnected();
        filter.get().onServiceConnected();
        StatusBarNotification source = doorDashOffer(STORE_A);
        ShadowNotificationListenerService dasher = Shadow.extract(listener.get());
        dasher.addActiveNotification(source);
        post(source);
        assertEquals(1, notifications().size());
        assertTrue("Dasher is not on screen, so it rings", rings(notifications().getAllNotifications().get(0)));

        // The user opens Dasher and the screen reads the offer and declines it.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(12));
        show(offer("$7.90"));

        assertEquals("our card goes once the screen has the offer", 0, notifications().size());
        assertEquals("Dasher's own notification is never touched", 1,
                listener.get().getActiveNotifications().length);
        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(1, recent.size());
        assertEquals(OfferRule.Result.DECLINE, recent.get(0).result);
        assertArrayEquals(new int[] {0, 1, 0}, DecisionLog.totals(app));
        String report = DecisionLog.report(app, 10);
        assertTrue(report, report.contains("    notification "));
        assertTrue(report, report.contains("Rang once: open Dasher to check it"));
    }

    @Test
    public void aCardThatRangIsClearedWhenTheUserComesBackToTheSameOffer() {
        listener.get().onListenerConnected();
        filter.get().onServiceConnected();
        // The screen reads a passing offer, and the user leaves Dasher before Dasher's notification of it comes.
        show(offer("$25.00"));
        dasherLeaves();
        StatusBarNotification source = doorDashOffer(STORE_A);
        ShadowNotificationListenerService dasher = Shadow.extract(listener.get());
        dasher.addActiveNotification(source);
        post(source);
        assertEquals(1, notifications().size());
        assertTrue("off screen it may be a next offer, so it rings", rings(notifications().getAllNotifications().get(0)));

        // Back in Dasher, the same offer is still up: the screen has it, so the card that rang goes.
        show(offer("$25.00"));
        assertEquals("our card goes once the screen reads its offer, rung or not", 0, notifications().size());
        assertEquals("Dasher's own notification is never touched", 1,
                listener.get().getActiveNotifications().length);
        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals("one offer, one line", 1, recent.size());
        assertEquals(DecisionLog.Source.SCREEN, recent.get(0).source);
        assertEquals(OfferRule.Result.KEEP, recent.get(0).result);
        assertArrayEquals(new int[] {1, 0, 0}, DecisionLog.totals(app));
        String report = DecisionLog.report(app, 10);
        assertTrue(report, report.contains("    notification ") && report.contains("Rang once: open Dasher to check it"));
    }

    @Test
    public void aCardForAnOfferTheScreenCouldNotReadStays() {
        listener.get().onListenerConnected();
        filter.get().onServiceConnected();
        post(doorDashOffer(STORE_A));
        assertEquals(1, notifications().size());

        // An offer screen with nothing readable on it: the screen has not read the offer, so the card stays.
        show(partialOffer("Store A"));
        assertEquals(1, notifications().size());
        assertEquals("a figureless animation makes no spurious screen REVIEW line", 1, DecisionLog.recent(app, 10).size());
    }

    @Test
    public void anUpdateWhileDasherIsStillOnScreenAddsNoCardOrLine() {
        listener.get().onListenerConnected();
        filter.get().onServiceConnected();
        show(idle());
        post(doorDashOffer(STORE_A));
        assertEquals(1, notifications().size());
        assertFalse("Dasher is on screen: no ring", rings(notifications().getAllNotifications().get(0)));
        show(offer("$7.90"));

        // DoorDash updates its notification as the offer ages, while the offer is still on screen.
        post(doorDashOffer("New Delivery! 0:40 left", STORE_A, System.currentTimeMillis()));
        assertEquals(0, notifications().size());
        assertEquals(1, DecisionLog.recent(app, 10).size());
    }

    @Test
    public void anUpdateAfterDasherLeftIsStillAnnouncedAsAPossibleNextOffer() {
        listener.get().onListenerConnected();
        filter.get().onServiceConnected();
        show(idle());
        post(doorDashOffer(STORE_A));
        show(offer("$7.90"));
        dasherLeaves();

        // Off screen, a changed post on the same key may be a next offer: it is announced, and rings once.
        post(doorDashOffer("New Delivery! 0:30 left", STORE_A, System.currentTimeMillis()));
        assertEquals(1, notifications().size());
        assertTrue(rings(notifications().getAllNotifications().get(0)));
        assertTrue(anyLine(DecisionLog.Source.NOTIFICATION, "CHECK_BELL"));
    }

    @Test
    public void twoOffersInQuickSuccessionEachFoldTheirOwnNotification() {
        filter.get().onServiceConnected();
        show(offer("$7.90"));
        post(doorDashOffer(STORE_A));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15));
        show(offer("$8.10"));
        post(doorDashOffer(STORE_B));

        assertEquals(0, notifications().size());
        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals(2, recent.size());
        assertEquals(DecisionLog.Source.SCREEN, recent.get(0).source);
        assertEquals(DecisionLog.Source.SCREEN, recent.get(1).source);
        assertArrayEquals(new int[] {0, 2, 0}, DecisionLog.totals(app));
    }

    @Test
    public void twoBackgroundOffersSecondsApartKeepTheirOwnLines() {
        post(doorDashOffer(STORE_A));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
        post(doorDashOffer(STORE_B));

        List<DecisionLog.Entry> recent = DecisionLog.recent(app, 10);
        assertEquals("two notifications are two offers, never one line", 2, recent.size());
        assertEquals("CHECK_BELL", recent.get(0).action.name());
        assertEquals("CHECK_BELL", recent.get(1).action.name());
        assertArrayEquals(new int[] {0, 0, 2}, DecisionLog.totals(app));
    }

    @Test
    public void aNotificationAfterDasherLeftTheScreenStillRingsOnce() {
        filter.get().onServiceConnected();
        show(offer("$7.90"));
        dasherLeaves();
        post(doorDashOffer(STORE_A));

        assertEquals(1, notifications().size());
        assertTrue("it may be a next offer: it rings", rings(notifications().getAllNotifications().get(0)));
        assertEquals(2, DecisionLog.recent(app, 10).size());
        assertTrue(anyLine(DecisionLog.Source.NOTIFICATION, "CHECK_BELL"));
    }

    // ---- The countdown on screen bounds how early its notification came ----

    @Test
    public void theCountdownOnScreenBoundsWhichNotificationAnOfferTakes() {
        filter.get().onServiceConnected();
        earlierNotification(25_000);
        // 0:45 left: this offer came at most about 17 s ago, after that notification.
        show(partialOffer("$7.90", "2 stops (7.2 mi) • 21 min", "0:45"));
        assertEquals(2, DecisionLog.recent(app, 10).size());
    }

    @Test
    public void anOlderCountdownTakesAnEarlierNotification() {
        filter.get().onServiceConnected();
        earlierNotification(25_000);
        // 0:20 left: the offer came about 40 s ago, so the notification 25 s ago is it.
        show(partialOffer("$7.90", "2 stops (7.2 mi) • 21 min", "0:20"));
        assertEquals(1, DecisionLog.recent(app, 10).size());
        assertArrayEquals(new int[] {0, 1, 0}, DecisionLog.totals(app));
    }

    @Test
    public void withoutACountdownARecentNotificationIsTaken() {
        filter.get().onServiceConnected();
        earlierNotification(20_000);
        show(offer("$7.90"));
        assertEquals(1, DecisionLog.recent(app, 10).size());
    }

    @Test
    public void withoutACountdownAnOlderNotificationIsNotTaken() {
        filter.get().onServiceConnected();
        earlierNotification(40_000);
        show(offer("$8.10"));
        assertEquals(2, DecisionLog.recent(app, 10).size());
    }

    @Test
    public void aReconnectReplayAddsNoLine() {
        listener.get().onListenerConnected();
        StatusBarNotification source = doorDashOffer(STORE_A);
        ShadowNotificationListenerService dasher = Shadow.extract(listener.get());
        dasher.addActiveNotification(source);
        post(source);
        assertEquals(1, DecisionLog.recent(app, 10).size());

        listener.get().onListenerDisconnected();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        listener.get().onListenerConnected();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("a replay re-checks the same post", 1, DecisionLog.recent(app, 10).size());
        assertEquals(1, notifications().size());
    }

    // ---- A same-store re-post on the reused key ----

    @Test
    public void theSameStoresNextOfferAfterTheScreenSawTheLastOneEndIsANewOffer() {
        listener.get().onListenerConnected();
        filter.get().onServiceConnected();
        show(idle());
        post(doorDashOffer(STORE_A));
        show(offer("$7.90"));
        show(idle());
        dasherLeaves();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));

        // DoorDash reuses its key, and the text is the same: before, this offer got no card, ring or line.
        post(doorDashOffer(STORE_A));
        assertEquals(1, notifications().size());
        assertTrue("a new offer in the background rings", rings(notifications().getAllNotifications().get(0)));
        DecisionLog.Entry newest = DecisionLog.recent(app, 1).get(0);
        assertEquals(DecisionLog.Source.NOTIFICATION, newest.source);
        assertEquals("CHECK_BELL", newest.action.name());
    }

    @Test
    public void theSameStoresNotificationOfAnOfferBeingDeclinedLetsItsConfirmationBeTapped() {
        listener.get().onListenerConnected();
        filter.get().onServiceConnected();
        // Offer A from Store A: its notification, then the screen reads it; then Dasher goes idle.
        show(idle());
        post(doorDashOffer(STORE_A));
        show(offer("$25.00"));
        show(idle());

        // The next offer, from the same store: the screen declines it at once, before its notification comes.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        show(offer("$7.90"));
        assertEquals("the first Decline tap is immediate", 1, Shadows.shadowOf(decline).getPerformedActions().size());
        // Its notification, on DoorDash's reused key, a moment later: a new incarnation of that key, but the offer
        // being declined. Before, it revoked that decline: the confirmation was never tapped.
        post(doorDashOffer(STORE_A));
        AccessibilityNodeInfo confirm = node("Decline offer", true);
        show(confirmation(confirm));

        assertEquals("Dasher's \"Are you sure?\" is tapped", 1, Shadows.shadowOf(confirm).getPerformedActions().size());
        assertFalse(FilterStore.lastStatus(app).contains("authority revoked"));
    }

    @Test
    public void aReadOffersCardStaysClearedAcrossAListenerReconnect() {
        listener.get().onListenerConnected();
        filter.get().onServiceConnected();
        StatusBarNotification source = doorDashOffer(STORE_A);
        ShadowNotificationListenerService dasher = Shadow.extract(listener.get());
        dasher.addActiveNotification(source);
        post(source);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        show(offer("$7.90"));
        dasherLeaves();
        assertEquals(0, notifications().size());
        assertEquals(1, DecisionLog.recent(app, 10).size());

        // Android reconnects the listener and replays Dasher's notification, the same post the screen read.
        listener.get().onListenerDisconnected();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        listener.get().onListenerConnected();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("no card comes back for an offer the screen read", 0, notifications().size());
        assertEquals("no REVIEW line either", 1, DecisionLog.recent(app, 10).size());
        assertArrayEquals("and no \"?\" count", new int[] {0, 1, 0}, DecisionLog.totals(app));
    }

    @Test
    public void pausingKeepsTheCardOfAPostTheScreenNeverRead() {
        listener.get().onListenerConnected();
        filter.get().onServiceConnected();
        post(doorDashOffer(STORE_A));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        show(offer("$7.90"));
        dasherLeaves();
        // A later post on the key, with Dasher off screen: perhaps a next offer, which the screen never read.
        StatusBarNotification later = doorDashOffer("New Delivery! 0:30 left", STORE_A, System.currentTimeMillis());
        ShadowNotificationListenerService dasher = Shadow.extract(listener.get());
        dasher.addActiveNotification(later);
        post(later);
        assertEquals(1, notifications().size());

        // Pausing re-checks the notifications Android shows: that card is not the screen's to clear.
        FilterStore.save(app, new FilterSettings(false, 2000, 0, 0, 0, 0));
        OfferNotificationService.rulesChanged();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the card of an offer the screen never read stays", 1, notifications().size());
    }

    @Test
    public void aChangedRepostAfterTheScreenReadTheOfferAndItsCardWentRingsOnce() {
        listener.get().onListenerConnected();
        filter.get().onServiceConnected();
        // Offer A in the background: its card rings; then the screen reads A, and its card goes.
        StatusBarNotification first = doorDashOffer(STORE_A);
        post(first);
        assertTrue(rings(notifications().getAllNotifications().get(0)));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        show(offer("$7.90"));
        dasherLeaves();
        assertEquals(0, notifications().size());

        // The same post again moments later, unchanged: the same offer, no card.
        post(doorDashOffer("New Delivery!", STORE_A, first.getPostTime() + 5_000));
        assertEquals(0, notifications().size());

        // The same store's key with changed text while Dasher is off screen: a new offer. Before, it could not ring,
        // since the card of the offer the screen read had already rung.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        StatusBarNotification next = doorDashOffer("New Delivery! Order ready", STORE_A, System.currentTimeMillis());
        ShadowNotificationListenerService dasher = Shadow.extract(listener.get());
        dasher.addActiveNotification(next);
        post(next);
        assertEquals(1, notifications().size());
        assertTrue("a new offer in the background rings once", rings(notifications().getAllNotifications().get(0)));
        assertEquals("CHECK_BELL", DecisionLog.recent(app, 1).get(0).action.name());

        // A replay of that post (a reconnect) never rings again.
        listener.get().onListenerDisconnected();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        listener.get().onListenerConnected();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        for (Notification card : notifications().getAllNotifications()) assertFalse(rings(card));
    }

    @Test
    public void anUnchangedRepostAfterAQuietGapIsANewOffer() {
        long now = System.currentTimeMillis();
        post(doorDashOffer("New Delivery!", STORE_A, now - 40_000));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        post(doorDashOffer("New Delivery!", STORE_A, now));

        assertEquals(2, DecisionLog.recent(app, 10).size());
        assertEquals(1, notifications().size());
        assertTrue(rings(notifications().getAllNotifications().get(0)));
    }

    @Test
    public void anUnchangedRepostMomentsLaterIsTheSameOffer() {
        long now = System.currentTimeMillis();
        post(doorDashOffer("New Delivery!", STORE_A, now - 5_000));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        post(doorDashOffer("New Delivery!", STORE_A, now));

        assertEquals(1, DecisionLog.recent(app, 10).size());
        assertEquals(1, notifications().size());
    }

    // ---- Dasher's own offer alert (the user's decision): no second ring when Android shows it sounded ----

    /** A loud offer channel, as Dasher's is unless the user silenced it. */
    private static NotificationChannel loudChannel() {
        return new NotificationChannel("dasher-offers", "New offers", NotificationManager.IMPORTANCE_HIGH);
    }

    /**
     * Android's ranking of {@code source}, built by reflection. From Android 10 Android says when it last alerted
     * audibly for it ({@code alertedAt}, 0 for never); before that only the post's interruption filter, importance
     * and channel can be read.
     */
    private static NotificationListenerService.RankingMap ranking(StatusBarNotification source,
            NotificationChannel channel, int importance, boolean matchesFilter, long alertedAt) throws Exception {
        String key = source.getKey();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
            ReflectionHelpers.setField(ranking, "mKey", key);
            ReflectionHelpers.setField(ranking, "mChannel", channel);
            ReflectionHelpers.setField(ranking, "mImportance", importance);
            ReflectionHelpers.setField(ranking, "mMatchesInterruptionFilter", matchesFilter);
            ReflectionHelpers.setField(ranking, "mLastAudiblyAlertedMs", alertedAt);
            return ReflectionHelpers.callConstructor(NotificationListenerService.RankingMap.class,
                    ReflectionHelpers.ClassParameter.from(NotificationListenerService.Ranking[].class,
                            new NotificationListenerService.Ranking[] {ranking}));
        }
        // Android 8: the ranking comes from a ranking update; a key that does not match the filter is intercepted.
        android.os.Bundle channels = new android.os.Bundle();
        channels.putParcelable(key, channel);
        Class<?> updateClass = Class.forName("android.service.notification.NotificationRankingUpdate");
        Object update = ReflectionHelpers.callConstructor(updateClass,
                ReflectionHelpers.ClassParameter.from(String[].class, new String[] {key}),
                ReflectionHelpers.ClassParameter.from(String[].class,
                        matchesFilter ? new String[0] : new String[] {key}),
                ReflectionHelpers.ClassParameter.from(android.os.Bundle.class, new android.os.Bundle()),
                ReflectionHelpers.ClassParameter.from(android.os.Bundle.class, new android.os.Bundle()),
                ReflectionHelpers.ClassParameter.from(int[].class, new int[] {importance}),
                ReflectionHelpers.ClassParameter.from(android.os.Bundle.class, new android.os.Bundle()),
                ReflectionHelpers.ClassParameter.from(android.os.Bundle.class, new android.os.Bundle()),
                ReflectionHelpers.ClassParameter.from(android.os.Bundle.class, channels),
                ReflectionHelpers.ClassParameter.from(android.os.Bundle.class, new android.os.Bundle()),
                ReflectionHelpers.ClassParameter.from(android.os.Bundle.class, new android.os.Bundle()),
                ReflectionHelpers.ClassParameter.from(android.os.Bundle.class, new android.os.Bundle()));
        @SuppressWarnings({"unchecked", "rawtypes"})
        ReflectionHelpers.ClassParameter<?> fromUpdate = new ReflectionHelpers.ClassParameter(updateClass, update);
        return ReflectionHelpers.callConstructor(NotificationListenerService.RankingMap.class, fromUpdate);
    }

    /** Dasher's offer, ranked as Android ranks a post that sounded: loud channel, filter matched, alerted now. */
    private void postSounded(StatusBarNotification source) throws Exception {
        listener.get().onNotificationPosted(source, ranking(source, loudChannel(),
                NotificationManager.IMPORTANCE_HIGH, true, source.getPostTime()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void whenAndroidShowsDashersOwnAlertSoundedTheCardIsPostedWithoutRinging() throws Exception {
        assertNotNull("a channel has the default sound unless it is turned off", loudChannel().getSound());
        postSounded(doorDashOffer(STORE_A));

        assertEquals("the card stays", 1, notifications().size());
        assertFalse("Dasher's own alert is heard: no second ring", rings(notifications().getAllNotifications().get(0)));
        assertEquals("DASHER_SOUNDS", DecisionLog.recent(app, 1).get(0).action.name());
        assertTrue(DecisionLog.report(app, 1).contains("Dasher's own offer alert sounds"));
        assertTrue("Settings has actual post evidence", FilterStore.doorDashChannelAlerts(app));
    }

    @Test
    public void aNativeAlertStillConsumesTheRingWhenOurReplacementWasBlocked() throws Exception {
        notifications().setNotificationsEnabled(false);
        postSounded(doorDashOffer(STORE_A));
        assertEquals(0, notifications().size());
        notifications().setNotificationsEnabled(true);
        post(doorDashOffer("New Delivery! 0:45 left", STORE_A, System.currentTimeMillis()));
        assertEquals(1, notifications().size());
        assertFalse("Android already sounded for this offer", rings(notifications().getAllNotifications().get(0)));
    }

    @Test
    public void aPeekNavigationCardCannotRingAgainForAnAlreadyAnnouncedOffer() {
        listener.get().onListenerConnected();
        post(doorDashOffer(STORE_A));
        assertTrue(rings(notifications().getAllNotifications().get(0)));
        String tag = app.getSystemService(NotificationManager.class).getActiveNotifications()[0].getTag();
        OfferSnapshot read = new OfferSnapshot(2500, 7.2, 21, 2);
        OfferNotificationService.readOnScreen(tag, read);
        OfferNotificationService.peekCard(app, read, OfferRule.Result.KEEP, "$25.00 passes");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, notifications().size());
        assertFalse("navigation's observed card shares the original ring budget",
                rings(notifications().getAllNotifications().get(0)));
    }

    @Test
    public void underDoNotDisturbTheCardStillRingsOnce() throws Exception {
        StatusBarNotification source = doorDashOffer(STORE_A);
        // Do Not Disturb holds Dasher's post back, so Android never alerted for it, loud channel or not.
        listener.get().onNotificationPosted(source, ranking(source, loudChannel(),
                NotificationManager.IMPORTANCE_HIGH, false, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, notifications().size());
        assertTrue(rings(notifications().getAllNotifications().get(0)));
        assertEquals("CHECK_BELL", DecisionLog.recent(app, 1).get(0).action.name());
        assertFalse("configured channel sound cannot create a mandatory Fix", FilterStore.doorDashChannelAlerts(app));
    }

    @Test
    public void aPostAndroidDemotedStillRingsOnce() throws Exception {
        StatusBarNotification source = doorDashOffer(STORE_A);
        // The channel is loud, but Android ranks this post below default importance: it made no sound.
        listener.get().onNotificationPosted(source, ranking(source, loudChannel(),
                NotificationManager.IMPORTANCE_LOW, true, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, notifications().size());
        assertTrue(rings(notifications().getAllNotifications().get(0)));
        assertEquals("CHECK_BELL", DecisionLog.recent(app, 1).get(0).action.name());
    }

    @Test
    public void anOnlyAlertOnceUpdateThatIsANewOfferStillRingsOnce() throws Exception {
        long now = System.currentTimeMillis();
        StatusBarNotification first = doorDashOffer("New Delivery!", STORE_A, now - 40_000);
        postSounded(first);
        assertFalse(rings(notifications().getAllNotifications().get(0)));

        // DoorDash re-posts its key with only-alert-once: a new offer after the quiet gap, but Android stays silent
        // for an update of a notification it still shows (its last audible alert is the first post's).
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        Notification payload = new Notification.Builder(app, "source")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText(STORE_A)
                .setOnlyAlertOnce(true)
                .build();
        StatusBarNotification update = new StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp", 3,
                "NEW_ORDER", 10001, 0, 0, payload, android.os.Process.myUserHandle(), now);
        listener.get().onNotificationPosted(update, ranking(update, loudChannel(),
                NotificationManager.IMPORTANCE_HIGH, true, first.getPostTime()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, notifications().size());
        assertTrue("Dasher's update made no sound, so ours rings", rings(notifications().getAllNotifications().get(0)));
        assertEquals("CHECK_BELL", DecisionLog.recent(app, 1).get(0).action.name());
    }

    @Test
    @Config(sdk = 26)
    public void beforeAndroid10AGroupChildThatAlertsThroughItsSummaryStillRingsOnce() throws Exception {
        Notification payload = new Notification.Builder(app, "source")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText(STORE_A)
                .setGroup("offers")
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .build();
        StatusBarNotification source = new StatusBarNotification("com.doordash.driverapp", "com.doordash.driverapp",
                3, "NEW_ORDER", 10001, 0, 0, payload, android.os.Process.myUserHandle(), System.currentTimeMillis());
        listener.get().onNotificationPosted(source, ranking(source, loudChannel(),
                NotificationManager.IMPORTANCE_HIGH, true, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, notifications().size());
        assertTrue("a child alerting through its summary makes no sound",
                rings(notifications().getAllNotifications().get(0)));
    }

    @Test
    public void whenDashersOfferChannelIsSilentTheCardStillRingsOnce() throws Exception {
        NotificationChannel offers = loudChannel();
        offers.setSound(null, null);
        StatusBarNotification source = doorDashOffer(STORE_A);
        listener.get().onNotificationPosted(source, ranking(source, offers, NotificationManager.IMPORTANCE_HIGH,
                true, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, notifications().size());
        assertTrue(rings(notifications().getAllNotifications().get(0)));
        assertEquals("CHECK_BELL", DecisionLog.recent(app, 1).get(0).action.name());
    }
}
