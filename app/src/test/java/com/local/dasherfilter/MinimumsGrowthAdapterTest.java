package com.local.dasherfilter;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.View;
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The minimums grow only where Autopilot may move its bar (0.5.1): through the real screen reader, at a safe point
 * between offers, never while an offer is up or was seen in the last 10 s, a decline or its question is under way, or
 * a peek is under way; growth due then waits for Autopilot's next safe tick. Thirty offers Autopilot judged at 108% over
 * two days, since the typical minimums took effect three days ago, make 8% due: $4.00 · $1.00/mi · $15/hr become $4.32
 * · $1.08/mi · $16.20/hr and the bar 108% becomes 100%. On the main looper with simulated time; synthetic Android, no
 * handset claimed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public final class MinimumsGrowthAdapterTest {
    private static final FilterSettings STARTER = FilterSettings.of(true, 400, 100, 25, 0);
    /** 6.6 mi and 27 min ask $6.75 of the typical minimums; $7.29 at 108%. */
    private static final String ROUTE = "2 stops (6.6 mi) • 27 min";
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2040);
    private static final String DASHER = "com.doordash.driverapp";
    private static final ComponentName DASHER_HOME = new ComponentName(DASHER, DASHER + ".Home");
    private static final String MAPS = "com.google.android.apps.maps";
    private static final ComponentName MAPS_HOME = new ComponentName(MAPS, MAPS + ".MapsActivity");
    private static final long HOUR = 3_600_000L;
    private static final String GREW = "minimums grew 8% after 30 offers above 103% on 2 days: $4.00 -> $4.32, "
            + "$1.00/mi -> $1.08/mi, $15.00/hr -> $16.20/hr (bar 108% -> 100%)";

    private Application app;
    private ServiceController<OfferFilterService> controller;
    private ServiceController<OfferNotificationService> listener;
    private OfferFilterService service;
    private long connectedAt;
    private AccessibilityNodeInfo decline;
    /** What was under way at each growth's write, as the test saw it. */
    private final List<String> unsafe = new CopyOnWriteArrayList<>();
    private boolean offerUp;
    private long offerGone = Long.MIN_VALUE / 2;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Updater.setEnabled(app, false);
        OfferAlerts.ensureChannel(app);
        DiagnosticLog.setEnabled(app, true);
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        ActiveRouteStore.clear(app);
        RestartSuppression.clear(app);
        AutoAcceptMemory.clear(app);
        OfferSilencer.forgetCache();
        AutopilotStore.clear(app);
        AutopilotRuntime.executorForTests = Runnable::run;
        AutopilotRuntime.forgetCache();
        OfferFilterService.forgetScreenState();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        // The typical minimums, set three days ago; Autopilot on at 108%.
        long wall = System.currentTimeMillis();
        FilterStore.wallClock = () -> wall - 3 * 24 * HOUR;
        FilterStore.save(app, STARTER);
        FilterStore.wallClock = System::currentTimeMillis;
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_TOP_TIER);
        assertTrue(FilterStore.commitAutopilotBar(app, FilterSettings.BAR_AT_MINIMUMS, 108));
        // Thirty offers it judged at 108% since: fifteen yesterday, fifteen in the last two hours.
        for (int i = 29; i >= 0; i--) {
            long at = wall - 10 * 60_000L - 7 * 60_000L * (i % 15) - (i >= 15 ? 24 * HOUR : 0);
            DecisionLog.record(app, new DecisionLog.Entry(at, DecisionLog.Source.SCREEN, false,
                    new OfferSnapshot(1500 + i, 6.6, 27, 2), 729, OfferRule.Result.KEEP, "passes your rules",
                    DecisionLog.Action.PASSES, true, Collections.<String>emptyList()).withBar(108, true));
        }
        AutopilotRuntime.beforeWriteForTests = () -> {
            long at = now();
            if (offerUp) unsafe.add("a growth at " + at + " with an offer up");
            else if (at - offerGone < OfferFilterService.BAR_CHANGE_QUIET_MS) {
                unsafe.add("a growth " + (at - offerGone) + " ms after an offer");
            }
        };
    }

    @After public void tearDown() {
        AutopilotRuntime.beforeWriteForTests = null;
        FilterStore.wallClock = System::currentTimeMillis;
        if (controller != null) controller.destroy();
        if (listener != null) listener.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.forgetScreenState();
    }

    // ---- An offer up, or seen moments ago ----

    @Test public void noGrowthWhileAnOfferIsUpNorInTheTenSecondsAfterItThenAtTheNextSafeTick() {
        connect(null);
        show(waiting());
        pass(5_000);
        // $9.00 meets 108% of the minimums ($7.29): left to the user, and up.
        offerUp = true;
        show(offer("$9.00", "0:35"));
        wantGrowth();
        assertNotGrown("not while an offer is up");
        pass(5_000);
        show(offer("$9.00", "0:30"));
        show(waiting());
        offerUp = false;
        offerGone = now();
        pass(5_000);
        wantGrowth();
        assertNotGrown("not within 10 s of an offer");
        assertGrowsAtTheFirstTickFrom(offerGone + OfferFilterService.BAR_CHANGE_QUIET_MS);
        assertEquals("decided at the bar it was shown at", 108, line(900).barPercent);
    }

    // ---- A decline and its question ----

    @Test public void noGrowthWhileADeclineOrItsQuestionIsUnderWayThenAtTheNextSafeTick() {
        connect(null);
        show(waiting());
        pass(15_000);
        // $5.00 is 69% of the $7.29 asked at 108%: declined at once.
        offerUp = true;
        AccessibilityNodeInfo failing = offer("$5.00", "0:35");
        List<Long> declines = taps(decline, true);
        show(failing);
        assertEquals(1, declines.size());
        wantGrowth();
        assertNotGrown("not while its Decline waits for Dasher's question");
        pass(300);
        AccessibilityNodeInfo confirm = button("Decline offer");
        List<Long> confirms = taps(confirm, true);
        // Dasher's question shows a rate above the goal: no recovery, and the growth stays due.
        show(question(confirm, "Your acceptance rate", "85%"));
        assertEquals(1, confirms.size());
        assertNotGrown("not while Dasher closes its question");
        pass(400);
        show(waiting());
        offerUp = false;
        offerGone = now();
        assertGrowsAtTheFirstTickFrom(offerGone + OfferFilterService.BAR_CHANGE_QUIET_MS);
        DecisionLog.Entry line = line(500);
        assertEquals("the decline went through as decided", DecisionLog.Action.CONFIRMATION_TAPPED, line.action);
        assertEquals(108, line.barPercent);
    }

    // ---- A peek ----

    @Test public void noGrowthWhileAPeekIsUnderWayThenAtTheNextSafeTick() {
        installed(DASHER_HOME);
        installed(MAPS_HOME);
        listen();
        connect(app(MAPS));
        pass(15_000);
        // Dasher's background notification names the store only: Peek brings Dasher up to read the offer.
        StatusBarNotification source = payless("Taco Bell");
        listener.get().onNotificationPosted(source, null);
        idle();
        pass(Peek.QUIET_MS);
        Intent opened = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull("Dasher is brought up", opened);
        assertEquals(DASHER_HOME, opened.getComponent());
        offerUp = true;
        dasherShows(node("Starting…", false));
        wantGrowth();
        assertNotGrown("not while the peek is under way");
        pass(10_000);
        assertNotGrown("not while the peek is under way");
        // The peek's deadline passes, and Dasher's notification goes.
        pass(12_000);
        assertTrue(log(), log().contains("[peek] ended because 20 s passed"));
        listener.get().onNotificationRemoved(source, null, NotificationListenerService.REASON_APP_CANCEL);
        idle();
        offerUp = false;
        offerGone = now() - OfferFilterService.BAR_CHANGE_QUIET_MS;
        assertGrowsAtTheFirstTickFrom(now());
    }

    // ---- Assertions ----

    /**
     * Nothing grows before the first of Autopilot's ticks at or after {@code safeFrom} (uptime), when nothing else is
     * under way, and that tick grows the minimums 8% and brings the bar from 108% to 100%, once.
     */
    private void assertGrowsAtTheFirstTickFrom(long safeFrom) {
        long tick = tickAtOrAfter(safeFrom);
        passUntil(tick - 1);
        assertNotGrown("nothing before Autopilot's next safe tick");
        passUntil(tick + 1);
        FilterSettings grown = FilterStore.load(app);
        assertArrayEquals(autopilotLog().toString(), new int[] {432, 108, 27, 0, 0, 0}, grown.minimums());
        assertEquals(100, grown.minimumScalePercent);
        assertEquals(1, logged(GREW));
        assertTrue(unsafe.toString(), unsafe.isEmpty());
        assertNotNull(AutopilotRuntime.growthNote(app));
        assertEquals("no commit line: the growth's own line says the bar's move", 0, commits());
        pass(120_000);
        assertEquals("once", 1, logged(GREW));
        assertArrayEquals(new int[] {432, 108, 27, 0, 0, 0}, FilterStore.load(app).minimums());
    }

    private void assertNotGrown(String why) {
        FilterSettings rules = FilterStore.load(app);
        assertArrayEquals(why, new int[] {400, 100, 25, 0, 0, 0}, rules.minimums());
        assertEquals(why, 108, rules.minimumScalePercent);
        assertEquals(why, 0, logged(GREW));
    }

    // ---- Autopilot ----

    /** A plan is asked for (a new reading, say): it finds the growth due and asks the screen reader for a commit. */
    private void wantGrowth() {
        AutopilotRuntime.requestPlan(app, AutopilotRuntime.Trigger.READING);
        idle();
        Autopilot.Plan plan = AutopilotRuntime.latest();
        assertNotNull(plan);
        assertNotNull("due", plan.growth);
    }

    /** The first of Autopilot's ticks at or after {@code at}: every 60 s from the connection. */
    private long tickAtOrAfter(long at) {
        long ticks = Math.max(1, (at - connectedAt + Autopilot.TICK_MS - 1) / Autopilot.TICK_MS);
        return connectedAt + ticks * Autopilot.TICK_MS;
    }

    private List<String> autopilotLog() {
        List<String> lines = new ArrayList<>();
        for (String line : log().split("\n")) {
            int at = line.indexOf("[autopilot] ");
            if (at >= 0) lines.add(line.substring(at + "[autopilot] ".length()));
        }
        return lines;
    }

    private int logged(String line) {
        int count = 0;
        for (String logged : autopilotLog()) if (logged.equals(line)) count++;
        return count;
    }

    private int commits() {
        int count = 0;
        for (String logged : autopilotLog()) {
            if (logged.startsWith("commit ") && logged.contains("% -> ")) count++;
        }
        return count;
    }

    private String log() {
        return DiagnosticLog.read(app);
    }

    // ---- The phone ----

    private void connect(AccessibilityNodeInfo front) {
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get();
        if (front != null) inFront(front);
        service.onServiceConnected();
        connectedAt = now();
        idle();
    }

    private void listen() {
        listener = Robolectric.buildService(OfferNotificationService.class).create();
        listener.get().onListenerConnected();
        idle();
    }

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

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        return node(DASHER, text, clickable);
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

    /** A Compose button: a clickable node without text, holding its label. */
    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo button = node(null, true);
        Shadows.shadowOf(button).addChild(node(label, false));
        return button;
    }

    /** An offer as Dasher draws it: Decline, the pay, the route line, Accept, then its countdown. */
    private AccessibilityNodeInfo offer(String pay, String countdown) {
        AccessibilityNodeInfo root = node(null, false);
        decline = button("Decline");
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node(pay, false));
        Shadows.shadowOf(root).addChild(node("incl. tips", false));
        Shadows.shadowOf(root).addChild(node(ROUTE, false));
        Shadows.shadowOf(root).addChild(button("Accept"));
        Shadows.shadowOf(root).addChild(node(countdown, false));
        return root;
    }

    /** Dasher's question after a Decline tap, with what else it shows. */
    private AccessibilityNodeInfo question(AccessibilityNodeInfo declineOffer, String... more) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Are you sure you want to decline this offer?", false));
        for (String part : more) Shadows.shadowOf(root).addChild(node(part, false));
        Shadows.shadowOf(root).addChild(declineOffer);
        Shadows.shadowOf(root).addChild(node("0:24", false));
        Shadows.shadowOf(root).addChild(button("View offer details"));
        return root;
    }

    private AccessibilityNodeInfo waiting() {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Finding offers", false));
        return root;
    }

    /** Another app's screen: its root and a label. */
    private AccessibilityNodeInfo app(String pkg) {
        AccessibilityNodeInfo root = node(pkg, null, false);
        Shadows.shadowOf(root).addChild(node(pkg, "Head north on Main St", false));
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
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
    }

    private void show(AccessibilityNodeInfo root) {
        dasherShows(root);
    }

    private void dasherShows(AccessibilityNodeInfo root) {
        inFront(root);
        service.onAccessibilityEvent(event(DASHER, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED));
        idle();
    }

    private static AccessibilityEvent event(String pkg, int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName(pkg);
        event.setEventTime(SystemClock.uptimeMillis());
        return event;
    }

    /** Records when (uptime) {@code button} is tapped; Android takes or refuses each tap. */
    private static List<Long> taps(AccessibilityNodeInfo button, boolean taken) {
        List<Long> at = new CopyOnWriteArrayList<>();
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            at.add(SystemClock.uptimeMillis());
            return taken;
        });
        return at;
    }

    /** Dasher's background notification of a new offer: the store only, no pay, miles or time. */
    private StatusBarNotification payless(String store) {
        Notification payload = new Notification.Builder(app, "dasher_offers")
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("New Delivery!")
                .setContentText("New Order: Go to " + store)
                .build();
        return new StatusBarNotification(DASHER, DASHER, 3, "NEW_ORDER", 10001, 0, 0, payload,
                android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    /** The newest screen line of the offer with this pay. */
    private DecisionLog.Entry line(int payCents) {
        DecisionLog.flush();
        for (DecisionLog.Entry entry : DecisionLog.recent(app, 100)) {
            if (entry.source == DecisionLog.Source.SCREEN && entry.facts.payCents != null
                    && entry.facts.payCents == payCents) {
                return entry;
            }
        }
        throw new AssertionError("no line for pay " + payCents + " in:\n" + DecisionLog.report(app, 40));
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static void passUntil(long at) {
        long left = at - now();
        if (left > 0) pass(left);
    }
}
