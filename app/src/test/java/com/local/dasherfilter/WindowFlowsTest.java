package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
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
import java.util.Arrays;
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
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
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
 * The window flows of a dash (the owner: "e2e dasher flows ... so the user isn't juggling multiple windows (dasher,
 * filter, gps) and can just flow"): the card a peek leaves while navigating pops up even when Dasher's own alert
 * already rang (without a sound of its own), counts down with the offer and, for an add-on, carries the add-on's own
 * figures; the tab shows over Dasher's half beside a map; the slim bar over an offer says pass or review by its
 * colour; and after an offer that left the user in Dasher from a map ends, a Back to map chip shows for a while over
 * Dasher's wait for offers. Simulated Android only: heads-up behaviour, task placement and the map coming back as it
 * was are for a handset.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class WindowFlowsTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final Rect TOP_HALF = new Rect(0, 0, 1080, 1000);
    private static final Rect DIVIDER = new Rect(0, 1000, 1080, 1040);
    private static final Rect BOTTOM_HALF = new Rect(0, 1040, 1080, 2040);
    private static final String DASHER = "com.doordash.driverapp";
    private static final ComponentName DASHER_HOME = new ComponentName(DASHER, DASHER + ".Home");
    private static final String MAPS = "com.google.android.apps.maps";
    private static final ComponentName MAPS_HOME = new ComponentName(MAPS, MAPS + ".MapsActivity");
    private static final String OTHER = "com.example.reader";
    private static final ComponentName OTHER_HOME = new ComponentName(OTHER, OTHER + ".Main");
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
        ActiveRouteStore.clear(app);
        Dashing.forgetCache();
        OfferFilterService.forgetScreenState();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        installed(DASHER_HOME, Intent.CATEGORY_LAUNCHER);
        installed(MAPS_HOME, Intent.CATEGORY_LAUNCHER);
        installed(OTHER_HOME, Intent.CATEGORY_LAUNCHER);
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

    /** An offer as Dasher draws it, its countdown at 0:35; {@code pay} null for one whose pay does not show. */
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

    private void dasherEvent() {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER);
        event.setEventTime(SystemClock.uptimeMillis());
        screen.get().onAccessibilityEvent(event);
        idle();
    }

    private void dasherShows(AccessibilityNodeInfo root) {
        inFront(root);
        dasherEvent();
    }

    private StatusBarNotification offerNotification(String store) {
        Notification payload = new Notification.Builder(app, "dasher_offers")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText("New Order: Go to " + store)
                .build();
        return new StatusBarNotification(DASHER, DASHER, 3, "NEW_ORDER", 10001, 0, 0, payload,
                android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    /**
     * Dasher's offer notification, listed by Android with its own tap; Android says it sounded, or (as DoorDash's own
     * offer channel is on phones) that it pops up without a sound of its own.
     */
    private StatusBarNotification postTappableNative(boolean sounded) throws Exception {
        StatusBarNotification source = offerNotification("Taco Bell");
        source.getNotification().contentIntent = PendingIntent.getActivity(app, 7,
                new Intent().setComponent(DASHER_HOME), PendingIntent.FLAG_IMMUTABLE);
        ShadowNotificationListenerService nativeAlerts = Shadow.extract(listener.get());
        nativeAlerts.addActiveNotification(source);
        listener.get().onNotificationPosted(source, sounded ? SameOfferAdapterTest.ranking(source,
                new NotificationChannel("dasher_offers", "Dasher", NotificationManager.IMPORTANCE_HIGH),
                NotificationManager.IMPORTANCE_HIGH, true, source.getPostTime())
                : SameOfferAdapterTest.dashersOwnChannel(source));
        idle();
        return source;
    }

    /** A post Android does not list: our card is the way in. */
    private void post(String store) {
        listener.get().onNotificationPosted(offerNotification(store), null);
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

    private void dasherOpened() {
        pass(Peek.QUIET_MS);
        Intent opened = started();
        assertNotNull("Dasher is brought up: " + DiagnosticLog.read(app), opened);
        assertEquals(DASHER_HOME, opened.getComponent());
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

    private void touchNow() {
        long at = SystemClock.uptimeMillis();
        assertEquals("the touch watch is up", 1, touchWatches().size());
        touchWatches().get(0).dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        idle();
    }

    private BackToMapChip chip() {
        for (View view : overlays()) if (view instanceof BackToMapChip) return (BackToMapChip) view;
        return null;
    }

    private DasherTab tab() {
        for (View view : overlays()) if (view instanceof DasherTab) return (DasherTab) view;
        return null;
    }

    private List<Notification> cards() {
        return Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getAllNotifications();
    }

    /** Our card with this tag, as Android shows it. */
    private Notification posted(String tag) {
        Notification card = Shadows.shadowOf(app.getSystemService(NotificationManager.class))
                .getNotification(tag, OfferAlerts.NOTIFICATION_ID);
        assertNotNull("a card tagged " + tag, card);
        return card;
    }

    private static String text(Notification card) {
        return String.valueOf(card.extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    /** Declines the offer Dasher shows (its Decline, then its question's), as on any offer on screen. */
    private void declinedOnScreen(AccessibilityNodeInfo shown) {
        dasherShows(shown);
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        dasherShows(question(confirm));
        pass(100);
    }

    // ---- The card a peek leaves while navigating (S1, S2) ----

    @Test
    public void aPassingOfferReadWhileNavigatingPopsUpSilentlyWithItsCountdownAfterDashersAlertRang()
            throws Exception {
        connect(app(MAPS));
        postTappableNative(true);
        assertTrue("no payless card beside Dasher's own tappable notification", cards().isEmpty());
        dasherOpened();
        long before = System.currentTimeMillis();
        dasherShows(offer("$25.00"));
        assertEquals("back to the map", MAPS_HOME, started().getComponent());
        assertEquals(1, cards().size());
        Notification card = cards().get(0);
        contains(text(card), "Passes: $25.00");
        assertEquals("it pops up, with no sound of its own: Dasher's alert already rang", OfferAlerts.SHOWN_CHANNEL_ID,
                card.getChannelId());
        assertTrue("never a quiet group child", card.getGroupAlertBehavior() != Notification.GROUP_ALERT_SUMMARY);
        NotificationChannel shown = app.getSystemService(NotificationManager.class)
                .getNotificationChannel(OfferAlerts.SHOWN_CHANNEL_ID);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, shown.getImportance());
        assertNull("no sound of its own", shown.getSound());
        assertFalse("no vibration of its own", shown.shouldVibrate());
        // The offer's countdown (0:35 when read) runs on the card, which goes with the offer.
        assertTrue(card.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        assertTrue(card.extras.getBoolean("android.chronometerCountDown"));
        assertTrue("ends with the offer: " + (card.when - before), card.when >= before + 30_000
                && card.when <= System.currentTimeMillis() + 35_000);
        assertTrue(card.getTimeoutAfter() > 0 && card.getTimeoutAfter() <= 35_000);
        contains(DiagnosticLog.read(app), "shown silently");
    }

    @Test
    public void aPassingOfferReadWhileNavigatingRingsThePassChimeWhenDashersAlertWasNotHeard() throws Exception {
        connect(app(MAPS));
        postTappableNative(false);
        assertTrue("no payless card beside Dasher's own tappable notification, heard or not", cards().isEmpty());
        dasherOpened();
        dasherShows(offer("$25.00"));
        assertEquals(MAPS_HOME, started().getComponent());
        assertEquals(1, cards().size());
        Notification card = cards().get(0);
        assertEquals("the pass chime, once", OfferAlerts.CHANNEL_ID, card.getChannelId());
        assertTrue(card.getGroupAlertBehavior() != Notification.GROUP_ALERT_SUMMARY);
    }

    @Test
    public void anUnclearOfferReadWhileNavigatingPopsUpSilentlyAfterDashersAlertRang() throws Exception {
        connect(app(MAPS));
        postTappableNative(true);
        dasherOpened();
        dasherShows(offer(null));
        assertEquals(MAPS_HOME, started().getComponent());
        Notification card = cards().get(0);
        contains(text(card), "Unclear: pay not read");
        assertEquals(OfferAlerts.SHOWN_CHANNEL_ID, card.getChannelId());
    }

    /**
     * The return to the map refused (Android would not open it): Dasher stays up showing the offer, so no card pops up
     * (or rings) over it; the user has the offer in front of them.
     */
    @Test
    public void aPassingOffersCardIsNotPostedWhenTheReturnToTheMapFails() throws Exception {
        OfferFilterService service = connect(app(MAPS));
        service.peekStarter = intent -> {
            if (MAPS_HOME.equals(intent.getComponent())) throw new android.content.ActivityNotFoundException("no map");
            service.startActivity(intent);
        };
        postTappableNative(true);
        dasherOpened();
        dasherShows(offer("$25.00"));
        assertNull("Dasher stays up", started());
        contains(DiagnosticLog.read(app), "[peek] could not go back to a navigation app: Dasher stays up");
        assertTrue("no card over the offer Dasher shows", cards().isEmpty());
    }

    // ---- An add-on's card (S6) ----

    @Test
    public void aPassingAddOnsCardCarriesTheAddOnsOwnFiguresNotPayNotRead() {
        FilterStore.save(app, new FilterSettings(true, 500, 0, 0, 0, 0));
        connect(app(MAPS));
        ActiveRouteStore.save(app, new OfferSnapshot(500, 3.0, 12, 2));
        post("Taco Bell");
        dasherOpened();
        AccessibilityNodeInfo addOn = node(DASHER, null, false);
        Shadows.shadowOf(addOn).addChild(node(DASHER, "Add to route", false));
        Shadows.shadowOf(addOn).addChild(node(DASHER, "+$4.00", false));
        Shadows.shadowOf(addOn).addChild(node(DASHER, "+2 mi", false));
        Shadows.shadowOf(addOn).addChild(button("Decline"));
        Shadows.shadowOf(addOn).addChild(button("Accept"));
        Shadows.shadowOf(addOn).addChild(node(DASHER, "0:30", false));
        dasherShows(addOn);
        assertEquals("on a route: back to the map with its card", MAPS_HOME, started().getComponent());
        assertEquals("one card for the offer (its own, not a second one)", 1, cards().size());
        Notification card = cards().get(0);
        assertEquals("Add-on passes: +$4.00 · +2 mi", text(card));
        assertEquals("Taco Bell offer meets your rules",
                String.valueOf(card.extras.getCharSequence(Notification.EXTRA_TITLE)));
    }

    @Test
    public void addOnCardWordsNeverInventWhatTheAddOnDoesNotSay() {
        assertEquals("Add-on passes: +$3.50 · +2.1 mi · +8 min",
                Peek.addOnCardText(OfferRule.Result.KEEP, new OfferSnapshot(350, 2.1, 8, null), "x"));
        assertEquals("Add-on unclear: added pay not read · +2 mi (add-on details unclear)",
                Peek.addOnCardText(OfferRule.Result.REVIEW, new OfferSnapshot(null, 2.0, null, null),
                        "add-on has missing or ambiguous incremental/route evidence"));
    }

    // ---- The cards themselves ----

    @Test
    public void aCardWithoutACountdownKeepsSixtySecondsAndNoChronometer() {
        assertTrue(OfferAlerts.post(app, new OfferAlerts.Card("a", OfferRule.Result.KEEP, "Passes: $25.00")
                .shown(true)));
        Notification card = posted("a");
        assertEquals(OfferAlerts.CARD_MS, card.getTimeoutAfter());
        assertFalse(card.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        assertTrue(OfferAlerts.post(app, new OfferAlerts.Card("b", OfferRule.Result.REVIEW, "x")
                .endsAt(System.currentTimeMillis() - 1_000)));
        Notification over = posted("b");
        assertFalse("a countdown already over is not shown", over.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        assertEquals(OfferAlerts.CARD_MS, over.getTimeoutAfter());
    }

    @Test
    public void neverACardForADeclineOnAnyChannel() {
        assertFalse(OfferAlerts.post(app, new OfferAlerts.Card("d", OfferRule.Result.DECLINE, "Declined")
                .shown(true).ring(true)));
        assertTrue(cards().isEmpty());
    }

    @Test
    public void theSilentDetailsChannelIsNotPartOfReadinessAndBlockedItsCardsGoQuietlyInstead() {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        assertTrue(OfferAlerts.canNotify(app));
        NotificationChannel shown = manager.getNotificationChannel(OfferAlerts.SHOWN_CHANNEL_ID);
        shown.setImportance(NotificationManager.IMPORTANCE_NONE);
        manager.createNotificationChannel(shown);
        assertTrue("the user's choice to turn its pop-ups off is never called blocked alerts",
                OfferAlerts.canNotify(app));
        assertTrue(OfferAlerts.post(app, new OfferAlerts.Card("q", OfferRule.Result.KEEP, "Passes").shown(true)));
        Notification card = cards().get(0);
        assertEquals("its own channel, quietly", OfferAlerts.CHANNEL_ID, card.getChannelId());
        assertEquals(Notification.GROUP_ALERT_SUMMARY, card.getGroupAlertBehavior());
        // A ringing channel blocked is still alerts to fix.
        NotificationChannel review = manager.getNotificationChannel(OfferAlerts.REVIEW_CHANNEL_ID);
        review.setImportance(NotificationManager.IMPORTANCE_NONE);
        manager.createNotificationChannel(review);
        assertFalse(OfferAlerts.canNotify(app));
    }

    @Test
    public void aDetailsCardInPlaceOfAQuietOneGoesUpOnTheDetailsChannel() {
        assertTrue(OfferAlerts.notifyOffer(app, "same", null, OfferRule.Result.REVIEW, "quiet", false));
        assertEquals(Notification.GROUP_ALERT_SUMMARY, cards().get(0).getGroupAlertBehavior());
        assertTrue(OfferAlerts.post(app, new OfferAlerts.Card("same", OfferRule.Result.KEEP, "Passes: $25.00")
                .shown(true)));
        assertEquals("one card for the offer", 1, cards().size());
        Notification card = cards().get(0);
        assertEquals(OfferAlerts.SHOWN_CHANNEL_ID, card.getChannelId());
        assertTrue(card.getGroupAlertBehavior() != Notification.GROUP_ALERT_SUMMARY);
    }

    // ---- The tab beside a map (A2) and the slim bar's colour (S10) ----

    @Test
    public void theTabShowsOverDashersHalfBesideAMapButNotBesideOfferFiltersOwn() {
        connect(app(MAPS));
        OfferFilterService service = screen.get();
        AccessibilityNodeInfo waiting = finding();
        AccessibilityNodeInfo maps = app(MAPS);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, maps, true, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, waiting, false, BOTTOM_HALF)));
        Shadows.shadowOf(service).setRootInActiveWindow(maps);
        dasherEvent();
        DasherTab tab = tab();
        assertNotNull("Maps beside Dasher is a driving layout: the tab is over Dasher's half", tab);
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) tab.getLayoutParams();
        assertTrue("within Dasher's half", params.y >= BOTTOM_HALF.top && params.y < BOTTOM_HALF.bottom);

        // Offer Filter's own half beside Dasher has the mascot: no tab.
        AccessibilityNodeInfo ours = node(app.getPackageName(), "Offer Filter", false);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, finding(), false, BOTTOM_HALF)));
        Shadows.shadowOf(service).setRootInActiveWindow(ours);
        dasherEvent();
        assertNull(tab());
    }

    @Test
    public void theTabBesideAMapIsOnlyASlimUntouchablePeekOverAnOffer() {
        connect(app(MAPS));
        OfferFilterService service = screen.get();
        AccessibilityNodeInfo maps = app(MAPS);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, maps, true, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, offer("$25.00"), false, BOTTOM_HALF)));
        Shadows.shadowOf(service).setRootInActiveWindow(maps);
        dasherEvent();
        DasherTab tab = tab();
        assertNotNull(tab);
        assertEquals(DasherTab.Look.PEEK, tab.look());
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) tab.getLayoutParams();
        assertTrue("every touch over the offer is Dasher's",
                (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        assertEquals(OfferRule.Result.KEEP, tab.verdict());
    }

    @Test
    public void theSlimBarIsGreenOverAPassAmberOverReviewAndPlainOverADecline() {
        connect(finding());
        dasherShows(offer("$25.00"));
        DasherTab tab = tab();
        assertNotNull(tab);
        assertEquals(DasherTab.Look.PEEK, tab.look());
        assertEquals(OfferRule.Result.KEEP, tab.verdict());
        assertEquals(Ui.GOOD, tab.peekColor());
        contains(String.valueOf(tab.getContentDescription()), "This offer passes your rules.");

        dasherShows(offer(null));
        assertEquals(OfferRule.Result.REVIEW, tab().verdict());
        assertEquals(Ui.WARNING, tab().peekColor());

        dasherShows(offer("$7.90"));
        assertNull("a decline keeps the filter's own colour", tab().verdict());
        assertEquals(new Ui(app).accent, tab().peekColor());

        dasherShows(finding());
        assertNull("no tint away from an offer", tab().verdict());
    }

    @Test
    public void whilePausedTheBarKeepsThePausedColour() {
        FilterStore.save(app, new FilterSettings(false, 2000, 0, 0, 0, 0));
        connect(finding());
        dasherShows(offer("$25.00"));
        assertNull(tab().verdict());
        assertEquals(Ui.WARNING, tab().peekColor());
    }

    // ---- Back to map (A1) ----

    /** A peek from Maps, its offer declined, then the user touches: Dasher is left up for them. */
    private void peekLeftUpFromMaps() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        declinedOnScreen(offer("$7.90"));
        pass(100);
        touchNow();
        contains(DiagnosticLog.read(app), "[peek] left Dasher up because you touched the screen");
        contains(DiagnosticLog.read(app), "[peek] back to map: armed (a peek left Dasher up; was in front: a "
                + "navigation app)");
    }

    @Test
    public void backToMapShowsOverTheWaitForOffersAfterAPeekLeftDasherUpAndItsTapOpensTheMap() {
        peekLeftUpFromMaps();
        dasherShows(finding());
        BackToMapChip chip = chip();
        assertNotNull("Dasher's wait for offers: the way back to the map", chip);
        assertEquals("Back to map", String.valueOf(chip.getContentDescription()));
        assertTrue("a full touch target", chip.getLayoutParams().height >= new Ui(app).dp(48));
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) chip.getLayoutParams();
        assertEquals("touchable", 0, params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        contains(DiagnosticLog.read(app), "[peek] back to map: offered over Dasher's wait for offers (a navigation app)");

        chip.performClick();
        idle();
        Intent back = started();
        assertNotNull(back);
        assertEquals(MAPS_HOME, back.getComponent());
        assertEquals("as a launcher opens it, never as a user action of Dasher's", AS_A_LAUNCHER_DOES,
                back.getFlags());
        assertNull("gone once tapped", chip());
        contains(DiagnosticLog.read(app), "[peek] back to map: you tapped it; opened a navigation app");
        // The tab went with the tap: the map now in front has none of ours over it.
        assertFalse(tab().getVisibility() == View.VISIBLE);
        inFront(app(MAPS));
        pass(OfferFilterService.TAP_SETTLE_MS + OfferFilterService.WINDOW_WATCH_MS);
        DasherTab tab = tab();
        assertTrue("no tab over the map", tab == null || tab.getVisibility() != View.VISIBLE);
        for (String line : DiagnosticLog.read(app).split("\n")) {
            assertFalse("never the app's identity: " + line, line.contains(MAPS));
        }
    }

    @Test
    public void backToMapIsNeverOverAnOfferAndGoesByItselfAfterTwelveSeconds() {
        peekLeftUpFromMaps();
        dasherShows(offer("$25.00"));
        assertNull("never over an offer", chip());
        dasherShows(finding());
        assertNotNull(chip());
        pass(OfferFilterService.CHIP_SHOW_MS - 1_000);
        assertNotNull(chip());
        pass(1_500);
        assertNull("gone by itself", chip());
        contains(DiagnosticLog.read(app), "[peek] back to map: gone (shown 12 s)");
        dasherShows(finding());
        assertNull("once", chip());
    }

    @Test
    public void backToMapWaitsNoLongerThanAnOffersLifetime() {
        peekLeftUpFromMaps();
        dasherShows(offer("$25.00"));
        pass(OfferFilterService.CHIP_ARMED_MS + 1_000);
        contains(DiagnosticLog.read(app), "[peek] back to map: gone (the offer did not end in time)");
        dasherShows(finding());
        assertNull(chip());
    }

    @Test
    public void backToMapGoesWithADelivery() {
        peekLeftUpFromMaps();
        dasherShows(node(DASHER, "Deliver by 7:45 PM", false));
        assertNull(chip());
        contains(DiagnosticLog.read(app), "[peek] back to map: gone (a delivery is under way)");
        dasherShows(finding());
        assertNull(chip());
    }

    @Test
    public void backToMapGoesWhenThePhoneLocks() {
        peekLeftUpFromMaps();
        dasherShows(finding());
        assertNotNull(chip());
        Shadows.shadowOf(screen.get().getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        Shadows.shadowOf(screen.get().getSystemService(PowerManager.class)).setIsInteractive(false);
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        idle();
        dasherEvent();
        contains(DiagnosticLog.read(app), "[peek] back to map: gone (the phone locked or the screen went off)");
        Shadows.shadowOf(screen.get().getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Shadows.shadowOf(screen.get().getSystemService(PowerManager.class)).setIsInteractive(true);
        dasherShows(finding());
        assertNull("not back after the unlock", chip());
    }

    @Test
    public void backToMapGoesOnAnAcceptTap() {
        peekLeftUpFromMaps();
        AccessibilityNodeInfo next = offer("$25.00");
        dasherShows(next);
        AccessibilityEvent click = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        click.setPackageName(DASHER);
        click.setEventTime(SystemClock.uptimeMillis());
        click.getText().add("Accept");
        screen.get().onAccessibilityEvent(click);
        idle();
        dasherShows(finding());
        assertNull("an acceptance ends it", chip());
        contains(DiagnosticLog.read(app), "[peek] back to map: gone (an offer was accepted)");
    }

    @Test
    public void aPeekFromAnotherAppLeavesNoChip() {
        connect(app(OTHER));
        post("Taco Bell");
        dasherOpened();
        declinedOnScreen(offer("$7.90"));
        pass(100);
        touchNow();
        dasherShows(finding());
        assertNull("only a navigation app gets the way back", chip());
    }

    /** Peek refused (the keyboard is up) with Maps in front: the offer's card is the way in, and the user taps it. */
    private void cardTappedFromMaps() {
        OfferFilterService service = connect(app(MAPS));
        AccessibilityNodeInfo maps = app(MAPS);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, maps, true, SCREEN),
                window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, null, false, BOTTOM_HALF)));
        Shadows.shadowOf(service).setRootInActiveWindow(maps);
        post("Taco Bell");
        contains(DiagnosticLog.read(app), "[peek] skipped: the keyboard is up");
        assertEquals(1, cards().size());
        Intent tap = Shadows.shadowOf(cards().get(0).contentIntent).getSavedIntent();
        inFront(app(MAPS));
        Robolectric.buildActivity(OpenDasherActivity.class, tap).create();
        idle();
        assertEquals(DASHER_HOME, started().getComponent());
    }

    @Test
    public void aCardTappedFromMapsOffersBackToMapOnceItsOfferEnds() {
        cardTappedFromMaps();
        contains(DiagnosticLog.read(app), "[peek] back to map: armed (you opened Dasher from an offer's card; was in "
                + "front: a navigation app)");
        declinedOnScreen(offer("$7.90"));
        assertNull("not over the offer or its question", chip());
        dasherShows(finding());
        assertNotNull("the offer ended: back to the map in one tap", chip());
        chip().performClick();
        idle();
        assertEquals(MAPS_HOME, started().getComponent());
    }

    /**
     * Dasher brought up from a card shows its wait for offers first and draws the offer after (the 0.4.72 report: 3.6 s
     * later): the chip waits for the offer to show and end, never offering the map before the offer appeared.
     */
    @Test
    public void aCardTappedFromMapsOffersNoChipOverTheWaitBeforeItsOfferShows() {
        cardTappedFromMaps();
        dasherShows(finding());
        assertNull("the offer has not shown yet: no way back over the wait before it", chip());
        assertFalse(DiagnosticLog.read(app).contains("back to map: offered"));
        pass(1_000);
        dasherShows(finding());
        assertNull(chip());
        declinedOnScreen(offer("$7.90"));
        dasherShows(finding());
        assertNotNull("shown, then ended: back to the map in one tap", chip());
    }

    @Test
    public void aPeekLeftUpBeforeItsOfferShowedOffersNoChipUntilAnOfferShowsAndEnds() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        dasherShows(finding());
        pass(100);
        touchNow();
        contains(DiagnosticLog.read(app), "[peek] left Dasher up because you touched the screen");
        contains(DiagnosticLog.read(app), "[peek] back to map: armed (a peek left Dasher up; was in front: a "
                + "navigation app)");
        dasherShows(finding());
        assertNull("no offer shown yet", chip());
        declinedOnScreen(offer("$7.90"));
        dasherShows(finding());
        assertNotNull(chip());
    }

    /**
     * The chip is touchable, and an offer Dasher draws by a content change is read only after the quiet gap: a tap on
     * the chip as an offer draws under it reads the screen again first, and opens nothing over the offer.
     */
    @Test
    public void backToMapTappedAsAnOfferDrawsUnderItReadsAgainAndOpensNothing() {
        peekLeftUpFromMaps();
        dasherShows(finding());
        BackToMapChip chip = chip();
        assertNotNull(chip);
        // Dasher draws the next offer; no read has seen it yet.
        inFront(offer("$25.00"));
        chip.performClick();
        idle();
        assertNull("the map is not opened over the offer", started());
        String log = DiagnosticLog.read(app);
        contains(log, "[peek] back to map: you tapped it; not opened (Dasher no longer shows its wait for offers)");
        assertNull(chip());
        DecisionLog.Entry read = DecisionLog.recent(app, 1).get(0);
        assertEquals("the offer is read as any (and passes)", OfferRule.Result.KEEP, read.result);
        assertEquals(2500, (int) read.facts.payCents);
        // The tab went with the tap (a map may come in front): Dasher's next change, read, brings it back over the offer.
        assertFalse(tab().getVisibility() == View.VISIBLE);
        pass(OfferFilterService.TAP_SETTLE_MS);
        dasherEvent();
        assertEquals(View.VISIBLE, tab().getVisibility());
        assertEquals(OfferRule.Result.KEEP, tab().verdict());
    }

    /** Dasher changed its screen (its content), stamped with its own time. */
    private void dasherChanged() {
        AccessibilityEvent change = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        change.setPackageName(DASHER);
        change.setEventTime(SystemClock.uptimeMillis());
        screen.get().onAccessibilityEvent(change);
    }

    private static boolean takesTouches(View view) {
        return (((WindowManager.LayoutParams) view.getLayoutParams()).flags
                & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
    }

    /**
     * Whatever Dasher changes under the chip may be an offer drawing: from that change until a read sees it, the chip
     * takes no touches (every touch there is Dasher's), staying in view; a read that finds the wait for offers still
     * there gives it its touches back, and one that finds an offer takes it away.
     */
    @Test
    public void theChipTakesNoTouchesFromDashersChangeUntilAReadSeesIt() {
        peekLeftUpFromMaps();
        dasherShows(finding());
        BackToMapChip chip = chip();
        assertNotNull(chip);
        assertTrue(takesTouches(chip));
        // A change read at once (the first after a quiet spell): the read saw it, the chip takes touches.
        dasherChanged();
        idle();
        assertTrue(takesTouches(chip()));
        // The next change within the quiet gap waits for its read: no touches meanwhile, the chip still in view.
        dasherChanged();
        assertNotNull("in view: a screen that keeps changing never makes it blink", chip());
        assertFalse("nothing read of this change yet", takesTouches(chip()));
        pass(OfferFilterService.QUIET_SCAN_GAP_MS + 50);
        assertTrue("the read found the wait for offers still there", takesTouches(chip()));
        // Dasher draws an offer by a content change held for the quiet gap: never a chip taking touches over it.
        inFront(offer("$25.00"));
        dasherChanged();
        assertFalse(takesTouches(chip()));
        pass(OfferFilterService.QUIET_SCAN_GAP_MS + 50);
        assertNull("the read saw the offer: the chip goes", chip());
    }

    @Test
    public void aCardTappedFromMapsThenAnotherAppInFrontEndsTheChip() {
        cardTappedFromMaps();
        declinedOnScreen(offer("$7.90"));
        dasherShows(finding());
        assertNotNull(chip());
        // The user goes back to the map by themselves.
        inFront(app(OTHER));
        screen.get().onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle();
        pass(1_000);
        assertNull(chip());
        dasherShows(finding());
        assertNull("it went for good", chip());
    }

    // ---- The navigation list (F17) ----

    @Test
    public void googleMapsGoIsANavigationApp() {
        assertTrue(new Peek.Front(Peek.Back.APP, "com.google.android.apps.mapslite",
                new ComponentName("com.google.android.apps.mapslite", "x.Main"), 1).navigation());
        assertEquals("a navigation app", new Peek.Front(Peek.Back.APP, "com.google.android.apps.mapslite",
                new ComponentName("com.google.android.apps.mapslite", "x.Main"), 1).kind());
        assertFalse(new Peek.Front(Peek.Back.APP, OTHER, OTHER_HOME, 1).navigation());
    }
}
