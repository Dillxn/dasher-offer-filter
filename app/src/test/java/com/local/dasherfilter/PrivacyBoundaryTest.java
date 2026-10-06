package com.local.dasherfilter;

import android.app.Application;
import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;

/** Synthetic privacy regressions only; no recovered diagnostic text or real payment details. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class PrivacyBoundaryTest {
    private Application app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Updater.setEnabled(app, false);
        DiagnosticLog.forgetCache();
        DiagnosticLog.clear(app);
        DiagnosticLog.setEnabled(app, true);
        DecisionLog.forgetCache();
        DecisionLog.clear(app);
    }

    @After public void cleanup() {
        DiagnosticLog.forgetCache();
    }

    @Test public void paymentScreenNeverReachesEitherLogOrDecisionEvidence() {
        String card = "Card details, 4111 1111 1111 1111, CVV, 123, Available balance $123.45";
        DiagnosticLog.logScreen(app, card);
        DiagnosticLog.log(app, "screen", card);
        String combined = DiagnosticLog.readScreens(app) + DiagnosticLog.read(app);
        assertFalse(combined.contains("4111"));
        assertFalse(combined.contains("123.45"));
        assertFalse(combined.contains("CVV"));
        assertTrue(combined.contains(DiagnosticLog.NOT_KEPT));
        assertTrue(DecisionLog.evidence(Arrays.asList("Card details", "$123.45", "4111 1111 1111 1111")).isEmpty());
    }

    @Test public void cleanupPurgesBothLegacyLogsAndQueueOnce() throws Exception {
        DiagnosticLog.read(app); // finish the writer before simulating a previous installation
        app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE).edit()
                .remove(DiagnosticLog.CLEANED_UP).commit();
        File screens = new File(app.getFilesDir(), "dasher-screens.log");
        File offers = new File(app.getFilesDir(), "offer-filter-diagnostics.log");
        Files.write(screens.toPath(), "Card details, CVV 123\n".getBytes(StandardCharsets.UTF_8));
        Files.write(offers.toPath(), ("old [screen] labels=[Card details, $123.45]\n"
                + "old [screen] labels=[$7.50, 2 stops, Deliver to Robin Q]\n").getBytes(StandardCharsets.UTF_8));
        File queue = new File(app.getFilesDir(), "report-outbox");
        queue.mkdirs();
        File pending = new File(queue, "legacy-d.json");
        Files.write(pending.toPath(), "{\"body\":\"CVV 123\"}".getBytes(StandardCharsets.UTF_8));
        DiagnosticLog.forgetCache();
        DiagnosticLog.cleanUpOnce(app);
        assertFalse(screens.exists());
        assertFalse(pending.exists());
        String cleaned = new String(Files.readAllBytes(offers.toPath()), StandardCharsets.UTF_8);
        assertFalse(cleaned.contains("$123.45"));
        assertFalse(cleaned.contains("Robin"));
        assertFalse(cleaned.contains("$7.50"));
        assertTrue(cleaned.contains(DiagnosticLog.CLEANED_UP_LINE));
        Files.write(screens.toPath(), "new safe dash".getBytes(StandardCharsets.UTF_8));
        DiagnosticLog.forgetCache();
        DiagnosticLog.cleanUpOnce(app);
        assertEquals("new safe dash", new String(Files.readAllBytes(screens.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void partialPaymentScreenWithoutHeadingKeepsOnlyStructure() {
        assertFalse(PersonalText.recognizedDashScreen(Arrays.asList("731", "12/34", "Copy")));
        OfferFilterService.scanLooperForTests = android.os.Looper.getMainLooper();
        org.robolectric.android.controller.ServiceController<OfferFilterService> service =
                org.robolectric.Robolectric.buildService(OfferFilterService.class).create();
        try {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));
            Dashing.seen(app);
            android.view.accessibility.AccessibilityNodeInfo root = node("");
            for (String label : new String[] {"731", "12/34", "Copy"}) {
                org.robolectric.Shadows.shadowOf(root).addChild(node(label));
            }
            org.robolectric.Shadows.shadowOf(service.get()).setRootInActiveWindow(root);
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName("com.doordash.driverapp");
            service.get().onAccessibilityEvent(event);
            String captured = DiagnosticLog.readScreens(app) + DiagnosticLog.read(app);
            assertTrue(captured, captured.contains(PersonalText.UNKNOWN_NOT_KEPT));
            assertFalse(captured.contains("12/34"));
            assertFalse(captured.contains("labels=[731"));
            assertFalse(captured.contains("Copy"));
        } finally {
            service.destroy();
            OfferFilterService.scanLooperForTests = null;
        }
    }

    @Test public void oneControlOnPartialPaymentPageCannotPersistItsAmountThroughStatus() {
        FilterStore.save(app, new FilterSettings(true, 2000, 0, 0, 0, 0));
        OfferFilterService.scanLooperForTests = android.os.Looper.getMainLooper();
        org.robolectric.android.controller.ServiceController<OfferFilterService> service =
                org.robolectric.Robolectric.buildService(OfferFilterService.class).create();
        try {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));
            Dashing.seen(app);
            android.view.accessibility.AccessibilityNodeInfo root = node("");
            android.view.accessibility.AccessibilityNodeInfo accept = null;
            for (String label : new String[] {"Accept", "$123.45", "731", "12/34", "Copy"}) {
                android.view.accessibility.AccessibilityNodeInfo child = node(label);
                if (label.equals("Accept")) {
                    accept = child;
                    child.setClickable(true);
                    child.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                }
                org.robolectric.Shadows.shadowOf(root).addChild(child);
            }
            TestWindows.full(service.get(), root);
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName("com.doordash.driverapp");
            service.get().onAccessibilityEvent(event);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            String status = FilterStore.lastStatus(app);
            assertTrue(status, status.contains("Both offer controls are not yet readable"));
            String captured = status + DiagnosticLog.readScreens(app) + DiagnosticLog.read(app);
            assertFalse(captured, captured.contains("123.45"));
            assertFalse(captured, captured.contains("12/34"));
            assertTrue(org.robolectric.Shadows.shadowOf(accept).getPerformedActions().isEmpty());
            assertTrue(DecisionLog.recent(app, 10).isEmpty());
        } finally {
            service.destroy();
            OfferFilterService.scanLooperForTests = null;
        }
    }

    private android.view.accessibility.AccessibilityNodeInfo node(String label) {
        android.view.accessibility.AccessibilityNodeInfo node =
                android.view.accessibility.AccessibilityNodeInfo.obtain(new android.view.View(app));
        node.setPackageName("com.doordash.driverapp");
        node.setText(label);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        return node;
    }

    @Test public void failedCleanupCannotMarkDoneReadOldTextOrSendReports() throws Exception {
        DiagnosticLog.read(app);
        app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE).edit()
                .remove(DiagnosticLog.CLEANED_UP).commit();
        File blocked = new File(app.getFilesDir(), "dasher-screens.log");
        blocked.delete();
        assertTrue(blocked.mkdir());
        File child = new File(blocked, "synthetic-private.txt");
        Files.write(child.toPath(), "731 12/34".getBytes(StandardCharsets.UTF_8));
        DiagnosticLog.forgetCache();
        assertFalse(DiagnosticLog.cleanUpOnce(app));
        assertFalse(app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE)
                .getBoolean(DiagnosticLog.CLEANED_UP, false));
        assertEquals("Diagnostics paused: privacy cleanup incomplete.", DiagnosticLog.readScreens(app));
        assertTrue(child.delete());
        assertTrue(DiagnosticLog.cleanUpOnce(app));
        assertFalse(blocked.exists());
    }

    @Test public void anExistingUnlistableOutboxFailsCleanupClosed() throws Exception {
        DiagnosticLog.read(app);
        File queue = new File(app.getFilesDir(), "report-outbox");
        if (queue.exists()) assertTrue(queue.delete());
        Files.write(queue.toPath(), "synthetic inaccessible queue".getBytes(StandardCharsets.UTF_8));
        app.getSharedPreferences("offer_filter_diagnostics", Context.MODE_PRIVATE).edit()
                .remove(DiagnosticLog.CLEANED_UP).commit();
        DiagnosticLog.forgetCache();
        assertFalse(LegacyReportingCleanup.deleteOutbox(app));
        assertFalse(DiagnosticLog.cleanUpOnce(app));
        assertTrue(queue.delete());
        assertTrue(DiagnosticLog.cleanUpOnce(app));
    }

    @Test public void offerReportsAndLegacyEvidenceRejectPaymentOrPartialScreens() throws Exception {
        java.util.List<String> payment = Arrays.asList("Card details", "CVV", "731", "$123.45");
        assertTrue(OfferReport.redact(payment).isEmpty());
        DecisionLog.Entry old = new DecisionLog.Entry(System.currentTimeMillis(), DecisionLog.Source.SCREEN,
                false, new OfferSnapshot(750, 2.0, 10, 2), 2000, OfferRule.Result.DECLINE, "synthetic",
                DecisionLog.Action.DECLINE_TAPPED, true, payment);
        String report = OfferReport.text(OfferReport.Problem.MISREAD, "test", 1, "Android test",
                new FilterSettings(true, 2000, 0, 0, 0, 0), old);
        assertFalse(report, report.contains("Card details"));
        assertFalse(report, report.contains("731"));
        assertFalse(report, report.contains("123.45"));
        assertTrue(DecisionLog.Entry.fromJson(old.toJson()).evidence.isEmpty());
    }
}
