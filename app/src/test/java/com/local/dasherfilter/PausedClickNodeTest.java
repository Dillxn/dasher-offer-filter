package com.local.dasherfilter;

import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityRecord;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.Collections;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * AGENTS ("Paused is a safe mode"): while paused nothing of Dasher's is read, "not one node and not a click's node
 * either". A click event's node is Android's lookup, which on a phone Dasher's own UI thread must serve
 * ({@code event.getSource()}); NeverStarveStressTest's paused click carries no node, so this one does, and counts every
 * lookup. A control shows the count works: reading again, the same click's node is asked for.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, shadows = PausedClickNodeTest.CountingRecord.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PausedClickNodeTest {
    /** Counts every node an event is asked for: on a phone, a call into the app that sent it. */
    @Implements(AccessibilityRecord.class)
    public static class CountingRecord extends ShadowAccessibilityRecord {
        static int sources;

        @Implementation
        @Override
        protected AccessibilityNodeInfo getSource() {
            sources++;
            return super.getSource();
        }
    }

    private static final String DASHER = "com.doordash.driverapp";
    private Application app;
    private ServiceController<OfferFilterService> controller;
    private OfferFilterService service;
    private AccessibilityNodeInfo accept;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        FilterStore.save(app, FilterSettings.of(false, 2000, 0, 0, 0));
        DiagnosticLog.clear(app);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        OfferFilterService.forgetScreenState();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        controller = Robolectric.buildService(OfferFilterService.class).create();
        service = controller.get();
        service.onServiceConnected();
        pass(0);
    }

    @After public void teardown() {
        controller.destroy();
        OfferFilterService.scanLooperForTests = null;
        OfferFilterService.forgetScreenState();
    }

    @Test public void pausedAClicksNodeIsNeverAskedFor() {
        show(offer());
        CountingRecord.sources = 0;
        click();
        pass(2_000);
        assertEquals("paused: not a click's node either", 0, CountingRecord.sources);
        assertTrue(DiagnosticLog.read(app).contains("not reading Dasher while paused (auto-decline is off)"));

        // Reading again, the same click's node is asked for (the count is real).
        FilterStore.save(app, FilterSettings.of(true, 2000, 0, 0, 0));
        OfferFilterService.requestCheckForRules();
        pass(500);
        CountingRecord.sources = 0;
        click();
        pass(2_000);
        assertTrue("reading, a click's node is looked up", CountingRecord.sources > 0);
    }

    private void click() {
        AccessibilityEvent click = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
        click.setPackageName(DASHER);
        click.getText().add("Accept");
        click.setEventTime(SystemClock.uptimeMillis());
        ((ShadowAccessibilityRecord) Shadow.extract(click)).setSourceNode(accept);
        service.onAccessibilityEvent(click);
    }

    private AccessibilityNodeInfo offer() {
        AccessibilityNodeInfo root = node(null, false);
        accept = node("Accept", true);
        Shadows.shadowOf(root).addChild(node("$25.00", false));
        Shadows.shadowOf(root).addChild(node("2 stops (7.2 mi) • 21 min", false));
        Shadows.shadowOf(root).addChild(accept);
        return root;
    }

    private AccessibilityNodeInfo node(String text, boolean clickable) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(new View(app));
        n.setPackageName(DASHER);
        n.setText(text);
        n.setVisibleToUser(true);
        n.setEnabled(true);
        n.setClickable(clickable);
        if (clickable) {
            n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Shadows.shadowOf(n).setOnPerformActionListener((action, args) -> true);
        }
        return n;
    }

    private void show(AccessibilityNodeInfo root) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(window);
        shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
        shadow.setRoot(root);
        shadow.setActive(true);
        shadow.setFocused(true);
        shadow.setBoundsInScreen(new Rect(0, 0, 1080, 2040));
        Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
        Shadows.shadowOf(service).setRootInActiveWindow(root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(DASHER);
        service.onAccessibilityEvent(event);
        pass(0);
    }

    private static void pass(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }
}
