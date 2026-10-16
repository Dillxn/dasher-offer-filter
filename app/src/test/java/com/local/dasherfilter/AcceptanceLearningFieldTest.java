package com.local.dasherfilter;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.Arrays;
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
import org.robolectric.shadows.ShadowSystemClock;

/**
 * The owner's 15 October 2026 accept at 4:56 AM ($9.45, 0.6 mi, 13 min, 2 stops; store and address made up), against
 * the learned bests his homepage showed then: $9.88 a stop (one order of $19.75; the chart's "Pay $21.54 ref" at
 * Autopilot's 109%), $3.73 a mile and $41.84 an hour, over his own $4.50 a stop, $2.70 a mile and $42 an hour, in area
 * mode. Its pay per stop is under the learned best and its 0.6 mi teaches no rate per mile, but its $43.62 an hour beats
 * both, so its acceptance raises the learned per hour. He asked "since I have learn on shouldn't a 109% offer raise my
 * mins, not just autopilot?": the 109% offer was his $7.45 at 4:23 AM, which beat no learned best; this one came right
 * after two of the app's declines and went uncounted ("Dasher's wait for offers wasn't seen before it"), so it taught
 * nothing. Taken with a seen Accept tap it teaches, full screen, in split screen and with Autopilot on; taken without one
 * (his case) it teaches once a frame of neither between the wait and the offer is passed over (0.6.24).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36}, instrumentedPackages = {"com.local.dasherfilter"})
@LooperMode(LooperMode.Mode.PAUSED)
public class AcceptanceLearningFieldTest {
    Application app;
    ServiceController<OfferFilterService> controller;
    AccessibilityNodeInfo accept;
    AccessibilityNodeInfo decline;
    AccessibilityNodeInfo confirm;
    /** Offer Filter's half above Dasher's, its own window active, as on the owner's screenshot. */
    boolean split;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("offer_filter", Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences(Consent.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(Consent.ACCEPTED_VERSION, Consent.VERSION).putLong(Consent.ACCEPTED_AT, 100).commit();
        Updater.setEnabled(app, false); DiagnosticLog.setEnabled(app, true); DiagnosticLog.clear(app);
        DecisionLog.forgetCache(); DecisionLog.clear(app); AutoAcceptMemory.clear(app);
        ActiveRouteStore.clear(app); OfferSilencer.forgetCache(); OfferNotificationService.forgetDeclineAction();
        FilterStore.save(app, FilterSettings.of(true, 450, 270, 70, 0));
        FilterStore.setScoreByArea(app, true);
        FilterStore.setAcceptanceLearning(app, true);
        teach(new OfferSnapshot(1975, 1.0, 8, 2));   // $9.875 a stop; too short and quick to teach a rate
        teach(new OfferSnapshot(1492, 4.0, 9, 2));   // $3.73 a mile
        teach(new OfferSnapshot(1534, 1.0, 22, 2));  // 69.7¢ a minute: $41.84 an hour
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }
    @After public void cleanup() {
        AutopilotRuntime.executorForTests = null; AutopilotRuntime.afterInputsForTests = null;
        AutopilotRuntime.wallClock = System::currentTimeMillis;
        AutopilotRuntime.forgetCache();
        controller.destroy(); OfferFilterService.nodeFetchForTests = null; OfferFilterService.forgetScreenState();
        OfferFilterService.scanLooperForTests = null; OfferFilterService.sawDasherBeside(0);
        OfferNotificationService.forgetDeclineAction();
    }
    private void teach(OfferSnapshot offer) {
        assertNotEquals(FilterStore.AcceptedLesson.STALE,
                FilterStore.recordConfirmedManualAcceptance(app, FilterStore.captureLearning(app, offer), offer));
    }
    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(new View(app));
        n.setPackageName("com.doordash.driverapp"); if (text != null) n.setText(text);
        n.setVisibleToUser(true); n.setEnabled(true); n.setClickable(clickable);
        if (clickable) {
            n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(n).setOnPerformActionListener((action, args) -> true);
        }
        return n;
    }
    private AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo n = node(null, true); Shadows.shadowOf(n).addChild(node(label, false)); return n;
    }
    private AccessibilityNodeInfo screen(String... labels) {
        AccessibilityNodeInfo root = node(null, false);
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label, false)); return root;
    }
    private void offer() {
        offer("$9.45", "2 stops (0.6 mi) • 13 min", "0:35");
    }
    private void offer(String pay, String route, String countdown) {
        AccessibilityNodeInfo root = screen(pay, "incl. tips", route, "Corner Burger", "Customer dropoff",
                "Guaranteed earnings for completing the offer.", countdown);
        decline = button("Decline"); accept = button("Accept");
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(accept); show(root);
    }
    private void waiting() { show(screen("This dash", "You're in a good place to wait for offers")); }
    /** Dasher's wait for offers as his 4:21 AM report shows it, half a second after the app's decline closed. */
    private void finding() {
        show(screen("Open Settings", "Help", "$", "0", ".", "This dash", "Finding offers..",
                "You're more likely to get offers at hotspots.", "Zone offer wait", "2-10 min", "Dash Preferences",
                "Expand Button", "Safety tools", "Sam T", "500 orders completed", "Home", "Schedule", "Account"));
    }
    /** A frame of neither as the next offer draws in: Dasher's header without its card (made up; none was kept). */
    private void drawingIn() {
        show(screen("Open Settings", "Help", "$", "0", ".", "This dash"));
    }
    private void pickup() {
        show(screen("Current dash", "Pick up by 5:04 AM", "Safety", "Help", "Pickup from", "Corner Burger",
                "12 Elm St", "Directions", "Arrived at store"));
    }
    /** An offer under his rules: the app's Decline, Dasher's question as his report shows it, its confirmation. */
    private void declinedByTheApp(String pay, String route) {
        offer(pay, route, "0:35");
        assertEquals("the app declines " + pay, 1, Shadows.shadowOf(decline).getPerformedActions().size());
        later(1_000);
        AccessibilityNodeInfo root = screen("Are you sure you want to decline this offer?",
                "Does not lower acceptance rate", "7%",
                "Maintaining a high acceptance rate helps you qualify for Dasher Rewards.");
        confirm = node("Decline offer", true);
        Shadows.shadowOf(root).addChild(confirm);
        Shadows.shadowOf(root).addChild(node("0:05", false));
        Shadows.shadowOf(root).addChild(node("View offer details", true));
        show(root);
        assertEquals("the app confirms its decline of " + pay, 1, Shadows.shadowOf(confirm).getPerformedActions().size());
        later(500);
        finding();
    }
    private static AccessibilityWindowInfo window(int id, int type, AccessibilityNodeInfo root, boolean active,
            Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(id); shadow.setType(type); if (root != null) shadow.setRoot(root);
        shadow.setActive(active); shadow.setBoundsInScreen(bounds);
        associate(root, window);
        return window;
    }
    private static void associate(AccessibilityNodeInfo node, AccessibilityWindowInfo window) {
        if (node == null) return;
        Shadows.shadowOf(node).setAccessibilityWindowInfo(window);
        for (int i = 0; i < node.getChildCount(); i++) associate(node.getChild(i), window);
    }
    private void show(AccessibilityNodeInfo root) {
        if (!split) {
            TestWindows.full(controller.get(), root);
            Shadows.shadowOf(controller.get().getWindows().get(0)).setFocused(true);
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName("com.doordash.driverapp"); controller.get().onAccessibilityEvent(event);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            return;
        }
        AccessibilityNodeInfo ours = AccessibilityNodeInfo.obtain(new View(app));
        ours.setPackageName(app.getPackageName()); ours.setVisibleToUser(true);
        Shadows.shadowOf(controller.get()).setWindows(Arrays.asList(
                window(301, AccessibilityWindowInfo.TYPE_APPLICATION, ours, true, new Rect(0, 0, 1080, 1010)),
                window(302, AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER, null, false, new Rect(0, 1010, 1080, 1040)),
                window(303, AccessibilityWindowInfo.TYPE_APPLICATION, root, false, new Rect(0, 1040, 1080, 2340))));
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(ours);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        ((ShadowAccessibilityRecord) Shadow.extract(event)).setWindowId(303);
        controller.get().onAccessibilityEvent(event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void later(long ms) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void tapAccept() {
        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        ((ShadowAccessibilityRecord) Shadow.extract(tap)).setSourceNode(accept);
        controller.get().onAccessibilityEvent(tap);
    }
    private void acceptTheFieldOffer() {
        waiting(); offer(); tapAccept();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        pickup();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    /**
     * His 4:55–4:56 AM in split screen: the wait, $7.50 and $6.70 declined by the app (the wait for offers after each),
     * a frame of neither {@code frameMs} before the $9.45, and his hand: no tap seen, the offer closing with time left
     * into its pickup.
     */
    private void takeTheFieldOfferAfterTwoDeclines(long frameMs) {
        split = true;
        finding();
        later(5_000);
        declinedByTheApp("$7.50", "2 stops (10.2 mi) • 25 min");
        later(8_000);
        declinedByTheApp("$6.70", "2 stops (6.4 mi) • 22 min");
        later(20_000);
        drawingIn();
        later(frameMs);
        offer();
        assertTrue("it passes", Shadows.shadowOf(decline).getPerformedActions().isEmpty());
        later(10_000);
        offer("$9.45", "2 stops (0.6 mi) • 13 min", "0:25");
        later(1_000);
        pickup();
        later(1_000);
    }
    private void assertPerHourLearned() {
        AcceptedFloors learned = FilterStore.load(app).accepted;
        assertEquals("its $43.62 an hour beats the $41.84 learned", 945, learned.minutePay);
        assertEquals(13, learned.minutes);
        assertEquals("pay per stop keeps its best", 1975, learned.highestPayCents);
        assertEquals("0.6 mi teaches no rate per mile", 1492, learned.milePay);
        assertArrayEquals("his own minimums stay", new int[] {450, 270, 70, 0, 0, 0}, FilterStore.load(app).minimums());
        assertTrue(DiagnosticLog.read(app).contains(
                "[learner] manual acceptance: learned; the learned minimums rose (Adopt on the homepage)"));
        assertNotNull("the homepage's note, with Adopt", FilterStore.learnedRaise(app));
    }
    private String history() {
        DecisionLog.flush();
        return DecisionLog.report(app, 10);
    }

    /** The seeded bests are the ones his homepage drew, and the offer scored 178% under them, not 109%. */
    @Test public void theSeededBestsDrawHisChartAndScoreTheOffer() {
        FilterSettings rules = FilterStore.load(app);
        assertEquals(2154, AreaScore.roundedCents(AreaScore.floors(rules, new OfferSnapshot(null, null, null, 2))
                .effectiveCents[AreaScore.PAY], 109));
        assertEquals(178, AreaScore.scorePercent(rules, new OfferSnapshot(945, 0.6, 13, 2)));
    }

    @Test public void theFieldAcceptRaisesTheLearnedPerHour() {
        acceptTheFieldOffer();
        assertPerHourLearned();
    }

    @Test public void theFieldAcceptRaisesTheLearnedPerHourInSplitScreen() {
        split = true;
        acceptTheFieldOffer();
        assertPerHourLearned();
    }

    /** Autopilot counts the same accept: its plan's floor is the offer's score, at most 150%. */
    @Test public void theFieldAcceptRaisesTheLearnedPerHourWithAutopilotOn() {
        AutopilotStore.clear(app); AutopilotRuntime.forgetCache();
        AutopilotRuntime.executorForTests = Runnable::run;
        AutopilotRuntime.wallClock = org.robolectric.shadows.ShadowSystem::currentTimeMillis;
        FilterStore.setAutopilot(app, true, FilterSettings.GOAL_PAY_FIRST);
        acceptTheFieldOffer();
        assertPerHourLearned();
        assertTrue(DiagnosticLog.read(app).contains("floor 150%"));
    }

    /** His case: no tap seen, the offer right after two of the app's declines, a frame of neither as it drew in. */
    @Test public void takenByHandRightAfterTheAppsDeclinesItCountsAndTeaches() {
        takeTheFieldOfferAfterTwoDeclines(300);
        String history = history();
        assertTrue(history, history.contains("Accepted: it closed with about 0:24 left on its countdown, and Dasher "
                + "showed a delivery screen"));
        assertFalse(history, history.contains("Dasher's wait for offers wasn't seen before it"));
        assertTrue(DiagnosticLog.read(app).contains("; wait_before=seen"));
        assertPerHourLearned();
    }

    /** A screen of neither that had stayed 5 s when the offer came still leaves it uncounted, and nothing is learned. */
    @Test public void aScreenOfNeitherThatStayedStillLeavesItUncounted() {
        takeTheFieldOfferAfterTwoDeclines(AcceptedOfferTracker.SETTLE_MS + 1_000);
        String history = history();
        assertTrue(history, history.contains(
                "Not counted from what followed: Dasher's wait for offers wasn't seen before it"));
        assertTrue(DiagnosticLog.read(app).contains("; wait_before=unclear"));
        AcceptedFloors learned = FilterStore.load(app).accepted;
        assertEquals("the learned per hour stays $41.84", 1534, learned.minutePay);
        assertEquals(22, learned.minutes);
    }
}
