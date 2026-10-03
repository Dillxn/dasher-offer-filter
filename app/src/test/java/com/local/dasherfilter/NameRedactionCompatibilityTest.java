package com.local.dasherfilter;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
import java.util.Arrays;
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
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Isolated comparison fixtures. Every personal value below is invented. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class NameRedactionCompatibilityTest {
    private Application app;
    private ServiceController<OfferFilterService> controller;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.setEnabled(app, true);
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        DecisionLog.forgetCache();
        ReportOutbox.forgetCache();
        OfferSilencer.forgetCache();
        OfferFilterService.scanLooperForTests = Looper.getMainLooper();
        controller = Robolectric.buildService(OfferFilterService.class).create();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        Dashing.seen(app);
    }
    @After public void stop() {
        controller.destroy();
        OfferFilterService.scanLooperForTests = null;
    }
    private AccessibilityNodeInfo node(String text) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(new View(app));
        node.setPackageName("com.doordash.driverapp");
        node.setText(text);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        return node;
    }
    private void show(List<String> labels) {
        AccessibilityNodeInfo root = node("");
        for (String label : labels) Shadows.shadowOf(root).addChild(node(label));
        TestWindows.full(controller.get(), root);
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName("com.doordash.driverapp");
        controller.get().onAccessibilityEvent(event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void exactUnrecognizedVerificationScreenStillDiscardsAllWords() {
        List<String> labels = Arrays.asList("Confirm you have the correct order before drop-off.",
                "Mix-ups frequently occur at drop-off when there are multiple orders in a Dash.",
                "Avery Q.", "Fictional Market", "1 items", "Confirm");
        assertFalse(PersonalText.recognizedDashScreen(labels));
        show(labels);
        String screens = DiagnosticLog.readScreens(app);
        assertTrue("unknown-screen discard policy preserved", screens.contains(PersonalText.UNKNOWN_NOT_KEPT));
        assertFalse("unknown-screen name cannot be retained", screens.contains("Avery"));
    }
    @Test public void recognizedDeliveryVerificationKeepsStoreButMasksCustomer() {
        List<String> labels = Arrays.asList("Confirm you have the correct order before drop-off.",
                "Mix-ups frequently occur at drop-off when there are multiple orders in a Dash.",
                "Avery Q.", "Fictional Market", "1 items", "Confirm", "Complete delivery steps");
        assertTrue(PersonalText.recognizedDashScreen(labels));
        show(labels);
        String screens = DiagnosticLog.readScreens(app);
        assertTrue("recognized fixture must reach capture", screens.contains("Fictional Market"));
        assertFalse("recognized verification leaked invented customer at capture", screens.contains("Avery"));
        assertTrue(screens.contains("[name]"));
        assertFalse("manual report must remain masked", DiagnosticLog.report(app).contains("Avery"));
        assertFalse("automatic report must remain masked", DiagnosticLog.fullReport(app).contains("Avery"));
    }
    @Test public void scanCustomerNameWithinRecognizedPickupIsMasked() {
        List<String> labels = Arrays.asList("Scan customer name", "Morgan R.", "Fictional Market",
                "Complete pickup steps");
        assertTrue(PersonalText.recognizedDashScreen(labels));
        show(labels);
        String screens = DiagnosticLog.readScreens(app);
        assertTrue("recognized fixture must reach capture", screens.contains("Fictional Market"));
        assertFalse("scan prompt leaked invented customer at capture", screens.contains("Morgan"));
        assertTrue(screens.contains("[name]"));
        assertFalse("automatic report must remain masked", DiagnosticLog.fullReport(app).contains("Morgan"));
    }
    @Test public void serializedScanHeadingMasksAndPreservesStore() {
        assertEquals("labels=[Scan customer name, [name], Fictional Market]",
                PersonalText.maskLine("labels=[Scan customer name, Morgan R., Fictional Market]"));
    }
}
