package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
import org.robolectric.shadows.ShadowSystemClock;

/**
 * The 0.5.1 report's 19:15 offer: "pay at most $12.80 with its +$ amount; dollars per mile", then "Decline request
 * refused by Android", outcome YOURS. A refused first-step Decline is retried only on fresh reads, a few times, never
 * after the user's own tap on Dasher; its log says why Android refused; and what the user then does still reaches its
 * line.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class RefusedDeclineTest {
    private static final String ROUTE = "2 stops (10.6 mi) • 21 min";
    private static int windowIds = 900;
    private Application app;
    private ServiceController<OfferFilterService> controller;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        // The report's rules at 19:15: $11.50 minimum, $2.35 a mile ($24.91 for 10.6 mi), bar 100.
        FilterStore.save(app, FilterSettings.of(true, 1150, 235, 0, 0));
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        DiagnosticLog.clear(app);
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    }

    @After
    public void stop() {
        OfferFilterService.childFetchForTests = null;
        controller.destroy();
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
    }

    @Test
    public void aRefusedDeclineIsRetriedOnAFreshReadAndThenRequested() {
        AtomicInteger calls = new AtomicInteger();
        AccessibilityNodeInfo decline = button("Decline", () -> calls.incrementAndGet() > 1);
        show(plusOffer(decline, "0:35"));
        assertEquals(1, calls.get());
        assertEquals(DecisionLog.Action.DECLINE_REFUSED, newest().action);
        pass(400);
        assertEquals("one retry, on a fresh read", 2, calls.get());
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, newest().action);
    }

    @Test
    public void aPersistentlyRefusedDeclineIsTriedAtMostFourTimes() {
        AtomicInteger calls = new AtomicInteger();
        AccessibilityNodeInfo decline = button("Decline", () -> { calls.incrementAndGet(); return false; });
        show(plusOffer(decline, "0:35"));
        // Dasher's countdown ticks: a content change every half second, for six seconds.
        for (int i = 0; i < 12; i++) {
            pass(500);
            content();
        }
        assertEquals("tried at most four times, never hammered", 4, calls.get());
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("first-step Decline REFUSED (try 4/4)"));
        assertTrue(log, log.contains("left to you"));
        assertEquals(DecisionLog.Outcome.YOURS, DecisionLog.outcome(newest()));
    }

    @Test
    public void aDeclineControlDasherRedrewIsNamedInTheRefusal() {
        AccessibilityNodeInfo decline = button("Decline", () -> false);
        Shadows.shadowOf(decline).setRefreshReturnValue(false);
        show(plusOffer(decline, "0:35"));
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("Android refused the click: Dasher redrew the control after the read"));
    }

    @Test
    public void aKeyboardOverDasherIsNamedInTheRefusalAndNothingIsAskedOfAndroid() {
        AtomicInteger calls = new AtomicInteger();
        AccessibilityNodeInfo decline = button("Decline", () -> { calls.incrementAndGet(); return true; });
        AccessibilityNodeInfo root = plusOffer(decline, "0:35");
        AccessibilityWindowInfo dasher = window(AccessibilityWindowInfo.TYPE_APPLICATION, root, true,
                new Rect(0, 0, 1080, 2040));
        AccessibilityWindowInfo keyboard = window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, null, false,
                new Rect(0, 1300, 1080, 2040));
        Shadows.shadowOf(controller.get()).setWindows(Arrays.asList(dasher, keyboard));
        Shadows.shadowOf(controller.get()).setRootInActiveWindow(root);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        assertEquals(0, calls.get());
        String log = DiagnosticLog.read(app);
        assertTrue(log, log.contains("Dasher's target window is no longer visible (the keyboard covers it)"));
    }

    @Test
    public void afterARefusedDeclineTheUsersAcceptEndsTheRetries() {
        AtomicInteger calls = new AtomicInteger();
        AccessibilityNodeInfo decline = button("Decline", () -> calls.incrementAndGet() > 1);
        show(plusOffer(decline, "0:35"));
        assertEquals(1, calls.get());
        userTaps("Accept");
        for (int i = 0; i < 4; i++) {
            pass(400);
            content();
        }
        assertEquals("the offer the user is accepting is never declined after its refused try", 1, calls.get());
        assertTrue(DiagnosticLog.read(app).contains("not tried again: you tapped Dasher"));
    }

    @Test
    public void aTapOfTheUsersDuringTheRefusedReadAlsoEndsTheRetries() {
        // As at 19:15:20: the offer is up, its pay unread (a word between the "+$" amount and the total).
        AtomicInteger reviewTaps = new AtomicInteger();
        show(reviewOffer(button("Decline", () -> { reviewTaps.incrementAndGet(); return true; }), "0:40"));
        assertEquals(DecisionLog.Action.NEEDS_REVIEW, newest().action);
        AtomicInteger calls = new AtomicInteger();
        AccessibilityNodeInfo decline = button("Decline", () -> calls.incrementAndGet() > 1);
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        // As at 19:15:35: the read that finds the ceiling, the user tapping Accept while it is under way. On a phone
        // the main thread counts that tap at once and its own read waits behind this one on the scanner thread; here
        // both share one looper, so the tap is counted as the main thread counts it, mid-read.
        OfferFilterService.childFetchForTests = parent -> {
            if (once.compareAndSet(false, true)) userClickCounted();
        };
        show(plusOffer(decline, "0:25"));
        OfferFilterService.childFetchForTests = null;
        assertEquals("the read's own Decline went ahead (and Android refused it)", 1, calls.get());
        for (int i = 0; i < 4; i++) {
            pass(400);
            content();
        }
        assertEquals("no retry of the Decline once the user tapped Dasher", 1, calls.get());
        assertEquals(0, reviewTaps.get());
    }

    @Test
    public void afterARefusedDeclineTheUsersOwnDeclineStillCountsOnItsLine() {
        AccessibilityNodeInfo decline = button("Decline", () -> false);
        show(plusOffer(decline, "0:35"));
        assertEquals(DecisionLog.Action.DECLINE_REFUSED, newest().action);
        userTaps("Decline");
        pass(100);
        // The next offer: the user's Decline of the one before counts on that offer's line.
        AccessibilityNodeInfo next = node(null, false);
        Shadows.shadowOf(next).addChild(button("Decline", () -> true));
        Shadows.shadowOf(next).addChild(node("$30.00", false));
        Shadows.shadowOf(next).addChild(node("2 stops (3.0 mi) • 15 min", false));
        Shadows.shadowOf(next).addChild(node("Accept", true));
        Shadows.shadowOf(next).addChild(node("0:40", false));
        show(next);
        DecisionLog.flush();
        DecisionLog.Entry refused = null;
        for (DecisionLog.Entry entry : DecisionLog.recent(app, 10)) {
            if (entry.action == DecisionLog.Action.DECLINE_REFUSED) refused = entry;
        }
        assertTrue(DecisionLog.report(app, 10), refused != null
                && DecisionLog.hasStep(refused, DecisionLog.StepKind.DECLINE_COUNTED));
    }

    // ---- Dasher's screens ----

    /** The report's offer: a bare "+$2" beside its $10.80 total, so pay is at most $12.80 against $24.91. */
    private AccessibilityNodeInfo plusOffer(AccessibilityNodeInfo decline, String countdown) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("Very busy", false));
        Shadows.shadowOf(root).addChild(node("+$2", false));
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node("$10.80", false));
        Shadows.shadowOf(root).addChild(node("incl. tips", false));
        Shadows.shadowOf(root).addChild(node(ROUTE, false));
        Shadows.shadowOf(root).addChild(node("Guaranteed earnings for completing the offer.", false));
        Shadows.shadowOf(root).addChild(node("Accept", true));
        Shadows.shadowOf(root).addChild(node(countdown, false));
        return root;
    }

    /** The same offer as {@link #plusOffer}, a word between its "+$" amount and its total: pay not found. */
    private AccessibilityNodeInfo reviewOffer(AccessibilityNodeInfo decline, String countdown) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("+$2", false));
        Shadows.shadowOf(root).addChild(node("Peak pay", false));
        Shadows.shadowOf(root).addChild(decline);
        Shadows.shadowOf(root).addChild(node("$10.80", false));
        Shadows.shadowOf(root).addChild(node("incl. tips", false));
        Shadows.shadowOf(root).addChild(node(ROUTE, false));
        Shadows.shadowOf(root).addChild(node("Guaranteed earnings for completing the offer.", false));
        Shadows.shadowOf(root).addChild(node("Accept", true));
        Shadows.shadowOf(root).addChild(node(countdown, false));
        return root;
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
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

    /** A clickable control labelled {@code label}; Android takes or refuses each click as {@code taken} says. */
    private AccessibilityNodeInfo button(String label, BooleanSupplier taken) {
        AccessibilityNodeInfo button = node(label, true);
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> taken.getAsBoolean());
        return button;
    }

    private AccessibilityWindowInfo window(int type, AccessibilityNodeInfo root, boolean active, Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setId(windowIds++);
        shadow.setType(type);
        if (root != null) shadow.setRoot(root);
        shadow.setActive(active);
        shadow.setBoundsInScreen(bounds);
        if (root != null) associate(root, window);
        return window;
    }

    private static void associate(AccessibilityNodeInfo node, AccessibilityWindowInfo window) {
        if (node == null) return;
        Shadows.shadowOf(node).setAccessibilityWindowInfo(window);
        for (int i = 0; i < node.getChildCount(); i++) associate(node.getChild(i), window);
    }

    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(controller.get(), root);
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    private void content() {
        event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    private void event(int type) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The main thread's count of a tap of the user's on Dasher, as onAccessibilityEvent makes it. */
    private void userClickCounted() {
        try {
            java.lang.reflect.Field field = OfferFilterService.class.getDeclaredField("dasherClicks");
            field.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong) field.get(controller.get())).incrementAndGet();
        } catch (ReflectiveOperationException unavailable) {
            throw new AssertionError(unavailable);
        }
    }

    private void userTaps(String label) {
        AccessibilityEvent tap = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        tap.setPackageName("com.doordash.driverapp");
        tap.getText().add(label);
        controller.get().onAccessibilityEvent(tap);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private DecisionLog.Entry newest() {
        DecisionLog.flush();
        return DecisionLog.recent(app, 1).get(0);
    }
}
