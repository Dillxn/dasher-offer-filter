package com.local.dasherfilter;

import android.Manifest;
import android.app.ActivityOptions;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowNotificationListenerService;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Peek's recovery, from the 0.4.72 reports: Dasher brought up by its launcher sometimes never draws a background
 * offer (it showed only once the user entered split screen), Peek went back to the map 4 s after an empty Dasher
 * screen even with the offer's notification still posted, a lock ended every peek, and three empty peeks turned the
 * Settings switch off. Now a peek never goes back for want of an offer while its notification is posted; Dasher's own
 * notification tap is sent once 2.5 s after Dasher came up without it; an offer Dasher never drew gets a card that says
 * so; a lock holds the peek for the unlock (60 s) and an offer that dinged while locked is looked at once unlocked
 * (40 s); and Peek pauses itself for a while instead of turning the switch off. Simulated Android only: Dasher's
 * reaction to its own notification tap, Android's background-start rules and the unlock's timing are for a handset.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class PeekRecoveryTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final Rect TOP_HALF = new Rect(0, 0, 1080, 1000);
    private static final Rect DIVIDER = new Rect(0, 1000, 1080, 1040);
    private static final Rect BOTTOM_HALF = new Rect(0, 1040, 1080, 2040);
    private static final String DASHER = "com.doordash.driverapp";
    private static final ComponentName DASHER_HOME = new ComponentName(DASHER, DASHER + ".Home");
    /** The screen Dasher's own offer notification opens. */
    private static final ComponentName DASHER_OFFER = new ComponentName(DASHER, DASHER + ".OfferNotificationActivity");
    private static final String MAPS = "com.google.android.apps.maps";
    private static final ComponentName MAPS_HOME = new ComponentName(MAPS, MAPS + ".MapsActivity");
    private static final String OTHER = "com.example.reader";
    private static final ComponentName OTHER_HOME = new ComponentName(OTHER, OTHER + ".Main");

    private Application app;
    private ServiceController<OfferFilterService> screen;
    private ServiceController<OfferNotificationService> listener;
    private AccessibilityNodeInfo decline;
    /** Dasher's own notification taps the screen reader sent, and their start options. */
    private final List<PendingIntent> ownTaps = new CopyOnWriteArrayList<>();
    private final List<Bundle> ownTapOptions = new CopyOnWriteArrayList<>();

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
        installed(DASHER_OFFER, Intent.CATEGORY_DEFAULT);
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

    /** Dasher's wait for offers, with nothing of the offer drawn. */
    private AccessibilityNodeInfo finding() {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        Shadows.shadowOf(root).addChild(node(DASHER, "Finding offers", false));
        return root;
    }

    /** A Dasher screen holding only these labels. */
    private AccessibilityNodeInfo dasherScreen(String... labels) {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(DASHER, label, false));
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

    /** Maps above, Dasher below showing {@code dasher} (its half active), as a 50/50 split screen. */
    private void splitWithDasherBelow(AccessibilityNodeInfo dasher) {
        OfferFilterService service = screen.get();
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, app(MAPS), false, TOP_HALF),
                window(AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, DIVIDER),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, dasher, true, BOTTOM_HALF)));
        Shadows.shadowOf(service).setRootInActiveWindow(dasher);
    }

    /** Screen reading connects with {@code front} in front; Dasher's own notification taps are recorded, not sent. */
    private OfferFilterService connect(AccessibilityNodeInfo front) {
        screen = Robolectric.buildService(OfferFilterService.class).create();
        inFront(front);
        screen.get().ownTapSender = (intent, options) -> {
            ownTaps.add(intent);
            ownTapOptions.add(options == null ? Bundle.EMPTY : options);
        };
        screen.get().onServiceConnected();
        idle();
        return screen.get();
    }

    /** Dasher's window changed (a read follows). */
    private void dasherEvent() {
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER);
        screen.get().onAccessibilityEvent(event);
        idle();
    }

    private void dasherShows(AccessibilityNodeInfo root) {
        inFront(root);
        dasherEvent();
    }

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

    /** A post Android does not list (Dasher withdrew it): its offer's notification is gone. */
    private void post(String store) {
        listener.get().onNotificationPosted(offerNotification(store), null);
        idle();
    }

    /** Dasher's own tap on its offer notification: an activity intent Dasher made. */
    private PendingIntent dashersOwn() {
        PendingIntent own = PendingIntent.getActivity(app, 7, new Intent().setComponent(DASHER_OFFER),
                PendingIntent.FLAG_IMMUTABLE);
        Shadows.shadowOf(own).setCreatorPackage(DASHER);
        return own;
    }

    private ShadowNotificationListenerService nativeAlerts() {
        return Shadow.extract(listener.get());
    }

    /** Android lists {@code source} as the one notification on its key now (nothing told to the listener). */
    private void listOnly(StatusBarNotification source) {
        while (listener.get().getActiveNotifications(new String[] {source.getKey()}).length > 0) {
            listener.get().cancelNotification(source.getKey());
        }
        nativeAlerts().addActiveNotification(source);
    }

    /** Dasher's offer notification, still listed by Android, with its own tap (sounded or not). */
    private StatusBarNotification postListed(String store, boolean sounded) {
        StatusBarNotification source = offerNotification(store);
        source.getNotification().contentIntent = dashersOwn();
        listOnly(source);
        listener.get().onNotificationPosted(source, sounded ? soundedRanking(source) : null);
        idle();
        return source;
    }

    /** Android's word that Dasher's own alert for this post sounded. */
    private static android.service.notification.NotificationListenerService.RankingMap soundedRanking(
            StatusBarNotification source) {
        try {
            return SameOfferAdapterTest.ranking(source,
                    new NotificationChannel("dasher_offers", "Dasher", NotificationManager.IMPORTANCE_HIGH),
                    NotificationManager.IMPORTANCE_HIGH, true, source.getPostTime());
        } catch (Exception unavailable) {
            throw new AssertionError(unavailable);
        }
    }

    /** Dasher withdrew the offer: its notification goes. */
    private void withdraw(StatusBarNotification source) {
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

    /**
     * Until {@code at} on Peek's clock. Simulated Android's own work can move the clock on while a step idles (a frame,
     * a layout), so a step's timing is taken from what the peek itself noted, never from the test's count of steps.
     */
    private static void passTo(long at) {
        long left = at - Peek.now();
        if (left > 0) pass(left);
    }

    /** The peek as the screen reader keeps it. */
    private Peek peekState() {
        return ReflectionHelpers.getField(screen.get(), "peek");
    }

    private Intent started() {
        return Shadows.shadowOf(app).getNextStartedActivity();
    }

    /** Dasher opened after the quiet, as its launcher icon opens it. */
    private void dasherOpened() {
        pass(Peek.QUIET_MS);
        Intent opened = started();
        assertNotNull("Dasher is brought up: " + log(app), opened);
        assertEquals(DASHER_HOME, opened.getComponent());
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

    private void touchNow() {
        long at = SystemClock.uptimeMillis();
        assertEquals("the touch watch is up", 1, touchWatches().size());
        touchWatches().get(0).dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        idle();
    }

    private int cards() {
        return app.getSystemService(NotificationManager.class).getActiveNotifications().length;
    }

    /** The one card Offer Filter shows. */
    private Notification card() {
        StatusBarNotification[] shown = app.getSystemService(NotificationManager.class).getActiveNotifications();
        assertEquals("one card", 1, shown.length);
        return shown[0].getNotification();
    }

    private static String text(Notification card) {
        return String.valueOf(card.extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    private static String log(Context context) {
        return DiagnosticLog.read(context);
    }

    private static void contains(String text, String part) {
        assertTrue(part + " in:\n" + text, text.contains(part));
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    /** The seconds a log line says between {@code before} and {@code after} ("… (5.0 s after the post)"). */
    private static double secondsIn(String text, String before, String after) {
        java.util.regex.Matcher found = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(before)
                + "([0-9]+\\.[0-9])" + java.util.regex.Pattern.quote(after)).matcher(text);
        assertTrue(before + "N" + after + " in:\n" + text, found.find());
        return Double.parseDouble(found.group(1));
    }

    /** The power button: the screen goes off and the keyguard comes up. */
    private void lockScreen() {
        OfferFilterService service = screen.get();
        Shadows.shadowOf(service.getSystemService(PowerManager.class)).setIsInteractive(false);
        Shadows.shadowOf(service.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        idle();
    }

    /** The screen on over the keyguard, then the keyguard dismissed: Android says the user is present. */
    private void unlock() {
        OfferFilterService service = screen.get();
        Shadows.shadowOf(service.getSystemService(PowerManager.class)).setIsInteractive(true);
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        idle();
        Shadows.shadowOf(service.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        app.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        idle();
    }

    /** Declines the offer Dasher shows (its Decline, then its question's), as on any offer on screen. */
    private void declined(AccessibilityNodeInfo shown) {
        List<Long> declines = taps(decline);
        dasherShows(shown);
        assertEquals("declined at once, as any offer on screen", 1, declines.size());
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm);
        dasherShows(question(confirm));
        assertEquals("its question confirmed", 1, confirms.size());
        pass(100);
    }

    /** The background-start mode an options bundle carries (Android 14 and later). */
    private static int startMode(Bundle options) throws Exception {
        java.lang.reflect.Method from = ActivityOptions.class.getDeclaredMethod("fromBundle", Bundle.class);
        from.setAccessible(true);
        return ((ActivityOptions) from.invoke(null, options)).getPendingIntentBackgroundActivityStartMode();
    }

    // ---- Dasher up without the offer: Dasher's own notification tap, once ----

    @Test
    public void dasherUpWithoutTheOfferGetsItsOwnNotificationTapOnceAndThePeekWaitsForTheOffer() throws Exception {
        connect(app(MAPS));
        postListed("Taco Bell", false);
        dasherOpened();
        // Dasher's launcher resumed its task on its wait for offers, without the background offer (the 0.4.72 report).
        dasherShows(finding());
        long up = peekState().upAt();
        passTo(up + Peek.PRESENT_MS - 100);
        assertTrue("not before 2.5 s", ownTaps.isEmpty());
        passTo(up + Peek.PRESENT_MS + 100);
        assertEquals("Dasher's own notification tap, once", 1, ownTaps.size());
        assertTrue("Dasher's own intent, as its notification holds it", DasherOwnIntent.fromDasher(ownTaps.get(0)));
        if (Build.VERSION.SDK_INT >= 34) {
            assertEquals("the screen reader's right to start a screen goes with it",
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED, startMode(ownTapOptions.get(0)));
        } else {
            assertTrue("no options before Android 14", ownTapOptions.get(0).isEmpty());
        }
        String log = log(app);
        contains(log, "[peek] Dasher up 0.0 s after it was opened (win=full/-/dasher/100; Dasher windows 1; "
                + "top window active yes)");
        contains(log, "[peek] no offer 2.5 s after Dasher came up: screen=waiting; offer notification=posted");
        contains(log, "[peek] Dasher's own notification tap: requested");

        // Dasher's wait for offers 4 s and more, the offer's notification still posted: never back for want of it.
        passTo(up + 4_600);
        assertNull("never back while the offer's notification is posted", started());
        assertFalse(log(app).contains("offer withdrawn"));

        // Dasher draws the offer 2.1 s after its own tap: declined at once, and back to Maps once that completed.
        AccessibilityNodeInfo shown = offer("$7.90");
        declined(shown);
        dasherShows(finding());
        Intent back = started();
        assertNotNull("back to Maps once the decline completed: " + log(app), back);
        assertEquals(MAPS_HOME, back.getComponent());
        log = log(app);
        contains(log, "[peek] offer facts read 4.6 s after Dasher came up (2.1 s after Dasher's notification tap)");
        contains(log, "[peek] returned to a navigation app: the offer was declined");
        contains(log, "[peek] cost: ");
        contains(log, " Dasher events");
        assertEquals("never tapped again", 1, ownTaps.size());
        contains(DecisionLog.report(app, 1), "| screen (peeked) |");
    }

    @Test
    public void theFieldsLatenciesNeverSendTheUserBackBeforeTheOfferDraws() {
        // The 0.4.72 report's timing: Dasher up 2.6 s after its launch on its wait for offers, the offer 3.6 s later.
        connect(app(MAPS));
        postListed("Taco Bell", false);
        dasherOpened();
        pass(2_600);
        dasherShows(finding());
        long up = peekState().upAt();
        passTo(up + 3_600);
        assertNull(started());
        declined(offer("$7.90"));
        dasherShows(finding());
        Intent back = started();
        assertNotNull("back after the decline: " + log(app), back);
        assertEquals(MAPS_HOME, back.getComponent());
        contains(log(app), "[peek] Dasher up 2.6 s after it was opened");
        contains(log(app), "after Dasher's notification tap)");
    }

    @Test
    public void aWholePeekWithOnlyAControlOfTheOfferDrawnLeavesACardThatSaysSo() {
        connect(app(OTHER));
        postListed("Taco Bell", false);
        dasherOpened();
        // Dasher draws the offer's Accept and nothing else of it, the whole time.
        dasherShows(dasherScreen("Accept"));
        pass(Peek.MAX_MS);
        assertTrue("never over a sign of the offer", ownTaps.isEmpty());
        contains(log(app), "[peek] ended because 20 s passed");
        contains(log(app), "[peek] the offer's card after 20 s: silent");
        assertEquals(OfferNotificationService.UNSHOWN_TEXT, text(card()));
        assertNull(started());
    }

    @Test
    public void dashersAnimatedFindingOffersIsOneScreenInTheScreensLog() {
        Dashing.seen(app);
        connect(dasherScreen("Finding offers."));
        dasherEvent();
        for (String dots : new String[] {"Finding offers..", "Finding offers...", "Finding offers.", "Finding offers…"}) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(1_500));
            dasherShows(dasherScreen(dots));
        }
        String screens = DiagnosticLog.readScreens(app);
        assertEquals("its dots are one screen, not a line every second: " + screens, 1,
                count(screens, "Finding offers"));
        // Another screen still gets its line.
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_500));
        dasherShows(dasherScreen("Pick up by 2:20 AM", "Taco Place", "Directions"));
        contains(DiagnosticLog.readScreens(app), "Pick up by 2:20 AM");
    }

    @Test
    public void anOfferDasherDrawsAtOnceNeedsNoTapOfItsOwn() {
        connect(app(OTHER));
        postListed("Taco Bell", false);
        dasherOpened();
        dasherShows(offer("$25.00"));
        pass(Peek.MAX_MS);
        assertTrue(ownTaps.isEmpty());
        String log = log(app);
        contains(log, "[peek] offer facts read 0.0 s after Dasher came up (after launcher)");
        assertFalse(log.contains("own notification tap"));
    }

    @Test
    public void signsOfAnOfferBeingDrawnNeverGetDashersOwnTap() {
        // One of its controls, its items, or a screen too big to read: the offer is drawing.
        assertNoOwnTapOver(dasherScreen("Accept"), null);
        assertNoOwnTapOver(dasherScreen("Finding offers", "3 items"), null);
        AccessibilityNodeInfo big = node(DASHER, null, false);
        for (int i = 0; i < 1_600; i++) Shadows.shadowOf(big).addChild(node(DASHER, "Row " + i, false));
        assertNoOwnTapOver(big, null);
        // Dasher's headline alone: a visible offer is never replaced by Dasher's own tap.
        assertNoOwnTapOver(dasherScreen("New Delivery!"), "[peek] Dasher's own notification tap: skipped (offer showing)");
    }

    private void assertNoOwnTapOver(AccessibilityNodeInfo partial, String skipLine) {
        if (screen != null) {
            screen.destroy();
            screen = null;
        }
        DiagnosticLog.clear(app);
        ownTaps.clear();
        OfferFilterService.forgetScreenState();
        connect(app(OTHER));
        pass(Peek.GAP_MS);
        postListed("Store " + System.nanoTime(), false);
        dasherOpened();
        dasherShows(partial);
        pass(Peek.PRESENT_MS + 500);
        assertTrue("no tap of Dasher's own over a sign of the offer: " + log(app), ownTaps.isEmpty());
        if (skipLine != null) contains(log(app), skipLine);
        else assertFalse(log(app), log(app).contains("own notification tap"));
    }

    @Test
    public void dashersOwnTapIsNotSentAfterANewerOffersNotification() {
        connect(app(MAPS));
        postListed("Taco Bell", false);
        dasherOpened();
        dasherShows(finding());
        pass(1_000);
        // Another offer's notification (on a key of its own) came meanwhile.
        StatusBarNotification another = new StatusBarNotification(DASHER, DASHER, 4, "NEW_ORDER", 10001, 0, 0,
                offerNotification("Burger Barn").getNotification(), android.os.Process.myUserHandle(),
                System.currentTimeMillis());
        listener.get().onNotificationPosted(another, null);
        idle();
        pass(Peek.PRESENT_MS);
        assertTrue(ownTaps.isEmpty());
        contains(log(app), "[peek] Dasher's own notification tap: skipped (newer offer)");
    }

    @Test
    public void dashersOwnTapIsNeverSentForAReplacedPost() {
        connect(app(MAPS));
        StatusBarNotification tracked = postListed("Taco Bell", false);
        dasherOpened();
        dasherShows(finding());
        // Android lists a newer post of the key than the one handled: never a tap of a replaced notification.
        StatusBarNotification newer = notification("New Delivery!", "New Order: Go to Taco Bell",
                tracked.getPostTime() + 1_000);
        newer.getNotification().contentIntent = dashersOwn();
        listOnly(newer);
        pass(Peek.PRESENT_MS + 100);
        assertTrue(ownTaps.isEmpty());
        contains(log(app), "[peek] Dasher's own notification tap: skipped (replaced)");
        pass(Peek.MAX_MS);
        assertNull("still posted: never back for want of an offer", started());
    }

    @Test
    public void dashersOwnTapIsNotSentIntoASplitScreenAndTheSplitEndsThePeek() {
        connect(app(OTHER));
        postListed("Taco Bell", false);
        dasherOpened();
        dasherShows(finding());
        pass(500);
        // The user split the screen; Dasher's next event has not come yet.
        splitWithDasherBelow(finding());
        pass(Peek.PRESENT_MS);
        assertTrue(ownTaps.isEmpty());
        contains(log(app), "[peek] Dasher's own notification tap: skipped (split)");
        dasherEvent();
        contains(log(app), "[peek] ended because the screen was split");
        assertNull(started());
    }

    private void restart(AccessibilityNodeInfo front) {
        if (screen != null) screen.destroy();
        screen = null;
        listener.destroy();
        OfferFilterService.forgetScreenState();
        DiagnosticLog.clear(app);
        ownTaps.clear();
        while (started() != null) { /* Forget what was started before. */ }
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        idle();
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11));
        connect(front);
    }

    @Test
    public void theLockedTimeDoesNotCountTowardDashersOwnTap() {
        connect(app(MAPS));
        postListed("Taco Bell", false);
        dasherOpened();
        dasherShows(finding());
        passTo(peekState().upAt() + 2_000);
        lockScreen();
        pass(10_000);
        assertTrue("nothing is sent while locked", ownTaps.isEmpty());
        inFront(finding());
        unlock();
        contains(log(app), "[peek] resumed after unlock");
        // Its time up moved on by the locked time: 0.5 s of Dasher up and unlocked are still to come.
        long up = peekState().upAt();
        assertTrue(Peek.now() - up < Peek.PRESENT_MS);
        passTo(up + Peek.PRESENT_MS - 100);
        assertTrue("2.5 s of Dasher up and unlocked, not before", ownTaps.isEmpty());
        passTo(up + Peek.PRESENT_MS + 100);
        assertEquals(1, ownTaps.size());
    }

    // ---- An offer Dasher never draws ----

    @Test
    public void anOfferDasherNeverDrawsLeavesDasherUpWithACardThatSaysSoAndNeverPausesPeek() {
        connect(app(OTHER));
        for (int peek = 0; peek < 3; peek++) {
            if (peek > 0) {
                inFront(app(OTHER));
                pass(Peek.GAP_MS);
            }
            postListed("Store " + peek, false);
            dasherOpened();
            dasherShows(finding());
            passTo(peekState().upAt() + Peek.PRESENT_MS + 100);
            assertEquals(peek + 1, ownTaps.size());
            long tapped = peekState().ownTapAt();
            passTo(tapped + Peek.OWN_TAP_WAIT_MS - 100);
            String line = "[peek] offer never showed (launcher, Dasher's notification tap); left Dasher up; card silent";
            assertEquals("not before 5 s after its own tap", peek, count(log(app), line));
            passTo(tapped + Peek.OWN_TAP_WAIT_MS + 100);
            assertEquals(peek + 1, count(log(app), line));
            assertNull("Dasher stays up for the user", started());
        }
        Notification card = card();
        assertEquals(OfferNotificationService.UNSHOWN_TEXT, text(card));
        Intent tap = Shadows.shadowOf(card.contentIntent).getSavedIntent();
        assertTrue("its tap tries Dasher's own notification first",
                tap.getBooleanExtra(OpenDasherActivity.EXTRA_PREFER_DASHER_OWN, false));
        assertNull("offers Dasher never drew never pause Peek", Peek.pausedWhy(app));
        assertTrue(FilterStore.peek(app));
    }

    @Test
    public void whileNavigatingAnOfferDasherNeverDrawsGoesBackToTheMapAndItsCardRingsOnce() throws Exception {
        connect(app(MAPS));
        postListed("Taco Bell", false);
        dasherOpened();
        dasherShows(finding());
        passTo(peekState().upAt() + Peek.PRESENT_MS + 100);
        assertEquals(1, ownTaps.size());
        long tapped = peekState().ownTapAt();
        passTo(tapped + Peek.OWN_TAP_WAIT_MS - 100);
        assertNull(started());
        passTo(tapped + Peek.OWN_TAP_WAIT_MS + 100);
        Intent back = started();
        assertNotNull("back to the map: " + log(app), back);
        assertEquals(MAPS_HOME, back.getComponent());
        String log = log(app);
        contains(log, "[peek] returned to a navigation app: the offer never showed");
        contains(log, "[peek] offer never showed (launcher, Dasher's notification tap); returned to a navigation app; "
                + "card rang");
        Notification card = card();
        assertEquals(OfferNotificationService.UNSHOWN_TEXT, text(card));

        // Its tap sends Dasher's own notification intent first (the user's tap: Dasher may open from it).
        Intent tap = Shadows.shadowOf(card.contentIntent).getSavedIntent();
        Robolectric.buildActivity(OpenDasherActivity.class, tap).create();
        idle();
        Intent opened = started();
        assertNotNull(opened);
        assertEquals("Dasher's own screen for its notification", DASHER_OFFER, opened.getComponent());
        assertNull("not its launcher as well", started());
        contains(log(app), "[alert] card tapped → opened Dasher (its own notification's screen, first)");
        assertEquals("the card is cleared", 0, cards());
    }

    @Test
    public void anUnshownOfferWhoseDasherAlertSoundedGetsASilentCard() {
        connect(app(MAPS));
        postListed("Taco Bell", true);
        dasherOpened();
        dasherShows(finding());
        pass(Peek.PRESENT_MS + Peek.OWN_TAP_WAIT_MS + 100);
        assertEquals(MAPS_HOME, started().getComponent());
        contains(log(app), "returned to a navigation app; card silent");
        assertEquals(OfferNotificationService.UNSHOWN_TEXT, text(card()));
    }

    @Test
    public void aWholePeekWithTheOfferNeverReadAndItsNotificationPostedLeavesACardThatSaysSo() {
        connect(app(OTHER));
        postListed("Taco Bell", false);
        dasherOpened();
        // Dasher's headline only, the whole time: its own tap is not sent over it, and the peek's time runs out.
        dasherShows(dasherScreen("New Delivery!"));
        pass(Peek.MAX_MS);
        String log = log(app);
        contains(log, "[peek] ended because 20 s passed");
        contains(log, "[peek] the offer's card after 20 s: silent");
        assertEquals(OfferNotificationService.UNSHOWN_TEXT, text(card()));
        assertNull(Peek.pausedWhy(app));
    }

    // ---- Withdrawn offers, and Peek's pause ----

    @Test
    public void anOfferWithdrawnWhileDasherShowsItsWaitGoesBackOnceItsNotificationIsGone() {
        connect(app(MAPS));
        StatusBarNotification source = postListed("Taco Bell", false);
        dasherOpened();
        dasherShows(finding());
        long up = peekState().upAt();
        passTo(up + Peek.PRESENT_MS + 100);
        assertEquals(1, ownTaps.size());
        passTo(up + 3_600);
        withdraw(source);
        assertNull(started());
        passTo(up + Peek.NO_OFFER_MS + 100);
        Intent back = started();
        assertNotNull("back once the offer's notification is gone: " + log(app), back);
        assertEquals(MAPS_HOME, back.getComponent());
        contains(log(app), "[peek] returned to a navigation app: offer withdrawn: no offer showed within 4 s of "
                + "Dasher's screen, and its notification is gone");
    }

    /** A peek whose offer Dasher withdrew (its notification never listed): back to Maps after 4 s. */
    private void withdrawnPeek(String store) {
        inFront(app(MAPS));
        pass(Peek.GAP_MS);
        post(store);
        dasherOpened();
        dasherShows(finding());
        pass(Peek.NO_OFFER_MS + 200);
        Intent back = started();
        assertNotNull("back after the withdrawn offer: " + log(app), back);
        assertEquals(MAPS_HOME, back.getComponent());
    }

    @Test
    public void threeWithdrawnOffersInARowPausePeekForAWhileWithoutTouchingTheSwitch() {
        connect(app(MAPS));
        withdrawnPeek("Store A");
        withdrawnPeek("Store B");
        assertNull(Peek.pausedWhy(app));
        withdrawnPeek("Store C");
        String why = "3 offers in a row were gone by the time Dasher showed";
        assertEquals(why, Peek.pausedWhy(app));
        assertTrue("the Settings switch stays the user's own", FilterStore.peek(app));
        contains(log(app), "[peek] paused: " + why + "; Peek works again at your next dash or in 15 minutes");

        // Paused: the next offer is not peeked at.
        inFront(app(MAPS));
        pass(Peek.GAP_MS);
        post("Store D");
        assertNull(started());
        contains(log(app), "[peek] skipped: Peek is paused for now (" + why + ")");

        // 15 minutes on, Peek works again by itself.
        pass(Peek.PAUSE_MS);
        post("Store E");
        dasherOpened();
        contains(log(app), "[peek] pause over: 15 minutes passed; Peek works again");
        assertNull(Peek.pausedWhy(app));
    }

    @Test
    public void enteringSplitScreenDuringAPeekEndsItNeitherCountedNorBeginningTheCountAfresh() {
        connect(app(MAPS));
        withdrawnPeek("Store A");
        withdrawnPeek("Store B");
        // The third peek: the user splits the screen with Dasher before any offer shows (the user's own way).
        inFront(app(MAPS));
        pass(Peek.GAP_MS);
        post("Store C");
        dasherOpened();
        dasherShows(finding());
        pass(1_000);
        splitWithDasherBelow(finding());
        dasherEvent();
        contains(log(app), "[peek] ended because the screen was split");
        pass(Peek.MAX_MS + Peek.GAP_MS);
        assertNull("Dasher stays as it is", started());
        assertNull("a split is not an empty peek", Peek.pausedWhy(app));
        // The user is back in Maps alone, long enough for Dasher's half to be forgotten.
        inFront(app(MAPS));
        pass(OfferFilterService.BESIDE_MS + 1_000);
        // A third withdrawn offer pauses Peek: the split did not begin the count afresh either.
        withdrawnPeek("Store D");
        assertEquals("3 offers in a row were gone by the time Dasher showed", Peek.pausedWhy(app));
    }

    @Test
    public void twoLaunchesDasherNeverCameUpForPausePeek() {
        connect(app(MAPS));
        for (int i = 0; i < 2; i++) {
            if (i > 0) pass(Peek.GAP_MS);
            post("Store " + i);
            dasherOpened();
            pass(Peek.OPEN_MS + 200);
            assertEquals(i + 1, count(log(app), "[peek] ended: Dasher did not come up within 6 s (the phone may "
                    + "block apps opening from the background)"));
        }
        assertEquals("Dasher did not come up for 2 peeks in a row (the phone may block apps opening from the "
                + "background)", Peek.pausedWhy(app));
        assertTrue(FilterStore.peek(app));
    }

    @Test
    public void touchesAndLocksNeverPausePeek() {
        connect(app(MAPS));
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                inFront(app(MAPS));
                pass(Peek.GAP_MS);
            }
            post("Store " + i);
            dasherOpened();
            dasherShows(finding());
            pass(500);
            touchNow();
        }
        for (int i = 3; i < 6; i++) {
            inFront(app(MAPS));
            pass(Peek.GAP_MS);
            post("Store " + i);
            dasherOpened();
            dasherShows(finding());
            pass(500);
            lockScreen();
            pass(Peek.RESUME_MS + 100);
            unlock();
        }
        assertEquals(3, count(log(app), "[peek] left Dasher up because you touched the screen"));
        assertEquals(3, count(log(app), "[peek] ended: not unlocked within 60 s"));
        assertNull(Peek.pausedWhy(app));
        assertTrue(FilterStore.peek(app));
    }

    // ---- The lock ----

    @Test
    public void aLockDuringAPeekHoldsItAndAnUnlockInTimeResumesItFromAFreshRead() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        dasherShows(finding());
        pass(1_000);
        lockScreen();
        contains(log(app), "[peek] held for the unlock: the screen went off or the phone locked (kept 60 s)");
        assertTrue("nothing of it watches while locked", touchWatches().isEmpty());
        pass(20_000);

        // Dasher drew the offer meanwhile: the read at the unlock declines it as any offer on screen.
        AccessibilityNodeInfo shown = offer("$7.90");
        List<Long> declines = taps(decline);
        inFront(shown);
        unlock();
        contains(log(app), "[peek] resumed after unlock");
        long resumed = peekState().resumedAt();
        assertEquals("declined at once on the fresh read", 1, declines.size());
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm);
        dasherShows(question(confirm));
        assertEquals(1, confirms.size());
        pass(100);
        dasherShows(finding());
        if (Peek.now() < resumed + Peek.QUIET_MS - 50) {
            assertNull("not back before the touch watch saw the phone quiet after the unlock", started());
        }
        passTo(resumed + Peek.QUIET_MS + 200);
        Intent back = started();
        assertNotNull("back once quiet: " + log(app), back);
        assertEquals(MAPS_HOME, back.getComponent());
        contains(log(app), "[peek] returned to a navigation app: the offer was declined");
    }

    @Test
    public void anOfferDeclinedBeforeTheLockGoesBackAfterTheUnlockOnceThePhoneIsQuiet() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        declined(offer("$7.90"));
        // The phone locks before Dasher closes its question.
        lockScreen();
        contains(log(app), "[peek] held for the unlock");
        pass(10_000);
        inFront(finding());
        unlock();
        long resumed = peekState().resumedAt();
        assertTrue(Peek.now() < resumed + Peek.QUIET_MS - 100);
        assertNull("not back before the quiet after the unlock", started());
        contains(log(app), "[peek] resumed after unlock");
        passTo(resumed + Peek.QUIET_MS + 200);
        Intent back = started();
        assertNotNull("back once quiet: " + log(app), back);
        assertEquals(MAPS_HOME, back.getComponent());
        contains(log(app), "[peek] returned to a navigation app: the offer was declined");
    }

    @Test
    public void aTouchAfterTheUnlockLeavesDasherUp() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        dasherShows(finding());
        pass(1_000);
        lockScreen();
        pass(5_000);
        inFront(finding());
        unlock();
        contains(log(app), "[peek] resumed after unlock");
        pass(100);
        touchNow();
        contains(log(app), "[peek] left Dasher up because you touched the screen");
        pass(Peek.MAX_MS);
        assertNull(started());
    }

    @Test
    public void aPeekHeldLongerThanAMinuteEndsAndDasherStaysAsItIs() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        dasherShows(finding());
        pass(1_000);
        lockScreen();
        pass(Peek.RESUME_MS + 100);
        contains(log(app), "[peek] ended: not unlocked within 60 s");
        inFront(finding());
        unlock();
        assertFalse(log(app).contains("resumed after unlock"));
        pass(Peek.MAX_MS);
        assertNull(started());
        assertNull(Peek.pausedWhy(app));
    }

    @Test
    public void aLaunchTheLockCameBetweenGoesOnAfterTheUnlockAndRingsTheCardWhenNeverUnlocked() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        // Locked before Dasher's window ever came up.
        lockScreen();
        contains(log(app), "[peek] held for the unlock");
        pass(5_000);
        AccessibilityNodeInfo shown = offer("$7.90");
        List<Long> declines = taps(decline);
        inFront(shown);
        unlock();
        contains(log(app), "[peek] resumed after unlock");
        contains(log(app), "[peek] Dasher up ");
        assertEquals("declined at once", 1, declines.size());

        // Another launch the lock came between, never unlocked within a minute: its card rings once.
        restartWithoutTaps();
        post("Burger Barn");
        dasherOpened();
        assertEquals(0, count(log(app), "audibleRequested=true"));
        lockScreen();
        pass(Peek.RESUME_MS + 100);
        contains(log(app), "[peek] ended: not unlocked within 60 s");
        assertEquals("its card rings once, as for a launch Dasher never came up for", 1,
                count(log(app), "audibleRequested=true"));
    }

    private void restartWithoutTaps() {
        restart(app(MAPS));
    }

    @Test
    public void aTouchWhileDasherOpenedBeforeTheLockIsNeverForgivenAtTheUnlock() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        pass(100);
        touchNow();
        lockScreen();
        pass(3_000);
        inFront(offer("$7.90"));
        unlock();
        contains(log(app), "[peek] ended at the unlock: you touched the screen as Dasher opened");
        pass(Peek.MAX_MS);
        assertNull(started());
    }

    // ---- An offer that dinged while the phone was locked ----

    @Test
    public void anOfferThatDingedWhileLockedIsPeekedAtWhenUnlockedInTime() {
        connect(app(MAPS));
        lockScreen();
        postListed("Taco Bell", false);
        assertNull(started());
        String log = log(app);
        contains(log, "[peek] skipped: the screen is off");
        contains(log, "[peek] offer arrived while locked; waiting for unlock");
        pass(5_000);
        inFront(app(MAPS));
        unlock();
        double after = secondsIn(log(app), "[peek] unlocked in time: checking it (", " s after the post)");
        assertTrue("about 5 s after the post: " + after, after >= 5.0 && after < 7.0);
        assertNull("the quiet starts after the unlock", started());
        dasherOpened();
        contains(log(app), "[peek] opening Dasher for Taco Bell (was in front: a navigation app)");
    }

    @Test
    public void anOfferWithdrawnOrReplacedWhileLockedIsNotLookedAtAfterTheUnlock() {
        connect(app(MAPS));
        lockScreen();
        StatusBarNotification source = postListed("Taco Bell", false);
        withdraw(source);
        inFront(app(MAPS));
        unlock();
        contains(log(app), "[peek] unlocked too late: offer notification gone");
        pass(Peek.QUIET_MS + 100);
        assertNull(started());

        // A newer post of the key Android lists: not the post that dinged.
        restart(app(MAPS));
        lockScreen();
        StatusBarNotification first = postListed("Taco Bell", false);
        StatusBarNotification newer = notification("New Delivery!", "New Order: Go to Taco Bell",
                first.getPostTime() + 1_000);
        listOnly(newer);
        inFront(app(MAPS));
        unlock();
        contains(log(app), "[peek] unlocked too late: offer notification gone");
        pass(Peek.QUIET_MS + 100);
        assertNull(started());
    }

    @Test
    public void anOfferUnlockedForMoreThan40SecondsAfterItsPostIsLeftToItsCard() {
        connect(app(MAPS));
        lockScreen();
        postListed("Taco Bell", false);
        pass(41_000);
        inFront(app(MAPS));
        unlock();
        double after = secondsIn(log(app), "[peek] unlocked too late: ", " s after the post");
        assertTrue("41 s after the post: " + after, after >= 41.0 && after < 43.0);
        pass(Peek.QUIET_MS + 100);
        assertNull(started());
    }

    @Test
    public void everyOtherRefusalStillAppliesAfterTheUnlock() {
        connect(app(MAPS));
        lockScreen();
        postListed("Taco Bell", false);
        pass(3_000);
        // Unlocked straight into Dasher: the user sees the offer there.
        inFront(finding());
        unlock();
        contains(log(app), "[peek] unlocked in time: checking it");
        contains(log(app), "[peek] skipped: Dasher is on screen");
        pass(Peek.QUIET_MS + 100);
        assertNull(started());
    }

    // ---- Arming ----

    @Test
    public void theQuietIsCountedFromWhenTheTouchWatchCameUp() {
        OfferFilterService service = connect(app(MAPS));
        post("Taco Bell");
        long armedAt = ReflectionHelpers.<Peek>getField(service, "peek").armedAt();
        // Android put the watch up late: about 300 ms after the peek was armed.
        ReflectionHelpers.setField(service, "watchState", 0);
        pass(300);
        ((Runnable) ReflectionHelpers.getField(service, "syncWatch")).run();
        long upAfter = Peek.now() - armedAt;
        pass(Peek.QUIET_MS - 100);
        assertNull("not before 700 ms of quiet the watch could see", started());
        pass(200);
        Intent opened = started();
        assertNotNull(log(app), opened);
        assertEquals(DASHER_HOME, opened.getComponent());
        assertTrue(upAfter >= 300);
        contains(log(app), "[peek] touch watch up after " + upAfter + " ms");
    }

    @Test
    public void noWatchByTheEndOfTheQuietWaitIsNoPeek() {
        OfferFilterService service = connect(app(MAPS));
        post("Taco Bell");
        ReflectionHelpers.setField(service, "watchState", 0);
        pass(Peek.QUIET_WAIT_MS + 200);
        assertNull(started());
        contains(log(app), "[peek] skipped: the touch watch was not ready");
        assertEquals("its card rings once", 1, count(log(app), "audibleRequested=true"));
    }

    @Test
    public void aSlowLookupBeforeArmingNeverCountsTowardTheQuiet() {
        OfferFilterService service = connect(app(MAPS));
        Supplier<List<AccessibilityWindowInfo>> windows = service.windowSource;
        AtomicBoolean slow = new AtomicBoolean();
        service.windowSource = () -> {
            boolean starting = Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("startPeek"));
            // Another app's root took a second to come.
            if (starting && slow.compareAndSet(false, true)) ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
            return windows.get();
        };
        long[] launchedAt = {-1};
        java.util.function.Consumer<Intent> start = service.peekStarter;
        service.peekStarter = intent -> {
            launchedAt[0] = SystemClock.uptimeMillis();
            start.accept(intent);
        };
        post("Taco Bell");
        assertTrue(slow.get());
        long watchUp = ReflectionHelpers.<Long>getField(service, "watchReadyAt");
        pass(Peek.QUIET_MS + 200);
        assertTrue("Dasher opened", launchedAt[0] > 0);
        assertTrue("700 ms of quiet the watch saw, not " + (launchedAt[0] - watchUp),
                launchedAt[0] - watchUp >= Peek.QUIET_MS);
    }

    // ---- The return ----

    @Test
    public void aHeadsUpAtTheTopAsPeekGoesBackDoesNotKeepTheUserInDasher() {
        OfferFilterService service = connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        declined(offer("$7.90"));
        Supplier<List<AccessibilityWindowInfo>> windows = service.windowSource;
        AtomicBoolean shown = new AtomicBoolean();
        service.windowSource = () -> {
            List<AccessibilityWindowInfo> listed = new ArrayList<>(windows.get());
            if (Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("peekBackNow"))) {
                shown.set(true);
            }
            // Another app's heads-up slides in at the top edge: not active, over a sliver of Dasher.
            if (shown.get()) {
                listed.add(window(AccessibilityWindowInfo.TYPE_SYSTEM, null, false, new Rect(0, 0, 1080, 200)));
            }
            return listed;
        };
        dasherShows(finding());
        assertTrue(shown.get());
        Intent back = started();
        assertNotNull("back to Maps all the same: " + log(app), back);
        assertEquals(MAPS_HOME, back.getComponent());
    }

    @Test
    public void theShadeOverDasherAsPeekGoesBackLeavesDasherUp() {
        OfferFilterService service = connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        declined(offer("$7.90"));
        AccessibilityNodeInfo shade = node("com.android.systemui", "Notifications", false);
        Supplier<List<AccessibilityWindowInfo>> windows = service.windowSource;
        service.windowSource = () -> {
            List<AccessibilityWindowInfo> listed = new ArrayList<>(windows.get());
            if (Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("peekBackNow"))) {
                listed.add(window(AccessibilityWindowInfo.TYPE_SYSTEM, shade, true, SCREEN));
            }
            return listed;
        };
        dasherShows(finding());
        assertNull("never back over the shade", started());
        contains(log(app), "[peek] ended because the foreground windows changed before returning");
    }

    @Test
    public void aCancelledReturnLooksAgainAtOnceSoDasherIsKnownToBeUp() {
        OfferFilterService service = connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        declined(offer("$7.90"));
        pass(100);
        Supplier<List<AccessibilityWindowInfo>> windows = service.windowSource;
        AtomicBoolean touched = new AtomicBoolean();
        service.windowSource = () -> {
            boolean finalCheck = Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("returnPeekOnMain"));
            if (finalCheck && touched.compareAndSet(false, true)) {
                long at = SystemClock.uptimeMillis();
                touchWatches().get(0).dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_OUTSIDE, 0, 0,
                        0));
            }
            return windows.get();
        };
        dasherShows(finding());
        assertTrue(touched.get());
        assertNull(started());
        contains(log(app), "[peek] return cancelled");
        assertTrue("Dasher is known to be up at once, not at its next event", OfferFilterService.isDasherForeground());
    }

    // ---- What the log says ----

    @Test
    public void dashersOwnWindowChangesAfterTheLaunchAreNamedByTheirClassAFewAtMost() {
        connect(app(MAPS));
        post("Taco Bell");
        dasherOpened();
        for (int i = 0; i < OfferFilterService.LAUNCH_WINDOW_LINES + 2; i++) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName(DASHER);
            event.setClassName(DASHER + ".ui.Screen" + i);
            screen.get().onAccessibilityEvent(event);
            idle();
        }
        AccessibilityEvent maps = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        maps.setPackageName(MAPS);
        maps.setClassName(MAPS + ".MapsActivity");
        screen.get().onAccessibilityEvent(maps);
        idle();
        String log = log(app);
        for (int i = 0; i < OfferFilterService.LAUNCH_WINDOW_LINES; i++) {
            contains(log, "[peek] Dasher window: Screen" + i);
        }
        assertFalse(log.contains("Screen" + OfferFilterService.LAUNCH_WINDOW_LINES));
        assertFalse("never another app's", log.contains("MapsActivity"));
        assertFalse(log.contains(DASHER + ".ui"));
    }

    @Test
    public void payNotFoundBesideBothControlsSaysTheShapesOfTheMoneyLabelsOnce() {
        AccessibilityNodeInfo root = node(DASHER, null, false);
        Shadows.shadowOf(root).addChild(button("Decline"));
        for (String piece : new String[] {"$", "9", ".", "10"}) Shadows.shadowOf(root).addChild(node(DASHER, piece, false));
        Shadows.shadowOf(root).addChild(node(DASHER, "2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(button("Accept"));
        connect(root);
        dasherEvent();
        pass(1_000);
        dasherEvent();
        String line = "[screen] pay not found: money labels whole=0 bare-$=1 digits=2 dot=1; controls=both; "
                + "facts=distance,duration,stops";
        assertEquals(log(app), 1, count(log(app), line));
    }

    @Test
    public void dasherResizedIntoAHalfSaysWhenTheOfferShowed() {
        connect(finding());
        dasherEvent();
        pass(500);
        splitWithDasherBelow(finding());
        dasherEvent();
        pass(600);
        splitWithDasherBelow(offer("$25.00"));
        dasherEvent();
        double later = secondsIn(log(app), "[split] Dasher resized 100%→49%; offer facts appeared ", " s later");
        assertTrue("0.6 s and what simulated Android's own frames took: " + later, later >= 0.6 && later < 1.5);

        // Back to the whole screen with the offer up: it was up already.
        inFront(offer("$25.00"));
        dasherEvent();
        contains(log(app), "[split] Dasher resized 49%→100%; offer facts already up");

        // Into a half again from Dasher's wait for offers, and no offer comes.
        pass(1_000);
        inFront(finding());
        dasherEvent();
        pass(1_000);
        splitWithDasherBelow(finding());
        dasherEvent();
        pass(OfferFilterService.RESIZE_FACTS_MS + 100);
        contains(log(app), "[split] Dasher resized 100%→49%; offer facts none within 5 s");
    }

    @Test
    public void whileDasherStaysInFrontTheWindowWatchAsksAndroidOnlyNeverDashersRoot() {
        screen = Robolectric.buildService(OfferFilterService.class).create();
        OfferFilterService service = screen.get();
        TestWindows.full(service, finding());
        service.onServiceConnected();
        idle();
        dasherEvent();
        assertTrue(OfferFilterService.isDasherForeground());
        int fetches = service.rootFetches;
        pass(5_000);
        assertEquals("no root of Dasher's is asked for while Android's list stays as it was", fetches,
                service.rootFetches);
        assertTrue(OfferFilterService.isDasherForeground());
        // Maps comes to the front: Android's list changes, and the watch looks.
        TestWindows.full(service, app(MAPS));
        pass(600);
        assertTrue(service.rootFetches > fetches);
        assertFalse(OfferFilterService.isDasherForeground());
    }

    // ---- A card's tap ----

    /** The keyboard was up, so no peek: the offer's card rang. Its tap opens Dasher by its launcher. */
    private String cardTapped() {
        OfferFilterService service = connect(app(MAPS));
        AccessibilityNodeInfo maps = app(MAPS);
        Shadows.shadowOf(service).setWindows(Arrays.asList(
                window(AccessibilityWindowInfo.TYPE_APPLICATION, maps, true, SCREEN),
                window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, null, false, BOTTOM_HALF)));
        Shadows.shadowOf(service).setRootInActiveWindow(maps);
        postListed("Taco Bell", false);
        contains(log(app), "[peek] skipped: the keyboard is up");
        Notification card = card();
        Intent tap = Shadows.shadowOf(card.contentIntent).getSavedIntent();
        assertFalse(tap.getBooleanExtra(OpenDasherActivity.EXTRA_PREFER_DASHER_OWN, false));
        inFront(app(MAPS));
        Robolectric.buildActivity(OpenDasherActivity.class, tap).create();
        idle();
        Intent opened = started();
        assertNotNull(opened);
        assertEquals("Dasher's launcher, as before", DASHER_HOME, opened.getComponent());
        contains(log(app), "[alert] card tapped → opened Dasher (launcher)");
        return tap.getStringExtra(OpenDasherActivity.EXTRA_CARD);
    }

    @Test
    public void aCardsDasherUpWithoutTheOfferGetsDashersOwnTapOnce() {
        cardTapped();
        dasherShows(finding());
        Object open = ReflectionHelpers.getField(screen.get(), "cardOpen");
        assertNotNull("the card's Dasher is watched", open);
        long up = ReflectionHelpers.<Long>getField(open, "upAt");
        passTo(up + Peek.PRESENT_MS - 100);
        assertTrue(ownTaps.isEmpty());
        passTo(up + Peek.PRESENT_MS + 100);
        assertEquals(1, ownTaps.size());
        assertTrue(DasherOwnIntent.fromDasher(ownTaps.get(0)));
        contains(log(app), "[alert] card: Dasher's own notification tap: requested");
        pass(10_000);
        dasherEvent();
        pass(Peek.PRESENT_MS + 100);
        assertEquals("once", 1, ownTaps.size());
    }

    @Test
    public void aCardsDasherShowingTheOfferNeedsNoTapOfItsOwn() {
        cardTapped();
        dasherShows(offer("$25.00"));
        pass(Peek.PRESENT_MS + 500);
        assertTrue(ownTaps.isEmpty());
        assertFalse(log(app).contains("card: Dasher's own notification tap"));
    }

    @Test
    public void aCardsDasherTheUserTappedGetsNoTapOfItsOwn() {
        cardTapped();
        dasherShows(finding());
        pass(1_000);
        AccessibilityEvent click = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        click.setPackageName(DASHER);
        screen.get().onAccessibilityEvent(click);
        idle();
        pass(Peek.PRESENT_MS);
        assertTrue(ownTaps.isEmpty());
        contains(log(app), "[alert] card: Dasher's own notification tap: skipped (you tapped Dasher)");
    }

    @Test
    public void aCardsOfferWhoseNotificationWentGetsNoTapOfDashersOwn() {
        cardTapped();
        dasherShows(finding());
        StatusBarNotification[] listed = listener.get().getActiveNotifications();
        for (StatusBarNotification source : listed) withdraw(source);
        pass(Peek.PRESENT_MS + 500);
        assertTrue(ownTaps.isEmpty());
        contains(log(app), "[alert] card: Dasher's own notification tap: skipped (notification gone)");
    }
}
