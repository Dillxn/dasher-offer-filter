package com.local.dasherfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.robolectric.shadows.ShadowSystemClock;

/**
 * The owner's two reports of 16 October 2026, both screens the app "didnt read": a "+$3" offer whose ceiling a
 * lifetime "N orders completed" under it took away, through every showing, and Dasher's add-on card, which has no
 * add-on phrase ("+$9.40", "+1 stop (1.9 mi) • +7 min"). The first is declined at its total, the "+$" amount
 * included (0.6.30; in 0.6.28 below its ceiling, the two added); the second is read and judged as an add-on to the
 * route under way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public class PlusOfferAndAddOnCardTest {
    private Application app;
    private ServiceController<OfferFilterService> controller;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        // The owner's minimums then, scored by area: $4.50 a stop, $16.20 a mile, $0.70 a minute.
        FilterStore.save(app, FilterSettings.of(true, 450, 1620, 70, 0).withScoreByArea(true));
        ActiveRouteStore.clear(app);
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
        controller.destroy();
        ActiveRouteStore.clear(app);
        OfferFilterService.sawDasherBeside(0);
        OfferFilterService.scanLooperForTests = null;
    }

    @Test
    public void thePlusOfferWithALifetimeOrderCountUnderItIsDeclinedAtItsTotal() {
        AtomicInteger declines = new AtomicInteger();
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node("+$3", false));
        Shadows.shadowOf(root).addChild(button("Decline", declines));
        for (String label : new String[] {"$9.90", "incl. tips", "2 stops (2.9 mi) • 17 min", "Qdoba Mexican Eats",
                "100 Main St", "200 Oak Ave", "Customer dropoff", "Guaranteed earnings for completing the offer."}) {
            Shadows.shadowOf(root).addChild(node(label, false));
        }
        Shadows.shadowOf(root).addChild(button("Accept", new AtomicInteger()));
        // Dasher's home under the offer: a name, the lifetime count, a badge and the tabs.
        for (String label : new String[] {"0:13", "Pat", "312 orders completed", "Rising Shopper", "trailing_icon",
                "Home", "Schedule", "Account"}) {
            Shadows.shadowOf(root).addChild(node(label, false));
        }
        show(root);
        assertEquals("declined, once", 1, declines.get());
        DecisionLog.Entry line = newest();
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, line.action);
        assertEquals(Integer.valueOf(990), line.facts.payCents);
        assertEquals("area requirement", line.reason);
    }

    @Test
    public void theAddOnCardIsReadAsAnAddOnToTheRouteUnderWayAndLeftToTheUser() {
        // On a delivery: the $10.65 offer for 1.3 mi and 13 min, 2 stops, accepted 3 minutes before.
        ActiveRouteStore.save(app, new OfferSnapshot(1065, 1.3, 13, 2));
        AtomicInteger declines = new AtomicInteger();
        show(addOnCard("+$9.40", declines));
        DecisionLog.Entry line = newest();
        assertTrue("an add-on", line.addOn);
        assertEquals(OfferRule.Result.REVIEW, line.result);
        assertEquals("its added miles are unread", OfferRule.ADD_ON_UNCLEAR, line.reason);
        assertEquals("what it adds", Integer.valueOf(940), line.facts.payCents);
        assertEquals(Integer.valueOf(7), line.facts.minutes);
        assertEquals(Integer.valueOf(1), line.facts.stops);
        assertEquals("never declined", 0, declines.get());
        assertFalse(DiagnosticLog.read(app), DiagnosticLog.read(app).contains("pay not found"));
    }

    @Test
    public void anAddOnCardPayingLessThanItsMinutesAskIsDeclined() {
        ActiveRouteStore.save(app, new OfferSnapshot(2000, 1.3, 13, 2));
        AtomicInteger declines = new AtomicInteger();
        show(addOnCard("+$2.00", declines));
        assertEquals("declined, once", 1, declines.get());
        DecisionLog.Entry line = newest();
        assertTrue(line.addOn);
        assertEquals(DecisionLog.Action.DECLINE_TAPPED, line.action);
        assertEquals("7 minutes at $0.70 ask $4.90", OfferRule.ADD_ON_ECONOMICS, line.reason);
    }

    // ---- Dasher's screens ----

    /** Dasher's add-on card as the report kept it, with its controls and countdown. */
    private AccessibilityNodeInfo addOnCard(String added, AtomicInteger declines) {
        AccessibilityNodeInfo root = node(null, false);
        Shadows.shadowOf(root).addChild(node(added, false));
        Shadows.shadowOf(root).addChild(button("Decline", declines));
        Shadows.shadowOf(root).addChild(node("incl. tips", false));
        Shadows.shadowOf(root).addChild(node("+1 stop (1.9 mi) • +7 min", false));
        Shadows.shadowOf(root).addChild(node("Guaranteed earnings for completing the offer.", false));
        Shadows.shadowOf(root).addChild(button("Accept", new AtomicInteger()));
        Shadows.shadowOf(root).addChild(node("0:40", false));
        return root;
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
        if (text != null) node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        if (clickable) node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        return node;
    }

    /** A clickable control labelled {@code label} whose clicks are counted. */
    private AccessibilityNodeInfo button(String label, AtomicInteger clicks) {
        AccessibilityNodeInfo button = node(label, true);
        Shadows.shadowOf(button).setOnPerformActionListener((action, args) -> {
            clicks.incrementAndGet();
            return true;
        });
        return button;
    }

    private void show(AccessibilityNodeInfo root) {
        TestWindows.full(controller.get(), root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private DecisionLog.Entry newest() {
        DecisionLog.flush();
        return DecisionLog.recent(app, 1).get(0);
    }
}
